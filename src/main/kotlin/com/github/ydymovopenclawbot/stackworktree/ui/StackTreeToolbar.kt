package com.github.ydymovopenclawbot.stackworktree.ui

import com.github.ydymovopenclawbot.stackworktree.actions.NewStackAction
import com.github.ydymovopenclawbot.stackworktree.ui.actions.AddBranchAction
import com.github.ydymovopenclawbot.stackworktree.ui.actions.RefreshStacksAction
import com.github.ydymovopenclawbot.stackworktree.ui.actions.RestackAction
import com.github.ydymovopenclawbot.stackworktree.ui.actions.SubmitAction
import com.github.ydymovopenclawbot.stackworktree.ui.actions.SyncAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup

/**
 * Factory for the StackTree toolbar.
 *
 * Button layout (left → right):
 * ```
 * [New Stack] [Add Branch] | [Restack*] [Submit*] [Sync*] | [Refresh]
 * ```
 * Buttons marked with `*` are disabled with explanatory tooltips until implemented.
 */
object StackTreeToolbar {

    /**
     * Builds and returns a horizontal [ActionToolbar] for the Stacks tab.
     *
     * @param place     The action place string forwarded to [ActionManager.createActionToolbar].
     *                  Should be a stable constant (e.g. `"StacksTab"`).
     * @param onRefresh Callback invoked when the Refresh button is clicked.
     */
    fun create(place: String, onRefresh: () -> Unit): ActionToolbar {
        val group = DefaultActionGroup().apply {
            add(NewStackAction())
            add(AddBranchAction())
            addSeparator()
            add(RestackAction())
            add(SubmitAction())
            add(SyncAction())
            addSeparator()
            add(RefreshStacksAction(onRefresh))
        }
        return ActionManager.getInstance().createActionToolbar(place, group, /* horizontal */ true)
    }
}
