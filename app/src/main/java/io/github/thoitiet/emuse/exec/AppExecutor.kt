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
import java.text.Normalizer
import java.util.Locale

class AppExecutor(private val ctx: Context) {
    private val pm: PackageManager get() = ctx.packageManager

    /**
     * Lowercase + diacritic-stripped for fuzzy matching (Eta parity, plus
     * Vietnamese diacritic-insensitivity like wait_for_text).
     */
    private fun norm(s: String): String =
        Normalizer.normalize(s.trim(), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .lowercase(Locale.ROOT)

    private data class ScoredApp(val label: String, val packageName: String, val system: Boolean, val score: Int)

    /**
     * Eta-style fuzzy app search: exact package (0), exact label (1),
     * normalized equal (2), normalized package contains (3),
     * normalized label contains (4). Sorted by score, then label.
     */
    private fun findAppsByName(query: String, includeSystem: Boolean): List<ScoredApp> {
        val nq = norm(query)
        if (nq.isEmpty()) return emptyList()
        @Suppress("DEPRECATION")
        val apps = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            pm.getInstalledApplications(0)
        }
        return apps.asSequence()
            .filter { includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .mapNotNull { ai ->
                val label = pm.getApplicationLabel(ai).toString()
                val score = when {
                    ai.packageName.equals(query.trim(), ignoreCase = true) -> 0
                    label.equals(query.trim(), ignoreCase = true) -> 1
                    norm(label) == nq -> 2
                    norm(ai.packageName).contains(nq) -> 3
                    norm(label).contains(nq) -> 4
                    else -> return@mapNotNull null
                }
                ScoredApp(label, ai.packageName, (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0, score)
            }
            .sortedWith(compareBy<ScoredApp> { it.score }.thenBy { it.label })
            .toList()
    }

    fun list(includeSystem: Boolean, query: String? = null, limit: Int = 10): JSONArray {
        val q = query?.trim().orEmpty()
        if (q.isNotEmpty()) {
            val lim = limit.coerceIn(1, 20)
            val arr = JSONArray()
            for (a in findAppsByName(q, includeSystem).take(lim)) {
                arr.put(
                    JSONObject()
                        .put("package", a.packageName)
                        .put("label", a.label)
                        .put("system", a.system),
                )
            }
            return arr
        }
        // No query: legacy behavior, list everything (no limit).
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

    fun start(packageName: String?, action: String?, uri: String?, extras: JSONObject?, appName: String? = null): JSONObject {
        val intent = if (!action.isNullOrEmpty()) {
            Intent(action, if (!uri.isNullOrEmpty()) Uri.parse(uri) else null)
        } else {
            val pkg = packageName?.trim().orEmpty()
            val resolved = if (pkg.isNotEmpty()) {
                pkg
            } else {
                // Eta parity: fuzzy-resolve an app display name to its package.
                val name = appName?.trim().orEmpty()
                require(name.isNotEmpty()) { "package, app_name, or action is required" }
                resolveAppName(name)
            }
            pm.getLaunchIntentForPackage(resolved)
                ?: throw IllegalArgumentException("no launch intent for $resolved")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        extras?.keys()?.forEach { key -> intent.putExtra(key, extras.optString(key)) }
        ctx.startActivity(intent)
        return JSONObject().put("started", true)
    }

    /**
     * Eta's launch_app name resolution: one exact (or single fuzzy) match
     * wins; zero matches -> IllegalArgumentException; several -> list the
     * candidates so the caller can pick a package_name.
     */
    private fun resolveAppName(appName: String): String {
        val matches = findAppsByName(appName, includeSystem = false)
        val exact = matches.filter { it.score <= 1 }
        return when {
            exact.size == 1 -> exact.single().packageName
            matches.size == 1 -> matches.single().packageName
            matches.isEmpty() -> throw IllegalArgumentException("no app matches \"$appName\"")
            else -> {
                val candidates = JSONArray()
                for (m in matches.take(10)) {
                    candidates.put(
                        JSONObject().put("package", m.packageName).put("label", m.label),
                    )
                }
                throw IllegalArgumentException(
                    "ambiguous app_name \"$appName\"; specify package_name. candidates=" +
                        candidates.toString(),
                )
            }
        }
    }

    /** Eta's open_uri: ACTION_VIEW with scheme + resolvability validation. */
    fun openUri(uriText: String): JSONObject {
        val text = uriText.trim()
        require(text.isNotEmpty()) { "uri is required" }
        val uri = Uri.parse(text)
        require(!uri.scheme.isNullOrBlank()) { "uri has no scheme: $text" }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        @Suppress("DEPRECATION")
        val resolves = if (Build.VERSION.SDK_INT >= 33) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            pm.resolveActivity(intent, 0)
        }
        require(resolves != null) { "no app can handle uri: $text" }
        ctx.startActivity(intent)
        return JSONObject()
            .put("ok", true)
            .put("tool", "open_uri")
            .put("scheme", uri.scheme!!.lowercase(Locale.ROOT))
    }

    fun stop(packageName: String): JSONObject {
        if (!ShellExecutor.hasRoot()) throw IllegalStateException("app.stop requires root")
        val r = ShellExecutor.exec(RootCommands.forceStop(packageName), asRoot = true)
        if (!r.ok) throw IllegalStateException("force-stop failed: ${r.stderr.trim().take(200)}")
        return JSONObject().put("stopped", true)
    }
}
