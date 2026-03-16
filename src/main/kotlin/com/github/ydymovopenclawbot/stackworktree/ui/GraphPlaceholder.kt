package com.github.ydymovopenclawbot.stackworktree.ui

import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.ListSelectionModel

/**
 * Minimal left-side panel that lists branch names and notifies a listener
 * whenever a branch is selected.
 *
 * This is a placeholder for the full stack graph component (task S2.x).
 * It intentionally avoids any graph-rendering logic; the only contract it
 * exposes is [populate] + the [onBranchSelected] callback.
 */
internal class GraphPlaceholder(
    private val onBranchSelected: (branchName: String) -> Unit,
) : JBPanel<GraphPlaceholder>(BorderLayout()) {

    private val listModel = DefaultListModel<String>()

    private val branchList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                selectedValue?.let { onBranchSelected(it) }
            }
        }
    }

    init {
        add(JBScrollPane(branchList), BorderLayout.CENTER)
    }

    /** Replaces the list contents with [branches]. Clears the current selection. */
    fun populate(branches: List<String>) {
        val previousSelection = branchList.selectedValue
        listModel.clear()
        branches.forEach { listModel.addElement(it) }
        // Restore selection if the branch is still present
        val restoredIdx = if (previousSelection != null) branches.indexOf(previousSelection) else -1
        if (restoredIdx >= 0) branchList.selectedIndex = restoredIdx
    }
}
