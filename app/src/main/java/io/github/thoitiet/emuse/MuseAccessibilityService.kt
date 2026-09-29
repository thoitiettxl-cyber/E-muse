package io.github.thoitiet.emuse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Outcome of a gesture dispatch, in the spirit of Eta's MainThreadCallGate:
 * a timeout does NOT prove the gesture never ran, so [UNKNOWN] must never be
 * replayed (not even via the root fallback) — the caller must re-observe.
 */
enum class GestureOutcome {
    /** Callback confirmed the gesture completed. */
    DISPATCHED,

    /** dispatchGesture() refused: nothing was submitted, root fallback is safe. */
    NOT_STARTED,

    /** Timeout or cancelled after dispatch: may already have executed. Do NOT replay. */
    UNKNOWN,
}

class MuseAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: MuseAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    private fun gesture(path: Path, durationMs: Long): GestureOutcome {
        val latch = CountDownLatch(1)
        var completed = false
        var cancelled = false
        val desc = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val submitted = dispatchGesture(
            desc,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    cancelled = true
                    latch.countDown()
                }
            },
            null,
        )
        // dispatchGesture() == false: the system refused outright — nothing ran.
        if (!submitted) return GestureOutcome.NOT_STARTED
        val finished = latch.await(8, TimeUnit.SECONDS)
        return when {
            completed -> GestureOutcome.DISPATCHED
            // Cancelled after dispatch, or latch timeout: the gesture may already
            // have executed. Replaying (even via root) risks a double action.
            cancelled || !finished -> GestureOutcome.UNKNOWN
            else -> GestureOutcome.UNKNOWN
        }
    }

    fun tap(x: Float, y: Float): GestureOutcome =
        gesture(Path().apply { moveTo(x, y) }, 50)

    /** Long-press = a tap stroke held for [durationMs]; same no-blind-replay contract as [tap]. */
    fun longPress(x: Float, y: Float, durationMs: Long): GestureOutcome =
        gesture(Path().apply { moveTo(x, y) }, durationMs.coerceAtLeast(300))

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): GestureOutcome =
        gesture(
            Path().apply { moveTo(x1, y1); lineTo(x2, y2) },
            durationMs.coerceAtLeast(50),
        )

    /** Only a few keys are injectable via accessibility global actions; others fall back to shell. */
    fun pressKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        KeyEvent.KEYCODE_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        KeyEvent.KEYCODE_APP_SWITCH -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        else -> false
    }

    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    /**
     * Tracks the foreground package from window-state events (the config
     * enables typeAllMask). Used by wait_for_package; may lag briefly behind
     * the real foreground app.
     */
    @Volatile
    var foregroundPackage: String? = null
        private set

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { foregroundPackage = it }
        }
    }
}
