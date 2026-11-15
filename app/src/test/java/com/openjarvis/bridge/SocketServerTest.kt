package com.openjarvis.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocketServerTest {

    @Test
    fun parseRequestReadsCommandAndRequestId() {
        val result = SocketServer.parseRequestLine("""{"cmd":"open chrome","requestId":"abc123"}""")
        assertEquals("abc123", result?.first)
        assertEquals("open chrome", result?.second)
    }

    @Test
    fun parseRequestDefaultsMissingRequestIdToEmptyString() {
        val result = SocketServer.parseRequestLine("""{"cmd":"status"}""")
        assertEquals("", result?.first)
        assertEquals("status", result?.second)
    }

    @Test
    fun parseRequestRejectsMissingOrBlankCommand() {
        assertNull(SocketServer.parseRequestLine("""{"requestId":"abc123"}"""))
        assertNull(SocketServer.parseRequestLine("""{"cmd":""}"""))
        assertNull(SocketServer.parseRequestLine("""{"cmd":"   "}"""))
    }

    @Test
    fun parseRequestRejectsMalformedJson() {
        listOf("", "{", """{"cmd":""", "null", "[][]", "not json").forEach { input ->
            assertNull("Should reject: $input", SocketServer.parseRequestLine(input))
        }
    }

    @Test
    fun parseRequestPreservesRequestIds() {
        listOf("abc123", "xyz789", "test-id-001", "").forEach { id ->
            val json = if (id.isEmpty()) """{"cmd":"test"}"""
                else """{"cmd":"test","requestId":"$id"}"""
            assertEquals(id, SocketServer.parseRequestLine(json)?.first)
        }
    }

    @Test
    fun escapeJsonEscapesSpecialCharacters() {
        val escaped = SocketServer.escapeJsonValue("hello\nworld\"test\tvalue\\end")
        assertTrue(escaped.contains("\\n"))
        assertTrue(escaped.contains("\\\""))
        assertTrue(escaped.contains("\\t"))
        assertTrue(escaped.contains("\\\\"))
    }

    @Test
    fun errorResponseContainsValidEscapedFields() {
        val response = SocketServer.errorResponse("req\"123", "line 1\nline 2")
        assertTrue(response.startsWith("{"))
        assertTrue(response.endsWith("}"))
        assertTrue(response.contains("\"requestId\":\"req\\\"123\""))
        assertTrue(response.contains("\"status\":\"error\""))
        assertTrue(response.contains("\"result\":\"line 1\\nline 2\""))
    }

    @Test
    fun specialCommandsAreParsedAsCommands() {
        listOf("status", "history", "memory", "providers").forEach { command ->
            val parsed = SocketServer.parseRequestLine("""{"cmd":"$command","requestId":"id-1"}""")
            assertEquals("id-1", parsed?.first)
            assertEquals(command, parsed?.second)
        }
    }
}
