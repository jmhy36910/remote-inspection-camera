package tw.com.upr.remoteinspection

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Line-delimited JSON control channel for the Windows controller. */
class NetworkControlServer(
    private val port: Int = 8765,
    private val onCommand: (JSONObject) -> JSONObject,
    private val isAuthorized: (String?) -> Boolean,
    private val onClientCountChanged: (Int) -> Unit = {}
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val clients = CopyOnWriteArrayList<PrintWriter>()
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val previewInFlight = ConcurrentHashMap<PrintWriter, AtomicBoolean>()
    private val previewLock = Any()
    private var latestPreview: String? = null
    private var previewSenderRunning = false
    fun hasClients(): Boolean = clients.isNotEmpty()

    fun start() {
        if (running) return
        running = true
        executor.execute {
            runCatching {
                serverSocket = ServerSocket(port)
                while (running) {
                    val socket = serverSocket?.accept() ?: break
                    executor.execute { handle(socket) }
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        socket.sendBufferSize = 32 * 1024
        sockets += socket
        var writer: PrintWriter? = null
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val clientWriter = PrintWriter(socket.getOutputStream(), true)
            writer = clientWriter
            while (running) {
                val line = reader.readLine() ?: break
                val command = JSONObject(line)
                if (isAuthorized(command.optString("token"))) {
                    if (command.optBoolean("previewFlowControl")) {
                        previewInFlight.putIfAbsent(clientWriter, AtomicBoolean(false))
                    }
                    if (command.optString("type") == "preview_ack") {
                        previewInFlight[clientWriter]?.set(false)
                        continue
                    }
                }
                val response = runCatching { onCommand(command) }
                    .getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "invalid request") }
                writer?.println(response.toString())
                if (isAuthorized(command.optString("token")) && clients.addIfAbsent(clientWriter)) {
                    runCatching { onClientCountChanged(clients.size) }
                }
            }
        } catch (_: Throwable) {
            // A controller closing its socket is a normal disconnect, not an app failure.
        } finally {
            writer?.let { clients.remove(it); previewInFlight.remove(it) }
            sockets.remove(socket)
            runCatching { socket.close() }
            runCatching { onClientCountChanged(clients.size) }
        }
    }

    fun broadcast(event: JSONObject) {
        if (clients.isEmpty() || !running) return
        val payload = event.toString()
        if (event.optString("event") == "preview") {
            synchronized(previewLock) {
                latestPreview = payload
                if (previewSenderRunning) return
                previewSenderRunning = true
            }
            executor.execute { sendLatestPreviews() }
            return
        }
        clients.forEach { writer ->
            executor.execute {
                runCatching { writer.println(payload) }
                    .onFailure {
                        clients.remove(writer)
                        previewInFlight.remove(writer)
                        runCatching { onClientCountChanged(clients.size) }
                    }
            }
        }
    }

    private fun sendLatestPreviews() {
        while (running) {
            val payload = synchronized(previewLock) {
                val value = latestPreview
                latestPreview = null
                if (value == null) previewSenderRunning = false
                value
            } ?: return
            clients.forEach { writer ->
                // One frame on the wire per negotiated client. A slow receiver
                // skips new frames until it acknowledges the complete previous
                // frame, instead of accumulating old frames in TCP buffers.
                val inFlight = previewInFlight[writer]
                if (inFlight != null && !inFlight.compareAndSet(false, true)) return@forEach
                runCatching { writer.println(payload) }
                    .onFailure { clients.remove(writer); runCatching { onClientCountChanged(clients.size) } }
            }
            // CameraSessionController paces encoding. Send the latest finished
            // frame immediately so this layer adds no second FPS cap.
        }
        synchronized(previewLock) { previewSenderRunning = false }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        executor.shutdownNow()
    }

    fun disconnectClients() {
        sockets.forEach { runCatching { it.close() } }
        clients.clear()
        runCatching { onClientCountChanged(0) }
    }
}
