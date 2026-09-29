package io.github.thoitiet.emuse.exec

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shell execution with Eta-grade mechanics (BoundedRootCommandExecutor):
 * bounded output, concurrent stream draining, timeout kill, process registry.
 *
 * Unlike Eta (fixed internal commands only), E-Muse intentionally allows
 * arbitrary commands here: this build is a full remote-control tool for its
 * owner via MCP (approved for testing). The safety boundary is the API key,
 * not the command list.
 */
object ShellExecutor {
    data class ExecResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val timedOut: Boolean = false,
        val truncated: Boolean = false,
    ) {
        val ok: Boolean get() = exitCode == 0 && !timedOut
        fun toJson(): JSONObject = JSONObject()
            .put("stdout", stdout)
            .put("stderr", stderr)
            .put("exitCode", exitCode)
            .put("timedOut", timedOut)
            .put("truncated", truncated)
    }

    private val active = ConcurrentHashMap.newKeySet<Process>()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var rootCache: Boolean? = null

    /** True when `su -c 'id -u'` reports uid 0. Result is cached. */
    fun hasRoot(): Boolean {
        rootCache?.let { return it }
        val ok = runCatching {
            exec("id -u", asRoot = true, timeoutMs = 5_000).stdout.trim() == "0"
        }.getOrDefault(false)
        rootCache = ok
        return ok
    }

    fun exec(
        command: String,
        asRoot: Boolean = false,
        timeoutMs: Long = 30_000,
        maxOutputBytes: Int = 256 * 1024,
    ): ExecResult {
        if (closed.get()) return ExecResult("", "executor closed", -1)
        val argv = if (asRoot) arrayOf("su", "-c", command) else arrayOf("sh", "-c", command)
        val proc = runCatching { ProcessBuilder(*argv).start() }.getOrElse {
            return ExecResult("", "process start failed: ${it.message}", -1)
        }
        if (!active.add(proc) || closed.get()) {
            terminate(proc)
            return ExecResult("", "executor closed", -1)
        }
        val timeout = timeoutMs.coerceIn(1_000L, 120_000L)
        val pool = Executors.newFixedThreadPool(2)
        return try {
            val outF = pool.submit<Bounded> { proc.inputStream.use { it.readBounded(maxOutputBytes) } }
            val errF = pool.submit<Bounded> { proc.errorStream.use { it.readBounded(maxOutputBytes) } }
            val finished = runCatching {
                proc.waitFor(timeout, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (!finished) terminate(proc)
            val out = runCatching { outF.get(2, TimeUnit.SECONDS) }.getOrDefault(Bounded.EMPTY)
            val err = runCatching { errF.get(2, TimeUnit.SECONDS) }.getOrDefault(Bounded.EMPTY)
            ExecResult(
                stdout = out.text,
                stderr = err.text,
                exitCode = if (finished) runCatching { proc.exitValue() }.getOrDefault(-1) else 124,
                timedOut = !finished,
                truncated = out.truncated || err.truncated,
            )
        } finally {
            active.remove(proc)
            terminate(proc)
            pool.shutdownNow()
        }
    }

    /** Terminate every tracked process; called on service shutdown. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.toList().forEach(::terminate)
        active.clear()
    }

    /**
     * Re-arm after [close]. A fresh CommandDispatcher (service restart in the
     * same process) must call this, otherwise every exec returns
     * "executor closed".
     */
    fun reset() {
        closed.set(false)
        rootCache = null
    }

    private fun terminate(proc: Process) {
        if (proc.isAlive) {
            runCatching { proc.destroy() }
            runCatching { proc.waitFor(250, TimeUnit.MILLISECONDS) }
        }
        if (proc.isAlive) runCatching { proc.destroyForcibly() }
        runCatching { proc.outputStream.close() }
        runCatching { proc.inputStream.close() }
        runCatching { proc.errorStream.close() }
    }

    private fun InputStream.readBounded(maxBytes: Int): Bounded {
        val limit = maxBytes.coerceIn(1, 2 * 1024 * 1024)
        val collected = ByteArrayOutputStream(limit.coerceAtMost(32 * 1024))
        val buf = ByteArray(8 * 1024)
        var truncated = false
        while (true) {
            val n = read(buf)
            if (n < 0) break
            val remaining = limit - collected.size()
            if (remaining > 0) collected.write(buf, 0, n.coerceAtMost(remaining))
            if (n > remaining) truncated = true
        }
        return Bounded(collected.toString(StandardCharsets.UTF_8.name()), truncated)
    }

    private data class Bounded(val text: String, val truncated: Boolean) {
        companion object {
            val EMPTY = Bounded("", false)
        }
    }
}
