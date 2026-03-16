package com.github.ydymovopenclawbot.stackworktree.state

import kotlinx.serialization.Serializable

/** Top-level state for a single repository's stacked-branch graph. */
@Serializable
data class StackState(
    val repoConfig: RepoConfig,
    val branches: Map<String, BranchNode> = emptyMap(),
)

/** Repository-level configuration stored with the stack. */
@Serializable
data class RepoConfig(
    val trunk: String,
    val remote: String,
    val version: Int = 1,
)

/** Health of a branch relative to its parent. */
@Serializable
enum class BranchHealth {
    CLEAN,
    NEEDS_REBASE,
    HAS_CONFLICTS,
    MERGED,
}

/** Pull-request metadata (provider-agnostic). */
@Serializable
data class PrInfo(
    val provider: String,  // e.g. "github", "gitlab"
    val id: String,
    val url: String,
    val status: String,    // e.g. "open", "merged", "closed"
    val ciStatus: String,  // e.g. "passing", "failing", "pending"
)

/**
 * Represents one branch in the stacked-branch graph.
 * [name] is also the map key in [StackState.branches].
 */
@Serializable
data class BranchNode(
    val name: String,
    val parent: String?,                     // null only for trunk
    val children: List<String> = emptyList(),
    val worktreePath: String? = null,
    val prInfo: PrInfo? = null,
    val baseCommit: String? = null,          // SHA of the merge-base with parent
    val health: BranchHealth = BranchHealth.CLEAN,
)
