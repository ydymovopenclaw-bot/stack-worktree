package com.github.ydymovopenclawbot.stackworktree.ops

import com.github.ydymovopenclawbot.stackworktree.git.AheadBehind
import com.github.ydymovopenclawbot.stackworktree.git.GitLayer
import com.github.ydymovopenclawbot.stackworktree.git.RebaseResult
import com.github.ydymovopenclawbot.stackworktree.git.Worktree
import com.github.ydymovopenclawbot.stackworktree.state.BranchNode
import com.github.ydymovopenclawbot.stackworktree.state.RepoConfig
import com.github.ydymovopenclawbot.stackworktree.state.StackState
import com.github.ydymovopenclawbot.stackworktree.state.StackStateStore
import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy

/**
 * Unit tests for [OpsLayerImpl] using hand-written fakes.
 *
 * No IntelliJ Platform runtime is required: [gitLayerOverride] and [stateStoreOverride]
 * are injected directly, so the [Project] parameter is never dereferenced.
 */
class OpsLayerImplTest {

    // -------------------------------------------------------------------------
    // Fakes
    // -------------------------------------------------------------------------

    /**
     * Records every call made to [GitLayer] and returns configurable [RebaseResult]s.
     *
     * [branchShas] maps branchName → current SHA; [resolveCommit] reads from it.
     * [existingBranches] backs [branchExists]; [createBranch] populates both.
     */
    private class FakeGitLayer(
        val branchShas: MutableMap<String, String> = mutableMapOf(),
        val existingBranches: MutableSet<String> = mutableSetOf(),
        val rebaseResultProvider: (branch: String, newBase: String, upstream: String) -> RebaseResult =
            { _, _, _ -> RebaseResult.Success },
    ) : GitLayer {

        val createdBranches = mutableListOf<Pair<String, String>>()       // name → base
        val deletedBranches = mutableListOf<String>()
        val resetCalls      = mutableListOf<Pair<String, String>>()       // branch → toCommit
        val rebaseCalls     = mutableListOf<Triple<String, String, String>>() // branch, newBase, upstream

        override fun worktreeAdd(path: String, branch: String): Worktree  = unsupported()
        override fun worktreeRemove(path: String)                          = unsupported()
        override fun worktreeList(): List<Worktree>                        = unsupported()
        override fun worktreePrune()                                       = unsupported()
        override fun aheadBehind(branch: String, parent: String): AheadBehind = unsupported()

        override fun createBranch(branchName: String, baseBranch: String) {
            createdBranches += branchName to baseBranch
            existingBranches += branchName
            // Inherit the base's SHA so resolveCommit works on the new branch.
            branchShas[branchName] = branchShas[baseBranch] ?: "sha-$baseBranch"
        }

        override fun deleteBranch(branchName: String) {
            deletedBranches += branchName
            existingBranches -= branchName
        }

        override fun resolveCommit(branchOrRef: String): String =
            branchShas[branchOrRef] ?: "sha-$branchOrRef"

        override fun branchExists(branchName: String): Boolean = branchName in existingBranches

        override fun resetBranch(branchName: String, toCommit: String) {
            resetCalls += branchName to toCommit
            branchShas[branchName] = toCommit
        }

        override fun rebaseOnto(branch: String, newBase: String, upstream: String): RebaseResult {
            rebaseCalls += Triple(branch, newBase, upstream)
            return rebaseResultProvider(branch, newBase, upstream).also { result ->
                if (result is RebaseResult.Success) {
                    branchShas[branch] = "rebased-$branch-onto-$newBase"
                }
            }
        }

        override fun checkoutNewBranch(branch: String) = unsupported()
        override fun stageAll()                         = unsupported()
        override fun commit(message: String)            = unsupported()

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
     * A non-null [Project] proxy that throws [UnsupportedOperationException] on any call.
     * Safe to pass because [OpsLayerImpl] never touches `project` when both overrides are set.
     */
    private val noProject: Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        throw UnsupportedOperationException("Unexpected call to Project.${method.name} in unit test")
    } as Project

    private fun makeOps(git: FakeGitLayer, store: FakeStateStore) =
        OpsLayerImpl(noProject, git, store)

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    /** main → feat */
    private fun linearState() = StackState(
        repoConfig = RepoConfig(trunk = "main", remote = "origin"),
        branches = mapOf(
            "main" to BranchNode("main", parent = null, children = listOf("feat")),
            "feat" to BranchNode("feat", parent = "main"),
        ),
    )

    /** main → feat → (c1, c2) */
    private fun fanState() = StackState(
        repoConfig = RepoConfig(trunk = "main", remote = "origin"),
        branches = mapOf(
            "main" to BranchNode("main", parent = null, children = listOf("feat")),
            "feat" to BranchNode("feat", parent = "main", children = listOf("c1", "c2")),
            "c1"   to BranchNode("c1",   parent = "feat"),
            "c2"   to BranchNode("c2",   parent = "feat"),
        ),
    )

