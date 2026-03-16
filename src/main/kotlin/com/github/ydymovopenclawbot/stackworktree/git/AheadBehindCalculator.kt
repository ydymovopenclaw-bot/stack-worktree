package com.github.ydymovopenclawbot.stackworktree.git

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Computes and caches ahead/behind counts for branches relative to their parents.
 *
 * Results are cached per branch for [ttlMs] milliseconds (default 30 s). The current
 * state is exposed as a [StateFlow] so the UI layer can observe changes reactively.
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

    private val cache = mutableMapOf<String, CachedEntry>()

    private val _state = MutableStateFlow<Map<String, AheadBehind>>(emptyMap())

    /** Observable map of branch name → [AheadBehind]. Updated on every [calculate] call. */
    val state: StateFlow<Map<String, AheadBehind>> = _state.asStateFlow()

    /**
     * Computes ahead/behind for each entry in [branches], where the map key is the branch
     * name and the value is its parent branch name.
     *
     * Cached entries that are still within the TTL are reused. Stale or missing entries are
     * fetched from [gitLayer] in a single pass. The [state] flow is updated with the full
     * result before returning.
     *
     * @param branches Map of branch → parent branch.
     * @return Map of branch → [AheadBehind] for every entry in [branches].
     */
    fun calculate(branches: Map<String, String>): Map<String, AheadBehind> {
        val now = clock()
        val result = mutableMapOf<String, AheadBehind>()

        for ((branch, parent) in branches) {
            val cached = cache[branch]
            if (cached != null && now - cached.timestampMs < ttlMs) {
                result[branch] = cached.value
            } else {
                // Fetch and cache the fresh value.
                val fresh = gitLayer.aheadBehind(branch, parent)
                cache[branch] = CachedEntry(fresh, now)
                result[branch] = fresh
            }
        }

        _state.value = result.toMap()
        return result.toMap()
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
