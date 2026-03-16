package com.github.ydymovopenclawbot.stackworktree.ui

import com.github.ydymovopenclawbot.stackworktree.git.AheadBehind

/**
 * Runtime view model for the Stacks graph panel. Not persisted.
 *
 * Computed on every refresh from the persisted [com.github.ydymovopenclawbot.stackworktree.state.StackState]
 * combined with live ahead/behind counts from [com.github.ydymovopenclawbot.stackworktree.git.AheadBehindCalculator].
 *
 * @param stacks        Ordered list of named stacks to display.
 * @param activeStack   Name of the currently active stack, mirrors [com.github.ydymovopenclawbot.stackworktree.state.StackState.activeStack].
 * @param currentBranch Short name of the currently checked-out branch (from git4idea).
 */
data class StackViewModel(
    val stacks: List<StackViewEntry>,
    val activeStack: String?,
    val currentBranch: String?,
)

/**
 * One named stack with its ordered branch nodes, ready for display.
 *
 * @param name     Logical stack name (e.g. "my-feature").
 * @param branches Ordered branch nodes, bottom → top.
 */
data class StackViewEntry(
    val name: String,
    val branches: List<BranchView>,
)

/**
 * Display data for a single branch node in the graph.
 *
 * @param name            Short branch name.
 * @param isCurrentBranch True when this is the currently checked-out branch.
 * @param aheadBehind     Ahead/behind counts relative to this branch's parent, or null if
 *                        not yet computed (e.g. the bottom-most branch has no parent).
 */
data class BranchView(
    val name: String,
    val isCurrentBranch: Boolean,
    val aheadBehind: AheadBehind?,
)
