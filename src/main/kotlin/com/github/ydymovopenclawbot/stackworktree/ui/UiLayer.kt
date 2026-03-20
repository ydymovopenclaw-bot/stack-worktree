package com.github.ydymovopenclawbot.stackworktree.ui

/**
 * UI layer — provides IntelliJ Platform UI components (tool windows, notifications, etc.)
 * that surface worktree and PR information to the user.
 */
interface UiLayer {
    /** Refreshes all UI components to reflect the current plugin state. */
    fun refresh()

    /** Shows a non-blocking INFO balloon with [message]. */
    fun notify(message: String)

    /**
     * Shows a non-blocking ERROR balloon with [message].
     *
     * When [detail] is non-null (e.g. a stack trace or full git error output), a
     * **"Show Details"** action is added to the balloon so the user can inspect it
     * without opening `idea.log`.
     */
    fun notifyError(message: String, detail: String? = null)
}
