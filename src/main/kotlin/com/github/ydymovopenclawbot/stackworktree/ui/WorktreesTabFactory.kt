package com.github.ydymovopenclawbot.stackworktree.ui

import com.github.ydymovopenclawbot.stackworktree.actions.OpenInNewWindowAction
import com.github.ydymovopenclawbot.stackworktree.actions.OpenInTerminalAction
import com.github.ydymovopenclawbot.stackworktree.git.GitLayer
import com.github.ydymovopenclawbot.stackworktree.git.Worktree
import com.github.ydymovopenclawbot.stackworktree.git.WorktreeException
import com.github.ydymovopenclawbot.stackworktree.ops.WorktreeOps
import com.github.ydymovopenclawbot.stackworktree.startup.StackGitChangeListener
import com.github.ydymovopenclawbot.stackworktree.state.StackTreeStateListener
import com.github.ydymovopenclawbot.stackworktree.state.StateLayer
import com.github.ydymovopenclawbot.stackworktree.state.stackStateService
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentProvider
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.io.File
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities

private val LOG = logger<WorktreesTabFactory>()

/**
 * Content provider for the "Worktrees" tab in the VCS Changes view.
 *
 * Lists ALL git worktrees with management actions: create, remove, refresh.
 * Each row shows the branch name, path, HEAD hash, lock status, and whether
 * the branch is tracked in a stack.
 */
class WorktreesTabFactory(private val project: Project) : ChangesViewContentProvider {

    private var connection: MessageBusConnection? = null
    private var list: JBList<Worktree>? = null
    private var listModel: DefaultListModel<Worktree>? = null
    private var trackedBranches: Set<String> = emptySet()

