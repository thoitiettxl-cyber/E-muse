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

    /** Local MCP server port (localhost only). */
    var mcpPort: Int
        get() = sp.getInt("mcp_port", 18789)
        set(v) = sp.edit().putInt("mcp_port", v).apply()

    /** Per-tool on/off flags for the direct endpoint (JSON object). */
    var toolFlagsJson: String
        get() = sp.getString("tool_flags", "{}") ?: "{}"
        set(v) = sp.edit().putString("tool_flags", v).apply()
}
