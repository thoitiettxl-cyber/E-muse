package io.github.thoitiet.emuse.exec

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import io.github.thoitiet.emuse.MuseAccessibilityService
import org.json.JSONObject

object InputExecutor {
    private fun service(): MuseAccessibilityService? = MuseAccessibilityService.instance

    fun tap(x: Int, y: Int): JSONObject {
        val svc = service()
        if (svc != null) {
            if (!svc.tap(x.toFloat(), y.toFloat())) throw IllegalStateException("accessibility tap failed")
            return JSONObject().put("ok", true).put("via", "accessibility")
        }
        val r = ShellExecutor.exec("input tap $x $y")
        if (r.exitCode != 0) throw IllegalStateException("input tap failed: ${r.stderr.trim()}")
        return JSONObject().put("ok", true).put("via", "shell")
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): JSONObject {
        val svc = service()
        if (svc != null) {
            if (!svc.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs.toLong())) {
                throw IllegalStateException("accessibility swipe failed")
            }
            return JSONObject().put("ok", true).put("via", "accessibility")
        }
        val r = ShellExecutor.exec("input swipe $x1 $y1 $x2 $y2 $durationMs")
        if (r.exitCode != 0) throw IllegalStateException("input swipe failed: ${r.stderr.trim()}")
        return JSONObject().put("ok", true).put("via", "shell")
    }

    fun key(keyCode: Int): JSONObject {
        val svc = service()
        if (svc != null && svc.pressKey(keyCode)) {
            return JSONObject().put("ok", true).put("via", "accessibility")
        }
        val r = ShellExecutor.exec("input keyevent $keyCode")
        if (r.exitCode != 0) throw IllegalStateException("input keyevent failed: ${r.stderr.trim()}")
        return JSONObject().put("ok", true).put("via", "shell")
    }

    fun text(text: String): JSONObject {
        val root = service()?.rootInActiveWindow
        val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            try {
                if (focused.isEditable) {
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text,
                        )
                    }
                    if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                        return JSONObject().put("ok", true).put("via", "accessibility")
                    }
                }
            } finally {
                focused.recycle()
            }
        }
        // Fallback: 'input text' needs spaces escaped as %s and has limited charset support.
        val escaped = text.replace(" ", "%s")
        val r = ShellExecutor.exec("input text $escaped")
        if (r.exitCode != 0) throw IllegalStateException("input text failed: ${r.stderr.trim()}")
        return JSONObject().put("ok", true).put("via", "shell")
    }
}
