package com.github.ydymovopenclawbot.stackworktree.ui

import com.github.ydymovopenclawbot.stackworktree.git.AheadBehind
import com.github.ydymovopenclawbot.stackworktree.state.BranchNode
import com.github.ydymovopenclawbot.stackworktree.state.RepoConfig
import com.github.ydymovopenclawbot.stackworktree.state.StackState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StackGraphModelTest {

    private val model = StackGraphModel()

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a [StackState] from one or more branch chains expressed as ordered
     * lists (parent-first). For example `listOf("main","A","B")` means main→A→B.
     *
     * Chains that share a prefix automatically create a fork. The trunk is the
     * branch that appears as the first element of the first chain.
     *
     * @param worktrees Optional map of branch → worktree path for branches with
     *                  an active worktree checked out.
     */
    private fun makeState(
        vararg chains: List<String>,
        worktrees: Map<String, String> = emptyMap(),
    ): StackState {
        // Collect all branch names and build parent / children maps
        val childrenOf = LinkedHashMap<String, MutableList<String>>()
        val parentOf = LinkedHashMap<String, String?>()

        for (chain in chains) {
            for (branch in chain) {
                childrenOf.getOrPut(branch) { mutableListOf() }
                parentOf.putIfAbsent(branch, null)
            }
            for (i in 0 until chain.size - 1) {
                val parent = chain[i]
                val child = chain[i + 1]
                if (child !in (childrenOf[parent] ?: emptyList<String>())) {
                    childrenOf.getOrPut(parent) { mutableListOf() }.add(child)
                }
                parentOf[child] = parent
            }
        }

        val trunk = chains.first().first()
        val branches = childrenOf.keys.associateWith { branch ->
            BranchNode(
                name = branch,
                parent = parentOf[branch],
                children = childrenOf[branch] ?: emptyList(),
                worktreePath = worktrees[branch],
            )
        }
        return StackState(
            repoConfig = RepoConfig(trunk = trunk, remote = "origin"),
            branches = branches,
        )
    }

    // -------------------------------------------------------------------------
    // AC 1: Linear stack A→B→C→D produces 4 nodes in a vertical line
    // -------------------------------------------------------------------------

    @Test
    fun `linear stack produces nodes in a single vertical column`() {
        val state = makeState(listOf("A", "B", "C", "D"))
        val nodes = model.buildGraph(state).associateBy { it.branch }

        assertEquals(4, nodes.size)
        // Depth increases top-to-bottom
        assertEquals(0, nodes["A"]!!.y)
        assertEquals(1, nodes["B"]!!.y)
        assertEquals(2, nodes["C"]!!.y)
        assertEquals(3, nodes["D"]!!.y)
        // All nodes share the same column
        val columns = nodes.values.map { it.x }.toSet()
        assertEquals(1, columns.size, "All nodes in a linear stack must share one column")
    }

    // -------------------------------------------------------------------------
    // AC 2: Forked stack A→B and A→C places B and C side by side below A
    // -------------------------------------------------------------------------

    @Test
    fun `forked stack places siblings at same depth with different columns`() {
        val state = makeState(listOf("A", "B"), listOf("A", "C"))
        val nodes = model.buildGraph(state).associateBy { it.branch }

        assertEquals(3, nodes.size)
        assertEquals(0, nodes["A"]!!.y)
        assertEquals(1, nodes["B"]!!.y)
        assertEquals(1, nodes["C"]!!.y)
        assertNotEquals(nodes["B"]!!.x, nodes["C"]!!.x, "Sibling nodes must have different x")
    }

    @Test
    fun `forked stack — trunk is centred over two siblings`() {
        val state = makeState(listOf("A", "B"), listOf("A", "C"))
        val nodes = model.buildGraph(state).associateBy { it.branch }

        val bx = nodes["B"]!!.x
        val cx = nodes["C"]!!.x
        val ax = nodes["A"]!!.x
        assertTrue(
            ax in minOf(bx, cx)..maxOf(bx, cx),
            "Trunk x=$ax must lie between children x=$bx and x=$cx",
        )
    }

    // -------------------------------------------------------------------------
    // AC 3: Each node carries ahead/behind text, health-based colour, worktree flag
    // -------------------------------------------------------------------------

    @Test
    fun `ahead-behind text is formatted correctly`() {
        val state = makeState(listOf("main", "feat"))
        val ab = mapOf("feat" to AheadBehind(ahead = 3, behind = 1))
        val nodes = model.buildGraph(state, ab).associateBy { it.branch }

        assertEquals("+3 / -1", nodes["feat"]!!.aheadBehindText)
    }

    @Test
    fun `ahead-behind text is empty when data is absent`() {
        val state = makeState(listOf("main", "feat"))
        val nodes = model.buildGraph(state).associateBy { it.branch }

        assertEquals("", nodes["main"]!!.aheadBehindText)
        assertEquals("", nodes["feat"]!!.aheadBehindText)
    }

    @Test
    fun `health is UNKNOWN when ahead-behind data is absent`() {
        val state = makeState(listOf("main"))
        val nodes = model.buildGraph(state).associateBy { it.branch }

        assertEquals(NodeHealth.UNKNOWN, nodes["main"]!!.health)
    }

    @Test
    fun `health is HEALTHY when behind is zero`() {
        val state = makeState(listOf("main", "feat"))
        val ab = mapOf("feat" to AheadBehind(ahead = 5, behind = 0))
        val nodes = model.buildGraph(state, ab).associateBy { it.branch }

        assertEquals(NodeHealth.HEALTHY, nodes["feat"]!!.health)
    }

    @Test
    fun `health is NEEDS_REBASE when behind is between 1 and 5 inclusive`() {
        val state = makeState(listOf("main", "feat"))
        for (behind in 1..5) {
            val ab = mapOf("feat" to AheadBehind(ahead = 0, behind = behind))
            val nodes = model.buildGraph(state, ab).associateBy { it.branch }
            assertEquals(
                NodeHealth.NEEDS_REBASE, nodes["feat"]!!.health,
                "Expected NEEDS_REBASE for behind=$behind",
            )
        }
    }

    @Test
    fun `health is CONFLICT when behind exceeds 5`() {
        val state = makeState(listOf("main", "feat"))
        val ab = mapOf("feat" to AheadBehind(ahead = 2, behind = 10))
        val nodes = model.buildGraph(state, ab).associateBy { it.branch }

        assertEquals(NodeHealth.CONFLICT, nodes["feat"]!!.health)
    }

    @Test
    fun `worktree indicator is set for branches with an active worktree`() {
        val state = makeState(
            listOf("main", "feat"),
            worktrees = mapOf("feat" to "/home/user/feat"),
        )
        val nodes = model.buildGraph(state).associateBy { it.branch }

        assertEquals(false, nodes["main"]!!.hasWorktree)
        assertEquals(true, nodes["feat"]!!.hasWorktree)
    }

    // -------------------------------------------------------------------------
    // AC 4: Layout algorithm unit tests with various stack shapes
    // -------------------------------------------------------------------------

    @Test
    fun `edges connect each parent to its direct children by branch name`() {
        val state = makeState(listOf("A", "B"), listOf("A", "C"))
        val nodes = model.buildGraph(state).associateBy { it.branch }

        assertEquals(listOf("B", "C"), nodes["A"]!!.edgesTo.sorted())
        assertEquals(emptyList<String>(), nodes["B"]!!.edgesTo)
        assertEquals(emptyList<String>(), nodes["C"]!!.edgesTo)
    }

    @Test
    fun `asymmetric tree — subtrees do not share columns`() {
        // A → B → D
        //   → C
        val state = makeState(listOf("A", "B", "D"), listOf("A", "C"))
        val nodes = model.buildGraph(state).associateBy { it.branch }

        assertEquals(4, nodes.size)
        assertEquals(0, nodes["A"]!!.y)
        assertEquals(1, nodes["B"]!!.y)
        assertEquals(1, nodes["C"]!!.y)
        assertEquals(2, nodes["D"]!!.y)
        // B and C must be in different columns
        assertNotEquals(nodes["B"]!!.x, nodes["C"]!!.x)
        // D must be in the same column as B (B has exactly one child)
        assertEquals(nodes["B"]!!.x, nodes["D"]!!.x)
    }

    @Test
    fun `wide fork — three siblings have distinct columns`() {
        // A → B, C, D
        val state = makeState(listOf("A", "B"), listOf("A", "C"), listOf("A", "D"))
        val nodes = model.buildGraph(state).associateBy { it.branch }

        val siblingColumns = listOf(nodes["B"]!!.x, nodes["C"]!!.x, nodes["D"]!!.x)
        assertEquals(3, siblingColumns.toSet().size, "Three siblings must occupy three distinct columns")
    }

    @Test
    fun `empty branches map returns empty graph`() {
        val state = StackState(
            repoConfig = RepoConfig(trunk = "main", remote = "origin"),
            branches = emptyMap(),
        )
        assertTrue(model.buildGraph(state).isEmpty())
    }

    @Test
    fun `single branch stack produces one node at origin`() {
        val state = makeState(listOf("main"))
        val nodes = model.buildGraph(state)

        assertEquals(1, nodes.size)
        assertEquals("main", nodes[0].branch)
        assertEquals(0, nodes[0].x)
        assertEquals(0, nodes[0].y)
        assertTrue(nodes[0].edgesTo.isEmpty())
    }
}
