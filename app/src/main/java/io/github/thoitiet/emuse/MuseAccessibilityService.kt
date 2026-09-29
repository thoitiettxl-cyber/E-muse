package io.github.thoitiet.emuse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun gesture(path: Path, durationMs: Long): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val desc = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(
            desc,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            },
            null,
        )
        latch.await(8, TimeUnit.SECONDS)
        return ok
    }

    fun tap(x: Float, y: Float): Boolean =
        gesture(Path().apply { moveTo(x, y) }, 50)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
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
}
