package io.github.azizarsln.beanline.sql

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiUtil

object JpqlToSqlConverter {
    private val WHITESPACE = Regex("\\s+")
    private val FROM = Regex("\\bfrom\\s+([A-Za-z_][\\w$]*)\\s+([A-Za-z_][\\w$]*)\\b", RegexOption.IGNORE_CASE)
    private val JOIN = Regex(
        "\\b(left|right|inner|full|cross)?\\s*(?:outer\\s+)?join\\s+(?:fetch\\s+)?" +
            "([A-Za-z_][\\w$]*(?:\\.[A-Za-z_][\\w$]*)*)\\s+(?:as\\s+)?([A-Za-z_][\\w$]*)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val PARAM = Regex(":[A-Za-z_][\\w$]*")
    private val CONCAT = Regex("\\bconcat\\s*\\(\\s*([^,]+?)\\s*,\\s*([^)]+?)\\s*\\)", RegexOption.IGNORE_CASE)
    private val SELECT = Regex("^select\\b", RegexOption.IGNORE_CASE)
    private val LINE_BREAK = Regex(
        "\\s+(left\\s+join|right\\s+join|inner\\s+join|full\\s+join|cross\\s+join|group\\s+by|order\\s+by" +
            "|union\\s+all|union|join|having|where|from)\\s+",
        RegexOption.IGNORE_CASE,
    )
    private val RELATION_ANNOTATIONS = listOf(
        "javax.persistence.ManyToOne",
        "javax.persistence.OneToOne",
        "jakarta.persistence.ManyToOne",
        "jakarta.persistence.OneToOne",
    )
    private val JOIN_COLUMN_ANNOTATIONS = listOf("javax.persistence.JoinColumn", "jakarta.persistence.JoinColumn")
    private const val JDK_PREFIX = "java."

    fun convert(project: Project, jpql: String, dialect: SqlDialect): JpqlToSqlResult {
        var sql = normalizeWhitespace(jpql)
        val aliases = linkedMapOf<String, String>()
        sql = applyEntityToTableMapping(project, sql, aliases)
        val notes = mutableListOf<String>()
        sql = applyJoinMapping(project, sql, aliases, notes)
        sql = applyColumnMapping(project, sql, aliases)
        val (paramConverted, paramMap) = applyParameterMapping(sql, dialect)
        sql = paramConverted
        sql = applyConcat(sql)
        return JpqlToSqlResult(sql = sql, parameterMap = paramMap, notes = notes)
    }

    /** Breaks the query into the lines a developer expects to read. */
    fun formatSql(sql: String): String {
        val broken = LINE_BREAK.replace(sql) { "\n" + it.groupValues[1].uppercase().replace(WHITESPACE, " ") + " " }
        return broken.replaceFirst(SELECT, "SELECT").trim()
    }

    private fun normalizeWhitespace(text: String): String = text.trim().replace(WHITESPACE, " ")

    private fun applyConcat(text: String): String {
        var value = text
        while (true) {
            val next = value.replace(CONCAT) { m ->
                val left = m.groupValues[1].trim()
                val right = m.groupValues[2].trim()
                "($left || $right)"
            }
            if (next == value) return value
            value = next
        }
    }

    private fun applyParameterMapping(text: String, dialect: SqlDialect): Pair<String, Map<String, String>> {
        val namesInOrder = PARAM.findAll(text).map { it.value.substring(1) }.toList()
        if (namesInOrder.isEmpty()) return text to emptyMap()
        return when (dialect) {
            SqlDialect.ORACLE -> text to namesInOrder.distinct().associateWith { ":$it" }
            SqlDialect.POSTGRESQL -> {
                val indices = linkedMapOf<String, Int>()
                for (name in namesInOrder) {
                    indices.putIfAbsent(name, indices.size + 1)
                }
                val mapped = text.replace(PARAM) { m ->
                    val name = m.value.substring(1)
                    "$" + indices.getValue(name)
                }
                val map = indices.entries.associate { (k, v) -> k to "\$$v" }
                mapped to map
            }
        }
    }

    private fun applyEntityToTableMapping(project: Project, text: String, aliases: MutableMap<String, String>): String =
        text.replace(FROM) { m ->
            val entityName = m.groupValues[1]
            val alias = m.groupValues[2]
            val table = resolveClassBySimpleName(project, entityName)?.let(::tableNameFor) ?: entityName
            aliases[alias] = entityName
            "from $table $alias"
        }
 
    private fun applyJoinMapping(
        project: Project,
        text: String,
        aliases: MutableMap<String, String>,
        notes: MutableList<String>,
    ): String =
        text.replace(JOIN) { m ->
            val type = m.groupValues[1].lowercase()
            val path = m.groupValues[2]
            val alias = m.groupValues[3]
            val entity = resolveEntityForPath(project, path, aliases)
            val entityName = entity?.name
            if (entity == null || entityName == null) {
                notes += "The join path '$path' could not be resolved, it was left as written."
                m.value
            } else {
                aliases[alias] = entityName
                val prefix = if (type.isEmpty()) "" else "$type "
                "${prefix}join ${tableNameFor(entity)} $alias"
            }
        }

    private fun resolveEntityForPath(project: Project, path: String, aliases: Map<String, String>): PsiClass? {
        val head = path.substringBefore('.')
        if (path == head) return resolveClassBySimpleName(project, path)
        var current = aliases[head]?.let { resolveClassBySimpleName(project, it) } ?: return null
        for (segment in path.substringAfter('.').split('.')) {
            val field = current.findFieldByName(segment, true) ?: return null
            val elementType = PsiUtil.extractIterableTypeParameter(field.type, false) ?: field.type
            current = (elementType as? PsiClassType)?.resolve() ?: return null
            if (current.qualifiedName?.startsWith(JDK_PREFIX) == true) return null
        }
        return current
    }

    private fun applyColumnMapping(project: Project, text: String, aliases: Map<String, String>): String {
        var sql = text
        for ((alias, entityName) in aliases) {
            val clazz = resolveClassBySimpleName(project, entityName) ?: continue
            val columns = columnMapFor(clazz)
            if (columns.isEmpty()) continue
            // A trailing dot means the reference points at a relation path, not at a column of this alias.
            val fieldRef = Regex("\\b" + Regex.escape(alias) + "\\.([A-Za-z_][\\w$]*)\\b(?!\\s*\\.)")
            sql = sql.replace(fieldRef) { m ->
                val field = m.groupValues[1]
                val column = columns[field] ?: field
                "$alias.$column"
            }
        }
        return sql
    }

    private fun resolveClassBySimpleName(project: Project, simpleName: String): PsiClass? {
        val scope = GlobalSearchScope.allScope(project)
        val cache = PsiShortNamesCache.getInstance(project)
        return cache.getClassesByName(simpleName, scope).firstOrNull()
    }

    private fun tableNameFor(clazz: PsiClass): String =
        annotationValue(clazz, "javax.persistence.Table", "name")
            ?: annotationValue(clazz, "jakarta.persistence.Table", "name")
            ?: clazz.name.orEmpty()

    private fun columnMapFor(clazz: PsiClass): Map<String, String> {
        val map = linkedMapOf<String, String>()
        for (field in clazz.allFields) {
            val isRelation = (RELATION_ANNOTATIONS + JOIN_COLUMN_ANNOTATIONS)
                .any { field.modifierList?.findAnnotation(it) != null }
            if (isRelation) continue
            val name = field.name
            val column = annotationValue(field, "javax.persistence.Column", "name")
                ?: annotationValue(field, "jakarta.persistence.Column", "name")
                ?: continue
            if (column.isNotBlank()) map[name] = column
        }
        return map
    }

    private fun annotationValue(owner: PsiModifierListOwner, annotationFqn: String, attribute: String): String? {
        val annotation = owner.modifierList?.findAnnotation(annotationFqn) ?: return null
        val value = annotation.findDeclaredAttributeValue(attribute) ?: return null
        return JavaPsiFacade.getInstance(owner.project).constantEvaluationHelper.computeConstantExpression(value) as? String
    }
}
