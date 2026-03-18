package com.github.ydymovopenclawbot.stackworktree.git

import java.util.concurrent.ConcurrentHashMap

/**
 * Computes and caches ahead/behind counts for branches relative to their parents.
 *
 * Results are cached per branch for [ttlMs] milliseconds (default 30 s).
 *
 * Thread-safety: [calculate] and [invalidate] may be called concurrently from pooled
 * background threads (e.g. rapid Refresh button clicks or burst GIT_REPO_CHANGE events).
 * The backing store is a [ConcurrentHashMap] so individual reads and writes are atomic.
 * The read-then-write check-and-set in [calculate] is wrapped in [synchronized] to prevent
 * two threads from both seeing a stale entry and each issuing a redundant git call.
 *
 * @param gitLayer Low-level git operations provider.
 * @param ttlMs    How long a cached entry is considered fresh (milliseconds).
 * @param clock    Source of the current time; injectable for testing.
 */
class AheadBehindCalculator(
    private val gitLayer: GitLayer,
    private val ttlMs: Long = 30_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class CachedEntry(val value: AheadBehind, val timestampMs: Long)

    // ConcurrentHashMap for safe concurrent reads; synchronized blocks guard the
    // read-then-write sequences to avoid duplicate git calls under contention.
    private val cache = ConcurrentHashMap<String, CachedEntry>()

    /**
     * Computes ahead/behind for each entry in [branches], where the map key is the branch
     * name and the value is its parent branch name.
     *
     * Cached entries that are still within the TTL are reused. Stale or missing entries are
     * fetched from [gitLayer] in a single pass.
     *
     * @param branches Map of branch → parent branch.
     * @return Map of branch → [AheadBehind] for every entry in [branches].
     */
    fun calculate(branches: Map<String, String>): Map<String, AheadBehind> {
        val now = clock()
        val result = mutableMapOf<String, AheadBehind>()

        for ((branch, parent) in branches) {
            // Check cache under lock, but compute outside to avoid holding the lock
            // during blocking git I/O (a hung git process would block all callers).
            val cached = synchronized(cache) {
                val entry = cache[branch]
                if (entry != null && now - entry.timestampMs < ttlMs) entry.value else null
            }
            val ab = cached ?: run {
                val fresh = gitLayer.aheadBehind(branch, parent)
                synchronized(cache) { cache[branch] = CachedEntry(fresh, now) }
                fresh
            }
            result[branch] = ab
        }

        return result
    }

    /**
     * Invalidates all cached entries. The next [calculate] call will re-fetch every branch
     * from [gitLayer].
     */
    fun invalidate() {
        cache.clear()
    }

    /**
     * Invalidates the cached entry for [branch] only. Other cached entries remain valid.
     */
    fun invalidate(branch: String) {
        cache.remove(branch)
    }
}
