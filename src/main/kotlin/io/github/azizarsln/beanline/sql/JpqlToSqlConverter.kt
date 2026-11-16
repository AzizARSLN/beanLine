package io.github.azizarsln.beanline.sql

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiUtil

object JpqlToSqlConverter {
    private val WHITESPACE = Regex("\\s+")
    private val FROM = Regex("\\bfrom\\s+([A-Za-z_][\\w$]*)\\s+([A-Za-z_][\\w$]*)\\b", RegexOption.IGNORE_CASE)
    private val JOIN = Regex(
        "\\b(left|right|inner|full|cross)?\\s*(?:outer\\s+)?join\\s+(?:fetch\\s+)?" +
            "([A-Za-z_][\\w$]*(?:\\.[A-Za-z_][\\w$]*)*)\\s+(?:as\\s+)?([A-Za-z_][\\w$]*)\\b" +
            "(?:\\s+on\\s*\\(([^()]*)\\))?",
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
    private val ON_KEYWORD = Regex("\\s+on\\s+", RegexOption.IGNORE_CASE)
    /** A to-one navigation such as `t.hastaHizmet.birimSevk.id` that has to become its own join. */
    private val RELATION_PATH = Regex("\\b([A-Za-z_][\\w$]*)((?:\\.[A-Za-z_][\\w$]*){2,})\\b")
    private val CLAUSE = Regex("\\b(where|group\\s+by|having|order\\s+by)\\b", RegexOption.IGNORE_CASE)
    private val RELATION_ANNOTATIONS = listOf(
        "javax.persistence.ManyToOne",
        "javax.persistence.OneToOne",
        "jakarta.persistence.ManyToOne",
        "jakarta.persistence.OneToOne",
    )
    private val JOIN_COLUMN_ANNOTATIONS = listOf("javax.persistence.JoinColumn", "jakarta.persistence.JoinColumn")
    private val ID_ANNOTATIONS = listOf("javax.persistence.Id", "jakarta.persistence.Id")
    private const val JDK_PREFIX = "java."
    private const val IMPLICIT_JOIN = "left join"
    private const val DEFAULT_ID_COLUMN = "ID"

    fun convert(project: Project, jpql: String, dialect: SqlDialect): JpqlToSqlResult {
        val aliases = linkedMapOf<String, PsiClass>()
        val notes = mutableListOf<String>()
        var sql = normalizeWhitespace(jpql)
        sql = applyEntityToTableMapping(project, sql, aliases)
        sql = applyJoinMapping(project, sql, aliases, notes)
        sql = applyRelationPathMapping(sql, aliases, notes)
        sql = applyColumnMapping(sql, aliases)
        val (paramConverted, paramMap) = applyParameterMapping(sql, dialect)
        sql = applyConcat(paramConverted)
        return JpqlToSqlResult(sql = sql, parameterMap = paramMap, notes = notes)
    }

    /** Breaks the query into the lines a developer expects to read. */
    fun formatSql(sql: String): String {
        val broken = LINE_BREAK.replace(sql) { "\n" + it.groupValues[1].uppercase().replace(WHITESPACE, " ") + " " }
        return broken.replaceFirst(SELECT, "SELECT").replace(ON_KEYWORD, " ON ").trim()
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
                    "$" + indices.getValue(m.value.substring(1))
                }
                mapped to indices.entries.associate { (k, v) -> k to "\$$v" }
            }
        }
    }

    private fun applyEntityToTableMapping(project: Project, text: String, aliases: MutableMap<String, PsiClass>): String =
        text.replace(FROM) { m ->
            val entityName = m.groupValues[1]
            val alias = m.groupValues[2]
            val clazz = resolveClassBySimpleName(project, entityName)
            if (clazz != null) aliases[alias] = clazz
            val table = clazz?.let(::tableNameFor) ?: entityName
            "from $table $alias"
        }

    /**
     * Renders every join the way `QueryBuilder.getJoinText` would, but resolved down to columns:
     * `left join LisTetkik lt on (lt=t.lisTetkik)` becomes
     * `left join LIS_TETKIK lt on LIS_TETKIK.ID = t.LIS_TETKIK_ID`.
     */
    private fun applyJoinMapping(
        project: Project,
        text: String,
        aliases: MutableMap<String, PsiClass>,
        notes: MutableList<String>,
    ): String =
        text.replace(JOIN) { m ->
            val type = m.groupValues[1].lowercase()
            val path = m.groupValues[2]
            val alias = m.groupValues[3]
            val condition = m.groupValues[4].trim().takeIf { it.isNotEmpty() }
            val entity = resolveEntityForPath(project, path, aliases)
            if (entity == null) {
                notes += "The join path '$path' could not be resolved, it was left as written."
                return@replace m.value
            }
            aliases[alias] = entity
            val prefix = if (type.isEmpty()) "" else "$type "
            val on = when {
                condition != null -> resolveJoinCondition(condition, aliases)
                path.contains('.') -> deriveJoinCondition(path, entity, aliases)
                else -> null
            }
            if (on == null) {
                notes += if (condition != null) {
                    "The join condition '$condition' could not be mapped, `$alias` joins without an on clause."
                } else {
                    "`$alias` joins without an on clause, its primary key has to be matched manually."
                }
                "${prefix}join ${tableNameFor(entity)} $alias"
            } else {
                "${prefix}join ${tableNameFor(entity)} $alias on $on"
            }
        }

    /** Expands a to-one path such as `t.hastaHizmet.birimSevk.id` into the joins it implies. */
    private fun applyRelationPathMapping(
        text: String,
        aliases: MutableMap<String, PsiClass>,
        notes: MutableList<String>,
    ): String {
        val joins = linkedSetOf<String>()
        val resolutions = mutableMapOf<String, String>()
        val replaced = RELATION_PATH.replace(text) { m ->
            val path = m.value
            resolutions[path]?.let { return@replace it }
            val rootAlias = m.groupValues[1]
            val segments = m.groupValues[2].substring(1).split('.')
            val rootEntity = aliases[rootAlias] ?: return@replace path
            val resolution = walkRelationPath(rootAlias, rootEntity, segments, joins)
            if (resolution == null) {
                notes += "The relation path '$path' could not be resolved, it was left as written."
                path
            } else {
                resolutions[path] = resolution
                resolution
            }
        }
        if (joins.isEmpty()) return replaced
        val insertAt = CLAUSE.find(replaced)?.range?.first ?: replaced.length
        val head = replaced.substring(0, insertAt).trimEnd()
        val tail = replaced.substring(insertAt).trimStart()
        return if (tail.isEmpty()) "$head ${joins.joinToString(" ")}" else "$head ${joins.joinToString(" ")} $tail"
    }

    private fun walkRelationPath(
        rootAlias: String,
        rootEntity: PsiClass,
        segments: List<String>,
        joins: MutableSet<String>,
    ): String? {
        var alias = rootAlias
        var entity = rootEntity
        for ((index, segment) in segments.withIndex()) {
            val field = entity.findFieldByName(segment, true) ?: return null
            if (index == segments.lastIndex) {
                val column = if (isRelation(field)) joinColumnFor(field) else columnFor(field)
                return column?.let { "$alias.$it" }
            }
            if (!isRelation(field)) return null
            val target = relationTarget(field) ?: return null
            val foreignKey = joinColumnFor(field) ?: return null
            val targetAlias = "${alias}_$segment"
            joins += "$IMPLICIT_JOIN ${tableNameFor(target)} $targetAlias " +
                "on $targetAlias.${primaryKeyColumnFor(target)} = $alias.$foreignKey"
            alias = targetAlias
            entity = target
        }
        return null
    }

    /** Maps one side of a join condition to a column: a bare alias becomes its primary key. */
    private fun resolveJoinCondition(condition: String, aliases: Map<String, PsiClass>): String? {
        val parts = condition.split("=")
        if (parts.size != 2) return null
        val left = resolveJoinOperand(parts[0].trim(), aliases) ?: return null
        val right = resolveJoinOperand(parts[1].trim(), aliases) ?: return null
        return "$left = $right"
    }

    private fun resolveJoinOperand(operand: String, aliases: Map<String, PsiClass>): String? {
        if (operand.isEmpty()) return null
        if (!operand.contains('.')) {
            val clazz = aliases[operand] ?: return null
            return "${tableNameFor(clazz)}.${primaryKeyColumnFor(clazz)}"
        }
        val alias = operand.substringBefore('.')
        val fieldName = operand.substringAfter('.')
        if (fieldName.contains('.')) return null
        val field = aliases[alias]?.findFieldByName(fieldName, true) ?: return null
        val column = if (isRelation(field)) joinColumnFor(field) else columnFor(field)
        return column?.let { "$alias.$it" }
    }

    /** Derives the on clause of a plain path join such as `join t.lisTetkik lt`. */
    private fun deriveJoinCondition(path: String, entity: PsiClass, aliases: Map<String, PsiClass>): String? {
        val parentAlias = path.substringBefore('.')
        val fieldName = path.substringAfterLast('.')
        val field = aliases[parentAlias]?.findFieldByName(fieldName, true) ?: return null
        val foreignKey = joinColumnFor(field) ?: return null
        return "${tableNameFor(entity)}.${primaryKeyColumnFor(entity)} = $parentAlias.$foreignKey"
    }

    private fun resolveEntityForPath(project: Project, path: String, aliases: Map<String, PsiClass>): PsiClass? {
        val head = path.substringBefore('.')
        if (path == head) return resolveClassBySimpleName(project, path)
        var current = aliases[head] ?: return null
        for (segment in path.substringAfter('.').split('.')) {
            val field = current.findFieldByName(segment, true) ?: return null
            current = relationTarget(field) ?: return null
        }
        return current
    }

    private fun applyColumnMapping(text: String, aliases: Map<String, PsiClass>): String {
        var sql = text
        for ((alias, clazz) in aliases) {
            val references = referenceMapFor(clazz)
            if (references.isEmpty()) continue
            // A trailing dot means the reference points at a relation path, not at a column of this alias.
            val fieldRef = Regex("\\b" + Regex.escape(alias) + "\\.([A-Za-z_][\\w$]*)\\b(?!\\s*\\.)")
            sql = sql.replace(fieldRef) { m ->
                val field = m.groupValues[1]
                "$alias.${references[field] ?: field}"
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

    /** Field name to physical column, relations resolved to their foreign key column. */
    private fun referenceMapFor(clazz: PsiClass): Map<String, String> {
        val map = linkedMapOf<String, String>()
        for (field in clazz.allFields) {
            val column = if (isRelation(field)) joinColumnFor(field) else columnFor(field)
            if (column != null && column.isNotBlank()) map[field.name] = column
        }
        return map
    }

    private fun primaryKeyColumnFor(clazz: PsiClass): String {
        for (field in clazz.allFields) {
            if (ID_ANNOTATIONS.any { field.modifierList?.findAnnotation(it) != null }) {
                return columnFor(field) ?: field.name
            }
        }
        return DEFAULT_ID_COLUMN
    }

    private fun isRelation(field: PsiField): Boolean =
        (RELATION_ANNOTATIONS + JOIN_COLUMN_ANNOTATIONS).any { field.modifierList?.findAnnotation(it) != null }

    private fun relationTarget(field: PsiField): PsiClass? {
        val elementType = PsiUtil.extractIterableTypeParameter(field.type, false) ?: field.type
        val clazz = (elementType as? PsiClassType)?.resolve() ?: return null
        if (clazz.qualifiedName?.startsWith(JDK_PREFIX) == true) return null
        return clazz
    }

    private fun columnFor(field: PsiField): String? =
        annotationValue(field, "javax.persistence.Column", "name")
            ?: annotationValue(field, "jakarta.persistence.Column", "name")

    private fun joinColumnFor(field: PsiField): String? =
        annotationValue(field, "javax.persistence.JoinColumn", "name")
            ?: annotationValue(field, "jakarta.persistence.JoinColumn", "name")

    private fun annotationValue(owner: PsiModifierListOwner, annotationFqn: String, attribute: String): String? {
        val annotation = owner.modifierList?.findAnnotation(annotationFqn) ?: return null
        val value = annotation.findDeclaredAttributeValue(attribute) ?: return null
        return JavaPsiFacade.getInstance(owner.project).constantEvaluationHelper.computeConstantExpression(value) as? String
    }
}
