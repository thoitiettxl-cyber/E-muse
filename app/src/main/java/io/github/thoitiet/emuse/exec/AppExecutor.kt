package io.github.thoitiet.emuse.exec

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import io.github.thoitiet.emuse.InstallReceiver
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AppExecutor(private val ctx: Context) {
    private val pm: PackageManager get() = ctx.packageManager

    fun list(includeSystem: Boolean): JSONArray {
        @Suppress("DEPRECATION")
        val apps = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            pm.getInstalledApplications(0)
        }
        val arr = JSONArray()
        for (ai in apps) {
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystem && isSystem) continue
            arr.put(
                JSONObject()
                    .put("package", ai.packageName)
                    .put("label", pm.getApplicationLabel(ai).toString())
                    .put("system", isSystem),
            )
        }
        return arr
    }

    fun info(packageName: String): JSONObject {
        @Suppress("DEPRECATION")
        val pi = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getPackageInfo(packageName, 0)
        }
        val ai = pi.applicationInfo ?: throw IllegalArgumentException("no applicationInfo for $packageName")
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        return JSONObject()
            .put("package", pi.packageName)
            .put("label", pm.getApplicationLabel(ai).toString())
            .put("versionName", pi.versionName ?: "")
            .put("versionCode", versionCode)
            .put("system", (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
    }

    /** Writes the APK into a PackageInstaller session and commits it.
     * The system shows a user confirmation dialog; result goes to InstallReceiver. */
    fun install(apkBase64: String): JSONObject {
        val bytes = Base64.decode(apkBase64, Base64.DEFAULT)
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("package.apk", 0, bytes.size.toLong()).use { out ->
                out.write(bytes)
                session.fsync(out)
            }
            val intent = Intent(ctx, InstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val statusReceiver = PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
            session.commit(statusReceiver.intentSender)
        } finally {
            session.close()
        }
        return JSONObject()
            .put("sessionId", sessionId)
            .put("status", "pending_user_confirmation")
    }

    /** Opens the system uninstall dialog; the user must confirm. */
    fun uninstall(packageName: String): JSONObject {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return JSONObject().put("status", "pending_user_confirmation")
    }

    fun start(packageName: String?, action: String?, uri: String?, extras: JSONObject?): JSONObject {
        val intent = if (!action.isNullOrEmpty()) {
            Intent(action, if (!uri.isNullOrEmpty()) Uri.parse(uri) else null)
        } else {
            require(!packageName.isNullOrEmpty()) { "package or action is required" }
            pm.getLaunchIntentForPackage(packageName)
                ?: throw IllegalArgumentException("no launch intent for $packageName")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        extras?.keys()?.forEach { key -> intent.putExtra(key, extras.optString(key)) }
        ctx.startActivity(intent)
        return JSONObject().put("started", true)
    }

    fun stop(packageName: String): JSONObject {
        if (!ShellExecutor.hasRoot()) throw IllegalStateException("app.stop requires root")
        val r = ShellExecutor.exec(RootCommands.forceStop(packageName), asRoot = true)
        if (!r.ok) throw IllegalStateException("force-stop failed: ${r.stderr.trim().take(200)}")
        return JSONObject().put("stopped", true)
    }

    fun cacheApkPath(name: String): File = File(ctx.cacheDir, name)
}
