package com.github.ydymovopenclawbot.stackworktree.ui.actions

import com.github.ydymovopenclawbot.stackworktree.git.GitLayer
import com.github.ydymovopenclawbot.stackworktree.ops.OpsLayer
import com.github.ydymovopenclawbot.stackworktree.state.StateLayer
import com.github.ydymovopenclawbot.stackworktree.ui.TrackBranchDialog
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages

/**
 * Context-menu action that opens [TrackBranchDialog] and adds the chosen branch to
 * the tracked-branch tree under the selected parent.
 *
 * The "Branch" dropdown shows every local branch not already tracked (and not the trunk
 * itself). The "Parent" dropdown shows the trunk plus all currently tracked branches.
 *
 * If [com.github.ydymovopenclawbot.stackworktree.state.PluginState.trunkBranch] is not
 * yet set, it is auto-detected (main → master → first alphabetical branch) and persisted
 * before the dialog is shown.
 */
class TrackBranchAction : AnAction("Track Branch...") {

    override fun actionPerformed(e: AnActionEvent) {
        val project    = e.project ?: return
        val stateLayer = project.service<StateLayer>()
        val opsLayer   = project.service<OpsLayer>()
        val gitLayer   = project.service<GitLayer>()

        var state = stateLayer.load()

        // Auto-detect trunk if not yet configured.
        if (state.trunkBranch == null) {
            val allBranches = gitLayer.listLocalBranches()
            val trunk = allBranches.firstOrNull { it == "main" }
                ?: allBranches.firstOrNull { it == "master" }
                ?: allBranches.firstOrNull()
            if (trunk != null) {
                stateLayer.save(state.copy(trunkBranch = trunk))
                state = stateLayer.load()
            }
        }

        val tracked   = state.trackedBranches.keys
        val allLocal  = gitLayer.listLocalBranches()
        val untracked = allLocal.filter { it !in tracked && it != state.trunkBranch }

        if (untracked.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "All local branches are already tracked.",
                "Track Branch",
            )
            return
        }

        val parentChoices = listOfNotNull(state.trunkBranch) + tracked.sorted()

        val dialog = TrackBranchDialog(project, untracked, parentChoices)
        if (dialog.showAndGet()) {
            opsLayer.trackBranch(dialog.selectedBranch, dialog.selectedParent)
        }
    }
}
