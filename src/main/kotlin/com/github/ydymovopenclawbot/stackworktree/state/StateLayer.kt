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

/**
 * A single node in the tracked-branch tree.
 *
 * @param name       The git branch name (e.g. "feature/foo").
 * @param parentName Branch name of this node's parent, or null if it is directly under trunk.
 * @param children   Ordered list of direct child branch names.
 */
data class TrackedBranchNode(
    val name: String,
    val parentName: String? = null,
    val children: List<String> = emptyList(),
)

/** Top-level container for all persisted plugin state. */
data class PluginState(
    val activeWorktrees: List<String> = emptyList(),
    val lastUsedBranch: String? = null,
    /** The trunk/base branch (e.g. "main" or "master"). */
    val trunkBranch: String? = null,
    /**
     * Flat map of all tracked branch nodes keyed by branch name.
     * Children of trunk have [TrackedBranchNode.parentName] == [trunkBranch].
     */
    val trackedBranches: Map<String, TrackedBranchNode> = emptyMap(),
)
