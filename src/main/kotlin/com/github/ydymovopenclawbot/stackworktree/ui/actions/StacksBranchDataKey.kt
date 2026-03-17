package com.github.ydymovopenclawbot.stackworktree.ui.actions

import com.intellij.openapi.actionSystem.DataKey

/**
 * [DataKey] carrying the branch name of the node currently right-clicked in the
 * Stacks graph panel. Used by [TrackBranchAction] and [UntrackBranchAction] to
 * receive context from the popup menu's [com.intellij.openapi.actionSystem.DataContext].
 */
object StacksBranchDataKey {
    val KEY: DataKey<String> = DataKey.create("StacksTab.SelectedBranch")
}
