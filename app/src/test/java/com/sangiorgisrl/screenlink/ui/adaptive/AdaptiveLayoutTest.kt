package com.sangiorgisrl.screenlink.ui.adaptive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the adaptive breakpoints to the M3 window-size-class bands and checks the
 * real device shapes the app must support: portrait phone, landscape phone,
 * tablet portrait, tablet landscape.
 */
class AdaptiveLayoutTest {

    @Test
    fun `width breakpoints match the M3 bands`() {
        assertEquals(WidthSize.Compact, AdaptiveLayout.widthSizeOf(0.0))
        assertEquals(WidthSize.Compact, AdaptiveLayout.widthSizeOf(599.9))
        assertEquals(WidthSize.Medium, AdaptiveLayout.widthSizeOf(600.0))
        assertEquals(WidthSize.Medium, AdaptiveLayout.widthSizeOf(839.9))
        assertEquals(WidthSize.Expanded, AdaptiveLayout.widthSizeOf(840.0))
        assertEquals(WidthSize.Expanded, AdaptiveLayout.widthSizeOf(1600.0))
    }

    @Test
    fun `height breakpoints match the M3 bands`() {
        assertEquals(HeightSize.Short, AdaptiveLayout.heightSizeOf(0.0))
        assertEquals(HeightSize.Short, AdaptiveLayout.heightSizeOf(479.9))
        assertEquals(HeightSize.MediumHeight, AdaptiveLayout.heightSizeOf(480.0))
        assertEquals(HeightSize.MediumHeight, AdaptiveLayout.heightSizeOf(899.9))
        assertEquals(HeightSize.Tall, AdaptiveLayout.heightSizeOf(900.0))
    }

    @Test
    fun `portrait phone is compact width and not short`() {
        val layout = AdaptiveLayout.from(411.0, 914.0)
        assertEquals(WidthSize.Compact, layout.widthSize)
        assertEquals(HeightSize.Tall, layout.heightSize)
        assertFalse(layout.isWide)
        assertFalse(layout.isShort)
    }

    @Test
    fun `landscape phone is wide but short - keyboard must go compact`() {
        val layout = AdaptiveLayout.from(914.0, 411.0)
        assertEquals(WidthSize.Expanded, layout.widthSize)
        assertEquals(HeightSize.Short, layout.heightSize)
        assertTrue(layout.isWide)
        assertTrue(layout.isShort)
    }

    @Test
    fun `tablet portrait is medium width and tall`() {
        val layout = AdaptiveLayout.from(800.0, 1280.0)
        assertEquals(WidthSize.Medium, layout.widthSize)
        assertEquals(HeightSize.Tall, layout.heightSize)
        assertTrue(layout.isWide)
        assertFalse(layout.isShort)
    }

    @Test
    fun `tablet landscape is expanded width and not short`() {
        val layout = AdaptiveLayout.from(1280.0, 800.0)
        assertEquals(WidthSize.Expanded, layout.widthSize)
        assertEquals(HeightSize.MediumHeight, layout.heightSize)
        assertTrue(layout.isWide)
        assertFalse(layout.isShort)
    }
}
