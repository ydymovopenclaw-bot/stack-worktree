package com.github.ydymovopenclawbot.stackworktree.pr

/**
 * A point-in-time snapshot of PR and CI badge data for every tracked branch.
 *
 * Produced by [PrStatusPoller] and consumed by the graph UI layer.  Each entry
 * maps a branch name to its [PrStatus], or `null` when no open PR exists for
 * that branch.
 *
 * @param statuses    Map of branch name → [PrStatus], with `null` meaning "no PR".
 * @param lastUpdated Epoch-millis timestamp of the last successful poll cycle
 *                    (zero when the snapshot has never been populated).
 */
data class PrBadgeSnapshot(
    val statuses: Map<String, PrStatus?> = emptyMap(),
    val lastUpdated: Long = 0L,
)
