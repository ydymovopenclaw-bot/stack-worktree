package com.github.ydymovopenclawbot.stackworktree.stack

import com.github.ydymovopenclawbot.stackworktree.state.StackState

/**
 * Generates Markdown stack-navigation tables for inclusion in pull request descriptions.
 *
 * The table lists every non-trunk branch in the stack in BFS (parent-before-child) order,
 * so the branch closest to trunk always appears in row 1. The row for [currentBranch] is
 * **bolded** so reviewers instantly see which PR they are viewing.
 *
 * Example output (3-branch stack, viewing `feat/b`):
 * ```
 * | # | Branch | PR |
 * |---|--------|----|
 * | 1 | feat/a | [#1](https://github.com/…/pull/1) |
 * | **2** | **feat/b** | **[#2](https://github.com/…/pull/2)** |
 * | 3 | feat/c | _(pending)_ |
 * ```
 *
 * This object is intentionally stateless / pure so it can be exercised in unit tests
 * without any IntelliJ Platform infrastructure.
 */
object StackNavTable {

    /**
     * Generates a Markdown navigation table for all non-trunk branches in [state].
     *
     * Branches are ordered in BFS (parent-before-child) traversal starting from the
     * trunk's direct children, so the root of the stack appears in row 1 and leaf
     * branches appear last.
     *
     * The row matching [currentBranch] is **bolded** in every cell. Branches that do not
     * yet have an associated PR (i.e. [com.github.ydymovopenclawbot.stackworktree.state.BranchNode.prInfo]
     * is `null`) show `_(pending)_` in the PR column.
     *
     * Returns an empty string when there are no non-trunk branches to display.
     *
     * @param state         The current stack state.
     * @param currentBranch The branch for whose PR description this table is generated;
     *                      its row is bolded.
     */
    fun generate(state: StackState, currentBranch: String): String {
        val orderedBranches = collectBfsOrder(state)
        if (orderedBranches.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("| # | Branch | PR |")
        sb.appendLine("|---|--------|----|")

        orderedBranches.forEachIndexed { index, branch ->
            val node   = state.branches[branch]
            val num    = index + 1
            val prCell = node?.prInfo?.let { "[#${it.id}](${it.url})" } ?: "_(pending)_"

            if (branch == currentBranch) {
                sb.appendLine("| **$num** | **$branch** | **$prCell** |")
            } else {
                sb.appendLine("| $num | $branch | $prCell |")
            }
        }

        return sb.toString().trimEnd()
    }

    /**
     * Returns all non-trunk branches in BFS (parent-before-child) order.
     * The trunk branch itself is excluded — it lives in [StackState.repoConfig.trunk] and
     * never has a PR raised against it.
     */
    internal fun collectBfsOrder(state: StackState): List<String> {
        val trunk  = state.repoConfig.trunk
        val result = mutableListOf<String>()
        val queue  = ArrayDeque(state.branches[trunk]?.children ?: emptyList())
        while (queue.isNotEmpty()) {
            val branch = queue.removeFirst()
            result.add(branch)
            queue.addAll(state.branches[branch]?.children ?: emptyList())
        }
        return result
    }
}
