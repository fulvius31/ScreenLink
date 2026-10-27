package com.sangiorgisrl.screenlink.data

import com.sangiorgisrl.screenlink.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ConnectionValidationTest {

    @Test
    fun `accepts a valid form`() {
        assertNull(ConnectionValidation.validate("Home", "user", "example.com", "22"))
        assertNull(ConnectionValidation.validate("x", "u", "10.0.2.2", "2222"))
    }

    @Test
    fun `rejects missing fields and bad ports`() {
        assertEquals(R.string.err_connection_name, ConnectionValidation.validate("", "u", "h", "22"))
        assertEquals(R.string.err_username, ConnectionValidation.validate("n", "", "h", "22"))
        assertEquals(R.string.err_host, ConnectionValidation.validate("n", "u", "", "22"))
        assertEquals(R.string.err_port, ConnectionValidation.validate("n", "u", "h", "0"))
        assertEquals(R.string.err_port, ConnectionValidation.validate("n", "u", "h", "70000"))
        assertEquals(R.string.err_port, ConnectionValidation.validate("n", "u", "h", "abc"))
    }

    @Test
    fun `sanitizers strip unsafe input`() {
        assertEquals("22", ConnectionValidation.sanitizePort("22"))
        assertEquals("2222", ConnectionValidation.sanitizePort("2a2b2 c2"))
        assertEquals("12345", ConnectionValidation.sanitizePort("1234567890"))
        assertEquals("abc", ConnectionValidation.sanitizeSessionName("a\r\nb\u0000c"))
    }

    // The form shows one message per field (Material 3 text-field error state),
    // so validation must report every problem at once, not just the first.
    @Test
    fun `validateFields reports every field at once`() {
        val errors = ConnectionValidation.validateFields("", "", "", "99999")
        assertEquals(R.string.err_connection_name, errors.displayName)
        assertEquals(R.string.err_username, errors.username)
        assertEquals(R.string.err_host, errors.host)
        assertEquals(R.string.err_port, errors.port)
        assertFalse(errors.isEmpty)
    }

    @Test
    fun `validateFields is empty for a valid form`() {
        val errors = ConnectionValidation.validateFields("Home", "user", "example.com", "22")
        assertTrue(errors.isEmpty)
        assertNull(errors.first)
    }

    @Test
    fun `validate reports the first field error in field order`() {
        val errors = ConnectionValidation.validateFields("", "", "", "22")
        assertEquals(errors.first, ConnectionValidation.validate("", "", "", "22"))
        assertEquals(errors.displayName, ConnectionValidation.validate("", "u", "h", "22"))
    }

    // Messages are resource ids, never text: validation runs on a worker thread and
    // the message may be shown after the user switches language.
    @Test
    fun `messages are resource ids not text`() {
        val errors = ConnectionValidation.validateFields("", "", "", "x")
        listOf(errors.displayName, errors.username, errors.host, errors.port).forEach { id ->
            assertTrue(id != null && id != 0, "expected a string resource id, got $id")
        }
    }
}