    /** main → feat → fix  (3-level) */
    private fun chainState() = StackState(
        repoConfig = RepoConfig(trunk = "main", remote = "origin"),
        branches = mapOf(
            "main" to BranchNode("main", parent = null, children = listOf("feat")),
            "feat" to BranchNode("feat", parent = "main", children = listOf("fix")),
            "fix"  to BranchNode("fix",  parent = "feat"),
        ),
    )

    // =========================================================================
    // insertBranchAbove — success
    // =========================================================================

    @Test
    fun `insertBranchAbove - creates branch from parent`() {
        val git = FakeGitLayer(branchShas = mutableMapOf("main" to "sha-main", "feat" to "sha-feat"))
        makeOps(git, FakeStateStore(linearState())).insertBranchAbove("feat", "mid")

        assertEquals(listOf("mid" to "main"), git.createdBranches)
    }

    @Test
    fun `insertBranchAbove - rebases target onto new branch with old parent as upstream`() {
        val git = FakeGitLayer(branchShas = mutableMapOf("main" to "sha-main", "feat" to "sha-feat"))
        makeOps(git, FakeStateStore(linearState())).insertBranchAbove("feat", "mid")

        assertEquals(Triple("feat", "mid", "main"), git.rebaseCalls.first())
    }

    @Test
    fun `insertBranchAbove - persists correct state on success`() {
        val store = FakeStateStore(linearState())
        val git   = FakeGitLayer(branchShas = mutableMapOf("main" to "sha-main", "feat" to "sha-feat"))
        makeOps(git, store).insertBranchAbove("feat", "mid")

        val saved = store.writeHistory.single()
        assertEquals("main",          saved.branches["mid"]!!.parent)
        assertEquals(listOf("feat"),  saved.branches["mid"]!!.children)
        assertEquals("mid",           saved.branches["feat"]!!.parent)
        assertEquals(listOf("mid"),   saved.branches["main"]!!.children)
    }

    @Test
    fun `insertBranchAbove - cascade uses pre-rebase SHA of parent as upstream`() {
        // main → feat → fix; insert "mid" above feat
        val git = FakeGitLayer(
            branchShas = mutableMapOf(
                "main" to "sha-main",
                "feat" to "sha-feat-before",
                "fix"  to "sha-fix-before",
            ),
        )
        makeOps(git, FakeStateStore(chainState())).insertBranchAbove("feat", "mid")

        // Call 0: feat --onto mid, upstream = main (old parent)
        assertEquals(Triple("feat", "mid", "main"), git.rebaseCalls[0])
        // Call 1 (cascade): fix --onto feat, upstream = SHA of feat *before* it was rebased
        assertEquals(Triple("fix", "feat", "sha-feat-before"), git.rebaseCalls[1])
    }

    @Test
    fun `insertBranchAbove - root branch skips rebase and just updates state`() {
        val state = StackState(
            repoConfig = RepoConfig(trunk = "main", remote = "origin"),
            branches   = mapOf("main" to BranchNode("main", parent = null)),
        )
        val git   = FakeGitLayer()
        val store = FakeStateStore(state)
        makeOps(git, store).insertBranchAbove("main", "before-main")

        assertTrue(git.rebaseCalls.isEmpty(), "No rebase when inserting above root")
        assertEquals(1, store.writeHistory.size)
        assertNull(store.writeHistory.single().branches["before-main"]?.parent)
    }

    // =========================================================================
    // insertBranchAbove — abort / rollback
    // =========================================================================

    @Test
    fun `insertBranchAbove - deletes new branch and writes no state on target-rebase abort`() {
        val git = FakeGitLayer(
            branchShas = mutableMapOf("main" to "sha-main", "feat" to "sha-feat"),
            rebaseResultProvider = { _, _, _ -> RebaseResult.Aborted("conflict") },
        )
        val store = FakeStateStore(linearState())
        makeOps(git, store).insertBranchAbove("feat", "mid")

        assertTrue("mid" in git.deletedBranches)
        assertTrue(store.writeHistory.isEmpty())
    }

    @Test
    fun `insertBranchAbove - cascade abort resets targetBranch and deletes new branch`() {
        // feat succeeds, fix aborts → must reset feat + delete mid
        val oldFeatTip = "sha-feat-before"
        val git = FakeGitLayer(
            branchShas = mutableMapOf("main" to "sha-main", "feat" to oldFeatTip, "fix" to "sha-fix"),
            rebaseResultProvider = { branch, _, _ ->
                if (branch == "fix") RebaseResult.Aborted("conflict in fix") else RebaseResult.Success
            },
        )
        val store = FakeStateStore(chainState())
        makeOps(git, store).insertBranchAbove("feat", "mid")

        assertTrue("mid" in git.deletedBranches, "mid should be deleted; deleted=${git.deletedBranches}")
        assertTrue(
            git.resetCalls.any { it.first == "feat" && it.second == oldFeatTip },
            "feat should be reset to $oldFeatTip; resetCalls=${git.resetCalls}",
        )
        assertTrue(store.writeHistory.isEmpty())
    }

    // =========================================================================
    // insertBranchBelow — success
    // =========================================================================

