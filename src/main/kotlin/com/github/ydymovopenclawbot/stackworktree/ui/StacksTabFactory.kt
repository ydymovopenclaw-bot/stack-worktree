package com.github.ydymovopenclawbot.stackworktree.ui

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentProvider
import com.intellij.ui.components.JBScrollPane
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackGraphData
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackGraphPanel
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants

private val LOG = logger<StacksTabFactory>()

class StacksTabFactory : ChangesViewContentProvider {

    private val graphPanel = StackGraphPanel().apply {
        onNodeSelected  = { node -> LOG.debug("Selected node: ${node.branchName}") }
        onNodeNavigated = { node -> LOG.info("Navigate requested for: ${node.branchName}") }
        // Start with an empty graph; other components will call updateGraph() when data arrives.
        updateGraph(StackGraphData())
    }

    override fun initContent(): JComponent =
        JBScrollPane(
            graphPanel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
        )

    override fun disposeContent() = Unit
}
