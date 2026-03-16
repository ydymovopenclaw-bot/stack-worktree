package com.github.ydymovopenclawbot.stackworktree.state

/**
 * State layer — persists and restores plugin state across IDE restarts.
 */
interface StateLayer {
    /** Loads persisted plugin state from storage. */
    fun load(): PluginState

    /** Persists [state] to storage. */
    fun save(state: PluginState)
}

/** Top-level container for all persisted plugin state. */
data class PluginState(
    val activeWorktrees: List<String> = emptyList(),
    val lastUsedBranch: String? = null,
)
