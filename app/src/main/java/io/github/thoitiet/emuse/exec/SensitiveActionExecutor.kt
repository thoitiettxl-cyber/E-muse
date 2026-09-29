package io.github.thoitiet.emuse.exec

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject

/**
 * Eta parity: sensitive-action tools that mutate device/app state.
 *
 * All three tools are root-only (same as Eta): `settings put`, `cmd wifi` /
 * `cmd bluetooth_manager`, `am` / `pm`. No new manifest permissions needed;
 * QUERY_ALL_PACKAGES (already declared) covers the getApplicationInfo check.
 */
class SensitiveActionExecutor(private val appCtx: Context) {

    private fun err(code: String, message: String, tool: String): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .put("tool", tool)

    private fun ok(tool: String): JSONObject =
        JSONObject().put("ok", true).put("tool", tool)

    private fun requireRoot(tool: String): JSONObject? =
        if (ShellExecutor.hasRoot()) null
        else err("ROOT_REQUIRED", "This tool needs root (KernelSU/Magisk).", tool)

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    // ------------------------------------------------------------------
    // set_setting (Eta parity)
    // ------------------------------------------------------------------

    fun setSetting(namespace: String, key: String, value: String): JSONObject {
        val tool = "set_setting"
        val ns = namespace.lowercase()
        if (ns != "system" && ns != "secure" && ns != "global") {
            return err("INVALID_NAMESPACE", "namespace must be system, secure or global", tool)
        }
        if (key.isBlank() || key.length > 200) {
            return err("INVALID_KEY", "key must be 1-200 characters", tool)
        }
        if (value.length > 2000) {
            return err("INVALID_VALUE", "value must be at most 2000 characters", tool)
        }
        requireRoot(tool)?.let { return it }
        val r = ShellExecutor.exec(
            "settings --user current put ${shellQuote(ns)} ${shellQuote(key)} ${shellQuote(value)}",
            asRoot = true,
        )
        if (!r.ok) {
            return err(
                "SET_FAILED",
                r.stderr.take(200).ifEmpty { "settings put failed" },
                tool,
            )
        }
        return ok(tool).put("namespace", ns).put("key", key).put("value", value)
    }

    // ------------------------------------------------------------------
    // set_device_state (Eta parity)
    // ------------------------------------------------------------------

    fun setDeviceState(target: String, enabled: Boolean): JSONObject {
        val tool = "set_device_state"
        requireRoot(tool)?.let { return it }
        val command = when (target.lowercase()) {
            "wifi" -> "cmd wifi set-wifi-enabled ${if (enabled) "enabled" else "disabled"}"
            "bluetooth" -> "cmd bluetooth_manager ${if (enabled) "enable" else "disable"}"
            else -> return err("INVALID_TARGET", "target must be wifi or bluetooth", tool)
        }
        val r = ShellExecutor.exec(command, asRoot = true)
        if (!r.ok) {
            return err(
                "STATE_CHANGE_FAILED",
                r.stderr.take(200).ifEmpty { "command failed" },
                tool,
            )
        }
        return ok(tool).put("target", target.lowercase()).put("enabled", enabled)
    }

    // ------------------------------------------------------------------
    // app_state_control (Eta parity: force_stop / freeze / unfreeze)
    // ------------------------------------------------------------------

    fun appStateControl(packageName: String, action: String): JSONObject {
        val tool = "app_state_control"
        requireRoot(tool)?.let { return it }
        if (!PACKAGE_RE.matches(packageName)) {
            return err("INVALID_PACKAGE", "package name format invalid", tool)
        }
        val exists = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                appCtx.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                appCtx.packageManager.getApplicationInfo(packageName, 0)
            }
        }.isSuccess
        if (!exists) {
            return err("APP_NOT_FOUND", "package not installed: $packageName", tool)
        }
        val command = when (action.lowercase()) {
            "force_stop" -> "am force-stop --user current ${shellQuote(packageName)}"
            "freeze" -> "pm disable-user --user current ${shellQuote(packageName)}"
            "unfreeze" -> "pm enable --user current ${shellQuote(packageName)}"
            else -> return err(
                "INVALID_ACTION",
                "action must be force_stop, freeze or unfreeze",
                tool,
            )
        }
        val r = ShellExecutor.exec(command, asRoot = true)
        if (!r.ok) {
            return err(
                "ACTION_FAILED",
                r.stderr.take(200).ifEmpty { "command failed" },
                tool,
            )
        }
        return ok(tool).put("package_name", packageName).put("action", action.lowercase())
    }

    companion object {
        // Same as Eta's PACKAGE_NAME regex.
        private val PACKAGE_RE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
}
