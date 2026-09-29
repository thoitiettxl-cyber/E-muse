package io.github.thoitiet.emuse.exec

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import io.github.thoitiet.emuse.GestureOutcome
import io.github.thoitiet.emuse.MuseAccessibilityService
import org.json.JSONObject

/**
 * Input injection: accessibility gestures first (Eta's preference), root
 * `input` command as fallback. Coordinates are validated against the screen
 * size (Eta's validatePoint) and the UI is given settle time after each
 * action (Eta's waitForUiSettle).
 */
object InputExecutor {
    private fun service(): MuseAccessibilityService? = MuseAccessibilityService.instance

    private fun shellOrThrow(cmd: String, tool: String): JSONObject {
        val r = ShellExecutor.exec(cmd, asRoot = true)
        if (r.timedOut) {
            // Eta's ACTION_OUTCOME_UNKNOWN: the action may already have executed.
            return JSONObject()
                .put("ok", false)
                .put("code", "ACTION_OUTCOME_UNKNOWN")
                .put("via", "shell")
                .put("note", "command timed out; re-observe before retrying")
        }
        if (!r.ok) throw IllegalStateException("$tool failed: ${r.stderr.trim().take(200)}")
        return JSONObject().put("ok", true).put("via", "shell")
    }

    fun tap(x: Int, y: Int): JSONObject {
        DeviceScreen.validatePoint(x, y)
        val svc = service()
        if (svc != null) {
            when (svc.tap(x.toFloat(), y.toFloat())) {
                GestureOutcome.DISPATCHED -> {
                    settleAfter("tap")
                    return JSONObject().put("ok", true).put("via", "accessibility")
                }
                // NOT_STARTED: nothing was submitted — the root fallback is safe.
                GestureOutcome.NOT_STARTED -> { /* fall through to shell */ }
                // UNKNOWN: the gesture may already have executed (timeout or
                // cancelled after dispatch). Replaying would risk a double tap,
                // so the root fallback is FORBIDDEN here (Eta's no-blind-retry).
                GestureOutcome.UNKNOWN -> return JSONObject()
                    .put("ok", false)
                    .put("code", "ACTION_OUTCOME_UNKNOWN")
                    .put("via", "accessibility")
                    .put(
                        "note",
                        "gesture outcome unknown (timeout/cancelled); re-observe " +
                            "before retrying — do NOT replay",
                    )
            }
        }
        val res = shellOrThrow(RootCommands.inputTap(x, y), "tap")
        if (res.optBoolean("ok")) settleAfter("tap")
        return res
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): JSONObject {
        DeviceScreen.validatePoint(x1, y1)
        DeviceScreen.validatePoint(x2, y2)
        val svc = service()
        if (svc != null) {
            when (svc.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs.toLong())) {
                GestureOutcome.DISPATCHED -> {
                    settleAfter("swipe")
                    return JSONObject().put("ok", true).put("via", "accessibility")
                }
                GestureOutcome.NOT_STARTED -> { /* fall through to shell */ }
                GestureOutcome.UNKNOWN -> return JSONObject()
                    .put("ok", false)
                    .put("code", "ACTION_OUTCOME_UNKNOWN")
                    .put("via", "accessibility")
                    .put(
                        "note",
                        "gesture outcome unknown (timeout/cancelled); re-observe " +
                            "before retrying — do NOT replay",
                    )
            }
        }
        val res = shellOrThrow(RootCommands.inputSwipe(x1, y1, x2, y2, durationMs), "swipe")
        if (res.optBoolean("ok")) settleAfter("swipe")
        return res
    }

    fun key(keyCode: Int): JSONObject {
        val svc = service()
        if (svc != null && svc.pressKey(keyCode)) {
            settleAfter("key")
            return JSONObject().put("ok", true).put("via", "accessibility")
        }
        val res = shellOrThrow(RootCommands.inputKey(keyCode), "key")
        if (res.optBoolean("ok")) settleAfter("key")
        return res
    }

    fun text(text: String): JSONObject {
        if (text.isEmpty() || text.length > 500) {
            throw IllegalArgumentException("text empty or too long (max 500)")
        }
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
                        settleAfter("text")
                        return JSONObject().put("ok", true).put("via", "accessibility")
                    }
                }
            } finally {
                focused.recycle()
            }
        }
        // Fallback: root `input text` with safe quoting. Platform limits apply
        // (no Unicode) — same caveat Eta documents by refusing this path.
        val res = shellOrThrow(RootCommands.inputText(text), "text")
        if (res.optBoolean("ok")) settleAfter("text")
        return res
    }
}
