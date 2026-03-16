package com.github.ydymovopenclawbot.stackworktree.startup

import com.github.ydymovopenclawbot.stackworktree.ui.StackStatusBarWidget
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.WindowManager
import git4idea.repo.GitRepository

/**
 * Startup hook that registers a [StackGitChangeListener] for lightweight status-bar
 * updates on checkout and commit events.
 *
 * The full graph refresh is handled independently by
 * [com.github.ydymovopenclawbot.stackworktree.ui.StacksTabFactory] once the Stacks tab is
 * opened.  This listener keeps the status bar branch indicator current even when the tab is
 * hidden.
 */
class MyProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        project.messageBus.connect().subscribe(
            GitRepository.GIT_REPO_CHANGE,
            StackGitChangeListener { repo ->
                val branch = repo.currentBranchName ?: return@StackGitChangeListener
                ApplicationManager.getApplication().invokeLater {
                    val bar = WindowManager.getInstance().getStatusBar(project) ?: return@invokeLater
                    (bar.getWidget(StackStatusBarWidget.ID) as? StackStatusBarWidget)
                        ?.updateCurrentBranch(branch)
                }
            },
        )
    }
}