    override fun initContent(): JComponent {
        val model = DefaultListModel<Worktree>()
        listModel = model

        val jbList = JBList(model).apply {
            cellRenderer = WorktreeCellRenderer()
            emptyText.text = "No worktrees found"
        }
        list = jbList

        // Context menu on right-click
        jbList.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { maybeShowPopup(e) }
            override fun mouseReleased(e: MouseEvent) { maybeShowPopup(e) }

            private fun maybeShowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val index = jbList.locationToIndex(e.point) ?: return
                if (index < 0) return
                val cellBounds = jbList.getCellBounds(index, index) ?: return
                if (!cellBounds.contains(e.point)) return
                jbList.selectedIndex = index
                val wt = model.getElementAt(index)
                buildContextMenu(wt).show(e.component, e.x, e.y)
            }
        })

        // Toolbar
        val actionGroup = DefaultActionGroup().apply {
            add(CreateWorktreeToolbarAction())
            add(RemoveWorktreeToolbarAction())
            add(RefreshToolbarAction())
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("WorktreesTab", actionGroup, true)
        toolbar.targetComponent = jbList

        // Subscribe to state changes
        connection = project.messageBus.connect().also { conn ->
            conn.subscribe(
                GitRepository.GIT_REPO_CHANGE,
                StackGitChangeListener { performRefresh() },
            )
            conn.subscribe(
                StackTreeStateListener.TOPIC,
                StackTreeStateListener { performRefresh() },
            )
            conn.subscribe(STACK_STATE_TOPIC, object : StackStateListener {
                override fun stateChanged() { performRefresh() }
            })
        }

        // Initial load
        performRefresh()

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(JBScrollPane(jbList), BorderLayout.CENTER)
        }
    }

    override fun disposeContent() {
        connection?.disconnect()
        connection = null
        list = null
        listModel = null
        trackedBranches = emptySet()
    }

    // ── Refresh ──────────────────────────────────────────────────────────────

    private fun performRefresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val gitLayer = project.service<GitLayer>()
                val worktrees = gitLayer.worktreeList()
                val tracked = project.service<StateLayer>().load().trackedBranches.keys
                SwingUtilities.invokeLater {
                    trackedBranches = tracked
                    val selected = list?.selectedValue
                    listModel?.clear()
                    worktrees.forEach { listModel?.addElement(it) }
                    // Restore selection
                    if (selected != null) {
                        val idx = worktrees.indexOfFirst { it.path == selected.path }
                        if (idx >= 0) list?.selectedIndex = idx
                    }
                }
            } catch (e: Exception) {
                LOG.warn("WorktreesTabFactory: refresh failed", e)
            }
        }
    }

    // ── Context menu ─────────────────────────────────────────────────────────

    private fun buildContextMenu(wt: Worktree): JPopupMenu {
        val popup = JPopupMenu()

        if (!wt.isMain) {
            val openWindowItem = JMenuItem("Open in New Window")
            openWindowItem.addActionListener { OpenInNewWindowAction.perform(wt) }
            popup.add(openWindowItem)

            val openTerminalItem = JMenuItem("Open in Terminal")
            openTerminalItem.addActionListener { openInTerminalIfAvailable(wt) }
            popup.add(openTerminalItem)

            val removeItem = JMenuItem("Remove Worktree")
            removeItem.addActionListener { launchRemoveWorktree(wt) }
            popup.add(removeItem)
        }

        val copyPathItem = JMenuItem("Copy Path")
        copyPathItem.addActionListener {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(java.awt.datatransfer.StringSelection(wt.path), null)
        }
        popup.add(copyPathItem)

        return popup
    }

    // ── Toolbar actions ──────────────────────────────────────────────────────

    private inner class CreateWorktreeToolbarAction : DumbAwareAction(
        "Create Worktree", "Create a new git worktree", AllIcons.General.Add,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            launchCreateWorktree()
        }
    }

    private inner class RemoveWorktreeToolbarAction : DumbAwareAction(
        "Remove", "Remove the selected worktree", AllIcons.General.Remove,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val selected = list?.selectedValue ?: return
            if (selected.isMain) return
            launchRemoveWorktree(selected)
        }

        override fun update(e: AnActionEvent) {
            val selected = list?.selectedValue
            e.presentation.isEnabled = selected != null && !selected.isMain
        }
    }

    private inner class RefreshToolbarAction : DumbAwareAction(
        "Refresh", "Refresh worktree list", AllIcons.Actions.Refresh,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            performRefresh()
        }
    }

    // ── Create worktree ──────────────────────────────────────────────────────

    private fun launchCreateWorktree() {
        val ops = WorktreeOps.forProject(project)
        val gitLayer = project.service<GitLayer>()
        val branches = gitLayer.listLocalBranches()
        val currentWorktrees = listModel?.let { m ->
            (0 until m.size()).map { m.getElementAt(it) }
        } ?: emptyList()
        val worktreeBranches = currentWorktrees
            .filter { it.branch.isNotEmpty() }
            .map { it.branch }
            .toSet()
        val existingWorktreePaths = currentWorktrees
            .filter { it.path.isNotEmpty() && it.branch.isNotEmpty() }
            .associate { it.branch to it.path }
        val currentBranch = GitRepositoryManager.getInstance(project)
            .repositories.firstOrNull()?.currentBranchName

        val dialog = CreateWorktreeDialog(
            project = project,
            branches = branches,
            worktreeBranches = worktreeBranches,
            preselectedBranch = null,
            pathResolver = { ops.defaultWorktreePath(it) },
            currentBranch = currentBranch,
            existingWorktreePaths = existingWorktreePaths,
        )
        if (!dialog.showAndGet()) return

        val chosenPath = dialog.getChosenPath()
        val selectedBranch = dialog.getSelectedBranch()
        val isNewBranch = dialog.isCreateNewBranch()
        val baseBranch = dialog.getBaseBranch()
        val openAfter = dialog.isOpenAfterCreation()

        if (dialog.isRememberDefault()) {
            val parentDir = File(chosenPath).parent
            if (parentDir != null) project.stackStateService().setWorktreeBasePath(parentDir)
        }

        object : Task.Backgroundable(project, "Creating worktree for '$selectedBranch'...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    if (isNewBranch) {
                        gitLayer.createBranch(selectedBranch, baseBranch)
                    }
                    val wt = ops.createWorktreeForBranch(selectedBranch, chosenPath)
                    project.messageBus
                        .syncPublisher(StackTreeStateListener.TOPIC)
                        .stateChanged()
                    notify("Worktree for '$selectedBranch' created at '$chosenPath'.", NotificationType.INFORMATION)
                    if (openAfter) {
                        ApplicationManager.getApplication().invokeLater {
                            OpenInNewWindowAction.perform(wt)
                        }
                    }
                } catch (ex: WorktreeException) {
                    if (isNewBranch) {
                        try { gitLayer.deleteBranch(selectedBranch) } catch (e: Exception) {
                            LOG.warn("Rollback: failed to delete orphaned branch '$selectedBranch'", e)
                        }
                    }
                    LOG.warn("WorktreesTabFactory: createWorktree failed", ex)
                    notify("Failed to create worktree: ${ex.message}", NotificationType.ERROR)
                } catch (ex: IllegalStateException) {
                    if (isNewBranch) {
                        try { gitLayer.deleteBranch(selectedBranch) } catch (e: Exception) {
                            LOG.warn("Rollback: failed to delete orphaned branch '$selectedBranch'", e)
                        }
                    }
                    LOG.warn("WorktreesTabFactory: branch already has worktree", ex)
                    notify(ex.message ?: "Branch already has a worktree.", NotificationType.WARNING)
                } catch (ex: Exception) {
                    if (isNewBranch) {
                        try { gitLayer.deleteBranch(selectedBranch) } catch (e: Exception) {
                            LOG.warn("Rollback: failed to delete orphaned branch '$selectedBranch'", e)
                        }
                    }
                    LOG.error("WorktreesTabFactory: createWorktree unexpected error", ex)
                    notify("Unexpected error: ${ex.message}", NotificationType.ERROR)
                }
            }
        }.queue()
    }

    // ── Remove worktree ──────────────────────────────────────────────────────

    private fun launchRemoveWorktree(wt: Worktree) {
        if (wt.isMain) return

        val confirmed = Messages.showYesNoDialog(
            project,
            "Remove the worktree for '${wt.branch}'?\n\nDirectory: ${wt.path}\n\nThe directory will be deleted.",
            "Remove Worktree",
            "Remove",
            "Cancel",
            Messages.getWarningIcon(),
        )
        if (confirmed != Messages.YES) return

        val ops = WorktreeOps.forProject(project)
        object : Task.Backgroundable(project, "Removing worktree for '${wt.branch}'...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    ops.removeWorktreeForBranch(wt.branch)
                    project.messageBus
                        .syncPublisher(StackTreeStateListener.TOPIC)
                        .stateChanged()
                    notify("Worktree for '${wt.branch}' removed.", NotificationType.INFORMATION)
                } catch (ex: WorktreeException) {
                    LOG.warn("WorktreesTabFactory: removeWorktree failed", ex)
                    notify("Failed to remove worktree: ${ex.message}", NotificationType.ERROR)
                } catch (ex: Exception) {
                    LOG.error("WorktreesTabFactory: removeWorktree unexpected error", ex)
                    notify("Unexpected error: ${ex.message}", NotificationType.ERROR)
                }
            }
        }.queue()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun openInTerminalIfAvailable(wt: Worktree) {
        try {
            OpenInTerminalAction.perform(project, wt)
        } catch (_: NoClassDefFoundError) {
            LOG.warn("Terminal plugin not available; cannot open worktree in terminal")
        } catch (e: Exception) {
            LOG.warn("Failed to open worktree in terminal: ${e.message}", e)
        }
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("StackWorktree")
            .createNotification(message, type)
            .notify(project)
    }

    // ── Cell renderer ────────────────────────────────────────────────────────

    /**
     * Custom cell renderer for worktree rows.
     *
     * Shows: branch name (bold), "(main)" tag, [Stack] badge, lock icon, path, short HEAD hash.
     */
    private inner class WorktreeCellRenderer : ListCellRenderer<Worktree> {

        override fun getListCellRendererComponent(
            list: JList<out Worktree>,
            value: Worktree,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val panel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(4, 8, 4, 8)
                isOpaque = true
                background = if (isSelected) list.selectionBackground else list.background
            }

            // Left side: branch name + tags
            val leftFlow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
            }

            // Lock icon
            if (value.isLocked) {
                val lockLabel = javax.swing.JLabel(AllIcons.Nodes.SecurityRole)
                leftFlow.add(lockLabel)
            }

            // Branch name (bold)
            val branchText = value.branch.ifEmpty { "(detached)" }
            val branchLabel = javax.swing.JLabel(branchText).apply {
                font = if (value.branch.isEmpty()) {
                    JBUI.Fonts.label(12f).deriveFont(Font.ITALIC)
                } else {
                    JBUI.Fonts.label(12f).deriveFont(Font.BOLD)
                }
                foreground = if (isSelected) list.selectionForeground else list.foreground
            }
            leftFlow.add(branchLabel)

            // "(main)" tag
            if (value.isMain) {
                val mainTag = javax.swing.JLabel("(main)").apply {
                    font = JBUI.Fonts.label(10f)
                    foreground = SECONDARY_TEXT
                }
                leftFlow.add(mainTag)
            }

            // [Stack] badge
            if (value.branch in trackedBranches) {
                leftFlow.add(StackBadgeLabel())
            }

            panel.add(leftFlow, BorderLayout.WEST)

            // Right side: path + short hash
            val rightFlow = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
            }

            val dirName = File(value.path).name.ifEmpty { value.path }
            val pathLabel = javax.swing.JLabel(dirName).apply {
                font = JBUI.Fonts.label(11f)
                foreground = SECONDARY_TEXT
                toolTipText = value.path
            }
            rightFlow.add(pathLabel)

            val shortHash = value.head.take(7)
            if (shortHash.isNotEmpty()) {
                val hashLabel = javax.swing.JLabel(shortHash).apply {
                    font = JBUI.Fonts.label(10f)
                    foreground = HASH_TEXT
                }
                rightFlow.add(hashLabel)
            }

            panel.add(rightFlow, BorderLayout.EAST)

            return panel
        }
    }

    companion object {
        private val SECONDARY_TEXT: JBColor = JBColor(Color(0x888888), Color(0x777777))
        private val HASH_TEXT: JBColor = JBColor(Color(0x999999), Color(0x666666))
        private val BADGE_BG: JBColor = JBColor(Color(0x4285F4), Color(0x3A5FBF))
        private val BADGE_TEXT_COLOR: JBColor = JBColor(Color.WHITE, Color.WHITE)
    }
}

/**
 * Small rounded "[Stack]" pill badge drawn via Java2D.
 */
private class StackBadgeLabel : JPanel() {

    private val text = "Stack"
    private val hPad = JBUI.scale(5)
    private val vPad = JBUI.scale(2)
    private val badgeFont = JBUI.Fonts.label(9f).deriveFont(Font.BOLD)

    init {
        isOpaque = false
        val fm = getFontMetrics(badgeFont)
        preferredSize = Dimension(fm.stringWidth(text) + hPad * 2, fm.height + vPad * 2)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val arc = height.toDouble()
        val shape = RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), arc, arc)

        g2.color = JBColor(Color(0x4285F4), Color(0x3A5FBF))
        g2.fill(shape)

        g2.font = badgeFont
        g2.color = JBColor(Color.WHITE, Color.WHITE)
        val fm = g2.getFontMetrics(badgeFont)
        val tx = (width - fm.stringWidth(text)) / 2
        val ty = (height + fm.ascent - fm.descent) / 2
        g2.drawString(text, tx, ty)
    }
}
