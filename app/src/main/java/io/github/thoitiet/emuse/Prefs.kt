package io.github.thoitiet.emuse

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("emuse", Context.MODE_PRIVATE)

    var workerUrl: String
        get() = sp.getString(
            "worker_url",
            "https://e-muse-mcp.ngthanhhuy951.workers.dev/device/connect",
        ) ?: ""
        set(v) = sp.edit().putString("worker_url", v).apply()

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v).apply()

    var overlayEnabled: Boolean
        get() = sp.getBoolean("overlay_enabled", false)
        set(v) = sp.edit().putBoolean("overlay_enabled", v).apply()
}
