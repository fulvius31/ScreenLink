package com.sangiorgisrl.screenlink.terminal.emulator

import android.view.KeyEvent.KEYCODE_DPAD_UP
import java.text.Normalizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The VT/ANSI behaviour the app actually relies on, pinned on the JVM.
 *
 * The emulator is what turns the bytes an SSH channel returns into a screen, so a
 * regression here means a garbled terminal on a real device. Each case feeds a byte
 * stream and asserts on the cells, the cursor and the emulator state - exactly what
 * `TerminalView` renders - plus the bytes the emulator sends back over the transport
 * (mouse reports, paste, title changes).
 *
 * Deliberately not covered: OSC 52 (clipboard), which goes through `android.util.Base64`
 * and therefore cannot run on the JVM.
 */
class TerminalEmulatorTest {

    // --- plain text and cursor movement -------------------------------------------------

    @Test
    fun `plain text writes cells and advances the cursor`() {
        val h = EmulatorHarness()
        h.feed("hello")
        assertEquals("hello", h.rows()[0])
        assertEquals(0, h.emulator.cursorRow)
        assertEquals(5, h.emulator.cursorCol)
    }

    @Test
    fun `carriage return returns to the first column without erasing`() {
        val h = EmulatorHarness()
        h.feed("abc\rX")
        assertEquals("Xbc", h.rows()[0])
    }

    @Test
    fun `line feed moves down but keeps the column`() {
        val h = EmulatorHarness()
        h.feed("a\nb")
        assertEquals(listOf("a", " b", "", "", ""), h.rows())
        assertEquals(1, h.emulator.cursorRow)
        assertEquals(2, h.emulator.cursorCol, "the column survived the line feed")
    }

    @Test
    fun `CRLF starts the next line at the first column`() {
        val h = EmulatorHarness()
        h.feed("a\r\nb")
        assertEquals(listOf("a", "b", "", "", ""), h.rows())
        assertEquals(1, h.emulator.cursorRow)
        assertEquals(1, h.emulator.cursorCol)
    }

    @Test
    fun `NUL is ignored`() {
        val h = EmulatorHarness()
        h.feed("a\u0000b")
        assertEquals("ab", h.rows()[0])
    }

    @Test
    fun `CUP moves the cursor to a one-based row and column`() {
        val h = EmulatorHarness()
        h.feed("${ESC}[3;5H")
        assertEquals(2, h.emulator.cursorRow)
        assertEquals(4, h.emulator.cursorCol)
        h.feed("X")
        assertEquals("    X", h.rows()[2])
        assertEquals(5, h.emulator.cursorCol, "writing advances past the cell")
    }

    @Test
    fun `tab stops are every eight columns`() {
        val h = EmulatorHarness()
        h.feed("a\tb")
        assertEquals("a       b", h.rows()[0])
        assertEquals(9, h.emulator.cursorCol)
    }

    @Test
    fun `a tab never runs past the last column`() {
        val h = EmulatorHarness(columns = 10)
        h.feed("\t\t\tX")
        assertEquals(9, h.emulator.cursorCol)
        assertEquals("         X", h.rows()[0])
    }

    // --- control characters -------------------------------------------------------------

    @Test
    fun `backspace moves the cursor left without erasing`() {
        val h = EmulatorHarness()
        h.feed("abc\u0008X")
        assertEquals("abX", h.rows()[0])
    }

    @Test
    fun `backspace steps back onto a line that auto-wrapped`() {
        val h = EmulatorHarness(columns = 10)
        h.feed("0123456789AB${ESC}[2;1H")
        assertTrue(h.emulator.screen.getLineWrap(0), "the wrap flag is set while wrapping")
        h.feed("\u0008")
        assertEquals(0, h.emulator.cursorRow)
        assertEquals(9, h.emulator.cursorCol)
        assertFalse(h.emulator.screen.getLineWrap(0), "and cleared when stepping back")
    }

    @Test
    fun `DEL is not a backspace`() {
        val h = EmulatorHarness()
        h.feed("a\u007F")
        assertEquals(1, h.emulator.cursorCol, "DEL must not move the cursor left like BS does")
        assertEquals("a\u007F", h.rows()[0], "DEL is stored as a zero-width mark in the current cell")
    }

