package io.github.azizarsln.beanline.endpoint

private val HTTP_VERBS = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
private val SCHEME_AND_HOST = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]*")
private val QUOTES = charArrayOf('\'', '"', '`')
private const val WILDCARD = "*"

/** A search text split into an optional HTTP verb and its path segments. */
data class UrlQuery(val verb: String?, val segments: List<String>, val text: String) {
    companion object {
        fun parse(input: String): UrlQuery {
            val trimmed = input.trim().trim(*QUOTES)
            val first = trimmed.substringBefore(' ')
            val verb = first.uppercase().takeIf { it in HTTP_VERBS && first.length < trimmed.length }
            val rest = if (verb != null) trimmed.substringAfter(' ').trim() else trimmed
            val path = rest.replace(SCHEME_AND_HOST, "").substringBefore('?').substringBefore('#')
            return UrlQuery(verb, splitPath(path).map(::querySegment), path.trim('/').lowercase())
        }

        private fun querySegment(segment: String): String =
            if (segment.startsWith(":") || segment.contains('{')) WILDCARD else segment
    }
}

fun splitPath(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

/**
 * Ranks how well [query] matches an endpoint, or returns null when it does not match. Higher is better.
 * An exact match ranks first, then a URL with a leading context path, then partial paths and plain text.
 */
fun matchScore(query: UrlQuery, verbs: List<String>, template: String, name: String): Int? {
    if (query.verb != null && verbs.isNotEmpty() && query.verb !in verbs && ANY_VERB !in verbs) return null
    val templateSegments = splitPath(template)
    val segments = query.segments
    if (segments.isEmpty()) return if (query.text.isEmpty()) 1 else null
    val literals = templateSegments.count { !isParameter(it) }

    if (segments.size == templateSegments.size && matchesAt(segments, 0, templateSegments)) return 400 + literals
    if (templateSegments.isNotEmpty() && segments.size > templateSegments.size) {
        val offset = segments.size - templateSegments.size
        if (literals > 0 && matchesFrom(segments, offset, templateSegments)) return 300 + literals
    }
    for (offset in 0..templateSegments.size - segments.size) {
        if (matchesAt(segments, offset, templateSegments)) return 200 + literals
    }
    val text = query.text
    return if (template.lowercase().contains(text) || name.lowercase().contains(text)) 100 else null
}

/** The string around [column] when it looks like a path, or null. */
fun extractUrlAt(line: String, column: Int): String? {
    val caret = column.coerceIn(0, line.length)
    val start = line.substring(0, caret).indexOfLast { it in QUOTES }
    if (start >= 0) {
        val end = line.indexOf(line[start], caret)
        if (end >= 0) {
            val quoted = line.substring(start + 1, end)
            if (quoted.contains('/')) return quoted
        }
    }
    val tokenStart = line.substring(0, caret).indexOfLast { it.isWhitespace() || it in QUOTES } + 1
    val tokenEnd = (caret until line.length).firstOrNull { line[it].isWhitespace() || line[it] in QUOTES } ?: line.length
    return line.substring(tokenStart, tokenEnd).takeIf { it.contains('/') }
}

fun looksLikeUrl(text: String): Boolean {
    val value = text.trim()
    return !value.contains('\n') && (SCHEME_AND_HOST.containsMatchIn(value) || value.startsWith("/"))
}

const val ANY_VERB = "ANY"

private fun isParameter(segment: String) = segment.contains('{')

private fun matchesAt(query: List<String>, offset: Int, template: List<String>): Boolean =
    query.indices.all { segmentMatches(query[it], template[offset + it]) }

private fun matchesFrom(query: List<String>, offset: Int, template: List<String>): Boolean =
    template.indices.all { segmentMatches(query[offset + it], template[it]) }

private fun segmentMatches(query: String, template: String): Boolean =
    query == WILDCARD || isParameter(template) || query.equals(template, ignoreCase = true)
