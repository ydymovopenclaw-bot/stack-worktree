package com.github.ydymovopenclawbot.stackworktree.state

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Project-level service that tracks stack-graph relationships between branches.
 *
 * Stores user-defined parent associations and optional worktree paths for each branch.
 * All mutations are thread-safe (synchronized on [lock]).
 *
 * Access via `project.getService(StackStateService::class.java)`.
 */
@Service(Service.Level.PROJECT)
class StackStateService {

    private val lock          = Any()
    private val branchParents = mutableMapOf<String, String>()   // child  → parent branch
    private val worktreePaths = mutableMapOf<String, String>()   // branch → absolute worktree path

    // ── Write API ─────────────────────────────────────────────────────────────

    /**
     * Atomically records that [branch] was created as a child of [parentBranch],
     * and optionally binds it to [worktreePath].
     *
     * Calling this method again with the same [branch] overwrites previous values.
     */
    fun recordBranch(branch: String, parentBranch: String, worktreePath: String?) {
        synchronized(lock) {
            branchParents[branch] = parentBranch
            if (worktreePath != null) worktreePaths[branch] = worktreePath
        }
    }

    // ── Read API ──────────────────────────────────────────────────────────────

    /** Returns the parent branch recorded for [branch], or `null` if unknown. */
    fun getParent(branch: String): String? =
        synchronized(lock) { branchParents[branch] }

    /**
     * Returns a snapshot of all known branch→parent mappings.
     *
     * The returned map is a defensive copy — subsequent mutations to the service
     * do not affect the returned instance.
     */
    fun getAllParents(): Map<String, String> =
        synchronized(lock) { branchParents.toMap() }

    /** Returns the worktree path recorded for [branch], or `null` if not bound. */
    fun getWorktreePath(branch: String): String? =
        synchronized(lock) { worktreePaths[branch] }
}

/** Convenience extension for accessing the service from a [Project]. */
fun Project.stackStateService(): StackStateService =
    getService(StackStateService::class.java)
