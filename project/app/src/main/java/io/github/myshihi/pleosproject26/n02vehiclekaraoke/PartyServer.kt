package io.github.myshihi.pleosproject26.n02vehiclekaraoke

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class HttpRequest(val method: String, val path: String, val query: Map<String, String>, val body: String) {
    val form get() = Http.parseQuery(body)
}

class HttpResponse(val status: Int, val type: String, val body: ByteArray) {
    companion object {
        fun json(status: Int, value: Map<String, Any?>) = HttpResponse(status, "application/json; charset=utf-8", Json.write(value).toByteArray())
        fun html(value: String) = HttpResponse(200, "text/html; charset=utf-8", value.toByteArray())
        fun text(status: Int, value: String) = HttpResponse(status, "text/plain; charset=utf-8", value.toByteArray())
    }
}

/** Tiny HTTP/1.1 request/response handling for the in-car local page. No keep-alive, bounded sizes. */
object Http {
    const val MAX_HEADER = 8 * 1024
    const val MAX_BODY = 4 * 1024

    fun parseQuery(value: String?): Map<String, String> {
        if (value.isNullOrEmpty()) return emptyMap()
        val out = linkedMapOf<String, String>()
        value.split('&').forEach { part ->
            if (part.isEmpty()) return@forEach
            val i = part.indexOf('=')
            val key = decode(if (i < 0) part else part.substring(0, i)) ?: return@forEach
            val v = decode(if (i < 0) "" else part.substring(i + 1)) ?: return@forEach
            if (key !in out) out[key] = v
        }
        return out
    }

    private fun decode(value: String) = try { URLDecoder.decode(value, "UTF-8") } catch (_: IllegalArgumentException) { null }

    /** Returns null for malformed or oversized requests. */
    fun read(input: InputStream): HttpRequest? {
        val head = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (matched < 4) {
            val b = input.read()
            if (b < 0 || head.size() >= MAX_HEADER) return null
            head.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
        }
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val first = lines.first().split(' ')
        if (first.size != 3 || !first[2].startsWith("HTTP/1.")) return null
        val method = first[0]
        if (method != "GET" && method != "POST") return null
        val target = first[1]
        if (!target.startsWith("/")) return null
        val headers = lines.drop(1).filter { it.contains(':') }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        val path = target.substringBefore('?')
        val query = if ('?' in target) target.substringAfter('?') else ""
        return HttpRequest(method, path, parseQuery(query), String(body, Charsets.UTF_8))
    }

    fun write(output: OutputStream, response: HttpResponse) {
        val reason = when (response.status) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 503 -> "Service Unavailable"; else -> "Error" }
        val head = buildString {
            append("HTTP/1.1 ${response.status} $reason\r\n")
            append("Content-Type: ${response.type}\r\n")
            append("Content-Length: ${response.body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            if (response.type.startsWith("text/html")) {
                append("Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(response.body)
        output.flush()
    }
}

object Json {
    fun write(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Int, is Long -> value.toString()
        is String -> quote(value)
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { quote(it.key.toString()) + ":" + write(it.value) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { write(it) }
        else -> quote(value.toString())
    }

    fun quote(value: String) = buildString {
        append('"')
        value.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' || c == '<' || c == '>' || c == '&' || c == ' ' || c == ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}

/** Finds addresses phones in the car can reach (vehicle hotspot / Wi-Fi first). */
object LocalAddress {
    fun candidates(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nic.name to it.hostAddress.orEmpty() } }
            .filter { it.second.isNotEmpty() }
            .sortedBy { (name, _) -> if (name.startsWith("ap") || name.startsWith("swlan")) 0 else if (name.startsWith("wlan")) 1 else 2 }
            .map { it.second }
            .distinct()
    } catch (_: Exception) { emptyList() }
}

/** Serves the join page and API on the local network only while the app is on screen. */
class PartyServer(val port: Int, private val handle: (HttpRequest) -> HttpResponse) {
    @Volatile private var socket: ServerSocket? = null
    private var pool: ThreadPoolExecutor? = null
    val running get() = socket != null
    val boundPort get() = socket?.localPort ?: -1

    fun start(): Boolean {
        if (socket != null) return true
        return try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(port), 16)
            val workers = ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16))
            socket = server; pool = workers
            thread(name = "SeatDjServer", isDaemon = true) {
                while (!server.isClosed) {
                    val client = try { server.accept() } catch (_: IOException) { break }
                    try { workers.execute { serve(client) } } catch (_: RejectedExecutionException) { close(client) }
                }
            }
            true
        } catch (_: IOException) { stop(); false }
    }

    fun stop() {
        try { socket?.close() } catch (_: IOException) { }
        socket = null
        pool?.shutdownNow(); pool = null
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 10_000
            val request = Http.read(client.getInputStream())
            val response = if (request == null) HttpResponse.text(400, "bad request")
                else try { handle(request) } catch (_: Exception) { HttpResponse.json(500, mapOf("ok" to false, "message" to "차량 앱에서 처리하지 못했어요.")) }
            Http.write(client.getOutputStream(), response)
        } catch (_: IOException) {
        } finally { close(client) }
    }

    private fun close(client: Socket) { try { client.close() } catch (_: IOException) { } }
}
