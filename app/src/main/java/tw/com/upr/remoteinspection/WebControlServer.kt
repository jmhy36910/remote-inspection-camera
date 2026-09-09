package tw.com.upr.remoteinspection

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import android.util.Base64

/** Browser control endpoint: GET /, GET /events (SSE), POST /control. */
class WebControlServer(
    private val context: Context,
    private val port: Int = 8787,
    private val onCommand: (JSONObject) -> JSONObject
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val eventClients = CopyOnWriteArrayList<PrintWriter>()
    @Volatile private var latestPreviewJpeg: ByteArray? = null
    private val previewClients = CopyOnWriteArrayList<Socket>()
    private val sockets = CopyOnWriteArrayList<Socket>()
    fun hasPreviewClients(): Boolean = previewClients.isNotEmpty()
    fun publishPreview(jpeg: ByteArray) { if (hasPreviewClients()) latestPreviewJpeg = jpeg }

    fun start() {
        if (running) return
        running = true
        executor.execute {
            runCatching {
                serverSocket = ServerSocket(port)
                while (running) serverSocket?.accept()?.let { socket ->
                    sockets += socket
                    executor.execute {
                        try { handle(socket) } catch (_: Exception) {
                            // Browser/OBS disconnects are normal.
                        } finally { sockets -= socket; runCatching { socket.close() } }
                    }
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: return
            val path = parts.getOrNull(1) ?: "/"
            var contentLength = 0
            while (true) {
                val header = reader.readLine() ?: return
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
            }
            if (method == "GET" && path == "/events") {
                val writer = PrintWriter(it.getOutputStream(), true)
                writer.print("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n")
                writer.flush(); eventClients += writer
                try {
                    while (running && !it.isClosed) {
                        Thread.sleep(2000)
                        synchronized(writer) { writer.print(": keepalive\n\n"); writer.flush() }
                        if (writer.checkError()) break
                    }
                } finally { eventClients -= writer }
                return
            }
            if (method == "GET" && path == "/mjpeg") {
                val output = it.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
                var last: ByteArray? = null
                previewClients += socket
                try {
                while (running && !it.isClosed) {
                    val frame = latestPreviewJpeg
                    if (frame != null && frame !== last) {
                        output.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
                        output.write(frame); output.write("\r\n".toByteArray()); output.flush(); last = frame
                    } else Thread.sleep(10)
                }
                } finally {
                    previewClients -= socket
                    if (previewClients.isEmpty()) latestPreviewJpeg = null
                }
                return
            }
            val response = if (method == "GET" && path == "/") {
                context.assets.open("index.html").use { it.readBytes() }.let { bytes ->
                    "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n" to bytes
                }
            } else if (method == "POST" && path == "/control") {
                val body = CharArray(contentLength); reader.read(body)
                val bytes = onCommand(JSONObject(String(body))).toString().toByteArray()
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n" to bytes
            } else "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n" to ByteArray(0)
            it.getOutputStream().use { output -> output.write(response.first.toByteArray()); output.write(response.second); output.flush() }
        }
    }

    fun broadcast(event: JSONObject) {
        if (event.optString("event") == "preview") {
            // Preview frames are delivered by /mjpeg. Do not enqueue every frame
            // into SSE, otherwise a reconnecting browser can accumulate stale data.
            latestPreviewJpeg = runCatching { Base64.decode(event.getString("data"), Base64.DEFAULT) }.getOrNull()
            return
        }
        val payload = "data: ${event}\n\n"
        eventClients.forEach { writer -> executor.execute { runCatching {
            synchronized(writer) { writer.print(payload); writer.flush() }
            if (writer.checkError()) eventClients.remove(writer)
        }.onFailure { eventClients.remove(writer) } } }
    }

    fun stop() {
        running = false; runCatching { serverSocket?.close() }
        sockets.forEach { runCatching { it.close() } }; sockets.clear()
        eventClients.clear(); previewClients.clear(); latestPreviewJpeg = null
        executor.shutdownNow()
    }
}
