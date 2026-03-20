package com.github.ydymovopenclawbot.stackworktree.testutil

import com.github.ydymovopenclawbot.stackworktree.git.AheadBehind
import com.github.ydymovopenclawbot.stackworktree.git.GitLayer
import com.github.ydymovopenclawbot.stackworktree.git.RebaseResult
import com.github.ydymovopenclawbot.stackworktree.git.Worktree

/**
 * Shared [GitLayer] fake for unit tests.
 *
 * All methods have configurable responses via public properties (lambdas or mutable maps).
 * Unset operations return safe defaults (empty lists, zero counts, no-op).
 */
open class FakeGitLayer(
    private val worktreeListResult: List<Worktree> = emptyList(),
    private val worktreeAddProvider: (path: String, branch: String) -> Worktree =
        { path, branch -> Worktree(path, branch, "abc123", false) },
) : GitLayer {

    val aheadBehindResponses = mutableMapOf<String, AheadBehind>()
    var aheadBehindCallCount = 0

    val addCalls = mutableListOf<Pair<String, String>>()
    val removeCalls = mutableListOf<String>()

    var branchExistsResult: (String) -> Boolean = { false }
    var resolveCommitResult: (String) -> String = { "0000000000000000000000000000000000000000" }
    var rebaseOntoResult: (String, String, String) -> RebaseResult = { _, _, _ -> RebaseResult.Success }
    var fetchRemoteResult: (String) -> Unit = {}
    var mergedRemoteBranchesResult: (String, String) -> Set<String> = { _, _ -> emptySet() }
    var localBranches: List<String> = emptyList()

    override fun aheadBehind(branch: String, parent: String): AheadBehind {
        aheadBehindCallCount++
        return aheadBehindResponses["$branch:$parent"] ?: AheadBehind(0, 0)
    }

    override fun worktreeAdd(path: String, branch: String): Worktree {
        addCalls += path to branch
        return worktreeAddProvider(path, branch)
    }

    override fun worktreeRemove(path: String, force: Boolean) { removeCalls += path }
    override fun worktreeList(): List<Worktree> = worktreeListResult
    override fun worktreePrune() = Unit
    override fun listLocalBranches(): List<String> = localBranches
    override fun createBranch(branchName: String, baseBranch: String) = Unit
    val deleteBranchCalls = mutableListOf<String>()
    override fun deleteBranch(branchName: String) { deleteBranchCalls += branchName }
    override fun resolveCommit(branchOrRef: String): String = resolveCommitResult(branchOrRef)
    override fun branchExists(branchName: String): Boolean = branchExistsResult(branchName)
    override fun resetBranch(branchName: String, toCommit: String) = Unit
    override fun rebaseOnto(branch: String, newBase: String, upstream: String): RebaseResult =
        rebaseOntoResult(branch, newBase, upstream)

    override fun fetchRemote(remote: String) = fetchRemoteResult(remote)
    override fun getMergedRemoteBranches(remote: String, trunkBranch: String): Set<String> =
        mergedRemoteBranchesResult(remote, trunkBranch)

    override fun push(branch: String, remote: String, forceWithLease: Boolean) = Unit
    override fun checkoutNewBranch(branch: String) = Unit
    override fun stageAll() = Unit
    override fun commit(message: String) = Unit
}
