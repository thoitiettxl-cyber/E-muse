package io.github.thoitiet.emuse.exec

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import io.github.thoitiet.emuse.MuseAccessibilityService
import java.text.Normalizer
import org.json.JSONArray
import org.json.JSONObject

/** Thrown when an action references an observation that is no longer the latest. */
class StaleObservationException(message: String) : IllegalStateException(message)

/**
 * Eta/E-Jev style UI snapshot: flatten the accessibility tree into a compact
 * element list on-device, dropping noise nodes. The model acts on element
 * ids ([tapElement]) instead of guessing coordinates.
 *
 * Freshness (Eta's observation_id binding): every [snapshot] mints a
 * monotonic `observation_id` ("o1", "o2", ...). Actions on elements accept an
 * optional `observationId`; when given but not equal to the latest snapshot,
 * the action is rejected with [StaleObservationException] instead of tapping
 * a stale screen.
 *
 * Each snapshot caches the live [AccessibilityNodeInfo]s so a later tap can
 * call performAction(CLICK) directly on the node — the most precise path.
 * The cache is refreshed (old nodes recycled) on every snapshot.
 */
object UiSnapshotter {
    private const val DEFAULT_MAX_NODES = 500

    private val editClasses = setOf(
        "android.widget.EditText",
        "android.widget.AutoCompleteTextView",
        "android.widget.MultiAutoCompleteTextView",
    )

    private var cache: Map<String, AccessibilityNodeInfo> = emptyMap()
    private var cacheBounds: Map<String, Rect> = emptyMap()

    private var observationSeq = 0L
    private var lastObservationId = ""

    /** NFC-normalize + lowercase for stable Vietnamese text matching. */
    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFC).lowercase()

    @Synchronized
    fun latestObservationId(): String = lastObservationId

    @Synchronized
    fun snapshot(
        query: String? = null,
        compact: Boolean = false,
        maxNodes: Int = DEFAULT_MAX_NODES,
    ): JSONObject {
        val svc = MuseAccessibilityService.instance
            ?: throw IllegalStateException("ui.snapshot needs the accessibility service")
        val root = svc.rootInActiveWindow ?: throw IllegalStateException("no active window")
        // Recycle the previous cache before rebuilding.
        cache.values.forEach { runCatching { it.recycle() } }
        val nodes = LinkedHashMap<String, AccessibilityNodeInfo>()
        val bounds = LinkedHashMap<String, Rect>()
        val out = JSONArray()
        val limit = maxNodes.coerceIn(1, 2000)
        val q = query?.trim()?.takeIf { it.isNotEmpty() }?.let(::norm)
        try {
            val counter = intArrayOf(0)
            walk(root, counter, nodes, bounds, out, q, compact, limit)
        } finally {
            runCatching { root.recycle() }
        }
        cache = nodes
        cacheBounds = bounds
        observationSeq++
        lastObservationId = "o$observationSeq"
        return JSONObject()
            .put("via", "accessibility")
            .put("observation_id", lastObservationId)
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
        query: String?,
        compact: Boolean,
        limit: Int,
    ): Boolean {
        if (counter[0] >= limit) return false
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
            // The node cache always holds every kept node; [query] only filters output.
            val matches = query == null ||
                norm(text).contains(query) || norm(desc).contains(query)
            if (matches) {
                val el = JSONObject()
                    .put("id", id)
                    .put("text", text.take(120))
                    .put("desc", desc.take(120))
                if (!compact) {
                    el.put("bounds", JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom))
                }
                el.put("clickable", n.isClickable)
                    .put("editable", editable)
                    .put("scrollable", n.isScrollable)
                    .put("enabled", n.isEnabled)
                    .put("password", n.isPassword)
                out.put(el)
            }
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (counter[0] >= limit) {
                c.recycle()
                break
            }
            if (!walk(c, counter, nodes, bounds, out, query, compact, limit)) {
                runCatching { c.recycle() }
            }
        }
        return cached
    }

    /**
     * Eta's freshness gate: when Pi passes the observation it acted on, refuse
     * to dispatch on a stale screen. Null/blank id = legacy callers, no check.
     */
    private fun checkFresh(observationId: String?) {
        if (!observationId.isNullOrBlank() && observationId != lastObservationId) {
            throw StaleObservationException(
                "STALE_OBSERVATION: observation '$observationId' is not the latest " +
                    "('$lastObservationId'); take a fresh ui_snapshot first",
            )
        }
    }

    /** Tap an element from the last snapshot. Returns the path used. */
    @Synchronized
    fun tapElement(id: String, observationId: String? = null): JSONObject {
        checkFresh(observationId)
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

    /**
     * T1.1: tap, settle, then a fresh snapshot — act + verify in one turn.
     * Returns `{tap: {...}, snapshot: {...}}`.
     */
    @Synchronized
    fun tapAndObserve(id: String, observationId: String? = null): JSONObject {
        val tap = tapElement(id, observationId)
        val snap = snapshot()
        return JSONObject().put("tap", tap).put("snapshot", snap)
    }

    /**
     * T1.3: wait until [text] appears anywhere in the accessibility tree.
     * Polls every 350ms (Eta's wait_for_text), matching NFC-normalized and
     * case-insensitive so Vietnamese diacritics match. One call, no Pi polling.
     */
    fun waitForText(text: String, timeoutMs: Long = 10_000L): JSONObject {
        require(text.isNotEmpty() && text.length <= 200) { "text empty or too long (max 200)" }
        val timeout = timeoutMs.coerceIn(1_000L, 60_000L)
        val needle = norm(text)
        val start = SystemClock.elapsedRealtime()
        while (true) {
            if (treeContains(needle)) {
                return JSONObject()
                    .put("found", true)
                    .put("elapsedMs", SystemClock.elapsedRealtime() - start)
            }
            if (SystemClock.elapsedRealtime() - start >= timeout) break
            try {
                Thread.sleep(350)
            } catch (_: InterruptedException) {
                break
            }
        }
        return JSONObject()
            .put("found", false)
            .put("elapsedMs", SystemClock.elapsedRealtime() - start)
    }

    /** Lightweight tree scan (no element list built); recycles every node it owns. */
    private fun treeContains(needle: String): Boolean {
        val svc = MuseAccessibilityService.instance ?: return false
        val root = svc.rootInActiveWindow ?: return false
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        try {
            while (stack.isNotEmpty() && visited < 2000) {
                val n = stack.removeLast()
                visited++
                try {
                    if (norm(n.text?.toString().orEmpty()).contains(needle) ||
                        norm(n.contentDescription?.toString().orEmpty()).contains(needle)
                    ) {
                        return true
                    }
                    for (i in 0 until n.childCount) {
                        n.getChild(i)?.let { stack.add(it) }
                    }
                } finally {
                    runCatching { n.recycle() }
                }
            }
        } finally {
            while (stack.isNotEmpty()) runCatching { stack.removeLast().recycle() }
        }
        return false
    }
}
