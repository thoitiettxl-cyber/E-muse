package io.github.thoitiet.emuse.exec

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.AlarmClock
import android.view.KeyEvent
import java.util.Calendar
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Eta parity: device-direct tools (alarm/timer, device & network status,
 * volume, media control, memory/storage summaries).
 *
 * Pure Android APIs; no new manifest permissions. Root is used only as a
 * fallback for data the platform hides from apps (Wi-Fi SSID without
 * location permission, `ps` RSS, `dumpsys diskstats`) and only when
 * [ShellExecutor.hasRoot] is true.
 */
class DeviceExecutor(private val appCtx: Context) {

    private fun err(code: String, message: String, tool: String): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .put("tool", tool)

    private fun ok(tool: String): JSONObject =
        JSONObject().put("ok", true).put("tool", tool)

    // ------------------------------------------------------------------
    // device_status (Eta parity)
    // ------------------------------------------------------------------

    fun status(): JSONObject {
        val battery = appCtx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val activity = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val storage = StatFs(Environment.getDataDirectory().absolutePath)
        return JSONObject()
            .put("ok", true)
            .put("tool", "device_status")
            .put("manufacturer", Build.MANUFACTURER ?: "")
            .put("model", Build.MODEL ?: "")
            .put("android_version", Build.VERSION.RELEASE ?: "")
            .put("sdk", Build.VERSION.SDK_INT)
            .put("security_patch", Build.VERSION.SECURITY_PATCH ?: "")
            .put("uptime_ms", SystemClock.elapsedRealtime())
            .put("battery_percent", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("charging", battery.isCharging)
            .put("memory_available_bytes", memory.availMem)
            .put("memory_total_bytes", memory.totalMem)
            .put("storage_available_bytes", storage.availableBytes)
            .put("storage_total_bytes", storage.totalBytes)
    }

    // ------------------------------------------------------------------
    // network_info (Eta parity)
    // ------------------------------------------------------------------

    fun networkInfo(): JSONObject {
        val connectivity =
            appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        val transports = JSONArray().also { array ->
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) array.put("wifi")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) array.put("cellular")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) array.put("ethernet")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) array.put("vpn")
        }
        @Suppress("DEPRECATION")
        val wifi = appCtx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val info = runCatching { wifi.connectionInfo }.getOrNull()
        val result = JSONObject()
            .put("ok", true)
            .put("tool", "network_info")
            .put("connected", capabilities != null)
            .put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("metered", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true)
            .put("transports", transports)
            .put("wifi_enabled", wifi.isWifiEnabled)
        var ssid = info?.ssid
            ?.takeUnless { it == WifiManager.UNKNOWN_SSID }
            ?.trim('"')
            ?.ifEmpty { null }
        var rssi: Int? = info?.rssi?.takeUnless { it == -127 }
        // Platform hides the SSID from apps without location permission;
        // fall back to a root `cmd wifi status` parse like Eta does.
        if ((ssid == null || rssi == null) && ShellExecutor.hasRoot()) {
            val dump = ShellExecutor.exec("cmd wifi status", asRoot = true, maxOutputBytes = 32 * 1024)
            if (dump.ok) {
                if (ssid == null) {
                    ssid = WIFI_STATUS_SSID.find(dump.stdout)
                        ?.groupValues?.get(1)?.trim()?.trim('"')?.ifEmpty { null }
                }
                if (rssi == null) {
                    rssi = WIFI_STATUS_RSSI.find(dump.stdout)?.groupValues?.get(1)?.toIntOrNull()
                }
            }
        }
        ssid?.let { result.put("ssid", it) }
        rssi?.let { result.put("rssi_dbm", it) }
        return result
    }

    // ------------------------------------------------------------------
    // volume (Eta parity: set_volume; get_volume is an E-muse extension)
    // ------------------------------------------------------------------

    private fun streamOf(name: String): Int = when (name.lowercase(Locale.ROOT)) {
        "media" -> AudioManager.STREAM_MUSIC
        "alarm" -> AudioManager.STREAM_ALARM
        "ring" -> AudioManager.STREAM_RING
        "notification" -> AudioManager.STREAM_NOTIFICATION
        else -> throw IllegalArgumentException("stream must be media/alarm/ring/notification")
    }

    fun getVolume(): JSONObject {
        val audio = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streams = JSONObject()
        for (name in listOf("media", "alarm", "ring", "notification")) {
            val s = streamOf(name)
            val max = audio.getStreamMaxVolume(s).coerceAtLeast(1)
            val level = audio.getStreamVolume(s)
            streams.put(
                name,
                JSONObject()
                    .put("level", level)
                    .put("max_level", max)
                    .put("percent", (level * 100 / max).coerceIn(0, 100)),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "get_volume")
            .put("streams", streams)
    }

    fun setVolume(stream: String, percent: Int): JSONObject {
        val streamName = stream.lowercase(Locale.ROOT)
        val s = try {
            streamOf(streamName)
        } catch (e: IllegalArgumentException) {
            return err("INVALID_ARGUMENT", e.message ?: "bad stream", "set_volume")
        }
        if (percent !in 0..100) {
            return err("INVALID_ARGUMENT", "percent must be 0-100", "set_volume")
        }
        val audio = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(s).coerceAtLeast(1)
        val level = ((percent / 100.0) * max).toInt().coerceIn(0, max)
        return runCatching {
            audio.setStreamVolume(s, level, 0)
            ok("set_volume")
                .put("stream", streamName)
                .put("percent", percent)
                .put("level", audio.getStreamVolume(s))
                .put("max_level", max)
        }.getOrElse {
            err("VOLUME_CHANGE_FAILED", "system rejected the volume change", "set_volume")
        }
    }

    // ------------------------------------------------------------------
    // media_control (Eta parity)
    // ------------------------------------------------------------------

    fun mediaControl(action: String): JSONObject {
        val actionName = action.lowercase(Locale.ROOT)
        val keyCode = when (actionName) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return err(
                "INVALID_ARGUMENT",
                "action must be play/pause/play_pause/next/previous/stop",
                "media_control",
            )
        }
        val audio = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            ok("media_control").put("action", actionName)
        }.getOrElse {
            err("MEDIA_CONTROL_FAILED", it.message ?: "dispatch failed", "media_control")
        }
    }

    // ------------------------------------------------------------------
    // alarm / timer (Eta parity, AlarmClock intents)
    // ------------------------------------------------------------------

    private fun startClockIntent(
        directIntent: Intent,
        fallbackAction: String,
        tool: String,
    ): JSONObject {
        val pm = appCtx.packageManager
        @Suppress("DEPRECATION")
        val resolves: (Intent) -> Boolean = { intent ->
            if (Build.VERSION.SDK_INT >= 33) {
                pm.resolveActivity(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0)) != null
            } else {
                pm.resolveActivity(intent, 0) != null
            }
        }
        val direct = directIntent.takeIf { resolves(it) }
        if (direct != null && runCatching { appCtx.startActivity(direct) }.isSuccess) {
            return ok(tool).put("mode", "direct")
        }
        val fallback = Intent(fallbackAction)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .takeIf { resolves(it) }
        if (fallback != null && runCatching { appCtx.startActivity(fallback) }.isSuccess) {
            return JSONObject()
                .put("ok", false)
                .put("code", "DIRECT_CLOCK_ACTION_FAILED")
                .put("message", "system did not accept the direct action; opened the clock UI instead")
                .put("tool", tool)
                .put("mode", "ui_fallback")
        }
        return err("CLOCK_UNAVAILABLE", "no clock app can handle this request", tool)
    }

    fun setAlarm(
        hour: Int,
        minute: Int,
        label: String?,
        vibrate: Boolean,
        repeatDays: List<String>?,
    ): JSONObject {
        if (hour !in 0..23 || minute !in 0..59) {
            return err("INVALID_ARGUMENT", "hour must be 0-23 and minute 0-59", "set_alarm")
        }
        val days = repeatDays?.map { day ->
            when (day.lowercase(Locale.ROOT)) {
                "mon" -> Calendar.MONDAY
                "tue" -> Calendar.TUESDAY
                "wed" -> Calendar.WEDNESDAY
                "thu" -> Calendar.THURSDAY
                "fri" -> Calendar.FRIDAY
                "sat" -> Calendar.SATURDAY
                "sun" -> Calendar.SUNDAY
                else -> return err(
                    "INVALID_ARGUMENT",
                    "repeat_days entries must be mon/tue/wed/thu/fri/sat/sun",
                    "set_alarm",
                )
            }
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_VIBRATE, vibrate)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        label?.trim()?.takeIf { it.isNotEmpty() }?.let {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, it.take(100))
        }
        days?.takeIf { it.isNotEmpty() }?.let {
            intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(it))
        }
        return startClockIntent(intent, AlarmClock.ACTION_SHOW_ALARMS, "set_alarm")
            .put("hour", hour)
            .put("minute", minute)
    }

    fun setTimer(seconds: Int, label: String?): JSONObject {
        if (seconds !in 1..86_400) {
            return err("INVALID_ARGUMENT", "duration_seconds must be 1-86400", "set_timer")
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        label?.trim()?.takeIf { it.isNotEmpty() }?.let {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, it.take(100))
        }
        return startClockIntent(intent, AlarmClock.ACTION_SHOW_TIMERS, "set_timer")
            .put("duration_seconds", seconds)
    }

    /**
     * Full alarm listing from the ColorOS clock app's private database
     * (Eta parity, P7), falling back to the next system alarm via
     * AlarmManager when root/the OEM database is unavailable.
     */
    fun listAlarms(enabledOnly: Boolean = true, limit: Int = 20): JSONObject {
        ColorOsExecutor(appCtx).readClockAlarms(enabledOnly, limit)?.let { return it }
        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next = runCatching { am.nextAlarmClock }.getOrNull()
        val items = JSONArray()
        next?.let {
            items.put(
                JSONObject()
                    .put("trigger_time_ms", it.triggerTime)
                    .put(
                        "trigger_time",
                        java.time.Instant.ofEpochMilli(it.triggerTime).toString(),
                    ),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "list_alarms")
            .put("items", items)
            .put(
                "note",
                "next system alarm only; full alarm/timer listing needs the clock app database (ColorOS, planned separately)",
            )
    }

    // ------------------------------------------------------------------
    // top_memory_apps / top_storage_apps (Eta parity, root-backed)
    // ------------------------------------------------------------------

    fun topMemoryApps(limit: Int): JSONObject {
        val n = limit.coerceIn(1, 30)
        data class Proc(val pid: Int, val name: String, val rssBytes: Long)
        val items: List<Proc> = if (ShellExecutor.hasRoot()) {
            val r = ShellExecutor.exec(
                "ps -A -o PID,RSS,NAME",
                asRoot = true,
                maxOutputBytes = 512 * 1024,
            )
            if (!r.ok) {
                return err("MEMORY_STATS_FAILED", "ps failed: ${r.stderr.take(120)}", "top_memory_apps")
            }
            r.stdout.lineSequence()
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 3)
                    if (parts.size != 3) return@mapNotNull null
                    val pid = parts[0].toIntOrNull() ?: return@mapNotNull null
                    val rssKb = parts[1].toLongOrNull() ?: return@mapNotNull null
                    Proc(pid, parts[2], rssKb * 1024L)
                }
                .sortedByDescending { it.rssBytes }
                .take(n)
                .toList()
        } else {
            // Non-root fallback: PSS via ActivityManager (no permission needed).
            val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val procs = runCatching { am.runningAppProcesses }.getOrNull().orEmpty()
            val pids = procs.map { it.pid }.toIntArray()
            val mems = runCatching { am.getProcessMemoryInfo(pids) }.getOrNull()
            procs.mapIndexedNotNull { i, p ->
                val pssKb = mems?.getOrNull(i)?.totalPss?.toLong() ?: return@mapIndexedNotNull null
                Proc(p.pid, p.processName ?: "", pssKb * 1024L)
            }.sortedByDescending { it.rssBytes }.take(n)
        }
        return ok("top_memory_apps")
            .put(
                "items",
                JSONArray().also { array ->
                    items.forEach {
                        array.put(
                            JSONObject()
                                .put("pid", it.pid)
                                .put("process", it.name)
                                .put("rss_bytes", it.rssBytes),
                        )
                    }
                },
            )
    }

    fun topStorageApps(limit: Int): JSONObject {
        val n = limit.coerceIn(1, 30)
        if (!ShellExecutor.hasRoot()) {
            return err(
                "STORAGE_STATS_UNAVAILABLE",
                "dumpsys diskstats needs root on this device",
                "top_storage_apps",
            )
        }
        val r = ShellExecutor.exec(
            "dumpsys diskstats",
            asRoot = true,
            timeoutMs = 20_000,
            maxOutputBytes = 2 * 1024 * 1024,
        )
        if (!r.ok) {
            return err("STORAGE_STATS_FAILED", "dumpsys failed: ${r.stderr.take(120)}", "top_storage_apps")
        }
        val packages = arrayLine(r.stdout, "Package Names:") ?: return err(
            "STORAGE_STATS_UNAVAILABLE",
            "system did not return parseable storage stats",
            "top_storage_apps",
        )
        val appSizes = longArrayLine(r.stdout, "App Sizes:")
        val dataSizes = longArrayLine(r.stdout, "App Data Sizes:")
        val cacheSizes = longArrayLine(r.stdout, "Cache Sizes:")
        if (appSizes == null || dataSizes == null || cacheSizes == null) {
            return err(
                "STORAGE_STATS_UNAVAILABLE",
                "system did not return parseable storage stats",
                "top_storage_apps",
            )
        }
        data class Usage(val pkg: String, val total: Long, val app: Long, val data: Long, val cache: Long)
        val items = (0 until packages.length())
            .mapNotNull { i ->
                val pkg = packages.optString(i).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val app = appSizes.getOrElse(i) { 0L }
                val data = dataSizes.getOrElse(i) { 0L }
                val cache = cacheSizes.getOrElse(i) { 0L }
                Usage(pkg, app + data + cache, app, data, cache)
            }
            .sortedByDescending { it.total }
            .take(n)
        return ok("top_storage_apps")
            .put(
                "items",
                JSONArray().also { array ->
                    items.forEach {
                        array.put(
                            JSONObject()
                                .put("package_name", it.pkg)
                                .put("total_bytes", it.total)
                                .put("app_bytes", it.app)
                                .put("data_bytes", it.data)
                                .put("cache_bytes", it.cache),
                        )
                    }
                },
            )
    }

    private fun arrayLine(stdout: String, prefix: String): JSONArray? {
        val line = stdout.lineSequence().firstOrNull { it.trimStart().startsWith(prefix) } ?: return null
        return runCatching { JSONArray(line.substringAfter(prefix).trim()) }.getOrNull()
    }

    private fun longArrayLine(stdout: String, prefix: String): List<Long>? {
        val arr = arrayLine(stdout, prefix) ?: return null
        return (0 until arr.length()).map { arr.optLong(it) }
    }

    companion object {
        // Same tolerant patterns Eta uses against `cmd wifi status`.
        private val WIFI_STATUS_SSID = Regex("""\bSSID:\s*([^,\r\n]+)""")
        private val WIFI_STATUS_RSSI = Regex("""\bRSSI:\s*(-?\d+)""")
    }
}
