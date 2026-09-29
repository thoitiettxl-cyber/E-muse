package io.github.thoitiet.emuse

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.github.thoitiet.emuse.exec.ScreenCapture
import io.github.thoitiet.emuse.exec.ShellExecutor
import java.util.concurrent.Executors

/**
 * Eta-style permission status list. Each row shows a status on the right
 * ("Đã bật" green / "Cần chú ý" orange); tapping a row performs the fix:
 * requests the runtime permission or opens the matching system settings
 * page. Replaces the old "tick checkboxes then grant" picker.
 */
class PermissionsActivity : AppCompatActivity() {

    private data class RuntimePermGroup(
        val title: String,
        val subtitle: String,
        val perms: Array<String>,
    )

    private class StatusRow(
        val statusView: TextView,
        val statusFn: () -> Pair<String, Boolean>, // (text, ok)
    )

    private val statusRows = mutableListOf<StatusRow>()

    /** Background executor for refresh work; root result cached at startup. */
    private val bgExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var rootCached: Boolean? = null

    companion object {
        private const val REQ_ROW_PERMS = 100
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
            UiKit.toast(this, "Đã gửi quyền chụp màn hình cho service")
        } else {
            UiKit.toast(this, "Chưa cấp quyền chụp màn hình")
        }
        updateStatuses()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        UiKit.addBottomNav(this, 2)
        bgExecutor.execute {
            val r = ShellExecutor.hasRoot()
            rootCached = r
            runOnUiThread { if (!isFinishing && !isDestroyed) updateStatuses() }
        }
        updateStatuses()
    }

    override fun onResume() {
        super.onResume()
        updateStatuses()
    }

    override fun onDestroy() {
        bgExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiKit.bg(this@PermissionsActivity))
            setPadding(UiKit.dp(this@PermissionsActivity, 16), UiKit.dp(this@PermissionsActivity, 8),
                UiKit.dp(this@PermissionsActivity, 16), UiKit.dp(this@PermissionsActivity, 32))
        }
        UiKit.header(this, root, "Quyền", "Trạng thái quyền của E-Muse")

        // ---- section: Quyền ứng dụng (runtime permissions, one row each) ----
        root.addView(UiKit.sectionTitle(this, "Quyền ứng dụng"))
        val appCard = UiKit.card(this)
        val groups = mutableListOf(
            RuntimePermGroup("Danh bạ", "Tìm danh bạ qua MCP",
                arrayOf(Manifest.permission.READ_CONTACTS)),
            RuntimePermGroup("Nhật ký cuộc gọi", "Tìm lịch sử cuộc gọi qua MCP",
                arrayOf(Manifest.permission.READ_CALL_LOG)),
            RuntimePermGroup("Tin nhắn SMS", "Tìm SMS, đọc mã OTP qua MCP",
                arrayOf(Manifest.permission.READ_SMS)),
            RuntimePermGroup("Lịch", "Tìm sự kiện lịch qua MCP",
                arrayOf(Manifest.permission.READ_CALENDAR)),
        )
        if (Build.VERSION.SDK_INT >= 33) {
            groups += RuntimePermGroup("Ảnh & video", "Tìm ảnh/video trong MediaStore",
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
            groups += RuntimePermGroup("Nhạc & ghi âm", "Tìm nhạc, file ghi âm",
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO))
            groups += RuntimePermGroup("Thông báo", "Hiện thông báo foreground service",
                arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        } else {
            groups += RuntimePermGroup("Tệp & media", "Tìm file trong bộ nhớ",
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
        groups += RuntimePermGroup("Vị trí", "Vị trí hiện tại của máy",
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION))
        for ((i, g) in groups.withIndex()) {
            statusRow(appCard, UiKit.accent, g.title, g.subtitle,
                statusFn = {
                    if (isGranted(g)) "Đã bật" to true else "Cần chú ý" to false
                },
                onTap = {
                    ActivityCompat.requestPermissions(this, g.perms, REQ_ROW_PERMS)
                })
            if (i != groups.lastIndex) appCard.addView(UiKit.divider(this))
        }
        root.addView(appCard)

        // ---- section: Quyền hệ thống đặc biệt ----
        root.addView(UiKit.sectionTitle(this, "Quyền hệ thống đặc biệt"))
        val sysCard = UiKit.card(this)
        statusRow(sysCard, UiKit.orange, "Accessibility",
            "Cần cho thao tác chạm/vuốt/gõ phím",
            statusFn = { if (accessibilityOn()) "Đã bật" to true else "Cần chú ý" to false },
            onTap = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        sysCard.addView(UiKit.divider(this))
        statusRow(sysCard, UiKit.orange, "Chụp màn hình",
            "MediaProjection, hoặc root screencap",
            statusFn = {
                val ok = ScreenCapture.hasProjection() || rootCached == true
                if (ok) "Đã bật" to true else "Cần chú ý" to false
            },
            onTap = {
                val mgr = getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(mgr.createScreenCaptureIntent())
            })
        sysCard.addView(UiKit.divider(this))
        statusRow(sysCard, UiKit.orange, "Cài đặt APK",
            "app_install cần quyền này (Install unknown apps)",
            statusFn = {
                if (packageManager.canRequestPackageInstalls()) "Đã cấp" to true
                else "Chưa cấp" to false
            },
            onTap = {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")))
            })
        sysCard.addView(UiKit.divider(this))
        statusRow(sysCard, UiKit.orange, "Truy cập dùng app",
            "Đọc app vừa mở, thời gian dùng foreground",
            statusFn = { if (usageAccessGranted()) "Đã cấp" to true else "Chưa cấp" to false },
            onTap = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
        root.addView(sysCard)

        // ---- section: Thiết bị ----
        root.addView(UiKit.sectionTitle(this, "Thiết bị"))
        val devCard = UiKit.card(this)
        statusRow(devCard, UiKit.green, "Root",
            "su qua KernelSU/Magisk",
            statusFn = {
                when (rootCached) {
                    true -> "Có" to true
                    false -> "Không" to false
                    null -> "Đang kiểm tra…" to false
                }
            },
            onTap = null) // info row only
        root.addView(devCard)

        root.addView(UiKit.hintText(this,
            "Bấm vào từng dòng để cấp quyền hoặc mở trang Cài đặt tương ứng."))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(UiKit.bg(this@PermissionsActivity))
            addView(root)
        })
    }

    /**
     * One status row: icon circle + title/subtitle + status text + chevron.
     * When [onTap] is null the row is informational (no chevron, not clickable).
     */
    private fun statusRow(
        parent: LinearLayout,
        iconColor: Int,
        title: String,
        subtitle: String,
        statusFn: () -> Pair<String, Boolean>,
        onTap: (() -> Unit)?,
    ) {
        val rowView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiKit.dp(this@PermissionsActivity, 16),
                UiKit.dp(this@PermissionsActivity, 12),
                UiKit.dp(this@PermissionsActivity, 16),
                UiKit.dp(this@PermissionsActivity, 12))
            if (onTap != null) {
                isClickable = true
                isFocusable = true
            }
        }
        rowView.addView(UiKit.circleIcon(this, title.first().uppercase(), iconColor, 40))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(UiKit.dp(this@PermissionsActivity, 12), 0,
                UiKit.dp(this@PermissionsActivity, 8), 0)
        }
        texts.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(UiKit.fgColor(this@PermissionsActivity))
        })
        texts.addView(TextView(this).apply {
            text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(UiKit.secondary(this@PermissionsActivity))
        })
        rowView.addView(texts)
        val statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        rowView.addView(statusView)
        if (onTap != null) {
            rowView.addView(TextView(this).apply {
                text = "›"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setTextColor(UiKit.secondary(this@PermissionsActivity))
                setPadding(UiKit.dp(this@PermissionsActivity, 6), 0, 0, 0)
            })
            rowView.setOnClickListener { onTap() }
        }
        parent.addView(rowView)
        statusRows += StatusRow(statusView, statusFn)
    }

    private fun updateStatuses() {
        for (r in statusRows) {
            val (text, ok) = r.statusFn()
            r.statusView.text = text
            r.statusView.setTextColor(if (ok) UiKit.green else UiKit.orange)
        }
    }

    private fun isGranted(g: RuntimePermGroup): Boolean =
        g.perms.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun usageAccessGranted(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName,
        ) == AppOpsManager.MODE_ALLOWED
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_ROW_PERMS) return
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
        UiKit.toast(this, msg)
        updateStatuses()
    }
}
