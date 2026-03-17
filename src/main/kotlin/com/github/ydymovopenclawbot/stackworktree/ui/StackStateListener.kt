package com.github.ydymovopenclawbot.stackworktree.ui

import com.intellij.util.messages.Topic

/**
 * Listener notified whenever the tracked-branch state changes (track or untrack operations).
 *
 * Subscribers (e.g. [StacksTabFactory]) use this to refresh the graph view without
 * polling or coupling directly to the ops layer.
 */
interface StackStateListener {
    fun stateChanged()
}

/** Project-level message bus topic for stack state changes. */
val STACK_STATE_TOPIC: Topic<StackStateListener> =
    Topic.create("StackStateChanged", StackStateListener::class.java)
