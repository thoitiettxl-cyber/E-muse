package io.github.thoitiet.emuse

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.github.thoitiet.emuse.exec.ScreenCapture
import io.github.thoitiet.emuse.exec.ShellExecutor

/**
 * Miuix/HyperOS-style settings screen: section titles + grouped cards with
 * large rounded corners. Rows are switch / info / action / input types.
 * Every toggle reflects the real state (fixed the stale button-label bug).
 */
class MainActivity : AppCompatActivity() {

    // ---- theme tokens (Miuix-like) ----
    private val dark: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    private val bg: Int get() = if (dark) 0xFF0D0D0D.toInt() else 0xFFF5F5F5.toInt()
    private val cardBg: Int get() = if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    private val text: Int get() = if (dark) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt()
    private val secondary: Int get() = if (dark) 0xFF9E9E9E.toInt() else 0xFF8E8E93.toInt()
    private val divider: Int get() = if (dark) 0x14FFFFFF else 0x12000000
    private val accent: Int = 0xFF0B84FF.toInt()

    private lateinit var statusCard: LinearLayout
    private lateinit var serviceSwitch: Switch
    private lateinit var overlaySwitch: Switch
    private lateinit var tunnelSwitch: Switch
    private lateinit var tunnelSummary: TextView
    private lateinit var tunnelUrlRow: LinearLayout
    private lateinit var tunnelUrlView: TextView
    private lateinit var apiKeyInput: EditText
    private lateinit var tunnelTokenInput: EditText
    private lateinit var tunnelHostInput: EditText
    private lateinit var accSummary: TextView
    private lateinit var shotSummary: TextView

