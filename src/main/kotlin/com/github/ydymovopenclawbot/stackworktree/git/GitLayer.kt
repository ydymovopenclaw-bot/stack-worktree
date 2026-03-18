package com.github.ydymovopenclawbot.stackworktree.git

/**
 * Git layer — responsible for all git worktree operations.
 */
interface GitLayer {
    /** Creates a new worktree at [path] checked out at [branch]. */
    fun worktreeAdd(path: String, branch: String): Worktree

    /** Removes the worktree at [path]. */
    fun worktreeRemove(path: String)

    /** Returns the list of current git worktrees for the repository. */
    fun worktreeList(): List<Worktree>

    /** Prunes stale worktree administrative files. */
    fun worktreePrune()

    /** Returns all local branch names, sorted alphabetically. */
    fun listLocalBranches(): List<String>

    /**
     * Returns how many commits [branch] is ahead of and behind [parent].
     *
     * Implemented via `git rev-list --left-right --count <parent>...<branch>`.
     * - ahead = commits in [branch] not in [parent]
     * - behind = commits in [parent] not in [branch]
     */
    fun aheadBehind(branch: String, parent: String): AheadBehind

    /**
     * Creates a new local branch [branchName] pointing at the current tip of [baseBranch].
     * Throws [WorktreeCommandException] if the branch already exists or [baseBranch] is unknown.
     */
    fun createBranch(branchName: String, baseBranch: String)

    /**
     * Force-deletes the local branch [branchName] (`git branch -D`).
     * Throws [WorktreeCommandException] if the branch does not exist.
     */
    fun deleteBranch(branchName: String)

    /**
     * Returns the full SHA-1 commit hash that [branchOrRef] currently points to.
     * Throws [WorktreeCommandException] if [branchOrRef] is unknown.
     */
    fun resolveCommit(branchOrRef: String): String

    /**
     * Returns `true` if a local branch named [branchName] exists in the repository.
     */
    fun branchExists(branchName: String): Boolean

    /**
     * Force-resets the tip of local branch [branchName] to [toCommit] (`git branch -f`).
     * Used for best-effort rollback after a failed rebase cascade.
     * Throws [WorktreeCommandException] if [branchName] does not exist.
     */
    fun resetBranch(branchName: String, toCommit: String)

    /**
     * Rebases [branch] onto [newBase], replaying only the commits that are reachable
     * from [branch] but not from [upstream] (the 3-argument `--onto` form):
     *
     * ```
     * git rebase --onto <newBase> <upstream> <branch>
     * ```
     *
     * When conflicts are detected, IntelliJ's merge dialog is opened automatically.
     * The caller blocks until the user either resolves all conflicts and the rebase
     * continues, or aborts (in which case [RebaseResult.Aborted] is returned and the
     * repository is left in its pre-rebase state).
     *
     * @param branch   The branch to rebase (its tip will be moved).
     * @param newBase  The new base commit/branch to rebase onto.
     * @param upstream The old upstream; commits reachable from [upstream] are excluded.
     */
    fun rebaseOnto(branch: String, newBase: String, upstream: String): RebaseResult

    /**
     * Fetches the given [remote] (`git fetch <remote>`).
     *
     * @throws WorktreeCommandException if the fetch fails (e.g. no network, unknown remote).
     */
    fun fetchRemote(remote: String)

    /**
     * Returns the set of **local** branch names that have been merged into
     * `<remote>/<trunkBranch>` on the remote.
     *
     * Runs `git branch -r --merged <remote>/<trunkBranch>`, strips the `<remote>/` prefix
     * from each line, and excludes [trunkBranch] itself.
     *
     * @throws WorktreeCommandException if the git command fails.
     */
    fun getMergedRemoteBranches(remote: String, trunkBranch: String): Set<String>

    /**
     * Creates a new local branch from the current HEAD (`git checkout -b <branch>`).
     *
     * @throws BranchOperationException if the branch already exists or the command fails.
     */
    fun checkoutNewBranch(branch: String)

    /**
     * Stages all changes in the working tree (`git add -A`).
     *
     * @throws BranchOperationException if the command fails.
     */
    fun stageAll()

    /**
     * Creates a commit with [message] (`git commit -m <message>`).
     *
     * Requires at least one staged change; throws if there is nothing to commit.
     *
     * @throws BranchOperationException if the command fails.
     */
    fun commit(message: String)
}

/** Result of a [GitLayer.rebaseOnto] call. */
sealed class RebaseResult {
    /** The rebase completed without conflicts. */
    object Success : RebaseResult()

    /**
     * The rebase was aborted — either the user dismissed the conflict dialog or an
     * unresolvable error occurred.  The repository is in its original pre-rebase state.
     */
    data class Aborted(val reason: String) : RebaseResult()
}

/** Thrown when a branch-level git operation (checkout, stage, commit) fails. */
class BranchOperationException(message: String) : RuntimeException(message)

/**
 * Descriptor for a git worktree.
 *
 * @param path     Absolute path to the worktree directory.
 * @param branch   Checked-out branch name (short form), or empty string for a detached HEAD.
 * @param head     Full SHA of the current HEAD commit.
 * @param isLocked Whether the worktree is locked (prevents pruning / removal).
 * @param isMain   `true` for the primary (first) worktree — the one that owns the `.git`
 *                 directory.  All linked worktrees have `isMain == false`.
 */
data class Worktree(
    val path: String,
    val branch: String,
    val head: String,
    val isLocked: Boolean,
    val isMain: Boolean = false,
)

/** How many commits a branch is ahead of and behind its parent. */
data class AheadBehind(val ahead: Int, val behind: Int)
