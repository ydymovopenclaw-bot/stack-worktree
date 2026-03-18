package com.github.ydymovopenclawbot.stackworktree.stack

import com.github.ydymovopenclawbot.stackworktree.state.BranchNode
import com.github.ydymovopenclawbot.stackworktree.state.PrInfo
import com.github.ydymovopenclawbot.stackworktree.state.RepoConfig
import com.github.ydymovopenclawbot.stackworktree.state.StackState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [StackNavTable].
 *
 * All tests are platform-free — no IntelliJ infrastructure required.
 */
class StackNavTableTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun stateOf(trunk: String, vararg pairs: Pair<String, String?>): StackState {
        // pairs = (branchName, parentName)
        val branches = mutableMapOf<String, BranchNode>()

        // Build parent → children mapping.
        val childrenOf = mutableMapOf<String, MutableList<String>>()
        for ((branch, parent) in pairs) {
            if (parent != null) childrenOf.getOrPut(parent) { mutableListOf() }.add(branch)
        }

        // Trunk node.
        branches[trunk] = BranchNode(
            name     = trunk,
            parent   = null,
            children = childrenOf[trunk] ?: emptyList(),
        )

        // Non-trunk nodes.
        for ((branch, parent) in pairs) {
            branches[branch] = BranchNode(
                name     = branch,
                parent   = parent,
                children = childrenOf[branch] ?: emptyList(),
            )
        }

        return StackState(
            repoConfig = RepoConfig(trunk = trunk, remote = "origin"),
            branches   = branches,
        )
    }

    private fun withPrInfo(state: StackState, branch: String, number: Int, url: String): StackState {
        val node = state.branches[branch] ?: return state
        return state.copy(
            branches = state.branches + (branch to node.copy(
                prInfo = PrInfo(
                    provider = "github",
                    id       = number.toString(),
                    url      = url,
                    status   = "open",
                    ciStatus = "pending",
                )
            ))
        )
    }

    // ── collectBfsOrder ───────────────────────────────────────────────────────

    @Test
    fun `collectBfsOrder returns empty list for trunk-only state`() {
        val state = stateOf("main")
        assertEquals(emptyList<String>(), StackNavTable.collectBfsOrder(state))
    }

    @Test
    fun `collectBfsOrder returns single branch`() {
        val state = stateOf("main", "feat/a" to "main")
        assertEquals(listOf("feat/a"), StackNavTable.collectBfsOrder(state))
    }

    @Test
    fun `collectBfsOrder returns linear stack bottom-to-top`() {
        val state = stateOf(
            "main",
            "feat/a" to "main",
            "feat/b" to "feat/a",
            "feat/c" to "feat/b",
        )
        assertEquals(listOf("feat/a", "feat/b", "feat/c"), StackNavTable.collectBfsOrder(state))
    }

    @Test
    fun `collectBfsOrder handles diamond — trunk with two children`() {
        val state = stateOf(
            "main",
            "feat/a" to "main",
            "feat/b" to "main",
        )
        val order = StackNavTable.collectBfsOrder(state)
        assertEquals(setOf("feat/a", "feat/b"), order.toSet())
        assertEquals(2, order.size)
    }

    // ── generate ──────────────────────────────────────────────────────────────

    @Test
    fun `generate returns empty string for trunk-only state`() {
        val state = stateOf("main")
        assertEquals("", StackNavTable.generate(state, "main"))
    }

    @Test
    fun `generate includes header row`() {
        val state = stateOf("main", "feat/a" to "main")
        val table = StackNavTable.generate(state, "feat/a")
        assertTrue(table.contains("| # | Branch | PR |"), "Expected header row in:\n$table")
        assertTrue(table.contains("|---|--------|----|"), "Expected separator row in:\n$table")
    }

    @Test
    fun `generate shows pending for branch without prInfo`() {
        val state = stateOf("main", "feat/a" to "main")
        val table = StackNavTable.generate(state, "feat/a")
        assertTrue(table.contains("_(pending)_"), "Expected pending marker in:\n$table")
    }

    @Test
    fun `generate renders PR link when prInfo present`() {
        val state = withPrInfo(
            stateOf("main", "feat/a" to "main"),
            "feat/a", 42, "https://github.com/org/repo/pull/42"
        )
        val table = StackNavTable.generate(state, "other")
        assertTrue(
            table.contains("[#42](https://github.com/org/repo/pull/42)"),
            "Expected PR link in:\n$table",
        )
    }

    @Test
    fun `generate bolds the current branch row`() {
        val state = stateOf(
            "main",
            "feat/a" to "main",
            "feat/b" to "feat/a",
        )
        val table = StackNavTable.generate(state, "feat/b")
        // feat/b row should be bolded.
        assertTrue(table.contains("**feat/b**"), "Expected bold branch name in:\n$table")
        // feat/a row should NOT be bolded.
        val lines = table.lines()
        val featALine = lines.first { it.contains("feat/a") }
        assertTrue(!featALine.contains("**feat/a**"), "feat/a should not be bold in:\n$featALine")
    }

    @Test
    fun `generate does not bold any row when currentBranch is not in stack`() {
        val state = stateOf("main", "feat/a" to "main")
        val table = StackNavTable.generate(state, "some-other-branch")
        assertTrue(!table.contains("**feat/a**"), "No row should be bolded:\n$table")
    }

    @Test
    fun `generate row numbers are 1-based and sequential`() {
        val state = stateOf(
            "main",
            "feat/a" to "main",
            "feat/b" to "feat/a",
            "feat/c" to "feat/b",
        )
        val table = StackNavTable.generate(state, "feat/a")
        val dataLines = table.lines().filter { it.startsWith("|") && !it.startsWith("| #") && !it.startsWith("|---") }
        assertEquals(3, dataLines.size, "Expected 3 data rows:\n$table")
        assertTrue(dataLines[0].contains("| **1**"), "Row 1 should be feat/a (current, bolded):\n${dataLines[0]}")
        assertTrue(dataLines[1].contains("| 2"), "Row 2 should be feat/b:\n${dataLines[1]}")
        assertTrue(dataLines[2].contains("| 3"), "Row 3 should be feat/c:\n${dataLines[2]}")
    }

    @Test
    fun `generate single-branch stack — current branch is bolded`() {
        val state = stateOf("main", "feat/only" to "main")
        val table = StackNavTable.generate(state, "feat/only")
        assertTrue(table.contains("**feat/only**"), "Single branch should be bolded:\n$table")
        assertTrue(table.contains("| **1**"), "Row number 1 should be bolded:\n$table")
    }

    @Test
    fun `generate mixed — some branches have prInfo, some pending`() {
        val base = stateOf(
            "main",
            "feat/a" to "main",
            "feat/b" to "feat/a",
        )
        val state = withPrInfo(base, "feat/a", 1, "https://github.com/org/repo/pull/1")
        val table = StackNavTable.generate(state, "feat/b")

        // feat/a has a PR link.
        assertTrue(table.contains("[#1](https://github.com/org/repo/pull/1)"), "Expected PR link:\n$table")
        // feat/b (current) shows pending.
        val lines = table.lines()
        val featBLine = lines.first { it.contains("feat/b") }
        assertTrue(featBLine.contains("_(pending)_"), "feat/b should be pending:\n$featBLine")
        assertTrue(featBLine.contains("**feat/b**"), "feat/b should be bolded:\n$featBLine")
    }
}
