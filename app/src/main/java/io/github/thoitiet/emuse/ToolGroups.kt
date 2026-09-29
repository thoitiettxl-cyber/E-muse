package io.github.thoitiet.emuse

/**
 * Permission groups for MCP tools, ported from Eta's group gating
 * (AGENT_TERMINAL_TOOLS / AGENT_DEVICE_DIRECT_TOOLS /
 * AGENT_DEVICE_SENSITIVE_READ_TOOLS / AGENT_DEVICE_SENSITIVE_ACTION_TOOLS).
 *
 * Two independent enforcement layers exist in E-Muse:
 *  - tool_flags: per-tool on/off (runtime, via the tool_flags tool)
 *  - group switches: per-group on/off (UI toggles in MainActivity)
 * A tool is blocked when EITHER its flag is off OR its group is off.
 *
 * Differences from Eta (product decisions):
 *  - E-Muse adds a UI switch for sensitive_read (Eta has none).
 *  - No browser group: browser_use was dropped, an empty switch is pointless.
 *  - Both sensitive groups default OFF (Eta defaults everything on).
 */
enum class ToolGroup(
    /** SharedPreferences key. */
    val prefKey: String,
    val defaultEnabled: Boolean,
    val title: String,
    val summary: String,
) {
    TERMINAL_FILE(
        "group_terminal_file", true,
        "Terminal & file",
        "shell_exec và các tool đọc/ghi file",
    ),
    DEVICE_DIRECT(
        "group_device_direct", true,
        "Thiết bị trực tiếp",
        "Thông tin máy, danh sách app, các tool chờ",
    ),
    SENSITIVE_READ(
        "group_sensitive_read", false,
        "Đọc dữ liệu nhạy cảm",
        "Clipboard, screenshot, cây UI, vị trí",
    ),
    SENSITIVE_ACTION(
        "group_sensitive_action", false,
        "Thao tác nhạy cảm",
        "Mở app, chạm/vuốt/gõ phím, cài/gỡ app",
    ),
}

/**
 * Every tool except the meta tool `tool_flags` belongs to exactly one
 * group. Tools missing from this map are fail-closed: blocked until
 * assigned a group.
 */
