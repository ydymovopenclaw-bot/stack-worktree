package com.github.ydymovopenclawbot.stackworktree.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Modal dialog for the "Track Branch" action.
 *
 * Presents two dropdowns:
 * - **Branch** — local branches not yet tracked.
 * - **Parent** — tracked branches plus the trunk, forming the valid parent set.
 *
 * Call [showAndGet] and then read [selectedBranch] / [selectedParent] if it returned `true`.
 */
class TrackBranchDialog(
    project: Project,
    availableBranches: List<String>,
    parentChoices: List<String>,
) : DialogWrapper(project) {

    private val branchCombo = ComboBox(availableBranches.toTypedArray())
    private val parentCombo = ComboBox(parentChoices.toTypedArray())

    init {
        title = "Track Branch"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Branch:") {
            cell(branchCombo).align(AlignX.FILL).comment("Local branch to add to the stack")
        }
        row("Parent:") {
            cell(parentCombo).align(AlignX.FILL).comment("Branch this one stacks on top of")
        }
    }

    /** The branch selected by the user (only valid after [showAndGet] returns `true`). */
    val selectedBranch: String get() = branchCombo.selectedItem as? String ?: ""

    /** The parent selected by the user (only valid after [showAndGet] returns `true`). */
    val selectedParent: String get() = parentCombo.selectedItem as? String ?: ""
}
