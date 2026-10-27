package com.sangiorgisrl.screenlink.terminal.emulator

import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Cell widths.
 *
 * Getting these wrong is what makes a terminal look "shifted": a CJK glyph or an emoji
 * counted as one cell desynchronises the renderer from the cursor. The tables are data,
 * so the test also checks the invariant the binary search in [WcWidth.intable] needs -
 * sorted and non-overlapping ranges - which a table edit would otherwise silently break.
 */
class WcWidthTest {

    @Test
    fun `ordinary characters take one cell`() {
        assertEquals(1, WcWidth.width('a'.code))
        assertEquals(1, WcWidth.width('é'.code))
        assertEquals(1, WcWidth.width('€'.code))
        assertEquals(1, WcWidth.width('ß'.code))
    }

    @Test
    fun `wide east asian characters take two cells`() {
        assertEquals(2, WcWidth.width(0x6F22)) // 漢
        assertEquals(2, WcWidth.width('あ'.code), "kana is wide")
        assertEquals(2, WcWidth.width(0xFF01)) // fullwidth exclamation mark
        assertEquals(2, WcWidth.width(0x1F300)) // cyclone emoji
        assertEquals(2, WcWidth.width(0x20000)) // CJK extension B
    }

    @Test
    fun `control characters occupy no cell of their own`() {
        assertEquals(0, WcWidth.width(0))
        assertEquals(0, WcWidth.width(7)) // BEL
        assertEquals(0, WcWidth.width(13)) // CR
        assertEquals(0, WcWidth.width(0x7F)) // DEL
        assertEquals(0, WcWidth.width(0x9F)) // C1
    }

    @Test
    fun `combining marks and zero-width formatting characters take no cell`() {
        assertEquals(0, WcWidth.width(0x0301)) // combining acute accent
        assertEquals(0, WcWidth.width(0x200B)) // zero width space
        assertEquals(0, WcWidth.width(0x202D)) // bidi override
        assertEquals(0, WcWidth.width(0xFE0F)) // variation selector
    }

    @Test
    fun `an unassigned but printable code point still takes a cell`() {
        // The local change versus POSIX wcwidth: unprintable is 0, not -1, because the
        // cell exists even when the glyph does not.
        assertEquals(1, WcWidth.width(0x0378))
    }

    @Test
    fun `the char array overload combines a surrogate pair before measuring`() {
        val wide = Character.toChars(0x20000)
        assertEquals(2, wide.size)
        assertEquals(2, WcWidth.width(wide, 0))
        assertEquals(1, WcWidth.width(Character.toChars('a'.code), 0))
    }

    @Test
    fun `the width tables stay sorted and non-overlapping`() {
        // WcWidth.inTable() binary-searches the packed start/end pairs, so an unsorted or
        // overlapping edit to the tables would return wrong widths instead of failing loudly.
        for (name in listOf("ZERO_WIDTH", "WIDE_EASTASIAN")) {
            val table = privateTable(name)
            assertTrue(table.size % 2 == 0, "$name must hold start/end pairs, got ${table.size} values")
            assertTrue(table.size / 2 > 10, "$name looks truncated: ${table.size / 2} ranges")
            for (range in 1 until table.size / 2) {
                val previousEnd = table[2 * (range - 1) + 1]
                val start = table[2 * range]
                val end = table[2 * range + 1]
                assertTrue(end >= start, "$name range $range ${start}..${end} is inverted")
                assertTrue(
                    previousEnd < start,
                    "$name range $range ${start}..${end} must start after ${previousEnd}",
                )
            }
        }
    }

    @Test
    fun `every packed range boundary still measures the way the table says`() {
        // The tables are packed start/end pairs, so one dropped or shifted value moves a
        // boundary silently. Pin what all 440 ranges imply, end by end, through the public
        // API. expectedWidth() is a linear-scan oracle over the same data: it says nothing
        // about the binary search, only that the packed values are the ones intended.
        val zero = privateTable("ZERO_WIDTH")
        val wide = privateTable("WIDE_EASTASIAN")
        for ((name, table) in listOf("ZERO_WIDTH" to zero, "WIDE_EASTASIAN" to wide)) {
            for (range in 0 until table.size / 2) {
                val start = table[2 * range]
                val end = table[2 * range + 1]
                val expected = expectedWidth(start, zero, wide)
                assertEquals(expected, WcWidth.width(start), "$name range $range starts at $start")
                assertEquals(expectedWidth(end, zero, wide), WcWidth.width(end), "$name range $range ends at $end")
                // The cell outside a range must change the answer, unless the neighbouring
                // cell happens to need the same width for its own reason.
                if (expectedWidth(start - 1, zero, wide) != expected) {
                    assertNotEquals(expected, WcWidth.width(start - 1), "$name range $range must start at $start, not earlier")
                }
                if (expectedWidth(end + 1, zero, wide) != expected) {
                    assertNotEquals(expected, WcWidth.width(end + 1), "$name range $range must end at $end, not later")
                }
            }
        }
    }

    /** The width the packed tables imply, with the zero table winning where they overlap. */
    private fun expectedWidth(codePoint: Int, zero: IntArray, wide: IntArray): Int = when {
        codePoint == 0 || codePoint == 0x034F || codePoint == 0x2028 || codePoint == 0x2029 -> 0
        codePoint in 0x200B..0x200F || codePoint in 0x202A..0x202E || codePoint in 0x2060..0x2063 -> 0
        codePoint < 32 || codePoint in 0x007F until 0x00A0 -> 0
        covers(zero, codePoint) -> 0
        covers(wide, codePoint) -> 2
        else -> 1
    }

    private fun covers(table: IntArray, codePoint: Int): Boolean {
        for (range in 0 until table.size / 2) {
            if (codePoint in table[2 * range]..table[2 * range + 1]) return true
        }
        return false
    }

    @Test
    fun `the width tables are private data`() {
        // The tables are only an implementation detail: callers go through width(), which
        // is where the unprintable-is-zero rule lives.
        for (name in listOf("ZERO_WIDTH", "WIDE_EASTASIAN")) {
            assertTrue(Modifier.isPrivate(privateField(name).modifiers), "$name must stay private")
        }
    }

    /** The tables are private, so the invariant checks read them reflectively. */
    private fun privateTable(name: String): IntArray = privateField(name).get(WcWidth) as IntArray

    private fun privateField(name: String): java.lang.reflect.Field {
        val field = WcWidth::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field
    }
}
