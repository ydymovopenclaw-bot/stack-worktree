package com.github.ydymovopenclawbot.stackworktree.actions

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CreateWorktreeFromBranchActionTest {
    @Test
    fun `action is disabled when branch has worktree`() {
        assertTrue(CreateWorktreeFromBranchAction.shouldDisable("feat/foo", mapOf("feat/foo" to "/tmp/wt")))
    }

    @Test
    fun `action is enabled when branch has no worktree`() {
        assertFalse(CreateWorktreeFromBranchAction.shouldDisable("feat/bar", emptyMap()))
    }
}
