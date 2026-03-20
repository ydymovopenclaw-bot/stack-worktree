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

    // ------------------------------------------------------------------
    // Keyboard focus state
    // ------------------------------------------------------------------

    @Test
    fun `focusedNodeIndex is -1 before any interaction`() {
        val panel = StackGraphPanel()
        assertEquals(-1, panel.focusedNodeIndex)
    }

    @Test
    fun `focusedNodeIndex resets to -1 on updateGraph`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"), node("feat", parentId = "main"))))
        // Simulate Down key via action map
        panel.actionMap.get("stackgraph.nav.down")
            .actionPerformed(java.awt.event.ActionEvent(panel, 0, ""))
        assertTrue(panel.focusedNodeIndex >= 0, "Should have moved focus to first node")

        // updateGraph should clear focus
        panel.updateGraph(StackGraphData(listOf(node("main"))))
        assertEquals(-1, panel.focusedNodeIndex)
    }

    // ------------------------------------------------------------------
    // Keyboard navigation — Down
    // ------------------------------------------------------------------

    private fun pressDown(panel: StackGraphPanel) {
        panel.actionMap.get("stackgraph.nav.down")
            .actionPerformed(java.awt.event.ActionEvent(panel, 0, ""))
    }

    private fun pressUp(panel: StackGraphPanel) {
        panel.actionMap.get("stackgraph.nav.up")
            .actionPerformed(java.awt.event.ActionEvent(panel, 0, ""))
    }

    private fun pressEnter(panel: StackGraphPanel) {
        panel.actionMap.get("stackgraph.nav.enter")
            .actionPerformed(java.awt.event.ActionEvent(panel, 0, ""))
    }

    private fun pressSpace(panel: StackGraphPanel) {
        panel.actionMap.get("stackgraph.nav.space")
            .actionPerformed(java.awt.event.ActionEvent(panel, 0, ""))
    }

    @Test
    fun `Down on empty graph is a no-op`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData())
        pressDown(panel) // must not throw
        assertEquals(-1, panel.focusedNodeIndex)
    }

    @Test
    fun `first Down focuses index 0`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"), node("feat", parentId = "main"))))
        pressDown(panel)
        assertEquals(0, panel.focusedNodeIndex)
    }

    @Test
    fun `second Down moves focus to index 1`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"), node("feat", parentId = "main"))))
        pressDown(panel)
        pressDown(panel)
        assertEquals(1, panel.focusedNodeIndex)
    }

    @Test
    fun `Down does not move past last node`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"), node("feat", parentId = "main"))))
        repeat(10) { pressDown(panel) }
        assertEquals(1, panel.focusedNodeIndex)
    }

    // ------------------------------------------------------------------
    // Keyboard navigation — Up
    // ------------------------------------------------------------------

    @Test
    fun `Up on empty graph is a no-op`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData())
        pressUp(panel) // must not throw
        assertEquals(-1, panel.focusedNodeIndex)
    }

    @Test
    fun `first Up focuses index 0`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"), node("feat", parentId = "main"))))
        pressUp(panel)
        assertEquals(0, panel.focusedNodeIndex)
    }

    @Test
    fun `Up does not move before index 0`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"), node("feat", parentId = "main"))))
        pressDown(panel) // go to 0
        repeat(10) { pressUp(panel) }
        assertEquals(0, panel.focusedNodeIndex)
    }

    @Test
    fun `Down then Up returns to first node`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(
            node("main"),
            node("feat", parentId = "main"),
            node("fix",  parentId = "feat"),
        )))
        pressDown(panel) // 0
        pressDown(panel) // 1
        pressDown(panel) // 2
        pressUp(panel)   // 1
        pressUp(panel)   // 0
        assertEquals(0, panel.focusedNodeIndex)
    }

    // ------------------------------------------------------------------
    // Keyboard navigation — Enter (checkout)
    // ------------------------------------------------------------------

    @Test
    fun `Enter with no focused node does not fire onNodeNavigated`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"))))

        var fired = false
        panel.onNodeNavigated = { fired = true }

        pressEnter(panel) // focusedNodeIndex is -1

        assertFalse(fired, "Enter with no focus should not fire onNodeNavigated")
    }

    @Test
    fun `Enter fires onNodeNavigated with focused node`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"), node("feat", parentId = "main"))))

        var received: StackNodeData? = null
        panel.onNodeNavigated = { received = it }

        pressDown(panel) // focus index 0
        pressEnter(panel)

        assertNotNull(received)
        // focusedNodeIndex 0 = first node in visual top-to-bottom order; at minimum it's one of our nodes
        assertTrue(received!!.id == "main" || received!!.id == "feat")
    }

    @Test
    fun `Enter does not change selectedNodeId`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"))))

        pressDown(panel)
        pressEnter(panel)

        // Enter = navigate (checkout); must NOT set selectedNodeId
        assertNull(panel.selectedNodeId)
    }

    // ------------------------------------------------------------------
    // Keyboard navigation — Space (select)
    // ------------------------------------------------------------------

    @Test
    fun `Space with no focused node does not fire onNodeSelected`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"))))

        var fired = false
        panel.onNodeSelected = { fired = true }

        pressSpace(panel) // focusedNodeIndex is -1

        assertFalse(fired, "Space with no focus should not fire onNodeSelected")
    }

    @Test
    fun `Space fires onNodeSelected with focused node`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"), node("feat", parentId = "main"))))

        var received: StackNodeData? = null
        panel.onNodeSelected = { received = it }

        pressDown(panel)
        pressSpace(panel)

        assertNotNull(received)
    }

    @Test
    fun `Space sets selectedNodeId to focused node`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"), node("feat", parentId = "main"))))

        pressDown(panel) // focus index 0 → some node
        pressSpace(panel)

        assertNotNull(panel.selectedNodeId)
    }

    @Test
    fun `Space does not fire onNodeNavigated`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"))))

        var navigated = false
        panel.onNodeNavigated = { navigated = true }

        pressDown(panel)
        pressSpace(panel)

        assertFalse(navigated, "Space must only fire onNodeSelected, not onNodeNavigated")
    }

    // ------------------------------------------------------------------
    // selectNode syncs focusedNodeIndex
    // ------------------------------------------------------------------

    @Test
    fun `selectNode syncs focusedNodeIndex to the selected node`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"), node("feat", parentId = "main"))))

        panel.selectNode("feat")

        // focusedNodeIndex should be ≥ 0 after selectNode
        assertTrue(panel.focusedNodeIndex >= 0, "selectNode should sync focusedNodeIndex")
    }

    @Test
    fun `selectNode on unknown id leaves focusedNodeIndex unchanged`() {
        val panel = StackGraphPanel()
        panel.updateGraph(StackGraphData(listOf(node("main"))))

        // pre-condition: focus index is -1
        assertEquals(-1, panel.focusedNodeIndex)
        panel.selectNode("nonexistent")
        assertEquals(-1, panel.focusedNodeIndex)
    }

    // ------------------------------------------------------------------
    // Action map key names are registered (sanity check)
    // ------------------------------------------------------------------

    @Test
    fun `action map contains all four keyboard navigation actions`() {
        val panel = StackGraphPanel()
        assertNotNull(panel.actionMap.get("stackgraph.nav.down"),  "Missing nav.down action")
        assertNotNull(panel.actionMap.get("stackgraph.nav.up"),    "Missing nav.up action")
        assertNotNull(panel.actionMap.get("stackgraph.nav.enter"), "Missing nav.enter action")
        assertNotNull(panel.actionMap.get("stackgraph.nav.space"), "Missing nav.space action")
    }

    @Test
    fun `panel is focusable`() {
        val panel = StackGraphPanel()
        assertTrue(panel.isFocusable, "StackGraphPanel must be focusable for keyboard navigation")
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
