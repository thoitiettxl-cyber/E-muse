package io.github.thoitiet.emuse

import org.json.JSONObject

/** Command names — must match workers/mcp/protocol.ts Cmd union. */
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
    const val INPUT_SWIPE = "input.swipe"
    const val INPUT_KEY = "input.key"
    const val INPUT_TEXT = "input.text"
    const val UI_DUMP = "ui.dump"
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
        val o = JSONObject().put("id", id).put("ok", ok)
        if (ok) o.put("result", result ?: JSONObject())
        else o.put("error", error ?: "unknown error")
        return o
    }
}
