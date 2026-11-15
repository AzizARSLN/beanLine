package io.github.azizarsln.beanline.sql

/** The JPQL a QueryBuilder chain stands for, together with the problems found on the way. */
data class QueryBuilderConversion(val jpql: String, val notes: List<String>)

/**
 * Turns a fluent QueryBuilder chain into the JPQL it stands for, for example
 * `QueryBuilder.create(Order.class, "o", em).select("o.id").join(Customer.class, "c", "c=o.customer", JoinType.LEFT)`
 * becomes `select o.id from Order o left join o.customer c`.
 */
object QueryBuilderToJpql {

    private val CREATE = Regex("""QueryBuilder\s*\.\s*create\s*\(""")
    private val STEP = Regex("""\.\s*([A-Za-z_]\w*)\s*\(""")

    fun looksLikeChain(text: String): Boolean = CREATE.containsMatchIn(text)

    fun convert(text: String): QueryBuilderConversion? {
        if (!looksLikeChain(text)) return null
        val create = CREATE.find(text) ?: return null
        val createCall = callArguments(text, create.range.last) ?: return null
        val rootEntity = entityName(createCall.arguments.getOrNull(0)) ?: return null
        val rootAlias = stringLiteral(createCall.arguments.getOrNull(1)) ?: return null

        val selects = mutableListOf<String>()
        val joins = mutableListOf<String>()
        val conditions = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val aliases = linkedSetOf(rootAlias)
        var orderBy: String? = null

        var index = createCall.end
        while (index < text.length) {
            val step = STEP.find(text, index) ?: break
            val call = callArguments(text, step.range.last) ?: break
            when (step.groupValues[1]) {
                "selectAlias" -> {
                    val expression = stringLiteral(call.arguments.getOrNull(0))
                    val alias = stringLiteral(call.arguments.getOrNull(1))
                    if (expression != null && alias != null) selects += "$expression as $alias"
                }
                "select" -> stringLiteral(call.arguments.getOrNull(0))?.let { selects += it }
                "join" -> joinClause(call.arguments, aliases, notes)?.let { joins += it }
                "whereIn" -> stringLiteral(call.arguments.getOrNull(0))?.let {
                    conditions += "$it in (:p${conditions.size + 1})"
                }
                "where" -> stringLiteral(call.arguments.getOrNull(0))?.let { conditions += it }
                "orderBy" -> orderBy = stringLiteral(call.arguments.getOrNull(0))
                "orderByDesc" -> stringLiteral(call.arguments.getOrNull(0))?.let { orderBy = "$it desc" }
            }
            index = call.end
        }

        val selectList = selects.ifEmpty { listOf(rootAlias) }.joinToString(", ")
        val jpql = buildString {
            append("select ").append(selectList)
            append(" from ").append(rootEntity).append(' ').append(rootAlias)
            for (join in joins) append(' ').append(join)
            if (conditions.isNotEmpty()) append(" where ").append(conditions.joinToString(" and "))
            orderBy?.let { append(" order by ").append(it) }
        }
        return QueryBuilderConversion(jpql, notes)
    }

    /**
     * Reads `join(Entity.class, "alias", "alias=relation", JoinType.LEFT)`. The condition binds the alias of the
     * joined entity to a relation path, so `"o.customer=c"` and `"c=o.customer"` both mean the same join.
     */
    private fun joinClause(args: List<String>, aliases: MutableSet<String>, notes: MutableList<String>): String? {
        val alias = stringLiteral(args.getOrNull(1)) ?: return null
        val condition = stringLiteral(args.getOrNull(2))
        val entity = entityName(args.getOrNull(0))

        val equals = condition?.indexOf('=') ?: -1
        val conditionAlias = if (equals > 0) condition?.substring(0, equals)?.trim() else null
        val relation = if (equals >= 0) condition?.substring(equals + 1)?.trim()?.takeIf { it.isNotEmpty() } else null

        if (conditionAlias != null && conditionAlias != alias) {
            notes += "join '$alias': the condition '$condition' names '$conditionAlias'; the declared alias was used"
        }
        if (relation != null) {
            val head = relation.substringBefore('.')
            when {
                head == alias -> notes += "join '$alias': relation '$relation' refers to the alias being joined"
                head !in aliases -> notes += "join '$alias': relation '$relation' starts with the unknown alias '$head'"
            }
        }
        aliases += alias

        val joined = relation ?: entity ?: return null
        val type = joinType(args.getOrNull(3))
        return if (relation == null && condition != null) "$type join $joined $alias on $condition"
        else "$type join $joined $alias"
    }

    private fun joinType(raw: String?): String =
        when (raw?.substringAfterLast('.')?.trim()?.uppercase()) {
            "LEFT" -> "left"
            "RIGHT" -> "right"
            "FULL" -> "full"
            else -> "inner"
        }

    private fun entityName(raw: String?): String? {
        val name = raw?.trim()?.removeSuffix(".class")?.trim()?.substringAfterLast('.') ?: return null
        return name.takeIf { it.isNotEmpty() }
    }

    private fun stringLiteral(raw: String?): String? {
        val value = raw?.trim() ?: return null
        if (value.length < 2 || value.first() != value.last() || value.first() !in QUOTES) return null
        return value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\'", "'")
    }

    /** The arguments of the call whose opening parenthesis sits at [openIndex], and the index just past its `)`. */
    private fun callArguments(text: String, openIndex: Int): Call? {
        if (openIndex >= text.length || text[openIndex] != '(') return null
        val arguments = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var quote: Char? = null
        var escaped = false
        var index = openIndex + 1
        while (index < text.length) {
            val char = text[index]
            when {
                quote != null -> {
                    current.append(char)
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == quote -> quote = null
                    }
                }
                char == '"' || char == '\'' -> {
                    quote = char
                    current.append(char)
                }
                char == '(' -> {
                    depth++
                    current.append(char)
                }
                char == ')' -> {
                    if (depth == 0) {
                        arguments += current.toString().trim()
                        return Call(arguments, index + 1)
                    }
                    depth--
                    current.append(char)
                }
                char == ',' && depth == 0 -> {
                    arguments += current.toString().trim()
                    current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        return null
    }

    private class Call(val arguments: List<String>, val end: Int)

    private val QUOTES = charArrayOf('"', '\'')
}
