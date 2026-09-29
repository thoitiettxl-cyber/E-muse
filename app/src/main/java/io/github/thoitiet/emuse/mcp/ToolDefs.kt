package io.github.thoitiet.emuse.mcp

/**
 * MCP tool definitions served by the on-device direct endpoint
 * (Cloudflare Tunnel -> 127.0.0.1:18789). The Cloudflare Worker relay was
 * removed; this file is the single source of truth for tool names, schemas
 * and annotations.
 */

data class ToolDef(
    val name: String,
    val description: String,
    /** Compact JSON of the inputSchema (parsed once, embedded verbatim). */
    val inputSchemaJson: String,
    val readOnlyHint: Boolean,
    val destructiveHint: Boolean,
    val idempotentHint: Boolean,
    val openWorldHint: Boolean,
    val kind: String, // "query" | "write"
    /** Device Cmd, null for tools served locally (device_list). */
    val cmd: String?,
)

private const val DEVICE_ID_PROP =
    "\"deviceId\":{\"type\":\"string\"," +
        "\"description\":\"Target device id. Optional when exactly one device is connected; required when several are.\"}"

private fun withDevice(props: String, required: String = ""): String =
    "{\"type\":\"object\",\"properties\":{$DEVICE_ID_PROP" +
        (if (props.isEmpty()) "" else ",$props") +
        "},\"required\":[$required],\"additionalProperties\":false}"

