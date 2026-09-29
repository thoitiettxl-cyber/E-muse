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
        "shell_exec",
        "Execute a shell command on the device. Runs as the app user via 'sh -c' by default; set asRoot to run via 'su -c' when the device is rooted.",
        withDevice(
            "\"command\":{\"type\":\"string\",\"description\":\"Shell command to run.\"}," +
                "\"asRoot\":{\"type\":\"boolean\",\"description\":\"Run with su (requires a rooted device). Default false.\"}," +
                "\"timeoutMs\":{\"type\":\"number\",\"description\":\"Max wait in ms (1000-120000). Default 30000.\"}",
            "\"command\"",
        ),
        false, true, false, false, "write", "shell.exec",
    ),
    ToolDef(
        "app_list",
        "List installed applications (package, label, system flag).",
        withDevice("\"system\":{\"type\":\"boolean\",\"description\":\"Include system apps. Default false.\"}"),
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
        "Launch an application: by package (its launcher intent) or by explicit action/uri with optional string extras.",
        withDevice(
            "\"package\":{\"type\":\"string\",\"description\":\"Application package name (used when action is omitted).\"}," +
                "\"action\":{\"type\":\"string\",\"description\":\"Intent action, e.g. android.intent.action.VIEW.\"}," +
                "\"uri\":{\"type\":\"string\",\"description\":\"Intent data URI.\"}," +
                "\"extras\":{\"type\":\"object\",\"description\":\"String extras to attach to the intent.\",\"additionalProperties\":{\"type\":\"string\"}}",
        ),
        false, false, false, false, "write", "app.start",
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
                "\"x\":{\"type\":\"number\",\"description\":\"X coordinate in pixels.\"}," +
                "\"y\":{\"type\":\"number\",\"description\":\"Y coordinate in pixels.\"}",
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
            "\"x1\":{\"type\":\"number\"},\"y1\":{\"type\":\"number\"}," +
                "\"x2\":{\"type\":\"number\"},\"y2\":{\"type\":\"number\"}," +
                "\"durationMs\":{\"type\":\"number\",\"description\":\"Gesture duration in ms. Default 300.\"}",
            "\"x1\",\"y1\",\"x2\",\"y2\"",
        ),
        false, false, false, false, "write", "input.swipe",
    ),
    ToolDef(
        "input_key",
        "Send a key event by Android key code (e.g. 3 = HOME, 4 = BACK, 66 = ENTER).",
        withDevice(
            "\"keyCode\":{\"type\":\"number\",\"description\":\"Android KeyEvent key code.\"}",
            "\"keyCode\"",
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
                "\"maxNodes\":{\"type\":\"number\",\"description\":\"Max elements to walk (1-2000). Default 500.\"}",
        ),
        true, false, true, false, "query", "ui.snapshot",
    ),
    ToolDef(
        "wait_for_text",
        "Wait until a text appears anywhere in the accessibility tree (e.g. after tapping something that loads). The device polls every 350ms and returns in ONE call — no manual snapshot polling. Matching is case-insensitive and diacritic-safe for Vietnamese.",
        withDevice(
            "\"text\":{\"type\":\"string\",\"description\":\"Text to wait for.\"}," +
                "\"timeoutMs\":{\"type\":\"number\",\"description\":\"Max wait in ms (1000-60000). Default 10000.\"}",
            "\"text\"",
        ),
        true, false, true, false, "query", "ui.wait_text",
    ),
    ToolDef(
        "tool_flags",
        "Get or change which E-muse tools are enabled. With no arguments, returns the on/off state of every tool. Pass {set: {shell_exec: false, file_push: false}} to disable tools, or {reset: true} to re-enable all. Disabled tools are hidden from tools/list and rejected on tools/call. This tool is always available and cannot be disabled.",
        "{\"type\":\"object\",\"properties\":{" +
            "\"set\":{\"type\":\"object\",\"description\":\"Map of tool name to enabled flag, e.g. {shell_exec: false}.\",\"additionalProperties\":{\"type\":\"boolean\"}}," +
            "\"reset\":{\"type\":\"boolean\",\"description\":\"Re-enable every tool.\"}" +
            "},\"additionalProperties\":false}",
        true, false, true, false, "query", null,
    ),
)

val TOOL_BY_NAME: Map<String, ToolDef> = TOOL_DEFS.associateBy { it.name }
