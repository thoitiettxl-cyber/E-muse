package io.github.thoitiet.emuse

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.github.thoitiet.emuse.exec.ScreenCapture
import io.github.thoitiet.emuse.exec.ShellExecutor

class MainActivity : AppCompatActivity() {
    private lateinit var urlInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var statusView: TextView
    private lateinit var tunnelUrlView: TextView
    private lateinit var tunnelBtn: Button
    private lateinit var tunnelTokenInput: EditText
    private lateinit var tunnelHostInput: EditText

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
            setPadding(48, 48, 48, 48)
        }
        fun label(t: String) = TextView(this).apply { text = t }
        fun spacer(h: Int) = TextView(this).apply { height = h }

        urlInput = EditText(this).apply {
            hint = "Worker URL (https://…/device/connect)"
            setText(prefs.workerUrl)
        }
        keyInput = EditText(this).apply {
            hint = "API key (EMUSE_API_KEY)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.apiKey)
        }
        val save = Button(this).apply {
            text = "Save"
            setOnClickListener {
                prefs.workerUrl = urlInput.text.toString().trim()
                prefs.apiKey = keyInput.text.toString().trim()
                toast("Đã lưu cấu hình")
            }
        }
        val start = Button(this).apply {
            text = "Start service"
            setOnClickListener {
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, MuseService::class.java),
                )
                refresh()
            }
        }
        val stop = Button(this).apply {
            text = "Stop service"
            setOnClickListener {
                startService(
                    Intent(this@MainActivity, MuseService::class.java)
                        .setAction(MuseService.ACTION_STOP),
                )
                refresh()
            }
        }
        val acc = Button(this).apply {
            text = "Mở cài đặt Accessibility"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val projection = Button(this).apply {
            text = "Cấp quyền chụp màn hình"
            setOnClickListener {
                val mgr = getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(mgr.createScreenCaptureIntent())
            }
        }
        val overlayBtn = Button(this).apply {
            text = "Bóng nổi: ${if (prefs.overlayEnabled) "bật" else "tắt"}"
            setOnClickListener {
                if (!prefs.overlayEnabled && !Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName"),
                        ),
                    )
                    toast("Cấp quyền hiển thị trên ứng dụng khác rồi bấm lại")
                    return@setOnClickListener
                }
                prefs.overlayEnabled = !prefs.overlayEnabled
                if (prefs.overlayEnabled) FloatingOverlay.show(this@MainActivity)
                else FloatingOverlay.hide()
                text = "Bóng nổi: ${if (prefs.overlayEnabled) "bật" else "tắt"}"
                refresh()
            }
        }
        statusView = TextView(this)
        tunnelUrlView = TextView(this).apply {
            text = ""
            setTextIsSelectable(true)
            setOnClickListener {
                val url = text.toString()
                if (url.startsWith("http")) {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("tunnel", url))
                    toast("Đã copy URL tunnel")
                }
            }
        }
        tunnelBtn = Button(this).apply {
            setOnClickListener {
                val prefs = Prefs(this@MainActivity)
                // Persist named-tunnel settings before (re)starting.
                prefs.tunnelToken = tunnelTokenInput.text.toString().trim()
                val host = tunnelHostInput.text.toString().trim()
                prefs.tunnelHostname = host.ifEmpty { "mcp.khosihuythao.com" }
                val enable = !prefs.tunnelEnabled
                // The toggle intent also (re)starts the service when needed.
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, MuseService::class.java)
                        .setAction(MuseService.ACTION_SET_TUNNEL)
                        .putExtra(MuseService.EXTRA_TUNNEL_ENABLED, enable),
                )
                toast(if (enable) "Đang bật tunnel (tải cloudflared lần đầu ~35MB)…" else "Đã tắt tunnel")
                // The public URL arrives asynchronously; poll the status view.
                tunnelUrlView.postDelayed({ refresh() }, 5000)
                tunnelUrlView.postDelayed({ refresh() }, 15000)
                tunnelUrlView.postDelayed({ refresh() }, 30000)
                refresh()
            }
        }

        root.addView(label("Worker URL"))
        root.addView(urlInput)
        root.addView(spacer(16))
        root.addView(label("API key"))
        root.addView(keyInput)
        root.addView(spacer(16))
        root.addView(save)
        root.addView(start)
        root.addView(stop)
        root.addView(acc)
        root.addView(projection)
        root.addView(overlayBtn)
        root.addView(spacer(24))
        root.addView(label("Cloudflare Tunnel (truy cập trực tiếp)"))
        tunnelTokenInput = EditText(this).apply {
            hint = "Named tunnel token (để trống = Quick Tunnel)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.tunnelToken)
        }
        tunnelHostInput = EditText(this).apply {
            hint = "Hostname cố định (vd mcp.khosihuythao.com)"
            setText(prefs.tunnelHostname)
        }
        root.addView(tunnelTokenInput)
        root.addView(tunnelHostInput)
        root.addView(tunnelBtn)
        root.addView(tunnelUrlView)
        root.addView(spacer(24))
        root.addView(label("Trạng thái:"))
        root.addView(statusView)

        setContentView(ScrollView(this).apply { addView(root) })

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

    private fun refresh() {
        // Root check blocks for seconds: never run it on the UI thread.
        Thread {
            val root = ShellExecutor.hasRoot()
            val acc = MuseAccessibilityService.instance != null || accessibilityOn()
            val prefs = Prefs(this@MainActivity)
            runOnUiThread {
                statusView.text = buildString {
                    appendLine("Service: ${if (MuseService.running) "đang chạy" else "đã dừng"}")
                    appendLine("Root: ${if (root) "có" else "không"}")
                    appendLine("Accessibility: ${if (acc) "đã bật" else "chưa bật"}")
                    appendLine("Chụp màn hình: ${if (ScreenCapture.hasProjection()) "MediaProjection" else if (root) "root screencap" else "chưa có"}")
                    appendLine("Bóng nổi: ${if (prefs.overlayEnabled) "bật" else "tắt"}")
                    appendLine("MCP direct: 127.0.0.1:${prefs.mcpPort} ${if (MuseService.running) "(sẵn sàng)" else ""}")
                }
                tunnelBtn.text = "Tunnel: ${if (prefs.tunnelEnabled) "tắt" else "bật"}"
                tunnelUrlView.text = when {
                    prefs.tunnelUrl.isNotEmpty() -> "URL: ${prefs.tunnelUrl}\n(bấm để copy)"
                    prefs.tunnelEnabled -> "Đang tạo tunnel…"
                    else -> "Tunnel đang tắt"
                }
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

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
