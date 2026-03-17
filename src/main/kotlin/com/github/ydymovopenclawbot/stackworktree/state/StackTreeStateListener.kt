package com.github.ydymovopenclawbot.stackworktree.state

import com.intellij.util.messages.Topic

/**
 * Message bus topic fired on the project bus whenever the persisted [StackState] changes.
 *
 * Subscribers (e.g. [com.github.ydymovopenclawbot.stackworktree.ui.StacksTabFactory])
 * should refresh their UI on [stateChanged].
 *
 * Published by [com.github.ydymovopenclawbot.stackworktree.actions.NewStackAction] after
 * a successful [StateStorage.write].
 */
fun interface StackTreeStateListener {

    companion object {
        @JvmField
        val TOPIC: Topic<StackTreeStateListener> =
            Topic.create("StackTree State Changed", StackTreeStateListener::class.java)
    }

    /**
     * Called after the stack state has been persisted to git refs.
     *
     * **May be called on any thread** — subscribers must marshal to the EDT before
     * performing any UI work (e.g. via `SwingUtilities.invokeLater` or
     * `ApplicationManager.getApplication().invokeLater`).
     */
    fun stateChanged()
}
