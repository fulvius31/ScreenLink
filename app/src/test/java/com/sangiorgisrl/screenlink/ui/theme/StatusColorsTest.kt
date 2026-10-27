package com.sangiorgisrl.screenlink.ui.theme

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the semantic status tones (active / connecting / idle) against the real
 * Material surfaces they are painted on.
 *
 * A status dot is a meaningful graphic, so Material 3 / WCAG 2.5.8 require 3:1
 * contrast. The surfaces below are read from the app's own light and dark
 * schemes, so the guarantee cannot silently rot when a token is retuned.
 * (On Android 12+ dynamic colour swaps the containers for equivalent tonal
 * steps; the tones themselves come from the Material palette - green 40/80,
 * amber 40/80, neutral outline - which is what keeps the ratio on those too.)
 */
class StatusColorsTest {

    private val lightSurfaces = listOf(
        "surface" to LightColors.surface,
        "surfaceContainerHigh" to LightColors.surfaceContainerHigh,
        "primaryContainer" to LightColors.primaryContainer,
        "secondaryContainer" to LightColors.secondaryContainer,
    )

    private val darkSurfaces = listOf(
        "surface" to DarkColors.surface,
        "surfaceContainerHigh" to DarkColors.surfaceContainerHigh,
        "primaryContainer" to DarkColors.primaryContainer,
        "secondaryContainer" to DarkColors.secondaryContainer,
    )

    @Test
    fun `contrast math matches the WCAG reference values`() {
        assertTrue(Contrast.ratio(androidx.compose.ui.graphics.Color.Black,
            androidx.compose.ui.graphics.Color.White) > 20.9)
        assertTrue(Contrast.ratio(androidx.compose.ui.graphics.Color.White,
            androidx.compose.ui.graphics.Color.White) < 1.001)
    }

    @Test
    fun `light status tones keep 3 to 1 on every light surface`() {
        assertAll(LightStatusColors, lightSurfaces)
    }

    @Test
    fun `dark status tones keep 3 to 1 on every dark surface`() {
        assertAll(DarkStatusColors, darkSurfaces)
    }

    @Test
    fun `each state has its own hue signature`() {
        // Luminance alone cannot separate green from amber (they sit at almost the
        // same tone), so the palette is pinned by hue role instead: live is
        // green-dominant, connecting is warm, idle is neutral grey. Every place a
        // dot appears it is paired with text, so colour is never the only cue.
        for (colors in listOf(LightStatusColors, DarkStatusColors)) {
            assertTrue(colors.active.green > colors.active.red, "active must be green-dominant")
            assertTrue(colors.connecting.red > colors.connecting.blue, "connecting must be warm")
            fun spread(c: androidx.compose.ui.graphics.Color): Float {
                val max = maxOf(c.red, c.green, c.blue)
                val min = minOf(c.red, c.green, c.blue)
                return max - min
            }
            assertTrue(spread(colors.idle) < 0.1f, "idle must be neutral")
            assertTrue(spread(colors.active) > 0.2f, "active must be chromatic")
        }
    }

    private fun assertAll(colors: StatusColors, surfaces: List<Pair<String, androidx.compose.ui.graphics.Color>>) {
        val tones = listOf("active" to colors.active, "connecting" to colors.connecting, "idle" to colors.idle)
        for ((toneName, tone) in tones) {
            for ((surfaceName, surface) in surfaces) {
                val ratio = Contrast.ratio(tone, surface)
                assertTrue(
                    ratio >= Contrast.MIN_NON_TEXT_CONTRAST,
                    "$toneName on $surfaceName is only %.2f:1".format(ratio),
                )
            }
        }
    }
}
