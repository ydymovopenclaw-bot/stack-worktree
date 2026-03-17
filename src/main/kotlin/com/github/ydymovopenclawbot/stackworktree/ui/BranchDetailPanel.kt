package com.github.ydymovopenclawbot.stackworktree.ui

import com.github.ydymovopenclawbot.stackworktree.model.StackNode
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.io.File
import javax.swing.DefaultListModel
import javax.swing.JButton

/**
 * Right-side panel that shows metadata for the currently selected [StackNode]:
 *
 * - Branch name (bold)
 * - Parent branch
 * - Ahead / behind count relative to the parent
 * - Commit list (git log --oneline style)
 * - Worktree path with an "Open" button, or "Not bound"
 * - Action buttons: Rebase, Submit PR, Create Worktree / Remove Worktree
 *
 * Call [showNode] to populate the panel, [clearSelection] to reset it.
 *
 * Set [onCreateWorktree] / [onRemoveWorktree] to react to worktree button clicks.
 */
internal class BranchDetailPanel : JBPanel<BranchDetailPanel>(BorderLayout()) {

    // ── Callbacks ─────────────────────────────────────────────────────────────

    /** Invoked on the EDT when the user clicks "Create Worktree". */
    var onCreateWorktree: ((branchName: String) -> Unit)? = null

    /** Invoked on the EDT when the user clicks "Remove Worktree". */
    var onRemoveWorktree: ((branchName: String) -> Unit)? = null

    /** Invoked on the EDT when the user clicks "Open in New Window". Receives the worktree path. */
    var onOpenInNewWindow: ((worktreePath: String) -> Unit)? = null

    /** Invoked on the EDT when the user clicks "Open in Terminal". Receives path and branch name. */
    var onOpenInTerminal: ((worktreePath: String, branchName: String) -> Unit)? = null

    // ── Metadata labels ───────────────────────────────────────────────────────

    private val branchNameLabel = JBLabel(EMPTY).apply {
        font = font.deriveFont(Font.BOLD, JBUI.scaleFontSize(14f).toFloat())
    }
    private val parentLabel      = JBLabel(EMPTY)
    private val aheadBehindLabel = JBLabel(EMPTY)
    private val worktreeLabel    = JBLabel(NOT_BOUND)

    // ── Commit list ───────────────────────────────────────────────────────────

    private val commitModel = DefaultListModel<String>()
    private val commitList  = JBList(commitModel)

    // ── Buttons ───────────────────────────────────────────────────────────────

    /** Opens the worktree directory in the system file manager. */
    private val openButton = JButton("Open").apply { isEnabled = false }

    /** Opens the worktree in a new IDE window (or focuses the existing one). */
    private val openInNewWindowButton = JButton("New Window").apply { isEnabled = false }

    /** Opens a terminal tab pre-cd'd to the worktree directory. */
    private val openInTerminalButton = JButton("Terminal").apply { isEnabled = false }

    /** Stubbed action buttons — Rebase/Submit PR remain disabled until implemented. */
    private val rebaseButton         = JButton("Rebase").apply    { isEnabled = false }
    private val submitPrButton       = JButton("Submit PR").apply { isEnabled = false }
    private val createWorktreeButton = JButton("Create Worktree").apply { isEnabled = false }
    private val removeWorktreeButton = JButton("Remove Worktree").apply { isEnabled = false }

