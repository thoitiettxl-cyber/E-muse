package io.github.thoitiet.emuse

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Floating "bóng nổi" bubble (Eta-style): shows what the agent is currently
 * doing on the phone. Small draggable bubble, tap to expand a compact panel
 * with the recent command log. Driven by [event]/[setConnected] from
 * MuseService and CommandDispatcher. All UI work is posted to the main thread.
 */
object FloatingOverlay {
    private val main = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val logs = ArrayDeque<String>()

    private var wm: WindowManager? = null
    private var bubble: FrameLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var logView: TextView? = null
    private var expanded = false

    private var ringColor = 0xFF9E9E9E.toInt() // gray=offline, green=connected, orange=busy
    private var busy = false
    private var connected = false

    fun show(ctx: Context) {
        main.post {
            if (bubble != null) return@post
            if (!Settings.canDrawOverlays(ctx)) return@post
            val app = ctx.applicationContext
            wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val b = buildBubble(app)
            val p = overlayParams(56.dp(app), 56.dp(app), Gravity.TOP or Gravity.START, 40, 160)
            bubble = b
            bubbleParams = p
            runCatching { wm?.addView(b, p) }
            event("Bóng nổi đã bật")
        }
    }

    fun hide() {
        main.post {
            runCatching { bubble?.let { wm?.removeView(it) } }
            runCatching { panel?.let { wm?.removeView(it) } }
            bubble = null
            orb = null
            panel = null
            wm = null
            expanded = false
            logs.clear()
            busy = false
            connected = false
            ringColor = 0xFF9E9E9E.toInt()
        }
    }

    /** Append a line to the floating log, e.g. "▶ shell.exec ls /sdcard". */
    fun event(text: String) {
        main.post {
            if (bubble == null) return@post
            val line = "${timeFmt.format(Date())} $text"
            logs.addLast(line)
            while (logs.size > 30) logs.removeFirst()
            logView?.text = logs.joinToString("\n")
            (logView?.parent as? ScrollView)?.post {
                (logView?.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
            }
            setBusy(text.startsWith("▶"))
        }
    }

    fun setConnected(connected: Boolean) {
        main.post {
            this.connected = connected
            if (!connected) busy = false
            refreshRing()
        }
    }

    // ---- state ----

    private fun setBusy(b: Boolean) {
        busy = b
        refreshRing()
    }

    private fun refreshRing() {
        ringColor = when {
            busy -> 0xFFFF9800.toInt()
            connected -> 0xFF4CAF50.toInt()
            else -> 0xFF9E9E9E.toInt()
        }
        orb?.accent = ringColor
    }

    // ---- views ----

    /** Eta-style orb: 56dp touch area, ~32dp glowing ball, halo fading out. */
    private class OrbView(ctx: Context) : View(ctx) {
        var accent: Int = 0xFF9E9E9E.toInt()
            set(v) { field = v; invalidate() }

        private fun darker(c: Int): Int {
            val f = 0.72f
            return android.graphics.Color.rgb(
                (android.graphics.Color.red(c) * f).toInt(),
                (android.graphics.Color.green(c) * f).toInt(),
                (android.graphics.Color.blue(c) * f).toInt(),
            )
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat().coerceAtLeast(1f)
            val cx = w / 2f
            val cy = w / 2f
            // halo
            val halo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.RadialGradient(
                    cx, cy, w / 2f,
                    intArrayOf(
                        android.graphics.Color.argb(110, android.graphics.Color.red(accent),
                            android.graphics.Color.green(accent), android.graphics.Color.blue(accent)),
                        android.graphics.Color.TRANSPARENT,
                    ),
                    floatArrayOf(0.35f, 1f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
            }
            canvas.drawCircle(cx, cy, w / 2f, halo)
            // ball
            val r = w * 0.3f
            val ball = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.RadialGradient(
                    cx - r * 0.3f, cy - r * 0.3f, r,
                    intArrayOf(accent, darker(accent)),
                    floatArrayOf(0f, 1f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
            }
            canvas.drawCircle(cx, cy, r, ball)
        }
    }

    private var orb: OrbView? = null

    private fun buildBubble(ctx: Context): FrameLayout {
        val o = OrbView(ctx).apply { accent = ringColor }
        orb = o
        return FrameLayout(ctx).apply {
            addView(o, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            setOnTouchListener(DragTouchListener(ctx, { wm }, { bubble }, { bubbleParams }))
            setOnClickListener { togglePanel() }
        }
    }

    private fun buildPanel(ctx: Context): LinearLayout {
        val title = TextView(ctx).apply {
            text = "E-Muse"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val close = TextView(ctx).apply {
            text = "✕"
            setTextColor(0xFF9E9E9E.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(16.dp(ctx), 8.dp(ctx), 4.dp(ctx), 8.dp(ctx))
            setOnClickListener { togglePanel() }
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp(ctx), 6.dp(ctx), 6.dp(ctx), 2.dp(ctx))
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(close)
        }
        val log = TextView(ctx).apply {
            setTextColor(0xFFB0BEC5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(14.dp(ctx), 0, 14.dp(ctx), 12.dp(ctx))
            text = logs.joinToString("\n").ifEmpty { "Chưa có lệnh nào." }
        }
        logView = log
        val scroll = ScrollView(ctx).apply { addView(log) }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16.dp(ctx).toFloat()
                setColor(0xF2212121.toInt())
                setStroke(1.dp(ctx), 0xFF4CAF50.toInt())
            }
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 220.dp(ctx)))
        }
    }

    private fun togglePanel() {
        val w = wm ?: return
        if (expanded) {
            runCatching { panel?.let { w.removeView(it) } }
            panel = null
            logView = null
            expanded = false
        } else {
            val ctx = bubble?.context ?: return
            val p = buildPanel(ctx)
            val pp = overlayParams(280.dp(ctx), WindowManager.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START, 104, 160)
            panel = p
            runCatching { w.addView(p, pp) }
            expanded = true
        }
    }

    // ---- helpers ----

    private fun overlayParams(w: Int, h: Int, gravity: Int, x: Int, y: Int) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
        }

    private fun Int.dp(ctx: Context): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, toFloat(), ctx.resources.displayMetrics).toInt()

    private class DragTouchListener(
        ctx: Context,
        private val getWm: () -> WindowManager?,
        private val getBubble: () -> FrameLayout?,
        private val getParams: () -> WindowManager.LayoutParams?,
    ) : View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var startX = 0
        private var startY = 0
        private var downRawX = 0f
        private var downRawY = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = getParams() ?: return false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x; startY = p.y
                    downRawX = e.rawX; downRawY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downRawX).toInt()
                    val dy = (e.rawY - downRawY).toInt()
                    if (dx * dx + dy * dy > touchSlop * touchSlop) moved = true
                    p.x = startX + dx
                    p.y = startY + dy
                    getWm()?.updateViewLayout(getBubble(), p)
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) return true // swallow click after drag
                }
            }
            return false
        }
    }
}
