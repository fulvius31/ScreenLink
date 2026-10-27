package com.sangiorgisrl.screenlink.terminal.emulator

import java.util.Properties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Colour parsing and the palette.
 *
 * OSC 4 lets the remote program repaint the palette, and the app's own palette arrives
 * through [TerminalColorScheme.updateWith] - both go through the X11-ish [TerminalColors.parse],
 * whose contract is "0 means the string was not a colour".
 *
 * Every palette here is a local one: nothing in this class touches shared state, which
 * is the point of [TerminalColors] owning its [TerminalColorScheme] rather than reading
 * a process-wide default.
 */
class TerminalColorsTest {

    @Test
    fun `a new instance starts from the theme defaults`() {
        val colors = TerminalColors()
        for (i in 0 until TextStyle.NUM_INDEXED_COLORS) {
            assertEquals(colors.scheme.defaultColors[i], colors.currentColors[i], "index $i")
        }
    }

    @Test
    fun `hex colours are accepted in short and long form`() {
        val opaqueRed = 0xFFFF0000.toInt()
        assertEquals(opaqueRed, TerminalColors.parse("#F00"))
        assertEquals(opaqueRed, TerminalColors.parse("#FF0000"))
        assertEquals(0xFF777777.toInt(), TerminalColors.parse("#777777"))
        assertEquals(0xFF000000.toInt(), TerminalColors.parse("#000000"))
    }

    @Test
    fun `rgb colon form scales each component to a byte`() {
        assertEquals(0xFFFF0000.toInt(), TerminalColors.parse("rgb:ff/00/00"))
        assertEquals(0xFFFF0000.toInt(), TerminalColors.parse("rgb:f/0/0"))
        assertEquals(0xFF808080.toInt(), TerminalColors.parse("rgb:80/80/80"))
    }

    @Test
    fun `named colours and malformed input parse to zero`() {
        // parse() only understands # and rgb: - X11 names are not resolved, and a broken
        // string must not repaint the palette.
        assertEquals(0, TerminalColors.parse("red"))
        assertEquals(0, TerminalColors.parse("#GG0000"))
        assertEquals(0, TerminalColors.parse("#FF000"))
        assertEquals(0, TerminalColors.parse("rgb:ff/00"))
        assertEquals(0, TerminalColors.parse(""))
        assertEquals(0, TerminalColors.parse("#"))
    }

    @Test
    fun `tryParseColor keeps the previous entry when the string is not a colour`() {
        val colors = TerminalColors()
        val untouched = colors.currentColors[4]
        colors.tryParseColor(4, "#00FF00")
        assertEquals(0xFF00FF00.toInt(), colors.currentColors[4])
        colors.tryParseColor(4, "nonsense")
        assertEquals(0xFF00FF00.toInt(), colors.currentColors[4])
        colors.tryParseColor(4, "")
        assertEquals(0xFF00FF00.toInt(), colors.currentColors[4])
        colors.reset(4)
        assertEquals(untouched, colors.currentColors[4])
    }

    @Test
    fun `reset restores one entry and all of them`() {
        val colors = TerminalColors()
        colors.currentColors[1] = 0xFF123456.toInt()
        colors.currentColors[255] = 0xFF123456.toInt()
        colors.reset(1)
        assertEquals(colors.scheme.defaultColors[1], colors.currentColors[1])
        assertEquals(0xFF123456.toInt(), colors.currentColors[255])
        colors.reset()
        for (i in 0 until TextStyle.NUM_INDEXED_COLORS) {
            assertEquals(colors.scheme.defaultColors[i], colors.currentColors[i], "index $i")
        }
    }

    @Test
    fun `two sessions do not share a palette`() {
        // The regression this guards: the defaults used to be one static array for the
        // whole process, so a remote program repainting index 1 in one connection
        // changed what an unrelated connection reset back to.
        val first = TerminalColors()
        val second = TerminalColors()
        first.currentColors[1] = 0xFF00FF00.toInt()
        first.scheme.updateWith(Properties().apply { setProperty("color9", "#FF0000") })

        assertEquals(0xffcd0000.toInt(), second.currentColors[1], "live palette must be private")
        assertEquals(0xffff0000.toInt(), second.currentColors[9], "theme palette must be private")

        first.reset()
        assertEquals(0xffcd0000.toInt(), first.currentColors[1], "reset returns to this session's own theme")
        assertEquals(0xFFFF0000.toInt(), first.currentColors[9], "what this session set survives its own reset")
    }

