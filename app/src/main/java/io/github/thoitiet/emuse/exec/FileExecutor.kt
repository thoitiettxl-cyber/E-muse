package io.github.thoitiet.emuse.exec

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class FileExecutor(private val ctx: Context) {
    companion object {
        const val MAX_PULL_BYTES = 10L * 1024 * 1024
        private val PROTECTED = setOf(
            "/", "/data", "/sdcard", "/storage",
            "/storage/emulated", "/storage/emulated/0",
        )
    }

    private fun req(path: String): File {
        require(path.startsWith("/")) { "path must be absolute" }
        val f = File(path)
        require(f.canonicalPath !in PROTECTED) { "refusing to operate on protected path" }
        return f
    }

    fun list(path: String): JSONArray {
        val dir = req(path)
        require(dir.isDirectory) { "not a directory: $path" }
        val arr = JSONArray()
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            arr.put(
                JSONObject()
                    .put("name", f.name)
                    .put("path", f.absolutePath)
                    .put("isDir", f.isDirectory)
                    .put("size", f.length())
                    .put("modified", f.lastModified()),
            )
        }
        return arr
    }

    fun pull(path: String): JSONObject {
        val f = req(path)
        require(f.isFile) { "not a file: $path" }
        require(f.length() <= MAX_PULL_BYTES) { "file too large (>10MB)" }
        val bytes = f.readBytes()
        return JSONObject()
            .put("name", f.name)
            .put("size", bytes.size)
            .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    fun push(path: String, base64: String, mode: String?): JSONObject {
        val f = req(path)
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        try {
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
        } catch (e: Exception) {
            if (!ShellExecutor.hasRoot()) throw e
            val tmp = File(ctx.cacheDir, "push_${System.currentTimeMillis()}.bin")
            try {
                tmp.writeBytes(bytes)
                val r = ShellExecutor.exec(
                    RootCommands.copyTo(tmp.absolutePath, path, mode),
                    asRoot = true,
                )
                if (!r.ok) throw IllegalStateException("root push failed: ${r.stderr.trim().take(200)}")
            } finally {
                tmp.delete()
            }
        }
        return JSONObject().put("written", true).put("size", bytes.size)
    }

    fun delete(path: String): JSONObject {
        val f = req(path)
        require(f.exists()) { "not found: $path" }
        val ok = f.deleteRecursively()
        return JSONObject().put("deleted", ok)
    }
}
