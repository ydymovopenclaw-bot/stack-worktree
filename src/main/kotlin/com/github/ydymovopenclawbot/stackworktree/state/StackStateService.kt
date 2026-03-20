package com.github.ydymovopenclawbot.stackworktree.state

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * Project-level service that tracks stack-graph relationships between branches.
 *
 * Stores user-defined parent associations and optional worktree paths for each branch.
 * Data survives IDE restarts via IntelliJ's [PersistentStateComponent] mechanism,
 * serialised to `stack-worktree.xml` in the project's `.idea/` directory.
 *
 * All mutations are thread-safe (synchronized on [lock]).
 *
 * Access via `project.getService(StackStateService::class.java)` or the
 * [stackStateService] extension.
 */
@Service(Service.Level.PROJECT)
@State(
    name     = "StackStateService",
    storages = [Storage("stack-worktree.xml")],
)
class StackStateService : PersistentStateComponent<StackStateService.PersistState> {

    /**
     * XML-serialisable state bean.
     *
     * Must use `var` and [MutableMap] (not `val` or read-only collections) so that
     * IntelliJ's XmlSerializer can read back persisted values via Java bean conventions
     * (no-arg constructor + mutable properties).
     */
    class PersistState {
        var branchParents: MutableMap<String, String> = mutableMapOf()  // child  → parent branch
        var worktreePaths: MutableMap<String, String> = mutableMapOf()  // branch → absolute worktree path
        /** Configurable base directory for worktree creation. Null = use project-derived default. */
        var worktreeBasePath: String? = null
    }

    private val lock  = Any()
    private var state = PersistState()

    // ── PersistentStateComponent ──────────────────────────────────────────────

    /** Returns a defensive copy of current state for serialisation. */
    override fun getState(): PersistState = synchronized(lock) {
        PersistState().also {
            it.branchParents    = state.branchParents.toMutableMap()
            it.worktreePaths    = state.worktreePaths.toMutableMap()
            it.worktreeBasePath = state.worktreeBasePath
        }
    }

    /** Replaces in-memory state with the deserialised [newState] on IDE startup. */
    override fun loadState(newState: PersistState): Unit = synchronized(lock) {
        state = newState
    }

    // ── Write API ─────────────────────────────────────────────────────────────

    /**
     * Atomically records that [branch] was created as a child of [parentBranch],
     * and optionally binds it to [worktreePath].
     *
     * Calling this method again with the same [branch] overwrites previous values.
     */
    fun recordBranch(branch: String, parentBranch: String, worktreePath: String?) {
        synchronized(lock) {
            state.branchParents[branch] = parentBranch
            if (worktreePath != null) state.worktreePaths[branch] = worktreePath
        }
    }

    /**
     * Binds [branch] to [path] without touching the parent mapping.
     *
     * Use this when the branch-parent relationship is already recorded and only
     * the worktree path needs to be added (e.g. after [WorktreeOps.createWorktreeForBranch]).
     */
    fun updateWorktreePath(branch: String, path: String): Unit =
        synchronized(lock) { state.worktreePaths[branch] = path }

    /** Removes the worktree binding for [branch]. No-op if no binding exists. */
    fun clearWorktreePath(branch: String): Unit =
        synchronized(lock) { state.worktreePaths.remove(branch) }

    /** Removes all tracked branches and worktree paths. Preserves worktreeBasePath (user preference). */
    fun clearAll(): Unit = synchronized(lock) {
        state.branchParents.clear()
        state.worktreePaths.clear()
    }

    /** Updates the configurable base directory used for default worktree path resolution. */
    fun setWorktreeBasePath(path: String): Unit =
        synchronized(lock) { state.worktreeBasePath = path }

    // ── Read API ──────────────────────────────────────────────────────────────

    /** Returns the parent branch recorded for [branch], or `null` if unknown. */
    fun getParent(branch: String): String? =
        synchronized(lock) { state.branchParents[branch] }

    /**
     * Returns a snapshot of all known branch→parent mappings.
     *
     * The returned map is a defensive copy — subsequent mutations to the service
     * do not affect the returned instance.
     */
    fun getAllParents(): Map<String, String> =
        synchronized(lock) { state.branchParents.toMap() }

    /** Returns the worktree path recorded for [branch], or `null` if not bound. */
    fun getWorktreePath(branch: String): String? =
        synchronized(lock) { state.worktreePaths[branch] }

    /**
     * Returns the configurable worktree base path, or `null` when the project-derived
     * default should be used (`<projectDir>/../<projectName>-worktrees`).
     */
    fun getWorktreeBasePath(): String? =
        synchronized(lock) { state.worktreeBasePath }
}

/** Convenience extension for accessing the service from a [Project]. */
fun Project.stackStateService(): StackStateService =
    getService(StackStateService::class.java)
