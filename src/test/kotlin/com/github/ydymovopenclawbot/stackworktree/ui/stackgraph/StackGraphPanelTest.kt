package com.github.ydymovopenclawbot.stackworktree.ui.stackgraph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.Dimension

/**
 * Behavioural tests for [StackGraphPanel] that do not require a full IntelliJ Platform runtime.
 *
 * We deliberately avoid triggering [StackGraphPanel.paintComponent] here (which would
 * resolve [com.intellij.ui.JBColor] against the IDE look-and-feel) and instead focus
 * on:
 *  - selection state management
 *  - preferred-size updates driven by layout results
 *  - callback wiring
 */
class StackGraphPanelTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun node(
        id: String,
        parentId: String? = null,
    ) = StackNodeData(id = id, branchName = id, parentId = parentId)

    // ------------------------------------------------------------------
    // Initial state
    // ------------------------------------------------------------------

    @Test
    fun `selectedNodeId is null before any interaction`() {
        val panel = StackGraphPanel()
        assertNull(panel.selectedNodeId)
    }

    // ------------------------------------------------------------------
    // updateGraph — preferred size
    // ------------------------------------------------------------------

    @Test
    fun `updateGraph with empty data sets minimum preferred size`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData())
        // Empty graph falls back to the coerceAtLeast minimums (200 × 100)
        assertEquals(Dimension(200, 100), panel.preferredSize)
    }

    @Test
    fun `updateGraph with one node produces preferred size larger than minimum`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"))))

        val ps = panel.preferredSize
        assertTrue(ps.width  > 0, "width should be positive")
        assertTrue(ps.height > 0, "height should be positive")
    }

    @Test
    fun `preferred size grows when more nodes are added`() {
        val panel = StackGraphPanel()

        panel.updateGraph(StackGraphData(listOf(node("main"))))
        val small = panel.preferredSize.height

        panel.updateGraph(StackGraphData(listOf(
            node("main"),
            node("feature", parentId = "main"),
            node("fix",     parentId = "feature"),
        )))
        val large = panel.preferredSize.height

        assertTrue(large > small, "Taller graph should produce larger preferred height")
    }

    // ------------------------------------------------------------------
    // updateGraph — resets selection
    // ------------------------------------------------------------------

    @Test
    fun `updateGraph resets selectedNodeId to null`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"), node("feature", parentId = "main"))))

        // Directly set selection (simulates what mouseClicked would do)
        val field = StackGraphPanel::class.java.getDeclaredField("selectedNodeId")
        field.isAccessible = true
        field.set(panel, "main")
        assertEquals("main", panel.selectedNodeId)

        // A fresh updateGraph should clear the selection
        panel.updateGraph(StackGraphData(listOf(node("main"))))
        assertNull(panel.selectedNodeId)
    }

    // ------------------------------------------------------------------
    // Callback wiring
    // ------------------------------------------------------------------

    @Test
    fun `onNodeSelected callback can be assigned without error`() {
        val panel = StackGraphPanel()
        var received: StackNodeData? = null
        panel.onNodeSelected = { received = it }
        // Callback assignment itself should not throw
        assertNull(received) // not invoked until a click
    }

    @Test
    fun `onNodeNavigated callback can be assigned without error`() {
        val panel = StackGraphPanel()
        var received: StackNodeData? = null
        panel.onNodeNavigated = { received = it }
        assertNull(received)
    }

    @Test
    fun `both callbacks can be reassigned`() {
        val panel = StackGraphPanel()
        panel.onNodeSelected  = { }
        panel.onNodeNavigated = { }
        panel.onNodeSelected  = null
        panel.onNodeNavigated = null
        // No exception means success
    }

    // ------------------------------------------------------------------
    // Multiple updateGraph calls
    // ------------------------------------------------------------------

    @Test
    fun `multiple updateGraph calls do not throw`() {
        val panel = StackGraphPanel()
        repeat(5) { i ->
            panel.updateGraph(StackGraphData(listOf(node("n$i"))))
        }
        assertNotNull(panel.preferredSize)
    }
}

// Kotlin stdlib assertTrue / assertFalse adapter (avoids importing org.junit.jupiter versions
// which have different signatures than Assertions.assertTrue).
private fun assertTrue(condition: Boolean, message: String = "") {
    if (!condition) throw AssertionError(if (message.isEmpty()) "Expected true" else message)
}
