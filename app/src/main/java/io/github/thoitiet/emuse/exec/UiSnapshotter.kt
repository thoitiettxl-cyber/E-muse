package io.github.thoitiet.emuse.exec

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.github.thoitiet.emuse.MuseAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Eta/E-Jev style UI snapshot: flatten the accessibility tree into a compact
 * element list on-device, dropping noise nodes. The model acts on element
 * ids ([tapElement]) instead of guessing coordinates.
 *
 * Each snapshot caches the live [AccessibilityNodeInfo]s so a later tap can
 * call performAction(CLICK) directly on the node — the most precise path.
 * The cache is refreshed (old nodes recycled) on every snapshot.
 */
object UiSnapshotter {
    private const val MAX_NODES = 500

    private val editClasses = setOf(
        "android.widget.EditText",
        "android.widget.AutoCompleteTextView",
        "android.widget.MultiAutoCompleteTextView",
    )

    private var cache: Map<String, AccessibilityNodeInfo> = emptyMap()
    private var cacheBounds: Map<String, Rect> = emptyMap()

    @Synchronized
    fun snapshot(): JSONObject {
        val svc = MuseAccessibilityService.instance
            ?: throw IllegalStateException("ui.snapshot needs the accessibility service")
        val root = svc.rootInActiveWindow ?: throw IllegalStateException("no active window")
        // Recycle the previous cache before rebuilding.
        cache.values.forEach { runCatching { it.recycle() } }
        val nodes = LinkedHashMap<String, AccessibilityNodeInfo>()
        val bounds = LinkedHashMap<String, Rect>()
        val out = JSONArray()
        try {
            val counter = intArrayOf(0)
            walk(root, counter, nodes, bounds, out)
        } finally {
            runCatching { root.recycle() }
        }
        cache = nodes
        cacheBounds = bounds
        return JSONObject()
            .put("via", "accessibility")
            .put("count", out.length())
            .put("elements", out)
    }

    /** Walks the tree; returns true if [n] itself was cached (caller must not recycle it). */
    private fun walk(
        n: AccessibilityNodeInfo,
        counter: IntArray,
        nodes: MutableMap<String, AccessibilityNodeInfo>,
        bounds: MutableMap<String, Rect>,
        out: JSONArray,
    ): Boolean {
        if (counter[0] >= MAX_NODES) return false
        val text = n.text?.toString().orEmpty()
        val desc = n.contentDescription?.toString().orEmpty()
        val editable = n.className?.toString() in editClasses ||
            n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        val keep = text.isNotEmpty() || desc.isNotEmpty() ||
            n.isClickable || editable || n.isScrollable
        var cached = false
        if (keep) {
            val id = "e${counter[0]}"
            counter[0]++
            val b = Rect()
            n.getBoundsInScreen(b)
            nodes[id] = n // cached: do NOT recycle
            bounds[id] = Rect(b)
            cached = true
            out.put(JSONObject()
                .put("id", id)
                .put("text", text.take(120))
                .put("desc", desc.take(120))
                .put("bounds", JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom))
                .put("clickable", n.isClickable)
                .put("editable", editable)
                .put("scrollable", n.isScrollable)
                .put("enabled", n.isEnabled)
                .put("password", n.isPassword))
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (counter[0] >= MAX_NODES) {
                c.recycle()
                break
            }
            if (!walk(c, counter, nodes, bounds, out)) runCatching { c.recycle() }
        }
        return cached
    }

    /** Tap an element from the last snapshot. Returns the path used. */
    @Synchronized
    fun tapElement(id: String): JSONObject {
        val node = cache[id]
        if (node != null) {
            val ok = runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            if (ok) {
                settleAfter("tap")
                return JSONObject().put("ok", true).put("via", "node-click").put("id", id)
            }
        }
        val b = cacheBounds[id]
        if (b != null && b.width() > 0 && b.height() > 0) {
            val res = InputExecutor.tap(b.centerX(), b.centerY())
            res.put("id", id).put("via", (res.optString("via") + "+element-center"))
            return res
        }
        throw IllegalStateException("unknown element id: $id (take a fresh ui_snapshot first)")
    }
}
