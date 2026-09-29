package io.github.thoitiet.emuse.exec

import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ShellExecutor {
    data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int) {
        fun toJson(): JSONObject = JSONObject()
            .put("stdout", stdout)
            .put("stderr", stderr)
            .put("exitCode", exitCode)
    }

    fun hasRoot(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("which", "su"))
        p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) {
        false
    }

    fun exec(command: String, asRoot: Boolean = false, timeoutSec: Long = 60): ExecResult {
        val useRoot = asRoot && hasRoot()
        val argv = if (useRoot) arrayOf("su", "-c", command) else arrayOf("sh", "-c", command)
        return try {
            val proc = Runtime.getRuntime().exec(argv)
            val out = StringBuilder()
            val err = StringBuilder()
            val t1 = Thread {
                try {
                    proc.inputStream.bufferedReader().use { r -> r.forEachLine { out.appendLine(it) } }
                } catch (_: Exception) {
                }
            }
            val t2 = Thread {
                try {
                    proc.errorStream.bufferedReader().use { r -> r.forEachLine { err.appendLine(it) } }
                } catch (_: Exception) {
                }
            }
            t1.start()
            t2.start()
            val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            t1.join(5000)
            t2.join(5000)
            if (!finished) proc.destroyForcibly()
            ExecResult(out.toString(), err.toString(), if (finished) proc.exitValue() else 124)
        } catch (e: Exception) {
            ExecResult("", e.message ?: "exec failed", 127)
        }
    }
}
