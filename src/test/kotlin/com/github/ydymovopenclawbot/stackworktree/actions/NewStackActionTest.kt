package com.github.ydymovopenclawbot.stackworktree.actions

import com.github.ydymovopenclawbot.stackworktree.state.BranchNode
import com.github.ydymovopenclawbot.stackworktree.state.RepoConfig
import com.github.ydymovopenclawbot.stackworktree.state.StackState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Unit tests for [NewStackAction.buildState].
 *
 * These tests exercise only the pure state-construction logic — no IntelliJ platform
 * or git process is involved, so they run fast without any test-framework overhead.
 */
class NewStackActionTest {

    private val action = NewStackAction()

    // -------------------------------------------------------------------------
    // No existing state
    // -------------------------------------------------------------------------

    @Test
    fun `on trunk with no existing state creates state with trunk node only`() {
        val state = action.buildState(existing = null, currentBranch = "main", trunk = "main")

        assertEquals("main", state.repoConfig.trunk)
        assertEquals(1, state.branches.size)
        val trunkNode = state.branches["main"]!!
        assertEquals("main", trunkNode.name)
        assertNull(trunkNode.parent)
    }

    @Test
    fun `on feature branch with no existing state creates trunk and feature nodes`() {
        val state = action.buildState(
            existing      = null,
            currentBranch = "feature-1",
            trunk         = "main",
        )

        assertEquals("main", state.repoConfig.trunk)
        assertEquals(2, state.branches.size)

        val trunkNode = state.branches["main"]!!
        assertNull(trunkNode.parent)

        val featureNode = state.branches["feature-1"]!!
        assertEquals("main", featureNode.parent)
    }

    @Test
    fun `trunk branch in repoConfig matches the dialog input`() {
        val state = action.buildState(existing = null, currentBranch = "feature-1", trunk = "develop")

        assertEquals("develop", state.repoConfig.trunk)
        assertNull(state.branches["develop"]!!.parent)
        assertEquals("develop", state.branches["feature-1"]!!.parent)
    }

    // -------------------------------------------------------------------------
    // Existing state — add new branch
    // -------------------------------------------------------------------------

    @Test
    fun `with existing state adds current branch as new root node`() {
        val existing = stackState("main")

        val state = action.buildState(
            existing      = existing,
            currentBranch = "feature-2",
            trunk         = "main",
        )

        assertEquals(2, state.branches.size)
        val newNode = state.branches["feature-2"]!!
        assertEquals("main", newNode.parent)
    }

    @Test
    fun `with existing state preserves all existing branches`() {
        val existing = stackState("main", "feature-1" to "main")

        val state = action.buildState(
            existing      = existing,
            currentBranch = "feature-2",
            trunk         = "main",
        )

        assertEquals(3, state.branches.size)
        assertEquals("main", state.branches["feature-1"]!!.parent)
        assertEquals("main", state.branches["feature-2"]!!.parent)
    }

    // -------------------------------------------------------------------------
    // No-op cases — returns the same reference (no unnecessary write)
    // -------------------------------------------------------------------------

    @Test
    fun `with existing state does not add already-tracked branch`() {
        val existing = stackState("main", "feature-1" to "main")

        val result = action.buildState(
            existing      = existing,
            currentBranch = "feature-1",
            trunk         = "main",
        )

        assertSame(existing, result)   // same reference → caller skips the write
    }

    @Test
    fun `with existing state does not modify when current branch is trunk`() {
        val existing = stackState("main")

        val result = action.buildState(
            existing      = existing,
            currentBranch = "main",
            trunk         = "main",
        )

        assertSame(existing, result)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal [StackState] with [trunk] as trunk node and optional
     * additional [branches] expressed as (name → parent) pairs.
     */
    private fun stackState(
        trunk: String,
        vararg branches: Pair<String, String>,
    ): StackState {
        val map = mutableMapOf(trunk to BranchNode(name = trunk, parent = null))
        for ((name, parent) in branches) {
            map[name] = BranchNode(name = name, parent = parent)
        }
        return StackState(
            repoConfig = RepoConfig(trunk = trunk, remote = "origin"),
            branches   = map,
        )
    }
}
