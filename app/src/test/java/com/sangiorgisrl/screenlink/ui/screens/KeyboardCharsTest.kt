package com.sangiorgisrl.screenlink.ui.screens

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the character rules of the in-app keyboard:
 *  - the number row reproduces the hardware shift map, so ! @ # $ % ^ & * ( ) are reachable;
 *  - every symbol key has a spoken accessibility NAME (a resource id, so TalkBack
 *    announces it in the user's language) instead of the raw glyph;
 *  - the symbols page re-flows for Short windows without losing or duplicating a key.
 */
class KeyboardCharsTest {

    @Test
    fun `shifted number row matches a hardware keyboard`() {
        val expected = mapOf(
            '1' to '!', '2' to '@', '3' to '#', '4' to '$', '5' to '%',
            '6' to '^', '7' to '&', '8' to '*', '9' to '(', '0' to ')',
        )
        expected.forEach { (digit, glyph) ->
            assertEquals(glyph, KeyboardChars.shifted(digit), "shift of $digit")
            assertTrue(KeyboardChars.hasShifted(digit), "$digit must have a shifted form")
        }
    }

    @Test
    fun `shifted punctuation matches a hardware keyboard`() {
        val expected = mapOf(
            '-' to '_', '=' to '+', '[' to '{', ']' to '}', ';' to ':',
            '\'' to '"', ',' to '<', '.' to '>', '/' to '?', '`' to '~',
        )
        expected.forEach { (plain, shifted) ->
            assertEquals(shifted, KeyboardChars.shifted(plain), "shift of $plain")
        }
    }

    @Test
    fun `keys without a shifted variant are sent unchanged`() {
        // A key must never send the wrong byte just because SHIFT is latched.
        listOf('$', '&', '!', '?', '%', '^', '_', '*', '#', ':', '"', '|', '~').forEach {
            assertEquals(it, KeyboardChars.shifted(it), "shift of $it")
            assertFalse(KeyboardChars.hasShifted(it), "$it has no shifted form")
        }
        // Letters are uppercased elsewhere, never through shifted().
        assertEquals('q', KeyboardChars.shifted('q'))
    }

    @Test
    fun `every symbol key has a spoken name`() {
        val named = KeyboardChars.SYMBOLS + "!@#$%^&*()".toList() + ' '
        named.forEach { c ->
            assertNotEquals(0, KeyboardChars.spokenNameRes(c), "no spoken name for '$c'")
        }
        assertNotEquals(0, KeyboardChars.spokenNameRes(' '))
    }

    @Test
    fun `spoken names are distinct and only words get no name`() {
        // Two symbols sharing one resource would make two keys announce the same thing.
        val res = KeyboardChars.SYMBOLS.map { KeyboardChars.spokenNameRes(it) }
        assertEquals(
            KeyboardChars.SYMBOLS.size,
            res.distinct().size,
            "every symbol needs its own spoken name",
        )
        // Letters and digits are announced correctly by the engine, so they get no
        // name of their own and the caller falls back to the key's own label.
        listOf('q', 'Q', '7', '\u0000').forEach { c ->
            assertEquals(0, KeyboardChars.spokenNameRes(c), "'$c' should have no spoken name")
        }
    }

    @Test
    fun `symbols page re-flows without losing keys`() {
        val full = KeyboardChars.symbolRows(8)
        assertEquals(4, full.size)
        full.forEach { assertEquals(8, it.size) }

        val compact = KeyboardChars.symbolRows(16)
        assertEquals(2, compact.size)
        compact.forEach { assertEquals(16, it.size) }

        // Same keys, same order, whatever the window height.
        assertEquals(KeyboardChars.SYMBOLS, full.flatten())
        assertEquals(KeyboardChars.SYMBOLS, compact.flatten())
        assertEquals(32, KeyboardChars.SYMBOLS.size)
        assertEquals(
            KeyboardChars.SYMBOLS.size,
            KeyboardChars.SYMBOLS.distinct().size,
            "a symbol key must not appear twice",
        )
    }

    @Test
    fun `symbol rows reject a nonsensical width`() {
        assertTrue(
            runCatching { KeyboardChars.symbolRows(0) }.isFailure,
            "perRow must be validated",
        )
    }
}
