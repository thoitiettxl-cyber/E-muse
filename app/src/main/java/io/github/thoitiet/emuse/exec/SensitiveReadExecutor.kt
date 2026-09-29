package io.github.thoitiet.emuse.exec

import android.Manifest
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.display.DisplayManager
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Eta parity: sensitive-read tools (contacts, call log, SMS, calendar,
 * media/audio/files/downloads, location, app usage, notifications,
 * Wi-Fi credentials, logcat, SMS OTP codes, settings, device environment).
 *
 * Permission model (deliberately different from Eta, which queries providers
 * as root and needs no runtime permissions): every provider-backed tool
 * checks `checkSelfPermission` first and fails with a clear
 * PERMISSION_REQUIRED error when the permission is missing. The user grants
 * it in Android Settings; the tool never requests permissions mid-call.
 * Tools that only work as root fail with ROOT_REQUIRED instead.
 *
 * All permissions used here are declared in AndroidManifest.xml.
 */
class SensitiveReadExecutor(private val appCtx: Context) {

    private fun err(code: String, message: String, tool: String): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .put("tool", tool)

    private fun ok(tool: String): JSONObject =
        JSONObject().put("ok", true).put("tool", tool)

    // ------------------------------------------------------------------
    // Permission helpers
    // ------------------------------------------------------------------

    private fun granted(perm: String): Boolean =
        appCtx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    /** Returns an error object when any permission is missing, else null. */
    private fun requirePerms(tool: String, vararg perms: String): JSONObject? {
        val missing = perms.filterNot(::granted)
        if (missing.isEmpty()) return null
        return err(
            "PERMISSION_REQUIRED",
            "Missing runtime permission(s): ${missing.joinToString(", ")}. " +
                "Grant in Android Settings -> Apps -> E-Muse -> Permissions, then retry.",
            tool,
        )
    }

    /**
     * MediaStore read on API 33+ needs the granular READ_MEDIA_* permissions;
     * on API 32 and below it needs READ_EXTERNAL_STORAGE.
     */
    private fun hasMediaRead(vararg kinds: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33) return kinds.any(::granted)
        return granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun requireMediaRead(tool: String, vararg kinds: String): JSONObject? {
        if (hasMediaRead(*kinds)) return null
        val names = if (Build.VERSION.SDK_INT >= 33) kinds.toList()
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        return err(
            "PERMISSION_REQUIRED",
            "Missing runtime permission(s): ${names.joinToString(", ")}. " +
                "Grant in Android Settings -> Apps -> E-Muse -> Permissions, then retry.",
            tool,
        )
    }

