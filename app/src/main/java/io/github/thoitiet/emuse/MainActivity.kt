package io.github.thoitiet.emuse

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.github.thoitiet.emuse.exec.ShellExecutor

class MainActivity : AppCompatActivity() {
    private lateinit var urlInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var statusView: TextView

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
        statusView = TextView(this)

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
        statusView.text = buildString {
            appendLine("Service: ${if (MuseService.running) "đang chạy" else "đã dừng"}")
            appendLine("Root: ${if (ShellExecutor.hasRoot()) "có" else "không"}")
            appendLine("Accessibility: ${if (accessibilityOn()) "đã bật" else "chưa bật"}")
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

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
