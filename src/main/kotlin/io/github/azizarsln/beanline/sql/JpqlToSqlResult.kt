package io.github.azizarsln.beanline.sql

data class JpqlToSqlResult(
    val sql: String,
    val parameterMap: Map<String, String>,
    val notes: List<String> = emptyList(),
)
