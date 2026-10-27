package com.sangiorgisrl.screenlink.ui.screens

import androidx.annotation.StringRes
import com.sangiorgisrl.screenlink.R

/**
 * Pure character rules for the in-app terminal keyboard, kept out of the
 * composable so they can be unit-tested on the JVM.
 */
internal object KeyboardChars {

    /**
     * Every symbol the symbols page offers, in layout order. Shell punctuation that
     * only exists in shifted form is reachable through the SHIFT latch on the number
     * row, so this list holds the unshifted keys.
     */
    val SYMBOLS: List<Char> = listOf(
        '$', '@', '&', '(', ')', '[', ']', '{', '}',
        '*', '#', '!', '?', '%', '^', '_', '-',
        ':', ';', '\'', '"', '<', '>', '=', '+',
        '/', '\\', '`', '~', '|', ',', '.',
    )

    /**
     * The symbols page as rows. A full-height window gets four rows of eight; a
     * "Short" window (landscape phone) gets the same symbols in fewer, wider rows -
     * four pad rows plus the fixed rows would push the keyboard's own bottom row,
     * and the terminal, off the screen.
     */
    fun symbolRows(perRow: Int): List<List<Char>> {
        require(perRow > 0) { "perRow must be positive" }
        return SYMBOLS.chunked(perRow)
    }

    /**
     * The glyph a SHIFTED key produces, the way a hardware keyboard does it.
     *
     * Letters are handled by uppercasing (see [TerminalKeyboard.sendLetter]); this
     * covers the number row and the punctuation that only exists in shifted form.
     * Returns the character unchanged when there is no shifted variant, so a key
     * can never send the wrong byte.
     */
    fun shifted(c: Char): Char = when (c) {
        '1' -> '!'
        '2' -> '@'
        '3' -> '#'
        '4' -> '$'
        '5' -> '%'
        '6' -> '^'
        '7' -> '&'
        '8' -> '*'
        '9' -> '('
        '0' -> ')'
        '-' -> '_'
        '=' -> '+'
        '[' -> '{'
        ']' -> '}'
        ';' -> ':'
        '\'' -> '"'
        ',' -> '<'
        '.' -> '>'
        '/' -> '?'
        '`' -> '~'
        else -> c
    }

    /** True when [c] has a shifted variant that differs from itself. */
    fun hasShifted(c: Char): Boolean = shifted(c) != c

    /**
     * A spoken name for punctuation, used as the accessibility label.
     *
     * A bare symbol as a content description is read inconsistently by screen
     * readers ("|" may be announced as "pipe" or "vertical bar" or skipped), so
     * the keyboard labels its keys with words a TalkBack user can act on.
     *
     * Returns a string RESOURCE id (0 when the character has no name of its own)
     * so the announcement is in the user's language: "less than" is not a term a
     * Spanish-speaking screen-reader user hears.
     */
    @StringRes
    fun spokenNameRes(c: Char): Int = when (c) {
        '!' -> R.string.sym_exclamation
        '@' -> R.string.sym_at
        '#' -> R.string.sym_hash
        '$' -> R.string.sym_dollar
        '%' -> R.string.sym_percent
        '^' -> R.string.sym_caret
        '&' -> R.string.sym_ampersand
        '*' -> R.string.sym_asterisk
        '(' -> R.string.sym_open_parenthesis
        ')' -> R.string.sym_close_parenthesis
        '-' -> R.string.sym_minus
        '_' -> R.string.sym_underscore
        '=' -> R.string.sym_equals
        '+' -> R.string.sym_plus
        '[' -> R.string.sym_open_bracket
        ']' -> R.string.sym_close_bracket
        '{' -> R.string.sym_open_brace
        '}' -> R.string.sym_close_brace
        ';' -> R.string.sym_semicolon
        ':' -> R.string.sym_colon
        '\'' -> R.string.sym_apostrophe
        '"' -> R.string.sym_quote
        ',' -> R.string.sym_comma
        '<' -> R.string.sym_less_than
        '.' -> R.string.sym_period
        '>' -> R.string.sym_greater_than
        '/' -> R.string.sym_slash
        '\\' -> R.string.sym_backslash
        '|' -> R.string.sym_pipe
        '`' -> R.string.sym_backtick
        '~' -> R.string.sym_tilde
        '?' -> R.string.sym_question
        ' ' -> R.string.sym_space
        else -> 0
    }
}
