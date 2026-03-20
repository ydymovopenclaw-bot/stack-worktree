package com.github.ydymovopenclawbot.stackworktree.ui

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CreateWorktreeDialogTest {

    // ── validatePath ─────────────────────────────────────────────────────────

    @Test
    fun `rejects blank worktree path`() {
        assertNotNull(CreateWorktreeDialog.validatePath("   "))
    }

    @Test
    fun `accepts valid worktree path`() {
        assertNull(CreateWorktreeDialog.validatePath("/tmp/my-worktree"))
    }

    @Test
    fun `rejects path already in use by another worktree`() {
        val existing = listOf("/tmp/wt-one", "/tmp/wt-two")
        assertNotNull(CreateWorktreeDialog.validatePath("/tmp/wt-one", existing))
    }

    @Test
    fun `accepts path not in use by another worktree`() {
        val existing = listOf("/tmp/wt-one", "/tmp/wt-two")
        assertNull(CreateWorktreeDialog.validatePath("/tmp/wt-three", existing))
    }

    // ── validateNewBranch ────────────────────────────────────────────────────

    @Test
    fun `rejects invalid new branch name`() {
        val existingBranches = listOf("main", "feature/one")
        assertNotNull(CreateWorktreeDialog.validateNewBranch("feature..bar", existingBranches))
    }

    @Test
    fun `rejects duplicate branch name`() {
        val existingBranches = listOf("main", "feature/one")
        assertNotNull(CreateWorktreeDialog.validateNewBranch("feature/one", existingBranches))
    }

    @Test
    fun `accepts valid new branch name`() {
        val existingBranches = listOf("main", "feature/one")
        assertNull(CreateWorktreeDialog.validateNewBranch("feature/two", existingBranches))
    }

    // ── validateExistingBranch ───────────────────────────────────────────────

    @Test
    fun `rejects branch that already has worktree`() {
        val worktreeBranches = setOf("feature/one")
        assertNotNull(CreateWorktreeDialog.validateExistingBranch("feature/one", worktreeBranches))
    }

    @Test
    fun `accepts branch without worktree`() {
        val worktreeBranches = setOf("feature/one")
        assertNull(CreateWorktreeDialog.validateExistingBranch("feature/two", worktreeBranches))
    }
}
