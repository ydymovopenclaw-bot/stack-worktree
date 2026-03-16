package com.github.ydymovopenclawbot.stackworktree.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler

/**
 * Production [GitLayer] implementation that shells out to `git worktree` via
 * [GitLineHandler]. Git4Idea does not expose worktree commands natively, so we
 * construct handlers with `GitCommand("worktree")` and append subcommand params.
 *
 * @param project  The IntelliJ [Project] required by [GitLineHandler].
 * @param gitRoot  The VirtualFile pointing to the root of the git repository.
 */
class GitLayerImpl(
    private val project: Project,
    private val gitRoot: VirtualFile,
) : GitLayer {

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Runs a `git worktree <params>` command, throwing on non-zero exit. */
    private fun runOrThrow(vararg params: String): List<String> {
        val result = runRaw(*params)
        if (!result.success()) throw WorktreeCommandException(result.errorOutputAsJoinedString)
        return result.output
    }

    /** Runs a `git worktree <params>` command and returns the raw result. */
    private fun runRaw(vararg params: String) =
        Git.getInstance().runCommand(
            GitLineHandler(project, gitRoot, GitCommand.WORKTREE).also {
                it.addParameters(*params)
            }
        )

    // -------------------------------------------------------------------------
    // Porcelain parser
    // -------------------------------------------------------------------------

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
