package io.github.thoitiet.emuse.exec

import org.json.JSONObject

object ScreenExecutor {
    @Volatile
    private var densityDpi: Int = 0

    fun configure(densityDpi: Int) {
        this.densityDpi = densityDpi
    }

    fun capture(): JSONObject {
        // 1) MediaProjection (no root needed) once the user granted it.
        if (ScreenCapture.hasProjection()) {
            val (w, h) = DeviceScreen.size()
            val png = runCatching {
                ScreenCapture.capturePng(w, h, densityDpi.takeIf { it > 0 } ?: 420)
            }.getOrNull()
            if (png != null && png.isNotEmpty()) {
                // capturePng downscales to 1080p max: report actual dimensions.
                val (rw, rh) = ScreenCapture.pngSize(png) ?: (w to h)
                return JSONObject()
                    .put("pngBase64", android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP))
                    .put("width", rw)
                    .put("height", rh)
                    .put("via", "mediaProjection")
            }
        }
        // 2) Root screencap fallback.
        if (ShellExecutor.hasRoot()) {
            val tmp = "/data/local/tmp/emuse_cap.png"
            val r = ShellExecutor.exec(
                "${RootCommands.screencapTo(tmp)} && base64 $tmp; rm -f $tmp",
                asRoot = true,
            )
            val b64 = r.stdout.filter { !it.isWhitespace() }
            if (r.exitCode == 0 && b64.isNotEmpty()) {
                val bytes = runCatching {
                    android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                }.getOrNull()
                if (bytes != null) {
                    // Same 1080p downscale as the MediaProjection path.
                    val scaled = ScreenCapture.downscalePng(bytes)
                    val (rw, rh) = ScreenCapture.pngSize(scaled) ?: DeviceScreen.size()
                    return JSONObject()
                        .put("pngBase64", android.util.Base64.encodeToString(scaled, android.util.Base64.NO_WRAP))
                        .put("width", rw)
                        .put("height", rh)
                        .put("via", "root")
                }
            }
        }
        throw IllegalStateException(
            "screen.capture unavailable: grant MediaProjection or root",
        )
    }
}
