package com.github.ydymovopenclawbot.stackworktree.ops

import com.github.ydymovopenclawbot.stackworktree.state.BranchNode
import com.github.ydymovopenclawbot.stackworktree.state.RepoConfig
import com.github.ydymovopenclawbot.stackworktree.state.StackState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure state-building functions in [StackStateOperations].
 *
 * No IntelliJ Platform runtime or git process is required — these functions are
 * deterministic transformations of [StackState] data.
 */
class StackStateOperationsTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private val config = RepoConfig(trunk = "main", remote = "origin")

    /** Constructs a [StackState] from a vararg list of [BranchNode]s. */
    private fun stateOf(vararg nodes: BranchNode) =
        StackState(repoConfig = config, branches = nodes.associateBy { it.name })

    // -------------------------------------------------------------------------
    // buildStateForInsertAbove — basic linear chain
    // -------------------------------------------------------------------------

    @Test
    fun `insertAbove - new branch sits between parent and target`() {
        // main → feat
        val state = stateOf(
            BranchNode("main", parent = null, children = listOf("feat")),
            BranchNode("feat", parent = "main"),
        )

        val next = buildStateForInsertAbove(state, target = "feat", newBranch = "mid", oldParent = "main")

        // main's children: [feat] → [mid]
        assertEquals(listOf("mid"), next.branches["main"]!!.children)
        // mid's parent = main, children = [feat]
        assertEquals("main", next.branches["mid"]!!.parent)
        assertEquals(listOf("feat"), next.branches["mid"]!!.children)
        // feat's parent = mid
        assertEquals("mid", next.branches["feat"]!!.parent)
    }

    @Test
    fun `insertAbove - target is root (no parent)`() {
        // Single root branch, no parent
        val state = stateOf(BranchNode("main", parent = null))

        val next = buildStateForInsertAbove(state, target = "main", newBranch = "before-main", oldParent = null)

        // before-main has no parent (it IS the new root)
        assertNull(next.branches["before-main"]!!.parent)
        assertEquals(listOf("main"), next.branches["before-main"]!!.children)
        // main's parent is now before-main
        assertEquals("before-main", next.branches["main"]!!.parent)
    }

    @Test
    fun `insertAbove - parent retains other children`() {
        // main → feat, main → hotfix  (two children)
        val state = stateOf(
            BranchNode("main", parent = null, children = listOf("feat", "hotfix")),
            BranchNode("feat",   parent = "main"),
            BranchNode("hotfix", parent = "main"),
        )

        // Insert "mid" above "feat" only
        val next = buildStateForInsertAbove(state, target = "feat", newBranch = "mid", oldParent = "main")

        // main's children: feat replaced by mid, hotfix stays
        assertEquals(listOf("mid", "hotfix"), next.branches["main"]!!.children)
        // hotfix parent unchanged
        assertEquals("main", next.branches["hotfix"]!!.parent)
    }

    @Test
    fun `insertAbove - does not mutate the original state`() {
        val state = stateOf(
            BranchNode("main", parent = null, children = listOf("feat")),
            BranchNode("feat", parent = "main"),
        )
        val originalBranches = state.branches.toMap()

        buildStateForInsertAbove(state, "feat", "mid", "main")

        assertEquals(originalBranches, state.branches, "Original state must not be mutated")
    }

    // -------------------------------------------------------------------------
    // buildStateForInsertBelow — basic cases
    // -------------------------------------------------------------------------

    @Test
    fun `insertBelow - new branch sits between target and its children`() {
        // main → feat → fix
        val state = stateOf(
            BranchNode("main", parent = null, children = listOf("feat")),
            BranchNode("feat", parent = "main", children = listOf("fix")),
            BranchNode("fix",  parent = "feat"),
        )

        val next = buildStateForInsertBelow(state, target = "feat", newBranch = "mid", children = listOf("fix"))

        // feat's children: [fix] → [mid]
        assertEquals(listOf("mid"), next.branches["feat"]!!.children)
        // mid's parent = feat, children = [fix]
        assertEquals("feat", next.branches["mid"]!!.parent)
        assertEquals(listOf("fix"), next.branches["mid"]!!.children)
        // fix's parent = mid
        assertEquals("mid", next.branches["fix"]!!.parent)
    }

    @Test
    fun `insertBelow - leaf branch (no children)`() {
        val state = stateOf(
            BranchNode("main", parent = null, children = listOf("feat")),
            BranchNode("feat", parent = "main"),
        )

        val next = buildStateForInsertBelow(state, target = "feat", newBranch = "after-feat", children = emptyList())

        assertEquals(listOf("after-feat"), next.branches["feat"]!!.children)
        assertEquals("feat", next.branches["after-feat"]!!.parent)
        assertEquals(emptyList<String>(), next.branches["after-feat"]!!.children)
    }

    @Test
    fun `insertBelow - multiple children are all re-parented`() {
        // main → feat → (c1, c2, c3)
        val state = stateOf(
            BranchNode("main", parent = null, children = listOf("feat")),
            BranchNode("feat", parent = "main", children = listOf("c1", "c2", "c3")),
            BranchNode("c1", parent = "feat"),
            BranchNode("c2", parent = "feat"),
            BranchNode("c3", parent = "feat"),
        )

        val next = buildStateForInsertBelow(state, "feat", "mid", listOf("c1", "c2", "c3"))

        assertEquals(listOf("mid"), next.branches["feat"]!!.children)
        assertEquals(listOf("c1", "c2", "c3"), next.branches["mid"]!!.children)
        for (child in listOf("c1", "c2", "c3")) {
            assertEquals("mid", next.branches[child]!!.parent, "$child parent")
        }
    }

    @Test
    fun `insertBelow - does not mutate the original state`() {
        val state = stateOf(
            BranchNode("main", parent = null, children = listOf("feat")),
            BranchNode("feat", parent = "main", children = listOf("fix")),
            BranchNode("fix",  parent = "feat"),
        )
        val originalBranches = state.branches.toMap()

        buildStateForInsertBelow(state, "feat", "mid", listOf("fix"))

        assertEquals(originalBranches, state.branches, "Original state must not be mutated")
    }

    // -------------------------------------------------------------------------
    // Round-trip: above then below restore structural invariants
    // -------------------------------------------------------------------------

    @Test
    fun `insertAbove then insertBelow restores equivalent shape`() {
        // main → feat
        val initial = stateOf(
            BranchNode("main", parent = null, children = listOf("feat")),
            BranchNode("feat", parent = "main"),
        )

        // Insert "mid" above "feat": main → mid → feat
        val afterAbove = buildStateForInsertAbove(initial, "feat", "mid", "main")
        assertEquals("main", afterAbove.branches["mid"]!!.parent)
        assertEquals(listOf("feat"), afterAbove.branches["mid"]!!.children)

        // Insert "after-mid" below "mid": main → mid → after-mid → feat
        val afterBelow = buildStateForInsertBelow(afterAbove, "mid", "after-mid", listOf("feat"))
        assertEquals("mid",       afterBelow.branches["after-mid"]!!.parent)
        assertEquals(listOf("feat"), afterBelow.branches["after-mid"]!!.children)
        assertEquals("after-mid", afterBelow.branches["feat"]!!.parent)
    }
}
