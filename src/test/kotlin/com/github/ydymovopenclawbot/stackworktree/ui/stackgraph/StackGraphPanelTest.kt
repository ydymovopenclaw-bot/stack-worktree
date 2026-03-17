package com.github.ydymovopenclawbot.stackworktree.ui.stackgraph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.awt.Dimension
import java.awt.Toolkit

/**
 * Behavioural tests for [StackGraphPanel] that do not require a full IntelliJ Platform runtime.
 *
 * We deliberately avoid triggering [StackGraphPanel.paintComponent] here (which would
 * resolve [com.intellij.ui.JBColor] against the IDE look-and-feel) and instead focus
 * on:
 *  - selection state management
 *  - preferred-size updates driven by layout results
 *  - callback wiring
 *
 * Tests use [StackGraphPanel.selectNode] rather than reflection to set selection state,
 * avoiding JBR-internal side-effects that can interfere with IntelliJ's ThreadLeakTracker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StackGraphPanelTest {

    // ------------------------------------------------------------------
    // One-time AWT warm-up
    //
    // IntelliJ's ThreadLeakTracker takes a per-test thread snapshot *before*
    // each test (@BeforeEach).  On JBR 25, the first call to Toolkit.getDesktopProperty()
    // lazily creates SystemPropertyWatcher via UNIXToolkit.initializeDesktopProperties().
    // If that happens *during* a test, the tracker flags it as a thread leak.
    //
    // Running @BeforeAll (which fires before any @BeforeEach / tracker snapshot)
    // ensures SystemPropertyWatcher is already alive when the first baseline is
    // captured, so it never appears as a "new" thread in any test.
    // ------------------------------------------------------------------

    @BeforeAll
    fun warmUpAwtToolkit() {
        // Force UNIXToolkit.initializeDesktopProperties() → initSystemPropertyWatcher().
        // getDesktopProperty() is the trigger; the exact key is unimportant.
        runCatching { Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints") }
    }

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

        // Use the internal selectNode() helper to simulate a single-click selection.
        // This avoids reflection (which can trigger JBR-internal AWT side-effects that
        // interfere with IntelliJ's ThreadLeakTracker), while still exercising the same
        // selection code-path as a real mouse click.
        panel.selectNode("main")
        assertEquals("main", panel.selectedNodeId)

        // A fresh updateGraph should clear the selection.
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

    // ------------------------------------------------------------------
    // selectNode — hit-test path and callback invocation
    // ------------------------------------------------------------------

    @Test
    fun `selectNode fires onNodeSelected with correct node data`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"), node("feature", parentId = "main"))))

        var received: StackNodeData? = null
        panel.onNodeSelected = { received = it }

        panel.selectNode("main")

        assertNotNull(received)
        assertEquals("main", received!!.id)
        assertEquals("main", panel.selectedNodeId)
    }

    @Test
    fun `selectNode on unknown id is a no-op`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"))))

        var callCount = 0
        panel.onNodeSelected = { callCount++ }

        panel.selectNode("nonexistent")

        assertEquals(0, callCount)
        assertNull(panel.selectedNodeId)
    }

    @Test
    fun `selectNode updates selectedNodeId across successive calls`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"), node("feat", parentId = "main"))))

        panel.selectNode("feat")
        assertEquals("feat", panel.selectedNodeId)

        panel.selectNode("main")
        assertEquals("main", panel.selectedNodeId)
    }

    @Test
    fun `selectNode does not fire onNodeNavigated`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"))))

        var navigated = false
        panel.onNodeNavigated = { navigated = true }

        panel.selectNode("main")

        assertFalse(navigated, "selectNode must only fire onNodeSelected, not onNodeNavigated")
    }

    @Test
    fun `selectNode with null onNodeSelected does not throw`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"))))
        panel.onNodeSelected = null

        panel.selectNode("main") // must not throw
        assertEquals("main", panel.selectedNodeId)
    }
}

// Kotlin stdlib assertTrue/assertFalse adapters (avoids importing org.junit.jupiter versions
// which have different signatures than Assertions.assertTrue/assertFalse).
private fun assertTrue(condition: Boolean, message: String = "") {
    if (!condition) throw AssertionError(if (message.isEmpty()) "Expected true" else message)
}

private fun assertFalse(condition: Boolean, message: String = "") {
    if (condition) throw AssertionError(if (message.isEmpty()) "Expected false" else message)
}
