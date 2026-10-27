package com.sangiorgisrl.screenlink.terminal.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Turning cells back into text.
 *
 * Copy, long-press selection and double-tap word selection all end up in these three
 * methods, and the wrapping bookkeeping is what decides whether a copied shell command
 * keeps its newlines. The screen is deliberately narrow so the wrap cases are visible.
 */
class TerminalBufferTextTest {

    @Test
    fun `a wrapped line is joined back together when copying`() {
        val harness = EmulatorHarness(columns = 10, rows = 5, transcriptRows = 100)
        harness.feed("abcdefghijkl") // 12 columns of text on a 10 column screen

        assertEquals("abcdefghij", harness.row(0))
        assertEquals("kl", harness.row(1))
        assertEquals("abcdefghijkl", harness.emulator.screen.getSelectedText(0, 0, 9, 1))
        assertEquals("abcdefghij\nkl", harness.emulator.screen.getSelectedText(0, 0, 9, 1, false))
    }

    @Test
    fun `a line that was not wrapped keeps its newline`() {
        val harness = EmulatorHarness(columns = 10, rows = 5, transcriptRows = 100)
        harness.feed("abc\r\ndef\r\n")

        assertEquals("abc\ndef", harness.emulator.screen.getSelectedText(0, 0, 9, 1))
    }

    @Test
    fun `a hard newline inside a wrapped line survives the join`() {
        val harness = EmulatorHarness(columns = 10, rows = 5, transcriptRows = 100)
        harness.feed("abcdefgh\r\nxy\r\n")

        assertEquals("abcdefgh\nxy", harness.emulator.screen.getSelectedText(0, 0, 9, 1))
    }

    @Test
    fun `the transcript text includes what scrolled off the top`() {
        val harness = EmulatorHarness(columns = 10, rows = 3, transcriptRows = 100)
        harness.feed((1..6).joinToString("\r\n") { "L$it" })

        assertEquals(3, harness.emulator.screen.activeTranscriptRows)
        assertEquals("L1\nL2\nL3\nL4\nL5\nL6", harness.emulator.screen.transcriptText)
        assertEquals("L4\nL5\nL6", harness.emulator.getSelectedText(0, 0, 9, 2))
    }

    @Test
    fun `the transcript variants differ only in how wrapped lines are joined`() {
        val harness = EmulatorHarness(columns = 10, rows = 3, transcriptRows = 100)
        harness.feed("abcdefghij") // wraps
        harness.feed("kl\r\nxy\r\n")

        assertEquals("abcdefghijkl\nxy", harness.emulator.screen.transcriptText)
        assertEquals("abcdefghij\nkl\nxy", harness.emulator.screen.transcriptTextWithoutJoinedLines)
        assertEquals("abcdefghijkl\nxy", harness.emulator.screen.transcriptTextWithFullLinesJoined)
    }

    @Test
    fun `a selection outside the screen is clamped instead of crashing`() {
        val harness = EmulatorHarness(columns = 10, rows = 3, transcriptRows = 100)
        harness.feed("one\r\ntwo\r\n")

        // Row indexes beyond the visible screen are what the view sends while the user
        // drags a handle past the edge.
        assertEquals("one\ntwo", harness.emulator.screen.getSelectedText(0, -50, 9, 99).trim())
    }

    @Test
    fun `a partial selection returns exactly the selected cells`() {
        val harness = EmulatorHarness(columns = 20, rows = 5)
        harness.feed("hello world\r\n")

        assertEquals("llo", harness.emulator.screen.getSelectedText(2, 0, 4, 0))
        assertEquals("hello world", harness.emulator.screen.getSelectedText(0, 0, 19, 0))
        assertEquals("world", harness.emulator.screen.getSelectedText(6, 0, 10, 0))
    }

    @Test
    fun `double tap picks the word under the cell`() {
        val harness = EmulatorHarness(columns = 20, rows = 5)
        harness.feed("hello world\r\nsecond line\r\n")

        assertEquals("hello", harness.emulator.screen.getWordAtLocation(0, 0))
        assertEquals("hello", harness.emulator.screen.getWordAtLocation(4, 0))
        assertEquals("world", harness.emulator.screen.getWordAtLocation(6, 0))
        assertEquals("world", harness.emulator.screen.getWordAtLocation(10, 0))
        assertEquals("second", harness.emulator.screen.getWordAtLocation(0, 1))
    }

    @Test
    fun `double tap on a blank returns nothing`() {
        val harness = EmulatorHarness(columns = 20, rows = 5)
        harness.feed("hello world\r\n")

        assertEquals("", harness.emulator.screen.getWordAtLocation(5, 0), "the space between the words")
        assertEquals("", harness.emulator.screen.getWordAtLocation(15, 0), "past the last word on the line")
    }

    @Test
    fun `a word split by wrapping is picked as one word`() {
        val harness = EmulatorHarness(columns = 10, rows = 5)
        harness.feed("abcdefghij") // no newline: the row is marked as wrapped
        harness.feed("klmn")

        assertEquals("abcdefghijklmn", harness.emulator.screen.getWordAtLocation(0, 0))
        assertEquals("abcdefghijklmn", harness.emulator.screen.getWordAtLocation(0, 1))
    }

    @Test
    fun `selecting half of a wide character still yields the whole glyph`() {
        val harness = EmulatorHarness(columns = 10, rows = 5)
        harness.feed("漢字")

        assertEquals("漢字", harness.row(0))
        assertEquals("漢", harness.emulator.screen.getSelectedText(0, 0, 0, 0))
        assertEquals("漢", harness.emulator.screen.getSelectedText(0, 0, 1, 0))
        assertEquals("字", harness.emulator.screen.getSelectedText(2, 0, 3, 0))
    }

    @Test
    fun `trailing blanks are not part of the copied text`() {
        val harness = EmulatorHarness(columns = 10, rows = 5)
        harness.feed("ab   \r\n")

        assertEquals("ab", harness.emulator.screen.getSelectedText(0, 0, 9, 0))
        assertEquals("", harness.emulator.screen.getSelectedText(0, 1, 9, 1), "an empty row copies as nothing")
        // A wrapped row keeps its trailing cells, otherwise the next row would look glued on.
        harness.feed("0123456789")
        assertEquals("0123456789", harness.emulator.screen.getSelectedText(0, 1, 9, 1))
    }
}
