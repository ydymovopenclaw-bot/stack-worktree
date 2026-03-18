package com.github.ydymovopenclawbot.stackworktree.settings

/**
 * Immutable snapshot of all user-configurable StackTree settings.
 *
 * Defaults reflect safe, user-friendly behaviour out of the box:
 * - Worktree base dir is empty, meaning the project root is used.
 * - Auto-prune and PR nav comment are enabled.
 * - Auto-restack is opt-in (disabled by default, can be destructive).
 * - Polling intervals match [com.github.ydymovopenclawbot.stackworktree.pr.PrStatusPoller.DEFAULT_POLL_INTERVAL_MS]
 *   and [com.github.ydymovopenclawbot.stackworktree.git.AheadBehindCalculator]'s default ttl.
 */
data class StackTreeSettings(
    /** Absolute path for the directory in which new linked worktrees are created.
     *  Empty string means "use project root" (the plugin derives a sibling folder). */
    val worktreeBaseDir: String = "",

    /** When `true`, merged branches' linked worktrees are pruned automatically during sync. */
    val autoPruneOnMerge: Boolean = true,

    /** When `true`, each PR description includes a Markdown stack-navigation table. */
    val prNavigationComment: Boolean = true,

    /** How often (in seconds) the ahead/behind count cache is invalidated and recomputed. */
    val aheadBehindRefreshInterval: Int = 30,

    /** How often (in seconds) the PR-status poller queries the hosting service. */
    val prPollInterval: Int = 60,

    /** When `true`, [com.github.ydymovopenclawbot.stackworktree.ops.OpsLayer.restackAll]
     *  is automatically triggered after a successful [com.github.ydymovopenclawbot.stackworktree.ops.OpsLayer.syncAll]. */
    val autoRestackOnSync: Boolean = false,

    /** Template used when generating branch names for new stack branches.
     *  Supported placeholders: `{stack}`, `{index}`, `{description}`. */
    val branchNamingTemplate: String = "{stack}/{index}-{description}",
)
