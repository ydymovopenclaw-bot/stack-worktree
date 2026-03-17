package com.github.ydymovopenclawbot.stackworktree.ui

import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackNodeData
import com.github.ydymovopenclawbot.stackworktree.util.Slugify
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Dialog for the "Add Branch to Stack" action.
 *
 * Fields:
 * - **Branch name** — auto-populated by slugifying the commit message as the
 *   user types; the user can freely override it at any point.
 * - **Commit message** — optional; when non-blank the action will stage all
 *   changes and create a commit on the new branch.
 * - **Create worktree** — when checked, `git worktree add` is used instead of
 *   `git checkout -b`, and the worktree path is stored on the node.
 */
class AddBranchToStackDialog(
    project: Project,
    parentNode: StackNodeData,
) : DialogWrapper(project) {

    // ── Fields ────────────────────────────────────────────────────────────────

    val branchNameField    = JBTextField()
    val commitMessageArea  = JTextArea(3, 40).apply { lineWrap = true; wrapStyleWord = true }
    val createWorktreeBox  = JBCheckBox("Create worktree", false)

    /** Tracks the last value we auto-generated so we can tell if the user edited the field. */
    private var lastAutoName = ""

    // ── Initialisation ────────────────────────────────────────────────────────

    init {
        title = "Add Branch to Stack (on '${parentNode.branchName}')"
        setOKButtonText("Create Branch")
        init()   // DialogWrapper lifecycle — must be called after field setup

        // Auto-populate branch name from slugified commit message.
        commitMessageArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent)  = syncBranchName()
            override fun removeUpdate(e: DocumentEvent)  = syncBranchName()
            override fun changedUpdate(e: DocumentEvent) = syncBranchName()
        })
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** The trimmed branch name entered by the user. */
    fun getBranchName(): String = branchNameField.text.trim()

    /** The trimmed commit message, or `null` when blank. */
    fun getCommitMessage(): String? = commitMessageArea.text.trim().takeIf { it.isNotBlank() }

    /** Whether the "Create worktree" checkbox is ticked. */
    fun isCreateWorktree(): Boolean = createWorktreeBox.isSelected

    // ── DialogWrapper overrides ───────────────────────────────────────────────

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val lc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(4, 0, 4, 8)
        }
        val fc = GridBagConstraints().apply {
            fill    = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets  = JBUI.insets(4, 0)
            gridwidth = GridBagConstraints.REMAINDER
        }

        // Row 1: commit message
        panel.add(JBLabel("Commit message:"), lc.copy())
        panel.add(JScrollPane(commitMessageArea), fc.copy())

        // Row 2: branch name
        panel.add(JBLabel("Branch name:"), lc.copy())
        panel.add(branchNameField, fc.copy())

        // Row 3: create worktree checkbox (spans both columns)
        val cbConstraints = fc.copy().apply { insets = JBUI.insets(6, 0, 2, 0) }
        panel.add(createWorktreeBox, cbConstraints)

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (getBranchName().isBlank()) {
            return ValidationInfo("Branch name must not be empty.", branchNameField)
        }
        // Basic git branch-name safety check (no spaces, no ..)
        if (getBranchName().contains(' ') || getBranchName().contains("..")) {
            return ValidationInfo("Branch name contains invalid characters.", branchNameField)
        }
        return null
    }

    override fun getPreferredFocusedComponent(): JComponent = commitMessageArea

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun syncBranchName() {
        val current = branchNameField.text
        // Only update when the field is blank or still shows our last auto-generated value.
        if (current.isBlank() || current == lastAutoName) {
            lastAutoName = Slugify.slugify(commitMessageArea.text)
            branchNameField.text = lastAutoName
        }
    }
}

/** Creates a defensive copy of [GridBagConstraints] (it is not a data class). */
private fun GridBagConstraints.copy(): GridBagConstraints = clone() as GridBagConstraints
