package io.github.thoitiet.emuse

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("emuse", Context.MODE_PRIVATE)

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

    /**
     * SHA-256 pin của cloudflared binary — trust-on-first-use: lần đầu gặp
     * binary (sau khi Boss xác nhận file đúng) thì lưu hash; các lần sau
     * hash khác -> TunnelManager fail-closed, từ chối chạy. Xóa file
     * cloudflared trong app filesDir (hoặc clear app data) để pin lại.
     */
    var cloudflaredSha256: String
        get() = sp.getString("cloudflared_sha256", "") ?: ""
        set(v) = sp.edit().putString("cloudflared_sha256", v).apply()

    /**
     * SHA-256 pin của CA bundle (cacert.pem). Re-pin mỗi lần tải lại vì
     * file xoay vòng theo curl.se — khác với binary (fail-closed).
     */
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
}
