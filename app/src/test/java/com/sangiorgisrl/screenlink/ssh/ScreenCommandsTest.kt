package com.sangiorgisrl.screenlink.ssh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScreenCommandsTest {

    @Test
    fun `name and socket validation`() {
        assertTrue(ScreenCommands.isValidName("work"))
        assertTrue(ScreenCommands.isValidName("a_b-c.9"))
        assertFalse(ScreenCommands.isValidName(""))
        assertFalse(ScreenCommands.isValidName("a b"))            // space
        assertFalse(ScreenCommands.isValidName("a'b"))            // quote
        assertFalse(ScreenCommands.isValidName("a;rm"))           // shell meta
        assertFalse(ScreenCommands.isValidName("a\nb"))           // newline
        assertFalse(ScreenCommands.isValidName("a".repeat(65)))   // too long

        assertTrue(ScreenCommands.isValidSocket("1234.work"))
        assertTrue(ScreenCommands.isValidSocket("1.pts-0.host"))
        assertFalse(ScreenCommands.isValidSocket("work"))          // needs pid.
        assertFalse(ScreenCommands.isValidSocket("1234.'x'"))      // quote
        assertFalse(ScreenCommands.isValidSocket("1234.a;b"))      // injection
    }

    @Test
    fun `command builders produce the expected shell commands`() {
        assertEquals("screen -ls", ScreenCommands.listSessionsCommand())
        assertEquals("screen -dmS 'work' bash -l", ScreenCommands.createSessionCommand("work"))
        assertEquals("screen -S '1234.work' -X quit", ScreenCommands.killSessionCommand("1234.work"))
        assertEquals("screen -S '1234.work' -X detach", ScreenCommands.detachSessionCommand("1234.work"))
        assertEquals("screen -dr 'work'", ScreenCommands.attachCommand("work"))
        assertEquals("screen -dR 'work'", ScreenCommands.attachOrCreateCommand("work"))
    }

    @Test
    fun `the app takes the mouse back from screen, never hands it to screen`() {
        // Root cause of "scrolling only works inside one window": `mousetrack on` makes SCREEN
        // switch the outer terminal into mouse reporting (ESC[?1000h, measured on screen
        // 4.09.01), so the scroll router sees "the app wants pointer events" in a plain
        // shell too and every drag/wheel stops scrolling. The app must therefore only ever
        // ask for OFF - an inner app that wants the mouse still gets it, because screen
        // forwards the app's own ?1000h/?1002h/?1006h regardless (also measured).
        assertEquals("screen -S 'work' -X mousetrack off", ScreenCommands.disableMouseTrackingCommand("work"))
        assertEquals(
            "screen -S 'x'\\''y' -X mousetrack off",
            ScreenCommands.disableMouseTrackingCommand("x'y"),
        )
    }

    @Test
    fun `names with spaces or metacharacters are safely quoted, not executed`() {
        assertEquals("screen -dmS 'my session' bash -l", ScreenCommands.createSessionCommand("my session"))
        assertEquals("screen -dr 'x; touch /tmp/pwn;'", ScreenCommands.attachCommand("x; touch /tmp/pwn;"))
        val tricky = ScreenCommands.createSessionCommand("a'b")
        // Exact, deterministic command - the apostrophe is escaped POSIX style.
        assertEquals("screen -dmS 'a'\\''b' bash -l", tricky)
    }

    @Test
    fun `unsafe sockets are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ScreenCommands.killSessionCommand("1234.'x'") }
        assertThrows(IllegalArgumentException::class.java) { ScreenCommands.detachSessionCommand("1234.a;b") }
    }

    @Test
    fun `normalize strips control characters but keeps spaces`() {
        assertEquals("my session", ScreenCommands.normalizeName("my session"))
        assertEquals("abc", ScreenCommands.normalizeName("a\r\nb\u0000c"))
        assertEquals("", ScreenCommands.normalizeName("\r\n"))
    }
}
