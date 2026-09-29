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
import io.github.thoitiet.emuse.exec.ScreenCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

class MuseService : Service() {
    companion object {
        const val ACTION_STOP = "io.github.thoitiet.emuse.ACTION_STOP"
        const val ACTION_START_PROJECTION = "io.github.thoitiet.emuse.ACTION_START_PROJECTION"
        const val EXTRA_MP_RESULT_CODE = "mp_result_code"
        const val EXTRA_MP_DATA = "mp_data"

        @Volatile
        var running: Boolean = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var client: OkHttpClient
    private lateinit var dispatcher: CommandDispatcher
    private var ws: WebSocket? = null
    private var reconnectDelayMs = 5000L
    private var connectGen = 0
    private var socketOpen = false
    private var fgTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundX(buildNotification("E-Muse đang khởi động…"), fgTypes)
        dispatcher = CommandDispatcher(this, ::sendResult, FloatingOverlay::event)
        client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        running = true
        if (Prefs(this).overlayEnabled) FloatingOverlay.show(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START_PROJECTION) {
            startProjection(intent)
        }
        connect()
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
            ScreenCapture.setProjection(mgr.getMediaProjection(resultCode, data))
            updateNotification("E-Muse: đã cấp quyền chụp màn hình")
        }.onFailure {
            updateNotification("E-Muse: cấp quyền chụp màn hình thất bại")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        FloatingOverlay.hide()
        runCatching { dispatcher.shutdown() }
        scope.cancel()
        try {
            ws?.close(1000, "service stopped")
        } catch (_: Exception) {
        }
        try {
            client.dispatcher.executorService.shutdown()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun connect() {
        val prefs = Prefs(this)
        val httpUrl = prefs.workerUrl.trim().trimEnd('/')
        val token = prefs.apiKey.trim()
        if (httpUrl.isEmpty() || token.isEmpty()) {
            updateNotification("E-Muse: chưa cấu hình URL / API key")
            return
        }
        if (socketOpen && ws != null) return // already connected
        // https:// -> wss://, http:// -> ws://
        val wsUrl = httpUrl.replaceFirst("^http".toRegex(), "ws")
        updateNotification("E-Muse: đang kết nối…")
        // Generation guard: cancelling the old socket fires its onFailure, whose
        // callbacks must be ignored so they can't schedule phantom reconnects
        // that would kill the healthy new socket (reconnect flap loop).
        val gen = ++connectGen
        runCatching { ws?.cancel() }
        ws = null
        socketOpen = false
        val request = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(request, SocketListener(gen))
    }

    private inner class SocketListener(private val gen: Int) : WebSocketListener() {
        private fun alive() = gen == connectGen

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!alive()) {
                runCatching { webSocket.cancel() }
                return
            }
            reconnectDelayMs = 5000L
            socketOpen = true
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
            val hello = JSONObject()
                .put("type", "hello")
                .put("deviceId", deviceId)
                .put("deviceName", Build.MODEL ?: "android")
                .put("token", Prefs(this@MuseService).apiKey.trim())
            webSocket.send(hello.toString())
            updateNotification("E-Muse: đã kết nối • $deviceId")
            FloatingOverlay.setConnected(true)
            FloatingOverlay.event("Đã kết nối Worker")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!alive()) return
            try {
                val o = JSONObject(text)
                if (o.has("cmd")) dispatcher.dispatch(DeviceCommand.fromJson(o))
            } catch (_: Exception) {
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!alive()) return
            socketOpen = false
            FloatingOverlay.setConnected(false)
            scheduleReconnect(gen)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!alive()) return
            socketOpen = false
            updateNotification("E-Muse: mất kết nối, thử lại…")
            FloatingOverlay.setConnected(false)
            FloatingOverlay.event("Mất kết nối: ${t.message?.take(40)}")
            scheduleReconnect(gen)
        }
    }

    private fun scheduleReconnect(gen: Int) {
        if (!running) return
        scope.launch {
            delay(reconnectDelayMs)
            reconnectDelayMs = min(reconnectDelayMs * 2, 60_000L)
            if (running && gen == connectGen) connect()
        }
    }

    private fun sendResult(result: DeviceResult) {
        try {
            ws?.send(result.toJson().toString())
        } catch (_: Exception) {
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel("emuse", "E-Muse", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, "emuse")
            .setContentTitle("E-Muse")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
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
