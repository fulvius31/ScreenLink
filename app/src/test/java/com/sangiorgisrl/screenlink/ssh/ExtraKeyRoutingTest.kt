package com.sangiorgisrl.screenlink.ssh

import android.view.KeyEvent
import com.sangiorgisrl.screenlink.terminal.emulator.KeyHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies the byte sequences the in-app extra keys produce.
 * The keyboard delegates to TerminalView.handleKeyCode(), which calls
 * KeyHandler.getCode() - the same path a hardware keyboard takes.
 */
class ExtraKeyRoutingTest {

    private fun code(keyCode: Int, mods: Int, cursorApp: Boolean = false, keypadApp: Boolean = false): String? =
        KeyHandler.getCode(keyCode, mods, cursorApp, keypadApp)

    @Test
    fun `arrows use the current cursor-key mode`() {
        // Normal mode: standard escape sequences bash/readline understands.
        assertEquals("\u001b[A", code(KeyEvent.KEYCODE_DPAD_UP, 0))
        assertEquals("\u001b[B", code(KeyEvent.KEYCODE_DPAD_DOWN, 0))
        assertEquals("\u001b[D", code(KeyEvent.KEYCODE_DPAD_LEFT, 0))
        assertEquals("\u001b[C", code(KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        // Application cursor mode (e.g. inside vim/readline emacs): OA..OD.
        assertEquals("\u001bOA", code(KeyEvent.KEYCODE_DPAD_UP, 0, cursorApp = true))
    }

    @Test
    fun `tab and enter produce the plain control characters`() {
        assertEquals("\t", code(KeyEvent.KEYCODE_TAB, 0))
        assertEquals("\r", code(KeyEvent.KEYCODE_ENTER, 0))
        assertEquals("\u007f", code(KeyEvent.KEYCODE_DEL, 0))      // Backspace
        assertEquals("\u001b[3~", code(KeyEvent.KEYCODE_FORWARD_DEL, 0)) // Del (forward)
    }

    @Test
    fun `ctrl latch affects printable keycodes in the emulator's own way`() {
        // Ctrl+Up arrow sends the xterm modifier form, exactly like a hardware Ctrl+arrow.
        val ctrlUp = code(KeyEvent.KEYCODE_DPAD_UP, KeyHandler.KEYMOD_CTRL)
        assertEquals("\u001b[1;5A", ctrlUp)
    }

    @Test
    fun `escape and navigation keys work`() {
        assertEquals("\u001b", code(KeyEvent.KEYCODE_ESCAPE, 0))
        assertEquals("\u001b[H", code(KeyEvent.KEYCODE_MOVE_HOME, 0))   // Home (FN+Left)
        assertEquals("\u001b[F", code(KeyEvent.KEYCODE_MOVE_END, 0))    // End
    }
}
