package io.github.azizarsln.beanline.sql

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.JPanel

class JpqlToSqlDialog(project: Project, private val text: String) : DialogWrapper(project, true) {
    private val area = JBTextArea(text)

    init {
        title = "SQL"
        area.isEditable = false
        area.lineWrap = true
        area.wrapStyleWord = true
        init()
        setOKButtonText("Copy")
    }

    override fun createCenterPanel(): JComponent {
        val scroll = JBScrollPane(area)
        scroll.preferredSize = Dimension(760, 420)
        return JPanel().apply { add(scroll) }
    }

    override fun doOKAction() {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        super.doOKAction()
    }
}
