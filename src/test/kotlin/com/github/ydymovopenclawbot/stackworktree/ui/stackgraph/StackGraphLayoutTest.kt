package com.github.ydymovopenclawbot.stackworktree.ui.stackgraph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [StackGraphLayout].
 *
 * These tests verify geometry (positions, sizes, canvas dimensions) and graph
 * topology (edges) produced by the layout engine.  No IntelliJ Platform runtime
 * is required — the layout object has zero Swing/JBColor dependencies.
 */
class StackGraphLayoutTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun node(
        id: String,
        parentId: String? = null,
        status: HealthStatus = HealthStatus.CLEAN,
    ) = StackNodeData(id = id, branchName = id, parentId = parentId, healthStatus = status)

    private val W  = StackGraphLayout.NODE_WIDTH
    private val H  = StackGraphLayout.NODE_HEIGHT
    private val HG = StackGraphLayout.H_GAP
    private val VG = StackGraphLayout.V_GAP
    private val P  = StackGraphLayout.PADDING

    // ------------------------------------------------------------------
    // Empty graph
    // ------------------------------------------------------------------

    @Test
    fun `empty graph returns zero dimensions and empty collections`() {
        val result = StackGraphLayout.compute(StackGraphData())
        assertTrue(result.nodeRects.isEmpty())
        assertTrue(result.edges.isEmpty())
        assertEquals(0, result.canvasWidth)
        assertEquals(0, result.canvasHeight)
    }

    // ------------------------------------------------------------------
    // Single node
    // ------------------------------------------------------------------

    @Test
    fun `single root node placed at top-left with padding`() {
        val result = StackGraphLayout.compute(StackGraphData(listOf(node("main"))))
        val rect = result.nodeRects["main"]!!

        assertEquals(P.toDouble(), rect.x,      0.01)
        assertEquals(P.toDouble(), rect.y,      0.01)
        assertEquals(W.toDouble(), rect.width,  0.01)
        assertEquals(H.toDouble(), rect.height, 0.01)
    }

    @Test
    fun `single node canvas size equals one node plus double padding`() {
        val result = StackGraphLayout.compute(StackGraphData(listOf(node("main"))))
        assertEquals(P * 2 + W, result.canvasWidth)
        assertEquals(P * 2 + H, result.canvasHeight)
    }

    @Test
    fun `single node produces no edges`() {
        val result = StackGraphLayout.compute(StackGraphData(listOf(node("main"))))
        assertTrue(result.edges.isEmpty())
    }

    // ------------------------------------------------------------------
    // Linear chain: main → feature → fix
    // ------------------------------------------------------------------

    @Test
    fun `linear chain — nodes stacked vertically`() {
        val data = StackGraphData(listOf(
            node("main"),
            node("feature", parentId = "main"),
            node("fix",     parentId = "feature"),
        ))
        val result = StackGraphLayout.compute(data)

        // All nodes should be in the same column (x == P)
        assertEquals(P.toDouble(), result.nodeRects["main"]!!.x,    0.01)
        assertEquals(P.toDouble(), result.nodeRects["feature"]!!.x, 0.01)
        assertEquals(P.toDouble(), result.nodeRects["fix"]!!.x,     0.01)

        // Depths: main=0, feature=1, fix=2
        assertEquals(P.toDouble(),              result.nodeRects["main"]!!.y,    0.01)
        assertEquals((P +     H + VG).toDouble(), result.nodeRects["feature"]!!.y, 0.01)
        assertEquals((P + 2 * (H + VG)).toDouble(), result.nodeRects["fix"]!!.y, 0.01)
    }

    @Test
    fun `linear chain — two edges produced in correct direction`() {
        val data = StackGraphData(listOf(
            node("main"),
            node("feature", parentId = "main"),
            node("fix",     parentId = "feature"),
        ))
        val result = StackGraphLayout.compute(data)

        assertTrue(result.edges.contains("main" to "feature"))
        assertTrue(result.edges.contains("feature" to "fix"))
        assertEquals(2, result.edges.size)
    }

    @Test
    fun `linear chain canvas height covers three rows`() {
        val data = StackGraphData(listOf(
            node("main"),
            node("feature", parentId = "main"),
            node("fix",     parentId = "feature"),
        ))
        val result = StackGraphLayout.compute(data)
        val expectedH = P * 2 + 3 * H + 2 * VG
        assertEquals(expectedH, result.canvasHeight)
    }

    // ------------------------------------------------------------------
    // Branching tree: main → (featureA, featureB)
    // ------------------------------------------------------------------

    @Test
    fun `branching tree — siblings placed in adjacent columns`() {
        val data = StackGraphData(listOf(
            node("main"),
            node("featureA", parentId = "main"),
            node("featureB", parentId = "main"),
        ))
        val result = StackGraphLayout.compute(data)

        val xA = result.nodeRects["featureA"]!!.x
        val xB = result.nodeRects["featureB"]!!.x

        // featureA should be left of featureB
        assertTrue(xA < xB, "featureA ($xA) should be left of featureB ($xB)")
        // They should differ by exactly one column step
        assertEquals((W + HG).toDouble(), xB - xA, 0.01)
    }

    @Test
    fun `branching tree — parent centred over two children`() {
        val data = StackGraphData(listOf(
            node("main"),
            node("featureA", parentId = "main"),
            node("featureB", parentId = "main"),
        ))
        val result = StackGraphLayout.compute(data)

        val xA     = result.nodeRects["featureA"]!!.x
        val xB     = result.nodeRects["featureB"]!!.x
        val xMain  = result.nodeRects["main"]!!.x

        // Parent centred: column = (colA + colB) / 2 = (0 + 1) / 2 = 0 (integer division)
        // So xMain == xA for 2 siblings (both columns 0 and 1 → parent gets column 0)
        assertEquals(xA, xMain, 0.01)
    }

    @Test
    fun `branching tree canvas width covers two columns`() {
        val data = StackGraphData(listOf(
            node("main"),
            node("featureA", parentId = "main"),
            node("featureB", parentId = "main"),
        ))
        val result = StackGraphLayout.compute(data)
        val expectedW = P * 2 + 2 * W + 1 * HG
        assertEquals(expectedW, result.canvasWidth)
    }

    @Test
    fun `branching tree — two edges produced`() {
        val data = StackGraphData(listOf(
            node("main"),
            node("featureA", parentId = "main"),
            node("featureB", parentId = "main"),
        ))
        val result = StackGraphLayout.compute(data)

        assertTrue(result.edges.contains("main" to "featureA"))
        assertTrue(result.edges.contains("main" to "featureB"))
        assertEquals(2, result.edges.size)
    }

    // ------------------------------------------------------------------
    // Multiple roots
    // ------------------------------------------------------------------

    @Test
    fun `two independent roots placed side by side at depth zero`() {
        val data = StackGraphData(listOf(node("rootA"), node("rootB")))
        val result = StackGraphLayout.compute(data)

        val yA = result.nodeRects["rootA"]!!.y
        val yB = result.nodeRects["rootB"]!!.y

        // Both at depth 0
        assertEquals(P.toDouble(), yA, 0.01)
        assertEquals(P.toDouble(), yB, 0.01)

        // Different columns
        val xA = result.nodeRects["rootA"]!!.x
        val xB = result.nodeRects["rootB"]!!.x
        assertTrue(xA != xB, "Two roots should be in different columns")
    }

    // ------------------------------------------------------------------
    // Node rect sizes are always constant
    // ------------------------------------------------------------------

    @Test
    fun `every node rect has the standard width and height`() {
        val data = StackGraphData(listOf(
            node("main"),
            node("featureA", parentId = "main"),
            node("featureB", parentId = "main"),
            node("fix",      parentId = "featureA"),
        ))
        val result = StackGraphLayout.compute(data)

        for ((id, rect) in result.nodeRects) {
            assertEquals(W.toDouble(), rect.width,  0.01, "Node '$id' width")
            assertEquals(H.toDouble(), rect.height, 0.01, "Node '$id' height")
        }
    }
}
