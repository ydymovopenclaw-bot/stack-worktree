package com.github.ydymovopenclawbot.stackworktree.actions

import com.github.ydymovopenclawbot.stackworktree.git.Worktree
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.wm.WindowManager
import java.nio.file.Path

/**
 * Opens the selected worktree in a new IDE window.
 *
 * If the worktree's directory is already open in another IDEA frame, that frame
 * is brought to the foreground instead of opening a duplicate window.
 *
 * The action reads the target [Worktree] from [StackDataKeys.SELECTED_WORKTREE]
 * so it works wherever a [com.intellij.openapi.actionSystem.DataProvider] exposes
 * that key (Stacks tab, worktree list context menu, branch detail panel).
 */
class OpenInNewWindowAction : DumbAwareAction("Open in New Window") {

    override fun actionPerformed(e: AnActionEvent) {
        val worktree = e.getData(StackDataKeys.SELECTED_WORKTREE) ?: return
        perform(worktree)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(StackDataKeys.SELECTED_WORKTREE) != null
    }

    companion object {
        /**
         * Opens [worktree] in a new IDEA window, or focuses the existing window if
         * the path is already open.  Safe to call from the EDT.
         */
        fun perform(worktree: Worktree) {
            val targetPath: Path = runCatching { Path.of(worktree.path).toRealPath() }
                .getOrElse { Path.of(worktree.path) }

            // Bring an already-open window to the foreground instead of duplicating it.
            val existing = ProjectManager.getInstance().openProjects.find { proj ->
                proj.basePath?.let { base ->
                    runCatching { Path.of(base).toRealPath() == targetPath }.getOrDefault(false)
                } == true
            }
            if (existing != null) {
                WindowManager.getInstance().getFrame(existing)?.toFront()
                return
            }

            // Open the worktree root in a new frame, bypassing the "same/new window?" dialog.
            ProjectManagerEx.getInstanceEx().openProject(
                Path.of(worktree.path),
                OpenProjectTask { forceOpenInNewFrame = true },
            )
        }
    }
}