    @Test
    fun `BEL notifies the client without writing a cell`() {
        val h = EmulatorHarness()
        h.feed("a\u0007b")
        assertEquals(1, h.output.bells)
        assertEquals("ab", h.rows()[0])
    }

    // --- wrapping, scrolling, margins ---------------------------------------------------

    @Test
    fun `text auto-wraps onto the next row and marks the line wrapped`() {
        val h = EmulatorHarness(columns = 10)
        h.feed("0123456789AB")
        assertEquals("0123456789", h.rows()[0])
        assertEquals("AB", h.rows()[1])
        assertTrue(h.emulator.screen.getLineWrap(0))
        assertEquals(1, h.emulator.cursorRow)
        assertEquals(2, h.emulator.cursorCol)
    }

    @Test
    fun `resetting DECAWM makes the last column overwrite instead of wrap`() {
        val h = EmulatorHarness(columns = 10)
        h.feed("${ESC}[?7l0123456789AB")
        assertEquals("012345678B", h.rows()[0])
        assertEquals("", h.rows()[1])
    }

    @Test
    fun `a line feed on the bottom row scrolls and counts the scroll`() {
        val h = EmulatorHarness(rows = 3)
        h.feed("1\r\n2\r\n3\r\n4")
        assertEquals(listOf("2", "3", "4"), h.rows())
        assertEquals(1, h.emulator.scrollCounter)
        h.emulator.clearScrollCounter()
        assertEquals(0, h.emulator.scrollCounter)
    }

    @Test
    fun `scrolling pushes lines into the transcript`() {
        val h = EmulatorHarness(rows = 3)
        h.feed("1\r\n2\r\n3\r\n4\r\n5")
        assertEquals(listOf("3", "4", "5"), h.rows())
        assertEquals(2, h.emulator.screen.activeTranscriptRows)
    }

    @Test
    fun `DECSTBM confines scrolling to the margin region`() {
        val h = EmulatorHarness(rows = 6)
        h.feed("1\r\n2\r\n3\r\n4\r\n5\r\n6")
        // Scroll between the 2nd and 4th visible line only.
        h.feed("${ESC}[2;4r${ESC}[4H\n\n")
        assertEquals(listOf("1", "4", "", "", "5", "6"), h.rows())
    }

    @Test
    fun `DECSTBM moves the cursor back to the top left`() {
        val h = EmulatorHarness(rows = 6)
        h.feed("${ESC}[5;3H${ESC}[2;4r")
        assertEquals(0, h.emulator.cursorRow)
        assertEquals(0, h.emulator.cursorCol)
    }

    // --- erasing, inserting, deleting ---------------------------------------------------

    @Test
    fun `ED 2J clears the screen but keeps the transcript`() {
        val h = EmulatorHarness(rows = 3)
        h.feed("1\r\n2\r\n3\r\n4\r\n5")
        assertTrue(h.emulator.screen.activeTranscriptRows > 0)
        h.feed("${ESC}[2J")
        assertEquals(listOf("", "", ""), h.rows())
        assertTrue(h.emulator.screen.activeTranscriptRows > 0, "ED 2J must not drop scrollback")
    }

    @Test
    fun `CSI 3J drops the transcript only`() {
        val h = EmulatorHarness(rows = 3)
        h.feed("1\r\n2\r\n3\r\n4\r\n5")
        assertEquals(2, h.emulator.screen.activeTranscriptRows)
        h.feed("${ESC}[3J")
        assertEquals(0, h.emulator.screen.activeTranscriptRows)
        assertEquals(listOf("3", "4", "5"), h.rows(), "the visible screen is untouched")
    }

    @Test
    fun `ED 0J erases from the cursor to the end of the screen`() {
        val h = EmulatorHarness(rows = 3)
        h.feed("aaa\r\nbbb\r\nccc")
        h.feed("${ESC}[2;3H${ESC}[0J")
        assertEquals(listOf("aaa", "bb", ""), h.rows())
    }

    @Test
    fun `EL 0 erases from the cursor to the end of the line`() {
        val h = EmulatorHarness()
        h.feed("abcde${ESC}[3G${ESC}[0K")
        assertEquals("ab", h.rows()[0])
    }

