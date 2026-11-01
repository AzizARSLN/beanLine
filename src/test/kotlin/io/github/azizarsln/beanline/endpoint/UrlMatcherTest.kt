package io.github.azizarsln.beanline.endpoint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlMatcherTest {

    private fun score(query: String, template: String, verbs: List<String> = listOf("GET"), name: String = "OrderResource#get") =
        matchScore(UrlQuery.parse(query), verbs, template, name)

    @Test
    fun `strips host, context path and query string`() {
        assertNotNull(score("https://app.example.com/shop/api/orders/42?expand=items#top", "/api/orders/{id}"))
    }

    @Test
    fun `exact match outranks a match with a context path`() {
        val exact = score("/api/orders", "/api/orders")!!
        val withContext = score("/shop/api/orders", "/api/orders")!!
        assertTrue(exact > withContext)
    }

    @Test
    fun `more literal segments outrank a parameter`() {
        val literal = score("/orders/open", "/orders/open")!!
        val parameter = score("/orders/open", "/orders/{id}")!!
        assertTrue(literal > parameter)
    }

    @Test
    fun `front-end placeholders act as wildcards`() {
        assertNotNull(score("/orders/\${id}/items", "/orders/{orderId}/items"))
        assertNotNull(score("/orders/{{id}}/items", "/orders/{orderId}/items"))
        assertNotNull(score("/orders/:id/items", "/orders/{orderId}/items"))
    }

    @Test
    fun `verb filters results`() {
        assertNotNull(score("GET /orders", "/orders"))
        assertNull(score("POST /orders", "/orders"))
        assertNotNull(score("POST /orders", "/orders", listOf(ANY_VERB)))
    }

    @Test
    fun `a template of only parameters does not swallow longer urls`() {
        assertNull(score("/some/long/path", "/{id}"))
    }

    @Test
    fun `falls back to plain text on path and name`() {
        assertNotNull(score("ord", "/api/orders"))
        assertNotNull(score("orderres", "/api/items", name = "OrderResource#get"))
        assertNull(score("invoice", "/api/orders"))
    }

    @Test
    fun `extracts the quoted string around the caret`() {
        val line = "fetch('/api/orders/' + id, 'x')"
        assertEquals("/api/orders/", extractUrlAt(line, 12))
    }

    @Test
    fun `extracts a bare token and rejects text without a slash`() {
        assertEquals("/a/b", extractUrlAt("see /a/b now", 6))
        assertNull(extractUrlAt("plain words", 2))
    }

    @Test
    fun `recognizes urls on the clipboard`() {
        assertTrue(looksLikeUrl("https://example.com/a"))
        assertTrue(looksLikeUrl("/a/b"))
        assertFalse(looksLikeUrl("hello world"))
        assertFalse(looksLikeUrl("/a\n/b"))
    }

    @Test
    fun `joins paths with normalized slashes`() {
        assertEquals("/api/orders/{id}", joinPaths("/api/orders/", "/{id}"))
        assertEquals("/", joinPaths("", ""))
    }
}
