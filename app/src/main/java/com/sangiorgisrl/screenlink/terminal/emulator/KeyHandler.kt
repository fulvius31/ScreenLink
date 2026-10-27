package com.sangiorgisrl.screenlink.terminal.emulator

/*
 * Derived from the terminal-emulator module of the Termux terminal emulator
 * (https://github.com/termux/termux-app), Copyright (c) the Termux contributors,
 * Apache License 2.0 - see the NOTICE file at the root of this repository.
 * Rewritten in Kotlin for this application.
 */

import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_BREAK
import android.view.KeyEvent.KEYCODE_DEL
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_ESCAPE
import android.view.KeyEvent.KEYCODE_F1
import android.view.KeyEvent.KEYCODE_F10
import android.view.KeyEvent.KEYCODE_F11
import android.view.KeyEvent.KEYCODE_F12
import android.view.KeyEvent.KEYCODE_F2
import android.view.KeyEvent.KEYCODE_F3
import android.view.KeyEvent.KEYCODE_F4
import android.view.KeyEvent.KEYCODE_F5
import android.view.KeyEvent.KEYCODE_F6
import android.view.KeyEvent.KEYCODE_F7
import android.view.KeyEvent.KEYCODE_F8
import android.view.KeyEvent.KEYCODE_F9
import android.view.KeyEvent.KEYCODE_FORWARD_DEL
import android.view.KeyEvent.KEYCODE_INSERT
import android.view.KeyEvent.KEYCODE_MOVE_END
import android.view.KeyEvent.KEYCODE_MOVE_HOME
import android.view.KeyEvent.KEYCODE_NUM_LOCK
import android.view.KeyEvent.KEYCODE_NUMPAD_0
import android.view.KeyEvent.KEYCODE_NUMPAD_1
import android.view.KeyEvent.KEYCODE_NUMPAD_2
import android.view.KeyEvent.KEYCODE_NUMPAD_3
import android.view.KeyEvent.KEYCODE_NUMPAD_4
import android.view.KeyEvent.KEYCODE_NUMPAD_5
import android.view.KeyEvent.KEYCODE_NUMPAD_6
import android.view.KeyEvent.KEYCODE_NUMPAD_7
import android.view.KeyEvent.KEYCODE_NUMPAD_8
import android.view.KeyEvent.KEYCODE_NUMPAD_9
import android.view.KeyEvent.KEYCODE_NUMPAD_ADD
import android.view.KeyEvent.KEYCODE_NUMPAD_COMMA
import android.view.KeyEvent.KEYCODE_NUMPAD_DIVIDE
import android.view.KeyEvent.KEYCODE_NUMPAD_DOT
import android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
import android.view.KeyEvent.KEYCODE_NUMPAD_EQUALS
import android.view.KeyEvent.KEYCODE_NUMPAD_MULTIPLY
import android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT
import android.view.KeyEvent.KEYCODE_PAGE_DOWN
import android.view.KeyEvent.KEYCODE_PAGE_UP
import android.view.KeyEvent.KEYCODE_SPACE
import android.view.KeyEvent.KEYCODE_SYSRQ
import android.view.KeyEvent.KEYCODE_TAB

/**
 * The translation from an Android key event to the bytes a VT/xterm client expects.
 *
 * A key code plus its modifier bits becomes either a control sequence or `null`, where
 * `null` means "do not intercept this key, let normal text input handle it".
 */
object KeyHandler {

    const val KEYMOD_ALT = 0x80000000.toInt()
    const val KEYMOD_CTRL = 0x40000000
    const val KEYMOD_SHIFT = 0x20000000
    const val KEYMOD_NUM_LOCK = 0x10000000

