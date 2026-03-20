package com.github.ydymovopenclawbot.stackworktree.state

/**
 * Base class for all failures in the StackTree state-persistence layer.
 *
 * Sealed so callers can exhaustively `when`-match without a catch-all arm.
 */
sealed class StateException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

/**
 * Thrown when the JSON blob stored in `refs/stacktree/state` cannot be deserialized.
 *
 * This usually means the blob was written by an incompatible future version of the plugin
 * or was corrupted externally.  Callers should handle this by resetting to a safe default
 * [StackState] and notifying the user via
 * [com.github.ydymovopenclawbot.stackworktree.ui.StackTreeNotifier.error].
 */
class StateCorruptedException(detail: String, cause: Throwable? = null) :
    StateException("Stack state is corrupted: $detail", cause)
