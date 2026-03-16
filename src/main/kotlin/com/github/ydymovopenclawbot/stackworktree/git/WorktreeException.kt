package com.github.ydymovopenclawbot.stackworktree.git

/** Base class for all worktree-related failures. */
sealed class WorktreeException(msg: String) : Exception(msg)

/** Thrown when attempting to create a worktree at a path that already holds one. */
class WorktreeAlreadyExistsException(path: String) :
    WorktreeException("Worktree already exists at: $path")

/** Thrown when the target path is not a known worktree (e.g. during removal). */
class WorktreeNotFoundException(path: String) :
    WorktreeException("No worktree found at: $path")

/** Thrown when trying to remove a locked worktree without the force flag. */
class WorktreeIsLockedException(path: String) :
    WorktreeException("Worktree is locked: $path")

/** Thrown for any other non-zero git worktree exit, with the raw stderr included. */
class WorktreeCommandException(detail: String) : WorktreeException(detail)
