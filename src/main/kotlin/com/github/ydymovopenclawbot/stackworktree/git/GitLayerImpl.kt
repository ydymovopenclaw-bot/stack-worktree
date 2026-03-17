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
class GitLayerImpl(private val project: Project) : GitLayer {

    // ── Repository root ───────────────────────────────────────────────────────

    /**
     * Resolves the repository root on every access via IntelliJ's in-memory index.
     * Returns `null` when no git repository is open (e.g. during IDE startup).
     */
    private val gitRoot: VirtualFile?
        get() = GitUtil.getRepositoryManager(project).repositories.firstOrNull()?.root

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

    override fun worktreeRemove(path: String) {
        val result = runRaw("remove", path)
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

    override fun worktreePrune() {
        runOrThrow("prune")
    }

    override fun listLocalBranches(): List<String> {
        val root = gitRoot ?: return emptyList()
        val repoManager = GitUtil.getRepositoryManager(project)
        val repo = repoManager.getRepositoryForRoot(root)
            ?: repoManager.repositories.firstOrNull()
        return repo?.branches?.localBranches?.map { it.name }?.sorted() ?: emptyList()
    }

    override fun resolveCommit(branchOrRef: String): String {
        val handler = GitLineHandler(project, gitRoot, GitCommand.REV_PARSE).also {
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
        val handler = GitLineHandler(project, gitRoot, GitCommand.BRANCH).also {
            it.addParameters("--list", branchName)
        }
        val result = Git.getInstance().runCommand(handler)
        // `git branch --list <name>` outputs "  <name>" or "* <name>" when found, empty when not.
        return result.success() && result.output.any { line ->
            line.trim().trimStart('*').trim() == branchName
        }
    }

    override fun resetBranch(branchName: String, toCommit: String) {
        val handler = GitLineHandler(project, gitRoot, GitCommand.BRANCH).also {
            it.addParameters("-f", branchName, toCommit)
        }
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) throw WorktreeCommandException(
            "Failed to reset branch '$branchName' to '$toCommit': ${result.errorOutputAsJoinedString}"
        )
    }

    override fun createBranch(branchName: String, baseBranch: String) {
        val handler = GitLineHandler(project, gitRoot, GitCommand.BRANCH).also {
            it.addParameters(branchName, baseBranch)
        }
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) throw WorktreeCommandException(
            "Failed to create branch '$branchName' from '$baseBranch': ${result.errorOutputAsJoinedString}"
        )
    }

    override fun deleteBranch(branchName: String) {
        val handler = GitLineHandler(project, gitRoot, GitCommand.BRANCH).also {
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
        val result = rebaser.rebase(gitRoot, listOf("--onto", newBase, upstream, branch))
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

    override fun checkoutNewBranch(branch: String) {
        val result = Git.getInstance().runCommand(
            GitLineHandler(project, gitRoot, GitCommand.CHECKOUT).also {
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
            GitLineHandler(project, gitRoot, GitCommand.ADD).also {
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
            GitLineHandler(project, gitRoot, GitCommand.COMMIT).also {
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

    /**
     * Parses the output of `git worktree list --porcelain`.
     *
     * Each worktree block looks like (fields separated by blank lines):
     * ```
     * worktree /abs/path
     * HEAD <sha>
     * branch refs/heads/<name>   <- or "detached"
     * locked [optional reason]   <- optional
     * ```
     */
    private fun parsePorcelain(lines: List<String>): List<Worktree> {
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
                line.startsWith("branch ")  -> branch = line.removePrefix("branch ")
                    .removePrefix("refs/heads/")
                line == "detached"          -> branch = ""
                line.startsWith("locked")   -> locked = true
                line.isBlank() && path.isNotEmpty() -> {
                    worktrees += Worktree(path, branch, head, locked)
                    path = ""
                }
            }
        }
        return worktrees
    }
}
