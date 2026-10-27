package com.sangiorgisrl.screenlink.terminal.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The 64-bit cell style word.
 *
 * Every cell on screen stores one of these longs, so a bit that leaks into the neighbour
 * field shows up as wrong colours rather than a crash. The tests pin the packing:
 * 16 flag bits (11 used), then 24 foreground bits, then 24 background bits, with the
 * truecolour flags deciding whether the colour fields hold an index or an RGB triple.
 */
class TextStyleTest {

    @Test
    fun `the indexed palette has 256 colours plus the three special slots`() {
        assertEquals(256, TextStyle.COLOR_INDEX_FOREGROUND)
        assertEquals(257, TextStyle.COLOR_INDEX_BACKGROUND)
        assertEquals(258, TextStyle.COLOR_INDEX_CURSOR)
        assertEquals(259, TextStyle.NUM_INDEXED_COLORS)
    }

    @Test
    fun `normal style decodes back to the special foreground and background slots`() {
        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.decodeForeColor(TextStyle.NORMAL))
        assertEquals(TextStyle.COLOR_INDEX_BACKGROUND, TextStyle.decodeBackColor(TextStyle.NORMAL))
        assertEquals(0, TextStyle.decodeEffect(TextStyle.NORMAL))
    }

    @Test
    fun `indexed colours survive a round trip`() {
        for (fore in listOf(0, 1, 9, 16, 128, 255, TextStyle.COLOR_INDEX_CURSOR)) {
            for (back in listOf(0, 7, 250, TextStyle.COLOR_INDEX_BACKGROUND)) {
                val style = TextStyle.encode(fore, back, 0)
                assertEquals(fore, TextStyle.decodeForeColor(style), "foreground $fore")
                assertEquals(back, TextStyle.decodeBackColor(style), "background $back")
            }
        }
    }

    @Test
    fun `effects survive a round trip`() {
        val effects =
            listOf(
                TextStyle.CHARACTER_ATTRIBUTE_BOLD,
                TextStyle.CHARACTER_ATTRIBUTE_ITALIC,
                TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE,
                TextStyle.CHARACTER_ATTRIBUTE_BLINK,
                TextStyle.CHARACTER_ATTRIBUTE_INVERSE,
                TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE,
                TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH,
                TextStyle.CHARACTER_ATTRIBUTE_PROTECTED,
                TextStyle.CHARACTER_ATTRIBUTE_DIM,
            )
        for (effect in effects) {
            val style = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.COLOR_INDEX_BACKGROUND, effect)
            assertEquals(effect, TextStyle.decodeEffect(style) and effect)
            assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.decodeForeColor(style))
            assertEquals(TextStyle.COLOR_INDEX_BACKGROUND, TextStyle.decodeBackColor(style))
        }
        val combined = TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
        val style = TextStyle.encode(1, 2, combined)
        assertEquals(combined, TextStyle.decodeEffect(style) and combined)
    }

    @Test
    fun `an opaque rgb colour is stored as truecolour and decodes exactly`() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF102030.toInt()
        val style = TextStyle.encode(red, blue, 0)
        assertEquals(red, TextStyle.decodeForeColor(style))
        assertEquals(blue, TextStyle.decodeBackColor(style))
        val effect = TextStyle.decodeEffect(style)
        assertTrue(effect and (1 shl 9) != 0, "foreground truecolour flag must be set")
        assertTrue(effect and (1 shl 10) != 0, "background truecolour flag must be set")
    }

    @Test
    fun `truecolour and indexed slots do not collide`() {
        // Index 1 (red in the default palette) and truecolour red are different styles:
        // the renderer picks the palette entry for one and the literal rgb for the other.
        val indexed = TextStyle.encode(1, TextStyle.COLOR_INDEX_BACKGROUND, 0)
        val trueColour = TextStyle.encode(0xFFFF0000.toInt(), TextStyle.COLOR_INDEX_BACKGROUND, 0)
        assertNotEquals(indexed, trueColour)
        assertEquals(1, TextStyle.decodeForeColor(indexed))
        assertEquals(0xFFFF0000.toInt(), TextStyle.decodeForeColor(trueColour))
    }

    @Test
    fun `only the low nine effect bits are packed so the truecolour flags stay free`() {
        // encode() masks the effect argument; without that mask a stray bit 9/10 here
        // would silently turn an indexed colour into truecolour garbage.
        val style = TextStyle.encode(3, 4, 1 shl 9)
        assertEquals(0, TextStyle.decodeEffect(style) and (1 shl 9))
        assertEquals(3, TextStyle.decodeForeColor(style))
        assertEquals(4, TextStyle.decodeBackColor(style))
    }

    @Test
    fun `foreground and background live in separate fields`() {
        val style = TextStyle.encode(0xFFFF00FF.toInt(), 0xFF00FF00.toInt(), 0)
        assertEquals(0xFFFF00FF.toInt(), TextStyle.decodeForeColor(style))
        assertEquals(0xFF00FF00.toInt(), TextStyle.decodeBackColor(style))
    }
}
