package com.sangiorgisrl.screenlink.terminal.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the emulator's own scrollback (the "transcript") can and cannot recover.
 *
 * This is the reason the UI offers GNU Screen copy mode (see
 * `ui/terminal/ScreenCopyMode.kt`): output that a full-screen program REPAINTS into
 * place never enters the transcript, so no amount of local scrolling can bring it
 * back - only the remote's own history can.
 *
 * Runs on the JVM: the emulator is plain Kotlin, so the behaviour is pinned without a
 * device. The scaffolding is the shared [EmulatorHarness] rather than a private pair of
 * no-op collaborators - one place to build an emulator is one place to fix when the
 * emulator grows a callback.
 */
class TerminalTranscriptTest {

    private fun emulatorFeeding(stream: String): TerminalEmulator =
        EmulatorHarness(columns = 80, rows = 24).feed(stream).emulator

    @Test
    fun `line-feed output grows the local transcript even in the alternate buffer`() {
        // GNU Screen emulates the alternate buffer itself and passes a plain shell's
        // output through as ordinary line feeds, so that history IS reachable locally.
        val stream = buildString {
            append("\u001b[?1049h\u001b[1;24r\u001b[H\u001b[J")
            for (i in 1..120) append("LINE-$i\r\n")
        }
        val emulator = emulatorFeeding(stream)
        assertTrue(emulator.isAlternateBufferActive, "screen keeps the alt buffer active")
        assertTrue(
            emulator.screen.activeTranscriptRows > 0,
            "expected the LF stream to be scrollable locally, got ${emulator.screen.activeTranscriptRows}",
        )
    }

    @Test
    fun `repainted full-screen output never reaches the transcript`() {
        // A TUI that homes the cursor and scrolls with CSI M writes its lines into
        // place: nothing is "pushed off" the top, so the transcript stays empty and
        // local scrolling has nothing to show. This is exactly the case that needs the
        // remote's own scrollback (screen copy mode / the app's page keys).
        val stream = buildString {
            append("\u001b[?1049h\u001b[1;24r\u001b[H\u001b[J")
            for (i in 1..120) {
                append("\u001b[H\u001b[M")
                append("\u001b[24;1HLINE-$i")
            }
        }
        val emulator = emulatorFeeding(stream)
        assertEquals(0, emulator.screen.activeTranscriptRows)
    }
}
