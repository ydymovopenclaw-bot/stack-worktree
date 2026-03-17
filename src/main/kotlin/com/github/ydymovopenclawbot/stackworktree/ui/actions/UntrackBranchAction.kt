package com.github.ydymovopenclawbot.stackworktree.ui.actions

import com.github.ydymovopenclawbot.stackworktree.ops.OpsLayer
import com.github.ydymovopenclawbot.stackworktree.state.StateLayer
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

/**
 * Context-menu action that removes the right-clicked branch from the tracked-branch tree.
 *
 * The underlying git branch is **never** deleted. Mid-stack branches have their children
 * re-parented to their grandparent; leaf branches are simply removed.
 *
 * The action is only visible/enabled when [StacksBranchDataKey.KEY] resolves to a
 * branch that is currently in the tracked-branch map (trunk is excluded).
 */
class UntrackBranchAction : AnAction("Untrack Branch") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val branch  = e.getData(StacksBranchDataKey.KEY) ?: return
        project.service<OpsLayer>().untrackBranch(branch)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val branch  = e.getData(StacksBranchDataKey.KEY)
        // Short-circuit: skip the state load entirely when no branch is selected,
        // which is the common case during normal IDE navigation.
        e.presentation.isEnabledAndVisible =
            project != null &&
            branch  != null &&
            branch in project.service<StateLayer>().load().trackedBranches
    }
}
