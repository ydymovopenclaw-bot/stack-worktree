package com.github.ydymovopenclawbot.stackworktree.ui

/** Display health of a graph node; drives colour in the rendered stack graph. */
enum class NodeHealth {
    /** Branch is up-to-date with its parent (behind == 0). */
    HEALTHY,

    /** Branch has drifted slightly behind its parent (behind in 1..5). */
    NEEDS_REBASE,

    /** Branch has diverged significantly from its parent (behind > 5). */
    CONFLICT,

    /** Ahead/behind data is not yet available for this branch. */
    UNKNOWN,
}
