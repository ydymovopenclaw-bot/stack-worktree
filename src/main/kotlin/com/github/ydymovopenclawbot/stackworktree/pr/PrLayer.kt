package com.github.ydymovopenclawbot.stackworktree.pr

/**
 * PR layer — integrates with pull-request providers (e.g. GitHub) to surface PR status
 * alongside each worktree.
 */
interface PrLayer {
    /** Returns the PR associated with [branch], or `null` if none exists. */
    fun findPr(branch: String): PrInfo?

    /** Opens the PR for [branch] in the default browser. */
    fun openPr(branch: String)
}

/** Minimal descriptor for a pull request. */
data class PrInfo(
    val number: Int,
    val title: String,
    val url: String,
    val state: PrState,
)

/** Possible states for a pull request. */
enum class PrState { OPEN, CLOSED, MERGED }
