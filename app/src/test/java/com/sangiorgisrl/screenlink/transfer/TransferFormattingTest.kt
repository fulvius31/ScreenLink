package com.sangiorgisrl.screenlink.transfer

import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The numbers a user reads while a file is moving.
 *
 * Every call passes an explicit locale: the app has its own language picker that
 * does not necessarily move `Locale.getDefault()`, so the formatting has to follow
 * the language the UI is drawn in. These tests pin both the unit steps and that.
 */
class TransferFormattingTest {

    private val us = Locale.US

    @Test
    fun `sizes step at 1024 and keep one decimal below 100`() {
        assertEquals("0 B", TransferFormatting.bytes(0L, us))
        assertEquals("977 B", TransferFormatting.bytes(977L, us))
        assertEquals("1023 B", TransferFormatting.bytes(1023L, us))
        // The step boundary: 1024 is already KB, not "1024 B".
        assertEquals("1.0 KB", TransferFormatting.bytes(1024L, us))
        assertEquals("1.5 KB", TransferFormatting.bytes(1536L, us))
        assertEquals("1.0 MB", TransferFormatting.bytes(1L shl 20, us))
        // At/above 100 the decimal is noise.
        assertEquals("100 MB", TransferFormatting.bytes(100L shl 20, us))
        assertEquals("1.0 GB", TransferFormatting.bytes(1L shl 30, us))
        assertEquals("2.0 TB", TransferFormatting.bytes(2L shl 40, us))
        assertEquals("?", TransferFormatting.bytes(-1L, us))
    }

    @Test
    fun `numbers follow the locale that was passed, not the process default`() {
        assertEquals("1.5 MB", TransferFormatting.bytes(1572864L, Locale.US))
        assertEquals("1,5 MB", TransferFormatting.bytes(1572864L, Locale.GERMANY))
    }

    @Test
    fun `rate is a size plus the per-second suffix`() {
        assertEquals("2.0 MB/s", TransferFormatting.rate(2.0 * 1024 * 1024, us))
        assertEquals("0 B/s", TransferFormatting.rate(0.0, us))
    }

    @Test
    fun `remaining time is coarse and readable`() {
        assertEquals("0 s", TransferFormatting.duration(0.0, us))
        assertEquals("59 s", TransferFormatting.duration(59.9, us))
        assertEquals("1 min", TransferFormatting.duration(60.0, us))
        assertEquals("4 min", TransferFormatting.duration(240.0, us))
        assertEquals("1 min 30 s", TransferFormatting.duration(90.0, us))
        assertEquals("1 h 00 min", TransferFormatting.duration(3600.0, us))
        assertEquals("1 h 30 min", TransferFormatting.duration(5400.0, us))
    }

    @Test
    fun `an unmeasurable time left shows the unknown marker instead of a fake number`() {
        assertEquals(TransferFormatting.UNKNOWN, TransferFormatting.duration(null, us))
        assertEquals(TransferFormatting.UNKNOWN, TransferFormatting.duration(-1.0, us))
        assertEquals(TransferFormatting.UNKNOWN, TransferFormatting.duration(Double.NaN, us))
        assertEquals(TransferFormatting.UNKNOWN, TransferFormatting.duration(Double.POSITIVE_INFINITY, us))
    }

    @Test
    fun `percent is clamped and null when there is no total`() {
        assertEquals(0, TransferFormatting.percent(0L, 100L))
        assertEquals(50, TransferFormatting.percent(50L, 100L))
        // A server that reports more than the expected size must not read "101%".
        assertEquals(100, TransferFormatting.percent(101L, 100L))
        assertNull(TransferFormatting.percent(10L, 0L))
        assertNull(TransferFormatting.percent(10L, -1L))
        assertNull(TransferFormatting.percent(-1L, 100L))
    }

    @Test
    fun `fraction drives a determinate bar and null means indeterminate`() {
        assertEquals(0f, TransferFormatting.fraction(0L, 10L))
        assertEquals(0.25f, TransferFormatting.fraction(1L, 4L))
        assertEquals(1f, TransferFormatting.fraction(9L, 4L))
        assertNull(TransferFormatting.fraction(1L, -1L))
    }
}
