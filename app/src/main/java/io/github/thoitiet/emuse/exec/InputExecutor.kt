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

    /** Eta's tap_area: tap the center of a rectangle (for large buttons/list items). */
    fun tapArea(x1: Int, y1: Int, x2: Int, y2: Int): JSONObject {
        DeviceScreen.validatePoint(x1, y1)
        DeviceScreen.validatePoint(x2, y2)
        require(x2 > x1 && y2 > y1) { "invalid area: ($x1,$y1)-($x2,$y2)" }
        return tap((x1 + x2) / 2, (y1 + y2) / 2)
            .put("area", "[$x1,$y1][$x2,$y2]")
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

    /**
     * Eta's long_press: accessibility long-press gesture first, root
     * `input swipe x y x y <duration>` as fallback. Same no-blind-replay
     * contract as [tap]: UNKNOWN outcome is never replayed.
     */
    fun longPress(x: Int, y: Int, durationMs: Int): JSONObject {
        DeviceScreen.validatePoint(x, y)
        val duration = durationMs.coerceIn(300, 3_000)
        val svc = service()
        if (svc != null) {
            when (svc.longPress(x.toFloat(), y.toFloat(), duration.toLong())) {
                GestureOutcome.DISPATCHED -> {
                    settleAfter("long_press")
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
        val res = shellOrThrow(RootCommands.inputLongPress(x, y, duration), "long_press")
        if (res.optBoolean("ok")) settleAfter("long_press")
        return res
    }

    /**
     * Eta's scroll: content-browsing direction mapped to a screen swipe.
     * down = show content below (swipe up), up = show content above
     * (swipe down), left/right analogously.
     */
    fun scroll(direction: String): JSONObject {
        val (w, h) = DeviceScreen.size()
        if (w <= 0 || h <= 0) throw IllegalStateException("unknown screen size")
        return when (direction.lowercase()) {
            "down" -> swipe(w / 2, (h * 0.75).toInt(), w / 2, (h * 0.25).toInt(), 500)
            "up" -> swipe(w / 2, (h * 0.25).toInt(), w / 2, (h * 0.75).toInt(), 500)
            "left" -> swipe((w * 0.25).toInt(), h / 2, (w * 0.75).toInt(), h / 2, 500)
            "right" -> swipe((w * 0.75).toInt(), h / 2, (w * 0.25).toInt(), h / 2, 500)
            else -> throw IllegalArgumentException("direction must be up/down/left/right")
        }.put("direction", direction.lowercase())
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
        try {
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
        } finally {
            root?.recycle()
        }
        // Fallback: root `input text` with safe quoting. Platform limits apply
        // (no Unicode) — same caveat Eta documents by refusing this path.
        val res = shellOrThrow(RootCommands.inputText(text), "text")
        if (res.optBoolean("ok")) settleAfter("text")
        return res
    }

    /**
     * Eta's replace_text: ACTION_SET_TEXT on the focused field (or a specific
     * element from ui_snapshot). No shell fallback — `input text` appends and
     * cannot replace, and unreadable fields (passwords) refuse reconstruction.
     */
    fun replaceText(
        text: String,
        elementId: String? = null,
        observationId: String? = null,
    ): JSONObject {
        require(text.length <= 4_000) { "text too long (max 4000)" }
        if (elementId != null) return UiSnapshotter.setElementText(elementId, observationId, text)
        val svc = service()
            ?: throw IllegalStateException("replace_text needs the accessibility service")
        val root = svc.rootInActiveWindow
            ?: throw IllegalStateException("no active window")
        try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: throw IllegalStateException("replace_text needs a focused field (or pass elementId)")
            try {
                if (!focused.isEditable) {
                    throw IllegalStateException("focused node is not editable")
                }
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text,
                    )
                }
                if (!focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    throw IllegalStateException("ACTION_SET_TEXT failed on the focused field")
                }
                settleAfter("text")
                return JSONObject().put("ok", true).put("via", "accessibility")
            } finally {
                focused.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    /** Eta's clear_text: replace with the empty string. */
    fun clearText(elementId: String? = null, observationId: String? = null): JSONObject =
        replaceText("", elementId, observationId)
}