    private fun requireUsageAccess(tool: String): JSONObject? {
        val appOps = appCtx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            appCtx.packageName,
        )
        if (mode == AppOpsManager.MODE_ALLOWED) return null
        return err(
            "USAGE_ACCESS_REQUIRED",
            "Grant usage access in Android Settings -> Special app access -> Usage access -> E-Muse, then retry.",
            tool,
        )
    }

    private fun requireRoot(tool: String): JSONObject? =
        if (ShellExecutor.hasRoot()) null
        else err("ROOT_REQUIRED", "This tool needs root (KernelSU/Magisk).", tool)

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    // ------------------------------------------------------------------
    // Generic ContentProvider query
    // ------------------------------------------------------------------

    private fun Cursor.optStr(col: String): String? {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) null else getString(i)
    }

    private fun Cursor.optLong(col: String): Long? {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) null else getLong(i)
    }

    private fun Cursor.optInt(col: String): Int? {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) null else getInt(i)
    }

    /**
     * Builds a case-insensitive LIKE selection over [columns] for [keyword],
     * using selection args (never string-concatenated) so the keyword cannot
     * inject SQL. Returns null when the keyword is blank.
     */
    private fun likeSelection(
        columns: List<String>,
        keyword: String,
    ): Pair<String, Array<String>>? {
        val kw = keyword.trim()
        if (kw.isEmpty()) return null
        val escaped = kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val sel = columns.joinToString(" OR ") { "LOWER($it) LIKE LOWER(?) ESCAPE '\\'" }
        return "($sel)" to Array(columns.size) { "%$escaped%" }
    }

    private fun combineSelection(
        fixed: String?,
        like: Pair<String, Array<String>>?,
    ): Pair<String?, Array<String>?> = when {
        fixed == null -> like?.first to like?.second
        like == null -> fixed to null
        else -> "($fixed) AND ${like.first}" to like.second
    }

    /**
     * Runs a provider query and maps each row. Returns an error object on
     * provider failure; the caller propagates it.
     */
    private fun queryProvider(
        tool: String,
        uri: Uri,
        projection: Array<String>,
        fixedSelection: String?,
        searchableColumns: List<String>,
        keyword: String,
        sortOrder: String,
        limit: Int,
        mapRow: Cursor.() -> JSONObject,
    ): JSONObject {
        val like = likeSelection(searchableColumns, keyword)
        val (selection, selectionArgs) = combineSelection(fixedSelection, like)
        val items = JSONArray()
        // Exact truncation: read one row past the limit; its presence proves
        // more data exists, instead of guessing from `items == limit`.
        var truncated = false
        try {
            appCtx.contentResolver.query(
                uri, projection, selection, selectionArgs, sortOrder,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (items.length() >= limit) {
                        truncated = true
                        break
                    }
                    items.put(cursor.mapRow())
                }
            } ?: return err("PROVIDER_UNAVAILABLE", "Content provider returned no cursor.", tool)
        } catch (e: SecurityException) {
            return err("PERMISSION_REQUIRED", "Provider denied access: ${e.message}", tool)
        } catch (e: Exception) {
            return err("PROVIDER_QUERY_FAILED", e.message ?: e.javaClass.simpleName, tool)
        }
        return ok(tool)
            .put("items", items)
            .put("count", items.length())
            .put("truncated", truncated)
    }

    private fun limitArg(a: org.json.JSONObject, default: Int, max: Int = 30): Int =
        a.optInt("limit", default).coerceIn(1, max)

    private fun queryArg(a: org.json.JSONObject): String =
        a.optString("query", "").trim().take(100)

    // ------------------------------------------------------------------
    // search_contacts (Eta parity)
    // ------------------------------------------------------------------

    fun searchContacts(a: JSONObject): JSONObject {
        val tool = "search_contacts"
        requirePerms(tool, Manifest.permission.READ_CONTACTS)?.let { return it }
        return queryProvider(
            tool = tool,
            uri = ContactsContract.Contacts.CONTENT_URI,
            projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.HAS_PHONE_NUMBER,
                ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
            ),
            fixedSelection = null,
            searchableColumns = listOf(ContactsContract.Contacts.DISPLAY_NAME),
            keyword = queryArg(a),
            sortOrder = "${ContactsContract.Contacts.DISPLAY_NAME} COLLATE LOCALIZED ASC",
            limit = limitArg(a, 10),
        ) {
            JSONObject()
                .put("id", optLong(ContactsContract.Contacts._ID))
                .put("display_name", optStr(ContactsContract.Contacts.DISPLAY_NAME) ?: "")
                .put("lookup", optStr(ContactsContract.Contacts.LOOKUP_KEY) ?: "")
                .put("has_phone_number", (optInt(ContactsContract.Contacts.HAS_PHONE_NUMBER) ?: 0) == 1)
                .put(
                    "updated_at",
                    optLong(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP)
                        ?: JSONObject.NULL,
                )
        }
    }

    // ------------------------------------------------------------------
    // search_call_history (Eta parity)
    // ------------------------------------------------------------------

    fun searchCallHistory(a: JSONObject): JSONObject {
        val tool = "search_call_history"
        requirePerms(tool, Manifest.permission.READ_CALL_LOG)?.let { return it }
        return queryProvider(
            tool = tool,
            uri = CallLog.Calls.CONTENT_URI,
            projection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.TYPE,
                CallLog.Calls.GEOCODED_LOCATION,
            ),
            fixedSelection = null,
            searchableColumns = listOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME),
            keyword = queryArg(a),
            sortOrder = "${CallLog.Calls.DATE} DESC",
            limit = limitArg(a, 10),
        ) {
            JSONObject()
                .put("id", optLong(CallLog.Calls._ID))
                .put("number", optStr(CallLog.Calls.NUMBER) ?: "")
                .put("name", optStr(CallLog.Calls.CACHED_NAME) ?: JSONObject.NULL)
                .put("date", optLong(CallLog.Calls.DATE) ?: JSONObject.NULL)
                .put("duration_s", optLong(CallLog.Calls.DURATION) ?: JSONObject.NULL)
                .put("type", optInt(CallLog.Calls.TYPE)?.let(::callType) ?: JSONObject.NULL)
                .put("location", optStr(CallLog.Calls.GEOCODED_LOCATION) ?: JSONObject.NULL)
        }
    }

    private fun callType(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        else -> "other_$type"
    }

    // ------------------------------------------------------------------
    // search_messages (Eta parity)
    // ------------------------------------------------------------------

    fun searchMessages(a: JSONObject): JSONObject {
        val tool = "search_messages"
        requirePerms(tool, Manifest.permission.READ_SMS)?.let { return it }
        return queryProvider(
            tool = tool,
            uri = Telephony.Sms.CONTENT_URI,
            projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ,
            ),
            fixedSelection = null,
            searchableColumns = listOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
            keyword = queryArg(a),
            sortOrder = "${Telephony.Sms.DATE} DESC",
            limit = limitArg(a, 10),
        ) {
            JSONObject()
                .put("id", optLong(Telephony.Sms._ID))
                .put("thread_id", optLong(Telephony.Sms.THREAD_ID) ?: JSONObject.NULL)
                .put("address", optStr(Telephony.Sms.ADDRESS) ?: "")
                .put("body", optStr(Telephony.Sms.BODY)?.take(1000) ?: "")
                .put("date", optLong(Telephony.Sms.DATE) ?: JSONObject.NULL)
                .put("type", optInt(Telephony.Sms.TYPE)?.let(::smsType) ?: JSONObject.NULL)
                .put("read", (optInt(Telephony.Sms.READ) ?: 0) == 1)
        }
    }

    private fun smsType(type: Int): String = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
        Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
        Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
        Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "outbox"
        Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
        Telephony.Sms.MESSAGE_TYPE_QUEUED -> "queued"
        else -> "other_$type"
    }

    // ------------------------------------------------------------------
    // search_calendar_events (Eta parity)
    // ------------------------------------------------------------------

    fun searchCalendarEvents(a: JSONObject): JSONObject {
        val tool = "search_calendar_events"
        requirePerms(tool, Manifest.permission.READ_CALENDAR)?.let { return it }
        return queryProvider(
            tool = tool,
            uri = CalendarContract.Events.CONTENT_URI,
            projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.CALENDAR_DISPLAY_NAME,
            ),
            fixedSelection = "${CalendarContract.Events.DELETED}=0",
            searchableColumns = listOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION,
            ),
            keyword = queryArg(a),
            sortOrder = "${CalendarContract.Events.DTSTART} DESC",
            limit = limitArg(a, 10),
        ) {
            JSONObject()
                .put("id", optLong(CalendarContract.Events._ID))
                .put("title", optStr(CalendarContract.Events.TITLE) ?: "")
                .put("description", optStr(CalendarContract.Events.DESCRIPTION)?.take(500) ?: JSONObject.NULL)
                .put("location", optStr(CalendarContract.Events.EVENT_LOCATION) ?: JSONObject.NULL)
                .put("start", optLong(CalendarContract.Events.DTSTART) ?: JSONObject.NULL)
                .put("end", optLong(CalendarContract.Events.DTEND) ?: JSONObject.NULL)
                .put("all_day", (optInt(CalendarContract.Events.ALL_DAY) ?: 0) == 1)
                .put("calendar", optStr(CalendarContract.Events.CALENDAR_DISPLAY_NAME) ?: JSONObject.NULL)
        }
    }

    // ------------------------------------------------------------------
    // search_media / search_audio / search_recordings / search_files /
    // search_downloads (Eta parity)
    // ------------------------------------------------------------------

    private val mediaFileProjection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.SIZE,
    )

    private fun Cursor.mediaRow(): JSONObject {
        val id = optLong(MediaStore.MediaColumns._ID) ?: 0L
        return JSONObject()
            .put("id", id)
            .put("display_name", optStr(MediaStore.MediaColumns.DISPLAY_NAME) ?: "")
            .put("mime_type", optStr(MediaStore.MediaColumns.MIME_TYPE) ?: JSONObject.NULL)
            .put("relative_path", optStr(MediaStore.MediaColumns.RELATIVE_PATH) ?: JSONObject.NULL)
            .put("date_modified", optLong(MediaStore.MediaColumns.DATE_MODIFIED) ?: JSONObject.NULL)
            .put("size", optLong(MediaStore.MediaColumns.SIZE) ?: JSONObject.NULL)
            .put("uri", "content://media/external/file/$id")
    }

    fun searchMedia(a: JSONObject): JSONObject {
        val tool = "search_media"
        requireMediaRead(
            tool,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )?.let { return it }
        return queryProvider(
            tool = tool,
            uri = MediaStore.Files.getContentUri("external"),
            projection = mediaFileProjection,
            fixedSelection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} " +
                "OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}",
            searchableColumns = listOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
            ),
            keyword = queryArg(a),
            sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            limit = limitArg(a, 10),
        ) { mediaRow() }
    }

    fun searchAudio(a: JSONObject, recordingsOnly: Boolean, tool: String): JSONObject {
        requireMediaRead(tool, Manifest.permission.READ_MEDIA_AUDIO)?.let { return it }
        val fixed = if (recordingsOnly) "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE '%Record%'" else null
        return queryProvider(
            tool = tool,
            uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.Audio.AudioColumns.TITLE,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.Audio.AudioColumns.ARTIST,
                MediaStore.Audio.AudioColumns.ALBUM,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.Audio.AudioColumns.DURATION,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE,
            ),
            fixedSelection = fixed,
            searchableColumns = listOf(
                MediaStore.Audio.AudioColumns.TITLE,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.Audio.AudioColumns.ARTIST,
                MediaStore.MediaColumns.RELATIVE_PATH,
            ),
            keyword = queryArg(a),
            sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            limit = limitArg(a, 10),
        ) {
            val id = optLong(MediaStore.MediaColumns._ID) ?: 0L
            JSONObject()
                .put("id", id)
                .put("title", optStr(MediaStore.Audio.AudioColumns.TITLE) ?: "")
                .put("display_name", optStr(MediaStore.MediaColumns.DISPLAY_NAME) ?: "")
                .put("artist", optStr(MediaStore.Audio.AudioColumns.ARTIST) ?: JSONObject.NULL)
                .put("album", optStr(MediaStore.Audio.AudioColumns.ALBUM) ?: JSONObject.NULL)
                .put("relative_path", optStr(MediaStore.MediaColumns.RELATIVE_PATH) ?: JSONObject.NULL)
                .put("duration_ms", optLong(MediaStore.Audio.AudioColumns.DURATION) ?: JSONObject.NULL)
                .put("date_modified", optLong(MediaStore.MediaColumns.DATE_MODIFIED) ?: JSONObject.NULL)
                .put("size", optLong(MediaStore.MediaColumns.SIZE) ?: JSONObject.NULL)
                .put("uri", "content://media/external/audio/media/$id")
        }
    }

    fun searchFiles(a: JSONObject): JSONObject {
        val tool = "search_files"
        requireMediaRead(
            tool,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )?.let { return it }
        return queryProvider(
            tool = tool,
            uri = MediaStore.Files.getContentUri("external"),
            projection = mediaFileProjection,
            fixedSelection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_NONE}",
            searchableColumns = listOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
            ),
            keyword = queryArg(a),
            sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            limit = limitArg(a, 10),
        ) { mediaRow() }
    }

    fun searchDownloads(a: JSONObject): JSONObject {
        val tool = "search_downloads"
        if (Build.VERSION.SDK_INT < 29) {
            return err("UNSUPPORTED", "MediaStore.Downloads needs API 29+.", tool)
        }
        requireMediaRead(
            tool,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )?.let { return it }
        return queryProvider(
            tool = tool,
            uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection = mediaFileProjection,
            fixedSelection = null,
            searchableColumns = listOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
            ),
            keyword = queryArg(a),
            sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            limit = limitArg(a, 10),
        ) { mediaRow() }
    }

    // ------------------------------------------------------------------
    // get_current_location (Eta parity)
    // ------------------------------------------------------------------

    fun getCurrentLocation(@Suppress("UNUSED_PARAMETER") a: JSONObject): JSONObject {
        val tool = "get_current_location"
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) {
            return err(
                "PERMISSION_REQUIRED",
                "Missing runtime permission(s): ${Manifest.permission.ACCESS_FINE_LOCATION} " +
                    "(or ${Manifest.permission.ACCESS_COARSE_LOCATION}). " +
                    "Grant in Android Settings -> Apps -> E-Muse -> Permissions, then retry.",
                tool,
            )
        }
        val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: android.location.Location? = null
        for (provider in runCatching { lm.getProviders(true) }.getOrNull().orEmpty()) {
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || loc.time > best.time) best = loc
        }
        val b = best ?: return err("LOCATION_UNAVAILABLE", "No last-known location available.", tool)
        fun round5(v: Double): Double = kotlin.math.round(v * 100_000.0) / 100_000.0
        return ok(tool)
            .put("latitude", round5(b.latitude))
            .put("longitude", round5(b.longitude))
            .put("accuracy_m", if (b.hasAccuracy()) b.accuracy.toInt() else JSONObject.NULL)
            .put("age_s", (System.currentTimeMillis() - b.time) / 1_000L)
    }

    // ------------------------------------------------------------------
    // recent_app_activity / app_usage_summary (Eta parity)
    // ------------------------------------------------------------------

    private fun appLabel(packageName: String): String = runCatching {
        val pm = appCtx.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }
        pm.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    fun recentAppActivity(a: JSONObject): JSONObject {
        val tool = "recent_app_activity"
        requireUsageAccess(tool)?.let { return it }
        val maxAgeHours = a.optInt("max_age_hours", 24).coerceIn(1, 168)
        val limit = limitArg(a, 20, 50)
        val packageFilter = a.optString("package_name", "").trim().take(256)
        val end = System.currentTimeMillis()
        val events = appCtx.getSystemService(UsageStatsManager::class.java)
            ?.queryEvents(end - maxAgeHours * 3_600_000L, end)
            ?: return err("APP_USAGE_UNAVAILABLE", "UsageStatsManager returned no events.", tool)
        val resumedType = if (Build.VERSION.SDK_INT >= 29) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }
        val rows = ArrayDeque<JSONObject>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != resumedType) continue
            if (packageFilter.isNotBlank() && event.packageName != packageFilter) continue
            rows.addFirst(
                JSONObject()
                    .put("package_name", event.packageName)
                    .put("app_name", appLabel(event.packageName))
                    .put("activity", event.className ?: JSONObject.NULL)
                    .put("resumed_at", event.timeStamp),
            )
            while (rows.size > limit) rows.removeLast()
        }
        return ok(tool)
            .put("items", JSONArray(rows))
            .put("count", rows.size)
            .put("window_hours", maxAgeHours)
    }

    fun appUsageSummary(a: JSONObject): JSONObject {
        val tool = "app_usage_summary"
        requireUsageAccess(tool)?.let { return it }
        val maxAgeHours = a.optInt("max_age_hours", 24).coerceIn(1, 168)
        val limit = limitArg(a, 20, 50)
        val end = System.currentTimeMillis()
        val stats = appCtx.getSystemService(UsageStatsManager::class.java)
            ?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - maxAgeHours * 3_600_000L, end)
            .orEmpty()
            .filter { it.totalTimeInForeground > 0L }
            .groupBy { it.packageName }
            .map { (packageName, entries) ->
                JSONObject()
                    .put("package_name", packageName)
                    .put("app_name", appLabel(packageName))
                    .put("foreground_ms", entries.sumOf { it.totalTimeInForeground })
                    .put("last_used_at", entries.maxOf { it.lastTimeUsed })
            }
            .sortedByDescending { it.optLong("foreground_ms") }
            .take(limit)
        return ok(tool)
            .put("items", JSONArray(stats))
            .put("count", stats.size)
            .put("window_hours", maxAgeHours)
    }

    // ------------------------------------------------------------------
    // recent_notifications (Eta parity; root, no manifest permission)
    // ------------------------------------------------------------------

    fun recentNotifications(a: JSONObject): JSONObject {
        val tool = "recent_notifications"
        requireRoot(tool)?.let { return it }
        val limit = limitArg(a, 10, 20)
        val packageFilter = a.optString("package_name", "").trim().take(256)
        val listed = ShellExecutor.exec(
            "cmd notification list",
            asRoot = true,
            maxOutputBytes = 256 * 1024,
        )
        if (!listed.ok) {
            return err("NOTIFICATION_LIST_FAILED", listed.stderr.take(200), tool)
        }
        val items = JSONArray()
        listed.stdout.lineSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && (packageFilter.isBlank() || "|$packageFilter|" in it) }
            .take(limit)
            .forEach { key ->
                val detail = ShellExecutor.exec(
                    "cmd notification get ${shellQuote(key)}",
                    asRoot = true,
                    maxOutputBytes = 128 * 1024,
                )
                if (!detail.ok) return@forEach
                val text = detail.stdout
                items.put(
                    JSONObject()
                        .put(
                            "package_name",
                            NOTIFICATION_PACKAGE.find(text)?.groupValues?.get(1).orEmpty(),
                        )
                        .put("title", notificationExtra(text, "android.title") ?: JSONObject.NULL)
                        .put("text", notificationExtra(text, "android.text") ?: JSONObject.NULL)
                        .put("sub_text", notificationExtra(text, "android.subText") ?: JSONObject.NULL),
                )
            }
        return ok(tool).put("items", items).put("count", items.length())
    }

    private fun notificationExtra(source: String, key: String): String? =
        Regex("""(?m)^\s*${Regex.escape(key)}=[^(]+\((.*)\)\s*$""")
            .find(source)?.groupValues?.get(1)?.takeUnless { it == "null" }

    // ------------------------------------------------------------------
    // wifi_credentials (Eta parity; root, no manifest permission)
    // ------------------------------------------------------------------

    fun wifiCredentials(a: JSONObject): JSONObject {
        val tool = "wifi_credentials"
        requireRoot(tool)?.let { return it }
        val requestedSsid = a.optString("ssid", "").trim().trim('"').take(128)
        val limit = limitArg(a, 20, 50)
        var xml: String? = null
        for (path in WIFI_CONFIG_PATHS) {
            val r = ShellExecutor.exec(
                "cat ${shellQuote(path)}",
                asRoot = true,
                maxOutputBytes = 2 * 1024 * 1024,
            )
            if (r.ok && "<Network>" in r.stdout) {
                xml = r.stdout
                break
            }
        }
        val src = xml ?: return err("WIFI_CONFIG_UNAVAILABLE", "Could not read WifiConfigStore.xml.", tool)
        val networks = NETWORK_BLOCK.findAll(src).mapNotNull { match ->
            val block = match.value
            val ssid = XML_SSID.find(block)?.groupValues?.get(1)?.decodeXml()?.trim('"')
                ?: return@mapNotNull null
            val password = XML_PSK.find(block)
                ?.groupValues?.get(1)
                ?.decodeXml()
                ?.trim('"')
                ?.takeUnless { it == "null" }
            JSONObject()
                .put("ssid", ssid)
                .put("password", password ?: JSONObject.NULL)
        }.filter {
            requestedSsid.isBlank() || it.optString("ssid").equals(requestedSsid, ignoreCase = true)
        }.distinctBy {
            it.optString("ssid").lowercase(Locale.ROOT)
        }.take(limit).toList()
        return ok(tool)
            .put("items", JSONArray(networks))
            .put("count", networks.size)
    }

    private fun String.decodeXml(): String =
        replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")

    // ------------------------------------------------------------------
    // read_sms_code (Eta parity)
    // ------------------------------------------------------------------

    fun readSmsCode(a: JSONObject): JSONObject {
        val tool = "read_sms_code"
        requirePerms(tool, Manifest.permission.READ_SMS)?.let { return it }
        val maxAgeMinutes = a.optInt("max_age_minutes", 10).coerceIn(1, 1_440)
        val cutoff = System.currentTimeMillis() - maxAgeMinutes * 60_000L
        val items = JSONArray()
        try {
            appCtx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                null, null,
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext() && items.length() < 10) {
                    val date = cursor.optLong(Telephony.Sms.DATE) ?: continue
                    if (date < cutoff) break
                    val body = cursor.optStr(Telephony.Sms.BODY).orEmpty()
                    val contextMatch = OTP_CONTEXT.find(body) ?: continue
                    val code = OTP.findAll(body)
                        .minByOrNull { match ->
                            kotlin.math.abs(match.range.first - contextMatch.range.first)
                        }
                        ?.groupValues?.get(1)
                        ?: continue
                    items.put(
                        JSONObject()
                            .put("code", code)
                            .put("sender", cursor.optStr(Telephony.Sms.ADDRESS).orEmpty())
                            .put("timestamp_ms", date),
                    )
                }
            } ?: return err("PROVIDER_UNAVAILABLE", "SMS provider returned no cursor.", tool)
        } catch (e: SecurityException) {
            return err("PERMISSION_REQUIRED", "SMS provider denied access: ${e.message}", tool)
        } catch (e: Exception) {
            return err("PROVIDER_QUERY_FAILED", e.message ?: e.javaClass.simpleName, tool)
        }
        return ok(tool).put("items", items).put("count", items.length())
    }

    // ------------------------------------------------------------------
    // get_logcat (Eta parity; root, no manifest permission)
    // ------------------------------------------------------------------

    fun getLogcat(a: JSONObject): JSONObject {
        val tool = "get_logcat"
        requireRoot(tool)?.let { return it }
        val maxLines = a.optInt("max_lines", 200).coerceIn(20, 500)
        val query = a.optString("query", "").trim().take(100)
        val result = ShellExecutor.exec(
            "logcat -d -v threadtime -t $maxLines",
            asRoot = true,
            maxOutputBytes = 512 * 1024,
        )
        if (!result.ok) {
            return err("LOGCAT_FAILED", result.stderr.take(200), tool)
        }
        val lines = result.stdout.lineSequence()
            .filter { query.isBlank() || it.contains(query, ignoreCase = true) }
            .take(maxLines)
            .toList()
        return ok(tool)
            .put("lines", JSONArray(lines))
            .put("count", lines.size)
            .put("truncated", result.truncated)
    }

    // ------------------------------------------------------------------
    // get_setting (Eta parity; no permission needed)
    // ------------------------------------------------------------------

    fun getSetting(a: JSONObject): JSONObject {
        val tool = "get_setting"
        val namespace = a.optString("namespace", "").lowercase(Locale.ROOT)
        val key = a.optString("key", "").take(128)
        if (namespace !in setOf("system", "secure", "global")) {
            return err("INVALID_NAMESPACE", "namespace must be system, secure, or global.", tool)
        }
        if (key.isBlank()) return err("INVALID_KEY", "key must not be blank.", tool)
        val publicValue = runCatching {
            when (namespace) {
                "system" -> Settings.System.getString(appCtx.contentResolver, key)
                "secure" -> Settings.Secure.getString(appCtx.contentResolver, key)
                else -> Settings.Global.getString(appCtx.contentResolver, key)
            }
        }.getOrNull()
        val value = publicValue ?: if (ShellExecutor.hasRoot()) {
            val r = ShellExecutor.exec(
                "settings --user current get ${shellQuote(namespace)} ${shellQuote(key)}",
                asRoot = true,
            )
            r.takeIf { it.ok }?.stdout?.trim()?.takeUnless { it == "null" }
        } else null
        return ok(tool)
            .put("namespace", namespace)
            .put("key", key)
            .put("value", value ?: JSONObject.NULL)
    }

    // ------------------------------------------------------------------
    // get_device_environment (Eta parity; no permission needed)
    // ------------------------------------------------------------------

    fun getDeviceEnvironment(@Suppress("UNUSED_PARAMETER") a: JSONObject): JSONObject {
        val tool = "get_device_environment"
        val audio = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val displays = (appCtx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).displays
        val power = appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = appCtx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val notification = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val routes = JSONArray()
        for (device in audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            routes.put(
                JSONObject()
                    .put("type", audioDeviceType(device.type))
                    .put("product_name", device.productName?.toString() ?: JSONObject.NULL)
                    .put("is_sink", device.isSink),
            )
        }
        return ok(tool)
            .put("interactive", power.isInteractive)
            .put("device_locked", keyguard.isDeviceLocked)
            .put("ringer_mode", ringerMode(audio.ringerMode))
            .put("dnd_filter", interruptionFilter(notification.currentInterruptionFilter))
            .put("audio_outputs", routes)
            .put("display_count", displays.size)
            .put(
                "external_display_count",
                displays.count { it.displayId != android.view.Display.DEFAULT_DISPLAY },
            )
    }

    private fun audioDeviceType(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_audio"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_audio"
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> "hdmi"
        AudioDeviceInfo.TYPE_HEARING_AID -> "hearing_aid"
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "bluetooth_le_audio"
        else -> "other_$type"
    }

    private fun ringerMode(mode: Int): String = when (mode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        AudioManager.RINGER_MODE_NORMAL -> "normal"
        else -> "unknown"
    }

    private fun interruptionFilter(filter: Int): String = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "all"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
        else -> "unknown"
    }

    companion object {
        private val NETWORK_BLOCK =
            Regex("<Network>.*?</Network>", setOf(RegexOption.DOT_MATCHES_ALL))
        private val XML_SSID = Regex("<string name=\"SSID\">(.*?)</string>")
        private val XML_PSK = Regex("<string name=\"PreSharedKey\">(.*?)</string>")
        private val NOTIFICATION_PACKAGE = Regex("""NotificationRecord\([^:]+:\s+pkg=([^\s]+)""")
        private val OTP = Regex("""(?<!\d)(\d{4,8})(?!\d)""")
        private val OTP_CONTEXT = Regex(
            """验证码|校验码|动态码|确认码|一次性密码|verification\s*code|one[- ]time\s*(?:code|password)|\botp\b""",
            RegexOption.IGNORE_CASE,
        )
        private val WIFI_CONFIG_PATHS = listOf(
            "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
            "/data/misc/wifi/WifiConfigStore.xml",
        )
    }
}
