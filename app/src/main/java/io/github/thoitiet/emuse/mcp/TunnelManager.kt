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
 * MCP server (127.0.0.1:PORT) on a public https://*.trycloudflare.com URL.
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

    private fun setState(s: State) {
        state = s
        onState?.invoke(s)
    }

    fun binaryFile(): File = File(appCtx.filesDir, BINARY_NAME)

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
     */
    fun start(url: String, onDownloadProgress: (Long, Long) -> Unit = { _, _ -> }) {
        localUrl = url
        if (wantRunning) return
        wantRunning = true
        supervisor?.cancel()
        supervisor = scope.launch {
            val bin = ensureBinary(onDownloadProgress) ?: run {
                wantRunning = false
                return@launch
            }
            var backoffMs = 5_000L
            while (wantRunning) {
                setState(State.Starting)
                val ok = runOnce(bin)
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
    private suspend fun runOnce(bin: File): Boolean = withContext(Dispatchers.IO) {
        // Prefer QUIC; fall back to http2 when UDP is blocked on this network.
        for (proto in listOf(null, "http2")) {
            if (!wantRunning) return@withContext false
            val args = mutableListOf(
                bin.absolutePath, "tunnel", "--url", localUrl, "--no-autoupdate",
            )
            if (proto != null) {
                args.add("--protocol")
                args.add(proto)
            }
            val p = try {
                startProcess(bin, args)
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
        if (wantRunning) setState(State.Failed("cloudflared không tạo được tunnel (thử QUIC + http2)"))
        false
    }

    private fun startProcess(bin: File, args: List<String>): Process {
        return try {
            ProcessBuilder(args).redirectErrorStream(false).start()
        } catch (e: Exception) {
            // Fallback: some ROMs block exec from the app data dir; use root.
            ProcessBuilder("su", "-c", args.joinToString(" ") { "'$it'" }).start()
        }
    }

    /** Scan stderr for the public URL; null when the process exits first. */
    private fun waitForUrl(p: Process): String? {
        val deadline = System.currentTimeMillis() + URL_TIMEOUT_MS
        val reader = p.errorStream.bufferedReader()
        try {
            while (System.currentTimeMillis() < deadline) {
                if (!p.isAlive) return null
                val line = if (reader.ready()) reader.readLine() else null
                if (line == null) {
                    Thread.sleep(200)
                    continue
                }
                URL_RE.find(line)?.let { return it.value }
                if (line.contains("failed", ignoreCase = true) &&
                    line.contains("tunnel", ignoreCase = true)
                ) {
                    // Keep scanning; a fatal error surfaces via process exit.
                }
            }
        } catch (_: Exception) {
        }
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
