package com.sangiorgisrl.screenlink.speech

/**
 * What a recognizer transcript becomes before it reaches the terminal.
 *
 * A speech transcript is not terminal input, and the difference is not cosmetic:
 *
 *  - **A dictated newline must never become Enter.** Recognizers put line breaks at
 *    sentence boundaries, and Enter in a terminal RUNS the line. Everything that is
 *    not a printable character therefore collapses to a single space, so the worst
 *    a dictation can do is leave a long command line waiting for the user to press
 *    Enter themselves.
 *  - **Escape sequences cannot be smuggled in.** ESC and the C1 controls are what
 *    would let a "transcript" reprogram the terminal or move the cursor somewhere
 *    else; they are stripped by the same rule.
 *  - **Recognizers type like a word processor.** Curly quotes, en dashes and an
 *    ellipsis look fine and break a shell command: `git commit -m “fix”` is a syntax
 *    error, and `—` is not a minus. The lookalikes below are folded back to the
 *    ASCII the shell actually accepts.
 */
object SpeechText {

    /**
     * A dictated command line, not an essay. A recognizer left running can stream far
     * more than a shell line holds, and everything past this point would be typed
     * into the prompt one character at a time.
     */
    const val MAX_CHARS = 1200

    /**
     * @return terminal-safe text, or "" when the transcript held nothing usable.
     */
    fun forTerminal(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val out = StringBuilder(raw.length.coerceAtMost(MAX_CHARS) + 8)
        var pendingSpace = false
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)

            val folded = asciiLookalike(cp)
            if (folded != null) {
                if (out.length >= MAX_CHARS) break
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                // "..." is three characters for one transcript character, so the cap
                // is applied to the replacement too: the limit is a hard limit.
                out.append(folded, 0, folded.length.coerceAtMost(MAX_CHARS - out.length))
                continue
            }

            if (isInvisible(cp)) continue

            // Controls (C0 + C1, so ESC, CR and LF included), every kind of space and
            // anything with no printable form: one space, collapsed with the next.
            if (isSeparator(cp)) {
                pendingSpace = true
                continue
            }

            // An emoji is two UTF-16 units: it is dropped whole at the limit rather
            // than cut in half, because half a surrogate pair is mojibake on the wire.
            if (out.length > MAX_CHARS - Character.charCount(cp)) break
            if (pendingSpace && out.isNotEmpty()) out.append(' ')
            pendingSpace = false
            out.appendCodePoint(cp)
        }
        return out.toString().trim()
    }

    /**
     * Typographic punctuation a recognizer emits where a terminal wants ASCII.
     * Returns the replacement (possibly several characters), or null when [cp] is
     * not a lookalike.
     */
    private fun asciiLookalike(cp: Int): String? = when (cp) {
        // Curly / prime apostrophes, and the acute accent recognizers use for one.
        '\u2018'.code, '\u2019'.code, '\u201A'.code, '\u201B'.code,
        '\u02BC'.code, '\u02BB'.code, '\u00B4'.code, '\u2032'.code,
        -> "'"
        // Curly double quotes and the prime (inches).
        '\u201C'.code, '\u201D'.code, '\u201E'.code, '\u201F'.code, '\u2033'.code -> "\""
        // Dash family: a hyphen-minus is what a flag, an option or a negative number wants.
        '\u2010'.code, '\u2011'.code, '\u2012'.code, '\u2013'.code,
        '\u2014'.code, '\u2015'.code, '\u2212'.code,
        -> "-"
        '\u2026'.code -> "..."
        else -> null
    }

    /**
     * Characters that carry no visible glyph and no meaning at a prompt: zero-width
     * joiners, the BOM, word-joiners, soft hyphens. Typed into a command line they
     * become invisible characters the shell cannot match, which is the worst kind of
     * bug to debug, so they never reach the pty at all.
     */
    private fun isInvisible(cp: Int): Boolean = when (cp) {
        '\u200B'.code, '\u200C'.code, '\u200D'.code, '\u2060'.code,
        '\uFEFF'.code, '\u00AD'.code,
        -> true
        else -> false
    }

    /** Anything that separates words rather than being one: controls, spaces, no-glyph. */
    private fun isSeparator(cp: Int): Boolean =
        Character.isISOControl(cp) ||
            Character.isSpaceChar(cp) ||
            Character.isWhitespace(cp) ||
            // getType() answers a byte, so compare it as one.
            Character.getType(cp).toInt() == Character.FORMAT.toInt() ||
            Character.getType(cp).toInt() == Character.UNASSIGNED.toInt() ||
            Character.getType(cp).toInt() == Character.SURROGATE.toInt()
}
