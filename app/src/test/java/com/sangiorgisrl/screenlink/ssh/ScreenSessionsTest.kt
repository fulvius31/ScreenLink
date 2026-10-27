package com.sangiorgisrl.screenlink.ssh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScreenSessionsTest {

    @Test
    fun `parses each supported state`() {
        val text = """
            |There is a screen on:
            |	1234.work	(Detached)
            |	5678.pts-0.host	(Attached)
            |	9999.old	(Unavailable)
            |1 Socket in /run/screen/S-root.
        """.trimMargin()

        val sessions = ScreenSessions.parse(text)
        assertEquals(3, sessions.size)
        assertEquals("1234.work", sessions[0].socket)
        assertEquals("work", sessions[0].name)
        assertEquals("Detached", sessions[0].state)
        assertEquals("pts-0.host", sessions[1].name)
        assertEquals("Attached", sessions[1].state)
        assertEquals("Unavailable", sessions[2].state)
    }

    @Test
    fun `parses the timestamped format`() {
        val text = "\t1134.demo\t(10/21/26 18:15:31)\t(Detached)\n"
        val sessions = ScreenSessions.parse(text)
        assertEquals(1, sessions.size)
        assertEquals("1134.demo", sessions[0].socket)
        assertEquals("demo", sessions[0].name)
    }

    @Test
    fun `returns empty for no sockets and garbage`() {
        assertTrue(ScreenSessions.parse("No Sockets found in /run/screen/S-root.").isEmpty())
        assertTrue(ScreenSessions.parse("").isEmpty())
        // A malicious socket with shell metachars must be dropped, not passed on.
        val evil = "\t1234.a'; touch /tmp/x ; '\t(Detached)\n"
        assertTrue(ScreenSessions.parse(evil).isEmpty())
    }

    @Test
    fun `does not drown in very long output`() {
        val big = List(2000) { i -> "\t$i.sess$i\t(Detached)" }.joinToString("\n")
        assertEquals(2000, ScreenSessions.parse(big).size)
    }
}