    /**
     * terminfo: http://pubs.opengroup.org/onlinepubs/7990989799/xcurses/terminfo.html
     * termcap: http://man7.org/linux/man-pages/man5/termcap.5.html
     *
     * Each value is a key code with the KEYMOD_* bits folded in; [getCodeFromTermcap]
     * splits them back apart again.
     */
    private val TERMCAP_TO_KEYCODE: Map<String, Int> = mapOf(
        "%i" to (KEYMOD_SHIFT or KEYCODE_DPAD_RIGHT),
        "#2" to (KEYMOD_SHIFT or KEYCODE_MOVE_HOME), // Shifted home
        "#4" to (KEYMOD_SHIFT or KEYCODE_DPAD_LEFT),
        "*7" to (KEYMOD_SHIFT or KEYCODE_MOVE_END), // Shifted end key

        "k1" to KEYCODE_F1,
        "k2" to KEYCODE_F2,
        "k3" to KEYCODE_F3,
        "k4" to KEYCODE_F4,
        "k5" to KEYCODE_F5,
        "k6" to KEYCODE_F6,
        "k7" to KEYCODE_F7,
        "k8" to KEYCODE_F8,
        "k9" to KEYCODE_F9,
        "k;" to KEYCODE_F10,
        "F1" to KEYCODE_F11,
        "F2" to KEYCODE_F12,
        "F3" to (KEYMOD_SHIFT or KEYCODE_F1),
        "F4" to (KEYMOD_SHIFT or KEYCODE_F2),
        "F5" to (KEYMOD_SHIFT or KEYCODE_F3),
        "F6" to (KEYMOD_SHIFT or KEYCODE_F4),
        "F7" to (KEYMOD_SHIFT or KEYCODE_F5),
        "F8" to (KEYMOD_SHIFT or KEYCODE_F6),
        "F9" to (KEYMOD_SHIFT or KEYCODE_F7),
        "FA" to (KEYMOD_SHIFT or KEYCODE_F8),
        "FB" to (KEYMOD_SHIFT or KEYCODE_F9),
        "FC" to (KEYMOD_SHIFT or KEYCODE_F10),
        "FD" to (KEYMOD_SHIFT or KEYCODE_F11),
        "FE" to (KEYMOD_SHIFT or KEYCODE_F12),

        "kb" to KEYCODE_DEL, // backspace key

        "kd" to KEYCODE_DPAD_DOWN, // terminfo=kcud1, down-arrow key
        "kh" to KEYCODE_MOVE_HOME,
        "kl" to KEYCODE_DPAD_LEFT,
        "kr" to KEYCODE_DPAD_RIGHT,

        // K1=Upper left of keypad:
        // t_K1 <kHome> keypad home key
        // t_K3 <kPageUp> keypad page-up key
        // t_K4 <kEnd> keypad end key
        // t_K5 <kPageDown> keypad page-down key
        "K1" to KEYCODE_MOVE_HOME,
        "K3" to KEYCODE_PAGE_UP,
        "K4" to KEYCODE_MOVE_END,
        "K5" to KEYCODE_PAGE_DOWN,

        "ku" to KEYCODE_DPAD_UP,

        "kB" to (KEYMOD_SHIFT or KEYCODE_TAB), // termcap=kB, terminfo=kcbt: Back-tab
        "kD" to KEYCODE_FORWARD_DEL, // terminfo=kdch1, delete-character key
        "kDN" to (KEYMOD_SHIFT or KEYCODE_DPAD_DOWN), // non-standard shifted arrow down
        "kF" to (KEYMOD_SHIFT or KEYCODE_DPAD_DOWN), // terminfo=kind, scroll-forward key
        "kI" to KEYCODE_INSERT,
        "kN" to KEYCODE_PAGE_UP,
        "kP" to KEYCODE_PAGE_DOWN,
        "kR" to (KEYMOD_SHIFT or KEYCODE_DPAD_UP), // terminfo=kri, scroll-backward key
        "kUP" to (KEYMOD_SHIFT or KEYCODE_DPAD_UP), // non-standard shifted up

        "@7" to KEYCODE_MOVE_END,
        "@8" to KEYCODE_NUMPAD_ENTER,
    )

    /** The reply a termcap-capable program asked for, or null if it is unknown. */
    @JvmStatic
    fun getCodeFromTermcap(termcap: String?, cursorKeysApplication: Boolean, keypadApplication: Boolean): String? {
        val keyCodeAndMod = TERMCAP_TO_KEYCODE[termcap ?: return null] ?: return null
        var keyCode = keyCodeAndMod
        var keyMod = 0
        for (modifier in intArrayOf(KEYMOD_SHIFT, KEYMOD_CTRL, KEYMOD_ALT, KEYMOD_NUM_LOCK)) {
            if (keyCode and modifier != 0) {
                keyMod = keyMod or modifier
                keyCode = keyCode and modifier.inv()
            }
        }
        return getCode(keyCode, keyMod, cursorKeysApplication, keypadApplication)
    }

