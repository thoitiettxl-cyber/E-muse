package io.github.thoitiet.emuse

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.thoitiet.emuse.exec.ShellExecutor
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Slim home screen (Miuix/HyperOS style, plain Views): status card
 * (Service / MCP local / Root / Tunnel URL), three quick toggles
 * (Chạy nền, Bóng nổi, Tunnel) and navigation cards to Tools / Quyền /
 * Cài đặt. All shared builders live in UiKit.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusCard: LinearLayout
    private lateinit var serviceSwitch: UiKit.SwitchRow
    private lateinit var overlaySwitch: UiKit.SwitchRow
    private lateinit var tunnelSwitch: UiKit.SwitchRow
    private lateinit var tunnelUrlRow: LinearLayout
    private lateinit var tunnelUrlView: TextView

    /** Background executor for all refresh() work (single thread, no churn). */
    private val bgExecutor = Executors.newSingleThreadExecutor()
    /** hasRoot() result cached once at startup; refresh() never blocks on it. */
    @Volatile
    private var rootCached: Boolean? = null

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiKit.bg(this@MainActivity))
            setPadding(UiKit.dp(this@MainActivity, 16), UiKit.dp(this@MainActivity, 8),
                UiKit.dp(this@MainActivity, 16), UiKit.dp(this@MainActivity, 32))
        }
        UiKit.header(this, root, "E-Muse", "On-device MCP agent")

        // ---- status card ----
        statusCard = UiKit.card(this)
        root.addView(statusCard)

        // ---- section: Dịch vụ ----
        root.addView(UiKit.sectionTitle(this, "Dịch vụ"))
        val svcCard = UiKit.card(this)
        serviceSwitch = UiKit.switchRow(this, svcCard, "Chạy nền",
            "Foreground service: MCP local + tunnel") { checked ->
            if (checked) {
                ContextCompat.startForegroundService(
                    this, Intent(this, MuseService::class.java),
                )
            } else {
                startService(
                    Intent(this, MuseService::class.java)
                        .setAction(MuseService.ACTION_STOP),
                )
            }
            refresh()
        }
        svcCard.addView(UiKit.divider(this))
        overlaySwitch = UiKit.switchRow(this, svcCard, "Bóng nổi",
            "Nút nổi hiển thị log lệnh") { checked ->
            if (checked && !Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
                UiKit.toast(this, "Cấp quyền hiển thị rồi bật lại")
                refresh()
                return@switchRow
            }
            prefs.overlayEnabled = checked
            if (checked) FloatingOverlay.show(this) else FloatingOverlay.hide()
            refresh()
        }
        svcCard.addView(UiKit.divider(this))
        tunnelSwitch = UiKit.switchRow(this, svcCard, "Tunnel", "") { checked ->
            prefs.tunnelEnabled = checked
            ContextCompat.startForegroundService(
                this,
                Intent(this, MuseService::class.java)
                    .setAction(MuseService.ACTION_SET_TUNNEL)
                    .putExtra(MuseService.EXTRA_TUNNEL_ENABLED, checked),
            )
            UiKit.toast(this, if (checked) "Đang bật tunnel…" else "Đã tắt tunnel")
            refresh()
            // URL đến bất đồng bộ; refresh lại vài lần.
            lifecycleScope.launch {
                delay(5000); refresh()
                delay(10000); refresh()
                delay(15000); refresh()
            }
        }
        svcCard.addView(UiKit.divider(this))
        tunnelUrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@MainActivity, 16), UiKit.dp(this@MainActivity, 12),
                UiKit.dp(this@MainActivity, 16), UiKit.dp(this@MainActivity, 12))
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
                    UiKit.toast(this@MainActivity, "Đã copy URL tunnel")
                }
            }
        }
        tunnelUrlRow.addView(tunnelUrlView)
        svcCard.addView(tunnelUrlRow)
        root.addView(svcCard)

        // ---- section: Khám phá ----
        root.addView(UiKit.sectionTitle(this, "Khám phá"))
        UiKit.navCard(this, root, "⚙", UiKit.accent, "Tools",
            "Danh sách MCP tools theo nhóm") {
            startActivity(Intent(this, ToolsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            })
        }
        UiKit.navCard(this, root, "🛡", UiKit.orange, "Quyền",
            "Trạng thái quyền ứng dụng & hệ thống") {
            startActivity(Intent(this, PermissionsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            })
        }
        UiKit.navCard(this, root, "☰", UiKit.green, "Cài đặt",
            "API key & Cloudflare Tunnel") {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            })
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(UiKit.bg(this@MainActivity))
            addView(root)
        })
        UiKit.addBottomNav(this, 0)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_POST_NOTIFICATIONS,
            )
        }
        // hasRoot() takes seconds: compute once in the background; refresh()
        // reuses the cached value so toggles never show fake-ON state.
        bgExecutor.execute {
            val r = ShellExecutor.hasRoot()
            rootCached = r
            runOnUiThread { if (!isFinishing && !isDestroyed) refresh() }
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        bgExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIFICATIONS) refresh()
    }

    private fun refresh() {
        // All work runs on the shared single-thread executor (no thread churn);
        // hasRoot() is cached from onCreate so refresh() never blocks toggles.
        bgExecutor.execute {
            val root = rootCached ?: false
            val prefs = Prefs(this@MainActivity)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                // status card
                statusCard.removeAllViews()
                UiKit.infoRow(this@MainActivity, statusCard, "Service",
                    if (MuseService.running) "đang chạy" else "đã dừng")
                statusCard.addView(UiKit.divider(this@MainActivity))
                UiKit.infoRow(this@MainActivity, statusCard, "MCP local",
                    "127.0.0.1:${prefs.mcpPort}")
                statusCard.addView(UiKit.divider(this@MainActivity))
                UiKit.infoRow(this@MainActivity, statusCard, "Root",
                    if (root) "có" else "không")

                serviceSwitch.setCheckedNoEvent(MuseService.running)
                overlaySwitch.setCheckedNoEvent(prefs.overlayEnabled)
                tunnelSwitch.setCheckedNoEvent(prefs.tunnelEnabled)
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
        }
    }
}
