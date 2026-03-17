package com.github.ydymovopenclawbot.stackworktree.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Dialog shown when the user clicks "Create Worktree" for a branch.
 *
 * Displays the resolved default worktree path (which the user may override) and offers
 * a "Remember as default base path" checkbox.  When checked, the chosen path's parent
 * directory is stored via [com.github.ydymovopenclawbot.stackworktree.state.StackStateService]
 * so that subsequent worktree creations are pre-filled with the same base.
 *
 * @param project     The current IntelliJ [Project]; used to configure the file-chooser.
 * @param branchName  Short name of the branch whose worktree will be created.
 * @param defaultPath Pre-computed default path shown in the path field on open.
 */
class WorktreePathDialog(
    project: Project,
    private val branchName: String,
    defaultPath: String,
) : DialogWrapper(project) {

    // ── Fields ────────────────────────────────────────────────────────────────

    val pathField = TextFieldWithBrowseButton().apply {
        text = defaultPath
        addBrowseFolderListener(
            "Choose Worktree Directory",
            "Select the directory where the worktree will be created",
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor(),
        )
    }

    val rememberDefaultBox = JBCheckBox("Remember as default base path", false)

    // ── Initialisation ────────────────────────────────────────────────────────

    init {
        title = "Create Worktree for '$branchName'"
        setOKButtonText("Create Worktree")
        init()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** The path entered by the user (trimmed). */
    fun getChosenPath(): String = pathField.text.trim()

    /** Whether the "Remember as default base path" checkbox is ticked. */
    fun isRememberDefault(): Boolean = rememberDefaultBox.isSelected

    // ── DialogWrapper overrides ───────────────────────────────────────────────

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())

        val lc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(4, 0, 4, 8)
        }
        val fc = GridBagConstraints().apply {
            fill      = GridBagConstraints.HORIZONTAL
            weightx   = 1.0
            insets    = JBUI.insets(4, 0)
            gridwidth = GridBagConstraints.REMAINDER
        }

        panel.add(JBLabel("Worktree path:"), lc.clone() as GridBagConstraints)
        panel.add(pathField, fc.clone() as GridBagConstraints)

        val cbConstraints = (fc.clone() as GridBagConstraints).apply {
            insets = JBUI.insets(4, 0, 2, 0)
        }
        panel.add(rememberDefaultBox, cbConstraints)

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        val path = getChosenPath()
        if (path.isBlank()) {
            return ValidationInfo("Worktree path must not be empty.", pathField.textField)
        }
        return null
    }

    override fun getPreferredFocusedComponent(): JComponent = pathField.textField
}
