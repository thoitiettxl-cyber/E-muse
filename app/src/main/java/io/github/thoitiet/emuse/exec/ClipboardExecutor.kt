package io.github.thoitiet.emuse.exec

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import io.github.thoitiet.emuse.MuseAccessibilityService
import org.json.JSONObject

/**
 * Clipboard read/write plus Eta's paste_text. Paste requires the
 * accessibility service to confirm real input focus (Eta's policy):
 * the clipboard is set and ACTION_PASTE is performed on the focused
 * editable node. The clipboard is never modified when there is no focus.
 */
class ClipboardExecutor(private val appCtx: Context) {
    companion object {
        const val MAX_CHARS = 20_000
    }

    private fun manager(): ClipboardManager =
        appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun set(text: String): JSONObject {
        require(text.length <= MAX_CHARS) { "text too long (max $MAX_CHARS)" }
        manager().setPrimaryClip(ClipData.newPlainText("emuse", text))
        return JSONObject().put("ok", true).put("chars", text.length)
    }

    fun get(): JSONObject {
        val clip = manager().primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(appCtx)?.toString().orEmpty()
        } else {
            ""
        }
        // Android 10+ restricts background clipboard reads; surface that as-is.
        return JSONObject().put("ok", true).put("text", text.take(MAX_CHARS))
    }

    fun paste(text: String): JSONObject {
        require(text.length <= MAX_CHARS) { "text too long (max $MAX_CHARS)" }
        val svc = MuseAccessibilityService.instance
            ?: throw IllegalStateException("paste_text needs the accessibility service")
        val root = svc.rootInActiveWindow
            ?: throw IllegalStateException("no active window")
        try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: throw IllegalStateException(
                    "paste_text needs real input focus; clipboard was not modified",
                )
            try {
                if (!focused.isEditable) {
                    throw IllegalStateException(
                        "focused node is not editable; clipboard was not modified",
                    )
                }
                manager().setPrimaryClip(ClipData.newPlainText("emuse", text))
                if (!focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    throw IllegalStateException("ACTION_PASTE failed on the focused field")
                }
                settleAfter("text")
                return JSONObject().put("ok", true).put("chars", text.length)
            } finally {
                focused.recycle()
            }
        } finally {
            root.recycle()
        }
    }
}
