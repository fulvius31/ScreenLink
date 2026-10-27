package com.sangiorgisrl.screenlink.ui.terminal

import android.view.KeyEvent

/**
 * GNU Screen's own scrollback ("copy mode"), driven through the pty.
 *
 * Why this exists: a session attached through GNU Screen keeps its history on the
 * SERVER. When the app attaches, screen repaints the window with absolute cursor
 * positioning, so the emulator's own transcript starts empty - output produced
 * before this client attached (or while the app was closed) is simply not in the
 * app's scrollback and no amount of local scrolling can reach it. The only way to
 * read it is to ask screen itself to scroll, which is what copy mode does.
 *
 * Every byte here was verified against GNU Screen 4.09.01 (its man page plus a
 * pty probe against a live session). That matters more than usual because
 *
 *     "All keys not described here exit copy mode."   - screen(1)
 *
 * so an unverified key is not merely ignored, it silently leaves copy mode and
 * types into the shell. Only the keys below are used, and the app leaves copy mode
 * itself before sending anything the user meant as input.
 *
 * The scroll keys are the emacs-style ones, which scroll the DISPLAY while keeping
 * the copy-mode cursor where it is (unlike `k`/`j`, which move the cursor and only
 * scroll once it reaches an edge - a poor fit for a drag gesture). `y` is
 * deliberately NOT used: in copy mode it yanks a line into screen's paste buffer.
 */
object ScreenCopyMode {

    /** `C-a [` - enter copy mode (status line: "Copy mode - Column x Line y(+1024)"). */
    const val ENTER = "\u0001["

    /** `ESC` - leave copy mode. Safe when already out: screen prints "Copy mode aborted". */
    const val EXIT = "\u001b"

    /** `C-y` - scroll the display down one line, i.e. BACK in time (older content). */
    const val LINE_UP = "\u0019"

    /** `C-e` - scroll the display up one line, i.e. toward the live prompt. */
    const val LINE_DOWN = "\u0005"

    /** `C-u` / `C-d` - half a screen back / forward (12 rows on a 24-row window). */
    const val HALF_PAGE_UP = "\u0015"
    const val HALF_PAGE_DOWN = "\u0004"

    /**
     * `C-b` / `C-f` - a full screen back / forward. Documented here because they are
     * part of the protocol, but the app does NOT use them: measured against a live
     * session, the FIRST `C-b` at the end of the buffer scrolls a single line (it has
     * to keep the copy-mode cursor inside the buffer), so a PGUP press would feel
     * broken. [FULL_PAGE_UP] / [FULL_PAGE_DOWN] below are built from the half-page
     * keys instead, which measured exactly 12 rows every time, cursor position
     * irrelevant.
     */
    const val PAGE_UP = "\u0002"
    const val PAGE_DOWN = "\u0006"

    /** A deterministic full page: two half-pages = 24 rows on a 24-row window. */
    const val FULL_PAGE_UP = HALF_PAGE_UP + HALF_PAGE_UP
    const val FULL_PAGE_DOWN = HALF_PAGE_DOWN + HALF_PAGE_DOWN

    /** `g` / `G` - top of the scrollback buffer / bottom (the live prompt). */
    const val BUFFER_TOP = "g"
    const val BUFFER_END = "G"

    /**
     * Cap for one gesture/button press. A finger drag reports a handful of rows at a
     * time; anything wilder (a huge fling) is clamped so a single write cannot bury
     * the remote in keystrokes, and the next event continues from there.
     */
    const val MAX_LINE_STEPS = 24

    /**
     * Bytes that scroll screen's display by [rowsBack] lines.
     *
     * Sign convention matches [TerminalView]-side scrolling everywhere in this app:
     * positive moves BACK in time (older content), negative moves toward the live
     * prompt, zero sends nothing.
     */
    @JvmStatic
    fun scrollCommands(rowsBack: Int): String {
        val steps = stepCount(rowsBack)
        if (steps == 0) return ""
        val key = if (rowsBack > 0) LINE_UP else LINE_DOWN
        return key.repeat(steps)
    }

    /**
     * How many lines [scrollCommands] will actually move for [rowsBack] - i.e. the
     * requested rows after the [MAX_LINE_STEPS] clamp. The view needs this to keep an
     * honest count of how far it has moved into screen's scrollback, so it knows when a
     * forward scroll has come back to the live prompt (screen clamps there too).
     */
    @JvmStatic
    fun stepCount(rowsBack: Int): Int =
        if (rowsBack == 0) 0 else minOf(kotlin.math.abs(rowsBack), MAX_LINE_STEPS)

    /**
     * The copy-mode key for a hardware/in-app key code, or null when the key is not
     * part of the verified set (the caller then leaves copy mode and lets the shell
     * have the key, instead of risking an accidental exit).
     */
    @JvmStatic
    fun keyForKeyCode(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_PAGE_UP -> FULL_PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> FULL_PAGE_DOWN
        KeyEvent.KEYCODE_DPAD_UP -> LINE_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> LINE_DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> BUFFER_TOP
        KeyEvent.KEYCODE_MOVE_END -> BUFFER_END
        else -> null
    }
}
