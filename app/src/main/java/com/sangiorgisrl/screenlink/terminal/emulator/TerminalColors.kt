package com.sangiorgisrl.screenlink.terminal.emulator

/*
 * Derived from the terminal-emulator module of the Termux terminal emulator
 * (https://github.com/termux/termux-app), Copyright (c) the Termux contributors,
 * Apache License 2.0 - see the NOTICE file at the root of this repository.
 * Rewritten in Kotlin for this application.
 */

/**
 * The indexed colours currently in use, which OSC 4 can repaint at any time.
 *
 * One instance per session, and [scheme] belongs to that instance: a remote program
 * that repaints index 1 (or pushes a palette through OSC 4) must not repaint the
 * terminal of another, unrelated connection. Upstream kept the defaults in a
 * process-wide static, which is fine for one session per process and wrong here.
 */
class TerminalColors(
    /** The palette [reset] falls back to. Owned by this instance, so it can be
     *  reconfigured per connection without touching any other session. */
    val scheme: TerminalColorScheme = TerminalColorScheme(),
) {

    /**
     * The live palette: normally the theme defaults, but a remote program can change
     * single entries with the OSC 4 control sequence.
     */
    @JvmField
    val currentColors = IntArray(TextStyle.NUM_INDEXED_COLORS)

    init {
        reset()
    }

    /** Put one index back to the colour the theme asked for. */
    fun reset(index: Int) {
        currentColors[index] = scheme.defaultColors[index]
    }

    /** Put every index back to the colour the theme asked for. */
    fun reset() {
        scheme.defaultColors.copyInto(currentColors)
    }

    /** Parse [textParameter] and, only if it is a colour, store it at [intoIndex]. */
    fun tryParseColor(intoIndex: Int, textParameter: String) {
        val color = parse(textParameter)
        if (color != 0) currentColors[intoIndex] = color
    }

    companion object {

    /**
     * Parse an X11-style colour spec (see XQueryColor(3)): `#RGB`, `#RRGGBB`,
     * `#RRRGGGBBB`, `#RRRRGGGGBBBB` or `rgb:h/h/h` with one to four hex digits each.
     *
     * Returns `0xFFRRGGBB` on success and `0` when the string is not a colour at all,
     * which is how callers tell "not a colour" from "black".
     */
    internal fun parse(color: String): Int {
        try {
            val skipInitial: Int
            val skipBetween: Int
            if (color.startsWith("#")) {
                skipInitial = 1
                skipBetween = 0
            } else if (color.startsWith("rgb:")) {
                skipInitial = 4
                skipBetween = 1
            } else {
                return 0
            }

            val digitsForAllThree = color.length - skipInitial - 2 * skipBetween
            if (digitsForAllThree % 3 != 0) return 0 // Unequal components.
            val digits = digitsForAllThree / 3
            val scale = 255.0 / ((1 shl (4 * digits)) - 1)

            var at = skipInitial
            fun nextComponent(): String = color.substring(at, at + digits).also { at += digits + skipBetween }
            val red = nextComponent()
            val green = nextComponent()
            val blue = nextComponent()

            val r = (Integer.parseInt(red, 16) * scale).toInt()
            val g = (Integer.parseInt(green, 16) * scale).toInt()
            val b = (Integer.parseInt(blue, 16) * scale).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        } catch (e: NumberFormatException) {
            return 0
        } catch (e: IndexOutOfBoundsException) {
            return 0
        }
    }
    }
}