val TOOL_DEFS: List<ToolDef> = listOf(
    ToolDef(
        "device_list",
        "List Android devices reachable through this E-muse endpoint (direct mode: always this device).",
        "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
        true, false, true, false, "query", null,
    ),
    ToolDef(
        "device_info",
        "Get device details: model, Android version, SDK level, root availability, accessibility/screen-capture readiness.",
        withDevice(""),
        true, false, true, false, "query", "device.info",
    ),
    ToolDef(
        "get_current_context",
        "Get the phone's current time, time zone, weekday, locale, and last known location. Call this when the task involves now, today, tomorrow, or the device's location. Location is included only when the app already holds a location permission.",
        withDevice(""),
        true, false, true, false, "query", "device.context",
    ),
    ToolDef(
        "device_status",
        "Read battery level/charging, memory (available/total), storage (available/total), system version, security patch, and uptime.",
        withDevice(""),
        true, false, true, false, "query", "device.status",
    ),
    ToolDef(
        "network_info",
        "Read the current network: connected/validated/metered, transports (wifi/cellular/ethernet/vpn), Wi-Fi state, current SSID and RSSI when visible. Does not return saved passwords.",
        withDevice(""),
        true, false, true, false, "query", "network.info",
    ),
    ToolDef(
        "get_volume",
        "Read the current volume level of every stream (media, alarm, ring, notification): level, max_level, percent.",
        withDevice(""),
        true, false, true, false, "query", "volume.get",
    ),
    ToolDef(
        "set_volume",
        "Set a volume stream directly; do not operate the volume GUI. percent 0 mutes the stream.",
        withDevice(
            "\"stream\":{\"type\":\"string\",\"enum\":[\"media\",\"alarm\",\"ring\",\"notification\"],\"description\":\"Volume stream.\"}," +
                "\"percent\":{\"type\":\"integer\",\"description\":\"0 to 100.\"}",
            "\"stream\",\"percent\"",
        ),
        false, false, true, false, "write", "volume.set",
    ),
    ToolDef(
        "media_control",
        "Send a media key to the active media session: play, pause, play_pause, next, previous, or stop.",
        withDevice(
            "\"action\":{\"type\":\"string\",\"enum\":[\"play\",\"pause\",\"play_pause\",\"next\",\"previous\",\"stop\"]}",
            "\"action\"",
        ),
        false, false, false, false, "write", "media.control",
    ),
    ToolDef(
        "set_alarm",
        "Create a system alarm directly; do not use the GUI. For relative times, convert with get_current_context first. hour/minute use the device local time. If the system does not accept a direct action, the clock UI is opened instead (mode ui_fallback).",
        withDevice(
            "\"hour\":{\"type\":\"integer\",\"description\":\"0 to 23.\"}," +
                "\"minute\":{\"type\":\"integer\",\"description\":\"0 to 59.\"}," +
                "\"label\":{\"type\":\"string\",\"description\":\"Alarm label, up to 100 characters.\"}," +
                "\"vibrate\":{\"type\":\"boolean\",\"description\":\"Whether to vibrate. Default true.\"}," +
                "\"repeat_days\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"mon\",\"tue\",\"wed\",\"thu\",\"fri\",\"sat\",\"sun\"]},\"description\":\"Repeat weekdays; omit for the next occurrence only.\"}",
            "\"hour\",\"minute\"",
        ),
        false, false, false, false, "write", "alarm.set",
    ),
    ToolDef(
        "set_timer",
        "Create a system timer directly; do not use the GUI. If the system does not accept a direct action, the clock UI is opened instead (mode ui_fallback).",
        withDevice(
            "\"duration_seconds\":{\"type\":\"integer\",\"description\":\"Timer duration in seconds, 1 to 86400.\"}," +
                "\"label\":{\"type\":\"string\",\"description\":\"Timer label, up to 100 characters.\"}",
            "\"duration_seconds\"",
        ),
        false, false, false, false, "write", "timer.set",
    ),
    ToolDef(
        "list_alarms",
        "List alarms (id, hour, minutes, days, enabled, message) from the ColorOS clock app database; falls back to the next system alarm when root/the OEM database is unavailable.",
        withDevice(
            "\"enabled_only\":{\"type\":\"boolean\",\"description\":\"Only enabled alarms. Default true.\"}," +
                "\"limit\":{\"type\":\"integer\",\"description\":\"Max results, 1 to 50. Default 20.\"}",
        ),
        true, false, true, false, "query", "alarm.list",
    ),
    ToolDef(
        "list_active_timers",
        "List active timers (description, duration, state, remaining/alert time) from the ColorOS clock app database. Needs root; ColorOS only.",
        withDevice(
            "\"limit\":{\"type\":\"integer\",\"description\":\"Max results, 1 to 50. Default 20.\"}",
        ),
        true, false, true, false, "query", "timer.active_list",
    ),
    ToolDef(
        "search_coloros_notes",
        "Search ColorOS notes and to-dos by title or body. Needs root; ColorOS Notes only.",
        withDevice(
            "\"query\":{\"type\":\"string\",\"description\":\"Title or body fragment. Omit to list all.\"}," +
                "\"limit\":{\"type\":\"integer\",\"description\":\"Max results, 1 to 30. Default 10.\"}",
        ),
        true, false, true, false, "query", "coloros.notes_search",
    ),
    ToolDef(
        "search_coloros_recordings",
        "Search ordinary and call recordings in the ColorOS Recorder app (name, duration, type, file path). Needs root; ColorOS only.",
        withDevice(
            "\"query\":{\"type\":\"string\",\"description\":\"Name or path fragment. Omit to list all.\"}," +
                "\"limit\":{\"type\":\"integer\",\"description\":\"Max results, 1 to 30. Default 10.\"}",
        ),
        true, false, true, false, "query", "coloros.recordings_search",
    ),
    ToolDef(
        "search_recording_summaries",
        "Search transcription summaries and note content linked to ColorOS recordings. Needs root; ColorOS only.",
        withDevice(
            "\"query\":{\"type\":\"string\",\"description\":\"Summary content fragment. Omit to list all.\"}," +
                "\"limit\":{\"type\":\"integer\",\"description\":\"Max results, 1 to 30. Default 10.\"}",
        ),
        true, false, true, false, "query", "coloros.recording_summaries_search",
    ),
    ToolDef(
        "search_coloros_memories",
        "Search ColorOS system memories (collected info, bills, schedules, pickup codes, parcels, places, attachments). Needs root; ColorOS only.",
        withDevice(
            "\"query\":{\"type\":\"string\",\"description\":\"Memory text fragment. Omit to list recent.\"}," +
                "\"limit\":{\"type\":\"integer\",\"description\":\"Max results, 1 to 30. Default 10.\"}",
        ),
        true, false, true, false, "query", "coloros.memories_search",
    ),
    ToolDef(
        "search_personal_orders",
        "Search food-delivery, shopping, parcel, ticket and travel orders recognized in ColorOS system memories. Needs root; ColorOS only.",
        withDevice(
            "\"query\":{\"type\":\"string\",\"description\":\"Order text fragment. Omit to list recent.\"}," +
                "\"limit\":{\"type\":\"integer\",\"description\":\"Max results, 1 to 30. Default 10.\"}",
        ),
        true, false, true, false, "query", "coloros.personal_orders_search",
    ),
    ToolDef(
        "search_saved_places",
        "Search places saved or recognized in ColorOS system memories. Needs root; ColorOS only.",
        withDevice(
            "\"query\":{\"type\":\"string\",\"description\":\"Place name or address fragment. Omit to list all.\"}," +
                "\"limit\":{\"type\":\"integer\",\"description\":\"Max results, 1 to 30. Default 10.\"}",
        ),
        true, false, true, false, "query", "coloros.saved_places_search",
    ),
    ToolDef(
        "top_memory_apps",
        "List processes with the highest current memory usage (pid, process name, rss_bytes). Uses root ps when available, otherwise ActivityManager PSS.",
        withDevice(
            "\"limit\":{\"type\":\"integer\",\"description\":\"Max results, 1 to 30. Default 10.\"}",
        ),
        true, false, true, false, "query", "memory.top_apps",
    ),
    ToolDef(
        "top_storage_apps",
        "List apps with the highest combined app+data+cache storage (package_name, total/app/data/cache bytes). Needs root (dumpsys diskstats).",
        withDevice(
            "\"limit\":{\"type\":\"integer\",\"description\":\"Max results, 1 to 30. Default 10.\"}",
        ),
        true, false, true, false, "query", "storage.top_apps",
    ),

    // P5: Eta-parity sensitive-read tools. All require the user to have
    // granted the relevant permission in Android Settings; the tool fails
    // with a clear PERMISSION_REQUIRED error otherwise.
    ToolDef(
        "search_contacts",
        "Search contacts by name (display name, lookup key, phone flag, last update). Needs READ_CONTACTS.",
        withDevice(
            "\"query\":{ \"type\":\"string\",\"description\":\"Name fragment. Omit to list all.\"}," +
                "\"limit\":{ \"type\":\"integer\",\"description\":\"Max results, 1 to 30. Default 10.\"}",
        ),
        true, false, true, false, "query", "contacts.search",
    ),
    ToolDef(
        "search_call_history",
        "Search call history by number or name (date, duration, type, geocoded location). Needs READ_CALL_LOG.",
        withDevice(
            "\"query\":{ \"type\":\"string\",\"description\":\"Number or name fragment. Omit to list all.\"}," +
                "\"limit\":{ \"type\":\"integer\",\"description\":\"Max results, 1 to 30. Default 10.\"}",
        ),
        true, false, true, false, "query", "calllog.search",
    ),
    ToolDef(
        "search_messages",
        "Search SMS messages by address or body (date, type, read flag). Needs READ_SMS.",
        withDevice(
            "\"query\":{ \"type\":\"string\",\"description\":\"Address or body fragment. Omit to list all.\"}," +
                "\"limit\":{ \"type\":\"integer\",\"description\":\"Max results, 1 to 30. Default 10.\"}",
        ),
        true, false, true, false, "query", "sms.search",
    ),
    ToolDef(
        "search_calendar_events",
        "Search calendar events by title, description or location (start/end, all-day, calendar). Needs READ_CALENDAR.",
        withDevice(
            "\"query\":{ \"type\":\"string\",\"description\":\"Title, description or location fragment. Omit to list all.\"}," +
                "\"limit\":{ \"type\":\"integer\",\"description\":\"Max results, 1 to 30. Default 10.\"}",
        ),
        true, false, true, false, "query", "calendar.search",
    ),
    ToolDef(
        "search_media",
        "Search photos and videos in MediaStore by file name or path (mime type, date, size). Needs at least one of READ_MEDIA_IMAGES/READ_MEDIA_VIDEO (API 33+) or READ_EXTERNAL_STORAGE (API 32 and below); the granted kinds decide which media types are visible.",
        withDevice(
            "\"query\":{ \"type\":\"string\",\"description\":\"File name or path fragment. Omit to list all.\"}," +
                "\"limit\":{ \"type\":\"integer\",\"description\":\"Max results, 1 to 30. Default 10.\"}",
        ),
        true, false, true, false, "query", "media.search",
    ),
    ToolDef(
        "search_audio",
        "Search audio files in MediaStore by title, artist or path (album, duration, size). Needs READ_MEDIA_AUDIO (API 33+) or READ_EXTERNAL_STORAGE (API 32 and below).",
        withDevice(
            "\"query\":{ \"type\":\"string\",\"description\":\"Title, artist or path fragment. Omit to list all.\"}," +
                "\"limit\":{ \"type\":\"integer\",\"description\":\"Max results, 1 to 30. Default 10.\"}",
        ),
        true, false, true, false, "query", "audio.search",
    ),
    ToolDef(
        "search_recordings",
        "Search call/voice recordings in MediaStore by title or path (album, duration, size). Same as search_audio plus a 'Record*' path filter. Needs READ_MEDIA_AUDIO (API 33+) or READ_EXTERNAL_STORAGE (API 32 and below).",
        withDevice(
            "\"query\":{ \"type\":\"string\",\"description\":\"Title or path fragment. Omit to list all.\"}," +
                "\"limit\":{ \"type\":\"integer\",\"description\":\"Max results, 1 to 30. Default 10.\"}",
        ),
        true, false, true, false, "query", "recordings.search",
    ),
    ToolDef(
        "search_files",
        "Search generic documents/other files in MediaStore by file name or path (mime type, size). Needs a READ_MEDIA_* permission (API 33+) or READ_EXTERNAL_STORAGE (API 32 and below).",
        withDevice(
            "\"query\":{ \"type\":\"string\",\"description\":\"File name or path fragment. Omit to list all.\"}," +
                "\"limit\":{ \"type\":\"integer\",\"description\":\"Max results, 1 to 30. Default 10.\"}",
        ),
        true, false, true, false, "query", "files.search",
    ),
    ToolDef(
        "search_downloads",
        "Search files in the Downloads collection by file name or path (mime type, size). API 29+. Needs a READ_MEDIA_* permission (API 33+) or READ_EXTERNAL_STORAGE (API 32 and below).",
        withDevice(
            "\"query\":{ \"type\":\"string\",\"description\":\"File name or path fragment. Omit to list all.\"}," +
                "\"limit\":{ \"type\":\"integer\",\"description\":\"Max results, 1 to 30. Default 10.\"}",
        ),
        true, false, true, false, "query", "downloads.search",
    ),
    ToolDef(
        "get_current_location",
        "Get the current/last-known device location (latitude, longitude rounded to 5 decimals, accuracy_m, age_s). Needs ACCESS_FINE_LOCATION (or ACCESS_COARSE_LOCATION).",
        withDevice(""),
        true, false, true, false, "query", "location.get",
    ),
    ToolDef(
        "recent_app_activity",
        "List recently foregrounded apps (package, app name, activity, resumed_at), newest first. Needs Usage access (Settings -> Special app access -> Usage access).",
        withDevice(
            "\"package_name\":{ \"type\":\"string\",\"description\":\"Filter to one package. Omit for all.\"}," +
                "\"max_age_hours\":{ \"type\":\"integer\",\"description\":\"Look-back window, 1 to 168 hours. Default 24.\"}," +
                "\"limit\":{ \"type\":\"integer\",\"description\":\"Max results, 1 to 50. Default 20.\"}",
        ),
        true, false, true, false, "query", "app.activity.recent",
    ),
    ToolDef(
        "app_usage_summary",
        "Summarize foreground time per app (foreground_ms, last_used_at), most-used first. Needs Usage access (Settings -> Special app access -> Usage access).",
        withDevice(
            "\"max_age_hours\":{ \"type\":\"integer\",\"description\":\"Look-back window, 1 to 168 hours. Default 24.\"}," +
                "\"limit\":{ \"type\":\"integer\",\"description\":\"Max results, 1 to 50. Default 20.\"}",
        ),
        true, false, true, false, "query", "app.usage.summary",
    ),
    ToolDef(
        "recent_notifications",
        "List active notifications (package, title, text) via root 'cmd notification'. Needs root; no manifest permission required.",
        withDevice(
            "\"package_name\":{ \"type\":\"string\",\"description\":\"Filter to one package. Omit for all.\"}," +
                "\"limit\":{ \"type\":\"integer\",\"description\":\"Max results, 1 to 20. Default 10.\"}",
        ),
        true, false, true, false, "query", "notifications.recent",
    ),
    ToolDef(
        "wifi_credentials",
        "List saved Wi-Fi networks (ssid, password) from WifiConfigStore.xml via root. Needs root; no manifest permission required. Omit ssid to list all.",
        withDevice(
            "\"ssid\":{ \"type\":\"string\",\"description\":\"Filter to one SSID. Omit for all.\"}," +
                "\"limit\":{ \"type\":\"integer\",\"description\":\"Max results, 1 to 50. Default 20.\"}",
        ),
        true, false, true, false, "query", "wifi.credentials",
    ),
    ToolDef(
        "read_sms_code",
        "Extract recent one-time verification codes from incoming SMS (code, sender, timestamp_ms), matching against OTP/verification-code keywords. Needs READ_SMS.",
        withDevice(
            "\"max_age_minutes\":{ \"type\":\"integer\",\"description\":\"Look-back window, 1 to 1440 minutes. Default 10.\"}",
        ),
        true, false, true, false, "query", "sms.code.read",
    ),
    ToolDef(
        "get_logcat",
        "Read device logs (logcat -d -v threadtime) via root, chronological order (oldest first). Needs root; no manifest permission required.",
        withDevice(
            "\"max_lines\":{ \"type\":\"integer\",\"description\":\"Max log lines, 20 to 500. Default 200.\"}," +
                "\"query\":{ \"type\":\"string\",\"description\":\"Case-insensitive filter. Omit for all.\"}",
        ),
        true, false, true, false, "query", "logcat.get",
    ),
    ToolDef(
        "get_setting",
        "Read one Android system setting (system|secure|global namespace), e.g. screen_brightness, android_id, adb_enabled. Falls back to root 'settings get' when the public API returns null.",
        withDevice(
            "\"namespace\":{ \"type\":\"string\",\"description\":\"system, secure or global.\"}," +
                "\"key\":{ \"type\":\"string\",\"description\":\"Setting key, e.g. screen_brightness.\"}",
        ),
        true, false, true, false, "query", "setting.get",
    ),
    ToolDef(
        "get_device_environment",
        "Get device environment: screen interactive/locked, ringer mode, Do-Not-Disturb filter, audio outputs, display count. No permission required.",
        withDevice(""),
        true, false, true, false, "query", "device.environment",
    ),
    // ---- P6: Eta-parity sensitive-action tools (root-only, like Eta) ----
    ToolDef(
        "set_setting",
        "Change one Android Settings value. Root-only.",
        withDevice(
            "\"namespace\":{\"type\":\"string\",\"enum\":[\"system\",\"secure\",\"global\"],\"description\":\"Settings namespace.\"}," +
                "\"key\":{\"type\":\"string\",\"maxLength\":200,\"description\":\"Exact settings key.\"}," +
                "\"value\":{\"type\":\"string\",\"maxLength\":2000,\"description\":\"New value.\"}",
            "\"namespace\",\"key\",\"value\"",
        ),
        false, true, true, false, "write", "setting.set",
    ),
    ToolDef(
        "set_device_state",
        "Enable or disable Wi-Fi/Bluetooth directly; do not operate the Settings GUI. Root-only.",
        withDevice(
            "\"target\":{\"type\":\"string\",\"enum\":[\"wifi\",\"bluetooth\"],\"description\":\"Device capability.\"}," +
                "\"enabled\":{\"type\":\"boolean\",\"description\":\"true enables, false disables.\"}",
            "\"target\",\"enabled\"",
        ),
        false, true, true, false, "write", "device.state",
    ),
    ToolDef(
        "app_state_control",
        "Force-stop, freeze, or unfreeze an exact package name, including system apps. Root-only.",
        withDevice(
            "\"package_name\":{\"type\":\"string\",\"maxLength\":255,\"description\":\"Exact Android package name.\"}," +
                "\"action\":{\"type\":\"string\",\"enum\":[\"force_stop\",\"freeze\",\"unfreeze\"],\"description\":\"Action to perform.\"}",
            "\"package_name\",\"action\"",
        ),
        false, true, true, false, "write", "app.state_control",
    ),
    ToolDef(
        "shell_exec",
        "Execute a shell command on the device. Runs as the app user via 'sh -c' by default; set asRoot to run via 'su -c' when the device is rooted.",
        withDevice(
            "\"command\":{\"type\":\"string\",\"description\":\"Shell command to run.\"}," +
                "\"asRoot\":{\"type\":\"boolean\",\"description\":\"Run with su (requires a rooted device). Default false.\"}," +
                "\"timeoutMs\":{\"type\":\"integer\",\"description\":\"Max wait in ms (1000-120000). Default 30000.\"}",
            "\"command\"",
        ),
        false, true, false, false, "write", "shell.exec",
    ),
    ToolDef(
        "app_list",
        "List installed applications (package, label, system flag). With query, fuzzy-searches by app name or package fragment instead (case- and diacritic-insensitive, e.g. 'fb' finds Facebook).",
        withDevice(
            "\"system\":{\"type\":\"boolean\",\"description\":\"Include system apps. Default false.\"}," +
                "\"query\":{\"type\":\"string\",\"description\":\"Fuzzy app name/package fragment. Omit to list all apps.\"}," +
                "\"limit\":{\"type\":\"integer\",\"description\":\"Max results when query is given, 1 to 20. Default 10.\"}",
        ),
        true, false, true, false, "query", "app.list",
    ),
    ToolDef(
        "app_info",
        "Get details about one installed package: label, versionName, versionCode, system flag.",
        withDevice(
            "\"package\":{\"type\":\"string\",\"description\":\"Application package name.\"}",
            "\"package\"",
        ),
        true, false, true, false, "query", "app.info",
    ),
    ToolDef(
        "app_install",
        "Install an APK on the device from base64-encoded bytes. Returns a PackageInstaller session id; the system shows a user confirmation dialog.",
        withDevice(
            "\"apkBase64\":{\"type\":\"string\",\"description\":\"APK file bytes, base64-encoded.\"}",
            "\"apkBase64\"",
        ),
        false, true, false, false, "write", "app.install",
    ),
    ToolDef(
        "app_uninstall",
        "Uninstall an application. Opens the system uninstall dialog, so the user must confirm.",
        withDevice(
            "\"package\":{\"type\":\"string\",\"description\":\"Application package name.\"}",
            "\"package\"",
        ),
        false, true, false, false, "write", "app.uninstall",
    ),
    ToolDef(
        "app_start",
        "Launch an application: by package (its launcher intent), by fuzzy app_name (resolved to a package; ambiguous names return candidates), or by explicit action/uri with optional string extras.",
        withDevice(
            "\"package\":{\"type\":\"string\",\"description\":\"Application package name (used when action is omitted). Takes precedence over app_name.\"}," +
                "\"app_name\":{\"type\":\"string\",\"description\":\"App display name, fuzzy-matched (e.g. \\\"Facebook\\\"). Used when package is omitted.\"}," +
                "\"action\":{\"type\":\"string\",\"description\":\"Intent action, e.g. android.intent.action.VIEW.\"}," +
                "\"uri\":{\"type\":\"string\",\"description\":\"Intent data URI.\"}," +
                "\"extras\":{\"type\":\"object\",\"description\":\"String extras to attach to the intent.\",\"additionalProperties\":{\"type\":\"string\"}}",
        ),
        false, false, false, false, "write", "app.start",
    ),
    ToolDef(
        "open_uri",
        "Open a URI with the system's ACTION_VIEW handler (browser, maps, deep links...). The URI must have a scheme and at least one app must resolve it.",
        withDevice(
            "\"uri\":{\"type\":\"string\",\"description\":\"URI to open, e.g. https://example.com or geo:0,0.\"}",
            "\"uri\"",
        ),
        false, false, false, false, "write", "app.open_uri",
    ),
    ToolDef(
        "app_stop",
        "Force-stop an application (am force-stop). Requires a rooted device.",
        withDevice(
            "\"package\":{\"type\":\"string\",\"description\":\"Application package name.\"}",
            "\"package\"",
        ),
        false, true, false, false, "write", "app.stop",
    ),
    ToolDef(
        "file_list",
        "List a directory on the device (name, path, isDir, size, modified).",
        withDevice(
            "\"path\":{\"type\":\"string\",\"description\":\"Absolute directory path.\"}",
            "\"path\"",
        ),
        true, false, true, false, "query", "file.list",
    ),
    ToolDef(
        "file_pull",
        "Read a file from the device. Returns name, size and base64 content (max 10MB).",
        withDevice(
            "\"path\":{\"type\":\"string\",\"description\":\"Absolute file path.\"}",
            "\"path\"",
        ),
        true, false, true, false, "query", "file.pull",
    ),
    ToolDef(
        "file_push",
        "Write base64-encoded bytes to a path on the device. Uses root fallback for paths outside the app sandbox.",
        withDevice(
            "\"path\":{\"type\":\"string\",\"description\":\"Absolute destination path.\"}," +
                "\"base64\":{\"type\":\"string\",\"description\":\"File bytes, base64-encoded.\"}," +
                "\"mode\":{\"type\":\"string\",\"description\":\"Optional chmod mode, e.g. '644'.\"}",
            "\"path\",\"base64\"",
        ),
        false, true, false, false, "write", "file.push",
    ),
    ToolDef(
        "file_delete",
        "Delete a file or directory on the device.",
        withDevice(
            "\"path\":{\"type\":\"string\",\"description\":\"Absolute path.\"}",
            "\"path\"",
        ),
        false, true, false, false, "write", "file.delete",
    ),
    ToolDef(
        "screen_capture",
        "Take a screenshot. Returns PNG bytes as an image content block plus width/height. Needs root or a granted MediaProjection session.",
        withDevice(""),
        true, false, true, false, "query", "screen.capture",
    ),
    ToolDef(
        "input_tap",
        "Tap a UI element or a screen point. Preferred: pass elementId from ui_snapshot (taps the live accessibility node directly, no coordinate guessing). Fallback: x + y in pixels. Pass observationId from the ui_snapshot you acted on: if the screen changed since, the tap is rejected with STALE_OBSERVATION instead of tapping the wrong element.",
        withDevice(
            "\"elementId\":{\"type\":\"string\",\"description\":\"Element id from ui_snapshot (e.g. \\\"e12\\\").\"}," +
                "\"observationId\":{\"type\":\"string\",\"description\":\"observation_id from the ui_snapshot this element came from. Optional; when given and stale, the tap is rejected.\"}," +
                "\"x\":{\"type\":\"integer\",\"description\":\"X coordinate in pixels.\"}," +
                "\"y\":{\"type\":\"integer\",\"description\":\"Y coordinate in pixels.\"}",
        ),
        false, false, false, false, "write", "input.tap",
    ),
    ToolDef(
        "tap_and_observe",
        "Tap a UI element and get a fresh ui_snapshot in ONE call: tap -> device settles -> new snapshot, all in the same response. This replaces the 3-turn pattern (ui_snapshot -> input_tap -> ui_snapshot). Returns {tap: {...}, snapshot: {observation_id, count, elements}}.",
        withDevice(
            "\"elementId\":{\"type\":\"string\",\"description\":\"Element id from ui_snapshot (e.g. \\\"e12\\\").\"}," +
                "\"observationId\":{\"type\":\"string\",\"description\":\"observation_id from the ui_snapshot this element came from. Optional; when given and stale, nothing is tapped.\"}",
            "\"elementId\"",
        ),
        false, false, false, false, "write", "input.tap_observe",
    ),
    ToolDef(
        "input_swipe",
        "Swipe from (x1, y1) to (x2, y2) in pixels over durationMs milliseconds.",
        withDevice(
            "\"x1\":{\"type\":\"integer\"},\"y1\":{\"type\":\"integer\"}," +
                "\"x2\":{\"type\":\"integer\"},\"y2\":{\"type\":\"integer\"}," +
                "\"durationMs\":{\"type\":\"integer\",\"description\":\"Gesture duration in ms. Default 300.\"}",
            "\"x1\",\"y1\",\"x2\",\"y2\"",
        ),
        false, false, false, false, "write", "input.swipe",
    ),
    ToolDef(
        "tap_area",
        "Tap the center of a rectangle. Prefer this for large buttons, large list items, and visible text regions. Coordinates in pixels.",
        withDevice(
            "\"x1\":{\"type\":\"integer\"},\"y1\":{\"type\":\"integer\"}," +
                "\"x2\":{\"type\":\"integer\"},\"y2\":{\"type\":\"integer\"}",
            "\"x1\",\"y1\",\"x2\",\"y2\"",
        ),
        false, false, false, false, "write", "input.tap_area",
    ),
    ToolDef(
        "long_press",
        "Long-press a screen point in pixels. Accessibility gesture first, root 'input swipe' fallback. Never replayed when the gesture outcome is unknown.",
        withDevice(
            "\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"}," +
                "\"durationMs\":{\"type\":\"integer\",\"description\":\"Long-press duration, 300 to 3000. Default 800.\"}",
            "\"x\",\"y\"",
        ),
        false, false, false, false, "write", "input.long_press",
    ),
    ToolDef(
        "long_press_element",
        "Long-press a UI element from a ui_snapshot (elementId). Tries ACTION_LONG_CLICK on the live node first, then the element center. Pass observationId to reject stale screens.",
        withDevice(
            "\"elementId\":{\"type\":\"string\",\"description\":\"Element id from ui_snapshot (e.g. \\\"e12\\\").\"}," +
                "\"observationId\":{\"type\":\"string\",\"description\":\"observation_id from the ui_snapshot this element came from. Optional; when given and stale, nothing is pressed.\"}," +
                "\"durationMs\":{\"type\":\"integer\",\"description\":\"Long-press duration, 300 to 3000. Default 800.\"}",
            "\"elementId\"",
        ),
        false, false, false, false, "write", "input.long_press_element",
    ),
    ToolDef(
        "scroll",
        "Scroll the current screen in content-browsing direction: down shows content below, up shows content above, left shows content to the left, right shows content to the right.",
        withDevice(
            "\"direction\":{\"type\":\"string\",\"enum\":[\"up\",\"down\",\"left\",\"right\"]}",
            "\"direction\"",
        ),
        false, false, false, false, "write", "input.scroll",
    ),
    ToolDef(
        "scroll_element",
        "Scroll a scrollable UI element from a ui_snapshot (elementId) in content-browsing direction: down shows content below, up shows content above, left shows content to the left, right shows content to the right. Pass observationId to reject stale screens.",
        withDevice(
            "\"elementId\":{\"type\":\"string\",\"description\":\"Scrollable element id from ui_snapshot (e.g. \\\"e12\\\").\"}," +
                "\"observationId\":{\"type\":\"string\",\"description\":\"observation_id from the ui_snapshot this element came from. Optional; when given and stale, nothing is scrolled.\"}," +
                "\"direction\":{\"type\":\"string\",\"enum\":[\"up\",\"down\",\"left\",\"right\"]}",
            "\"elementId\",\"direction\"",
        ),
        false, false, false, false, "write", "input.scroll_element",
    ),
    ToolDef(
        "input_key",
        "Send a key event: by Android key code, or by named button (BACK/HOME/ENTER/RECENTS/PASTE/NOTIFICATIONS/QUICK_SETTINGS). Named buttons prefer accessibility global actions; key codes fall back to 'input keyevent'.",
        withDevice(
            "\"keyCode\":{\"type\":\"integer\",\"description\":\"Android KeyEvent key code (e.g. 3 = HOME, 4 = BACK, 66 = ENTER).\"}," +
                "\"button\":{\"type\":\"string\",\"enum\":[\"BACK\",\"HOME\",\"ENTER\",\"RECENTS\",\"PASTE\",\"NOTIFICATIONS\",\"QUICK_SETTINGS\"],\"description\":\"Named button; takes precedence over keyCode when both are given.\"}",
        ),
        false, false, false, false, "write", "input.key",
    ),
    ToolDef(
        "input_text",
        "Type text into the currently focused input field (accessibility) or via the 'input text' shell command.",
        withDevice(
            "\"text\":{\"type\":\"string\",\"description\":\"Text to type.\"}",
            "\"text\"",
        ),
        false, false, false, false, "write", "input.text",
    ),
    ToolDef(
        "replace_text",
        "Replace the text of the currently focused field or a specified editable element (elementId from ui_snapshot). Requires accessibility; no shell fallback. Pass observationId to reject stale screens.",
        withDevice(
            "\"text\":{\"type\":\"string\",\"maxLength\":4000,\"description\":\"Replacement text, up to 4000 characters.\"}," +
                "\"elementId\":{\"type\":\"string\",\"description\":\"Editable element id from ui_snapshot. Omit to use the focused field.\"}," +
                "\"observationId\":{\"type\":\"string\",\"description\":\"observation_id from the ui_snapshot this element came from. Optional; when given and stale, nothing is replaced.\"}",
            "\"text\"",
        ),
        false, false, false, false, "write", "input.replace_text",
    ),
    ToolDef(
        "clear_text",
        "Clear the currently focused field or a specified editable element (elementId from ui_snapshot). Requires accessibility. Pass observationId to reject stale screens.",
        withDevice(
            "\"elementId\":{\"type\":\"string\",\"description\":\"Editable element id from ui_snapshot. Omit to use the focused field.\"}," +
                "\"observationId\":{\"type\":\"string\",\"description\":\"observation_id from the ui_snapshot this element came from. Optional; when given and stale, nothing is cleared.\"}",
        ),
        false, false, false, false, "write", "input.clear_text",
    ),
    ToolDef(
        "set_clipboard",
        "Write text to the system clipboard. Use this to prepare long text, CJK, emoji, or special characters for paste.",
        withDevice(
            "\"text\":{\"type\":\"string\",\"maxLength\":20000,\"description\":\"Text to copy, up to 20000 characters.\"}",
            "\"text\"",
        ),
        false, false, true, false, "write", "clipboard.set",
    ),
    ToolDef(
        "get_clipboard",
        "Read system clipboard text. Android 10+ restricts background reads and may return empty.",
        withDevice(""),
        true, false, true, false, "query", "clipboard.get",
    ),
    ToolDef(
        "paste_text",
        "Insert text via the system clipboard paste path: sets the clipboard, then performs paste on the focused editable field. Requires the accessibility service to confirm real input focus; the clipboard is never modified without focus.",
        withDevice(
            "\"text\":{\"type\":\"string\",\"maxLength\":20000,\"description\":\"Text to paste, up to 20000 characters.\"}",
            "\"text\"",
        ),
        false, false, false, false, "write", "input.paste",
    ),
    ToolDef(
        "ui_dump",
        "Dump the current UI hierarchy as JSON (class, text, content description, bounds, children). Needs the accessibility service, or root as fallback.",
        withDevice(""),
        true, false, true, false, "query", "ui.dump",
    ),
    ToolDef(
        "ui_snapshot",
        "Compact UI snapshot (Eta-style): flat list of on-screen elements with stable ids, text, content description, bounds and clickable/editable/scrollable flags. Noise nodes are filtered on-device. Every snapshot mints an observation_id — pass it as observationId to input_tap/tap_and_observe so a tap on a changed screen is rejected instead of hitting the wrong element. Use the ids with input_tap elementId instead of guessing coordinates. Keep snapshots small with query/compact to avoid truncated output.",
        withDevice(
            "\"query\":{\"type\":\"string\",\"description\":\"Only return elements whose text or description contains this string (case-insensitive).\"}," +
                "\"compact\":{\"type\":\"boolean\",\"description\":\"Omit element bounds to shrink the response. Default false.\"}," +
                "\"maxNodes\":{\"type\":\"integer\",\"description\":\"Max elements to walk (1-2000). Default 500.\"}",
        ),
        true, false, true, false, "query", "ui.snapshot",
    ),
    ToolDef(
        "observe_screen",
        "Observe the current phone screen in ONE call: the Eta-style UI tree (observation_id + elements) plus, optionally, a screenshot. Returns {observation_id, ui_tree, screenshot?}; the screenshot is delivered as an image content block when include_screenshot is true.",
        withDevice(
            "\"include_screenshot\":{\"type\":\"boolean\",\"description\":\"Attach the current screen image. Default false. Enable when UI nodes are not enough (canvas/map/visual content).\"}," +
                "\"include_ui_tree\":{\"type\":\"boolean\",\"description\":\"Include the accessibility UI tree. Default true.\"}," +
                "\"max_nodes\":{\"type\":\"integer\",\"description\":\"Max UI nodes in the tree (1-120). Default 60.\"}",
        ),
        true, false, true, false, "query", "ui.observe",
    ),
    ToolDef(
        "wait_for_text",
        "Wait until a text appears anywhere in the accessibility tree (e.g. after tapping something that loads). The device polls every 350ms and returns in ONE call — no manual snapshot polling. Matching is case-insensitive and diacritic-safe for Vietnamese.",
        withDevice(
            "\"text\":{\"type\":\"string\",\"description\":\"Text to wait for.\"}," +
                "\"timeoutMs\":{\"type\":\"integer\",\"description\":\"Max wait in ms (1000-60000). Default 10000.\"}",
            "\"text\"",
        ),
        true, false, true, false, "query", "ui.wait_text",
    ),
    ToolDef(
        "wait_for_package",
        "Wait until the given Android package is in the foreground. Use after app_start/open_uri to confirm the target app opened. The device polls every 350ms and returns in ONE call.",
        withDevice(
            "\"package_name\":{\"type\":\"string\",\"description\":\"Application package name to wait for.\"}," +
                "\"timeoutMs\":{\"type\":\"integer\",\"description\":\"Max wait in ms (500-60000). Default 10000.\"}",
            "\"package_name\"",
        ),
        true, false, true, false, "query", "ui.wait_package",
    ),
    ToolDef(
        "wait",
        "Wait for a duration so animations, network loads, or page transitions can finish. Do not use this instead of the verifiable waits wait_for_text/wait_for_package.",
        withDevice(
            "\"durationMs\":{\"type\":\"integer\",\"description\":\"Wait duration, 100 to 30000. Default 1000.\"}",
        ),
        true, false, true, false, "query", "input.wait",
    ),
    ToolDef(
        "open_system_panel",
        "Open the notification shade or the quick settings panel. Prefers accessibility global actions; falls back to the statusbar shell command. Aliases 'notification'/'quicksettings'/'settings' are also accepted for quick_settings.",
        withDevice(
            "\"panel\":{\"type\":\"string\",\"enum\":[\"notifications\",\"quick_settings\"]}",
            "\"panel\"",
        ),
        false, false, false, false, "write", "system.panel",
    ),
    ToolDef(
        "tool_flags",
        "Get or change which E-muse tools are enabled. With no arguments, returns the on/off state of every tool plus the four permission group switches (terminal_file, device_direct, sensitive_read, sensitive_action). Pass {set: {shell_exec: false, file_push: false}} to disable tools, or {reset: true} to re-enable all. Disabled tools are hidden from tools/list and rejected on tools/call. A tool is also blocked when its permission group is off (groups are toggled in the app UI, section \"Quyền tool\"). This tool is always available and cannot be disabled.",
        "{\"type\":\"object\",\"properties\":{" +
            "\"set\":{\"type\":\"object\",\"description\":\"Map of tool name to enabled flag, e.g. {shell_exec: false}.\",\"additionalProperties\":{\"type\":\"boolean\"}}," +
            "\"reset\":{\"type\":\"boolean\",\"description\":\"Re-enable every tool.\"}" +
            "},\"additionalProperties\":false}",
        true, false, true, false, "query", null,
    ),
)

val TOOL_BY_NAME: Map<String, ToolDef> = TOOL_DEFS.associateBy { it.name }
