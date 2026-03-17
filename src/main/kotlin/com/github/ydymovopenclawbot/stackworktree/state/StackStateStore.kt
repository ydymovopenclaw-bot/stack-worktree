package com.github.ydymovopenclawbot.stackworktree.state

/**
 * Minimal persistence contract used by [com.github.ydymovopenclawbot.stackworktree.ops.OpsLayerImpl].
 *
 * Extracting this interface from [StateStorage] allows unit tests to inject an
 * in-memory fake without requiring a live git repository.
 */
interface StackStateStore {
    /** Reads the current [StackState], or `null` if no state has been persisted yet. */
    fun read(): StackState?

    /** Persists [state], replacing any previously stored state. */
    fun write(state: StackState)
}