    init {
        add(buildForm(), BorderLayout.CENTER)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Populates all fields from [node]. */
    fun showNode(node: StackNode) {
        branchNameLabel.text  = node.branchName
        parentLabel.text      = node.parentBranch ?: "(none)"
        aheadBehindLabel.text = "+${node.aheadCount} / -${node.behindCount}"

        commitModel.clear()
        node.commits.forEach { commitModel.addElement("${it.hash}  ${it.subject}") }

        // Re-wire the Open / New Window / Terminal buttons for the new path.
        openButton.actionListeners.forEach { openButton.removeActionListener(it) }
        openInNewWindowButton.actionListeners.forEach { openInNewWindowButton.removeActionListener(it) }
        openInTerminalButton.actionListeners.forEach { openInTerminalButton.removeActionListener(it) }
        if (node.worktreePath != null) {
            worktreeLabel.text = node.worktreePath
            openButton.isEnabled = true
            openButton.addActionListener { openDirectory(node.worktreePath) }
            openInNewWindowButton.isEnabled = true
            openInNewWindowButton.addActionListener { onOpenInNewWindow?.invoke(node.worktreePath) }
            openInTerminalButton.isEnabled = true
            openInTerminalButton.addActionListener { onOpenInTerminal?.invoke(node.worktreePath, node.branchName) }
        } else {
            worktreeLabel.text = NOT_BOUND
            openButton.isEnabled = false
            openInNewWindowButton.isEnabled = false
            openInTerminalButton.isEnabled = false
        }

        // Wire Create / Remove worktree buttons — only one is enabled at a time.
        createWorktreeButton.actionListeners.forEach { createWorktreeButton.removeActionListener(it) }
        removeWorktreeButton.actionListeners.forEach { removeWorktreeButton.removeActionListener(it) }
        if (node.worktreePath == null) {
            createWorktreeButton.isEnabled = true
            removeWorktreeButton.isEnabled = false
            createWorktreeButton.addActionListener { onCreateWorktree?.invoke(node.branchName) }
        } else {
            createWorktreeButton.isEnabled = false
            removeWorktreeButton.isEnabled = true
            removeWorktreeButton.addActionListener { onRemoveWorktree?.invoke(node.branchName) }
        }
    }

    /** Resets all fields to their empty/placeholder state. */
    fun clearSelection() {
        branchNameLabel.text  = EMPTY
        parentLabel.text      = EMPTY
        aheadBehindLabel.text = EMPTY
        worktreeLabel.text    = NOT_BOUND
        openButton.isEnabled  = false
        openButton.actionListeners.forEach { openButton.removeActionListener(it) }
        openInNewWindowButton.isEnabled = false
        openInNewWindowButton.actionListeners.forEach { openInNewWindowButton.removeActionListener(it) }
        openInTerminalButton.isEnabled = false
        openInTerminalButton.actionListeners.forEach { openInTerminalButton.removeActionListener(it) }
        createWorktreeButton.isEnabled = false
        createWorktreeButton.actionListeners.forEach { createWorktreeButton.removeActionListener(it) }
        removeWorktreeButton.isEnabled = false
        removeWorktreeButton.actionListeners.forEach { removeWorktreeButton.removeActionListener(it) }
        commitModel.clear()
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildForm(): JBPanel<*> {
        val panel = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            border = JBUI.Borders.empty(8)
        }

        val labelConstraints = GridBagConstraints().apply {
            gridx = 0; weightx = 0.0; fill = GridBagConstraints.NONE
            insets = JBUI.insets(3, 0, 3, 8)
            anchor = GridBagConstraints.NORTHWEST
        }
        val valueConstraints = GridBagConstraints().apply {
            gridx = 1; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(3, 0)
            anchor = GridBagConstraints.NORTHWEST
        }

        var row = 0

        fun addRow(caption: String, value: JBLabel) {
            labelConstraints.gridy = row
            valueConstraints.gridy = row
            panel.add(JBLabel(caption).apply {
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            }, labelConstraints)
            panel.add(value, valueConstraints)
            row++
        }

        addRow("Branch:", branchNameLabel)
        addRow("Parent:", parentLabel)
        addRow("Ahead / Behind:", aheadBehindLabel)

        // Worktree row: label + path + Open / New Window / Terminal buttons
        labelConstraints.gridy = row
        panel.add(JBLabel("Worktree:").apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }, labelConstraints)
        val worktreeRow = JBPanel<JBPanel<*>>().apply {
            isOpaque = false
            add(worktreeLabel)
            add(openButton)
            add(openInNewWindowButton)
            add(openInTerminalButton)
        }
        valueConstraints.gridy = row
        panel.add(worktreeRow, valueConstraints)
        row++

        // Commit list — fills remaining vertical space
        val commitScrollPane = JBScrollPane(commitList)
        panel.add(commitScrollPane, GridBagConstraints().apply {
            gridx = 0; gridy = row; gridwidth = 2
            weightx = 1.0; weighty = 1.0
            fill = GridBagConstraints.BOTH
            insets = JBUI.insets(6, 0, 6, 0)
        })
        row++

        // Action buttons row
        val actionsRow = JBPanel<JBPanel<*>>().apply {
            isOpaque = false
            add(rebaseButton)
            add(submitPrButton)
            add(createWorktreeButton)
            add(removeWorktreeButton)
        }
        panel.add(actionsRow, GridBagConstraints().apply {
            gridx = 0; gridy = row; gridwidth = 2
            weightx = 1.0; weighty = 0.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4, 0, 0, 0)
        })

        return panel
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun openDirectory(path: String) {
        if (!Desktop.isDesktopSupported()) return
        try {
            Desktop.getDesktop().open(File(path))
        } catch (e: Exception) {
            LOG.warn("Failed to open worktree directory '$path'", e)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(BranchDetailPanel::class.java)
        private const val EMPTY     = "—"
        private const val NOT_BOUND = "Not bound"
    }
}
