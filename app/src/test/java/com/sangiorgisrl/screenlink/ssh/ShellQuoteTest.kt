package com.sangiorgisrl.screenlink.ssh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ShellQuoteTest {

    @Test
    fun `plain values are wrapped in single quotes`() {
        assertEquals("'work'", ShellQuote.single("work"))
        assertEquals("'my session'", ShellQuote.single("my session"))
    }

    @Test
    fun `single quotes are escaped so injection is neutralized`() {
        // The canonical POSIX way to embed a single quote inside single quotes.
        assertEquals("'a'\\''b'", ShellQuote.single("a'b"))
    }

    @Test
    fun `shell metacharacters are inert inside single quotes`() {
        val quoted = ShellQuote.single("x;rm -rf /tmp \$(id) `whoami` &")
        assert(quoted.startsWith("'") && quoted.endsWith("'"))
        // A single-quoted string cannot contain an unescaped quote.
        assertEquals(0, quoted.count { it == '\'' } % 2)
    }
}
