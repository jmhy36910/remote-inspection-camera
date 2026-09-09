package tw.com.upr.remoteinspection

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList

/** Line-delimited JSON control channel for the Windows controller. */
class NetworkControlServer(
    private val port: Int = 8765,
    private val onCommand: (JSONObject) -> JSONObject,
    private val onClientCountChanged: (Int) -> Unit = {}
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val clients = CopyOnWriteArrayList<PrintWriter>()
    private val sockets = CopyOnWriteArrayList<Socket>()
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
        sockets += socket
        var writer: PrintWriter? = null
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val clientWriter = PrintWriter(socket.getOutputStream(), true)
            writer = clientWriter
            clients += clientWriter
            runCatching { onClientCountChanged(clients.size) }
            while (running) {
                val line = reader.readLine() ?: break
                val response = runCatching { onCommand(JSONObject(line)) }
                    .getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "invalid request") }
                writer?.println(response.toString())
            }
        } catch (_: Throwable) {
            // A controller closing its socket is a normal disconnect, not an app failure.
        } finally {
            writer?.let { clients.remove(it) }
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
                    .onFailure { clients.remove(writer); runCatching { onClientCountChanged(clients.size) } }
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
}
