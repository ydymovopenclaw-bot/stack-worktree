package com.github.ydymovopenclawbot.stackworktree.state

/**
 * Top-level state persisted in refs/stacktree/state.
 *
 * @property stacks     All stacks tracked by StackTree, ordered arbitrarily.
 * @property activeStack Name of the currently active stack, or null if none selected.
 * @property schemaVersion Monotonically increasing integer for forward-compatible migration.
 */
data class StackState(
    val stacks: List<StackEntry> = emptyList(),
    val activeStack: String? = null,
    val schemaVersion: Int = 1,
)

/**
 * A single named stack: an ordered list of branches from bottom (index 0) to top.
 *
 * @property name          Logical stack name (e.g. "my-feature").
 * @property branches      Ordered branch names, bottom → top (e.g. ["main", "feat/base", "feat/top"]).
 * @property worktreePaths Local worktree paths parallel to [branches]; empty string means no worktree.
 */
data class StackEntry(
    val name: String,
    val branches: List<String>,
    val worktreePaths: List<String> = emptyList(),
)
