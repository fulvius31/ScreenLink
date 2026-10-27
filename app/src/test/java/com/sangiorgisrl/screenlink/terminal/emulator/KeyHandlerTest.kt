package com.sangiorgisrl.screenlink.terminal.emulator

import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_DEL
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_ESCAPE
import android.view.KeyEvent.KEYCODE_F1
import android.view.KeyEvent.KEYCODE_F2
import android.view.KeyEvent.KEYCODE_F5
import android.view.KeyEvent.KEYCODE_FORWARD_DEL
import android.view.KeyEvent.KEYCODE_NUMPAD_2
import android.view.KeyEvent.KEYCODE_NUMPAD_DOT
import android.view.KeyEvent.KEYCODE_NUM_LOCK
import android.view.KeyEvent.KEYCODE_SPACE
import android.view.KeyEvent.KEYCODE_TAB
import com.sangiorgisrl.screenlink.terminal.emulator.KeyHandler.KEYMOD_ALT
import com.sangiorgisrl.screenlink.terminal.emulator.KeyHandler.KEYMOD_CTRL
import com.sangiorgisrl.screenlink.terminal.emulator.KeyHandler.KEYMOD_NUM_LOCK
import com.sangiorgisrl.screenlink.terminal.emulator.KeyHandler.KEYMOD_SHIFT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Key press to escape sequence.
 *
 * This table is the whole keyboard contract with the remote shell: get one entry wrong and
 * the user sees `~` instead of an arrow, or Ctrl-C stops working. The KeyEvent constants
 * are compile-time ints, so this runs on the JVM without a device.
 */
class KeyHandlerTest {

    private fun code(keyCode: Int, keyMod: Int = 0, cursorApp: Boolean = false, keypadApp: Boolean = false): String? =
        KeyHandler.getCode(keyCode, keyMod, cursorApp, keypadApp)

    @Test
    fun `arrows depend on the cursor key mode`() {
        assertEquals("\u001B[A", code(KEYCODE_DPAD_UP))
        assertEquals("\u001B[B", code(KEYCODE_DPAD_DOWN))
        assertEquals("\u001B[C", code(KEYCODE_DPAD_RIGHT))
        assertEquals("\u001B[D", code(KEYCODE_DPAD_LEFT))
        // DECCKM (application cursor keys) swaps [ for O.
        assertEquals("\u001BOA", code(KEYCODE_DPAD_UP, cursorApp = true))
        assertEquals("\u001BOC", code(KEYCODE_DPAD_RIGHT, cursorApp = true))
    }

    @Test
    fun `modifiers are encoded in the xterm parameter form`() {
        assertEquals("\u001B[1;2A", code(KEYCODE_DPAD_UP, KEYMOD_SHIFT))
        assertEquals("\u001B[1;3A", code(KEYCODE_DPAD_UP, KEYMOD_ALT))
        assertEquals("\u001B[1;5A", code(KEYCODE_DPAD_UP, KEYMOD_CTRL))
        assertEquals("\u001B[1;8A", code(KEYCODE_DPAD_UP, KEYMOD_SHIFT or KEYMOD_ALT or KEYMOD_CTRL))
    }

    @Test
    fun `function keys use the vt100 form without modifiers and the tilde form with them`() {
        assertEquals("\u001BOP", code(KEYCODE_F1))
        assertEquals("\u001BOQ", code(KEYCODE_F2))
        assertEquals("\u001B[1;2P", code(KEYCODE_F1, KEYMOD_SHIFT))
        assertEquals("\u001B[15~", code(KEYCODE_F5))
        assertEquals("\u001B[15;5~", code(KEYCODE_F5, KEYMOD_CTRL))
    }

    @Test
    fun `backspace follows xterm rather than the vt100 NUL convention`() {
        assertEquals("\u007F", code(KEYCODE_DEL))
        assertEquals("\u0008", code(KEYCODE_DEL, KEYMOD_CTRL))
        assertEquals("\u001B\u007F", code(KEYCODE_DEL, KEYMOD_ALT))
        assertEquals("\u001B\u0008", code(KEYCODE_DEL, KEYMOD_ALT or KEYMOD_CTRL))
        assertEquals("\u001B[3~", code(KEYCODE_FORWARD_DEL))
    }

    @Test
    fun `space falls through to normal input unless ctrl is held`() {
        // Returning a sequence here would break dead keys / combining accents on soft keyboards.
        assertNull(code(KEYCODE_SPACE))
        assertEquals("\u0000", code(KEYCODE_SPACE, KEYMOD_CTRL))
    }

    @Test
    fun `tab and shift-tab`() {
        assertEquals("\u0009", code(KEYCODE_TAB))
        assertEquals("\u001B[Z", code(KEYCODE_TAB, KEYMOD_SHIFT))
    }

    @Test
    fun `enter and escape`() {
        assertEquals("\r", code(KEYCODE_ENTER))
        assertEquals("\u001B\r", code(KEYCODE_ENTER, KEYMOD_ALT))
        assertEquals("\u001B", code(KEYCODE_ESCAPE))
        assertEquals("\u001B", code(KEYCODE_BACK))
    }

    @Test
    fun `numpad digits depend on num lock and keypad mode`() {
        assertEquals("2", code(KEYCODE_NUMPAD_2, KEYMOD_NUM_LOCK))
        assertEquals("\u001BOr", code(KEYCODE_NUMPAD_2, KEYMOD_NUM_LOCK, keypadApp = true))
        // Num lock off: the key is a navigation key, so cursor mode applies.
        assertEquals("\u001B[B", code(KEYCODE_NUMPAD_2))
        assertEquals("\u001BOB", code(KEYCODE_NUMPAD_2, cursorApp = true))
        // Num lock off on the dot key is a delete.
        assertEquals("\u001B[3~", code(KEYCODE_NUMPAD_DOT))
        assertEquals(".", code(KEYCODE_NUMPAD_DOT, KEYMOD_NUM_LOCK))
    }

    @Test
    fun `num lock itself only means something in keypad application mode`() {
        assertNull(code(KEYCODE_NUM_LOCK))
        assertEquals("\u001BOP", code(KEYCODE_NUM_LOCK, keypadApp = true))
    }

    @Test
    fun `an unmapped key code produces nothing`() {
        assertNull(code(12345))
    }

    @Test
    fun `termcap names resolve through the same table`() {
        assertEquals("\u001B[C", KeyHandler.getCodeFromTermcap("kr", false, false))
        assertEquals("\u001BOC", KeyHandler.getCodeFromTermcap("kr", true, false))
        assertEquals("\u001B[A", KeyHandler.getCodeFromTermcap("ku", false, false))
        assertEquals("\u001B[Z", KeyHandler.getCodeFromTermcap("kB", false, false))
        assertEquals("\u007F", KeyHandler.getCodeFromTermcap("kb", false, false))
        assertEquals("\u001B[3~", KeyHandler.getCodeFromTermcap("kD", false, false))
        assertEquals("\u001B[1;2C", KeyHandler.getCodeFromTermcap("%i", false, false))
        assertNull(KeyHandler.getCodeFromTermcap("nope", false, false))
    }
}
