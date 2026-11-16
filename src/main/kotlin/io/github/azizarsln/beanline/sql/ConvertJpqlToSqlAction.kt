package io.github.azizarsln.beanline.sql

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import io.github.azizarsln.beanline.ui.BeanlineIcons

class ConvertJpqlToSqlAction : AnAction(
    "Convert JPQL to SQL",
    "Convert the selected JPQL/HQL query or QueryBuilder chain to PostgreSQL or Oracle SQL",
    BeanlineIcons.Bean,
) {
    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        val enabled = project != null && editor != null && !editor.selectionModel.selectedText.isNullOrBlank()
        e.presentation.isEnabledAndVisible = enabled
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selection = editor.selectionModel.selectedText?.trim().orEmpty()
        if (selection.isBlank()) {
            Messages.showInfoMessage(project, "Select a JPQL/HQL query or a QueryBuilder chain first.", TITLE)
            return
        }

        val options = arrayOf("PostgreSQL", "Oracle")
        val choiceIndex = Messages.showChooseDialog(
            project,
            "Choose target SQL dialect:",
            TITLE,
            BeanlineIcons.Bean,
            options,
            options.first(),
        )
        if (choiceIndex < 0) return
        val dialect = if (choiceIndex == 1) SqlDialect.ORACLE else SqlDialect.POSTGRESQL

        val chain = QueryBuilderToJpql.convert(selection)
        val jpql = chain?.jpql ?: selection
        val result = JpqlToSqlConverter.convert(project, jpql, dialect)
        val notes = chain?.notes.orEmpty() + result.notes

        JpqlToSqlDialog(project, render(result, notes, options[choiceIndex])).show()
    }

    private fun render(
        result: JpqlToSqlResult,
        notes: List<String>,
        dialect: String,
    ): String = buildString {
        appendLine("$dialect SQL:")
        appendLine(JpqlToSqlConverter.formatSql(result.sql))
        val parameters = result.parameterMap.entries.filter { (name, placeholder) -> placeholder != ":$name" }
        if (parameters.isNotEmpty()) {
            appendLine()
            appendLine("Parameters:")
            for ((name, placeholder) in parameters) {
                appendLine("$placeholder -> :$name")
            }
        }
        if (notes.isNotEmpty()) {
            appendLine()
            appendLine("---")
            for (note in notes) {
                appendLine(note)
            }
        }
    }

    private companion object {
        const val TITLE = "Convert JPQL to SQL"
    }
}
