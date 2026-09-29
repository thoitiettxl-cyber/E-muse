package io.github.thoitiet.emuse.exec

import android.os.SystemClock
import io.github.thoitiet.emuse.MuseAccessibilityService
import org.json.JSONObject

/**
 * System-level waits and panel actions (Eta's wait / wait_for_package /
 * open_system_panel). No Android context needed: foreground-package
 * detection consults both the accessibility service's tracked package and
 * a root `dumpsys window` parse each poll iteration.
 */
object SystemExecutor {
    private val FOCUS_PKG = Regex("""m(?:CurrentFocus|FocusedApp)=Window\{[^}]*\s([A-Za-z][\w]*(\.[\w]+)+)/""")

    /** Eta's wait: plain sleep so animations/network/page transitions can finish. */
    fun waitMs(durationMs: Int): JSONObject {
        val d = durationMs.coerceIn(100, 30_000)
        val start = SystemClock.elapsedRealtime()
        try {
            Thread.sleep(d.toLong())
        } catch (_: InterruptedException) {
            // Report what actually elapsed rather than the requested duration.
            Thread.currentThread().interrupt()
            return JSONObject()
                .put("ok", true)
                .put("duration_ms", (SystemClock.elapsedRealtime() - start).toInt())
                .put("interrupted", true)
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
            // Eta checks both sources each iteration: the a11y-tracked package
            // can lag (window-state events are best-effort).
            if (trackedPackage() == target || dumpsysPackage() == target) {
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

    private fun trackedPackage(): String? =
        MuseAccessibilityService.instance?.foregroundPackage

    private fun dumpsysPackage(): String? {
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
