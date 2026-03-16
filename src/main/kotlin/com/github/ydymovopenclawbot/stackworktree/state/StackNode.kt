package com.github.ydymovopenclawbot.stackworktree.state

/**
 * One node in the stack topology tree.
 *
 * [branch] is the branch name. [children] are the branches stacked directly on top of
 * this one. The root [StackNode] represents the trunk (main/master).
 */
data class StackNode(
    val branch: String,
    val children: List<StackNode> = emptyList(),
)
