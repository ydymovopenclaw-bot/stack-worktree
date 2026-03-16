package com.github.ydymovopenclawbot.stackworktree.git

import com.github.ydymovopenclawbot.stackworktree.model.CommitEntry
import com.github.ydymovopenclawbot.stackworktree.model.StackNode
import com.intellij.openapi.project.Project
import git4idea.GitUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository

/**
 * Fetches branch detail data from the local git repository using Git4Idea APIs.
 *
 * All methods return sensible defaults (empty lists, zero counts) rather than
 * throwing exceptions when git is unavailable or a command fails.
 */
class BranchDetailService(private val project: Project) {

    private fun repo(): GitRepository? =
        GitUtil.getRepositoryManager(project).repositories.firstOrNull()

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns sorted list of local branch names. */
    fun listBranches(): List<String> =
        repo()?.branches?.localBranches?.map { it.name }?.sorted() ?: emptyList()

    /**
     * Returns worktree entries from `git worktree list --porcelain`.
     * Each entry maps branch name → absolute worktree path.
     */
    fun worktreesByBranch(): Map<String, String> {
        val repo = repo() ?: return emptyMap()
        val handler = GitLineHandler(project, repo.root, GitCommand.WORKTREE)
        handler.addParameters("list", "--porcelain")
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return emptyMap()

        // Porcelain format: blocks separated by blank lines
        // worktree <path>\nHEAD <sha>\nbranch refs/heads/<name>\n\n
        val entries = mutableMapOf<String, String>()
        var currentPath: String? = null
        for (line in result.output) {
            when {
                line.startsWith("worktree ") -> currentPath = line.removePrefix("worktree ").trim()
                line.startsWith("branch refs/heads/") -> {
                    val branch = line.removePrefix("branch refs/heads/").trim()
                    if (currentPath != null) entries[branch] = currentPath
                }
                line.isBlank() -> currentPath = null
            }
        }
        return entries
    }

    /**
     * Builds a [StackNode] for [branchName] by running git commands to resolve
     * the parent branch, ahead/behind counts, and recent commits.
     *
     * Returns null if no git repository is found.
     */
    fun loadNode(branchName: String, worktreePath: String?): StackNode? {
        val repo = repo() ?: return null
        val parent = guessParent(branchName, repo)
        val (ahead, behind) = if (parent != null) aheadBehind(branchName, parent, repo) else (0 to 0)
        val commits = commitLog(branchName, parent, repo)
        return StackNode(
            branchName = branchName,
            parentBranch = parent,
            aheadCount = ahead,
            behindCount = behind,
            commits = commits,
            worktreePath = worktreePath,
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Heuristic: the first of main/master/develop that exists locally and is not
     * the branch itself is assumed to be the parent. Returns null for trunk branches.
     */
    private fun guessParent(branch: String, repo: GitRepository): String? {
        val localNames = repo.branches.localBranches.map { it.name }.toSet()
        return listOf("main", "master", "develop").firstOrNull { it != branch && it in localNames }
    }

    /**
     * Runs `git rev-list --left-right --count <parent>...<branch>` and returns
     * (ahead, behind) as a pair.  Output format is "<behind>\t<ahead>".
     */
    private fun aheadBehind(branch: String, parent: String, repo: GitRepository): Pair<Int, Int> {
        val handler = GitLineHandler(project, repo.root, GitCommand.REV_LIST)
        handler.addParameters("--left-right", "--count", "$parent...$branch")
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return 0 to 0
        val parts = result.output.firstOrNull()?.trim()?.split("\\s+".toRegex()) ?: return 0 to 0
        val behind = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val ahead  = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return ahead to behind
    }

    /**
     * Runs `git log --oneline --max-count=50 <parent>..<branch>` (or just `<branch>`
     * when no parent is known) and returns the entries in log order.
     */
    private fun commitLog(branch: String, parent: String?, repo: GitRepository): List<CommitEntry> {
        val range = if (parent != null) "$parent..$branch" else branch
        val handler = GitLineHandler(project, repo.root, GitCommand.LOG)
        handler.addParameters("--oneline", "--max-count=50", range)
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return emptyList()
        return result.output.map { line ->
            val spaceIdx = line.indexOf(' ')
            if (spaceIdx > 0) CommitEntry(line.substring(0, spaceIdx), line.substring(spaceIdx + 1))
            else CommitEntry(line, "")
        }
    }
}
