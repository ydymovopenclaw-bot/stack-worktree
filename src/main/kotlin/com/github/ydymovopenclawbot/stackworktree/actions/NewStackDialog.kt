package com.github.ydymovopenclawbot.stackworktree.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Dialog for creating a new stack.
 *
 * Collects the trunk branch from the user. The current branch becomes the first node
 * of the new stack automatically — no separate name input is required because the
 * data model derives stack identity from the branch graph, not a user-supplied name.
 *
 * @param project      The current IntelliJ project (used for dialog parenting).
 * @param defaultTrunk Pre-filled trunk branch — defaults to `"main"`.
 */
class NewStackDialog(
    project: Project,
    defaultTrunk: String = "main",
) : DialogWrapper(project) {

    /** The trunk branch the user entered. Valid only after [showAndGet] returns `true`. */
    var trunkBranch: String = defaultTrunk
        private set

    init {
        title = "New Stack"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Trunk branch:") {
            textField()
                .align(AlignX.FILL)
                .bindText(::trunkBranch)
                .focused()
                .comment("The base branch this stack builds on (e.g. main, develop)")
        }
    }

    override fun doValidate(): ValidationInfo? = when {
        trunkBranch.isBlank() -> ValidationInfo("Trunk branch must not be blank")
        !isValidBranchName(trunkBranch) -> ValidationInfo("'$trunkBranch' is not a valid git branch name")
        else -> null
    }
}
