package com.github.ydymovopenclawbot.stackworktree.pr

/**
 * PR layer — integrates with pull-request providers (e.g. GitHub) to surface PR status
 * alongside each worktree.
 */
interface PrLayer {
    /** Returns the PR associated with [branch], or `null` if none exists. */
    fun findPr(branch: String): PrInfo?

    /**
     * Returns the full PR status (CI checks + review decision) for [branch],
     * or `null` if no open PR exists.  Performs blocking network I/O — callers
     * must invoke this from a background thread, never from the EDT.
     */
    fun getPrStatus(branch: String): PrStatus?

    /** Opens the PR for [branch] in the default browser. */
    fun openPr(branch: String)
}

/** Minimal descriptor for a pull request. */
data class PrInfo(
    val number: Int,
    val title: String,
    val url: String,
    val state: PrState,
    /** Whether the pull request is in draft / work-in-progress state. */
    val isDraft: Boolean = false,
)

/** Possible states for a pull request. */
enum class PrState { OPEN, CLOSED, MERGED }
