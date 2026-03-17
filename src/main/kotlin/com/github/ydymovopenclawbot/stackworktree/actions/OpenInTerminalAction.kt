package com.github.ydymovopenclawbot.stackworktree.actions

import com.github.ydymovopenclawbot.stackworktree.git.Worktree
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * Opens an IntelliJ terminal tab pre-cd'd to the selected worktree's directory.
 *
 * The tab is named after the worktree's branch so it is easy to identify when
 * multiple terminals are open.
 *
 * Requires the bundled Terminal plugin (`org.jetbrains.plugins.terminal`).
 * The action is registered via `terminal-support.xml` which is loaded only when
 * that plugin is present, so the class is never instantiated in IDEs that ship
 * without the Terminal plugin.
 *
 * The action reads the target [Worktree] from [StackDataKeys.SELECTED_WORKTREE]
 * so it works wherever a [com.intellij.openapi.actionSystem.DataProvider] exposes
 * that key (Stacks tab, worktree list context menu, branch detail panel).
 */
class OpenInTerminalAction : DumbAwareAction("Open in Terminal") {

    override fun actionPerformed(e: AnActionEvent) {
        val project  = e.project ?: return
        val worktree = e.getData(StackDataKeys.SELECTED_WORKTREE) ?: return
        perform(project, worktree)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(StackDataKeys.SELECTED_WORKTREE) != null
    }

    companion object {
        /**
         * Opens a new terminal tab at [worktree]'s directory inside [project].
         * Safe to call from the EDT.
         */
        fun perform(project: Project, worktree: Worktree) {
            val tabName = if (worktree.branch.isNotEmpty()) "Worktree: ${worktree.branch}"
                          else "Worktree"
            // createShellWidget(workingDirectory, tabName, requestFocus, deferSessionStart)
            TerminalToolWindowManager.getInstance(project)
                .createShellWidget(worktree.path, tabName, /* requestFocus = */ true, /* deferSessionStartUntilUiShown = */ false)
        }
    }
}
