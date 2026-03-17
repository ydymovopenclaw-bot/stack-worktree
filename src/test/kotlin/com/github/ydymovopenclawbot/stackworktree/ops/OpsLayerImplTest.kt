package com.github.ydymovopenclawbot.stackworktree.ops

import com.github.ydymovopenclawbot.stackworktree.git.AheadBehind
import com.github.ydymovopenclawbot.stackworktree.git.GitLayer
import com.github.ydymovopenclawbot.stackworktree.git.RebaseResult
import com.github.ydymovopenclawbot.stackworktree.git.Worktree
import com.github.ydymovopenclawbot.stackworktree.state.BranchNode
import com.github.ydymovopenclawbot.stackworktree.state.PluginState
import com.github.ydymovopenclawbot.stackworktree.state.RepoConfig
import com.github.ydymovopenclawbot.stackworktree.state.StackState
import com.github.ydymovopenclawbot.stackworktree.state.StackStateStore
import com.github.ydymovopenclawbot.stackworktree.state.TrackedBranchNode
import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy
import kotlin.test.assertFailsWith

class OpsLayerImplTest {

    // ── Algorithms (track/untrack pure-function tests) ────────────────────────

    private val A = OpsLayerImpl.Algorithms

    private fun stateWith(vararg nodes: TrackedBranchNode): PluginState =
        PluginState(
            trunkBranch     = "main",
            trackedBranches = nodes.associateBy { it.name },
        )

    @Test
    fun `track basic — adds node as child of trunk`() {
        val state  = stateWith()
        val result = A.applyTrackBranch(state, "feature-a", "main")

        val node = result.trackedBranches["feature-a"]
        assertEquals("feature-a", node?.name)
        assertEquals("main", node?.parentName)
        assertTrue(node?.children?.isEmpty() == true)
    }

    @Test
    fun `track basic — does not create parent node when parent is trunk`() {
        val state  = stateWith()
        val result = A.applyTrackBranch(state, "feature-a", "main")

        assertNull(result.trackedBranches["main"])
    }

    @Test
    fun `track updates parent's children list`() {
        val state = stateWith(
            TrackedBranchNode(name = "feature-a", parentName = "main"),
        )
        val result = A.applyTrackBranch(state, "feature-b", "feature-a")

        val parent = result.trackedBranches["feature-a"]
        assertEquals(listOf("feature-b"), parent?.children)
    }

    @Test
    fun `track appends to existing children in order`() {
        val state = stateWith(
            TrackedBranchNode(
                name       = "feature-a",
                parentName = "main",
                children   = listOf("feature-b"),
            ),
            TrackedBranchNode(name = "feature-b", parentName = "feature-a"),
        )
        val result = A.applyTrackBranch(state, "feature-c", "feature-a")

        val parent = result.trackedBranches["feature-a"]
        assertEquals(listOf("feature-b", "feature-c"), parent?.children)
    }

    @Test
    fun `track duplicate branch throws IllegalArgumentException`() {
        val state = stateWith(
            TrackedBranchNode(name = "feature-a", parentName = "main"),
        )
        assertFailsWith<IllegalArgumentException> {
            A.applyTrackBranch(state, "feature-a", "main")
        }
    }

    @Test
    fun `track with invalid parent throws IllegalArgumentException`() {
        val state = stateWith()
        assertFailsWith<IllegalArgumentException> {
            A.applyTrackBranch(state, "feature-a", "not-trunk-or-tracked")
        }
    }

    @Test
    fun `track preserves existing state fields`() {
        val state = stateWith(
            TrackedBranchNode(name = "feature-a", parentName = "main"),
        ).copy(activeWorktrees = listOf("/some/path"), lastUsedBranch = "feature-a")

        val result = A.applyTrackBranch(state, "feature-b", "main")

        assertEquals(listOf("/some/path"), result.activeWorktrees)
        assertEquals("feature-a", result.lastUsedBranch)
    }

    @Test
    fun `untrack leaf — removes node`() {
        val state = stateWith(
            TrackedBranchNode(name = "feature-a", parentName = "main"),
        )
        val result = A.applyUntrackBranch(state, "feature-a")

        assertTrue(result.trackedBranches.isEmpty())
    }

    @Test
    fun `untrack leaf — removes branch from parent's children list`() {
        val state = stateWith(
            TrackedBranchNode(
                name       = "feature-a",
                parentName = "main",
                children   = listOf("feature-b"),
            ),
            TrackedBranchNode(name = "feature-b", parentName = "feature-a"),
        )
        val result = A.applyUntrackBranch(state, "feature-b")

        val parent = result.trackedBranches["feature-a"]
        assertTrue(parent?.children?.isEmpty() == true)
    }

