package mon.launcher

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * HTTP server for pages opened from another device on the local network.
 * Stops after [lifetimeMs] even if nothing calls [stop].
 */
class LanServer(
    private val lifetimeMs: Long = 10 * 60_000L,
    private val handler: (Request) -> Response,
) {
    /** [path] has no query string. */
    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: InputStream)

    /** [after] runs after the response is sent. */
    class Response(
        val code: Int,
        val type: String,
        val body: ByteArray,
        val headers: Map<String, String> = emptyMap(),
        val after: (() -> Unit)? = null,
    ) {
        companion object {
            fun text(code: Int, message: String) = Response(code, "text/plain; charset=utf-8", message.toByteArray())
        }
    }

    @Volatile private var socket: ServerSocket? = null
    private val connections = AtomicInteger(0)

    val address: String?
        get() = if (socket == null) null else lanAddress()?.let { "http://$it:$PORT" }

    /** Returns false if the port can't be opened, e.g. another app uses it. */
    fun start(): Boolean {
        if (socket != null) return true
        val server = runCatching { ServerSocket(PORT).apply { soTimeout = 1000 } }
            .onFailure { logProblem("Couldn't open port $PORT for the phone page", it) }
            .getOrNull() ?: return false
        socket = server
        val deadline = System.currentTimeMillis() + lifetimeMs
        thread(name = "lan-server", isDaemon = true) {
            while (!server.isClosed && System.currentTimeMillis() < deadline) {
                val client = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
                if (connections.incrementAndGet() > MAX_CONNECTIONS) {
                    connections.decrementAndGet()
                    runCatching { client.close() }
                    continue
                }
                // Errors stay inside one connection.
                // Browsers open spare connections that time out unused.
                thread(isDaemon = true) {
                    try {
                        runCatching { client.use { handle(it) } }
                    } finally {
                        connections.decrementAndGet()
                    }
                }
            }
            runCatching { server.close() }
            // Allows start() again after the lifetime ends.
            // Skipped if stop() or a new start() replaced the socket.
            if (socket === server) socket = null
        }
        return true
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        val request = readLine(input) ?: return
        val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }
            .take(100)
            .mapNotNull { line -> line.split(":", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim().lowercase() to it[1].trim() } }
            .toMap()
        val parts = request.split(" ")
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty().substringBefore('?')
        val response = handler(Request(method, path, headers, input))
        respond(out, response)
        response.after?.invoke()
    }

    private fun respond(out: OutputStream, response: Response) {
        val status = STATUS[response.code] ?: "Error"
        val extra = response.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
        out.write(
            ("HTTP/1.1 ${response.code} $status\r\nContent-Type: ${response.type}\r\n" +
                "Content-Length: ${response.body.size}\r\nCache-Control: no-store\r\n${extra}Connection: close\r\n\r\n").toByteArray(),
        )
        out.write(response.body)
        out.flush()
    }

    /** Reads one line without buffering past it, so the body is left intact. */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.isEmpty()) null else line.toString()
            if (b == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length > 8192) return null
            line.append(b.toChar())
        }
    }

    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    companion object {
        const val PORT = 8642
        private const val MAX_CONNECTIONS = 8

        private val STATUS = mapOf(
            200 to "OK", 400 to "Bad Request",
            404 to "Not Found", 411 to "Length Required", 413 to "Payload Too Large",
        )
    }
}
