package io.github.azizarsln.beanline.commit

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import io.github.azizarsln.beanline.ui.BeanlineIcons

class GenerateCommitMessageAction : AnAction(
    "Generate Commit Message",
    "Append a summary of the pending changes to the commit message",
    BeanlineIcons.Bean,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val changes: Collection<Change> = e.getData(VcsDataKeys.CHANGES)?.toList()
            ?.takeIf { it.isNotEmpty() }
            ?: ChangeListManager.getInstance(project).defaultChangeList.changes

        val text = CommitMessageGenerator.generate(project, changes)
        if (text == null) {
            Messages.showInfoMessage(project, "There are no pending changes to summarize and no branch was found.", TITLE)
            return
        }

        appendToEditor(project, editor, text)
    }

    private fun appendToEditor(project: Project, editor: Editor, text: String) {
        val document = editor.document
        val existing = document.text
        val separator = when {
            existing.isEmpty() -> ""
            existing.endsWith("\n\n") -> ""
            existing.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        val offset = document.textLength
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(offset, separator + text)
        }
        editor.caretModel.moveToOffset(document.textLength)
    }

    private companion object {
        const val TITLE = "Generate Commit Message"
    }
}
