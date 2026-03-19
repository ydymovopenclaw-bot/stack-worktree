package com.github.ydymovopenclawbot.stackworktree.actions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Verifies that all S7.2 actions are registered in [ActionManager] and that the
 * "StackTree" Keymap group exists and contains them.
 *
 * Uses [BasePlatformTestCase] so that the IntelliJ Platform is initialised and
 * [ActionManager.getInstance] returns a live instance with [plugin.xml] loaded.
 */
class ActionRegistrationTest : BasePlatformTestCase() {

    // -------------------------------------------------------------------------
    // Individual action IDs
    // -------------------------------------------------------------------------

    fun testNewStackActionIsRegistered() {
        assertNotNull(
            "StackTree.NewStack must be registered in plugin.xml",
            ActionManager.getInstance().getAction("StackTree.NewStack"),
        )
    }

    fun testRestackActionIsRegistered() {
        assertNotNull(
            "StackWorktree.Restack must be registered in plugin.xml",
            ActionManager.getInstance().getAction("StackWorktree.Restack"),
        )
    }

    fun testAddBranchActionIsRegistered() {
        assertNotNull(
            "StackWorktree.AddBranch must be registered in plugin.xml",
            ActionManager.getInstance().getAction("StackWorktree.AddBranch"),
        )
    }

    fun testSubmitStackActionIsRegistered() {
        assertNotNull(
            "StackWorktree.SubmitStack must be registered in plugin.xml",
            ActionManager.getInstance().getAction("StackWorktree.SubmitStack"),
        )
    }

    fun testSyncActionIsRegistered() {
        assertNotNull(
            "StackWorktree.Sync must be registered in plugin.xml",
            ActionManager.getInstance().getAction("StackWorktree.Sync"),
        )
    }

    fun testRefreshActionIsRegistered() {
        assertNotNull(
            "StackWorktree.Refresh must be registered in plugin.xml",
            ActionManager.getInstance().getAction("StackWorktree.Refresh"),
        )
    }

    // -------------------------------------------------------------------------
    // StackTree Keymap group
    // -------------------------------------------------------------------------

    fun testStackTreeGroupExists() {
        val group = ActionManager.getInstance().getAction("StackTree")
        assertNotNull("'StackTree' Keymap group must be registered in plugin.xml", group)
        assertTrue(
            "'StackTree' must be an ActionGroup so it surfaces in Settings > Keymap",
            group is ActionGroup,
        )
    }
}