    @Test
    fun `insertBranchBelow - creates branch from target`() {
        val git = FakeGitLayer(branchShas = mutableMapOf("feat" to "sha-feat"))
        makeOps(git, FakeStateStore(linearState())).insertBranchBelow("feat", "after-feat")

        assertEquals(listOf("after-feat" to "feat"), git.createdBranches)
    }

    @Test
    fun `insertBranchBelow - rebases all children onto new branch with target as upstream`() {
        val git = FakeGitLayer(
            branchShas = mutableMapOf("feat" to "sha-feat", "c1" to "sha-c1", "c2" to "sha-c2"),
        )
        makeOps(git, FakeStateStore(fanState())).insertBranchBelow("feat", "mid")

        val rebasedBranches = git.rebaseCalls.map { it.first }.toSet()
        assertEquals(setOf("c1", "c2"), rebasedBranches)
        git.rebaseCalls.forEach { (branch, newBase, upstream) ->
            assertEquals("mid",  newBase,   "newBase for $branch")
            assertEquals("feat", upstream,  "upstream for $branch")
        }
    }

    @Test
    fun `insertBranchBelow - leaf branch has no rebase calls and state is updated`() {
        val store = FakeStateStore(linearState())
        makeOps(FakeGitLayer(), store).insertBranchBelow("feat", "after-feat")

        assertTrue(store.writeHistory.single().let { s ->
            s.branches["feat"]!!.children == listOf("after-feat") &&
            s.branches["after-feat"]!!.parent == "feat"
        })
    }

    @Test
    fun `insertBranchBelow - persists correct state on success`() {
        val store = FakeStateStore(fanState())
        val git   = FakeGitLayer(
            branchShas = mutableMapOf("feat" to "sha-feat", "c1" to "sha-c1", "c2" to "sha-c2"),
        )
        makeOps(git, store).insertBranchBelow("feat", "mid")

        val saved = store.writeHistory.single()
        assertEquals(listOf("mid"),       saved.branches["feat"]!!.children)
        assertEquals(listOf("c1", "c2"), saved.branches["mid"]!!.children)
        assertEquals("mid",              saved.branches["c1"]!!.parent)
        assertEquals("mid",              saved.branches["c2"]!!.parent)
    }

    // =========================================================================
    // insertBranchBelow — abort / rollback
    // =========================================================================

    @Test
    fun `insertBranchBelow - deletes new branch and writes no state when first child aborts`() {
        val git = FakeGitLayer(
            branchShas = mutableMapOf("feat" to "sha-feat", "c1" to "sha-c1", "c2" to "sha-c2"),
            rebaseResultProvider = { branch, _, _ ->
                if (branch == "c1") RebaseResult.Aborted("conflict") else RebaseResult.Success
            },
        )
        val store = FakeStateStore(fanState())
        makeOps(git, store).insertBranchBelow("feat", "mid")

        assertTrue("mid" in git.deletedBranches)
        assertTrue(store.writeHistory.isEmpty())
        // c1 itself failed — no completed rebases to roll back
        assertTrue(git.resetCalls.isEmpty(), "No resetBranch when first child aborts")
    }

    @Test
    fun `insertBranchBelow - resets already-rebased children when mid-loop abort occurs`() {
        // c1 succeeds, c2 aborts → c1 must be reset to its pre-rebase SHA
        val oldC1Tip = "sha-c1-before"
        val git = FakeGitLayer(
            branchShas = mutableMapOf("feat" to "sha-feat", "c1" to oldC1Tip, "c2" to "sha-c2"),
            rebaseResultProvider = { branch, _, _ ->
                if (branch == "c2") RebaseResult.Aborted("conflict in c2") else RebaseResult.Success
            },
        )
        val store = FakeStateStore(fanState())
        makeOps(git, store).insertBranchBelow("feat", "mid")

        assertTrue("mid" in git.deletedBranches, "mid should be deleted; deleted=${git.deletedBranches}")
        assertTrue(
            git.resetCalls.any { it.first == "c1" && it.second == oldC1Tip },
            "c1 should be reset to $oldC1Tip; resetCalls=${git.resetCalls}",
        )
        assertTrue(store.writeHistory.isEmpty())
    }

    // =========================================================================
    // requireBranchAbsent — git branch check
    // =========================================================================

    @Test
    fun `insertBranchAbove - throws IllegalArgumentException when branch exists in git but not in state`() {
        val git = FakeGitLayer(existingBranches = mutableSetOf("mid"))
        val ex  = assertThrows<IllegalArgumentException> {
            makeOps(git, FakeStateStore(linearState())).insertBranchAbove("feat", "mid")
        }
        assertTrue("mid" in ex.message!!)
    }

    @Test
    fun `insertBranchBelow - throws IllegalArgumentException when branch exists in git but not in state`() {
        val git = FakeGitLayer(existingBranches = mutableSetOf("after-feat"))
        val ex  = assertThrows<IllegalArgumentException> {
            makeOps(git, FakeStateStore(linearState())).insertBranchBelow("feat", "after-feat")
        }
        assertTrue("after-feat" in ex.message!!)
    }
}
