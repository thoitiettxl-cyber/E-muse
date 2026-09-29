package io.github.thoitiet.emuse

import android.content.Context
import android.os.Build
import io.github.thoitiet.emuse.exec.AppExecutor
import io.github.thoitiet.emuse.exec.ClipboardExecutor
import io.github.thoitiet.emuse.exec.DeviceExecutor
import io.github.thoitiet.emuse.exec.FileExecutor
import io.github.thoitiet.emuse.exec.InputExecutor
import io.github.thoitiet.emuse.exec.ScreenExecutor
import io.github.thoitiet.emuse.exec.SensitiveActionExecutor
import io.github.thoitiet.emuse.exec.SensitiveReadExecutor
import io.github.thoitiet.emuse.exec.ColorOsExecutor
import io.github.thoitiet.emuse.exec.ShellExecutor
import io.github.thoitiet.emuse.exec.SystemExecutor
import io.github.thoitiet.emuse.exec.UiDumpExecutor
import io.github.thoitiet.emuse.exec.UiSnapshotter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class CommandDispatcher(
    ctx: Context,
    private val onEvent: (String) -> Unit = {},
) {
    private val appCtx = ctx.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = Prefs(appCtx)
    private val apps = AppExecutor(appCtx)
    private val files = FileExecutor(appCtx)
    private val clipboard = ClipboardExecutor(appCtx)
    private val device = DeviceExecutor(appCtx)
    private val sensitiveRead = SensitiveReadExecutor(appCtx)
    private val colorOs = ColorOsExecutor(appCtx)
    private val sensitiveAction = SensitiveActionExecutor(appCtx)

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
        // Layer 2 of group gating (defense in depth; layer 1 is McpHandler).
        // Unknown cmds keep their existing behavior (error from execute()).
        val toolName = TOOL_NAME_BY_CMD[cmd.cmd]
        if (toolName != null) {
            groupBlockReason(toolName) { prefs.isGroupEnabled(it) }?.let { reason ->
                onEvent("⛔ $toolName: ${reason.take(100)}")
                return DeviceResult(cmd.id, false, error = reason)
            }
        }
        // Drive the floating overlay: start line (▶ also flips the orb busy),
        // then ✔/✖ so the bubble shows the command log including errors.
        val label = toolName ?: cmd.cmd
        onEvent("▶ $label${shortArgs(cmd)}")
        return try {
            val result = withTimeout(timeoutMs.coerceIn(1_000L, 120_000L)) { execute(cmd) }
            onEvent("✔ $label")
            DeviceResult(cmd.id, true, result)
        } catch (e: TimeoutCancellationException) {
            val err = "command timed out after ${timeoutMs}ms"
            onEvent("✖ $label: $err")
            DeviceResult(cmd.id, false, error = err)
        } catch (e: Exception) {
            val err = e.message ?: e.javaClass.simpleName
            onEvent("✖ $label: ${err.take(120)}")
            DeviceResult(cmd.id, false, error = err)
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
            Cmds.SHELL_EXEC -> "cmd=<${a.optString("command").length} chars>"
            Cmds.APP_INFO, Cmds.APP_UNINSTALL, Cmds.APP_STOP -> a.optString("package")
            Cmds.APP_LIST -> a.optString("query").ifEmpty { if (a.optBoolean("system", false)) "system" else "" }
            Cmds.APP_START -> a.optString("package")
                .ifEmpty { a.optString("app_name") }
                .ifEmpty { a.optString("action") }
            Cmds.APP_OPEN_URI -> "uri=<${a.optString("uri").length} chars>"
            Cmds.FILE_LIST, Cmds.FILE_PULL, Cmds.FILE_PUSH, Cmds.FILE_DELETE -> a.optString("path")
            Cmds.INPUT_TAP -> "(${a.optInt("x")}, ${a.optInt("y")})"
            Cmds.INPUT_TAP_AREA -> "(${a.optInt("x1")},${a.optInt("y1")})-(${a.optInt("x2")},${a.optInt("y2")})"
            Cmds.INPUT_TAP_OBSERVE -> "element=${a.optString("elementId")}"
            Cmds.INPUT_LONG_PRESS -> "(${a.optInt("x")}, ${a.optInt("y")}) ${a.optInt("durationMs", 800)}ms"
            Cmds.INPUT_LONG_PRESS_ELEMENT -> "element=${a.optString("elementId")}"
            Cmds.INPUT_SCROLL -> a.optString("direction")
            Cmds.INPUT_SCROLL_ELEMENT -> "element=${a.optString("elementId")} ${a.optString("direction")}"
            Cmds.UI_WAIT_TEXT -> "text=<${a.optString("text").length} chars>"
            Cmds.UI_WAIT_PACKAGE -> a.optString("package_name")
            Cmds.UI_OBSERVE -> "screenshot=${a.optBoolean("include_screenshot", false)}"
            Cmds.DEVICE_CONTEXT -> ""
            Cmds.DEVICE_STATUS -> ""
            Cmds.NETWORK_INFO -> ""
            Cmds.VOLUME_GET -> ""
            Cmds.VOLUME_SET -> "${a.optString("stream")}=${a.optInt("percent")}%"
            Cmds.MEDIA_CONTROL -> a.optString("action")
            Cmds.ALARM_SET -> "${a.optInt("hour")}:${a.optInt("minute")}"
            Cmds.TIMER_SET -> "${a.optInt("duration_seconds")}s"
            Cmds.ALARM_LIST -> ""
            Cmds.MEMORY_TOP_APPS -> "n=${a.optInt("limit", 10)}"
            Cmds.STORAGE_TOP_APPS -> "n=${a.optInt("limit", 10)}"

            // P5 sensitive-read
            Cmds.CONTACTS_SEARCH,
            Cmds.CALLLOG_SEARCH,
            Cmds.SMS_SEARCH,
            Cmds.CALENDAR_SEARCH,
            Cmds.MEDIA_SEARCH,
            Cmds.AUDIO_SEARCH,
            Cmds.RECORDINGS_SEARCH,
            Cmds.FILES_SEARCH,
            Cmds.DOWNLOADS_SEARCH -> "q=${a.optString("query", "").take(12)} n=${a.optInt("limit", 10)}"
            Cmds.LOCATION_GET -> ""
            Cmds.APP_ACTIVITY_RECENT,
            Cmds.APP_USAGE_SUMMARY -> "age=${a.optInt("max_age_hours", 24)}h n=${a.optInt("limit", 20)}"
            Cmds.NOTIFICATIONS_RECENT -> "pkg=${a.optString("package_name", "")} n=${a.optInt("limit", 10)}"
            Cmds.WIFI_CREDENTIALS -> "ssid=${a.optString("ssid", "")} n=${a.optInt("limit", 20)}"
            Cmds.SMS_CODE_READ -> "age=${a.optInt("max_age_minutes", 10)}m"
            Cmds.LOGCAT_GET -> "n=${a.optInt("max_lines", 200)} q=${a.optString("query", "").take(12)}"
            Cmds.SETTING_GET -> "${a.optString("namespace", "")}.${a.optString("key", "")}"
            Cmds.DEVICE_ENVIRONMENT -> ""
            Cmds.TIMER_ACTIVE_LIST -> "n=${a.optInt("limit", 20)}"

            // P7 ColorOS-specific (queries truncated: overlay is user-visible)
            Cmds.COLOROS_NOTES_SEARCH,
            Cmds.COLOROS_RECORDINGS_SEARCH,
            Cmds.RECORDING_SUMMARIES_SEARCH,
            Cmds.COLOROS_MEMORIES_SEARCH,
            Cmds.PERSONAL_ORDERS_SEARCH,
            Cmds.SAVED_PLACES_SEARCH -> "q=${a.optString("query", "").take(12)} n=${a.optInt("limit", 10)}"
            Cmds.SETTING_SET -> "${a.optString("namespace", "")}.${a.optString("key", "")}"
            Cmds.DEVICE_STATE_SET ->
                "${a.optString("target", "")}=${if (a.optBoolean("enabled", false)) "on" else "off"}"
            Cmds.APP_STATE_CONTROL -> "${a.optString("action", "")} ${a.optString("package_name", "")}"
            Cmds.INPUT_WAIT -> "${a.optInt("durationMs", 1_000)}ms"
            Cmds.SYSTEM_PANEL -> a.optString("panel")
            // Text content is never printed on the user-visible overlay
            // (shoulder-surfing risk: passwords, OTPs, private messages).
            // Length only.
            Cmds.INPUT_TEXT -> "text=${a.optString("text").length} chars"
            Cmds.INPUT_REPLACE_TEXT -> "text=${a.optString("text").length} chars"
            Cmds.INPUT_CLEAR_TEXT -> "element=${a.optString("elementId")}"
            Cmds.INPUT_PASTE -> "text=${a.optString("text").length} chars"
            Cmds.CLIPBOARD_SET -> "text=${a.optString("text").length} chars"
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

            Cmds.DEVICE_STATUS -> device.status()
            Cmds.NETWORK_INFO -> device.networkInfo()
            Cmds.VOLUME_GET -> device.getVolume()
            Cmds.VOLUME_SET -> device.setVolume(
                a.getString("stream"),
                a.getInt("percent"),
            )
            Cmds.MEDIA_CONTROL -> device.mediaControl(a.getString("action"))
            Cmds.ALARM_SET -> device.setAlarm(
                a.getInt("hour"),
                a.getInt("minute"),
                a.optString("label").ifEmpty { null },
                a.optBoolean("vibrate", true),
                a.optJSONArray("repeat_days")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                },
            )
            Cmds.TIMER_SET -> device.setTimer(
                a.getInt("duration_seconds"),
                a.optString("label").ifEmpty { null },
            )
            Cmds.ALARM_LIST -> device.listAlarms(
                a.optBoolean("enabled_only", true), a.optInt("limit", 20),
            )
            Cmds.MEMORY_TOP_APPS -> device.topMemoryApps(a.optInt("limit", 10))
            Cmds.STORAGE_TOP_APPS -> device.topStorageApps(a.optInt("limit", 10))

            // P5: Eta-parity sensitive-read tools
            Cmds.CONTACTS_SEARCH -> sensitiveRead.searchContacts(a)
            Cmds.CALLLOG_SEARCH -> sensitiveRead.searchCallHistory(a)
            Cmds.SMS_SEARCH -> sensitiveRead.searchMessages(a)
            Cmds.CALENDAR_SEARCH -> sensitiveRead.searchCalendarEvents(a)
            Cmds.MEDIA_SEARCH -> sensitiveRead.searchMedia(a)
            Cmds.AUDIO_SEARCH -> sensitiveRead.searchAudio(a, recordingsOnly = false, tool = "search_audio")
            Cmds.RECORDINGS_SEARCH -> sensitiveRead.searchAudio(a, recordingsOnly = true, tool = "search_recordings")
            Cmds.FILES_SEARCH -> sensitiveRead.searchFiles(a)
            Cmds.DOWNLOADS_SEARCH -> sensitiveRead.searchDownloads(a)
            Cmds.LOCATION_GET -> sensitiveRead.getCurrentLocation(a)
            Cmds.APP_ACTIVITY_RECENT -> sensitiveRead.recentAppActivity(a)
            Cmds.APP_USAGE_SUMMARY -> sensitiveRead.appUsageSummary(a)
            Cmds.NOTIFICATIONS_RECENT -> sensitiveRead.recentNotifications(a)
            Cmds.WIFI_CREDENTIALS -> sensitiveRead.wifiCredentials(a)
            Cmds.SMS_CODE_READ -> sensitiveRead.readSmsCode(a)
            Cmds.LOGCAT_GET -> sensitiveRead.getLogcat(a)
            Cmds.SETTING_GET -> sensitiveRead.getSetting(a)
            Cmds.DEVICE_ENVIRONMENT -> sensitiveRead.getDeviceEnvironment(a)

            // P7: Eta-parity ColorOS-specific tools
            Cmds.COLOROS_NOTES_SEARCH -> colorOs.searchColorOsNotes(a)
            Cmds.COLOROS_RECORDINGS_SEARCH -> colorOs.searchColorOsRecordings(a)
            Cmds.RECORDING_SUMMARIES_SEARCH -> colorOs.searchRecordingSummaries(a)
            Cmds.COLOROS_MEMORIES_SEARCH -> colorOs.searchColorOsMemories(a)
            Cmds.PERSONAL_ORDERS_SEARCH -> colorOs.searchPersonalOrders(a)
            Cmds.SAVED_PLACES_SEARCH -> colorOs.searchSavedPlaces(a)
            Cmds.TIMER_ACTIVE_LIST -> colorOs.listActiveTimers(a)

            // P6 sensitive-action
            Cmds.SETTING_SET -> sensitiveAction.setSetting(
                a.getString("namespace"), a.getString("key"), a.getString("value"),
            )
            Cmds.DEVICE_STATE_SET -> sensitiveAction.setDeviceState(
                a.getString("target"), a.getBoolean("enabled"),
            )
            Cmds.APP_STATE_CONTROL -> sensitiveAction.appStateControl(
                a.getString("package_name"), a.getString("action"),
            )

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
