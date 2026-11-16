package io.github.azizarsln.beanline.sql

/** The HQL a QueryBuilder chain stands for, together with the problems found on the way. */
data class QueryBuilderConversion(val jpql: String, val notes: List<String>)


object QueryBuilderToJpql {

    private val CREATE = Regex("""QueryBuilder\s*\.\s*create\s*\(""")
    private val STEP = Regex("""\.\s*([A-Za-z_]\w*)\s*\(""")
    private const val DEFAULT_ROOT_ALIAS = "t"

    fun looksLikeChain(text: String): Boolean = CREATE.containsMatchIn(text)

    fun convert(text: String): QueryBuilderConversion? {
        if (!looksLikeChain(text)) return null
        val create = CREATE.find(text) ?: return null
        val createCall = callArguments(text, create.range.last) ?: return null
        val rootEntity = entityName(createCall.arguments.getOrNull(0)) ?: return null
        val rootAlias = createCall.arguments.getOrNull(1)?.let(::stringLiteral) ?: DEFAULT_ROOT_ALIAS

        val aliases = linkedSetOf(rootAlias)
        val joins = mutableListOf<String>()
        val selects = mutableListOf<Selection>()
        val conditions = mutableListOf<Condition>()
        val notes = mutableListOf<String>()
        var orderBy: Ordering? = null
        var paramIndex = 0

        var index = createCall.end
        while (index < text.length) {
            val step = STEP.find(text, index) ?: break
            val call = callArguments(text, step.range.last) ?: break
            when (step.groupValues[1]) {
                "selectAlias" -> {
                    val field = stringLiteral(call.arguments.getOrNull(0))
                    val alias = stringLiteral(call.arguments.getOrNull(1))
                    if (field != null) selects += Selection(field, alias?.takeIf { it.isNotBlank() })
                }
                "select" -> {
                    if (call.arguments.isEmpty()) {
                        selects += Selection(rootAlias, null)
                    } else {
                        for (argument in call.arguments) stringLiteral(argument)?.let { selects += Selection(it, null) }
                    }
                }
                "join" -> renderJoin(call.arguments, rootAlias, aliases, notes)?.let { joins += it }
                "whereIn" -> stringLiteral(call.arguments.getOrNull(0))?.let {
                    conditions += Condition(it, "in :p" + paramIndex++)
                }
                "whereNotIn" -> stringLiteral(call.arguments.getOrNull(0))?.let {
                    conditions += Condition(it, "not in :p" + paramIndex++)
                }
                "where" -> stringLiteral(call.arguments.getOrNull(0))?.let {
                    conditions += Condition(it, "= :p" + paramIndex++)
                }
                "whereBetween" -> stringLiteral(call.arguments.getOrNull(0))?.let {
                    conditions += Condition(it, "between :p${paramIndex++} and :p${paramIndex++}")
                }
                "orderBy" -> stringLiteral(call.arguments.getOrNull(0))?.let { orderBy = Ordering(it, false) }
                "orderByDesc" -> stringLiteral(call.arguments.getOrNull(0))?.let { orderBy = Ordering(it, true) }
            }
            index = call.end
        }

        val selectList = if (selects.isEmpty()) {
            rootAlias
        } else {
            selects.joinToString(", ") { selection ->
                val field = resolveField(selection.field, rootAlias, aliases)
                selection.alias?.let { "$field as $it" } ?: field
            }
        }

        val jpql = buildString {
            append("select ").append(selectList)
            append(" from ").append(rootEntity).append(' ').append(rootAlias)
            for (join in joins) append(' ').append(join)
            if (conditions.isNotEmpty()) {
                append(" where ")
                append(conditions.joinToString(" and ") { "${resolveField(it.field, rootAlias, aliases)} ${it.suffix}" })
            }
            orderBy?.let {
                append(" order by ").append(resolveField(it.field, rootAlias, aliases)).append(if (it.desc) " desc" else "")
            }
        }
        return QueryBuilderConversion(jpql, notes)
    }

    /**
     * `QueryBuilder.getJoinText` renders a class based join as `<type> join <Entity> <alias> on (<condition>)`
     * and a plain path join as `<type> join <path> <alias>`.
     */
    private fun renderJoin(
        args: List<String>,
        rootAlias: String,
        aliases: MutableSet<String>,
        notes: MutableList<String>,
    ): String? {
        val first = args.getOrNull(0)?.trim().orEmpty()
        val type = joinType(args.lastOrNull())

        if (first.endsWith(".class")) {
            val entity = entityName(first) ?: return null
            val alias = stringLiteral(args.getOrNull(1)) ?: return null
            val condition = stringLiteral(args.getOrNull(2))
            aliases += alias
            if (condition.isNullOrBlank()) {
                notes += "'$alias' join has no condition, it was treated as a plain join"
                return "$type $entity $alias"
            }
            return "$type $entity $alias on ($condition)"
        }

        val alias = stringLiteral(args.getOrNull(0)) ?: return null
        val field = stringLiteral(args.getOrNull(1)) ?: return null
        val path = resolveField(field, rootAlias, aliases)
        aliases += alias
        return "$type $path $alias"
    }

    private fun resolveField(field: String, rootAlias: String, aliases: Set<String>): String {
        val head = field.substringBefore('.')
        return if (field.contains('.') && head in aliases) field else "$rootAlias.$field"
    }

    private fun joinType(raw: String?): String =
        when (raw?.trim()?.removeSurrounding("\"")?.substringAfterLast('.')?.uppercase()) {
            "LEFT" -> "left join"
            "RIGHT" -> "right join"
            "FULL" -> "full join"
            else -> "join"
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

    private data class Selection(val field: String, val alias: String?)
    private data class Condition(val field: String, val suffix: String)
    private data class Ordering(val field: String, val desc: Boolean)

    private val QUOTES = charArrayOf('"', '\'')
}
