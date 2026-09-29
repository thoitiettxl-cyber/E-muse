package io.github.thoitiet.emuse.exec

import android.os.SystemClock
import io.github.thoitiet.emuse.MuseAccessibilityService
import org.json.JSONObject

/**
 * System-level waits and panel actions (Eta's wait / wait_for_package /
 * open_system_panel). No Android context needed: foreground-package
 * detection reads the accessibility service's tracked package first and
 * falls back to a root `dumpsys window` parse.
 */
object SystemExecutor {
    private val FOCUS_PKG = Regex("""m(?:CurrentFocus|FocusedApp)=Window\{[^}]*\s([A-Za-z][\w]*(\.[\w]+)+)/""")

    /** Eta's wait: plain sleep so animations/network/page transitions can finish. */
    fun waitMs(durationMs: Int): JSONObject {
        val d = durationMs.coerceIn(100, 30_000)
        try {
            Thread.sleep(d.toLong())
        } catch (_: InterruptedException) {
        }
        return JSONObject().put("ok", true).put("duration_ms", d)
    }

    /**
     * Eta's wait_for_package: poll until [packageName] is in the foreground.
     * Use after app_start/open_uri to confirm the target app opened.
     */
    fun waitForPackage(packageName: String, timeoutMs: Long): JSONObject {
        val target = packageName.trim()
        require(target.isNotEmpty()) { "package_name empty" }
        val timeout = timeoutMs.coerceIn(500L, 60_000L)
        val deadline = SystemClock.elapsedRealtime() + timeout
        var attempts = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            attempts++
            if (foregroundPackage() == target) {
                return JSONObject()
                    .put("ok", true)
                    .put("package_name", target)
                    .put("attempts", attempts)
            }
            try {
                Thread.sleep(350)
            } catch (_: InterruptedException) {
                break
            }
        }
        return JSONObject()
            .put("ok", false)
            .put("code", "TIMEOUT")
            .put("package_name", target)
            .put("attempts", attempts)
    }

    /** Eta's open_system_panel: notification shade or quick settings. */
    fun openPanel(panel: String): JSONObject {
        return when (panel.lowercase()) {
            "notifications", "notification" -> InputExecutor.pressButton("NOTIFICATIONS")
            "quick_settings", "quicksettings", "settings" -> InputExecutor.pressButton("QUICK_SETTINGS")
            else -> throw IllegalArgumentException("panel must be notifications or quick_settings")
        }.put("panel", panel.lowercase())
    }

    private fun foregroundPackage(): String? {
        MuseAccessibilityService.instance?.foregroundPackage?.let { return it }
        // Root fallback: parse the focused window out of dumpsys.
        val r = runCatching {
            ShellExecutor.exec(
                "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'",
                asRoot = true,
            )
        }.getOrNull() ?: return null
        if (!r.ok) return null
        return FOCUS_PKG.find(r.stdout)?.groupValues?.get(1)
    }
}