val TOOL_GROUP_BY_NAME: Map<String, ToolGroup> = mapOf(
    // ---- terminal_file ----
    "shell_exec" to ToolGroup.TERMINAL_FILE,
    "file_list" to ToolGroup.TERMINAL_FILE,
    "file_pull" to ToolGroup.TERMINAL_FILE,
    "file_push" to ToolGroup.TERMINAL_FILE,
    "file_delete" to ToolGroup.TERMINAL_FILE,
    // ---- device_direct ----
    "device_list" to ToolGroup.DEVICE_DIRECT,
    "device_info" to ToolGroup.DEVICE_DIRECT,
    "device_status" to ToolGroup.DEVICE_DIRECT,
    "network_info" to ToolGroup.DEVICE_DIRECT,
    "get_volume" to ToolGroup.DEVICE_DIRECT,
    "set_volume" to ToolGroup.DEVICE_DIRECT,
    "media_control" to ToolGroup.DEVICE_DIRECT,
    "set_alarm" to ToolGroup.DEVICE_DIRECT,
    "set_timer" to ToolGroup.DEVICE_DIRECT,
    "list_alarms" to ToolGroup.SENSITIVE_READ,
    "list_active_timers" to ToolGroup.SENSITIVE_READ,
    "top_memory_apps" to ToolGroup.DEVICE_DIRECT,
    "top_storage_apps" to ToolGroup.DEVICE_DIRECT,

    // P5: Eta-parity sensitive-read tools
    "search_contacts" to ToolGroup.SENSITIVE_READ,
    "search_call_history" to ToolGroup.SENSITIVE_READ,
    "search_messages" to ToolGroup.SENSITIVE_READ,
    "search_calendar_events" to ToolGroup.SENSITIVE_READ,
    "search_media" to ToolGroup.SENSITIVE_READ,
    "search_audio" to ToolGroup.SENSITIVE_READ,
    "search_recordings" to ToolGroup.SENSITIVE_READ,
    "search_files" to ToolGroup.SENSITIVE_READ,
    "search_downloads" to ToolGroup.SENSITIVE_READ,
    "get_current_location" to ToolGroup.SENSITIVE_READ,
    "recent_app_activity" to ToolGroup.SENSITIVE_READ,
    "app_usage_summary" to ToolGroup.SENSITIVE_READ,
    "recent_notifications" to ToolGroup.SENSITIVE_READ,
    "wifi_credentials" to ToolGroup.SENSITIVE_READ,
    "read_sms_code" to ToolGroup.SENSITIVE_READ,
    "get_logcat" to ToolGroup.SENSITIVE_READ,
    "get_setting" to ToolGroup.SENSITIVE_READ,
    "get_device_environment" to ToolGroup.SENSITIVE_READ,

    // P7: Eta-parity ColorOS-specific tools (personal data -> sensitive read)
    "search_coloros_notes" to ToolGroup.SENSITIVE_READ,
    "search_coloros_recordings" to ToolGroup.SENSITIVE_READ,
    "search_recording_summaries" to ToolGroup.SENSITIVE_READ,
    "search_coloros_memories" to ToolGroup.SENSITIVE_READ,
    "search_personal_orders" to ToolGroup.SENSITIVE_READ,
    "search_saved_places" to ToolGroup.SENSITIVE_READ,
    "app_list" to ToolGroup.DEVICE_DIRECT,
    "app_info" to ToolGroup.DEVICE_DIRECT,
    "wait" to ToolGroup.DEVICE_DIRECT,
    "wait_for_text" to ToolGroup.DEVICE_DIRECT,
    "wait_for_package" to ToolGroup.DEVICE_DIRECT,
    // ---- sensitive_read ----
    "get_current_context" to ToolGroup.SENSITIVE_READ,
    "set_clipboard" to ToolGroup.SENSITIVE_READ,
    "get_clipboard" to ToolGroup.SENSITIVE_READ,
    "screen_capture" to ToolGroup.SENSITIVE_READ,
    "ui_dump" to ToolGroup.SENSITIVE_READ,
    "ui_snapshot" to ToolGroup.SENSITIVE_READ,
    "observe_screen" to ToolGroup.SENSITIVE_READ,
    // ---- sensitive_action ----
    "app_install" to ToolGroup.SENSITIVE_ACTION,
    "app_uninstall" to ToolGroup.SENSITIVE_ACTION,
    "app_start" to ToolGroup.SENSITIVE_ACTION,
    "app_stop" to ToolGroup.SENSITIVE_ACTION,
    "open_uri" to ToolGroup.SENSITIVE_ACTION,
    "input_tap" to ToolGroup.SENSITIVE_ACTION,
    "tap_and_observe" to ToolGroup.SENSITIVE_ACTION,
    "input_swipe" to ToolGroup.SENSITIVE_ACTION,
    "tap_area" to ToolGroup.SENSITIVE_ACTION,
    "long_press" to ToolGroup.SENSITIVE_ACTION,
    "long_press_element" to ToolGroup.SENSITIVE_ACTION,
    "scroll" to ToolGroup.SENSITIVE_ACTION,
    "scroll_element" to ToolGroup.SENSITIVE_ACTION,
    "input_key" to ToolGroup.SENSITIVE_ACTION,
    "input_text" to ToolGroup.SENSITIVE_ACTION,
    "replace_text" to ToolGroup.SENSITIVE_ACTION,
    "clear_text" to ToolGroup.SENSITIVE_ACTION,
    "paste_text" to ToolGroup.SENSITIVE_ACTION,
    "open_system_panel" to ToolGroup.SENSITIVE_ACTION,
    // P6: Eta-parity sensitive-action tools
    "set_setting" to ToolGroup.SENSITIVE_ACTION,
    "set_device_state" to ToolGroup.SENSITIVE_ACTION,
    "app_state_control" to ToolGroup.SENSITIVE_ACTION,
)

