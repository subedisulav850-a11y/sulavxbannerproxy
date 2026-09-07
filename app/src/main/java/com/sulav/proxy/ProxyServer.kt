package com.sulav.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class ProxyServer(
    private val host: String,
    private val port: Int,
    private val target: String,
    private val onEvent: (Capture) -> Unit,
    private val onError: (String) -> Unit
) {
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false
    private var socket: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        pool.execute {
            try {
                socket = ServerSocket(port, 64, java.net.InetAddress.getByName(host))
                while (running) {
                    val client = socket!!.accept()
                    pool.execute { handle(client) }
                }
            } catch (e: Exception) {
                if (running) onError("Proxy error: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(client: java.net.Socket) {
        client.use { s ->
            try {
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1]
                if (path == "/download" || path.endsWith(".json")) {
                    val body = "{\"serverLoginUrl\":\"http://$host:$port/\",\"forwardTarget\":\"$target\"}"
                    writeResponse(output, 200, "application/json", body.toByteArray())
                    return
                }

                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val p = line.indexOf(':')
                    if (p > 0) headers[line.substring(0, p).trim()] = line.substring(p + 1).trim()
                }
                val length = headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.toIntOrNull() ?: 0
                val body = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val n = input.read(body, offset, length - offset)
                    if (n <= 0) break
                    offset += n
                }

                val upstreamUrl = target.trimEnd('/') + (if (path.startsWith('/')) path else "/$path")
                val conn = URL(upstreamUrl).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = false
                headers.forEach { (k, v) ->
                    if (!k.equals("Host", true) && !k.equals("Content-Length", true) && !k.equals("Connection", true)) {
                        conn.setRequestProperty(k, v)
                    }
                }
                if (method != "GET" && method != "DELETE") {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body) }
                }
                val status = conn.responseCode
                val stream = if (status >= 400) conn.errorStream else conn.inputStream
                val response = stream?.use { it.readBytes() } ?: ByteArray(0)
                writeResponse(output, status, conn.contentType ?: "application/octet-stream", response)
                val safeHeaders = headers.entries.joinToString("\n") { (k, v) ->
                    if (k.equals("Authorization", true)) "$k: [REDACTED]" else "$k: $v"
                }
                onEvent(Capture(method, upstreamUrl, status, hex(body, 512), hex(response, 512), safeHeaders))
                conn.disconnect()
            } catch (e: Exception) {
                runCatching { writeResponse(502, "text/plain", (e.message ?: "Bad Gateway").toByteArray(StandardCharsets.UTF_8), s) }
                onError("Request failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (out.isEmpty()) null else out.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) out.append(b.toChar())
            if (out.length > 8192) return null
        }
        return out.toString()
    }

    private fun writeResponse(out: BufferedOutputStream, status: Int, type: String, body: ByteArray) {
        val reason = when (status) { 200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 400 -> "Bad Request"; 404 -> "Not Found"; 502 -> "Bad Gateway"; else -> "Response" }
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)
        out.write(head); out.write(body); out.flush()
    }

    private fun writeResponse(status: Int, type: String, body: ByteArray, socket: java.net.Socket) {
        val out = BufferedOutputStream(socket.getOutputStream())
        writeResponse(out, status, type, body)
    }

    private fun hex(bytes: ByteArray, limit: Int): String = bytes.copyOfRange(0, minOf(bytes.size, limit)).joinToString(" ") { "%02X".format(it) }
}
