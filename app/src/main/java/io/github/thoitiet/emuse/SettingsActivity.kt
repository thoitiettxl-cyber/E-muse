package io.github.thoitiet.emuse

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings screen: API key ("Truy cập") + Cloudflare Tunnel config.
 * Inputs persist in onPause; changing token/hostname while the tunnel is
 * on restarts it so the new config applies.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var apiKeyInput: EditText
    private lateinit var tunnelTokenInput: EditText
    private lateinit var tunnelHostInput: EditText
    private lateinit var tunnelSwitch: UiKit.SwitchRow
    private lateinit var tunnelUrlRow: LinearLayout
    private lateinit var tunnelUrlView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiKit.bg(this@SettingsActivity))
            setPadding(UiKit.dp(this@SettingsActivity, 16), UiKit.dp(this@SettingsActivity, 8),
                UiKit.dp(this@SettingsActivity, 16), UiKit.dp(this@SettingsActivity, 32))
        }
        UiKit.header(this, root, "Cài đặt", "Truy cập & Cloudflare Tunnel")

        // ---- section: Truy cập ----
        root.addView(UiKit.sectionTitle(this, "Truy cập"))
        val accessCard = UiKit.card(this)
        apiKeyInput = UiKit.inputRow(this, accessCard, "API key",
            "EMUSE_API_KEY — guard cho MCP endpoint", true)
        apiKeyInput.setText(prefs.apiKey)
        root.addView(accessCard)
        root.addView(UiKit.hintText(this,
            "Client gọi MCP phải gửi header EMUSE_API_KEY."))

        // ---- section: Cloudflare Tunnel ----
        root.addView(UiKit.sectionTitle(this, "Cloudflare Tunnel"))
        val tunCard = UiKit.card(this)
        tunnelSwitch = UiKit.switchRow(this, tunCard, "Tunnel", "") { checked ->
            persistInputs(applyLive = false)
            prefs.tunnelEnabled = checked
            ContextCompat.startForegroundService(
                this,
                Intent(this, MuseService::class.java)
                    .setAction(MuseService.ACTION_SET_TUNNEL)
                    .putExtra(MuseService.EXTRA_TUNNEL_ENABLED, checked),
            )
            UiKit.toast(this, if (checked) "Đang bật tunnel…" else "Đã tắt tunnel")
            refreshTunnel()
            // URL đến bất đồng bộ; refresh lại vài lần.
            lifecycleScope.launch {
                delay(5000); refreshTunnel()
                delay(10000); refreshTunnel()
                delay(15000); refreshTunnel()
            }
        }
        tunnelSwitch.setCheckedNoEvent(prefs.tunnelEnabled)
        tunCard.addView(UiKit.divider(this))
        tunnelUrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@SettingsActivity, 16),
                UiKit.dp(this@SettingsActivity, 12),
                UiKit.dp(this@SettingsActivity, 16),
                UiKit.dp(this@SettingsActivity, 12))
        }
        tunnelUrlView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(UiKit.accent)
            setTextIsSelectable(true)
            setOnClickListener {
                val url = prefs.tunnelUrl
                if (url.startsWith("http")) {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("tunnel", url))
                    UiKit.toast(this@SettingsActivity, "Đã copy URL tunnel")
                }
            }
        }
        tunnelUrlRow.addView(tunnelUrlView)
        tunCard.addView(tunnelUrlRow)
        tunCard.addView(UiKit.divider(this))
        tunnelTokenInput = UiKit.inputRow(this, tunCard, "Token",
            "Named tunnel token (trống = Quick Tunnel)", true)
        tunnelTokenInput.setText(prefs.tunnelToken)
        tunCard.addView(UiKit.divider(this))
        tunnelHostInput = UiKit.inputRow(this, tunCard, "Hostname",
            "vd mcp.example.com", false)
        tunnelHostInput.setText(prefs.tunnelHostname)
        root.addView(tunCard)
        root.addView(UiKit.hintText(this,
            "Có token + hostname cố định → URL không đổi sau mỗi lần bật."))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(UiKit.bg(this@SettingsActivity))
            addView(root)
        })
        UiKit.addBottomNav(this, 3)
        refreshTunnel()
    }

    override fun onResume() {
        super.onResume()
        refreshTunnel()
        tunnelSwitch.setCheckedNoEvent(Prefs(this).tunnelEnabled)
    }

    override fun onPause() {
        persistInputs()
        super.onPause()
    }

    private fun refreshTunnel() {
        val prefs = Prefs(this)
        tunnelSwitch.summary.text = when {
            prefs.tunnelUrl.isNotEmpty() -> prefs.tunnelUrl
            prefs.tunnelEnabled -> "Đang tạo tunnel…"
            else -> "Đã tắt"
        }
        tunnelUrlView.text = if (prefs.tunnelUrl.isNotEmpty())
            "URL: ${prefs.tunnelUrl}\n(bấm để copy)" else ""
        tunnelUrlRow.visibility =
            if (prefs.tunnelUrl.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun persistInputs(applyLive: Boolean = true) {
        val prefs = Prefs(this)
        if (::apiKeyInput.isInitialized) prefs.apiKey = apiKeyInput.text.toString().trim()
        val newToken =
            if (::tunnelTokenInput.isInitialized) tunnelTokenInput.text.toString().trim()
            else prefs.tunnelToken
        val newHost =
            if (::tunnelHostInput.isInitialized) tunnelHostInput.text.toString().trim().ifEmpty { "" }
            else prefs.tunnelHostname
        val changed = newToken != prefs.tunnelToken || newHost != prefs.tunnelHostname
        prefs.tunnelToken = newToken
        prefs.tunnelHostname = newHost
        // Token/hostname đổi khi tunnel đang chạy -> báo service restart để apply.
        // (Switch Tunnel tự gọi ACTION_SET_TUNNEL nên truyền applyLive=false
        // để khỏi restart thừa.)
        if (applyLive && changed && prefs.tunnelEnabled) {
            val intent = Intent(this, MuseService::class.java)
                .setAction(MuseService.ACTION_RESTART_TUNNEL)
            if (MuseService.running) startService(intent)
            else ContextCompat.startForegroundService(this, intent)
            UiKit.toast(this, "Đã đổi cấu hình tunnel — đang restart…")
        }
    }
}