/** Cmd -> tool name, for the CommandDispatcher enforcement layer. */
val TOOL_NAME_BY_CMD: Map<String, String> = mapOf(
    Cmds.DEVICE_INFO to "device_info",
    Cmds.DEVICE_CONTEXT to "get_current_context",
    Cmds.SHELL_EXEC to "shell_exec",
    Cmds.APP_LIST to "app_list",
    Cmds.APP_INFO to "app_info",
    Cmds.APP_INSTALL to "app_install",
    Cmds.APP_UNINSTALL to "app_uninstall",
    Cmds.APP_START to "app_start",
    Cmds.APP_OPEN_URI to "open_uri",
    Cmds.APP_STOP to "app_stop",
    Cmds.FILE_LIST to "file_list",
    Cmds.FILE_PULL to "file_pull",
    Cmds.FILE_PUSH to "file_push",
    Cmds.FILE_DELETE to "file_delete",
    Cmds.SCREEN_CAPTURE to "screen_capture",
    Cmds.INPUT_TAP to "input_tap",
    Cmds.INPUT_TAP_AREA to "tap_area",
    Cmds.INPUT_TAP_OBSERVE to "tap_and_observe",
    Cmds.INPUT_SWIPE to "input_swipe",
    Cmds.INPUT_LONG_PRESS to "long_press",
    Cmds.INPUT_LONG_PRESS_ELEMENT to "long_press_element",
    Cmds.INPUT_SCROLL to "scroll",
    Cmds.INPUT_SCROLL_ELEMENT to "scroll_element",
    Cmds.INPUT_KEY to "input_key",
    Cmds.INPUT_TEXT to "input_text",
    Cmds.INPUT_REPLACE_TEXT to "replace_text",
    Cmds.INPUT_CLEAR_TEXT to "clear_text",
    Cmds.INPUT_PASTE to "paste_text",
    Cmds.CLIPBOARD_SET to "set_clipboard",
    Cmds.CLIPBOARD_GET to "get_clipboard",
    Cmds.UI_DUMP to "ui_dump",
    Cmds.UI_SNAPSHOT to "ui_snapshot",
    Cmds.UI_OBSERVE to "observe_screen",
    Cmds.UI_WAIT_TEXT to "wait_for_text",
    Cmds.UI_WAIT_PACKAGE to "wait_for_package",
    Cmds.INPUT_WAIT to "wait",
    Cmds.SYSTEM_PANEL to "open_system_panel",
    Cmds.DEVICE_STATUS to "device_status",
    Cmds.NETWORK_INFO to "network_info",
    Cmds.VOLUME_GET to "get_volume",
    Cmds.VOLUME_SET to "set_volume",
    Cmds.MEDIA_CONTROL to "media_control",
    Cmds.ALARM_SET to "set_alarm",
    Cmds.TIMER_SET to "set_timer",
    Cmds.ALARM_LIST to "list_alarms",
    Cmds.TIMER_ACTIVE_LIST to "list_active_timers",
    Cmds.MEMORY_TOP_APPS to "top_memory_apps",
    Cmds.STORAGE_TOP_APPS to "top_storage_apps",

    // P5: Eta-parity sensitive-read tools
    Cmds.CONTACTS_SEARCH to "search_contacts",
    Cmds.CALLLOG_SEARCH to "search_call_history",
    Cmds.SMS_SEARCH to "search_messages",
    Cmds.CALENDAR_SEARCH to "search_calendar_events",
    Cmds.MEDIA_SEARCH to "search_media",
    Cmds.AUDIO_SEARCH to "search_audio",
    Cmds.RECORDINGS_SEARCH to "search_recordings",
    Cmds.FILES_SEARCH to "search_files",
    Cmds.DOWNLOADS_SEARCH to "search_downloads",
    Cmds.LOCATION_GET to "get_current_location",
    Cmds.APP_ACTIVITY_RECENT to "recent_app_activity",
    Cmds.APP_USAGE_SUMMARY to "app_usage_summary",
    Cmds.NOTIFICATIONS_RECENT to "recent_notifications",
    Cmds.WIFI_CREDENTIALS to "wifi_credentials",
    Cmds.SMS_CODE_READ to "read_sms_code",
    Cmds.LOGCAT_GET to "get_logcat",
    Cmds.SETTING_GET to "get_setting",
    Cmds.DEVICE_ENVIRONMENT to "get_device_environment",

    // P7: Eta-parity ColorOS-specific tools
    Cmds.COLOROS_NOTES_SEARCH to "search_coloros_notes",
    Cmds.COLOROS_RECORDINGS_SEARCH to "search_coloros_recordings",
    Cmds.RECORDING_SUMMARIES_SEARCH to "search_recording_summaries",
    Cmds.COLOROS_MEMORIES_SEARCH to "search_coloros_memories",
    Cmds.PERSONAL_ORDERS_SEARCH to "search_personal_orders",
    Cmds.SAVED_PLACES_SEARCH to "search_saved_places",

    // P6: Eta-parity sensitive-action tools
    Cmds.SETTING_SET to "set_setting",
    Cmds.DEVICE_STATE_SET to "set_device_state",
    Cmds.APP_STATE_CONTROL to "app_state_control",
)

/**
 * Returns null when the tool may run, otherwise a human-readable reason.
 * `isEnabled` reads the group switch (backed by Prefs).
 * `tool_flags` is exempt (always available); unmapped tools are fail-closed.
 */
fun groupBlockReason(toolName: String, isEnabled: (ToolGroup) -> Boolean): String? {
    if (toolName == "tool_flags") return null
    val group = TOOL_GROUP_BY_NAME[toolName]
        ?: return "Tool \"$toolName\" is not assigned to a permission group and is blocked."
    if (isEnabled(group)) return null
    return "Tool \"$toolName\" is in group \"${group.title}\" which is disabled. " +
        "Enable it in the E-Muse app (section \"Quyền tool\") to use this tool."
}
