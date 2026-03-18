package com.github.ydymovopenclawbot.stackworktree.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Verifies [StateLayerImpl] save/load round-trips without field loss. */
class StateLayerImplTest {

    @Test
    fun `save and load round-trip preserves all fields`() {
        val layer = StateLayerImpl()
        val original = PluginState(
            trunkBranch = "main",
            trackedBranches = mapOf(
                "feat-a" to TrackedBranchNode(
                    name = "feat-a",
                    parentName = "main",
                    children = listOf("feat-b"),
                ),
                "feat-b" to TrackedBranchNode(
                    name = "feat-b",
                    parentName = "feat-a",
                    children = emptyList(),
                ),
            ),
            activeWorktrees = listOf("/tmp/wt-a", "/tmp/wt-b"),
            lastUsedBranch = "feat-a",
        )

        layer.save(original)
        val loaded = layer.load()

        assertEquals(original.trunkBranch, loaded.trunkBranch)
        assertEquals(original.trackedBranches.size, loaded.trackedBranches.size)
        assertEquals(original.activeWorktrees, loaded.activeWorktrees)
        assertEquals(original.lastUsedBranch, loaded.lastUsedBranch)

        for ((name, node) in original.trackedBranches) {
            val loadedNode = loaded.trackedBranches[name]!!
            assertEquals(node.name, loadedNode.name)
            assertEquals(node.parentName, loadedNode.parentName)
            assertEquals(node.children, loadedNode.children)
        }
    }

    @Test
    fun `save and load round-trip with empty state`() {
        val layer = StateLayerImpl()
        val original = PluginState()

        layer.save(original)
        val loaded = layer.load()

        assertEquals(original.trunkBranch, loaded.trunkBranch)
        assertEquals(original.trackedBranches, loaded.trackedBranches)
        assertEquals(original.activeWorktrees, loaded.activeWorktrees)
        assertEquals(original.lastUsedBranch, loaded.lastUsedBranch)
    }

    @Test
    fun `XML state round-trip preserves data through getState and loadState`() {
        val layer = StateLayerImpl()
        val original = PluginState(
            trunkBranch = "develop",
            trackedBranches = mapOf(
                "fix-1" to TrackedBranchNode("fix-1", "develop", listOf("fix-2")),
                "fix-2" to TrackedBranchNode("fix-2", "fix-1"),
            ),
            activeWorktrees = listOf("/wt"),
            lastUsedBranch = "fix-2",
        )

        layer.save(original)
        val xmlSnapshot = layer.state

        // Simulate IDE restart: create a new instance and feed it the XML state.
        val layer2 = StateLayerImpl()
        layer2.loadState(xmlSnapshot)
        val loaded = layer2.load()

        assertEquals(original.trunkBranch, loaded.trunkBranch)
        assertEquals(original.trackedBranches.keys, loaded.trackedBranches.keys)
        assertEquals(original.activeWorktrees, loaded.activeWorktrees)
        assertEquals(original.lastUsedBranch, loaded.lastUsedBranch)
    }
}
