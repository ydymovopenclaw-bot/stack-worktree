package com.github.ydymovopenclawbot.stackworktree.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import git4idea.repo.GitRepositoryManager
import com.intellij.util.Consumer
import java.awt.event.MouseEvent

/**
 * Status bar widget that shows the currently checked-out branch name.
 *
 * Updated by two independent paths:
 * 1. [updateState] — called after a full refresh when a complete [StackViewModel] is available.
 * 2. [updateCurrentBranch] — called by [com.github.ydymovopenclawbot.stackworktree.startup.MyProjectActivity]
 *    for lightweight updates triggered directly by git repository events.
 */
class StackStatusBarWidget : StatusBarWidget, StatusBarWidget.TextPresentation {

    @Volatile private var displayText: String = ""
    @Volatile private var tooltipText: String = "StackTree: no stacks tracked"
    private var statusBar: StatusBar? = null

    companion object {
        const val ID = "com.github.ydymovopenclawbot.stackworktree.StackStatusBarWidget"
    }

    // -------------------------------------------------------------------------
    // Update API
    // -------------------------------------------------------------------------

    /** Full update from a complete [StackViewModel] produced by a refresh cycle. */
    fun updateState(model: StackViewModel) {
        displayText = model.currentBranch?.let { "⎇ $it" } ?: ""
        tooltipText = buildTooltip(model)
        statusBar?.updateWidget(ID)
    }

    /**
     * Lightweight update when only the current branch has changed (e.g. after checkout).
     * Does not require a full refresh cycle.
     */
    fun updateCurrentBranch(branch: String) {
        displayText = "⎇ $branch"
        statusBar?.updateWidget(ID)
    }

    // -------------------------------------------------------------------------
    // StatusBarWidget
    // -------------------------------------------------------------------------

    override fun ID(): String = ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun install(statusBar: StatusBar) { this.statusBar = statusBar }
    override fun dispose() { statusBar = null }

    // -------------------------------------------------------------------------
    // StatusBarWidget.TextPresentation
    // -------------------------------------------------------------------------

    override fun getText(): String = displayText
    override fun getTooltipText(): String = tooltipText
    override fun getAlignment(): Float = 0.5f
    override fun getClickConsumer(): Consumer<MouseEvent>? = null

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildTooltip(model: StackViewModel): String {
        if (model.stacks.isEmpty()) return "StackTree: no stacks tracked"
        return model.stacks.joinToString("\n") { entry ->
            "${entry.name}: ${entry.branches.joinToString(" → ") { it.name }}"
        }
    }
}

/**
 * Factory that registers [StackStatusBarWidget] with the IDE status bar.
 *
 * Visible only when at least one git repository is open in the project
 * (same condition as [com.github.ydymovopenclawbot.stackworktree.ui.StacksTabVisibilityPredicate]).
 */
class StackStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = StackStatusBarWidget.ID
    override fun getDisplayName(): String = "StackTree Stack"

    override fun isAvailable(project: Project): Boolean =
        GitRepositoryManager.getInstance(project).repositories.isNotEmpty()

    override fun createWidget(project: Project): StatusBarWidget = StackStatusBarWidget()

    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
