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
 * doing on the phone. Draggable bubble, tap to expand a panel with the
 * recent command log. Driven by [event]/[setConnected] from MuseService and
 * CommandDispatcher. All UI work is posted to the main thread.
 */
object FloatingOverlay {
    private val main = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val logs = ArrayDeque<String>()

    private var wm: WindowManager? = null
    private var bubble: FrameLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var logView: TextView? = null
    private var dot: View? = null
    private var statusView: TextView? = null
    private var expanded = false

    fun show(ctx: Context) {
        main.post {
            if (bubble != null) return@post
            if (!Settings.canDrawOverlays(ctx)) return@post
            val app = ctx.applicationContext
            wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val b = buildBubble(app)
            val p = overlayParams(72.dp(app), 72.dp(app), Gravity.TOP or Gravity.START, 40, 160)
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
            panel = null
            wm = null
            expanded = false
            logs.clear()
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
            statusView?.text = text.take(48)
            setBusy(text.startsWith("▶"))
            (logView?.parent as? ScrollView)?.post {
                (logView?.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    fun setConnected(connected: Boolean) {
        main.post {
            if (bubble == null) return@post
            dot?.background = dotDrawable(if (connected) 0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt())
            if (!connected) setBusy(false)
        }
    }

    // ---- views ----

    private fun buildBubble(ctx: Context): FrameLayout {
        val d = dotDrawable(0xFF9E9E9E.toInt())
        val dotView = View(ctx).apply {
            background = d
            layoutParams = FrameLayout.LayoutParams(14.dp(ctx), 14.dp(ctx), Gravity.TOP or Gravity.END).apply {
                setMargins(0, 10.dp(ctx), 10.dp(ctx), 0)
            }
        }
        dot = dotView
        val label = TextView(ctx).apply {
            text = "E"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
        }
        val status = TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            gravity = Gravity.CENTER
            text = "E-Muse"
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(label)
            addView(status)
        }
        statusView = status
        return FrameLayout(ctx).apply {
            background = bubbleDrawable()
            addView(inner, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(dotView)
            setOnTouchListener(DragTouchListener())
            setOnClickListener { togglePanel() }
        }
    }

    private fun buildPanel(ctx: Context): LinearLayout {
        val title = TextView(ctx).apply {
            text = "E-Muse • hoạt động"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(16.dp(ctx), 12.dp(ctx), 16.dp(ctx), 8.dp(ctx))
        }
        val log = TextView(ctx).apply {
            setTextColor(0xFFCFD8DC.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(16.dp(ctx), 0, 16.dp(ctx), 12.dp(ctx))
            text = logs.joinToString("\n").ifEmpty { "Chưa có lệnh nào." }
        }
        logView = log
        val scroll = ScrollView(ctx).apply { addView(log) }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = panelDrawable()
            addView(title)
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 260.dp(ctx)))
        }
    }

    private fun togglePanel() {
        val w = wm ?: return
        if (expanded) {
            runCatching { panel?.let { w.removeView(it) } }
            panel = null
            expanded = false
        } else {
            val ctx = bubble?.context ?: return
            val p = buildPanel(ctx)
            val pp = overlayParams(300.dp(ctx), WindowManager.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START, 120, 160)
            panel = p
            panelParams = pp
            runCatching { w.addView(p, pp) }
            expanded = true
        }
    }

    private fun setBusy(busy: Boolean) {
        dot?.background = dotDrawable(
            if (busy) 0xFFFF9800.toInt()
            else if (logView != null) 0xFF4CAF50.toInt()
            else 0xFF9E9E9E.toInt(),
        )
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

    private fun bubbleDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xCC1A1A1A.toInt())
        setStroke(2, 0xFF4CAF50.toInt())
    }

    private fun panelDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 18f
        setColor(0xE61A1A1A.toInt())
        setStroke(1, 0xFF4CAF50.toInt())
    }

    private fun dotDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun Int.dp(ctx: Context): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, toFloat(), ctx.resources.displayMetrics).toInt()

    private inner class DragTouchListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downRawX = 0f
        private var downRawY = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = bubbleParams ?: return false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x; startY = p.y
                    downRawX = e.rawX; downRawY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downRawX).toInt()
                    val dy = (e.rawY - downRawY).toInt()
                    if (dx * dx + dy * dy > 100) moved = true
                    p.x = startX + dx
                    p.y = startY + dy
                    wm?.updateViewLayout(bubble, p)
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) return true // swallow click after drag
                }
            }
            return false
        }
    }
}