    @Test
    fun `EL 1 erases from the start of the line to the cursor`() {
        val h = EmulatorHarness()
        h.feed("abcde${ESC}[3G${ESC}[1K")
        assertEquals("   de", h.rows()[0], "the cursor cell is included")
    }

    @Test
    fun `EL 2 erases the whole line`() {
        val h = EmulatorHarness()
        h.feed("abcde${ESC}[2K")
        assertEquals("", h.rows()[0])
    }

    @Test
    fun `IL inserts blank lines and DL deletes them`() {
        val h = EmulatorHarness(rows = 3)
        h.feed("AAA\r\nBBB\r\nCCC${ESC}[H")
        h.feed("${ESC}[1L")
        assertEquals(listOf("", "AAA", "BBB"), h.rows())
        h.feed("${ESC}[H${ESC}[2M")
        assertEquals(listOf("BBB", "", ""), h.rows())
    }

    @Test
    fun `DCH deletes the characters to the right of the cursor`() {
        val h = EmulatorHarness()
        h.feed("abcde${ESC}[H${ESC}[2P")
        assertEquals("cde", h.rows()[0])
    }

    // --- buffers and modes --------------------------------------------------------------

    @Test
    fun `the alternate buffer saves and restores the main screen`() {
        val h = EmulatorHarness()
        h.feed("MAIN")
        h.feed("${ESC}[?1049h${ESC}[H")
        assertTrue(h.emulator.isAlternateBufferActive)
        h.feed("ALT")
        assertEquals("ALT", h.rows()[0])
        h.feed("${ESC}[?1049l")
        assertFalse(h.emulator.isAlternateBufferActive)
        assertEquals("MAIN", h.rows()[0])
    }

    @Test
    fun `DECSET 25 shows and hides the cursor`() {
        val h = EmulatorHarness()
        assertTrue(h.emulator.isCursorEnabled, "the cursor starts enabled")
        h.feed("${ESC}[?25l")
        assertFalse(h.emulator.isCursorEnabled)
        h.feed("${ESC}[?25h")
        assertTrue(h.emulator.isCursorEnabled)
    }

    @Test
    fun `DECSET 1 puts the cursor keys in application mode`() {
        val h = EmulatorHarness()
        h.feed("${ESC}[?1h")
        assertTrue(h.emulator.isCursorKeysApplicationMode)
        assertEquals("\u001BOA", KeyHandler.getCode(KEYCODE_DPAD_UP, 0, true, false))
        h.feed("${ESC}[?1l")
        assertFalse(h.emulator.isCursorKeysApplicationMode)
        assertEquals("\u001B[A", KeyHandler.getCode(KEYCODE_DPAD_UP, 0, false, false))
    }

    @Test
    fun `DECSET 1007 turns alternate scroll reporting on and off`() {
        val h = EmulatorHarness()
        assertFalse(h.emulator.isAlternateScrollActive)
        h.feed("${ESC}[?1007h")
        assertTrue(h.emulator.isAlternateScrollActive)
        h.feed("${ESC}[?1007l")
        assertFalse(h.emulator.isAlternateScrollActive)
    }

    // --- mouse --------------------------------------------------------------------------

