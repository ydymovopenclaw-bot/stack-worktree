package com.github.ydymovopenclawbot.stackworktree.actions

import com.github.ydymovopenclawbot.stackworktree.git.Worktree
import com.intellij.openapi.actionSystem.DataKey

/**
 * IntelliJ [DataKey] constants used to propagate stack-panel selection state
 * through the [com.intellij.openapi.actionSystem.DataContext].
 *
 * The [com.github.ydymovopenclawbot.stackworktree.ui.StacksTabFactory] registers
 * a [com.intellij.ide.DataManager] provider on the Stacks panel that returns the
 * currently selected branch name for [SELECTED_BRANCH_NAME].  Action classes query
 * this key in [com.intellij.openapi.actionSystem.AnAction.update] to enable/disable
 * themselves and in [com.intellij.openapi.actionSystem.AnAction.actionPerformed] to
 * read which branch the user right-clicked.
 */
object StackDataKeys {

    /**
     * Short name (e.g. `"feature/foo"`) of the branch node currently selected in
     * the Stacks graph panel.  `null` / absent when nothing is selected.
     */
    val SELECTED_BRANCH_NAME: DataKey<String> =
        DataKey.create("stackworktree.selectedBranchName")

    /**
     * The [Worktree] whose row is currently selected / right-clicked in the
     * Stacks panel (either the graph node or the worktree list).
     * `null` / absent when no worktree row is focused or the selected branch
     * has no linked worktree.
     */
    val SELECTED_WORKTREE: DataKey<Worktree> =
        DataKey.create("stackworktree.selectedWorktree")
}
