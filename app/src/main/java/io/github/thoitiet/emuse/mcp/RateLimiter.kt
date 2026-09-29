package io.github.thoitiet.emuse.mcp

/**
 * Minimal in-memory per-key rate limiter: at most [maxPerMinute] requests
 * per rolling 60-second window for each key. Thread-safe via a single lock.
 * Buckets are pruned lazily on each check; keys are API keys (a handful at
 * most), so the map stays tiny.
 */
class RateLimiter(private val maxPerMinute: Int = 60) {
    private val lock = Any()
    private val hits = HashMap<String, ArrayDeque<Long>>()

    /** true when the request is allowed, false when the bucket is exhausted. */
    fun allow(key: String): Boolean {
        val now = System.currentTimeMillis()
        val cutoff = now - 60_000L
        synchronized(lock) {
            val q = hits.getOrPut(key) { ArrayDeque() }
            while (q.isNotEmpty() && q.first() <= cutoff) q.removeFirst()
            if (q.size >= maxPerMinute) return false
            q.addLast(now)
            return true
        }
    }
}