    @Test
    fun `the SGR mouse protocol reports a press as an SGR sequence`() {
        val h = EmulatorHarness()
        h.feed("${ESC}[?1000h${ESC}[?1006h")
        assertTrue(h.emulator.isMouseTrackingActive)
        h.output.clearOutput()
        h.emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 3, 4, true)
        assertEquals("${ESC}[<0;3;4M", h.output.text)
    }

    @Test
    fun `the legacy mouse protocol offsets button and coordinates by 32`() {
        val h = EmulatorHarness()
        h.feed("${ESC}[?1000h")
        h.output.clearOutput()
        h.emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 3, 4, true)
        assertEquals("${ESC}[M #\$", h.output.text)
        h.output.clearOutput()
        h.emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 3, 4, false)
        assertEquals("${ESC}[M##\$", h.output.text, "releasing any button is reported as 3")
    }

    @Test
    fun `mouse coordinates are clipped to the screen`() {
        val h = EmulatorHarness(columns = 10, rows = 5)
        h.feed("${ESC}[?1000h${ESC}[?1006h")
        h.output.clearOutput()
        h.emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 99, 99, true)
        assertEquals("${ESC}[<0;10;5M", h.output.text)
    }

    @Test
    fun `drag events stay silent unless button tracking is on`() {
        val h = EmulatorHarness()
        h.feed("${ESC}[?1000h${ESC}[?1006h")
        h.output.clearOutput()
        h.emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, 2, 2, true)
        assertEquals("", h.output.text, "DECSET 1000 alone must not report motion")
        h.feed("${ESC}[?1002h")
        h.emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, 2, 2, true)
        assertEquals("${ESC}[<32;2;2M", h.output.text)
    }

    // --- paste --------------------------------------------------------------------------

    @Test
    fun `bracketed paste wraps the text in the 2004 markers`() {
        val h = EmulatorHarness()
        h.feed("${ESC}[?2004h")
        h.output.clearOutput()
        h.emulator.paste("a\nb")
        assertEquals("${ESC}[200~a\rb${ESC}[201~", h.output.text)
    }

    @Test
    fun `paste strips escapes so pasted text cannot inject sequences`() {
        val h = EmulatorHarness()
        h.output.clearOutput()
        h.emulator.paste("a${ESC}[2J")
        assertEquals("a[2J", h.output.text)
    }

    // --- titles -------------------------------------------------------------------------

    @Test
    fun `OSC 0 sets the title and reports it only when it changes`() {
        val h = EmulatorHarness()
        assertNull(h.emulator.title, "no title until the remote asks for one")
        h.feed("${ESC}]0;first\u0007")
        assertEquals("first", h.emulator.title)
        h.feed("${ESC}]0;first\u0007")
        assertEquals(listOf<Pair<String?, String?>>(null to "first"), h.output.titles)
    }

    @Test
    fun `a title terminated by ST works like one terminated by BEL`() {
        val h = EmulatorHarness()
        h.feed("${ESC}]2;second${ESC}\\")
        assertEquals("second", h.emulator.title)
        assertEquals(1, h.output.titles.size)
    }

    // --- colours ------------------------------------------------------------------------

    @Test
    fun `SGR 31 selects indexed red and stores it in the cell`() {
        val h = EmulatorHarness()
        h.feed("${ESC}[31mR${ESC}[0m")
        val style = h.emulator.screen.getStyleAt(0, 0)
        assertEquals(1, TextStyle.decodeForeColor(style))
        assertEquals(TextStyle.COLOR_INDEX_BACKGROUND, TextStyle.decodeBackColor(style))
        assertEquals(0, TextStyle.decodeEffect(style))
    }

    @Test
    fun `SGR bold and truecolour survive into the cell style`() {
        val h = EmulatorHarness()
        h.feed("${ESC}[1;38;2;255;128;0mX")
        val style = h.emulator.screen.getStyleAt(0, 0)
        assertEquals(0xFFFF8000.toInt(), TextStyle.decodeForeColor(style))
        assertEquals(
            TextStyle.CHARACTER_ATTRIBUTE_BOLD,
            TextStyle.decodeEffect(style) and TextStyle.CHARACTER_ATTRIBUTE_BOLD,
        )
    }

    @Test
    fun `SGR 48 truecolour sets the background`() {
        val h = EmulatorHarness()
        h.feed("${ESC}[48;2;1;2;3mX")
        assertEquals(0xFF010203.toInt(), TextStyle.decodeBackColor(h.emulator.screen.getStyleAt(0, 0)))
    }

    @Test
    fun `OSC 4 repaints an indexed colour and notifies the client`() {
        val h = EmulatorHarness()
        val defaultRed = h.emulator.colors.scheme.defaultColors[1]
        h.feed("${ESC}]4;1;#FF0000${ESC}\\")
        assertEquals(0xFFFF0000.toInt(), h.emulator.colors.currentColors[1])
        assertTrue(defaultRed != h.emulator.colors.currentColors[1])
        assertTrue(h.output.colorChanges > 0, "the view has to be told to repaint its palette")
    }

    @Test
    fun `reset restores colours and SGR state but keeps the title`() {
        val h = EmulatorHarness()
        h.feed("${ESC}]0;kept\u0007${ESC}[31mred")
        h.emulator.reset()
        assertEquals("kept", h.emulator.title)
        assertEquals(
            h.emulator.colors.scheme.defaultColors[1],
            h.emulator.colors.currentColors[1],
            "a dynamically set colour goes back to the theme",
        )
        assertTrue(h.emulator.isCursorEnabled)
        h.feed("\rX")
        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.decodeForeColor(h.emulator.screen.getStyleAt(0, 0)))
    }

    // --- text encoding ------------------------------------------------------------------

    @Test
    fun `multi-byte UTF-8 arrives as one cell`() {
        val h = EmulatorHarness()
        h.feed("caf\u00E9")
        assertEquals("café", h.rows()[0])
        assertEquals(4, h.emulator.cursorCol)
    }

    @Test
    fun `an over-long UTF-8 sequence becomes the replacement character`() {
        val h = EmulatorHarness()
        h.feedBytes(0xC0, 0x80)
        assertEquals("\uFFFD", h.rows()[0])
    }

    @Test
    fun `a byte that cannot start UTF-8 becomes the replacement character`() {
        val h = EmulatorHarness()
        h.feedBytes(0xFF, 'a'.code)
        assertEquals("\uFFFDa", h.rows()[0])
    }

    @Test
    fun `a truncated UTF-8 sequence emits one replacement character and keeps the next byte`() {
        val h = EmulatorHarness()
        // 0xE4 starts a three-byte sequence; 'a' is not a continuation byte.
        h.feedBytes(0xE4, 'a'.code)
        assertEquals("\uFFFDa", h.rows()[0])
    }

    @Test
    fun `C1 controls decoded from UTF-8 are dropped`() {
        val h = EmulatorHarness()
        h.feedBytes('a'.code, 0xC2, 0x85, 'b'.code)
        assertEquals("ab", h.rows()[0])
    }

    @Test
    fun `a four-byte code point survives`() {
        val h = EmulatorHarness()
        h.feed("\uD83D\uDE00")
        assertEquals("\uD83D\uDE00", h.rows()[0])
    }

    @Test
    fun `a wide character occupies two cells`() {
        val h = EmulatorHarness()
        h.feed("\u6F22")
        assertEquals(2, WcWidth.width(0x6F22))
        assertEquals("\u6F22", h.rows()[0])
        assertEquals(2, h.emulator.cursorCol)
    }

    @Test
    fun `a combining mark joins the previous cell`() {
        val h = EmulatorHarness()
        h.feed("e\u0301")
        assertEquals("\u00E9", Normalizer.normalize(h.rows()[0], Normalizer.Form.NFC))
        assertEquals(1, h.emulator.cursorCol, "a zero-width mark must not advance the cursor")
    }

    // --- character sets -----------------------------------------------------------------

    @Test
    fun `the DEC special line-drawing set maps ASCII to box characters`() {
        val h = EmulatorHarness()
        h.feed("${ESC}(0jqqqqk${ESC}(B")
        assertEquals("\u2518\u2500\u2500\u2500\u2500\u2510", h.rows()[0])
    }

    // --- resizing -----------------------------------------------------------------------

    @Test
    fun `shrinking the rows keeps the newest lines on screen`() {
        val h = EmulatorHarness(rows = 5)
        h.feed("1\r\n2\r\n3\r\n4\r\n5")
        h.emulator.resize(EmulatorHarness.DEFAULT_COLUMNS, 3)
        assertEquals(3, h.emulator.rows)
        assertEquals(listOf("3", "4", "5"), h.rows())
    }

    @Test
    fun `narrowing the columns re-wraps the text`() {
        val h = EmulatorHarness(columns = 10, rows = 5)
        h.feed("abcdefghij\r\nkl")
        h.emulator.resize(4, 5)
        assertEquals(4, h.emulator.columns)
        assertEquals(listOf("abcd", "efgh", "ij", "kl", ""), h.rows())
    }

    @Test
    fun `resizing rejects a degenerate screen`() {
        val h = EmulatorHarness()
        val error = runCatching { h.emulator.resize(1, 5) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "expected a guard, got $error")
    }
}
