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
import android.widget.CheckBox
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
import androidx.lifecycle.lifecycleScope
import io.github.thoitiet.emuse.exec.ScreenCapture
import io.github.thoitiet.emuse.exec.ShellExecutor
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    private val fgColor: Int get() = if (dark) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt()
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
    private lateinit var installSummary: TextView
    private val groupSwitches = mutableMapOf<ToolGroup, Switch>()
    private val permRows = mutableListOf<PermRow>()
    private lateinit var usageSummary: TextView

    /** Background executor for all refresh() work (single thread, no churn). */
    private val bg = Executors.newSingleThreadExecutor()
    /** hasRoot() result cached once at startup; refresh() never blocks on it. */
    @Volatile
    private var rootCached: Boolean? = null

    private var updatingUi = false

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 1
        private const val REQ_RUNTIME_PERMS = 2
        private const val KEY_PERM_CHECKS = "perm_checks"
    }

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
            setTextColor(fgColor)
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
        }.first
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
        }.first
        root.addView(svcCard)

        // ---- section: Truy cập ----
        root.addView(sectionTitle("Truy cập"))
        val accessCard = card()
        apiKeyInput = inputRow(accessCard, "API key", "EMUSE_API_KEY — guard cho MCP endpoint", true)
        apiKeyInput.setText(prefs.apiKey)
        root.addView(accessCard)
        root.addView(hintText("Client gọi MCP phải gửi header EMUSE_API_KEY."))

        // ---- section: Quyền tool ----
        root.addView(sectionTitle("Quyền tool"))
        val permCard = card()
        for (g in ToolGroup.entries) {
            val sw = switchRow(permCard, g.title, g.summary) { checked ->
                prefs.setGroupEnabled(g, checked)
                toast(if (checked) "Đã bật: ${g.title}" else "Đã tắt: ${g.title}")
                refresh()
            }.first
            groupSwitches[g] = sw
            if (g != ToolGroup.entries.last()) permCard.addView(divider())
        }
        root.addView(permCard)
        root.addView(hintText("Tool bị chặn khi nhóm của nó tắt — kể cả khi tool_flags đang bật."))

        // ---- section: Quyền Android (chọn rồi cấp, kiểu Eta) ----
        root.addView(sectionTitle("Quyền Android"))
        val runPermCard = card()
        val perms = mutableListOf(
            RuntimePerm("Danh bạ", arrayOf(Manifest.permission.READ_CONTACTS)),
            RuntimePerm("Nhật ký cuộc gọi", arrayOf(Manifest.permission.READ_CALL_LOG)),
            RuntimePerm("Tin nhắn SMS", arrayOf(Manifest.permission.READ_SMS)),
            RuntimePerm("Lịch", arrayOf(Manifest.permission.READ_CALENDAR)),
        )
        if (Build.VERSION.SDK_INT >= 33) {
            perms += RuntimePerm(
                "Ảnh & video",
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO),
            )
            perms += RuntimePerm(
                "Nhạc & ghi âm",
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO),
            )
            perms += RuntimePerm(
                "Thông báo",
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            )
        } else {
            perms += RuntimePerm(
                "Tệp & media",
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            )
        }
        perms += RuntimePerm(
            "Vị trí",
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
        for (item in perms) {
            permRows += checkRow(runPermCard, item.title, item)
            runPermCard.addView(divider())
        }
        // Rotation: restore the checkbox ticks (EditTexts survive via Prefs).
        savedInstanceState?.getBooleanArray(KEY_PERM_CHECKS)?.let { saved ->
            permRows.forEachIndexed { i, r ->
                if (i < saved.size) setCheck(r.box, saved[i])
            }
        }
        usageSummary = actionRow(runPermCard, "Truy cập dùng app", "", "Mở cài đặt") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }.second
        runPermCard.addView(divider())
        installSummary = actionRow(
            runPermCard,
            "Cài đặt APK",
            "app_install cần quyền này (Install unknown apps)",
            "Mở cài đặt",
        ) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:$packageName"),
                ),
            )
        }.second
        root.addView(runPermCard)
        val grantBtn = Button(this).apply {
            text = "Cấp quyền đã chọn"
            setOnClickListener { grantSelectedPerms() }
        }
        root.addView(LinearLayout(this).apply {
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(
                grantBtn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        })
        root.addView(hintText("Tick chọn các quyền cần, bấm nút để cấp một lần. Quyền hệ thống đặc biệt (dùng app) mở trang Cài đặt tương ứng."))

        // ---- section: Cloudflare Tunnel ----
        root.addView(sectionTitle("Cloudflare Tunnel"))
        val tunCard = card()
        tunnelSummary = TextView(this) // placeholder, replaced below
        val tunPair = switchRow(tunCard, "Tunnel", "") { checked ->
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
            // lifecycleScope: cancelled on destroy, never touches the old activity.
            lifecycleScope.launch {
                delay(5000); refresh()
                delay(10000); refresh()
                delay(15000); refresh()
            }
        }
        tunnelSwitch = tunPair.first
        tunnelSummary = tunPair.second
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
        accSummary = actionRow(devCard, "Accessibility", "", "Mở cài đặt") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.second
        devCard.addView(divider())
        shotSummary = actionRow(devCard, "Chụp màn hình", "", "Cấp quyền") {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        }.second
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
                REQ_POST_NOTIFICATIONS,
            )
        }
        // hasRoot() takes seconds: compute once in the background; refresh()
        // reuses the cached value so toggles never show fake-ON state.
        bg.execute {
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

    override fun onPause() {
        persistInputs()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBooleanArray(KEY_PERM_CHECKS, permRows.map { it.box.isChecked }.toBooleanArray())
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    private fun persistInputs() {
        val prefs = Prefs(this)
        if (::apiKeyInput.isInitialized) prefs.apiKey = apiKeyInput.text.toString().trim()
        if (::tunnelTokenInput.isInitialized) prefs.tunnelToken = tunnelTokenInput.text.toString().trim()
        if (::tunnelHostInput.isInitialized) {
            prefs.tunnelHostname = tunnelHostInput.text.toString().trim()
                .ifEmpty { "" }
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

    /** Returns Pair(Switch, summary TextView) — no index-based lookup needed. */
    private fun switchRow(
        parent: LinearLayout,
        title: String,
        summary: String,
        onChange: (Boolean) -> Unit,
    ): Pair<Switch, TextView> {
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
            setTextColor(fgColor)
        })
        val summaryView = TextView(this).apply {
            text = summary
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(secondary)
        }
        texts.addView(summaryView)
        val sw = Switch(this)
        sw.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) onChange(checked)
        }
        row.addView(texts)
        row.addView(sw)
        parent.addView(row)
        return sw to summaryView
    }

    /** Row for the "chọn rồi cấp" runtime-permission picker: checkbox + title + status. */
    private fun checkRow(parent: LinearLayout, title: String, item: RuntimePerm): PermRow {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val box = CheckBox(this)
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(fgColor)
        })
        val status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(secondary)
        }
        texts.addView(status)
        row.addView(box)
        row.addView(texts)
        parent.addView(row)
        return PermRow(box, status, item)
    }

    /** Returns Pair(Button, summary TextView). */
    private fun actionRow(
        parent: LinearLayout,
        title: String,
        summary: String,
        buttonText: String,
        onClick: () -> Unit,
    ): Pair<Button, TextView> {
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
            setTextColor(fgColor)
        })
        val summaryView = TextView(this).apply {
            text = summary
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(secondary)
        }
        texts.addView(summaryView)
        row.addView(texts)
        val btn = Button(this).apply {
            text = buttonText
            setOnClickListener { onClick() }
        }
        row.addView(btn)
        parent.addView(row)
        return btn to summaryView
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
            setTextColor(fgColor)
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
            setTextColor(fgColor)
        })
        val et = EditText(this).apply {
            this.hint = hint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(fgColor)
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

    private data class RuntimePerm(val title: String, val perms: Array<String>)
    private data class PermRow(val box: CheckBox, val status: TextView, val item: RuntimePerm)

    private fun isPermGranted(item: RuntimePerm): Boolean =
        item.perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun usageAccessGranted(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        return appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName,
        ) == android.app.AppOpsManager.MODE_ALLOWED
    }

    /** Eta-style "chọn rồi cấp": grant every checked-but-not-granted permission at once. */
    private fun grantSelectedPerms() {
        val wanted = permRows
            .filter { it.box.isChecked && !isPermGranted(it.item) }
            .flatMap { it.item.perms.toList() }
            .distinct()
            .toTypedArray()
        if (wanted.isEmpty()) {
            toast("Không có quyền nào cần cấp (đã đủ hoặc chưa tick chọn)")
            return
        }
        ActivityCompat.requestPermissions(this, wanted, REQ_RUNTIME_PERMS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            refresh()
            return
        }
        if (requestCode == REQ_RUNTIME_PERMS) {
            val denied = permissions.indices
                .filter { grantResults[it] != PackageManager.PERMISSION_GRANTED }
                .map { permissions[it] }
            val granted = permissions.size - denied.size
            // "Don't ask again": the next tap would deny silently with no
            // dialog — tell the user to grant manually in Settings instead.
            val neverAsk = denied.filter {
                !ActivityCompat.shouldShowRequestPermissionRationale(this, it)
            }
            val msg = buildString {
                append("Đã cấp $granted/${permissions.size} quyền")
                if (neverAsk.isNotEmpty()) {
                    append(". ${neverAsk.size} quyền bị từ chối vĩnh viễn " +
                        "(Don't ask again): mở Cài đặt > Ứng dụng > E-Muse > " +
                        "Quyền để cấp thủ công")
                }
            }
            toast(msg)
            refresh()
        }
    }

    private fun canRequestInstall(): Boolean = packageManager.canRequestPackageInstalls()

    private fun setCheck(box: CheckBox, checked: Boolean) {
        updatingUi = true
        box.isChecked = checked
        updatingUi = false
    }

    private fun setSwitch(sw: Switch, checked: Boolean) {
        updatingUi = true
        sw.isChecked = checked
        updatingUi = false
    }

    private fun refresh() {
        // All work runs on the shared single-thread executor (no thread churn);
        // hasRoot() is cached from onCreate so refresh() never blocks toggles.
        bg.execute {
            val root = rootCached ?: false
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
                for ((g, sw) in groupSwitches) setSwitch(sw, prefs.isGroupEnabled(g))
                tunnelSummary.text = when {
                    prefs.tunnelUrl.isNotEmpty() -> prefs.tunnelUrl
                    prefs.tunnelEnabled -> "Đang tạo tunnel…"
                    else -> "Đã tắt"
                }
                tunnelUrlView.text = if (prefs.tunnelUrl.isNotEmpty())
                    "URL: ${prefs.tunnelUrl}\n(bấm để copy)" else ""
                tunnelUrlRow.visibility =
                    if (prefs.tunnelUrl.isNotEmpty()) View.VISIBLE else View.GONE

                accSummary.text = if (acc) "Đã bật" else "Chưa bật"
                shotSummary.text = shot
                installSummary.text = if (canRequestInstall()) "Đã cấp" else "Chưa cấp"

                // Android runtime permissions (chọn rồi cấp)
                for (r in permRows) {
                    val g = isPermGranted(r.item)
                    r.status.text = if (g) "Đã cấp" else "Chưa cấp"
                    if (g) setCheck(r.box, false)
                }
                usageSummary.text = if (usageAccessGranted()) "Đã cấp" else "Chưa cấp"
            }
        }
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
