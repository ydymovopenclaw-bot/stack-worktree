package com.github.ydymovopenclawbot.stackworktree.ops

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Tests for [SyncResult.summaryMessage]. */
class SyncResultTest {

    @Test
    fun `zero merged, no rebase needed, no pruned`() {
        val result = SyncResult(emptyList(), emptyList(), emptyList())
        assertEquals("Synced: 0 merged", result.summaryMessage())
    }

    @Test
    fun `merged branches only`() {
        val result = SyncResult(listOf("feat-a", "feat-b"), emptyList(), emptyList())
        assertEquals("Synced: 2 merged", result.summaryMessage())
    }

    @Test
    fun `merged with rebase needed`() {
        val result = SyncResult(
            listOf("feat-a"),
            emptyList(),
            listOf(
                BranchStatus("b", 3, 2),
                BranchStatus("c", 1, 0),
                BranchStatus("d", 0, 5),
            ),
        )
        assertEquals("Synced: 1 merged, 2 need rebase", result.summaryMessage())
    }

    @Test
    fun `single worktree pruned uses singular`() {
        val result = SyncResult(listOf("feat"), listOf("/tmp/wt"), emptyList())
        assertEquals("Synced: 1 merged, 1 worktree pruned", result.summaryMessage())
    }

    @Test
    fun `multiple worktrees pruned uses plural`() {
        val result = SyncResult(listOf("a", "b"), listOf("/tmp/a", "/tmp/b"), emptyList())
        assertEquals("Synced: 2 merged, 2 worktrees pruned", result.summaryMessage())
    }

    @Test
    fun `all segments combined`() {
        val result = SyncResult(
            listOf("a", "b"),
            listOf("/tmp/a", "/tmp/b", "/tmp/c"),
            listOf(BranchStatus("x", 0, 1)),
        )
        assertEquals("Synced: 2 merged, 1 need rebase, 3 worktrees pruned", result.summaryMessage())
    }

    @Test
    fun `no rebase needed when all behindCount is zero`() {
        val result = SyncResult(
            emptyList(),
            emptyList(),
            listOf(BranchStatus("a", 5, 0), BranchStatus("b", 3, 0)),
        )
        assertEquals("Synced: 0 merged", result.summaryMessage())
    }
}
