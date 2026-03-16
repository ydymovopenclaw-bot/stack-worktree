package com.github.ydymovopenclawbot.stackworktree.ui

/**
 * UI layer — provides IntelliJ Platform UI components (tool windows, notifications, etc.)
 * that surface worktree and PR information to the user.
 */
interface UiLayer {
    /** Refreshes all UI components to reflect the current plugin state. */
    fun refresh()

    /** Shows a non-blocking notification balloon with [message]. */
    fun notify(message: String)
}
