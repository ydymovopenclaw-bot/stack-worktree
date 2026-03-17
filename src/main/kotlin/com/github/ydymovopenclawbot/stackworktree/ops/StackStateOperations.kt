package com.github.ydymovopenclawbot.stackworktree.ops

import com.github.ydymovopenclawbot.stackworktree.state.BranchNode
import com.github.ydymovopenclawbot.stackworktree.state.StackState

/**
 * Pure functions that compute the next [StackState] for insert-above and insert-below
 * operations.  Extracted from [OpsLayerImpl] so they can be unit-tested without the
 * IntelliJ Platform runtime.
 */

/**
 * Returns a new [StackState] reflecting an **insert-above** operation:
 *
 * - Adds [newBranch] with parent = [oldParent] and children = `[target]`.
 * - Changes [target]'s parent from [oldParent] to [newBranch].
 * - Updates [oldParent]'s children list: replaces [target] with [newBranch].
 */
internal fun buildStateForInsertAbove(
    state: StackState,
    target: String,
    newBranch: String,
    oldParent: String?,
): StackState {
    val branches = state.branches.toMutableMap()

    // Update old parent: replace target with newBranch in its children list.
    if (oldParent != null) {
        val parentNode = branches[oldParent] ?: BranchNode(oldParent, null)
        branches[oldParent] = parentNode.copy(
            children = parentNode.children.map { if (it == target) newBranch else it }.distinct(),
        )
    }

    // Insert newBranch between oldParent and target.
    branches[newBranch] = BranchNode(name = newBranch, parent = oldParent, children = listOf(target))

    // Update target: its parent is now newBranch.
    val targetNode = branches[target] ?: BranchNode(target, oldParent)
    branches[target] = targetNode.copy(parent = newBranch)

    return state.copy(branches = branches)
}

/**
 * Returns a new [StackState] reflecting an **insert-below** operation:
 *
 * - Adds [newBranch] with parent = [target] and children = [children].
 * - Changes [target]'s children from [children] to `[newBranch]`.
 * - Re-parents each of [children] from [target] to [newBranch].
 */
internal fun buildStateForInsertBelow(
    state: StackState,
    target: String,
    newBranch: String,
    children: List<String>,
): StackState {
    val branches = state.branches.toMutableMap()

    // Update target: its only direct child is now newBranch.
    val targetNode = branches[target] ?: BranchNode(target, null)
    branches[target] = targetNode.copy(children = listOf(newBranch))

    // Insert newBranch between target and its former children.
    branches[newBranch] = BranchNode(name = newBranch, parent = target, children = children)

    // Re-parent each former child.
    for (child in children) {
        val childNode = branches[child] ?: BranchNode(child, target)
        branches[child] = childNode.copy(parent = newBranch)
    }

    return state.copy(branches = branches)
}
