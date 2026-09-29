package io.github.thoitiet.emuse

import android.content.Context
import android.os.Build
import io.github.thoitiet.emuse.exec.AppExecutor
import io.github.thoitiet.emuse.exec.ClipboardExecutor
import io.github.thoitiet.emuse.exec.FileExecutor
import io.github.thoitiet.emuse.exec.InputExecutor
import io.github.thoitiet.emuse.exec.ScreenExecutor
import io.github.thoitiet.emuse.exec.ShellExecutor
import io.github.thoitiet.emuse.exec.SystemExecutor
import io.github.thoitiet.emuse.exec.UiDumpExecutor
import io.github.thoitiet.emuse.exec.UiSnapshotter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class CommandDispatcher(
    ctx: Context,
    private val onEvent: (String) -> Unit = {},
) {
    private val appCtx = ctx.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val apps = AppExecutor(appCtx)
    private val files = FileExecutor(appCtx)
    private val clipboard = ClipboardExecutor(appCtx)

    init {
        ShellExecutor.reset()
        ScreenExecutor.configure(appCtx.resources.displayMetrics.densityDpi)
    }

    /**
     * Synchronous variant for the on-device MCP server: runs the command and
     * returns its result.
     */
    suspend fun executeCommand(
        cmd: DeviceCommand,
        timeoutMs: Long = 120_000,
    ): DeviceResult {
        return try {
            val result = withTimeout(timeoutMs.coerceIn(1_000L, 120_000L)) { execute(cmd) }
            DeviceResult(cmd.id, true, result)
        } catch (e: TimeoutCancellationException) {
            DeviceResult(cmd.id, false, error = "command timed out after ${timeoutMs}ms")
        } catch (e: Exception) {
            DeviceResult(cmd.id, false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    fun shutdown() {
        scope.cancel()
        ShellExecutor.close()
    }

    /** Short human-readable arg summary for the floating overlay, e.g. ` ls /sdcard`. */
    private fun shortArgs(cmd: DeviceCommand): String {
        val a = cmd.args
        val s = when (cmd.cmd) {
            Cmds.SHELL_EXEC -> a.optString("command")
            Cmds.APP_INFO, Cmds.APP_UNINSTALL, Cmds.APP_STOP -> a.optString("package")
            Cmds.APP_LIST -> a.optString("query").ifEmpty { if (a.optBoolean("system", false)) "system" else "" }
            Cmds.APP_START -> a.optString("package")
                .ifEmpty { a.optString("app_name") }
                .ifEmpty { a.optString("action") }
            Cmds.APP_OPEN_URI -> a.optString("uri").take(40)
            Cmds.FILE_LIST, Cmds.FILE_PULL, Cmds.FILE_PUSH, Cmds.FILE_DELETE -> a.optString("path")
            Cmds.INPUT_TAP -> "(${a.optInt("x")}, ${a.optInt("y")})"
            Cmds.INPUT_TAP_AREA -> "(${a.optInt("x1")},${a.optInt("y1")})-(${a.optInt("x2")},${a.optInt("y2")})"
            Cmds.INPUT_TAP_OBSERVE -> "element=${a.optString("elementId")}"
            Cmds.INPUT_LONG_PRESS -> "(${a.optInt("x")}, ${a.optInt("y")}) ${a.optInt("durationMs", 800)}ms"
            Cmds.INPUT_LONG_PRESS_ELEMENT -> "element=${a.optString("elementId")}"
            Cmds.INPUT_SCROLL -> a.optString("direction")
            Cmds.INPUT_SCROLL_ELEMENT -> "element=${a.optString("elementId")} ${a.optString("direction")}"
            Cmds.UI_WAIT_TEXT -> a.optString("text").take(20)
            Cmds.UI_WAIT_PACKAGE -> a.optString("package_name")
            Cmds.UI_OBSERVE -> "screenshot=${a.optBoolean("include_screenshot", false)}"
            Cmds.DEVICE_CONTEXT -> ""
            Cmds.INPUT_WAIT -> "${a.optInt("durationMs", 1_000)}ms"
            Cmds.SYSTEM_PANEL -> a.optString("panel")
            Cmds.INPUT_TEXT -> a.optString("text").take(20)
            Cmds.INPUT_REPLACE_TEXT -> a.optString("text").take(20)
            Cmds.INPUT_CLEAR_TEXT -> "element=${a.optString("elementId")}"
            Cmds.INPUT_PASTE -> a.optString("text").take(20)
            Cmds.CLIPBOARD_SET -> a.optString("text").take(20)
            Cmds.CLIPBOARD_GET -> ""
            Cmds.INPUT_KEY -> {
                val b = a.optString("button")
                if (b.isNotEmpty()) "button=$b" else "keyCode=${a.optInt("keyCode")}"
            }
            else -> ""
        }.take(40)
        return if (s.isNotEmpty()) " $s" else ""
    }

    private fun execute(cmd: DeviceCommand): Any? {
        val a = cmd.args
        return when (cmd.cmd) {
            Cmds.DEVICE_INFO -> deviceInfo()
            Cmds.DEVICE_CONTEXT -> deviceContext()
            Cmds.SHELL_EXEC -> ShellExecutor.exec(
                a.getString("command"),
                a.optBoolean("asRoot", false),
                a.optLong("timeoutMs", 30_000).coerceIn(1_000L, 120_000L),
            ).toJson()

            Cmds.APP_LIST -> apps.list(
                a.optBoolean("system", false),
                a.optString("query").ifEmpty { null },
                a.optInt("limit", 10),
            )
            Cmds.APP_INFO -> apps.info(a.getString("package"))
            Cmds.APP_INSTALL -> apps.install(a.getString("apkBase64"))
            Cmds.APP_UNINSTALL -> apps.uninstall(a.getString("package"))
            Cmds.APP_START -> apps.start(
                a.optString("package").ifEmpty { null },
                a.optString("action").ifEmpty { null },
                a.optString("uri").ifEmpty { null },
                a.optJSONObject("extras"),
                a.optString("app_name").ifEmpty { null },
            )
            Cmds.APP_OPEN_URI -> apps.openUri(a.getString("uri"))
            Cmds.APP_STOP -> apps.stop(a.getString("package"))

            Cmds.FILE_LIST -> files.list(a.getString("path"))
            Cmds.FILE_PULL -> files.pull(a.getString("path"))
            Cmds.FILE_PUSH -> files.push(
                a.getString("path"),
                a.getString("base64"),
                a.optString("mode").ifEmpty { null },
            )
            Cmds.FILE_DELETE -> files.delete(a.getString("path"))

            Cmds.SCREEN_CAPTURE -> ScreenExecutor.capture()

            Cmds.INPUT_TAP -> {
                val elementId = a.optString("elementId").ifEmpty { null }
                val obsId = a.optString("observationId").ifEmpty { null }
                if (elementId != null) UiSnapshotter.tapElement(elementId, obsId)
                else InputExecutor.tap(a.getInt("x"), a.getInt("y"))
            }
            Cmds.INPUT_TAP_AREA -> InputExecutor.tapArea(
                a.getInt("x1"), a.getInt("y1"),
                a.getInt("x2"), a.getInt("y2"),
            )
            Cmds.INPUT_TAP_OBSERVE -> UiSnapshotter.tapAndObserve(
                a.getString("elementId"),
                a.optString("observationId").ifEmpty { null },
            )
            Cmds.INPUT_SWIPE -> InputExecutor.swipe(
                a.getInt("x1"), a.getInt("y1"),
                a.getInt("x2"), a.getInt("y2"),
                a.optInt("durationMs", 300),
            )
            Cmds.INPUT_LONG_PRESS -> InputExecutor.longPress(
                a.getInt("x"), a.getInt("y"),
                a.optInt("durationMs", 800),
            )
            Cmds.INPUT_LONG_PRESS_ELEMENT -> UiSnapshotter.longPressElement(
                a.getString("elementId"),
                a.optString("observationId").ifEmpty { null },
                a.optInt("durationMs", 800),
            )
            Cmds.INPUT_SCROLL -> InputExecutor.scroll(a.getString("direction"))
            Cmds.INPUT_SCROLL_ELEMENT -> UiSnapshotter.scrollElement(
                a.getString("elementId"),
                a.optString("observationId").ifEmpty { null },
                a.getString("direction"),
            )
            Cmds.INPUT_KEY -> {
                val button = a.optString("button").ifEmpty { null }
                if (button != null) InputExecutor.pressButton(button)
                else if (a.has("keyCode")) InputExecutor.key(a.getInt("keyCode"))
                else throw IllegalArgumentException("input_key needs keyCode or button")
            }
            Cmds.INPUT_TEXT -> InputExecutor.text(a.getString("text"))
            Cmds.INPUT_REPLACE_TEXT -> InputExecutor.replaceText(
                a.getString("text"),
                a.optString("elementId").ifEmpty { null },
                a.optString("observationId").ifEmpty { null },
            )
            Cmds.INPUT_CLEAR_TEXT -> InputExecutor.clearText(
                a.optString("elementId").ifEmpty { null },
                a.optString("observationId").ifEmpty { null },
            )
            Cmds.INPUT_PASTE -> clipboard.paste(a.getString("text"))
            Cmds.CLIPBOARD_SET -> clipboard.set(a.getString("text"))
            Cmds.CLIPBOARD_GET -> clipboard.get()

            Cmds.UI_DUMP -> UiDumpExecutor.dump()
            Cmds.UI_SNAPSHOT -> UiSnapshotter.snapshot(
                query = a.optString("query").ifEmpty { null },
                compact = a.optBoolean("compact", false),
                maxNodes = a.optInt("maxNodes", 500),
            )
            Cmds.UI_OBSERVE -> observeScreen(
                includeScreenshot = a.optBoolean("include_screenshot", false),
                includeUiTree = a.optBoolean("include_ui_tree", true),
                maxNodes = a.optInt("max_nodes", 60),
            )
            Cmds.UI_WAIT_TEXT -> UiSnapshotter.waitForText(
                a.getString("text"),
                a.optLong("timeoutMs", 10_000L),
            )
            Cmds.UI_WAIT_PACKAGE -> SystemExecutor.waitForPackage(
                a.getString("package_name"),
                a.optLong("timeoutMs", 10_000L),
            )
            Cmds.INPUT_WAIT -> SystemExecutor.waitMs(a.optInt("durationMs", 1_000))
            Cmds.SYSTEM_PANEL -> SystemExecutor.openPanel(a.getString("panel"))

            else -> throw IllegalArgumentException("unknown cmd: ${cmd.cmd}")
        }
    }

    private fun deviceInfo(): JSONObject = JSONObject()
        .put("deviceId", android.provider.Settings.Secure.getString(
            appCtx.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        ) ?: "")
        .put("model", Build.MODEL ?: "")
        .put("manufacturer", Build.MANUFACTURER ?: "")
        .put("androidVersion", Build.VERSION.RELEASE ?: "")
        .put("sdkInt", Build.VERSION.SDK_INT)
        .put("root", ShellExecutor.hasRoot())
        .put("accessibility", MuseAccessibilityService.instance != null)
        .put("package", appCtx.packageName)

    /**
     * Eta's get_current_context: current time, time zone, weekday, locale,
     * plus last-known location when the app already holds a location
     * permission (no new manifest permission is requested here).
     */
    private fun deviceContext(): JSONObject {
        val now = java.time.ZonedDateTime.now()
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        return JSONObject()
            .put("datetime", now.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("timezone", now.zone.id)
            .put(
                "weekday",
                now.dayOfWeek.getDisplayName(
                    java.time.format.TextStyle.FULL,
                    java.util.Locale.ENGLISH,
                ),
            )
            .put("locale", java.util.Locale.getDefault().toLanguageTag())
            .put("location", lastKnownLocation())
    }

    private fun lastKnownLocation(): JSONObject {
        val fine = appCtx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = appCtx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            return JSONObject().put("status", "location_permission_required")
        }
        val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        var best: android.location.Location? = null
        for (provider in runCatching { lm.getProviders(true) }.getOrNull().orEmpty()) {
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || loc.time > best.time) best = loc
        }
        val b = best ?: return JSONObject().put("status", "location_unavailable")
        fun round5(v: Double): Double = kotlin.math.round(v * 100_000.0) / 100_000.0
        return JSONObject()
            .put("latitude", round5(b.latitude))
            .put("longitude", round5(b.longitude))
            .put("accuracy_m", if (b.hasAccuracy()) b.accuracy.toInt() else JSONObject.NULL)
            .put("age_s", (System.currentTimeMillis() - b.time) / 1_000L)
    }

    /**
     * Eta's observe_screen: one call returning the Eta-style UI tree
     * (ui_snapshot) and/or a screenshot, per flags. Reuses the existing
     * executors; McpHandler attaches the screenshot as an image content
     * block when present.
     */
    private fun observeScreen(
        includeScreenshot: Boolean,
        includeUiTree: Boolean,
        maxNodes: Int,
    ): JSONObject {
        val out = JSONObject()
        if (includeUiTree) {
            val snap = UiSnapshotter.snapshot(maxNodes = maxNodes.coerceIn(1, 120))
            out.put("observation_id", snap.optString("observation_id"))
            out.put("ui_tree", snap)
        } else {
            out.put("observation_id", UiSnapshotter.latestObservationId())
        }
        if (includeScreenshot) {
            out.put("screenshot", ScreenExecutor.capture())
        }
        return out
    }
}
