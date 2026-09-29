package io.github.thoitiet.emuse

import android.content.Context
import android.os.Build
import io.github.thoitiet.emuse.exec.AppExecutor
import io.github.thoitiet.emuse.exec.FileExecutor
import io.github.thoitiet.emuse.exec.InputExecutor
import io.github.thoitiet.emuse.exec.ScreenExecutor
import io.github.thoitiet.emuse.exec.ShellExecutor
import io.github.thoitiet.emuse.exec.UiDumpExecutor
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
    private val send: (DeviceResult) -> Unit,
) {
    private val appCtx = ctx.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val apps = AppExecutor(appCtx)
    private val files = FileExecutor(appCtx)

    init {
        ShellExecutor.reset()
        ScreenExecutor.configure(appCtx.resources.displayMetrics.densityDpi)
    }

    fun dispatch(cmd: DeviceCommand) {
        scope.launch {
            val res = try {
                val result = withTimeout(120_000) { execute(cmd) }
                DeviceResult(cmd.id, true, result)
            } catch (e: TimeoutCancellationException) {
                DeviceResult(cmd.id, false, error = "command timed out after 120s")
            } catch (e: Exception) {
                DeviceResult(cmd.id, false, error = e.message ?: e.javaClass.simpleName)
            }
            send(res)
        }
    }

    fun shutdown() {
        scope.cancel()
        ShellExecutor.close()
    }

    private fun execute(cmd: DeviceCommand): Any? {
        val a = cmd.args
        return when (cmd.cmd) {
            Cmds.DEVICE_INFO -> deviceInfo()
            Cmds.SHELL_EXEC -> ShellExecutor.exec(
                a.getString("command"),
                a.optBoolean("asRoot", false),
                a.optLong("timeoutMs", 30_000).coerceIn(1_000L, 120_000L),
            ).toJson()

            Cmds.APP_LIST -> apps.list(a.optBoolean("system", false))
            Cmds.APP_INFO -> apps.info(a.getString("package"))
            Cmds.APP_INSTALL -> apps.install(a.getString("apkBase64"))
            Cmds.APP_UNINSTALL -> apps.uninstall(a.getString("package"))
            Cmds.APP_START -> apps.start(
                a.optString("package").ifEmpty { null },
                a.optString("action").ifEmpty { null },
                a.optString("uri").ifEmpty { null },
                a.optJSONObject("extras"),
            )
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

            Cmds.INPUT_TAP -> InputExecutor.tap(a.getInt("x"), a.getInt("y"))
            Cmds.INPUT_SWIPE -> InputExecutor.swipe(
                a.getInt("x1"), a.getInt("y1"),
                a.getInt("x2"), a.getInt("y2"),
                a.optInt("durationMs", 300),
            )
            Cmds.INPUT_KEY -> InputExecutor.key(a.getInt("keyCode"))
            Cmds.INPUT_TEXT -> InputExecutor.text(a.getString("text"))

            Cmds.UI_DUMP -> UiDumpExecutor.dump()

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
}
