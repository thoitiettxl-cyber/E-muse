package io.github.thoitiet.emuse

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = openPrefs(ctx.applicationContext)

    /** API key guarding the on-device MCP endpoint (sent as EMUSE_API_KEY header). */
    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v).apply()

    var overlayEnabled: Boolean
        get() = sp.getBoolean("overlay_enabled", false)
        set(v) = sp.edit().putBoolean("overlay_enabled", v).apply()

    /** Direct mode: expose the on-device MCP server via Cloudflare Tunnel. */
    var tunnelEnabled: Boolean
        get() = sp.getBoolean("tunnel_enabled", false)
        set(v) = sp.edit().putBoolean("tunnel_enabled", v).apply()

    /** Last public tunnel URL (quick tunnels change on every start). */
    var tunnelUrl: String
        get() = sp.getString("tunnel_url", "") ?: ""
        set(v) = sp.edit().putString("tunnel_url", v).apply()

    /** Named-tunnel token (empty = Quick Tunnel with a random URL). */
    var tunnelToken: String
        get() = sp.getString("tunnel_token", "") ?: ""
        set(v) = sp.edit().putString("tunnel_token", v).apply()

    /** Fixed public hostname for named-tunnel mode. */
    var tunnelHostname: String
        get() = sp.getString("tunnel_hostname", "") ?: ""
        set(v) = sp.edit().putString("tunnel_hostname", v).apply()

    /** SHA-256 pin of the cloudflared binary (TOFU; empty = not pinned yet). */
    var cloudflaredSha256: String
        get() = sp.getString("cloudflared_sha256", "") ?: ""
        set(v) = sp.edit().putString("cloudflared_sha256", v).apply()

    /** SHA-256 pin of the bundled CA bundle (TOFU; empty = not pinned yet). */
    var caBundleSha256: String
        get() = sp.getString("cabundle_sha256", "") ?: ""
        set(v) = sp.edit().putString("cabundle_sha256", v).apply()

    /** Local MCP server port (localhost only). */
    var mcpPort: Int
        get() = sp.getInt("mcp_port", 18789)
        set(v) = sp.edit().putInt("mcp_port", v).apply()

    /** Per-tool on/off flags for the direct endpoint (JSON object). */
    var toolFlagsJson: String
        get() = sp.getString("tool_flags", "{}") ?: "{}"
        set(v) = sp.edit().putString("tool_flags", v).apply()

    /**
     * Permission group switches (see ToolGroup). Defaults: terminal_file
     * and device_direct ON; sensitive_read and sensitive_action OFF.
     */
    fun isGroupEnabled(group: ToolGroup): Boolean =
        sp.getBoolean(group.prefKey, group.defaultEnabled)

    fun setGroupEnabled(group: ToolGroup, enabled: Boolean) =
        sp.edit().putBoolean(group.prefKey, enabled).apply()

    companion object {
        private const val TAG = "E-Muse"
        private const val FILE_PLAIN = "emuse"
        private const val FILE_SECURE = "emuse_secure"

        /**
         * Secrets (api_key, tunnel_token) live in EncryptedSharedPreferences
         * (Android Keystore-backed). A one-time migration copies existing
         * plaintext values over, then clears the old file. Falls back to the
         * plaintext file when the Keystore is unavailable.
         */
        private fun openPrefs(appCtx: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(appCtx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val secure = EncryptedSharedPreferences.create(
                    appCtx,
                    FILE_SECURE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                migrateOnce(appCtx, secure)
                secure
            } catch (e: Exception) {
                Log.w(TAG, "encrypted prefs unavailable, using plaintext: ${e.message}")
                appCtx.getSharedPreferences(FILE_PLAIN, Context.MODE_PRIVATE)
            }
        }

        private fun migrateOnce(appCtx: Context, secure: SharedPreferences) {
            val plain = appCtx.getSharedPreferences(FILE_PLAIN, Context.MODE_PRIVATE)
            if (plain.all.isEmpty()) return
            val ed = secure.edit()
            for ((k, v) in plain.all) {
                when (v) {
                    is String -> ed.putString(k, v)
                    is Boolean -> ed.putBoolean(k, v)
                    is Int -> ed.putInt(k, v)
                    is Long -> ed.putLong(k, v)
                    is Float -> ed.putFloat(k, v)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        ed.putStringSet(k, v as Set<String>)
                    }
                }
            }
            ed.apply()
            plain.edit().clear().apply()
            Log.i(TAG, "migrated prefs to encrypted storage")
        }
    }
}
