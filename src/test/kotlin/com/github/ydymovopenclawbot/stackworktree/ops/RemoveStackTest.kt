package com.github.ydymovopenclawbot.stackworktree.ops

import com.github.ydymovopenclawbot.stackworktree.git.AheadBehind
import com.github.ydymovopenclawbot.stackworktree.git.GitLayer
import com.github.ydymovopenclawbot.stackworktree.git.RebaseResult
import com.github.ydymovopenclawbot.stackworktree.git.Worktree
import com.github.ydymovopenclawbot.stackworktree.git.WorktreeCommandException
import com.github.ydymovopenclawbot.stackworktree.state.PluginState
import com.github.ydymovopenclawbot.stackworktree.state.StackStateService
import com.github.ydymovopenclawbot.stackworktree.state.StackStateStore
import com.github.ydymovopenclawbot.stackworktree.state.StackState
import com.github.ydymovopenclawbot.stackworktree.state.StateLayer
import com.github.ydymovopenclawbot.stackworktree.state.StackTreeStateListener
import com.github.ydymovopenclawbot.stackworktree.state.TrackedBranchNode
import com.github.ydymovopenclawbot.stackworktree.ui.STACK_STATE_TOPIC
import com.github.ydymovopenclawbot.stackworktree.ui.StackStateListener
import com.github.ydymovopenclawbot.stackworktree.ui.UiLayer
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusOwner
import com.intellij.util.messages.Topic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class RemoveStackTest {

    private val A = OpsLayerImpl.Algorithms

    private fun stateWith(
        trunk: String = "main",
        vararg nodes: TrackedBranchNode,
    ): PluginState = PluginState(
        trunkBranch = trunk,
        trackedBranches = nodes.associateBy { it.name },
    )

    @Test
    fun `empty stack returns empty result and clears trunkBranch`() {
        val state = PluginState(trunkBranch = "main", trackedBranches = emptyMap())
        val (newState, ordered) = A.applyRemoveStack(state, "main")

        assertNull(newState.trunkBranch)
        assertTrue(newState.trackedBranches.isEmpty())
        assertTrue(ordered.isEmpty())
    }

    @Test
    fun `removes all branches and sets trunkBranch to null`() {
        val state = stateWith(
            "main",
            TrackedBranchNode("a", parentName = "main", children = listOf("b")),
            TrackedBranchNode("b", parentName = "a"),
        )

        val (newState, _) = A.applyRemoveStack(state, "main")

        assertNull(newState.trunkBranch)
        assertTrue(newState.trackedBranches.isEmpty())
    }

    @Test
    fun `returns branches in leaf-first order — children before parents`() {
        val state = stateWith(
            "main",
            TrackedBranchNode("a", parentName = "main", children = listOf("b")),
            TrackedBranchNode("b", parentName = "a", children = listOf("c")),
            TrackedBranchNode("c", parentName = "b"),
        )

        val (_, ordered) = A.applyRemoveStack(state, "main")

        // Leaf-first: c before b before a
        val idxC = ordered.indexOf("c")
        val idxB = ordered.indexOf("b")
        val idxA = ordered.indexOf("a")
        assertTrue(idxC < idxB, "c ($idxC) should come before b ($idxB)")
        assertTrue(idxB < idxA, "b ($idxB) should come before a ($idxA)")
    }

    @Test
    fun `fan structure — all children appear before parent`() {
        val state = stateWith(
            "main",
            TrackedBranchNode("root", parentName = "main", children = listOf("left", "right")),
            TrackedBranchNode("left", parentName = "root"),
            TrackedBranchNode("right", parentName = "root"),
        )

        val (_, ordered) = A.applyRemoveStack(state, "main")

        val idxRoot = ordered.indexOf("root")
        val idxLeft = ordered.indexOf("left")
        val idxRight = ordered.indexOf("right")
        assertTrue(idxLeft < idxRoot, "left should come before root")
        assertTrue(idxRight < idxRoot, "right should come before root")
    }

    @Test
    fun `all tracked branches are included in ordered list`() {
        val state = stateWith(
            "main",
            TrackedBranchNode("a", parentName = "main", children = listOf("b")),
            TrackedBranchNode("b", parentName = "a"),
        )

        val (_, ordered) = A.applyRemoveStack(state, "main")

        assertEquals(setOf("a", "b"), ordered.toSet())
    }

    // ── RemoveStackResult.summary() tests ──────────────────────────────────

    @Test
    fun `summary with only removed branches`() {
        val result = RemoveStackResult(removedBranches = listOf("a", "b", "c"))
        assertEquals("Removed 3 branch(es) from stack.", result.summary())
    }

    @Test
    fun `summary with deleted branches and removed worktrees`() {
        val result = RemoveStackResult(
            removedBranches = listOf("a", "b"),
            deletedBranches = listOf("a", "b"),
            removedWorktrees = listOf("/wt/a", "/wt/b"),
        )
        assertEquals(
            "Removed 2 branch(es) from stack, deleted 2 git branch(es), pruned 2 worktree(s).",
            result.summary(),
        )
    }

    @Test
    fun `summary with failed worktrees`() {
        val result = RemoveStackResult(
            removedBranches = listOf("a", "b"),
            removedWorktrees = listOf("/wt/b"),
            failedWorktrees = mapOf("a" to "directory not empty"),
        )
        assertEquals(
            "Removed 2 branch(es) from stack, pruned 1 worktree(s), 1 worktree(s) failed to remove.",
            result.summary(),
        )
    }

    // ── Integration test: worktree-failure-skip ──────────────────────────────

    private class FakeGitLayer(
        val worktreeRemoveHandler: (String) -> Unit = {},
    ) : GitLayer {
        val removedWorktrees = mutableListOf<String>()
        val deletedBranches  = mutableListOf<String>()

        override fun worktreeAdd(path: String, branch: String): Worktree = unsupported()
        override fun worktreeRemove(path: String, force: Boolean) {
            worktreeRemoveHandler(path) // may throw to simulate failure
            removedWorktrees += path
        }
        override fun worktreeList(): List<Worktree> = emptyList()
        override fun worktreePrune() = unsupported()
        override fun aheadBehind(branch: String, parent: String): AheadBehind = AheadBehind(0, 0)
        override fun listLocalBranches(): List<String> = emptyList()
        override fun fetchRemote(remote: String) = Unit
        override fun getMergedRemoteBranches(remote: String, trunkBranch: String): Set<String> = emptySet()
        override fun createBranch(branchName: String, baseBranch: String) = unsupported()
        override fun deleteBranch(branchName: String) { deletedBranches += branchName }
        override fun resolveCommit(branchOrRef: String): String = "sha-$branchOrRef"
        override fun branchExists(branchName: String): Boolean = false
        override fun resetBranch(branchName: String, toCommit: String) = unsupported()
        override fun rebaseOnto(branch: String, newBase: String, upstream: String): RebaseResult = unsupported()
        override fun push(branch: String, remote: String, forceWithLease: Boolean) = unsupported()
        override fun checkoutNewBranch(branch: String) = unsupported()
        override fun stageAll() = unsupported()
        override fun commit(message: String) = unsupported()
        private fun unsupported(): Nothing = throw UnsupportedOperationException()
    }

    private class FakeStateStore : StackStateStore {
        var deleted = false
        override fun read(): StackState? = null
        override fun write(state: StackState) = Unit
        override fun delete() { deleted = true }
    }

    private class FakeStateLayer(initial: PluginState = PluginState()) : StateLayer {
        var current = initial
        override fun load() = current
        override fun save(state: PluginState) { current = state }
    }

    private class FakeUiLayer : UiLayer {
        val notifications = mutableListOf<String>()
        override fun refresh() = Unit
        override fun notify(message: String) { notifications += message }
    }

    /**
     * Creates a minimal [Project] proxy that supports `messageBus.syncPublisher()`
     * for the two topics fired by `removeStack`. All other calls throw.
     */
    private fun fakeProject(): Project {
        val noOpStackTreeStateListener = StackTreeStateListener { }
        val noOpStackStateListener = object : StackStateListener {
            override fun stateChanged() = Unit
        }

        val messageBus = Proxy.newProxyInstance(
            MessageBus::class.java.classLoader,
            arrayOf(MessageBus::class.java),
        ) { _, method, args ->
            when (method.name) {
                "syncPublisher" -> {
                    val topic = args?.firstOrNull() as? Topic<*>
                    when (topic) {
                        StackTreeStateListener.TOPIC -> noOpStackTreeStateListener
                        STACK_STATE_TOPIC -> noOpStackStateListener
                        else -> throw UnsupportedOperationException("Unexpected topic: $topic")
                    }
                }
                "isDisposed" -> false
                else -> throw UnsupportedOperationException("Unexpected MessageBus.${method.name}")
            }
        } as MessageBus

        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getMessageBus" -> messageBus
                "isDisposed" -> false
                else -> throw UnsupportedOperationException("Unexpected Project.${method.name}")
            }
        } as Project
    }

    @Test
    fun `removeStack skips failed worktree removal and reports it in result`() {
        val failPath = "/wt/a"
        val git = FakeGitLayer(worktreeRemoveHandler = { path ->
            if (path == failPath) throw WorktreeCommandException("directory not empty")
        })
        val stateService = StackStateService().apply {
            recordBranch("a", "main", failPath)
            recordBranch("b", "a", "/wt/b")
        }
        val stateLayer = FakeStateLayer(
            stateWith(
                "main",
                TrackedBranchNode("a", parentName = "main", children = listOf("b")),
                TrackedBranchNode("b", parentName = "a"),
            )
        )

        val ops = OpsLayerImpl.forTest(
            project = fakeProject(),
            gitLayer = git,
            stateStore = FakeStateStore(),
            stateLayer = stateLayer,
            stateService = stateService,
        )

        val result = ops.removeStack(
            stackRoot = "main",
            deleteBranches = false,
            removeWorktrees = true,
        )

        // The worktree for "a" should have failed; "b" should have succeeded.
        assertTrue(result.failedWorktrees.containsKey("a"), "Expected 'a' in failedWorktrees: ${result.failedWorktrees}")
        assertTrue(result.failedWorktrees["a"]!!.contains("directory not empty"))
        assertTrue(result.removedWorktrees.contains("/wt/b"), "Expected '/wt/b' in removedWorktrees: ${result.removedWorktrees}")
        // State should still be cleared.
        assertNull(stateLayer.current.trunkBranch)
        assertTrue(stateLayer.current.trackedBranches.isEmpty())
    }
}
