package com.github.ydymovopenclawbot.stackworktree.pr

/**
 * Full status snapshot for a pull request, combining its base info with live CI and review data.
 */
data class PrStatus(
    val prInfo: PrInfo,
    val checksState: ChecksState,
    val reviewState: ReviewState,
)

/** Aggregated result of all CI check runs attached to the PR's head commit. */
enum class ChecksState {
    /** At least one check is still running. */
    PENDING,

    /** All checks completed and passed (or were skipped). */
    PASSING,

    /** At least one check failed. */
    FAILING,

    /** No checks are configured for this repository. */
    NONE,
}

/** Aggregated review decision for the pull request. */
enum class ReviewState {
    /** At least one reviewer approved and no outstanding change requests. */
    APPROVED,

    /** At least one reviewer requested changes. */
    CHANGES_REQUESTED,

    /** Reviews are required but none have been submitted yet. */
    REVIEW_REQUIRED,

    /** Reviews are not configured / not applicable. */
    NONE,
}