    @Test
    fun `the scheme maps foreground background cursor and colourN keys`() {
        val scheme = TerminalColorScheme()
        val defaultForeground = scheme.defaultColors[TextStyle.COLOR_INDEX_FOREGROUND]
        val props = Properties()
        props.setProperty("foreground", "#FF8800")
        props.setProperty("background", "#102030")
        props.setProperty("cursor", "#FFFFFF")
        props.setProperty("color1", "#FF0000")
        scheme.updateWith(props)

        assertEquals(0xFFFF8800.toInt(), scheme.defaultColors[TextStyle.COLOR_INDEX_FOREGROUND])
        assertEquals(0xFF102030.toInt(), scheme.defaultColors[TextStyle.COLOR_INDEX_BACKGROUND])
        assertEquals(0xFFFFFFFF.toInt(), scheme.defaultColors[TextStyle.COLOR_INDEX_CURSOR])
        assertEquals(0xFFFF0000.toInt(), scheme.defaultColors[1])
        // Untouched entries keep the xterm chart values.
        assertEquals(0xff00cd00.toInt(), scheme.defaultColors[2])

        scheme.updateWith(Properties())
        assertEquals(defaultForeground, scheme.defaultColors[TextStyle.COLOR_INDEX_FOREGROUND])
    }

    @Test
    fun `an empty property set resets the scheme to the built-in defaults`() {
        val scheme = TerminalColorScheme()
        scheme.updateWith(Properties().apply { setProperty("color9", "#FF0000") })
        assertEquals(0xFFFF0000.toInt(), scheme.defaultColors[9])
        scheme.updateWith(Properties())
        assertEquals(0xffff0000.toInt(), scheme.defaultColors[9])
        assertEquals(TextStyle.NUM_INDEXED_COLORS, scheme.defaultColors.size)
    }

    @Test
    fun `a rejected update leaves the palette exactly as it was`() {
        // Properties iterates in unspecified order, which is exactly why the good key
        // must not be able to land before the bad one is noticed: a half-applied
        // palette is live state that the session keeps resetting back to.
        val scheme = TerminalColorScheme()
        val defaultRed = 0xffcd0000.toInt()
        scheme.updateWith(Properties().apply { setProperty("color9", "#FF0000") })
        assertEquals(0xFFFF0000.toInt(), scheme.defaultColors[9])

        val halfBad = Properties().apply {
            setProperty("color1", "#00FF00")
            setProperty("nonsense", "#FF0000")
        }
        assertThrows(IllegalArgumentException::class.java) { scheme.updateWith(halfBad) }

        assertEquals(defaultRed, scheme.defaultColors[1], "the good key must not have been applied")
        assertEquals(0xFFFF0000.toInt(), scheme.defaultColors[9], "the earlier state must survive")
    }

    @Test
    fun `a colour index outside the palette is rejected instead of crashing`() {
        // The palette is 256 indexed slots plus the foreground/background/cursor pseudo
        // slots, so 258 is the last legal index and 259 is the first one that used to run
        // straight off the end of the array.
        val scheme = TerminalColorScheme()
        for (key in listOf("color259", "color-1", "color99999", "color")) {
            assertThrows(
                IllegalArgumentException::class.java,
                { scheme.updateWith(Properties().apply { setProperty(key, "#FF0000") }) },
                key,
            )
        }
        assertEquals(0xffcd0000.toInt(), scheme.defaultColors[1], "the palette must be untouched")
    }

    @Test
    fun `unknown keys and unparsable colours are rejected loudly`() {
        val scheme = TerminalColorScheme()
        assertThrows(IllegalArgumentException::class.java) {
            scheme.updateWith(Properties().apply { setProperty("foregroundy", "#FF0000") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            scheme.updateWith(Properties().apply { setProperty("colorX", "#FF0000") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            scheme.updateWith(Properties().apply { setProperty("foreground", "not-a-colour") })
        }
    }

    @Test
    fun `a session repaints through its own palette only`() {
        // The palette a session resets to is the one it owns, so reconfiguring it is a
        // per-connection decision (which is what a colour setting will need one day).
        val colors = TerminalColors()
        colors.scheme.updateWith(Properties().apply { setProperty("color1", "#00FF00") })
        colors.currentColors[1] = 0xFF123456.toInt()
        colors.reset(1)
        assertEquals(0xFF00FF00.toInt(), colors.currentColors[1])
        assertEquals(0xffcd0000.toInt(), TerminalColors().currentColors[1], "a fresh session is unaffected")
    }
}
