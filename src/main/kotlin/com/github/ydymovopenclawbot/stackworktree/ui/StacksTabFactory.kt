package com.github.ydymovopenclawbot.stackworktree.ui

import com.intellij.openapi.vcs.changes.ui.ChangesViewContentProvider
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import java.awt.BorderLayout
import javax.swing.JComponent

class StacksTabFactory : ChangesViewContentProvider {

    override fun initContent(): JComponent =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(JBLabel("StackTree — no stacks tracked yet"), BorderLayout.CENTER)
        }

    override fun disposeContent() = Unit
}
