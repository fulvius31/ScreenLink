package com.sangiorgisrl.screenlink.ui.terminal

import android.view.KeyEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pins the GNU Screen copy-mode byte protocol.
 *
 * screen(1) is explicit that "all keys not described here exit copy mode", so these
 * assertions are not bookkeeping: a wrong byte silently drops the user back into the
 * shell while the app still thinks it is paging history. The bytes below are the ones
 * verified against GNU Screen 4.09.01 (man page + a pty probe against a live session).
 */
class ScreenCopyModeTest {

    @Test
    fun `enter and exit are C-a bracket and escape`() {
        assertEquals("\u0001[", ScreenCopyMode.ENTER) // C-a [
        assertEquals("\u001b", ScreenCopyMode.EXIT)   // ESC
    }

    @Test
    fun `the scroll keys are the emacs display-scroll set, not cursor keys`() {
        // C-y / C-e / C-u / C-d / C-b / C-f scroll the DISPLAY and keep the copy-mode
        // cursor put, which is what a drag gesture needs. k/j only move the cursor.
        assertEquals("\u0019", ScreenCopyMode.LINE_UP)
        assertEquals("\u0005", ScreenCopyMode.LINE_DOWN)
        assertEquals("\u0015", ScreenCopyMode.HALF_PAGE_UP)
        assertEquals("\u0004", ScreenCopyMode.HALF_PAGE_DOWN)
        assertEquals("\u0002", ScreenCopyMode.PAGE_UP)
        assertEquals("\u0006", ScreenCopyMode.PAGE_DOWN)
    }

    @Test
    fun `y is never sent because in copy mode it yanks into the screen paste buffer`() {
        val all = listOf(
            ScreenCopyMode.ENTER, ScreenCopyMode.EXIT, ScreenCopyMode.LINE_UP,
            ScreenCopyMode.LINE_DOWN, ScreenCopyMode.HALF_PAGE_UP, ScreenCopyMode.HALF_PAGE_DOWN,
            ScreenCopyMode.PAGE_UP, ScreenCopyMode.PAGE_DOWN,
            ScreenCopyMode.BUFFER_TOP, ScreenCopyMode.BUFFER_END,
            ScreenCopyMode.FULL_PAGE_UP, ScreenCopyMode.FULL_PAGE_DOWN,
        )
        assertFalse(all.any { it.contains("y") }, "copy-mode yank key must not be used")
        // Only g/G are printable: they are the documented buffer-top/end commands.
        all.flatMap { it.toList() }.forEach { ch ->
            if (ch.code >= 0x20 && ch.code < 0x7f) {
                assertEquals(true, ch == 'g' || ch == 'G' || ch == '[', "unexpected printable key $ch")
            }
        }
    }

    @Test
    fun `positive rowsBack pages back in time and negative returns toward live`() {
        // The app-wide convention: positive = older content. In screen that is C-y.
        assertEquals(ScreenCopyMode.LINE_UP, ScreenCopyMode.scrollCommands(1))
        assertEquals(ScreenCopyMode.LINE_UP + ScreenCopyMode.LINE_UP, ScreenCopyMode.scrollCommands(2))
        assertEquals(ScreenCopyMode.LINE_DOWN, ScreenCopyMode.scrollCommands(-1))
    }

    @Test
    fun `zero rows sends nothing and huge gestures are capped`() {
        assertEquals("", ScreenCopyMode.scrollCommands(0))
        assertEquals(ScreenCopyMode.MAX_LINE_STEPS, ScreenCopyMode.scrollCommands(10_000).length)
        assertEquals(ScreenCopyMode.MAX_LINE_STEPS, ScreenCopyMode.scrollCommands(-10_000).length)
        assertEquals(3, ScreenCopyMode.scrollCommands(-3).length)
    }

    @Test
    fun `stepCount reports the rows a command actually moves so the live offset stays honest`() {
        // The view counts these steps to know when a forward scroll is back at the live
        // prompt (screen clamps there too) and can leave copy mode by itself.
        assertEquals(0, ScreenCopyMode.stepCount(0))
        assertEquals(3, ScreenCopyMode.stepCount(3))
        assertEquals(3, ScreenCopyMode.stepCount(-3))
        assertEquals(ScreenCopyMode.MAX_LINE_STEPS, ScreenCopyMode.stepCount(10_000))
        assertEquals(ScreenCopyMode.MAX_LINE_STEPS, ScreenCopyMode.stepCount(-10_000))
        // The count must match the bytes actually sent, or the offset drifts.
        assertEquals(
            ScreenCopyMode.stepCount(40),
            ScreenCopyMode.scrollCommands(40).length,
            "stepCount must equal the number of keys scrollCommands emits",
        )
    }

    @Test
    fun `page arrow home and end keys drive the history`() {
        // A page is two half-pages: measured, the first C-b at the end of the buffer
        // scrolls only ONE line, which reads as a broken PGUP key.
        assertEquals(ScreenCopyMode.FULL_PAGE_UP, ScreenCopyMode.keyForKeyCode(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(ScreenCopyMode.FULL_PAGE_DOWN, ScreenCopyMode.keyForKeyCode(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(ScreenCopyMode.HALF_PAGE_UP + ScreenCopyMode.HALF_PAGE_UP, ScreenCopyMode.FULL_PAGE_UP)
        assertEquals(ScreenCopyMode.HALF_PAGE_DOWN + ScreenCopyMode.HALF_PAGE_DOWN, ScreenCopyMode.FULL_PAGE_DOWN)
        assertEquals(ScreenCopyMode.LINE_UP, ScreenCopyMode.keyForKeyCode(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(ScreenCopyMode.LINE_DOWN, ScreenCopyMode.keyForKeyCode(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(ScreenCopyMode.BUFFER_TOP, ScreenCopyMode.keyForKeyCode(KeyEvent.KEYCODE_MOVE_HOME))
        assertEquals(ScreenCopyMode.BUFFER_END, ScreenCopyMode.keyForKeyCode(KeyEvent.KEYCODE_MOVE_END))
    }

    @Test
    fun `typing keys are not history keys so the caller can leave copy mode`() {
        // Anything outside the verified set must be reported as "not a history key",
        // otherwise a letter would be swallowed by the pager (or silently exit it).
        assertNull(ScreenCopyMode.keyForKeyCode(KeyEvent.KEYCODE_A))
        assertNull(ScreenCopyMode.keyForKeyCode(KeyEvent.KEYCODE_ENTER))
        assertNull(ScreenCopyMode.keyForKeyCode(KeyEvent.KEYCODE_DEL))
        assertNull(ScreenCopyMode.keyForKeyCode(KeyEvent.KEYCODE_ESCAPE))
        assertNull(ScreenCopyMode.keyForKeyCode(KeyEvent.KEYCODE_DPAD_LEFT))
    }
}
