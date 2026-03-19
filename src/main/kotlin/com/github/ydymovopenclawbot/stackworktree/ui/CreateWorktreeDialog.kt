package com.github.ydymovopenclawbot.stackworktree.ui

import com.github.ydymovopenclawbot.stackworktree.actions.isValidBranchName
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Enhanced worktree creation dialog supporting both existing and new branch workflows.
 *
 * @param project            The current IntelliJ project.
 * @param branches           All local branch names (for the branch dropdown and duplicate checks).
 * @param worktreeBranches   Branches that already have a linked worktree.
 * @param defaultBasePath    Default base directory for worktree paths.
 * @param preselectedBranch  When non-null, pre-selects and disables the branch dropdown.
 * @param pathResolver       Computes the default worktree path for a given branch name.
 * @param currentBranch      When non-null, pre-selects this branch in the base branch dropdown.
 * @param existingWorktreePaths Paths already in use by other worktrees (for duplicate-path validation).
 */
class CreateWorktreeDialog(
    private val project: Project,
    private val branches: List<String>,
    private val worktreeBranches: Set<String> = emptySet(),
    private val defaultBasePath: String? = null,
    private val preselectedBranch: String? = null,
    private val pathResolver: (String) -> String = { branch ->
        val sanitised = branch.replace('/', '-').replace('\\', '-')
        if (defaultBasePath != null) "$defaultBasePath/$sanitised" else sanitised
    },
    private val currentBranch: String? = null,
    private val existingWorktreePaths: Map<String, String> = emptyMap(),
) : DialogWrapper(project) {

    // ── Fields ───────────────────────────────────────────────────────────────

    val branchCombo = ComboBox(DefaultComboBoxModel(branches.toTypedArray())).apply {
        if (preselectedBranch != null && branches.contains(preselectedBranch)) {
            selectedItem = preselectedBranch
            isEnabled = false
        }
    }

    val createNewBranchBox = JBCheckBox("Create new branch", false)

    val newBranchNameField = JBTextField().apply { isVisible = false }
    private val newBranchLabel = JBLabel("New branch name:").apply { isVisible = false }

    val baseBranchCombo = ComboBox(DefaultComboBoxModel(branches.toTypedArray())).apply {
        isVisible = false
    }
    private val baseBranchLabel = JBLabel("Base branch:").apply { isVisible = false }

    val pathField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            "Choose Worktree Directory",
            "Select the directory where the worktree will be created",
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor(),
        )
    }

    val rememberDefaultBox = JBCheckBox("Remember as default base path", false)
    val openAfterCreationBox = JBCheckBox("Open worktree after creation", true)

    // ── Initialisation ───────────────────────────────────────────────────────

    init {
        title = "Create Worktree"
        setOKButtonText("Create Worktree")

        // Auto-update path when branch selection changes.
        branchCombo.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED && !createNewBranchBox.isSelected) {
                updatePathFromBranch(e.item as? String)
            }
        }

        // Toggle new-branch fields visibility.
        createNewBranchBox.addItemListener {
            val creating = createNewBranchBox.isSelected
            newBranchLabel.isVisible = creating
            newBranchNameField.isVisible = creating
            baseBranchLabel.isVisible = creating
            baseBranchCombo.isVisible = creating
            branchCombo.isEnabled = !creating && preselectedBranch == null

            if (creating) {
                updatePathFromBranch(newBranchNameField.text.takeIf { it.isNotBlank() })
            } else {
                updatePathFromBranch(branchCombo.selectedItem as? String)
            }
        }

        // Auto-update path when new branch name changes.
        newBranchNameField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = onNewBranchNameChanged()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = onNewBranchNameChanged()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = onNewBranchNameChanged()
        })

        // Pre-select current branch in the base branch dropdown.
        if (currentBranch != null && branches.contains(currentBranch)) {
            baseBranchCombo.selectedItem = currentBranch
        }

        // Set initial path.
        val initial = preselectedBranch ?: branches.firstOrNull()
        if (initial != null) updatePathFromBranch(initial)

        init()
    }

    private fun onNewBranchNameChanged() {
        if (createNewBranchBox.isSelected) {
            val name = newBranchNameField.text.trim()
            updatePathFromBranch(name.takeIf { it.isNotBlank() })
        }
    }

    private fun updatePathFromBranch(branch: String?) {
        if (branch != null) {
            pathField.text = pathResolver(branch)
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun getChosenPath(): String = pathField.text.trim()
    fun isRememberDefault(): Boolean = rememberDefaultBox.isSelected
    fun isCreateNewBranch(): Boolean = createNewBranchBox.isSelected
    fun isOpenAfterCreation(): Boolean = openAfterCreationBox.isSelected

    fun getSelectedBranch(): String = if (createNewBranchBox.isSelected) {
        newBranchNameField.text.trim()
    } else {
        branchCombo.selectedItem as? String ?: ""
    }

    fun getBaseBranch(): String = baseBranchCombo.selectedItem as? String ?: ""

    // ── DialogWrapper overrides ──────────────────────────────────────────────

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())

        val lc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(4, 0, 4, 8)
        }
        val fc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = JBUI.insets(4, 0)
            gridwidth = GridBagConstraints.REMAINDER
        }

        // Branch dropdown
        panel.add(JBLabel("Branch:"), lc.clone() as GridBagConstraints)
        panel.add(branchCombo, fc.clone() as GridBagConstraints)

        // Create new branch checkbox (full width)
        panel.add(createNewBranchBox, fc.clone() as GridBagConstraints)

        // New branch name (hidden by default)
        panel.add(newBranchLabel, lc.clone() as GridBagConstraints)
        panel.add(newBranchNameField, fc.clone() as GridBagConstraints)

        // Base branch (hidden by default)
        panel.add(baseBranchLabel, lc.clone() as GridBagConstraints)
        panel.add(baseBranchCombo, fc.clone() as GridBagConstraints)

        // Worktree path
        panel.add(JBLabel("Worktree path:"), lc.clone() as GridBagConstraints)
        panel.add(pathField, fc.clone() as GridBagConstraints)

        // Checkboxes
        val cbConstraints = (fc.clone() as GridBagConstraints).apply {
            insets = JBUI.insets(4, 0, 2, 0)
        }
        panel.add(rememberDefaultBox, cbConstraints.clone() as GridBagConstraints)
        panel.add(openAfterCreationBox, cbConstraints.clone() as GridBagConstraints)

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (createNewBranchBox.isSelected) {
            val branchError = validateNewBranch(newBranchNameField.text.trim(), branches)
            if (branchError != null) return ValidationInfo(branchError, newBranchNameField)
            val base = getBaseBranch()
            if (base.isBlank()) return ValidationInfo("Select a base branch.", baseBranchCombo)
        } else {
            val selected = branchCombo.selectedItem as? String ?: ""
            val branchError = validateExistingBranch(selected, worktreeBranches)
            if (branchError != null) return ValidationInfo(branchError, branchCombo)
        }

        val pathError = validatePath(pathField.text.trim(), existingWorktreePaths.values)
        if (pathError != null) return ValidationInfo(pathError, pathField.textField)

        return null
    }

    override fun getPreferredFocusedComponent(): JComponent =
        if (createNewBranchBox.isSelected) newBranchNameField else pathField.textField

    // ── Static validation ────────────────────────────────────────────────────

    companion object {
        fun validatePath(path: String, existingWorktreePaths: Collection<String> = emptyList()): String? {
            if (path.isBlank()) return "Worktree path must not be empty."
            if (path in existingWorktreePaths) return "Path '$path' is already in use by another worktree."
            return null
        }

        fun validateNewBranch(branchName: String, existingBranches: List<String>): String? {
            if (branchName.isBlank()) return "Branch name must not be empty."
            if (!isValidBranchName(branchName)) return "Invalid branch name: '$branchName'."
            if (branchName in existingBranches) return "Branch '$branchName' already exists."
            return null
        }

        fun validateExistingBranch(branchName: String, worktreeBranches: Set<String>): String? {
            if (branchName in worktreeBranches) return "Branch '$branchName' already has a worktree."
            return null
        }
    }
}
