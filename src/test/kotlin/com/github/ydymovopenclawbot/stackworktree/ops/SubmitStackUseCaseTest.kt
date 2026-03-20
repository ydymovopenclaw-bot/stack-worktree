package com.github.ydymovopenclawbot.stackworktree.ops

import com.github.ydymovopenclawbot.stackworktree.git.AheadBehind
import com.github.ydymovopenclawbot.stackworktree.git.BranchOperationException
import com.github.ydymovopenclawbot.stackworktree.git.GitLayer
import com.github.ydymovopenclawbot.stackworktree.git.RebaseResult
import com.github.ydymovopenclawbot.stackworktree.git.Worktree
import com.github.ydymovopenclawbot.stackworktree.git.WorktreeCommandException
import com.github.ydymovopenclawbot.stackworktree.pr.PrInfo
import com.github.ydymovopenclawbot.stackworktree.pr.PrProvider
import com.github.ydymovopenclawbot.stackworktree.pr.PrProviderException
import com.github.ydymovopenclawbot.stackworktree.pr.PrState
import com.github.ydymovopenclawbot.stackworktree.pr.PrStatus
import com.github.ydymovopenclawbot.stackworktree.state.BranchNode
import com.github.ydymovopenclawbot.stackworktree.state.RepoConfig
import com.github.ydymovopenclawbot.stackworktree.state.StackState
import com.github.ydymovopenclawbot.stackworktree.state.StackStateStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [SubmitStackUseCase].
 *
 * All dependencies are hand-written fakes — no IntelliJ Platform or network required.
 */
class SubmitStackUseCaseTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    /** Records every [push] call; optionally throws on a specified branch. */
    private class FakeGitLayer(
        private val pushFailOn: String? = null,
    ) : GitLayer {

        data class PushCall(val branch: String, val remote: String, val forceWithLease: Boolean)
        val pushCalls = mutableListOf<PushCall>()

        override fun push(branch: String, remote: String, forceWithLease: Boolean) {
            pushCalls += PushCall(branch, remote, forceWithLease)
            if (branch == pushFailOn) throw WorktreeCommandException("push rejected for '$branch'")
        }

        // Unused stubs.
        override fun worktreeAdd(p: String, b: String): Worktree = stub()
        override fun worktreeRemove(path: String, force: Boolean) = Unit
        override fun worktreeList(): List<Worktree> = emptyList()
        override fun worktreePrune() = Unit
        override fun listLocalBranches(): List<String> = emptyList()
        override fun aheadBehind(b: String, p: String): AheadBehind = AheadBehind(0, 0)
        override fun createBranch(n: String, b: String) = Unit
        override fun deleteBranch(n: String) = Unit
        override fun resolveCommit(ref: String): String = "0000000"
        override fun branchExists(name: String): Boolean = false
        override fun resetBranch(name: String, to: String) = Unit
        override fun rebaseOnto(b: String, nb: String, u: String): RebaseResult = RebaseResult.Success
        override fun fetchRemote(remote: String) = Unit
        override fun getMergedRemoteBranches(r: String, t: String): Set<String> = emptySet()
        override fun checkoutNewBranch(branch: String) = Unit
        override fun stageAll() = Unit
        override fun commit(message: String) = Unit

        private fun stub(): Nothing = throw UnsupportedOperationException()
    }

    /** Configurable fake [PrProvider] that records every call. */
    private class FakePrProvider(
        /** If non-null, returned by [findPrByBranch] for the matching branch. */
        private val existingPrs: Map<String, PrInfo> = emptyMap(),
        /** Counter used to mint unique PR numbers for newly created PRs. */
        private var nextNumber: Int = 100,
    ) : PrProvider {

        data class CreateCall(val branch: String, val base: String, val title: String, val body: String)
        data class UpdateCall(val prId: Int, val title: String?, val body: String?, val base: String?)

        val createCalls = mutableListOf<CreateCall>()
        val updateCalls = mutableListOf<UpdateCall>()
        val findCalls   = mutableListOf<String>()

        override fun createPr(branch: String, base: String, title: String, body: String): PrInfo {
            createCalls += CreateCall(branch, base, title, body)
            val num = nextNumber++
            return PrInfo(num, title, "https://github.com/org/repo/pull/$num", PrState.OPEN)
        }

        override fun updatePr(prId: Int, title: String?, body: String?, base: String?): PrInfo {
            updateCalls += UpdateCall(prId, title, body, base)
            return PrInfo(prId, title ?: "untitled", "https://github.com/org/repo/pull/$prId", PrState.OPEN)
        }

        override fun findPrByBranch(branch: String): PrInfo? {
            findCalls += branch
            return existingPrs[branch]
        }

        override fun getPrStatus(prId: Int): PrStatus = throw UnsupportedOperationException()
        override fun closePr(prId: Int) = throw UnsupportedOperationException()
    }

    /** In-memory [StackStateStore]. */
    private class FakeStateStore(initial: StackState? = null) : StackStateStore {
        private var stored: StackState? = initial
        val writeHistory = mutableListOf<StackState>()

        override fun read(): StackState? = stored
        override fun write(state: StackState) { stored = state; writeHistory += state }
        override fun delete() { stored = null }
    }

    // ── State builder helpers ─────────────────────────────────────────────────

    private fun linearState(trunk: String, vararg branches: String): StackState {
        val branchMap = mutableMapOf<String, BranchNode>()
        val chain     = listOf(trunk) + branches.toList()

        chain.forEachIndexed { i, name ->
            val parent   = if (i == 0) null else chain[i - 1]
            val children = if (i < chain.lastIndex) listOf(chain[i + 1]) else emptyList()
            branchMap[name] = BranchNode(name = name, parent = parent, children = children)
        }

        return StackState(
            repoConfig = RepoConfig(trunk = trunk, remote = "origin"),
            branches   = branchMap,
        )
    }

    private fun stateWithPr(
        state: StackState,
        branch: String,
        prId: Int,
        prUrl: String,
    ): StackState {
        val node = state.branches[branch] ?: return state
        return state.copy(
            branches = state.branches + (branch to node.copy(
                prInfo = com.github.ydymovopenclawbot.stackworktree.state.PrInfo(
                    provider = "github",
                    id       = prId.toString(),
                    url      = prUrl,
                    status   = "open",
                    ciStatus = "pending",
                )
            ))
        )
    }

    // ── Tests: NoState ────────────────────────────────────────────────────────

    @Test
    fun `returns NoState when no stack has been persisted`() {
        val result = SubmitStackUseCase(FakeGitLayer(), FakePrProvider(), FakeStateStore()).execute()
        assertEquals(SubmitResult.NoState, result)
    }

    @Test
    fun `returns Success(0,0) for trunk-only state`() {
        val state = linearState("main")
        val result = SubmitStackUseCase(FakeGitLayer(), FakePrProvider(), FakeStateStore(state)).execute()
        assertEquals(SubmitResult.Success(0, 0), result)
    }

    // ── Tests: first submit ───────────────────────────────────────────────────

    @Test
    fun `first submit creates PRs for all branches in BFS order`() {
        val state = linearState("main", "feat/a", "feat/b", "feat/c")
        val git   = FakeGitLayer()
        val pr    = FakePrProvider()
        val store = FakeStateStore(state)

        val result = SubmitStackUseCase(git, pr, store).execute()

        assertTrue(result is SubmitResult.Success)
        assertEquals(3, (result as SubmitResult.Success).created)
        assertEquals(0, result.updated)
        // 3 PRs created in Pass 1 + 3 description updates in Pass 2 = 3 update calls.
        assertEquals(3, pr.createCalls.size)
    }

    @Test
    fun `first submit creates PRs with correct base branches`() {
        val state = linearState("main", "feat/a", "feat/b")
        val pr    = FakePrProvider()

        SubmitStackUseCase(FakeGitLayer(), pr, FakeStateStore(state)).execute()

        // feat/a → base: main; feat/b → base: feat/a
        val createA = pr.createCalls.find { it.branch == "feat/a" }
        val createB = pr.createCalls.find { it.branch == "feat/b" }
        assertNotNull(createA, "Expected createPr for feat/a")
        assertNotNull(createB, "Expected createPr for feat/b")
        assertEquals("main",   createA!!.base)
        assertEquals("feat/a", createB!!.base)
    }

    @Test
    fun `first submit pushes every branch with force-with-lease`() {
        val state = linearState("main", "feat/a", "feat/b")
        val git   = FakeGitLayer()

        SubmitStackUseCase(git, FakePrProvider(), FakeStateStore(state)).execute()

        val pushed = git.pushCalls.map { it.branch }
        assertEquals(listOf("feat/a", "feat/b"), pushed)
        assertTrue(git.pushCalls.all { it.forceWithLease })
        assertTrue(git.pushCalls.all { it.remote == "origin" })
    }

    @Test
    fun `first submit persists PR info in state`() {
        val state = linearState("main", "feat/a")
        val store = FakeStateStore(state)

        SubmitStackUseCase(FakeGitLayer(), FakePrProvider(nextNumber = 7), store).execute()

        val written = store.writeHistory.last()
        val prInfo  = written.branches["feat/a"]?.prInfo
        assertNotNull(prInfo, "Expected prInfo stored for feat/a")
        assertEquals("7", prInfo!!.id)
        assertEquals("github", prInfo.provider)
        assertEquals("https://github.com/org/repo/pull/7", prInfo.url)
    }

    @Test
    fun `PR descriptions include a stack navigation table`() {
        val state = linearState("main", "feat/a", "feat/b")
        val pr    = FakePrProvider()

        SubmitStackUseCase(FakeGitLayer(), pr, FakeStateStore(state)).execute()

        // Every body sent to createPr or updatePr should contain the stack table header.
        val allBodies = pr.createCalls.map { it.body } + pr.updateCalls.map { it.body ?: "" }
        assertTrue(
            allBodies.all { it.contains("| # | Branch | PR |") },
            "Expected every PR body to contain the nav table header",
        )
    }

    @Test
    fun `second submit updates existing PRs — no new createPr calls`() {
        val baseState = linearState("main", "feat/a")
        val afterFirst = stateWithPr(baseState, "feat/a", 42, "https://github.com/org/repo/pull/42")

        val pr    = FakePrProvider()
        val store = FakeStateStore(afterFirst)

        val result = SubmitStackUseCase(FakeGitLayer(), pr, store).execute()

        assertEquals(0, pr.createCalls.size, "No PRs should be created on second submit")
        assertTrue(pr.updateCalls.isNotEmpty(), "updatePr should be called on second submit")
        assertTrue(result is SubmitResult.Success)
        assertEquals(1, (result as SubmitResult.Success).updated)
        assertEquals(0, result.created)
    }

    @Test
    fun `second submit updates correct PR number from persisted state`() {
        val baseState  = linearState("main", "feat/a")
        val afterFirst = stateWithPr(baseState, "feat/a", 42, "https://github.com/org/repo/pull/42")

        val pr = FakePrProvider()
        SubmitStackUseCase(FakeGitLayer(), pr, FakeStateStore(afterFirst)).execute()

        // All update calls should reference PR #42.
        val updatedIds = pr.updateCalls.map { it.prId }
        assertTrue(updatedIds.all { it == 42 }, "Expected all updates to reference PR #42, got: $updatedIds")
    }

    @Test
    fun `reuses existing remote PR when state has no stored prInfo`() {
        val state = linearState("main", "feat/a")
        val existing = PrInfo(77, "feat/a title", "https://github.com/org/repo/pull/77", PrState.OPEN)
        val pr    = FakePrProvider(existingPrs = mapOf("feat/a" to existing))
        val store = FakeStateStore(state)

        SubmitStackUseCase(FakeGitLayer(), pr, store).execute()

        assertEquals(0, pr.createCalls.size, "Should not create a new PR when remote already has one")
        val updatedIds = pr.updateCalls.map { it.prId }
        assertTrue(77 in updatedIds, "Expected updatePr to be called with #77")
    }

    @Test
    fun `pass 2 refreshes all PR bodies with fully resolved links`() {
        // After Pass 1 both PRs exist; Pass 2 should update every PR's body with the
        // complete navigation table (no 'pending' links for the sibling).
        val state = linearState("main", "feat/a", "feat/b")
        val pr    = FakePrProvider(nextNumber = 10)

        SubmitStackUseCase(FakeGitLayer(), pr, FakeStateStore(state)).execute()

        // Pass 2 must have been called for each branch (2 update calls beyond Pass 1).
        // Pass 1 for feat/a and feat/b: no update calls (both are new).
        // Pass 2: 2 update calls to refresh nav tables.
        assertEquals(2, pr.updateCalls.size)

        // Verify Pass-2 bodies contain resolved PR links (no '_(pending)_').
        val pass2Bodies = pr.updateCalls.map { it.body ?: "" }
        assertTrue(
            pass2Bodies.all { body -> !body.contains("_(pending)_") },
            "Pass-2 PR bodies should not contain pending markers; got:\n${pass2Bodies.joinToString("\n---\n")}",
        )
    }

    @Test
    fun `push failure aborts without creating PRs`() {
        val state = linearState("main", "feat/a", "feat/b")
        val git   = FakeGitLayer(pushFailOn = "feat/a")
        val pr    = FakePrProvider()

        assertThrows<WorktreeCommandException> {
            SubmitStackUseCase(git, pr, FakeStateStore(state)).execute()
        }

        // No PR should have been created since the first push failed.
        assertEquals(0, pr.createCalls.size, "No PRs should be created after a push failure")
    }

    @Test
    fun `push failure aborts mid-stack — already-pushed branches stay pushed`() {
        // feat/a pushes successfully; feat/b fails.
        val state = linearState("main", "feat/a", "feat/b")
        val git   = FakeGitLayer(pushFailOn = "feat/b")

        assertThrows<WorktreeCommandException> {
            SubmitStackUseCase(git, FakePrProvider(), FakeStateStore(state)).execute()
        }

        // feat/a was already pushed.
        assertEquals(listOf("feat/a", "feat/b"), git.pushCalls.map { it.branch })
    }

    @Test
    fun `state is not written after a push failure`() {
        val state = linearState("main", "feat/a")
        val git   = FakeGitLayer(pushFailOn = "feat/a")
        val store = FakeStateStore(state)

        assertThrows<WorktreeCommandException> {
            SubmitStackUseCase(git, FakePrProvider(), store).execute()
        }

        assertEquals(0, store.writeHistory.size, "State must not be written after a failure")
    }

    // ── Tests: nav table content ──────────────────────────────────────────────

    @Test
    fun `nav table bolds the current branch in each PR description`() {
        val state = linearState("main", "feat/a", "feat/b")
        val pr    = FakePrProvider(nextNumber = 1)

        SubmitStackUseCase(FakeGitLayer(), pr, FakeStateStore(state)).execute()

        // Pass 2 updates contain the final nav tables.
        val updateForA = pr.updateCalls.find { it.prId == 1 }
        val updateForB = pr.updateCalls.find { it.prId == 2 }

        assertNotNull(updateForA, "Expected a Pass-2 update for PR #1 (feat/a)")
        assertNotNull(updateForB, "Expected a Pass-2 update for PR #2 (feat/b)")

        assertTrue(
            updateForA!!.body?.contains("**feat/a**") == true,
            "PR #1 body should bold 'feat/a':\n${updateForA.body}",
        )
        assertTrue(
            updateForB!!.body?.contains("**feat/b**") == true,
            "PR #2 body should bold 'feat/b':\n${updateForB.body}",
        )
    }

    // ── Tests: SubmitResult.Success.summaryMessage ────────────────────────────

    @Test
    fun `summaryMessage — created only`() {
        assertEquals("1 PR created", SubmitResult.Success(1, 0).summaryMessage())
        assertEquals("3 PRs created", SubmitResult.Success(3, 0).summaryMessage())
    }

    @Test
    fun `summaryMessage — updated only`() {
        assertEquals("1 PR updated", SubmitResult.Success(0, 1).summaryMessage())
        assertEquals("2 PRs updated", SubmitResult.Success(0, 2).summaryMessage())
    }

    @Test
    fun `summaryMessage — both created and updated`() {
        assertEquals("2 PRs created, 1 PR updated", SubmitResult.Success(2, 1).summaryMessage())
    }

    @Test
    fun `summaryMessage — none`() {
        assertEquals("Stack submitted — no changes needed", SubmitResult.Success(0, 0).summaryMessage())
    }
}
