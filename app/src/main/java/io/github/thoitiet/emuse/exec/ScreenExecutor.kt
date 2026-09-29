package io.github.thoitiet.emuse.exec

import org.json.JSONObject

object ScreenExecutor {
    /**
     * Set to true by MainActivity after the MediaProjection permission flow.
     * TODO: wire an ActivityResultLauncher with
     *   (getSystemService(MediaProjectionManager::class.java)).createScreenCaptureIntent()
     * and feed the resulting MediaProjection into a capture pipeline
     * (ImageReader -> PNG). Until then, capture() uses root screencap.
     */
    @Volatile
    var projectionGranted: Boolean = false

    fun capture(): JSONObject {
        // TODO: when projectionGranted is true, acquire the latest Image from the
        // ImageReader, compress to PNG and base64 it here.
        if (ShellExecutor.hasRoot()) {
            val tmp = "/data/local/tmp/emuse_cap.png"
            val r = ShellExecutor.exec("screencap -p $tmp && base64 $tmp; rm -f $tmp", asRoot = true)
            val b64 = r.stdout.filter { !it.isWhitespace() }
            if (r.exitCode == 0 && b64.isNotEmpty()) {
                val (w, h) = screenSize()
                return JSONObject()
                    .put("pngBase64", b64)
                    .put("width", w)
                    .put("height", h)
            }
        }
        throw IllegalStateException(
            "screen.capture unavailable: needs root, or a MediaProjection grant (see TODO above)",
        )
    }

    private fun screenSize(): Pair<Int, Int> {
        val r = ShellExecutor.exec("wm size")
        val m = Regex("""(\d+)x(\d+)""").find(r.stdout)
        return if (m != null) {
            Pair(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        } else {
            Pair(0, 0)
        }
    }
}
