package io.github.thoitiet.emuse.mcp

import android.content.Context
import io.github.thoitiet.emuse.Prefs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages the cloudflared quick-tunnel process that exposes the on-device
 * MCP server (127.0.0.1:PORT) on a public trycloudflare.com URL.
 *
 * The binary (~35MB arm64) is downloaded on first use into the app's
 * private files dir — it is NOT bundled in the APK.
 *
 * Lifecycle notes (batch A fixes):
 * - A generation counter invalidates a stale supervisor: stop() then start()
 *   in quick succession (or a service restart) can otherwise leave the old
 *   supervisor blocked in Process.waitFor(); when it wakes it would see the
 *   new wantRunning=true and spawn a second cloudflared, leaking the old one
 *   forever (same pattern as MuseService's connectGen).
 * - The child PID is recorded in a pidfile so a fresh start() can reap a
 *   cloudflared orphaned by a killed service (init adopts it, so it survives).
 * - The named-tunnel token is passed via environment variables, never via
 *   argv or an embedded shell string (visible in `ps`).
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
        private const val PID_FILE_NAME = "cloudflared.pid"
        private const val TOKEN_FILE_NAME = ".tunnel_token"
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

    private val prefs = Prefs(appCtx)

    // Written on the IO thread (supervisor), read on the main thread
    // (stop()/killProc()) — must be volatile for visibility.
    @Volatile
    private var proc: Process? = null
    private var supervisor: Job? = null
    private var wantRunning = false
    // BL1: bumped on every stop()/restart so a stale supervisor blocked in
    // p.waitFor() exits instead of spawning a second cloudflared.
    private val generation = AtomicInteger(0)
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

    private fun pidFile(): File = File(appCtx.filesDir, PID_FILE_NAME)

    private fun sha256Hex(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Bug 9 — SHA-256 pin, trust-on-first-use: the first time we see the
     * binary (after Boss has confirmed the downloaded file is legit) its
     * hash is stored in Prefs; later runs fail closed on mismatch.
     * To re-pin after Boss re-verifies: delete the `cloudflared` file from
     * the app's files dir (or clear app data) and let it download again.
     */
    private fun verifyBinaryPin(bin: File): Boolean {
        val actual = runCatching { sha256Hex(bin) }.getOrNull() ?: return false
        val pinned = prefs.cloudflaredSha256
        if (pinned.isEmpty()) {
            prefs.cloudflaredSha256 = actual
            return true
        }
        if (!pinned.equals(actual, ignoreCase = true)) {
            setState(State.Failed("SHA-256 cloudflared không khớp bản đã pin — từ chối chạy"))
            return false
        }
        return true
    }

    /** Download the Mozilla CA bundle (Go on Android can't read the system store). */
    private suspend fun ensureCaBundle(): File? = withContext(Dispatchers.IO) {
        val ca = caBundleFile()
        if (ca.exists() && ca.length() > CA_BUNDLE_MIN_BYTES && verifyCaPin(ca)) {
            return@withContext ca
        }
        try {
            ensureActive()
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
            // The CA bundle rotates upstream (curl.se), so re-pin on every
            // fresh download instead of failing closed like the binary.
            prefs.caBundleSha256 = runCatching { sha256Hex(ca) }.getOrDefault("")
            ca
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Trust-on-first-use pin for the CA bundle; mismatch -> re-download. */
    private fun verifyCaPin(ca: File): Boolean {
        val pinned = prefs.caBundleSha256
        if (pinned.isEmpty()) {
            prefs.caBundleSha256 = runCatching { sha256Hex(ca) }.getOrDefault("")
            return true
        }
        return runCatching { pinned.equals(sha256Hex(ca), ignoreCase = true) }.getOrDefault(false)
    }

    /** Download cloudflared arm64 on first use. Returns null on failure. */
    suspend fun ensureBinary(onProgress: (done: Long, total: Long) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val bin = binaryFile()
            if (bin.exists() && bin.canExecute() && bin.length() > 1_000_000) {
                return@withContext if (verifyBinaryPin(bin)) bin else null
            }
            setState(State.Downloading)
            try {
                ensureActive()
                val tmp = File(appCtx.filesDir, "$BINARY_NAME.tmp")
                var conn = openFollowRedirects(DOWNLOAD_URL)
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            ensureActive()
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
                if (!verifyBinaryPin(bin)) return@withContext null
                setState(State.Stopped)
                bin
            } catch (e: CancellationException) {
                throw e
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
     * If the token/hostname changed while running, the tunnel is restarted
     * so the new config takes effect.
     *
     * @param token blank = Quick Tunnel (random trycloudflare.com URL);
     *   non-blank = named tunnel via `cloudflared tunnel run` (token passed
     *   via env var, never argv), whose public hostname is fixed server-side
     *   ([hostname]). Named mode requires a non-blank hostname.
     */
    fun start(
        url: String,
        token: String = "",
        hostname: String = "",
        onDownloadProgress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        val newUrl = url
        val newToken = token.trim()
        val newHost = hostname.trim()
        val changed = wantRunning &&
            (newUrl != localUrl || newToken != tunnelToken || newHost != tunnelHostname)
        localUrl = newUrl
        tunnelToken = newToken
        tunnelHostname = newHost
        // should-fix 7: fail immediately — without a hostname waitForUrl can
        // never match, so we'd kill a healthy process after 60s and retry forever.
        if (tunnelToken.isNotBlank() && tunnelHostname.isBlank()) {
            setState(State.Failed("named tunnel cần hostname — nhập Hostname hoặc để trống Token để dùng Quick Tunnel"))
            return
        }
        if (wantRunning && !changed) return // idempotent
        if (changed) {
            // Bug 6: config đổi khi đang chạy -> restart để apply.
            generation.incrementAndGet() // vô hiệu supervisor cũ (BL1)
            supervisor?.cancel()
            supervisor = null
            killProc()
        }
        killStaleTunnel() // BL2: dọn cloudflared mồ côi từ lần service chết trước
        wantRunning = true
        val gen = generation.incrementAndGet()
        supervisor?.cancel()
        supervisor = scope.launch {
            val bin = ensureBinary(onDownloadProgress) ?: run {
                wantRunning = false
                return@launch
            }
            val caBundle = ensureCaBundle() // null -> cloudflared falls back to its default roots
            var backoffMs = 5_000L
            while (wantRunning && generation.get() == gen) {
                setState(State.Starting)
                val ok = runOnce(bin, caBundle, gen)
                if (!wantRunning || generation.get() != gen) break
                if (ok) backoffMs = 5_000L // reset after a healthy run
                delay(backoffMs)
                backoffMs = minOf(backoffMs * 2, 60_000L)
            }
            if (!wantRunning) setState(State.Stopped)
        }
    }

    fun stop() {
        wantRunning = false
        generation.incrementAndGet() // BL1: supervisor cũ kẹt trong waitFor() sẽ thoát, không spawn lại
        supervisor?.cancel()
        supervisor = null
        killProc()
        runCatching { pidFile().delete() }
        runCatching { File(appCtx.filesDir, TOKEN_FILE_NAME).delete() }
        setState(State.Stopped)
    }

    /** Đặt state Failed từ bên ngoài (vd local server bind lỗi -> không start tunnel). */
    fun failNow(reason: String) {
        wantRunning = false
        setState(State.Failed(reason))
    }

    /**
     * BL2: kill a cloudflared orphan left behind when the service was killed
     * (init adopts the child, so it survives and would otherwise become a
     * second, uncontrolled tunnel after START_STICKY restart).
     * PID reuse guard: only kill when the PID still belongs to our binary;
     * when the owner can't be verified, leave it alone.
     */
    private fun killStaleTunnel() {
        val f = pidFile()
        if (!f.exists()) return
        val pid = runCatching { f.readText().trim().toLong() }.getOrNull()
        if (pid == null) {
            runCatching { f.delete() }
            return
        }
        try {
            val handle = ProcessHandle.of(pid).orElse(null)
            if (handle == null || !handle.isAlive) {
                f.delete()
                return
            }
            val cmd = runCatching { handle.info().command().orElse("") }.getOrDefault("")
            if (cmd.isEmpty() || cmd != binaryFile().absolutePath) {
                // Không xác minh được chủ PID (hoặc PID đã tái sử dụng) -> không kill bừa.
                f.delete()
                return
            }
            handle.destroyForcibly()
            f.delete()
        } catch (_: Exception) {
            runCatching { f.delete() }
        }
    }

    private fun writePid(p: Process) {
        runCatching { pidFile().writeText(p.pid().toString()) }
    }

    private fun clearPid(p: Process) {
        // Chỉ xóa khi pidfile còn trỏ đúng process này (tránh xóa của gen mới).
        runCatching {
            val f = pidFile()
            if (f.exists() && f.readText().trim() == p.pid().toString()) f.delete()
        }
    }

    /**
     * Run cloudflared once. Returns true when it stayed up (URL obtained and
     * process lived); false when it exited early / never produced a URL.
     */
    private suspend fun runOnce(bin: File, caBundle: File?, gen: Int): Boolean =
        withContext(Dispatchers.IO) {
            // Prefer QUIC; fall back to http2 when UDP is blocked on this network.
            val named = tunnelToken.isNotBlank()
            for (proto in listOf(null, "http2")) {
                if (!wantRunning || generation.get() != gen) return@withContext false
                val args = mutableListOf(bin.absolutePath, "tunnel", "--no-autoupdate")
                if (named) {
                    // Named tunnel: everything (hostname, ingress) is configured
                    // server-side; the token authenticates this connector and is
                    // passed via env var (see startProcess), never argv.
                    args.add("run")
                } else {
                    args.add("--url")
                    args.add(localUrl)
                }
                if (proto != null) {
                    args.add("--protocol")
                    args.add(proto)
                }
                val p = try {
                    startProcess(bin, args, caBundle, named)
                } catch (e: Exception) {
                    if (!wantRunning || generation.get() != gen) return@withContext false
                    continue // try next protocol / backoff
                }
                proc = p
                writePid(p)
                // BL1: stop()/restart chen giữa spawn và gán proc -> dọn ngay,
                // không để process lạ rò rỉ ngoài tầm kiểm soát.
                if (!wantRunning || generation.get() != gen) {
                    destroyNow(p)
                    clearPid(p)
                    if (proc === p) proc = null
                    return@withContext false
                }
                val urlFound = waitForUrl(p, gen)
                if (generation.get() != gen || !wantRunning) {
                    destroyNow(p)
                    clearPid(p)
                    if (proc === p) proc = null
                    return@withContext false
                }
                if (urlFound != null) {
                    setState(State.Running(urlFound))
                    // Stay until the process dies or we're asked to stop.
                    try {
                        p.waitFor()
                    } catch (_: Exception) {
                    }
                    clearPid(p)
                    if (proc === p) proc = null
                    return@withContext wantRunning && generation.get() == gen
                }
                killProc()
                clearPid(p)
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

    /**
     * Bug 8: the named-tunnel token travels via environment variables
     * (TUNNEL_TOKEN is the name cloudflared's CLI officially reads), never
     * via argv or an embedded shell string — both show up in `ps` output.
     */
    private fun startProcess(bin: File, args: List<String>, caBundle: File?, named: Boolean): Process {
        val pb = ProcessBuilder(args).apply {
            redirectErrorStream(false)
            // Go's TLS stack honors SSL_CERT_FILE; Android's system store is
            // invisible to it, so hand it our bundled Mozilla roots.
            if (caBundle != null) environment()["SSL_CERT_FILE"] = caBundle.absolutePath
            if (named) {
                environment()["TUNNEL_TOKEN"] = tunnelToken
                environment()["CLOUDFLARE_TUNNEL_TOKEN"] = tunnelToken
            }
        }
        return try {
            pb.start()
        } catch (e: Exception) {
            // Fallback: some ROMs block exec from the app data dir; use root.
            // The token is NOT embedded in the shell string: it is written to
            // a 600 file that only this shell reads into its environment.
            val envPrefix = if (caBundle != null) "SSL_CERT_FILE='${caBundle.absolutePath}' " else ""
            val tokenSetup = if (named) {
                val tf = writeTokenFile()
                "TUNNEL_TOKEN=$(cat '${tf.absolutePath}'); " +
                    "CLOUDFLARE_TUNNEL_TOKEN=$TUNNEL_TOKEN; " +
                    "export TUNNEL_TOKEN CLOUDFLARE_TUNNEL_TOKEN; "
            } else ""
            ProcessBuilder("su", "-c", envPrefix + tokenSetup + args.joinToString(" ") { "'$it'" }).start()
        }
    }

    /** Ghi token ra file 600 để shell root đọc — không bao giờ nhúng token vào argv/shell string. */
    private fun writeTokenFile(): File {
        val tf = File(appCtx.filesDir, TOKEN_FILE_NAME)
        tf.writeText(tunnelToken)
        tf.setReadable(false, false)
        tf.setReadable(true, true)
        tf.setWritable(false, false)
        tf.setWritable(true, true)
        tf.setExecutable(false, false)
        return tf
    }

    /**
     * Scan stderr for the public URL; null when the process exits first.
     * Named tunnels print no URL — they log "Registered tunnel connection"
     * instead, so map that to the fixed hostname.
     */
    private suspend fun waitForUrl(p: Process, gen: Int): String? {
        val deadline = System.currentTimeMillis() + URL_TIMEOUT_MS
        val fixedUrl =
            "https://$tunnelHostname".takeIf { tunnelToken.isNotBlank() && tunnelHostname.isNotBlank() }
        val reader = p.errorStream.bufferedReader()
        val tail = ArrayDeque<String>()
        try {
            while (System.currentTimeMillis() < deadline && generation.get() == gen) {
                ensureActive()
                if (!p.isAlive) {
                    lastErrTail = tail.toList()
                    return null
                }
                val line = if (reader.ready()) reader.readLine() else null
                if (line == null) {
                    delay(200) // should-fix 5: delay thay Thread.sleep -> hợp tác với cancel
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
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        lastErrTail = tail.toList()
        return null
    }

    private fun killProc() {
        val p = proc ?: return
        proc = null
        destroyNow(p)
        clearPid(p)
    }

    private fun destroyNow(p: Process) {
        try {
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        } catch (_: Exception) {
        }
    }
}
