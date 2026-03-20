package com.github.ydymovopenclawbot.stackworktree.git

import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.GitUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.rebase.GitRebaser
import git4idea.update.GitUpdateResult

/**
 * Production [GitLayer] implementation registered as a project-level service.
 *
 * The repository root is resolved lazily from [GitUtil.getRepositoryManager] on each
 * access, so callers do not need to supply it at construction time. This makes the class
 * injectable via `project.service<GitLayer>()`.
 *
 * Worktree commands shell out to `git worktree` via [GitLineHandler] (Git4Idea does not
 * expose worktree commands natively). [listLocalBranches] uses the in-memory branch index
 * and never forks a process.
 *
 * @param project The IntelliJ [Project] used by [GitLineHandler] and
 *   [GitUtil.getRepositoryManager].
 */
@Service(Service.Level.PROJECT)
class GitLayerImpl(
    private val project: Project,
) : GitLayer {

    private var rootOverride: VirtualFile? = null

    // ── Repository root ───────────────────────────────────────────────────────

    /**
     * Resolves the repository root. Uses [rootOverride] when provided (tests), otherwise
     * falls back to IntelliJ's in-memory index. Returns `null` when no git repository
     * is open (e.g. during IDE startup).
     */
    private val gitRoot: VirtualFile?
        get() = rootOverride
            ?: GitUtil.getRepositoryManager(project).repositories.firstOrNull()?.root

    /**
     * Returns [gitRoot], throwing [WorktreeCommandException] when no repository is found.
     * Used by all worktree operations that require a concrete root path.
     */
    private fun requireRoot(): VirtualFile =
        gitRoot ?: throw WorktreeCommandException(
            "No git repository found for project '${project.name}'"
        )

    // ── Public API ────────────────────────────────────────────────────────────

    override fun worktreeAdd(path: String, branch: String): Worktree {
        val result = runRaw("add", path, branch)
        if (!result.success()) {
            val err = result.errorOutputAsJoinedString
            if (err.contains("already exists", ignoreCase = true)) {
                throw WorktreeAlreadyExistsException(path)
            }
            throw WorktreeCommandException(err)
        }
        val canonical = java.io.File(path).canonicalPath
        return worktreeList().firstOrNull { java.io.File(it.path).canonicalPath == canonical }
            ?: throw WorktreeCommandException("worktree add succeeded but path not found in list: $path")
    }

    override fun worktreeRemove(path: String, force: Boolean) {
        val result = if (force) runRaw("remove", "--force", path) else runRaw("remove", path)
        if (!result.success()) {
            val err = result.errorOutputAsJoinedString
            when {
                err.contains("is locked", ignoreCase = true) ->
                    throw WorktreeIsLockedException(path)
                err.contains("not a working tree", ignoreCase = true) ||
                        err.contains("is not a registered worktree", ignoreCase = true) ->
                    throw WorktreeNotFoundException(path)
                else -> throw WorktreeCommandException(err)
            }
        }
    }

    override fun worktreeList(): List<Worktree> {
        val lines = runOrThrow("list", "--porcelain")
        return parsePorcelain(lines)
    }

    // Delegate to the companion so the implementation is accessible from unit tests
    // without requiring a live IntelliJ project.
    private fun parsePorcelain(lines: List<String>): List<Worktree> =
        Companion.parsePorcelain(lines)

    override fun worktreePrune() {
        runOrThrow("prune")
    }

    override fun listLocalBranches(): List<String> {
        val repo = GitUtil.getRepositoryManager(project).repositories.firstOrNull()
            ?: return emptyList()
        return repo.branches.localBranches.map { it.name }.sorted()
    }

    override fun resolveCommit(branchOrRef: String): String {
        val handler = GitLineHandler(project, requireRoot(), GitCommand.REV_PARSE).also {
            it.addParameters("--verify", branchOrRef)
        }
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) throw WorktreeCommandException(
            "Failed to resolve '$branchOrRef': ${result.errorOutputAsJoinedString}"
        )
        return result.output.firstOrNull()?.trim()
            ?: throw WorktreeCommandException("rev-parse returned no output for '$branchOrRef'")
    }

    override fun branchExists(branchName: String): Boolean {
        val handler = GitLineHandler(project, requireRoot(), GitCommand.BRANCH).also {
            it.addParameters("--list", branchName)
        }
        val result = Git.getInstance().runCommand(handler)
        // `git branch --list <name>` outputs "  <name>" or "* <name>" when found, empty when not.
        return result.success() && result.output.any { line ->
            line.trim().trimStart('*').trim() == branchName
        }
    }

    override fun resetBranch(branchName: String, toCommit: String) {
        val handler = GitLineHandler(project, requireRoot(), GitCommand.BRANCH).also {
            it.addParameters("-f", branchName, toCommit)
        }
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) throw WorktreeCommandException(
            "Failed to reset branch '$branchName' to '$toCommit': ${result.errorOutputAsJoinedString}"
        )
    }

    override fun createBranch(branchName: String, baseBranch: String) {
        val handler = GitLineHandler(project, requireRoot(), GitCommand.BRANCH).also {
            it.addParameters(branchName, baseBranch)
        }
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) throw WorktreeCommandException(
            "Failed to create branch '$branchName' from '$baseBranch': ${result.errorOutputAsJoinedString}"
        )
    }

    override fun deleteBranch(branchName: String) {
        val handler = GitLineHandler(project, requireRoot(), GitCommand.BRANCH).also {
            it.addParameters("-D", branchName)
        }
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) throw WorktreeCommandException(
            "Failed to delete branch '$branchName': ${result.errorOutputAsJoinedString}"
        )
    }

    /**
     * Runs `git rebase --onto <newBase> <upstream> <branch>` via [GitRebaser].
     *
     * Delegating to [GitRebaser] is critical for correct IDE integration: it loops over
     * every conflicting commit in turn, opening IntelliJ's three-pane merge dialog for each
     * one, and only advances to the next commit once the current conflicts are fully resolved.
     * A manual [git4idea.merge.GitConflictResolver] approach only handles one round of
     * conflict resolution and then calls `--continue`, which aborts the entire rebase if
     * a subsequent commit also has conflicts.
     *
     * The current thread's [com.intellij.openapi.progress.ProgressIndicator] is used when
     * available (e.g. when called from a [com.intellij.openapi.progress.Task.Backgroundable]);
     * an [EmptyProgressIndicator] is used as a safe fallback otherwise.
     */
    override fun rebaseOnto(branch: String, newBase: String, upstream: String): RebaseResult {
        val indicator = ProgressManager.getInstance().progressIndicator ?: EmptyProgressIndicator()
        val rebaser = GitRebaser(project, Git.getInstance(), indicator)
        val result = rebaser.rebase(requireRoot(), listOf("--onto", newBase, upstream, branch))
        return when (result) {
            GitUpdateResult.SUCCESS,
            GitUpdateResult.SUCCESS_WITH_RESOLVED_CONFLICTS,
            GitUpdateResult.NOTHING_TO_UPDATE -> RebaseResult.Success
            GitUpdateResult.CANCEL ->
                RebaseResult.Aborted("User cancelled rebase of '$branch' onto '$newBase'")
            else ->
                RebaseResult.Aborted("Rebase of '$branch' onto '$newBase' did not complete: $result")
        }
    }

    override fun aheadBehind(branch: String, parent: String): AheadBehind {
        val handler = GitLineHandler(project, requireRoot(), GitCommand.REV_LIST).also {
            it.addParameters("--left-right", "--count", "$parent...$branch")
        }
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) throw WorktreeCommandException(result.errorOutputAsJoinedString)
        // Output is "<behind>\t<ahead>" (left=parent side, right=branch side)
        val line = result.output.firstOrNull()?.trim()
            ?: throw WorktreeCommandException("rev-list returned no output for $parent...$branch")
        val parts = line.split("\t")
        if (parts.size != 2) throw WorktreeCommandException("unexpected rev-list output: $line")
        return AheadBehind(ahead = parts[1].toInt(), behind = parts[0].toInt())
    }

    override fun fetchRemote(remote: String) {
        val handler = GitLineHandler(project, requireRoot(), GitCommand.FETCH).also {
            it.addParameters(remote)
        }
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) throw WorktreeCommandException(
            "Failed to fetch remote '$remote': ${result.errorOutputAsJoinedString}"
        )
    }

    override fun getMergedRemoteBranches(remote: String, trunkBranch: String): Set<String> {
        val handler = GitLineHandler(project, requireRoot(), GitCommand.BRANCH).also {
            it.addParameters("-r", "--merged", "$remote/$trunkBranch")
        }
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) throw WorktreeCommandException(
            "Failed to list merged remote branches: ${result.errorOutputAsJoinedString}"
        )
        // Each line looks like: "  origin/feature-a" or "  origin/HEAD -> origin/main"
        val prefix = "$remote/"
        return result.output
            .map { it.trim() }
            .filter { it.startsWith(prefix) && !it.contains("->") }
            .map { it.removePrefix(prefix) }
            .filter { it.isNotEmpty() && it != trunkBranch }
            .toSet()
    }

    override fun push(branch: String, remote: String, forceWithLease: Boolean) {
        val handler = GitLineHandler(project, requireRoot(), GitCommand.PUSH).also {
            it.addParameters("--set-upstream")
            if (forceWithLease) it.addParameters("--force-with-lease")
            it.addParameters(remote, branch)
        }
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) throw WorktreeCommandException(
            "Failed to push '$branch' to '$remote': ${result.errorOutputAsJoinedString}"
        )
    }

    override fun checkoutNewBranch(branch: String) {
        val result = Git.getInstance().runCommand(
            GitLineHandler(project, requireRoot(), GitCommand.CHECKOUT).also {
                it.addParameters("-b", branch)
            }
        )
        if (!result.success()) {
            val err = result.errorOutputAsJoinedString
            throw BranchOperationException(
                if (err.isBlank()) "git checkout -b $branch failed" else err
            )
        }
    }

    override fun stageAll() {
        val result = Git.getInstance().runCommand(
            GitLineHandler(project, requireRoot(), GitCommand.ADD).also {
                it.addParameters("-A")
            }
        )
        if (!result.success()) {
            val err = result.errorOutputAsJoinedString
            throw BranchOperationException(
                if (err.isBlank()) "git add -A failed" else err
            )
        }
    }

    override fun commit(message: String) {
        val result = Git.getInstance().runCommand(
            GitLineHandler(project, requireRoot(), GitCommand.COMMIT).also {
                it.addParameters("-m", message)
            }
        )
        if (!result.success()) {
            val err = result.errorOutputAsJoinedString
            throw BranchOperationException(
                if (err.isBlank()) "git commit failed" else err
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Runs a `git worktree <params>` command, throwing on non-zero exit. */
    private fun runOrThrow(vararg params: String): List<String> {
        val result = runRaw(*params)
        if (!result.success()) throw WorktreeCommandException(result.errorOutputAsJoinedString)
        return result.output
    }

    /** Runs a `git worktree <params>` command and returns the raw result. */
    private fun runRaw(vararg params: String) =
        Git.getInstance().runCommand(
            GitLineHandler(project, requireRoot(), GitCommand.WORKTREE).also {
                it.addParameters(*params)
            }
        )

    // ── Porcelain parser ──────────────────────────────────────────────────────

    companion object {

        fun withRoot(project: Project, root: VirtualFile): GitLayerImpl =
            GitLayerImpl(project).apply { rootOverride = root }

        /**
         * Parses the output of `git worktree list --porcelain`.
         *
         * Each worktree block looks like (fields separated by blank lines):
         * ```
         * worktree /abs/path
         * HEAD <sha>
         * branch refs/heads/<name>   <- or "detached" / "bare"
         * locked [optional reason]   <- optional
         * ```
         *
         * Exposed as `internal` so it can be tested from [PorcelainParserTest]
         * without requiring a live IntelliJ project.
         */
        internal fun parsePorcelain(lines: List<String>): List<Worktree> {
            val worktrees = mutableListOf<Worktree>()
            var path = ""; var head = ""; var branch = ""; var locked = false

            // Append a sentinel blank line so the last block is always flushed.
            for (line in lines + "") {
                when {
                    line.startsWith("worktree ") -> {
                        path = line.removePrefix("worktree ")
                        head = ""; branch = ""; locked = false
                    }
                    line.startsWith("HEAD ")     -> head   = line.removePrefix("HEAD ")
                    // Strip the well-known heads prefix; fall back to the full ref for
                    // anything outside refs/heads/ (e.g. refs/tags/ from detached-style adds).
                    line.startsWith("branch ")   -> branch = line.removePrefix("branch ")
                        .removePrefix("refs/heads/")
                    line == "detached"           -> branch = ""
                    // Bare worktrees have no checked-out branch; treat them identically to
                    // a detached HEAD for display purposes (branch stays empty).
                    line == "bare"               -> { /* bare worktree — no branch, leave branch empty */ }
                    line.startsWith("locked")    -> locked = true
                    line.isBlank() && path.isNotEmpty() -> {
                        worktrees += Worktree(
                            path     = path,
                            branch   = branch,
                            head     = head,
                            isLocked = locked,
                            isMain   = worktrees.isEmpty(),
                        )
                        path = ""
                    }
                }
            }
            return worktrees
        }
    }
}
