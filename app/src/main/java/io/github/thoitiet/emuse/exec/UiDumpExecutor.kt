package io.github.thoitiet.emuse.exec

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.github.thoitiet.emuse.MuseAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

object UiDumpExecutor {
    private const val MAX_DEPTH = 10
    private const val MAX_NODES = 2000

    fun dump(): JSONObject {
        val svc = MuseAccessibilityService.instance
        if (svc != null) {
            val root = svc.rootInActiveWindow ?: throw IllegalStateException("no active window")
            try {
                // Local counter (not shared state): two dumps may run concurrently.
                val counter = intArrayOf(0)
                return JSONObject()
                    .put("via", "accessibility")
                    .put("root", nodeToJson(root, 0, counter))
            } finally {
                root.recycle()
            }
        }
        if (ShellExecutor.hasRoot()) {
            val tmp = "/data/local/tmp/emuse_uidump.xml"
            val r = ShellExecutor.exec(
                "${RootCommands.uiautomatorDump(tmp)} && base64 $tmp; rm -f $tmp",
                asRoot = true,
            )
            val b64 = r.stdout.filter { !it.isWhitespace() }
            if (r.exitCode == 0 && b64.isNotEmpty()) {
                return JSONObject().put("via", "uiautomator").put("xmlBase64", b64)
            }
        }
        throw IllegalStateException("ui.dump unavailable: enable the accessibility service or grant root")
    }

    private fun nodeToJson(n: AccessibilityNodeInfo, depth: Int, counter: IntArray): JSONObject {
        val o = JSONObject()
        val b = Rect()
        n.getBoundsInScreen(b)
        o.put("class", n.className?.toString() ?: "")
        o.put("text", n.text?.toString() ?: "")
        o.put("contentDesc", n.contentDescription?.toString() ?: "")
        o.put("bounds", JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom))
        o.put("clickable", n.isClickable)
        if (depth < MAX_DEPTH && counter[0] < MAX_NODES) {
            val kids = JSONArray()
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                counter[0]++
                kids.put(nodeToJson(c, depth + 1, counter))
                c.recycle()
            }
            o.put("children", kids)
        }
        return o
    }
}
