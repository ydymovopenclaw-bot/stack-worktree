package com.github.ydymovopenclawbot.stackworktree.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.SwingUtilities

/**
 * Scrollable panel that renders the current [StackViewModel] as a vertical tree.
 *
 * Each named stack is shown as a bold heading followed by its branch nodes,
 * annotated with ahead/behind counts relative to the branch's parent in the stack.
 * The currently checked-out branch is highlighted with a filled bullet (●).
 *
 * Call [render] on the EDT whenever the view model changes (e.g. after a refresh).
 */
class StackGraphPanel : JBPanel<StackGraphPanel>(BorderLayout()) {

    private val contentPanel = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    init {
        add(JBScrollPane(contentPanel), BorderLayout.CENTER)
        // Show empty state immediately so the panel is never blank.
        render(StackViewModel(stacks = emptyList(), activeStack = null, currentBranch = null))
    }

    /**
     * Re-renders the panel to reflect [model].
     *
     * Must be called on the Event Dispatch Thread.
     */
    fun render(model: StackViewModel) {
        check(SwingUtilities.isEventDispatchThread()) {
            "StackGraphPanel.render() must be called on the EDT"
        }
        contentPanel.removeAll()

        if (model.stacks.isEmpty()) {
            contentPanel.add(JBLabel("  No stacks tracked yet"))
        } else {
            for (entry in model.stacks) {
                val marker = if (entry.name == model.activeStack) "▸ " else "  "
                contentPanel.add(
                    JBLabel("$marker${entry.name}").apply {
                        font = font.deriveFont(Font.BOLD)
                    }
                )
                for (branch in entry.branches) {
                    val bullet = if (branch.isCurrentBranch) "●" else "○"
                    val ab = branch.aheadBehind?.let { " (+${it.ahead}/-${it.behind})" } ?: ""
                    contentPanel.add(JBLabel("      $bullet ${branch.name}$ab"))
                }
            }
        }

        contentPanel.revalidate()
        contentPanel.repaint()
    }
}
