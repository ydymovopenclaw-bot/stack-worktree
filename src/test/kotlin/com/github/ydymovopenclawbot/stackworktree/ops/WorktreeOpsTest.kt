package com.github.ydymovopenclawbot.stackworktree.ops

import com.github.ydymovopenclawbot.stackworktree.git.AheadBehind
import com.github.ydymovopenclawbot.stackworktree.git.GitLayer
import com.github.ydymovopenclawbot.stackworktree.git.RebaseResult
import com.github.ydymovopenclawbot.stackworktree.git.Worktree
import com.github.ydymovopenclawbot.stackworktree.git.WorktreeAlreadyExistsException
import com.github.ydymovopenclawbot.stackworktree.git.WorktreeNotFoundException
import com.github.ydymovopenclawbot.stackworktree.state.BranchNode
import com.github.ydymovopenclawbot.stackworktree.state.RepoConfig
import com.github.ydymovopenclawbot.stackworktree.state.StackState
import com.github.ydymovopenclawbot.stackworktree.state.StackStateService
import com.github.ydymovopenclawbot.stackworktree.state.StackStateStore
import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy

/**
 * Unit tests for [WorktreeOps] using hand-written fakes.
 *
 * No IntelliJ Platform runtime is required: [gitLayerOverride], [stateStoreOverride],
 * and [stateServiceOverride] are injected directly, so the [Project] parameter is
 * never dereferenced (except in [defaultWorktreePath] tests that exercise the
 * project-derived fallback path).
 */
class WorktreeOpsTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    /**
     * Records every [worktreeAdd] and [worktreeRemove] call; returns configurable
     * results. All other [GitLayer] methods throw [UnsupportedOperationException].
     */
    private class FakeGitLayer(
        private val worktreeListResult: List<Worktree> = emptyList(),
        private val worktreeAddProvider: (path: String, branch: String) -> Worktree =
            { path, branch -> Worktree(path, branch, "abc123", false) },
    ) : GitLayer {

        val addCalls    = mutableListOf<Pair<String, String>>() // path → branch
        val removeCalls = mutableListOf<String>()               // paths removed

        override fun worktreeAdd(path: String, branch: String): Worktree {
            addCalls += path to branch
            return worktreeAddProvider(path, branch)
        }

        override fun worktreeRemove(path: String) { removeCalls += path }
        override fun worktreeList(): List<Worktree> = worktreeListResult

        override fun fetchRemote(remote: String)                                          = unsupported()
        override fun getMergedRemoteBranches(r: String, t: String): Set<String>        = unsupported()
        override fun worktreePrune()                                                   = unsupported()
        override fun listLocalBranches(): List<String>                                 = unsupported()
        override fun aheadBehind(branch: String, parent: String): AheadBehind         = unsupported()
        override fun createBranch(branchName: String, baseBranch: String)              = unsupported()
        override fun deleteBranch(branchName: String)                                  = unsupported()
        override fun resolveCommit(branchOrRef: String): String                        = unsupported()
        override fun branchExists(branchName: String): Boolean                         = unsupported()
        override fun resetBranch(branchName: String, toCommit: String)                = unsupported()
        override fun rebaseOnto(b: String, nb: String, u: String): RebaseResult       = unsupported()
        override fun push(branch: String, remote: String, forceWithLease: Boolean)     = unsupported()
        override fun checkoutNewBranch(branch: String)                                 = unsupported()
        override fun stageAll()                                                        = unsupported()
        override fun commit(message: String)                                           = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException()
    }

    /** In-memory [StackStateStore] that records every [write] call. */
    private class FakeStateStore(initial: StackState? = null) : StackStateStore {
        private var stored: StackState? = initial
        val writeHistory = mutableListOf<StackState>()

        override fun read(): StackState? = stored
        override fun write(state: StackState) { stored = state; writeHistory += state }
    }

    /**
     * [Project] proxy that throws on any method — safe when [WorktreeOps] is fully
     * wired with test doubles and never accesses [project.basePath] / [project.name].
     */
    private val noProject: Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        throw UnsupportedOperationException("Unexpected call to Project.${method.name} in unit test")
    } as Project

    /**
     * [Project] stub that returns [basePath] and [projectName] — used only for
     * [WorktreeOps.defaultWorktreePath] tests that exercise the project-derived fallback.
     */
    private fun stubProject(basePath: String, projectName: String): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> basePath
                "getName"     -> projectName
                else -> throw UnsupportedOperationException("Unexpected Project.${method.name}")
            }
        } as Project

    // ── Fixture helpers ───────────────────────────────────────────────────────

    /** main → [branch] (with optional [worktreePath]). */
    private fun singleBranchState(branch: String, worktreePath: String? = null) = StackState(
        repoConfig = RepoConfig(trunk = "main", remote = "origin"),
        branches = mapOf(
            "main"  to BranchNode("main", parent = null, children = listOf(branch)),
            branch  to BranchNode(branch, parent = "main", worktreePath = worktreePath),
        ),
    )

    // =========================================================================
    // createWorktreeForBranch
    // =========================================================================

    @Test
    fun `createWorktreeForBranch - calls git layer with the supplied path and branch`() {
        val git = FakeGitLayer()
        val ops = WorktreeOps(noProject, git, FakeStateStore(singleBranchState("feat")), StackStateService())

        ops.createWorktreeForBranch("feat", "/tmp/feat-wt")

        assertEquals(listOf("/tmp/feat-wt" to "feat"), git.addCalls)
    }

    @Test
    fun `createWorktreeForBranch - persists path in state service`() {
        val svc = StackStateService()
        val ops = WorktreeOps(noProject, FakeGitLayer(), FakeStateStore(singleBranchState("feat")), svc)

        ops.createWorktreeForBranch("feat", "/tmp/feat-wt")

        assertEquals("/tmp/feat-wt", svc.getWorktreePath("feat"))
    }

    @Test
    fun `createWorktreeForBranch - persists path in state store`() {
        val store = FakeStateStore(singleBranchState("feat"))
        val ops   = WorktreeOps(noProject, FakeGitLayer(), store, StackStateService())

        ops.createWorktreeForBranch("feat", "/tmp/feat-wt")

        val saved = store.writeHistory.single()
        assertEquals("/tmp/feat-wt", saved.branches["feat"]!!.worktreePath)
    }

    @Test
    fun `createWorktreeForBranch - returns the Worktree descriptor from git layer`() {
        val expected = Worktree("/tmp/feat-wt", "feat", "deadbeef", false)
        val git      = FakeGitLayer(worktreeAddProvider = { _, _ -> expected })
        val ops      = WorktreeOps(noProject, git, FakeStateStore(singleBranchState("feat")), StackStateService())

        val result = ops.createWorktreeForBranch("feat", "/tmp/feat-wt")

        assertEquals(expected, result)
    }

    @Test
    fun `createWorktreeForBranch - throws IllegalStateException when branch already bound`() {
        val svc = StackStateService().also { it.updateWorktreePath("feat", "/existing") }
        val git = FakeGitLayer()
        val ops = WorktreeOps(noProject, git, FakeStateStore(singleBranchState("feat", "/existing")), svc)

        val ex = assertThrows<IllegalStateException> {
            ops.createWorktreeForBranch("feat", "/tmp/new-path")
        }
        assertTrue(ex.message!!.contains("feat"), "Error message should mention the branch")
        assertTrue(git.addCalls.isEmpty(), "git layer must not be called when branch is already bound")
    }

    @Test
    fun `createWorktreeForBranch - propagates WorktreeException thrown by git layer`() {
        val git = FakeGitLayer(worktreeAddProvider = { _, _ ->
            throw WorktreeAlreadyExistsException("/tmp/feat-wt")
        })
        val ops = WorktreeOps(noProject, git, FakeStateStore(singleBranchState("feat")), StackStateService())

        assertThrows<WorktreeAlreadyExistsException> {
            ops.createWorktreeForBranch("feat", "/tmp/feat-wt")
        }
    }

    // =========================================================================
    // removeWorktreeForBranch
    // =========================================================================

    @Test
    fun `removeWorktreeForBranch - calls git layer with the path from state service`() {
        val svc = StackStateService().also { it.updateWorktreePath("feat", "/tmp/feat-wt") }
        val git = FakeGitLayer()
        val ops = WorktreeOps(noProject, git, FakeStateStore(singleBranchState("feat")), svc)

        ops.removeWorktreeForBranch("feat")

        assertEquals(listOf("/tmp/feat-wt"), git.removeCalls)
    }

    @Test
    fun `removeWorktreeForBranch - clears path from state service`() {
        val svc = StackStateService().also { it.updateWorktreePath("feat", "/tmp/feat-wt") }
        val ops = WorktreeOps(noProject, FakeGitLayer(), FakeStateStore(singleBranchState("feat")), svc)

        ops.removeWorktreeForBranch("feat")

        assertNull(svc.getWorktreePath("feat"))
    }

    @Test
    fun `removeWorktreeForBranch - clears path from state store`() {
        val store = FakeStateStore(singleBranchState("feat", worktreePath = "/tmp/feat-wt"))
        val svc   = StackStateService().also { it.updateWorktreePath("feat", "/tmp/feat-wt") }
        val ops   = WorktreeOps(noProject, FakeGitLayer(), store, svc)

        ops.removeWorktreeForBranch("feat")

        val saved = store.writeHistory.single()
        assertNull(saved.branches["feat"]!!.worktreePath)
    }

    @Test
    fun `removeWorktreeForBranch - falls back to worktreeList when service has no path`() {
        val listedWorktrees = listOf(Worktree("/tmp/feat-from-list", "feat", "abc123", false))
        val git = FakeGitLayer(worktreeListResult = listedWorktrees)
        // Service has NO path recorded for "feat".
        val ops = WorktreeOps(noProject, git, FakeStateStore(singleBranchState("feat")), StackStateService())

        ops.removeWorktreeForBranch("feat")

        assertEquals(listOf("/tmp/feat-from-list"), git.removeCalls)
    }

    @Test
    fun `removeWorktreeForBranch - throws WorktreeNotFoundException when not in service or worktree list`() {
        val git = FakeGitLayer(worktreeListResult = emptyList())
        val ops = WorktreeOps(noProject, git, FakeStateStore(singleBranchState("feat")), StackStateService())

        assertThrows<WorktreeNotFoundException> {
            ops.removeWorktreeForBranch("feat")
        }
    }

    // =========================================================================
    // defaultWorktreePath
    // =========================================================================

    @Test
    fun `defaultWorktreePath - uses configured base path when set in service`() {
        val svc = StackStateService().also { it.setWorktreeBasePath("/workspace/worktrees") }
        val ops = WorktreeOps(noProject, FakeGitLayer(), FakeStateStore(), svc)

        assertEquals("/workspace/worktrees/feat", ops.defaultWorktreePath("feat"))
    }

    @Test
    fun `defaultWorktreePath - sanitizes forward slashes in branch name`() {
        val svc = StackStateService().also { it.setWorktreeBasePath("/base") }
        val ops = WorktreeOps(noProject, FakeGitLayer(), FakeStateStore(), svc)

        assertEquals("/base/feature-my-branch", ops.defaultWorktreePath("feature/my-branch"))
    }

    @Test
    fun `defaultWorktreePath - sanitizes backslashes in branch name`() {
        val svc = StackStateService().also { it.setWorktreeBasePath("/base") }
        val ops = WorktreeOps(noProject, FakeGitLayer(), FakeStateStore(), svc)

        assertEquals("/base/feature-my-branch", ops.defaultWorktreePath("feature\\my-branch"))
    }

    @Test
    fun `defaultWorktreePath - uses project-derived path when no base path configured`() {
        val project = stubProject("/home/user/my-repo", "my-repo")
        // No base path in service → falls back to project.basePath
        val ops = WorktreeOps(project, FakeGitLayer(), FakeStateStore(), StackStateService())

        assertEquals(
            "/home/user/my-repo/../my-repo-worktrees/feat",
            ops.defaultWorktreePath("feat"),
        )
    }
}
