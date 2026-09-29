package io.github.thoitiet.emuse.mcp

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages the cloudflared quick-tunnel process that exposes the on-device
 * MCP server (127.0.0.1:PORT) on a public trycloudflare.com URL.
 *
 * The binary (~35MB arm64) is downloaded on first use into the app's
 * private files dir — it is NOT bundled in the APK.
 */
class TunnelManager(
    private val appCtx: Context,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Stopped : State
        data object Downloading : State
        data object Starting : State
        data class Running(val url: String) : State
        data class Failed(val reason: String) : State
    }

    companion object {
        private const val BINARY_NAME = "cloudflared"
        private const val DOWNLOAD_URL =
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"
        // Go binaries cannot read Android's system CA store, so every TLS
        // handshake fails with "x509: certificate signed by unknown authority".
        // Ship our own Mozilla bundle and point Go at it via SSL_CERT_FILE.
        private const val CA_BUNDLE_NAME = "cacert.pem"
        private const val CA_BUNDLE_URL = "https://curl.se/ca/cacert.pem"
        private const val CA_BUNDLE_MIN_BYTES = 100_000L
        private val URL_RE = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com")
        private const val URL_TIMEOUT_MS = 60_000L
    }

    @Volatile
    var state: State = State.Stopped
        private set

    var onState: ((State) -> Unit)? = null

    private var proc: Process? = null
    private var supervisor: Job? = null
    private var wantRunning = false
    private var localUrl = ""
    private var tunnelToken = ""
    private var tunnelHostname = ""
    private var lastErrTail: List<String> = emptyList()

    private fun setState(s: State) {
        state = s
        onState?.invoke(s)
    }

    fun binaryFile(): File = File(appCtx.filesDir, BINARY_NAME)

    private fun caBundleFile(): File = File(appCtx.filesDir, CA_BUNDLE_NAME)

    /** Download the Mozilla CA bundle (Go on Android can't read the system store). */
    private suspend fun ensureCaBundle(): File? = withContext(Dispatchers.IO) {
        val ca = caBundleFile()
        if (ca.exists() && ca.length() > CA_BUNDLE_MIN_BYTES) return@withContext ca
        try {
            val tmp = File(appCtx.filesDir, "$CA_BUNDLE_NAME.tmp")
            val conn = openFollowRedirects(CA_BUNDLE_URL)
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return@withContext null
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            // Sanity: must look like a PEM bundle. (curl.se's file starts
            // with ## comment lines, so scan the head, not just line 1.)
            val head = StringBuilder()
            tmp.inputStream().bufferedReader().use { r ->
                repeat(40) {
                    val line = r.readLine() ?: return@repeat
                    head.append(line).append('\n')
                }
            }
            if (tmp.length() < CA_BUNDLE_MIN_BYTES || !head.contains("BEGIN CERTIFICATE")) {
                tmp.delete()
                return@withContext null
            }
            tmp.renameTo(ca)
            ca
        } catch (e: Exception) {
            null
        }
    }

    /** Download cloudflared arm64 on first use. Returns null on failure. */
    suspend fun ensureBinary(onProgress: (done: Long, total: Long) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val bin = binaryFile()
            if (bin.exists() && bin.canExecute() && bin.length() > 1_000_000) return@withContext bin
            setState(State.Downloading)
            try {
                val tmp = File(appCtx.filesDir, "$BINARY_NAME.tmp")
                var conn = openFollowRedirects(DOWNLOAD_URL)
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
                if (tmp.length() < 1_000_000) {
                    tmp.delete()
                    setState(State.Failed("file tải về quá nhỏ (${tmp.length()} bytes)"))
                    return@withContext null
                }
                tmp.renameTo(bin)
                bin.setExecutable(true, true)
                setState(State.Stopped)
                bin
            } catch (e: Exception) {
                setState(State.Failed("tải cloudflared thất bại: ${e.message?.take(80)}"))
                null
            }
        }

    private fun openFollowRedirects(url: String): HttpURLConnection {
        var current = url
        repeat(5) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "E-Muse/1.0")
            }
            val code = c.responseCode
            if (code in 301..308) {
                val loc = c.getHeaderField("Location")
                    ?: throw IllegalStateException("redirect without Location")
                current = loc
                c.disconnect()
            } else {
                return c
            }
        }
        throw IllegalStateException("too many redirects")
    }

    /**
     * Start the tunnel (idempotent). Downloads the binary first when needed.
     * Runs a supervisor that restarts cloudflared with backoff if it dies.
     *
     * @param token blank = Quick Tunnel (random trycloudflare.com URL);
     *   non-blank = named tunnel via `cloudflared tunnel run --token`, whose
     *   public hostname is fixed server-side ([hostname]).
     */
    fun start(
        url: String,
        token: String = "",
        hostname: String = "",
        onDownloadProgress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        localUrl = url
        tunnelToken = token.trim()
        tunnelHostname = hostname.trim()
        if (wantRunning) return
        wantRunning = true
        supervisor?.cancel()
        supervisor = scope.launch {
            val bin = ensureBinary(onDownloadProgress) ?: run {
                wantRunning = false
                return@launch
            }
            val caBundle = ensureCaBundle() // null -> cloudflared falls back to its default roots
            var backoffMs = 5_000L
            while (wantRunning) {
                setState(State.Starting)
                val ok = runOnce(bin, caBundle)
                if (!wantRunning) break
                if (ok) backoffMs = 5_000L // reset after a healthy run
                delay(backoffMs)
                backoffMs = minOf(backoffMs * 2, 60_000L)
            }
            if (!wantRunning) setState(State.Stopped)
        }
    }

    fun stop() {
        wantRunning = false
        supervisor?.cancel()
        supervisor = null
        killProc()
        setState(State.Stopped)
    }

    fun isRunning(): Boolean = wantRunning

    /**
     * Run cloudflared once. Returns true when it stayed up (URL obtained and
     * process lived); false when it exited early / never produced a URL.
     */
    private suspend fun runOnce(bin: File, caBundle: File?): Boolean = withContext(Dispatchers.IO) {
        // Prefer QUIC; fall back to http2 when UDP is blocked on this network.
        val named = tunnelToken.isNotBlank()
        for (proto in listOf(null, "http2")) {
            if (!wantRunning) return@withContext false
            val args = if (named) {
                // Named tunnel: everything (hostname, ingress) is configured
                // server-side; the token authenticates this connector.
                mutableListOf(
                    bin.absolutePath, "tunnel", "--no-autoupdate",
                    "run", "--token", tunnelToken,
                )
            } else {
                mutableListOf(
                    bin.absolutePath, "tunnel", "--url", localUrl, "--no-autoupdate",
                )
            }
            if (proto != null) {
                args.add("--protocol")
                args.add(proto)
            }
            val p = try {
                startProcess(bin, args, caBundle)
            } catch (e: Exception) {
                if (!wantRunning) return@withContext false
                continue // try next protocol / backoff
            }
            proc = p
            val urlFound = waitForUrl(p)
            if (urlFound != null) {
                setState(State.Running(urlFound))
                // Stay until the process dies or we're asked to stop.
                try {
                    p.waitFor()
                } catch (_: Exception) {
                }
                proc = null
                return@withContext wantRunning // true: healthy run, reset backoff
            }
            killProc()
            // No URL: try the next protocol before giving up this round.
        }
        if (wantRunning) {
            val tail = lastErrTail.takeLast(2).joinToString(" | ").take(160)
            val reason = "cloudflared không tạo được tunnel (thử QUIC + http2)" +
                (if (tail.isNotEmpty()) " — $tail" else "")
            setState(State.Failed(reason))
        }
        false
    }

    private fun startProcess(bin: File, args: List<String>, caBundle: File?): Process {
        // Go's TLS stack honors SSL_CERT_FILE; Android's system store is
        // invisible to it, so hand it our bundled Mozilla roots.
        val envPrefix = if (caBundle != null) "SSL_CERT_FILE='${caBundle.absolutePath}' " else ""
        return try {
            ProcessBuilder(args).apply {
                redirectErrorStream(false)
                if (caBundle != null) environment()["SSL_CERT_FILE"] = caBundle.absolutePath
            }.start()
        } catch (e: Exception) {
            // Fallback: some ROMs block exec from the app data dir; use root.
            ProcessBuilder("su", "-c", envPrefix + args.joinToString(" ") { "'$it'" }).start()
        }
    }

    /**
     * Scan stderr for the public URL; null when the process exits first.
     * Named tunnels print no URL — they log "Registered tunnel connection"
     * instead, so map that to the fixed hostname.
     */
    private fun waitForUrl(p: Process): String? {
        val deadline = System.currentTimeMillis() + URL_TIMEOUT_MS
        val fixedUrl = "https://$tunnelHostname".takeIf { tunnelToken.isNotBlank() && tunnelHostname.isNotBlank() }
        val reader = p.errorStream.bufferedReader()
        val tail = ArrayDeque<String>()
        try {
            while (System.currentTimeMillis() < deadline) {
                if (!p.isAlive) {
                    lastErrTail = tail.toList()
                    return null
                }
                val line = if (reader.ready()) reader.readLine() else null
                if (line == null) {
                    Thread.sleep(200)
                    continue
                }
                tail.addLast(line.take(200))
                if (tail.size > 10) tail.removeFirst()
                URL_RE.find(line)?.let { return it.value }
                if (fixedUrl != null && line.contains("Registered tunnel connection")) {
                    return fixedUrl
                }
                if (line.contains("failed", ignoreCase = true) &&
                    line.contains("tunnel", ignoreCase = true)
                ) {
                    // Keep scanning; a fatal error surfaces via process exit.
                }
            }
        } catch (_: Exception) {
        }
        lastErrTail = tail.toList()
        return null
    }

    private fun killProc() {
        val p = proc ?: return
        proc = null
        try {
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        } catch (_: Exception) {
        }
    }
}