    private var updatingUi = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Android 14+: MediaProjection must be created inside a foreground
            // service of type mediaProjection — hand the grant to MuseService.
            ContextCompat.startForegroundService(
                this,
                Intent(this, MuseService::class.java)
                    .setAction(MuseService.ACTION_START_PROJECTION)
                    .putExtra(MuseService.EXTRA_MP_RESULT_CODE, result.resultCode)
                    .putExtra(MuseService.EXTRA_MP_DATA, result.data),
            )
            toast("Đã gửi quyền chụp màn hình cho service")
        } else {
            toast("Chưa cấp quyền chụp màn hình")
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }

        // ---- header ----
        root.addView(TextView(this).apply {
            text = "E-Muse"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(text)
            setPadding(0, dp(24), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "On-device MCP agent"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(secondary)
            setPadding(0, 0, 0, dp(16))
        })

        // ---- status card ----
        statusCard = card()
        root.addView(statusCard)

        // ---- section: Dịch vụ ----
        root.addView(sectionTitle("Dịch vụ"))
        val svcCard = card()
        serviceSwitch = switchRow(
            svcCard, "Chạy nền",
            "Foreground service: MCP local + tunnel",
        ) { checked ->
            persistInputs()
            if (checked) {
                ContextCompat.startForegroundService(
                    this, Intent(this, MuseService::class.java)
                )
            } else {
                startService(
                    Intent(this, MuseService::class.java)
                        .setAction(MuseService.ACTION_STOP),
                )
            }
            refresh()
        }
        svcCard.addView(divider())
        overlaySwitch = switchRow(
            svcCard, "Bóng nổi",
            "Nút nổi hiển thị log lệnh",
        ) { checked ->
            if (checked && !Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName"),
                    ),
                )
                toast("Cấp quyền hiển thị rồi bật lại")
                refresh()
                return@switchRow
            }
            prefs.overlayEnabled = checked
            if (checked) FloatingOverlay.show(this) else FloatingOverlay.hide()
            refresh()
        }
        root.addView(svcCard)

        // ---- section: Truy cập ----
        root.addView(sectionTitle("Truy cập"))
        val accessCard = card()
        apiKeyInput = inputRow(accessCard, "API key", "EMUSE_API_KEY — guard cho MCP endpoint", true)
        apiKeyInput.setText(prefs.apiKey)
        root.addView(accessCard)
        root.addView(hintText("Client gọi MCP phải gửi header EMUSE_API_KEY."))

        // ---- section: Cloudflare Tunnel ----
        root.addView(sectionTitle("Cloudflare Tunnel"))
        val tunCard = card()
        tunnelSummary = TextView(this) // placeholder, replaced below
        tunnelSwitch = switchRow(tunCard, "Tunnel", "") { checked ->
            persistInputs()
            prefs.tunnelEnabled = checked
            ContextCompat.startForegroundService(
                this,
                Intent(this, MuseService::class.java)
                    .setAction(MuseService.ACTION_SET_TUNNEL)
                    .putExtra(MuseService.EXTRA_TUNNEL_ENABLED, checked),
            )
            toast(if (checked) "Đang bật tunnel…" else "Đã tắt tunnel")
            refresh()
            // URL đến bất đồng bộ; refresh lại vài lần.
            tunnelSummary.postDelayed({ refresh() }, 5000)
            tunnelSummary.postDelayed({ refresh() }, 15000)
            tunnelSummary.postDelayed({ refresh() }, 30000)
        }
        // grab the summary view of the switch row we just added
        tunnelSummary = ((tunCard.getChildAt(tunCard.childCount - 1) as LinearLayout)
            .getChildAt(0) as LinearLayout).getChildAt(1) as TextView
        tunCard.addView(divider())
        tunnelUrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        tunnelUrlView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(accent)
            setTextIsSelectable(true)
            setOnClickListener {
                val url = prefs.tunnelUrl
                if (url.startsWith("http")) {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("tunnel", url))
                    toast("Đã copy URL tunnel")
                }
            }
        }
        tunnelUrlRow.addView(tunnelUrlView)
        tunCard.addView(tunnelUrlRow)
        tunCard.addView(divider())
        tunnelTokenInput = inputRow(tunCard, "Token", "Named tunnel token (trống = Quick Tunnel)", true)
        tunnelTokenInput.setText(prefs.tunnelToken)
        tunCard.addView(divider())
        tunnelHostInput = inputRow(tunCard, "Hostname", "vd mcp.example.com", false)
        tunnelHostInput.setText(prefs.tunnelHostname)
        root.addView(tunCard)
        root.addView(hintText("Có token + hostname cố định → URL không đổi sau mỗi lần bật."))

        // ---- section: Thiết bị ----
        root.addView(sectionTitle("Thiết bị"))
        val devCard = card()
        accSummary = TextView(this) // placeholder
        actionRow(devCard, "Accessibility", "", "Mở cài đặt") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        accSummary = summaryOf(devCard)
        devCard.addView(divider())
        shotSummary = TextView(this) // placeholder
        actionRow(devCard, "Chụp màn hình", "", "Cấp quyền") {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        }
        shotSummary = summaryOf(devCard)
        root.addView(devCard)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1,
            )
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        persistInputs()
        super.onPause()
    }

    private fun persistInputs() {
        val prefs = Prefs(this)
        if (::apiKeyInput.isInitialized) prefs.apiKey = apiKeyInput.text.toString().trim()
        if (::tunnelTokenInput.isInitialized) prefs.tunnelToken = tunnelTokenInput.text.toString().trim()
        if (::tunnelHostInput.isInitialized) {
            prefs.tunnelHostname = tunnelHostInput.text.toString().trim()
                .ifEmpty { "mcp.khosihuythao.com" }
        }
    }

    // ---------- UI builders ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(secondary)
        setPadding(dp(16), dp(24), dp(16), dp(8))
    }

    private fun hintText(t: String) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(secondary)
        setPadding(dp(16), dp(6), dp(16), 0)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(cardBg)
        }
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1),
        ).apply { leftMargin = dp(16) }
        setBackgroundColor(divider)
    }

    /** Returns the Switch; the summary TextView can be found via [summaryOf]. */
    private fun switchRow(
        parent: LinearLayout,
        title: String,
        summary: String,
        onChange: (Boolean) -> Unit,
    ): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(text)
        })
        texts.addView(TextView(this).apply {
            text = summary
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(secondary)
        })
        val sw = Switch(this)
        sw.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) onChange(checked)
        }
        row.addView(texts)
        row.addView(sw)
        parent.addView(row)
        return sw
    }

    private fun summaryOf(card: LinearLayout): TextView {
        val row = card.getChildAt(card.childCount - 1) as LinearLayout
        return (row.getChildAt(0) as LinearLayout).getChildAt(1) as TextView
    }

    private fun actionRow(
        parent: LinearLayout,
        title: String,
        summary: String,
        buttonText: String,
        onClick: () -> Unit,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(text)
        })
        texts.addView(TextView(this).apply {
            text = summary
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(secondary)
        })
        row.addView(texts)
        row.addView(Button(this).apply {
            text = buttonText
            setOnClickListener { onClick() }
        })
        parent.addView(row)
    }

    private fun infoRow(parent: LinearLayout, title: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        row.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(text)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(secondary)
        })
        parent.addView(row)
    }

    private fun inputRow(parent: LinearLayout, title: String, hint: String, password: Boolean): EditText {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        col.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(text)
        })
        val et = EditText(this).apply {
            this.hint = hint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(text)
            setHintTextColor(secondary)
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = null
            setPadding(0, dp(4), 0, 0)
        }
        col.addView(et)
        parent.addView(col)
        return et
    }

    // ---------- state ----------

    private fun setSwitch(sw: Switch, checked: Boolean) {
        updatingUi = true
        sw.isChecked = checked
        updatingUi = false
    }

    private fun refresh() {
        // Root check blocks for seconds: never run it on the UI thread.
        Thread {
            val root = ShellExecutor.hasRoot()
            val prefs = Prefs(this@MainActivity)
            val acc = MuseAccessibilityService.instance != null || accessibilityOn()
            val shot = when {
                ScreenCapture.hasProjection() -> "MediaProjection"
                root -> "root screencap"
                else -> "chưa có"
            }
            runOnUiThread {
                // status card
                statusCard.removeAllViews()
                infoRow(statusCard, "Service", if (MuseService.running) "đang chạy" else "đã dừng")
                statusCard.addView(divider())
                infoRow(statusCard, "MCP local", "127.0.0.1:${prefs.mcpPort}")
                statusCard.addView(divider())
                infoRow(statusCard, "Root", if (root) "có" else "không")

                setSwitch(serviceSwitch, MuseService.running)
                setSwitch(overlaySwitch, prefs.overlayEnabled)
                setSwitch(tunnelSwitch, prefs.tunnelEnabled)
                tunnelSummary.text = when {
                    prefs.tunnelUrl.isNotEmpty() -> prefs.tunnelUrl
                    prefs.tunnelEnabled -> "Đang tạo tunnel…"
                    else -> "Đang tắt"
                }
                tunnelUrlView.text = if (prefs.tunnelUrl.isNotEmpty())
                    "URL: ${prefs.tunnelUrl}\n(bấm để copy)" else ""
                tunnelUrlRow.visibility =
                    if (prefs.tunnelUrl.isNotEmpty()) View.VISIBLE else View.GONE

                accSummary.text = if (acc) "Đã bật" else "Chưa bật"
                shotSummary.text = shot
            }
        }.start()
    }

    private fun accessibilityOn(): Boolean {
        if (MuseAccessibilityService.instance != null) return true
        val flat = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val me = ComponentName(this, MuseAccessibilityService::class.java).flattenToString()
        return flat.split(":").any { it.equals(me, ignoreCase = true) }
    }

    private fun toast(t: String) =
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
