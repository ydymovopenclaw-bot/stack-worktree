package com.github.ydymovopenclawbot.stackworktree.ops

data class RemoveStackResult(
    val removedBranches: List<String>,
    val deletedBranches: List<String> = emptyList(),
    val removedWorktrees: List<String> = emptyList(),
    val failedWorktrees: Map<String, String> = emptyMap(),
    val failedBranches: Map<String, String> = emptyMap(),
) {
    fun summary(): String = buildString {
        append("Removed ${removedBranches.size} branch(es) from stack")
        if (deletedBranches.isNotEmpty()) append(", deleted ${deletedBranches.size} git branch(es)")
        if (removedWorktrees.isNotEmpty()) append(", pruned ${removedWorktrees.size} worktree(s)")
        if (failedWorktrees.isNotEmpty()) append(", ${failedWorktrees.size} worktree(s) failed to remove")
        if (failedBranches.isNotEmpty()) append(", ${failedBranches.size} branch(es) failed to delete")
        append(".")
    }
}
