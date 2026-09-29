package io.github.thoitiet.emuse

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
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
    private const val TAG = "FloatingOverlay"
    private val main = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val logs = ArrayDeque<String>()

    private var wm: WindowManager? = null
    private var overlayType: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private var bubble: FrameLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var badgeView: TextView? = null
    private var panel: LinearLayout? = null
    private var logView: TextView? = null
    private var expanded = false

    private var ringColor = 0xFF9E9E9E.toInt() // gray=offline, green=connected, orange=busy
    private var busy = false
    private var connected = false

    fun show(ctx: Context) {
        main.post {
            if (bubble != null) return@post
            // WindowManagerService only allows TYPE_ACCESSIBILITY_OVERLAY from an
            // accessibility service's own context — the service owns the window
            // token, and addView() from a regular app context is rejected with
            // BadTokenException ("Only accessibility services can add
            // accessibility overlays"). Eta does the same: it resolves the
            // service first and uses it as the overlay context. Fall back to
            // TYPE_APPLICATION_OVERLAY (needs the draw-over permission) when
            // the service isn't connected.
            val svc = MuseAccessibilityService.instance
            val overlayCtx = svc ?: ctx.applicationContext
            if (svc == null && !Settings.canDrawOverlays(ctx)) {
                Log.w(TAG, "show: no a11y service and canDrawOverlays=false, skip")
                return@post
            }
            val wm = overlayCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm == null) {
                Log.w(TAG, "show: no WindowManager")
                return@post
            }
            // Eta's trick: an accessibility overlay is invisible to the accessibility
            // tree (and to screenshots), so ui_snapshot never sees our own orb and
            // Pi can't tap it by mistake.
            val type = if (svc != null) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }
            val b = buildBubble(overlayCtx)
            val p = overlayParams(type, 56.dp(overlayCtx), 56.dp(overlayCtx),
                Gravity.TOP or Gravity.START, 40, 160)
            // Only mark the overlay as shown after addView actually succeeds —
            // a swallowed failure used to leave bubble != null with no window.
            val ok = runCatching { wm.addView(b, p) }
                .onFailure { Log.e(TAG, "show: addView failed (type=$type)", it) }
                .isSuccess
            if (!ok) return@post
            this.wm = wm
            overlayType = type
            bubble = b
            bubbleParams = p
            event("Bóng nổi đã bật")
        }
    }

    fun hide() {
        // onDestroy can run after the main looper is gone; posting then would
        // leak the window. Remove synchronously when already on the main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            removeViews()
        } else {
            main.post { removeViews() }
        }
    }

    private fun removeViews() {
        runCatching { bubble?.let { wm?.removeView(it) } }
        runCatching { panel?.let { wm?.removeView(it) } }
        bubble = null
        orb = null
        badgeView = null
        panel = null
        wm = null
        expanded = false
        logs.clear()
        busy = false
        connected = false
        ringColor = 0xFF9E9E9E.toInt()
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
            badgeView?.let {
                it.text = logs.size.toString()
                it.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
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
        // Log-count badge: visible feedback that the panel has content.
        val badge = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFF44336.toInt())
            }
            visibility = View.GONE
        }
        badgeView = badge
        return FrameLayout(ctx).apply {
            addView(o, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(badge, FrameLayout.LayoutParams(
                20.dp(ctx), 20.dp(ctx), Gravity.END or Gravity.TOP))
            // Single touch listener handles both drag and tap explicitly.
            // The old separate OnClickListener could be swallowed when the
            // touch listener consumed/moved the gesture, so taps never
            // expanded the panel. Now a tap is detected here directly.
            setOnTouchListener(TapDragTouchListener(
                ctx,
                { wm },
                { bubble },
                { bubbleParams },
                { v -> v.performClick(); togglePanel() },
            ))
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
                .onFailure { Log.w(TAG, "togglePanel: removeView failed", it) }
            panel = null
            logView = null
            expanded = false
        } else {
            val ctx = bubble?.context ?: return
            val p = buildPanel(ctx)
            val pp = overlayParams(overlayType, 280.dp(ctx), WindowManager.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START, 104, 160)
            // addView then force visible: a panel added while the window
            // token is mid-transition could otherwise stay invisible.
            // Only flip `expanded` when the add actually succeeds.
            val ok = runCatching {
                w.addView(p, pp)
                p.visibility = View.VISIBLE
                p.bringToFront()
            }.onFailure { Log.e(TAG, "togglePanel: addView failed (type=$overlayType)", it) }
                .isSuccess
            if (!ok) return
            panel = p
            expanded = true
        }
    }

    // ---- helpers ----

    private fun overlayParams(type: Int, w: Int, h: Int, gravity: Int, x: Int, y: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            w, h,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
        }
    }

    private fun Int.dp(ctx: Context): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, toFloat(), ctx.resources.displayMetrics).toInt()

    /**
     * Unified tap-vs-drag handler. DOWN is consumed (returns true) so the
     * gesture's MOVE/UP events keep arriving here; a tap (no move beyond
     * touch slop) fires [onTap] directly instead of relying on a separate
     * OnClickListener, which the touch handling could swallow.
     */
    private class TapDragTouchListener(
        ctx: Context,
        private val getWm: () -> WindowManager?,
        private val getBubble: () -> FrameLayout?,
        private val getParams: () -> WindowManager.LayoutParams?,
        private val onTap: (View) -> Unit,
    ) : View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var startX = 0
        private var startY = 0
        private var downRawX = 0f
        private var downRawY = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = getParams() ?: return true
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x; startY = p.y
                    downRawX = e.rawX; downRawY = e.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downRawX).toInt()
                    val dy = (e.rawY - downRawY).toInt()
                    if (dx * dx + dy * dy > touchSlop * touchSlop) moved = true
                    if (moved) {
                        p.x = startX + dx
                        p.y = startY + dy
                        getWm()?.updateViewLayout(getBubble(), p)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap(v)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return true
        }
    }
}
