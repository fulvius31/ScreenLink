package com.sangiorgisrl.screenlink.ssh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildScreenCommandTest {

    @Test
    fun `attach strictly resumes while create attaches-or-creates`() {
        // Attach (-dr) FAILS if the session is gone - the user asked for THEIR session,
        // never a silently-created fresh shell.
        assertEquals("screen -dr 'demo'", buildTerminalScreenCommand("attach", "demo"))
        // Create (-dR) is the tolerant "attaches or creates" used by the default-session
        // quick-open and the Create button.
        assertEquals("screen -dR 'demo'", buildTerminalScreenCommand("create", "demo"))
    }

    @Test
    fun `shell mode has no command`() {
        assertNull(buildTerminalScreenCommand("shell", "demo"))
    }

    @Test
    fun `null and blank names produce null`() {
        assertNull(buildTerminalScreenCommand("attach", null))
        assertNull(buildTerminalScreenCommand("attach", ""))
        assertNull(buildTerminalScreenCommand("attach", "\n\t "))
    }

    @Test
    fun `malicious names become inert quoted strings, never separate commands`() {
        val cmd = buildTerminalScreenCommand("create", "x'; touch /tmp/pwn; '")
        assertTrue(cmd != null)
        // POSIX single-quote escaping: apostrophes inside the name are escaped.
        assertEquals("screen -dR 'x'\\''; touch /tmp/pwn; '\\'''", cmd)
        // Balanced quotes and no top-level shell separators.
        assertEquals(0, cmd!!.count { it == '\'' } % 2)
    }

    @Test
    fun `names with spaces stay a single screen invocation`() {
        assertEquals("screen -dR 'my work session'", buildTerminalScreenCommand("create", "my work session"))
    }

    @Test
    fun `control characters are stripped from route names`() {
        assertEquals("screen -dR 'abc'", buildTerminalScreenCommand("create", "a\r\nb\u0000c"))
    }

    @Test
    fun `unknown mode yields no command`() {
        assertNull(buildTerminalScreenCommand("whatever", "demo"))
    }
}

class FriendlyScreenNameTest {
    @Test
    fun `tab label for a socket id shows the screen session name`() {
        assertEquals("work", ScreenCommands.friendlyScreenName("1234.work"))
        assertEquals("demo", ScreenCommands.friendlyScreenName("1134.demo"))
    }

    @Test
    fun `tab label keeps a plain session name unchanged`() {
        assertEquals("work", ScreenCommands.friendlyScreenName("work"))
        assertEquals("my work session", ScreenCommands.friendlyScreenName("my work session"))
    }

    @Test
    fun `tab label strips control characters and trims`() {
        assertEquals("demo", ScreenCommands.friendlyScreenName("  \n1234.demo\r\t "))
        assertEquals("", ScreenCommands.friendlyScreenName("  \n\t "))
    }

    @Test
    fun `unnamed screen sockets keep their pty suffix`() {
        assertEquals("pts-0.myserver", ScreenCommands.friendlyScreenName("1234.pts-0.myserver"))
    }
}
