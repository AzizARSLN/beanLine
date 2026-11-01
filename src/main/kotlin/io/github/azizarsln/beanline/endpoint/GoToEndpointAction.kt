package io.github.azizarsln.beanline.endpoint

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.DataFlavor

class GoToEndpointAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val text = e.getData(CommonDataKeys.EDITOR)?.let(::textFromEditor) ?: urlFromClipboard().orEmpty()
        SearchEverywhereManager.getInstance(project).show(ENDPOINT_CONTRIBUTOR_ID, text, e)
    }

    private fun textFromEditor(editor: Editor): String? {
        editor.selectionModel.selectedText?.takeIf { it.isNotBlank() }?.let { return it }
        val document = editor.document
        val offset = editor.caretModel.offset
        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val line = document.charsSequence.subSequence(lineStart, document.getLineEndOffset(lineNumber)).toString()
        return extractUrlAt(line, offset - lineStart)
    }

    private fun urlFromClipboard(): String? =
        CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)?.takeIf(::looksLikeUrl)
}
