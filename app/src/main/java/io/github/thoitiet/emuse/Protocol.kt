package io.github.thoitiet.emuse

import org.json.JSONObject

/** Command names for the on-device executor. */
object Cmds {
    const val DEVICE_INFO = "device.info"
    const val SHELL_EXEC = "shell.exec"
    const val APP_LIST = "app.list"
    const val APP_INFO = "app.info"
    const val APP_INSTALL = "app.install"
    const val APP_UNINSTALL = "app.uninstall"
    const val APP_START = "app.start"
    const val APP_STOP = "app.stop"
    const val FILE_LIST = "file.list"
    const val FILE_PULL = "file.pull"
    const val FILE_PUSH = "file.push"
    const val FILE_DELETE = "file.delete"
    const val SCREEN_CAPTURE = "screen.capture"
    const val INPUT_TAP = "input.tap"
    const val INPUT_TAP_AREA = "input.tap_area"
    const val INPUT_TAP_OBSERVE = "input.tap_observe"
    const val INPUT_SWIPE = "input.swipe"
    const val INPUT_LONG_PRESS = "input.long_press"
    const val INPUT_LONG_PRESS_ELEMENT = "input.long_press_element"
    const val INPUT_SCROLL = "input.scroll"
    const val INPUT_SCROLL_ELEMENT = "input.scroll_element"
    const val INPUT_KEY = "input.key"
    const val INPUT_TEXT = "input.text"
    const val INPUT_REPLACE_TEXT = "input.replace_text"
    const val INPUT_CLEAR_TEXT = "input.clear_text"
    const val INPUT_PASTE = "input.paste"
    const val CLIPBOARD_SET = "clipboard.set"
    const val CLIPBOARD_GET = "clipboard.get"
    const val UI_DUMP = "ui.dump"
    const val UI_SNAPSHOT = "ui.snapshot"
    const val UI_WAIT_TEXT = "ui.wait_text"
    const val UI_WAIT_PACKAGE = "ui.wait_package"
    const val INPUT_WAIT = "input.wait"
    const val SYSTEM_PANEL = "system.panel"
}

data class DeviceCommand(val id: String, val cmd: String, val args: JSONObject) {
    companion object {
        fun fromJson(o: JSONObject): DeviceCommand =
            DeviceCommand(
                o.getString("id"),
                o.getString("cmd"),
                o.optJSONObject("args") ?: JSONObject(),
            )
    }
}

data class DeviceResult(
    val id: String,
    val ok: Boolean,
    val result: Any? = null,
    val error: String? = null,
) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("type", "result").put("id", id).put("ok", ok)
        if (ok) o.put("result", result ?: JSONObject())
        else o.put("error", error ?: "unknown error")
        return o
    }
}
