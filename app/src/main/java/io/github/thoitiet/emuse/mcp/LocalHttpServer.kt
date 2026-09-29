package io.github.thoitiet.emuse.mcp

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Minimal HTTP/1.1 server for the on-device MCP endpoint.
 * Zero dependencies on purpose: only POST /mcp (and GET /health) with
 * Content-Length or chunked request bodies, keep-alive responses.
 * Bound to 127.0.0.1 only — the public side is Cloudflare Tunnel.
 */
class LocalHttpServer(
    private val port: Int,
    private val handler: suspend (
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray,
    ) -> Response,
) {
    data class Response(
        val status: Int,
        val body: ByteArray,
        val contentType: String = "application/json",
        val headers: Map<String, String> = emptyMap(),
    )

    companion object {
        private const val MAX_BODY = 1024 * 1024
        private const val MAX_HEADERS = 32 * 1024
        private const val IDLE_TIMEOUT_MS = 60_000
        private const val MAX_CONNECTIONS = 64

        private fun statusText(code: Int): String = when (code) {
            200 -> "OK"
            202 -> "Accepted"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            500 -> "Internal Server Error"
            else -> "OK"
        }
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(MAX_CONNECTIONS)
    private var server: ServerSocket? = null

    @Volatile
    var started: Boolean = false
        private set

    fun start() {
        if (started) return
        // stop() cancels the scope, so a fresh one is needed on every start;
        // otherwise the accept loop would never run on a reused instance.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val s = ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))
        server = s
        started = true
        scope.launch {
            while (started) {
                try {
                    val sock = s.accept()
                    launch { semaphore.withPermit { handleConnection(sock) } }
                } catch (_: Exception) {
                    if (!started) break
                }
            }
        }
    }

    fun stop() {
        started = false
        runCatching { server?.close() }
        scope.cancel()
    }

    private suspend fun handleConnection(sock: Socket) {
        try {
            sock.soTimeout = IDLE_TIMEOUT_MS
            sock.tcpNoDelay = true
            val input = sock.getInputStream()
            val output = sock.getOutputStream()
            val reader = HttpReader(input)
            while (true) {
                val requestLine = try {
                    reader.readLine() ?: break
                } catch (_: SocketTimeoutException) {
                    break // idle keep-alive timeout
                }
                if (requestLine.isEmpty()) continue
                val parts = requestLine.split(" ")
                if (parts.size < 2) break
                val method = parts[0].uppercase()
                val rawPath = parts[1]
                val path = rawPath.substringBefore("?")
                val http11 = requestLine.endsWith("HTTP/1.1")
                val headers = try {
                    reader.readHeaders()
                } catch (_: Exception) {
                    break
                }
                val body = try {
                    readBody(reader, headers)
                } catch (_: Exception) {
                    writeResponse(output, Response(413, ByteArray(0)), false)
                    break
                }
                val reqKeepAlive = when (headers["connection"]?.lowercase()) {
                    "close" -> false
                    "keep-alive" -> true
                    else -> http11
                }
                val resp = try {
                    handler(method, path, headers, body)
                } catch (_: Exception) {
                    Response(500, ByteArray(0))
                }
                // 401 from the MCP layer carries its own auth challenge header.
                writeResponse(output, resp, reqKeepAlive)
                output.flush()
                if (!reqKeepAlive) break
            }
        } catch (_: Exception) {
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun readBody(reader: HttpReader, headers: Map<String, String>): ByteArray {
        val chunked = headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true
        if (chunked) return reader.readChunked(MAX_BODY)
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len < 0 || len > MAX_BODY) throw IllegalArgumentException("body too large")
        if (len == 0) return ByteArray(0)
        return reader.readExact(len)
    }

    private fun writeResponse(output: OutputStream, resp: Response, keepAlive: Boolean) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(resp.status).append(' ').append(statusText(resp.status)).append("\r\n")
        sb.append("Content-Type: ").append(resp.contentType).append("\r\n")
        sb.append("Content-Length: ").append(resp.body.size).append("\r\n")
        for ((k, v) in resp.headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.US_ASCII))
        if (resp.body.isNotEmpty()) output.write(resp.body)
    }

    /** Small buffered HTTP reader over a raw InputStream. */
    private class HttpReader(private val input: InputStream) {
        private val buf = ByteArray(8192)
        private var pos = 0
        private var end = 0
        private var totalHeaderBytes = 0

        private fun fill(): Boolean {
            if (pos < end) return true
            val n = input.read(buf)
            if (n <= 0) return false
            pos = 0
            end = n
            return true
        }

        fun readByte(): Int {
            if (!fill()) throw EOFException()
            return buf[pos++].toInt() and 0xFF
        }

        fun readLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = try {
                    readByte()
                } catch (_: EOFException) {
                    return if (sb.isEmpty()) null else sb.toString()
                }
                totalHeaderBytes++
                if (totalHeaderBytes > MAX_HEADERS + 4096) throw IllegalStateException("headers too large")
                if (b == '\r'.code) {
                    val n = readByte()
                    if (n != '\n'.code) throw IllegalStateException("bad line ending")
                    return sb.toString()
                }
                if (b == '\n'.code) return sb.toString()
                sb.append(b.toChar())
            }
        }

        fun readHeaders(): Map<String, String> {
            totalHeaderBytes = 0
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine() ?: throw EOFException()
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }
            return headers
        }

        fun readExact(n: Int): ByteArray {
            val out = ByteArray(n)
            var off = 0
            while (off < n) {
                if (!fill()) throw EOFException()
                val take = minOf(end - pos, n - off)
                System.arraycopy(buf, pos, out, off, take)
                pos += take
                off += take
            }
            return out
        }

        fun readChunked(max: Int): ByteArray {
            val chunks = mutableListOf<ByteArray>()
            var total = 0
            while (true) {
                val sizeLine = readLine() ?: throw EOFException()
                val size = sizeLine.substringBefore(";").trim().toIntOrNull(16)
                    ?: throw IllegalStateException("bad chunk size")
                if (size == 0) {
                    // consume trailing headers + final CRLF
                    while (true) {
                        val t = readLine() ?: throw EOFException()
                        if (t.isEmpty()) break
                    }
                    break
                }
                if (total + size > max) throw IllegalStateException("body too large")
                chunks.add(readExact(size))
                total += size
                val cr = readByte()
                val lf = readByte()
                if (cr != '\r'.code || lf != '\n'.code) throw IllegalStateException("bad chunk ending")
            }
            val out = ByteArray(total)
            var off = 0
            for (c in chunks) {
                System.arraycopy(c, 0, out, off, c.size)
                off += c.size
            }
            return out
        }
    }
}