    @Test
    fun `untrack mid-stack — children re-parented to grandparent`() {
        val state = stateWith(
            TrackedBranchNode(
                name       = "A",
                parentName = "main",
                children   = listOf("B"),
            ),
            TrackedBranchNode(
                name       = "B",
                parentName = "A",
                children   = listOf("C"),
            ),
            TrackedBranchNode(name = "C", parentName = "B"),
        )
        val result = A.applyUntrackBranch(state, "B")

        assertNull(result.trackedBranches["B"])

        assertEquals("A", result.trackedBranches["C"]?.parentName)

        assertEquals(listOf("C"), result.trackedBranches["A"]?.children)
    }

    @Test
    fun `untrack mid-stack — multiple children spliced in order`() {
        val state = stateWith(
            TrackedBranchNode(
                name       = "A",
                parentName = "main",
                children   = listOf("B", "X"),
            ),
            TrackedBranchNode(
                name       = "B",
                parentName = "A",
                children   = listOf("C", "D"),
            ),
            TrackedBranchNode(name = "X", parentName = "A"),
            TrackedBranchNode(name = "C", parentName = "B"),
            TrackedBranchNode(name = "D", parentName = "B"),
        )
        val result = A.applyUntrackBranch(state, "B")

        assertEquals(listOf("C", "D", "X"), result.trackedBranches["A"]?.children)
        assertEquals("A", result.trackedBranches["C"]?.parentName)
        assertEquals("A", result.trackedBranches["D"]?.parentName)
    }

    @Test
    fun `untrack root-level branch — children re-parented to trunk`() {
        val state = stateWith(
            TrackedBranchNode(
                name       = "A",
                parentName = "main",
                children   = listOf("B"),
            ),
            TrackedBranchNode(name = "B", parentName = "A"),
        )
        val result = A.applyUntrackBranch(state, "A")

        assertEquals("main", result.trackedBranches["B"]?.parentName)
        assertNull(result.trackedBranches["A"])
    }

    @Test
    fun `untrack non-tracked branch throws IllegalArgumentException`() {
        val state = stateWith()
        assertFailsWith<IllegalArgumentException> {
            A.applyUntrackBranch(state, "does-not-exist")
        }
    }

    // ── Insert operations (insertBranchAbove / insertBranchBelow) ─────────────

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
        override fun listLocalBranches(): List<String>                     = emptyList()

        override fun createBranch(branchName: String, baseBranch: String) {
            createdBranches += branchName to baseBranch
            existingBranches += branchName
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

    private class FakeStateStore(initial: StackState? = null) : StackStateStore {
        private var stored: StackState? = initial
        val writeHistory = mutableListOf<StackState>()

        override fun read(): StackState? = stored
        override fun write(state: StackState) { stored = state; writeHistory += state }
    }

    private val noProject: Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        throw UnsupportedOperationException("Unexpected call to Project.${method.name} in unit test")
    } as Project

    private fun makeOps(git: FakeGitLayer, store: FakeStateStore) =
        OpsLayerImpl(noProject, git, store)

    private fun linearState() = StackState(
        repoConfig = RepoConfig(trunk = "main", remote = "origin"),
        branches = mapOf(
            "main" to BranchNode("main", parent = null, children = listOf("feat")),
            "feat" to BranchNode("feat", parent = "main"),
        ),
    )

    private fun fanState() = StackState(
        repoConfig = RepoConfig(trunk = "main", remote = "origin"),
        branches = mapOf(
            "main" to BranchNode("main", parent = null, children = listOf("feat")),
            "feat" to BranchNode("feat", parent = "main", children = listOf("c1", "c2")),
            "c1"   to BranchNode("c1",   parent = "feat"),
            "c2"   to BranchNode("c2",   parent = "feat"),
        ),
    )

    private fun chainState() = StackState(
        repoConfig = RepoConfig(trunk = "main", remote = "origin"),
        branches = mapOf(
            "main" to BranchNode("main", parent = null, children = listOf("feat")),
            "feat" to BranchNode("feat", parent = "main", children = listOf("fix")),
            "fix"  to BranchNode("fix",  parent = "feat"),
        ),
    )

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
        val git = FakeGitLayer(
            branchShas = mutableMapOf(
                "main" to "sha-main",
                "feat" to "sha-feat-before",
                "fix"  to "sha-fix-before",
            ),
        )
        makeOps(git, FakeStateStore(chainState())).insertBranchAbove("feat", "mid")

        assertEquals(Triple("feat", "mid", "main"), git.rebaseCalls[0])
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
        assertTrue(git.resetCalls.isEmpty(), "No resetBranch when first child aborts")
    }

    @Test
    fun `insertBranchBelow - resets already-rebased children when mid-loop abort occurs`() {
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
