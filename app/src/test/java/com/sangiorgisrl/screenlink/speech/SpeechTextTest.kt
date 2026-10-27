package com.sangiorgisrl.screenlink.speech

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a spoken sentence becomes before it is typed into a shell.
 *
 * The rule under test is one sentence long and worth repeating: **dictation can
 * never run a command.** Recognizers emit the punctuation of prose - line breaks at
 * sentence boundaries, curly quotes, dashes - and a terminal reads a line break as
 * Enter. So every transcript that arrives here is checked for the characters that
 * would act on the shell rather than describe text to it.
 */
class SpeechTextTest {

    @Test
    fun `an ordinary command line survives untouched`() {
        assertEquals("git commit -m 'fix build'", SpeechText.forTerminal("git commit -m 'fix build'"))
        assertEquals("ls -la /var/log | less", SpeechText.forTerminal("ls -la /var/log | less"))
    }

    @Test
    fun `nothing that comes back can contain a line break`() {
        // The single most dangerous thing a recognizer can return: a sentence
        // boundary in the middle of a command line is a command that runs itself.
        val raw = "cd /var/log\nsudo tail -f syslog\r\n"

        val safe = SpeechText.forTerminal(raw)

        assertEquals("cd /var/log sudo tail -f syslog", safe)
        assertFalse(safe.contains('\n'), "a newline in terminal input is Enter")
        assertFalse(safe.contains('\r'))
    }

    @Test
    fun `escape and the other control characters become a space, not an escape sequence`() {
        // ESC is what would let a "transcript" reprogram the terminal, move the
        // cursor, or rewrite the line the user is typing. Only the control character
        // is dropped: the printable text that followed it is ordinary input, and
        // brackets belong in a command line.
        assertEquals("ls [2J", SpeechText.forTerminal("ls\u001B[2J\u0007"))
        // C1 controls (the 0x80-0x9F range) are the other half of the same attack.
        assertEquals("a b", SpeechText.forTerminal("a\u009Bb"))
        assertEquals("a b", SpeechText.forTerminal("a\u0085b"))
        // Tabs and form feeds are word separators, not literal tabs.
        assertEquals("a b c", SpeechText.forTerminal("a\tb\u000Cc"))
    }

    @Test
    fun `word processor punctuation folds back to what a shell accepts`() {
        // Curly quotes look identical to straight ones and break the command.
        assertEquals("git commit -m \"fix\"", SpeechText.forTerminal("git commit -m \u201Cfix\u201D"))
        assertEquals("it's here", SpeechText.forTerminal("it\u2019s here"))
        // A dash that is not hyphen-minus is not a minus sign, and not a flag.
        assertEquals("a - b --x", SpeechText.forTerminal("a \u2013 b \u2014\u2011x"))
        assertEquals("x-1", SpeechText.forTerminal("x\u22121"))
        assertEquals("wait ...", SpeechText.forTerminal("wait \u2026"))
    }

    @Test
    fun `invisible characters never reach the pty`() {
        // Zero-width joiners and the BOM type as nothing at all, which makes the
        // resulting "command not found" impossible to debug by eye.
        assertEquals("abc", SpeechText.forTerminal("a\u200Bb\u200Dc\uFEFF"))
        assertEquals("abc", SpeechText.forTerminal("a\u00ADbc"))
        assertEquals("abc", SpeechText.forTerminal("a\u2060bc"))
    }

    @Test
    fun `runs of whitespace collapse to one space and the ends are trimmed`() {
        assertEquals("one two", SpeechText.forTerminal("   one \n\n  two   "))
        // A non-breaking space is still a separator: typing it into a command line
        // gives an argument the shell cannot split.
        assertEquals("one two", SpeechText.forTerminal("one\u00A0two"))
        assertEquals("", SpeechText.forTerminal("   \n\t "))
    }

    @Test
    fun `text that is not latin is kept`() {
        // Folding is for lookalikes, not for other alphabets: a user dictating a
        // path or a commit message in Spanish or Japanese must get their text.
        assertEquals("café ni\u00F1o \u4F60\u597D \u043F\u0440\u0438\u0432\u0435\u0442", SpeechText.forTerminal("caf\u00E9 ni\u00F1o \u4F60\u597D \u043F\u0440\u0438\u0432\u0435\u0442"))
        assertEquals("🚀 launch", SpeechText.forTerminal("🚀 launch"))
    }

    @Test
    fun `an endless utterance is cut rather than typed forever`() {
        // A recognizer left running streams without limit; past this point the text
        // would be a wall of characters fed into the prompt one code point at a time.
        val huge = "a".repeat(SpeechText.MAX_CHARS + 500)

        val safe = SpeechText.forTerminal(huge)

        assertEquals(SpeechText.MAX_CHARS, safe.length)
    }

    @Test
    fun `a cut-off sentence still trims its dangling space`() {
        val raw = "a".repeat(SpeechText.MAX_CHARS - 1) + " \u2026 and more"

        val safe = SpeechText.forTerminal(raw)

        assertTrue(safe.length <= SpeechText.MAX_CHARS, "was ${safe.length}")
        assertFalse(safe.endsWith(" "), "the cut must not leave trailing whitespace")
    }

    @Test
    fun `nothing usable comes back as an empty string`() {
        assertEquals("", SpeechText.forTerminal(""))
        assertEquals("", SpeechText.forTerminal(null))
        // A transcript of only punctuation-that-is-not-text still has no payload.
        assertEquals("", SpeechText.forTerminal("\u200B\uFEFF\n"))
    }

    @Test
    fun `a broken surrogate pair does not crash or leak a lone half`() {
        // Recognizers do not produce these, but a truncated MAX_CHARS cut can, and
        // a lone surrogate typed into a pty becomes mojibake.
        val safe = SpeechText.forTerminal("a\uD800b")

        assertEquals("a b", safe)
    }
}
