package io.github.thoitiet.emuse

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import io.github.thoitiet.emuse.exec.ScreenCapture
import io.github.thoitiet.emuse.mcp.LocalHttpServer
import io.github.thoitiet.emuse.mcp.McpHandler
import io.github.thoitiet.emuse.mcp.TunnelManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MuseService : Service() {
    companion object {
        const val ACTION_STOP = "io.github.thoitiet.emuse.ACTION_STOP"
        const val ACTION_START_PROJECTION = "io.github.thoitiet.emuse.ACTION_START_PROJECTION"
        const val ACTION_SET_TUNNEL = "io.github.thoitiet.emuse.ACTION_SET_TUNNEL"
        const val ACTION_RESTART_TUNNEL = "io.github.thoitiet.emuse.ACTION_RESTART_TUNNEL"
        const val EXTRA_TUNNEL_ENABLED = "tunnel_enabled"
        const val EXTRA_MP_RESULT_CODE = "mp_result_code"
        const val EXTRA_MP_DATA = "mp_data"

        @Volatile
        var running: Boolean = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dispatcher: CommandDispatcher
    private var localServer: LocalHttpServer? = null
    private var tunnel: TunnelManager? = null
    private var fgTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundX(buildNotification("E-Muse đang khởi động…"), fgTypes)
        dispatcher = CommandDispatcher(this, FloatingOverlay::event)
        running = true
        if (Prefs(this).overlayEnabled) FloatingOverlay.show(this)
        startDirectEndpoint()
    }

    /**
     * Direct mode: serve the MCP endpoint on localhost and (optionally)
     * expose it publicly via Cloudflare Tunnel (Quick or named).
     */
    private fun startDirectEndpoint() {
        val prefs = Prefs(this)
        val handler = McpHandler(dispatcher, prefs) { directDeviceJson() }
        val port = prefs.mcpPort
        var bindOk = false
        try {
            localServer = LocalHttpServer(port) { method, path, headers, body ->
                val r = handler.handleHttp(method, path, headers, body)
                LocalHttpServer.Response(
                    status = r.status,
                    body = r.json?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0),
                    headers = r.headers,
                )
            }.also { it.start() }
            bindOk = true
            FloatingOverlay.event("MCP direct: 127.0.0.1:$port")
        } catch (e: Exception) {
            FloatingOverlay.event("MCP direct lỗi: ${e.message?.take(60)}")
        }
        val tm = TunnelManager(this, scope)
        tunnel = tm
        tm.onState = { s ->
            when (s) {
                is TunnelManager.State.Running -> {
                    prefs.tunnelUrl = s.url
                    updateNotification("E-Muse: direct ${s.url}")
                    FloatingOverlay.setConnected(true)
                    FloatingOverlay.event("Tunnel: ${s.url}")
                }
                is TunnelManager.State.Failed -> {
                    updateNotification("E-Muse: tunnel lỗi")
                    FloatingOverlay.setConnected(false)
                    FloatingOverlay.event("Tunnel lỗi: ${s.reason.take(60)}")
                }
                is TunnelManager.State.Downloading ->
                    FloatingOverlay.event("Đang tải cloudflared…")
                else -> {}
            }
        }
        if (prefs.tunnelEnabled) {
            if (!bindOk) {
                // Never point a public tunnel at a dead local server.
                tm.failNow("MCP local bind thất bại — không start tunnel")
            } else {
                tm.start(
                    "http://127.0.0.1:$port",
                    prefs.tunnelToken,
                    prefs.tunnelHostname,
                ) { done, total ->
                    if (total > 0) FloatingOverlay.event("Tải cloudflared ${(done * 100 / total)}%")
                }
            }
        }
    }

    private fun stopDirectEndpoint() {
        runCatching { tunnel?.stop() }
        tunnel = null
        runCatching { localServer?.stop() }
        localServer = null
    }

    /** Toggle the tunnel at runtime (called from MainActivity). */
    fun setTunnelEnabled(enabled: Boolean) {
        val prefs = Prefs(this)
        prefs.tunnelEnabled = enabled
        val tm = tunnel ?: return
        if (enabled) {
            tm.start(
                "http://127.0.0.1:${prefs.mcpPort}",
                prefs.tunnelToken,
                prefs.tunnelHostname,
            ) { _, _ -> }
        } else {
            tm.stop()
            prefs.tunnelUrl = ""
            FloatingOverlay.setConnected(false)
            updateNotification("E-Muse: đang chạy (direct local)")
        }
    }

    private fun directDeviceJson(): JSONObject {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        return JSONObject()
            .put("deviceId", deviceId)
            .put("deviceName", Build.MODEL ?: "android")
            .put("connectedAt", iso)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SET_TUNNEL) {
            setTunnelEnabled(intent.getBooleanExtra(EXTRA_TUNNEL_ENABLED, false))
            return START_STICKY
        }
        if (intent?.action == ACTION_RESTART_TUNNEL) {
            // Token/hostname changed while running: restart tunnel with current prefs.
            setTunnelEnabled(Prefs(this).tunnelEnabled)
            return START_STICKY
        }
        if (intent?.action == ACTION_START_PROJECTION) {
            startProjection(intent)
        }
        return START_STICKY
    }

    /**
     * Android 14+: MediaProjection may only be created while a foreground
     * service of type mediaProjection is running, so the grant is handed to
     * the service instead of being consumed in the activity.
     */
    private fun startProjection(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_MP_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_MP_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) return
        fgTypes = fgTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        startForegroundX(buildNotification("E-Muse đang chạy"), fgTypes)
        runCatching {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            val projection = mgr.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection null")
            ScreenCapture.setProjection(projection)
            updateNotification("E-Muse: đã cấp quyền chụp màn hình")
        }.onFailure {
            updateNotification("E-Muse: cấp quyền chụp màn hình thất bại")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        FloatingOverlay.hide()
        ScreenCapture.release()
        if (::dispatcher.isInitialized) runCatching { dispatcher.shutdown() }
        stopDirectEndpoint()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel("emuse", "E-Muse", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, "emuse")
            .setContentTitle("E-Muse")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

    private fun startForegroundX(n: Notification, types: Int) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, types)
        } else {
            @Suppress("DEPRECATION")
            startForeground(1, n)
        }
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(1, buildNotification(text))
    }
}
