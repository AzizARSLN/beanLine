package io.github.azizarsln.beanline.commit

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VirtualFile

object CommitMessageGenerator {

    fun generate(project: Project, changes: Collection<Change>): String? {
        val added = sortedSetOf<String>()
        val modified = sortedSetOf<String>()
        val deleted = sortedSetOf<String>()

        for (change in changes) {
            val path = relativePath(project, change.virtualFile) ?: continue
            when (change.type) {
                Change.Type.NEW -> added.add(path)
                Change.Type.DELETED -> deleted.add(path)
                else -> modified.add(path)
            }
        }

        if (added.isEmpty() && modified.isEmpty() && deleted.isEmpty()) return null

        return buildString {
            appendGroup("Added", added)
            appendGroup("Modified", modified)
            appendGroup("Deleted", deleted)
        }.trimEnd()
    }

    private fun StringBuilder.appendGroup(title: String, files: Collection<String>) {
        if (files.isEmpty()) return
        appendLine("$title:")
        files.forEach { appendLine("- $it") }
        appendLine()
    }

    private fun relativePath(project: Project, file: VirtualFile?): String? {
        if (file == null) return null
        val basePath = project.basePath ?: return file.path
        return FileUtil.getRelativePath(basePath, file.path, '/') ?: file.path
    }
}
