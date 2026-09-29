package io.github.thoitiet.emuse

import org.json.JSONObject

/** Command names for the on-device executor. */
object Cmds {
    const val DEVICE_INFO = "device.info"
    const val DEVICE_CONTEXT = "device.context"
    const val SHELL_EXEC = "shell.exec"
    const val APP_LIST = "app.list"
    const val APP_INFO = "app.info"
    const val APP_INSTALL = "app.install"
    const val APP_UNINSTALL = "app.uninstall"
    const val APP_START = "app.start"
    const val APP_OPEN_URI = "app.open_uri"
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
    const val UI_OBSERVE = "ui.observe"
    const val UI_WAIT_TEXT = "ui.wait_text"
    const val UI_WAIT_PACKAGE = "ui.wait_package"
    const val INPUT_WAIT = "input.wait"
    const val SYSTEM_PANEL = "system.panel"
    const val DEVICE_STATUS = "device.status"
    const val NETWORK_INFO = "network.info"
    const val VOLUME_GET = "volume.get"
    const val VOLUME_SET = "volume.set"
    const val MEDIA_CONTROL = "media.control"
    const val ALARM_SET = "alarm.set"
    const val TIMER_SET = "timer.set"
    const val ALARM_LIST = "alarm.list"
    const val MEMORY_TOP_APPS = "memory.top_apps"
    const val STORAGE_TOP_APPS = "storage.top_apps"

    // P5: Eta-parity sensitive-read tools
    const val CONTACTS_SEARCH = "contacts.search"
    const val CALLLOG_SEARCH = "calllog.search"
    const val SMS_SEARCH = "sms.search"
    const val CALENDAR_SEARCH = "calendar.search"
    const val MEDIA_SEARCH = "media.search"
    const val AUDIO_SEARCH = "audio.search"
    const val RECORDINGS_SEARCH = "recordings.search"
    const val FILES_SEARCH = "files.search"
    const val DOWNLOADS_SEARCH = "downloads.search"
    const val LOCATION_GET = "location.get"
    const val APP_ACTIVITY_RECENT = "app.activity.recent"
    const val APP_USAGE_SUMMARY = "app.usage.summary"
    const val NOTIFICATIONS_RECENT = "notifications.recent"
    const val WIFI_CREDENTIALS = "wifi.credentials"
    const val SMS_CODE_READ = "sms.code.read"
    const val LOGCAT_GET = "logcat.get"
    const val SETTING_GET = "setting.get"
    const val DEVICE_ENVIRONMENT = "device.environment"
    const val SETTING_SET = "setting.set"
    const val DEVICE_STATE_SET = "device.state"
    const val APP_STATE_CONTROL = "app.state_control"
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