    /**
     * The bytes for [keyCode] with [keyMode] modifiers, or null when the key should fall
     * through to ordinary text input.
     *
     * @param cursorApp DECCKM: arrows and friends use the `ESC O x` form instead of `ESC [ x`.
     * @param keypadApplication DECNKM: the numpad sends application-mode sequences.
     */
    @JvmStatic
    fun getCode(keyCode: Int, keyMode: Int, cursorApp: Boolean, keypadApplication: Boolean): String? {
        var modifiers = keyMode
        val numLockOn = modifiers and KEYMOD_NUM_LOCK != 0
        modifiers = modifiers and KEYMOD_NUM_LOCK.inv()

        // `ESC [ 1 ; <modifier> <letter>` for the cursor-key family.
        fun cursorSequence(letter: Char) =
            if (modifiers == 0) {
                if (cursorApp) "\u001BO$letter" else "\u001B[$letter"
            } else {
                transformForModifiers("\u001B[1", modifiers, letter)
            }

        // `ESC O <letter>` when the keypad is in application mode, plain text otherwise.
        fun keypadSequence(letter: Char, plain: String) =
            if (keypadApplication) transformForModifiers("\u001BO", modifiers, letter) else plain

        return when (keyCode) {
            KEYCODE_DPAD_CENTER -> "\r"

            KEYCODE_DPAD_UP -> cursorSequence('A')
            KEYCODE_DPAD_DOWN -> cursorSequence('B')
            KEYCODE_DPAD_RIGHT -> cursorSequence('C')
            KEYCODE_DPAD_LEFT -> cursorSequence('D')

            // KEYCODE_HOME is owned by the system and never reaches an app; a Logitech
            // k810 sends KEYCODE_MOVE_HOME for FN+LeftArrow instead.
            KEYCODE_MOVE_HOME -> cursorSequence('H')
            KEYCODE_MOVE_END -> cursorSequence('F')

            // An xterm can send F1-F4 either vt100-compatible (`ESC O P`) or as
            // `ESC [ 11 ~`, and Home/End likewise. Vim understands both, so we do too.
            KEYCODE_F1 -> if (modifiers == 0) "\u001BOP" else transformForModifiers("\u001B[1", modifiers, 'P')
            KEYCODE_F2 -> if (modifiers == 0) "\u001BOQ" else transformForModifiers("\u001B[1", modifiers, 'Q')
            KEYCODE_F3 -> if (modifiers == 0) "\u001BOR" else transformForModifiers("\u001B[1", modifiers, 'R')
            KEYCODE_F4 -> if (modifiers == 0) "\u001BOS" else transformForModifiers("\u001B[1", modifiers, 'S')
            KEYCODE_F5 -> transformForModifiers("\u001B[15", modifiers, '~')
            KEYCODE_F6 -> transformForModifiers("\u001B[17", modifiers, '~')
            KEYCODE_F7 -> transformForModifiers("\u001B[18", modifiers, '~')
            KEYCODE_F8 -> transformForModifiers("\u001B[19", modifiers, '~')
            KEYCODE_F9 -> transformForModifiers("\u001B[20", modifiers, '~')
            KEYCODE_F10 -> transformForModifiers("\u001B[21", modifiers, '~')
            KEYCODE_F11 -> transformForModifiers("\u001B[23", modifiers, '~')
            KEYCODE_F12 -> transformForModifiers("\u001B[24", modifiers, '~')

            KEYCODE_SYSRQ -> "\u001B[32~" // Sys Request / Print
            // Is this Scroll lock? KEYCODE_CANCEL -> "[33~"
            KEYCODE_BREAK -> "\u001B[34~" // Pause/Break

            KEYCODE_ESCAPE, KEYCODE_BACK -> "\u001B"

            KEYCODE_INSERT -> transformForModifiers("\u001B[2", modifiers, '~')
            KEYCODE_FORWARD_DEL -> transformForModifiers("\u001B[3", modifiers, '~')

            KEYCODE_PAGE_UP -> "\u001B[5~"
            KEYCODE_PAGE_DOWN -> "\u001B[6~"

            KEYCODE_DEL -> {
                val prefix = if (modifiers and KEYMOD_ALT == 0) "" else "\u001B"
                // Just do what xterm and gnome-terminal does:
                prefix + if (modifiers and KEYMOD_CTRL == 0) "\u007F" else "\u0008"
            }

            KEYCODE_NUM_LOCK -> if (keypadApplication) "\u001BOP" else null

            // Without ctrl, let the space fall through to normal input processing, which
            // is what lets a combining accent be written.
            KEYCODE_SPACE -> if (modifiers and KEYMOD_CTRL == 0) null else "\u0000"

            KEYCODE_TAB -> if (modifiers and KEYMOD_SHIFT == 0) "\t" else "\u001B[Z"

            KEYCODE_ENTER -> if (modifiers and KEYMOD_ALT == 0) "\r" else "\u001B\r"

            KEYCODE_NUMPAD_ENTER -> keypadSequence('M', "\n")
            KEYCODE_NUMPAD_MULTIPLY -> keypadSequence('j', "*")
            KEYCODE_NUMPAD_ADD -> keypadSequence('k', "+")
            KEYCODE_NUMPAD_COMMA -> ","
            KEYCODE_NUMPAD_DOT ->
                if (numLockOn) {
                    if (keypadApplication) "\u001BOn" else "."
                } else {
                    transformForModifiers("\u001B[3", modifiers, '~') // DELETE
                }
            KEYCODE_NUMPAD_SUBTRACT -> keypadSequence('m', "-")
            KEYCODE_NUMPAD_DIVIDE -> keypadSequence('o', "/")
            KEYCODE_NUMPAD_0 ->
                if (numLockOn) {
                    keypadSequence('p', "0")
                } else {
                    transformForModifiers("\u001B[2", modifiers, '~') // INSERT
                }
            KEYCODE_NUMPAD_1 -> if (numLockOn) keypadSequence('q', "1") else cursorSequence('F') // END
            KEYCODE_NUMPAD_2 -> if (numLockOn) keypadSequence('r', "2") else cursorSequence('B') // DOWN
            KEYCODE_NUMPAD_3 -> if (numLockOn) keypadSequence('s', "3") else "\u001B[6~" // PGDN
            KEYCODE_NUMPAD_4 -> if (numLockOn) keypadSequence('t', "4") else cursorSequence('D') // LEFT
            KEYCODE_NUMPAD_5 -> keypadSequence('u', "5")
            KEYCODE_NUMPAD_6 -> if (numLockOn) keypadSequence('v', "6") else cursorSequence('C') // RIGHT
            KEYCODE_NUMPAD_7 -> if (numLockOn) keypadSequence('w', "7") else cursorSequence('H') // HOME
            KEYCODE_NUMPAD_8 -> if (numLockOn) keypadSequence('x', "8") else cursorSequence('A') // UP
            KEYCODE_NUMPAD_9 -> if (numLockOn) keypadSequence('y', "9") else "\u001B[5~" // PGUP
            KEYCODE_NUMPAD_EQUALS -> keypadSequence('X', "=")

            else -> null
        }
    }

    /**
     * Insert the xterm modifier parameter into a sequence of the form
     * `ESC [ <num> <lastChar>`, giving `ESC [ <num> ; <modifier> <lastChar>`.
     */
    private fun transformForModifiers(start: String, keymod: Int, lastChar: Char): String {
        val modifier = when (keymod) {
            KEYMOD_SHIFT -> 2
            KEYMOD_ALT -> 3
            KEYMOD_SHIFT or KEYMOD_ALT -> 4
            KEYMOD_CTRL -> 5
            KEYMOD_SHIFT or KEYMOD_CTRL -> 6
            KEYMOD_ALT or KEYMOD_CTRL -> 7
            KEYMOD_SHIFT or KEYMOD_ALT or KEYMOD_CTRL -> 8
            else -> return start + lastChar
        }
        return "$start;$modifier$lastChar"
    }
}
