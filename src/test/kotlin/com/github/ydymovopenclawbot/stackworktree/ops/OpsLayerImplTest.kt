package com.github.ydymovopenclawbot.stackworktree.ops

import com.github.ydymovopenclawbot.stackworktree.git.AheadBehind
import com.github.ydymovopenclawbot.stackworktree.git.GitLayer
import com.github.ydymovopenclawbot.stackworktree.git.RebaseResult
import com.github.ydymovopenclawbot.stackworktree.git.Worktree
import com.github.ydymovopenclawbot.stackworktree.state.BranchHealth
import com.github.ydymovopenclawbot.stackworktree.state.BranchNode
import com.github.ydymovopenclawbot.stackworktree.state.PluginState
import com.github.ydymovopenclawbot.stackworktree.state.RepoConfig
import com.github.ydymovopenclawbot.stackworktree.state.StackState
import com.github.ydymovopenclawbot.stackworktree.state.StackStateStore
import com.github.ydymovopenclawbot.stackworktree.state.TrackedBranchNode
import com.github.ydymovopenclawbot.stackworktree.ui.UiLayer
import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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

    private class FakeUiLayer : UiLayer {
        val notifications = mutableListOf<String>()
        var refreshCount  = 0

        override fun refresh() { refreshCount++ }
        override fun notify(message: String) { notifications += message }
    }

    private val noProject: Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        throw UnsupportedOperationException("Unexpected call to Project.${method.name} in unit test")
    } as Project

    private fun makeOps(
        git:   FakeGitLayer,
        store: FakeStateStore,
        ui:    FakeUiLayer = FakeUiLayer(),
    ) = OpsLayerImpl(noProject, git, store, ui)

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

    // ── rebaseOntoParent ──────────────────────────────────────────────────────

    @Test
    fun `rebaseOntoParent - calls rebaseOnto with parent as both newBase and upstream`() {
        val git = FakeGitLayer(branchShas = mutableMapOf("main" to "sha-main", "feat" to "sha-feat"))
        makeOps(git, FakeStateStore(linearState())).rebaseOntoParent("feat")

        assertEquals(1, git.rebaseCalls.size)
        val (branch, newBase, upstream) = git.rebaseCalls.single()
        assertEquals("feat", branch)
        assertEquals("main", newBase)
        assertEquals("main", upstream)
    }

    @Test
    fun `rebaseOntoParent - updates baseCommit to parent tip on success`() {
        val parentTip = "sha-main-tip"
        val git   = FakeGitLayer(branchShas = mutableMapOf("main" to parentTip, "feat" to "sha-feat"))
        val store = FakeStateStore(linearState())
        makeOps(git, store).rebaseOntoParent("feat")

        val saved = store.writeHistory.single()
        assertEquals(parentTip, saved.branches["feat"]!!.baseCommit)
    }

    @Test
    fun `rebaseOntoParent - sets health to CLEAN on success`() {
        val git   = FakeGitLayer(branchShas = mutableMapOf("main" to "sha-main", "feat" to "sha-feat"))
        val store = FakeStateStore(
            linearState().let { s ->
                // Start with NEEDS_REBASE to verify it flips to CLEAN.
                s.copy(branches = s.branches + ("feat" to s.branches["feat"]!!.copy(health = BranchHealth.NEEDS_REBASE)))
            }
        )
        makeOps(git, store).rebaseOntoParent("feat")

        assertEquals(BranchHealth.CLEAN, store.writeHistory.single().branches["feat"]!!.health)
    }

    @Test
    fun `rebaseOntoParent - emits success notification and triggers refresh`() {
        val git = FakeGitLayer(branchShas = mutableMapOf("main" to "sha-main", "feat" to "sha-feat"))
        val ui  = FakeUiLayer()
        makeOps(git, FakeStateStore(linearState()), ui).rebaseOntoParent("feat")

        assertEquals(1, ui.notifications.size)
        assertTrue(ui.notifications.single().contains("feat"))
        assertEquals(1, ui.refreshCount)
    }

    @Test
    fun `rebaseOntoParent - writes no state and shows no notification on abort`() {
        val git = FakeGitLayer(
            branchShas = mutableMapOf("main" to "sha-main", "feat" to "sha-feat"),
            rebaseResultProvider = { _, _, _ -> RebaseResult.Aborted("conflict") },
        )
        val store = FakeStateStore(linearState())
        val ui    = FakeUiLayer()
        makeOps(git, store, ui).rebaseOntoParent("feat")

        assertTrue(store.writeHistory.isEmpty(), "No state written on abort")
        assertTrue(ui.notifications.isEmpty(),   "No notification on abort")
        assertEquals(0, ui.refreshCount,          "No refresh on abort")
    }

    @Test
    fun `rebaseOntoParent - throws IllegalStateException when branch is not tracked`() {
        val git = FakeGitLayer()
        assertThrows<IllegalStateException> {
            makeOps(git, FakeStateStore(linearState())).rebaseOntoParent("unknown-branch")
        }
    }

    @Test
    fun `rebaseOntoParent - throws IllegalStateException when branch has no parent (trunk)`() {
        val git   = FakeGitLayer(branchShas = mutableMapOf("main" to "sha-main"))
        assertThrows<IllegalStateException> {
            makeOps(git, FakeStateStore(linearState())).rebaseOntoParent("main")
        }
    }

    @Test
    fun `rebaseOntoParent - preserves all other branches in state on success`() {
        val git   = FakeGitLayer(branchShas = mutableMapOf("main" to "sha-main", "feat" to "sha-feat", "fix" to "sha-fix"))
        val store = FakeStateStore(chainState())
        makeOps(git, store).rebaseOntoParent("feat")

        val saved = store.writeHistory.single()
        // "main" and "fix" nodes must be untouched
        assertEquals(chainState().branches["main"], saved.branches["main"])
        assertEquals(chainState().branches["fix"],  saved.branches["fix"])
    }

    // ── restackAll ────────────────────────────────────────────────────────────

    private fun fourBranchChainState() = StackState(
        repoConfig = RepoConfig(trunk = "main", remote = "origin"),
        branches = mapOf(
            "main" to BranchNode("main", parent = null, children = listOf("auth")),
            "auth" to BranchNode("auth", parent = "main", children = listOf("api")),
            "api"  to BranchNode("api",  parent = "auth", children = listOf("ui")),
            "ui"   to BranchNode("ui",   parent = "api"),
        ),
    )

    private fun multiStackState() = StackState(
        repoConfig = RepoConfig(trunk = "main", remote = "origin"),
        branches = mapOf(
            "main"    to BranchNode("main",    parent = null, children = listOf("stack-a", "stack-b")),
            "stack-a" to BranchNode("stack-a", parent = "main", children = listOf("a-child")),
            "a-child" to BranchNode("a-child", parent = "stack-a"),
            "stack-b" to BranchNode("stack-b", parent = "main"),
        ),
    )

    @Test
    fun `restackAll - null state returns Success(0) with no rebase calls`() {
        val git   = FakeGitLayer()
        val store = FakeStateStore(null)
        val result = makeOps(git, store).restackAll()

        assertEquals(RestackResult.Success(0), result)
        assertTrue(git.rebaseCalls.isEmpty())
        assertTrue(store.writeHistory.isEmpty())
    }

    @Test
    fun `restackAll - trunk-only state returns Success(0) with no rebase calls`() {
        val state = StackState(
            repoConfig = RepoConfig(trunk = "main", remote = "origin"),
            branches   = mapOf("main" to BranchNode("main", parent = null)),
        )
        val git   = FakeGitLayer(branchShas = mutableMapOf("main" to "sha-main"))
        val result = makeOps(git, FakeStateStore(state)).restackAll()

        assertEquals(RestackResult.Success(0), result)
        assertTrue(git.rebaseCalls.isEmpty())
    }

    @Test
    fun `restackAll - single branch is rebased onto trunk`() {
        val git   = FakeGitLayer(branchShas = mutableMapOf("main" to "sha-main", "feat" to "sha-feat"))
        val store = FakeStateStore(linearState())
        val result = makeOps(git, store).restackAll()

        assertEquals(RestackResult.Success(1), result)
        assertEquals(1, git.rebaseCalls.size)
        val (branch, newBase, upstream) = git.rebaseCalls.single()
        assertEquals("feat",     branch)
        assertEquals("main",     newBase)
        assertEquals("sha-main", upstream)
        assertEquals(1, store.writeHistory.size)
    }

    @Test
    fun `restackAll - chain rebased in BFS order using pre-rebase parent tip as upstream`() {
        val git = FakeGitLayer(
            branchShas = mutableMapOf(
                "main" to "sha-main",
                "feat" to "sha-feat-before",
                "fix"  to "sha-fix-before",
            ),
        )
        val result = makeOps(git, FakeStateStore(chainState())).restackAll()

        assertEquals(RestackResult.Success(2), result)
        assertEquals(Triple("feat", "main", "sha-main"),        git.rebaseCalls[0])
        // upstream for fix must be the PRE-rebase tip of feat, not the post-rebase value
        assertEquals(Triple("fix",  "feat", "sha-feat-before"), git.rebaseCalls[1])
    }

    @Test
    fun `restackAll - 4-branch stack restacks all 3 non-trunk branches in order`() {
        val git = FakeGitLayer(
            branchShas = mutableMapOf(
                "main" to "sha-main",
                "auth" to "sha-auth",
                "api"  to "sha-api",
                "ui"   to "sha-ui",
            ),
        )
        val store  = FakeStateStore(fourBranchChainState())
        val result = makeOps(git, store).restackAll()

        assertEquals(RestackResult.Success(3), result)
        assertEquals(listOf("auth", "api", "ui"), git.rebaseCalls.map { it.first })
        assertEquals(1, store.writeHistory.size)
    }

    @Test
    fun `restackAll - baseCommit updated to parent tip after successful rebase`() {
        val mainTip = "sha-main-tip"
        val git     = FakeGitLayer(branchShas = mutableMapOf("main" to mainTip, "feat" to "sha-feat"))
        val store   = FakeStateStore(linearState())
        makeOps(git, store).restackAll()

        val saved = store.writeHistory.single()
        // After rebasing feat onto main, baseCommit should reflect main's current tip.
        assertEquals(mainTip, saved.branches["feat"]!!.baseCommit)
    }

    @Test
    fun `restackAll - abort keeps already-rebased branches without rollback`() {
        val oldFeatTip = "sha-feat-before"
        val git = FakeGitLayer(
            branchShas = mutableMapOf(
                "main" to "sha-main",
                "feat" to oldFeatTip,
                "fix"  to "sha-fix",
            ),
            rebaseResultProvider = { branch, _, _ ->
                if (branch == "fix") RebaseResult.Aborted("conflict in fix") else RebaseResult.Success
            },
        )
        val store  = FakeStateStore(chainState())
        val result = makeOps(git, store).restackAll()

        // Returns Aborted with correct counts
        assertTrue(result is RestackResult.Aborted)
        val aborted = result as RestackResult.Aborted
        assertEquals(1,     aborted.rebasedCount)
        assertEquals("fix", aborted.failedBranch)

        // No rollback — feat must NOT have been reset
        assertTrue(git.resetCalls.isEmpty(), "No resetBranch calls expected; got ${git.resetCalls}")

        // Partial progress must be persisted
        assertEquals(1, store.writeHistory.size, "State must be written even on abort")
        // feat's baseCommit should be set (it was successfully rebased)
        assertNotNull(store.writeHistory.single().branches["feat"]!!.baseCommit)
    }

    @Test
    fun `restackAll - abort on very first branch still writes state`() {
        val git = FakeGitLayer(
            branchShas = mutableMapOf("main" to "sha-main", "feat" to "sha-feat"),
            rebaseResultProvider = { _, _, _ -> RebaseResult.Aborted("immediate conflict") },
        )
        val store  = FakeStateStore(linearState())
        val result = makeOps(git, store).restackAll()

        assertTrue(result is RestackResult.Aborted)
        assertEquals(0,      (result as RestackResult.Aborted).rebasedCount)
        assertEquals("feat", result.failedBranch)
        assertEquals(1, store.writeHistory.size, "State must be written even on first-branch abort")
    }

    @Test
    fun `restackAll - progress callback receives correct 1-based indices and branch names`() {
        val git = FakeGitLayer(
            branchShas = mutableMapOf(
                "main" to "sha-main",
                "feat" to "sha-feat",
                "fix"  to "sha-fix",
            ),
        )
        val progressCalls = mutableListOf<Triple<Int, Int, String>>()
        makeOps(git, FakeStateStore(chainState())).restackAll { current, total, name ->
            progressCalls += Triple(current, total, name)
        }

        assertEquals(
            listOf(Triple(1, 2, "feat"), Triple(2, 2, "fix")),
            progressCalls,
        )
    }

    @Test
    fun `restackAll - multiple stacks under trunk are all rebased`() {
        val git = FakeGitLayer(
            branchShas = mutableMapOf(
                "main"    to "sha-main",
                "stack-a" to "sha-stack-a",
                "a-child" to "sha-a-child",
                "stack-b" to "sha-stack-b",
            ),
        )
        val store  = FakeStateStore(multiStackState())
        val result = makeOps(git, store).restackAll()

        assertEquals(RestackResult.Success(3), result)
        val rebased = git.rebaseCalls.map { it.first }.toSet()
        assertEquals(setOf("stack-a", "a-child", "stack-b"), rebased)
    }
}
