package com.sangiorgisrl.screenlink.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Supporting text on a filled container is drawn as the "on <container>" colour
 * at an emphasis alpha. Material 3 allows that, but the composited result is
 * still text, so it has to clear WCAG AA (4.5:1). These cases read the app's
 * own light and dark schemes, so retuning a colour scheme cannot quietly push
 * secondary text under the bar.
 */
class EmphasisTest {

    private val pairs = listOf(
        "light onPrimaryContainer/primaryContainer" to
            (LightColors.onPrimaryContainer to LightColors.primaryContainer),
        "light onSecondaryContainer/secondaryContainer" to
            (LightColors.onSecondaryContainer to LightColors.secondaryContainer),
        "dark onPrimaryContainer/primaryContainer" to
            (DarkColors.onPrimaryContainer to DarkColors.primaryContainer),
        "dark onSecondaryContainer/secondaryContainer" to
            (DarkColors.onSecondaryContainer to DarkColors.secondaryContainer),
    )

    @Test
    fun `medium emphasis text still clears WCAG AA on every container`() {
        for (entry in pairs) {
            val name = entry.first
            val fg = entry.second.first
            val bg = entry.second.second
            val ratio = Contrast.ratio(
                Contrast.composite(fg, Emphasis.MEDIUM_ALPHA, bg),
                bg,
            )
            assertTrue(
                ratio >= Contrast.MIN_TEXT_CONTRAST,
                "$name at emphasis alpha is only %.2f:1".format(ratio),
            )
        }
    }

    @Test
    fun `onSurfaceVariant is already the medium-emphasis colour so it is never dimmed`() {
        // Material 3 hands you onSurfaceVariant as the "supporting text on a
        // surface" role, so it clears AA on its own; putting an emphasis alpha on
        // top of it is the mistake this guard exists to catch.
        for (scheme in listOf(LightColors, DarkColors)) {
            for (bg in listOf(scheme.surface, scheme.surfaceContainerHigh, scheme.surfaceVariant)) {
                val ratio = Contrast.ratio(scheme.onSurfaceVariant, bg)
                assertTrue(
                    ratio >= Contrast.MIN_TEXT_CONTRAST,
                    "onSurfaceVariant on ${bg} is only %.2f:1".format(ratio),
                )
            }
        }
    }

    @Test
    fun `emphasis only dims, it never brightens`() {
        val fg = Color(0xFF001A41)
        val bg = Color(0xFFD8E2FF)
        assertTrue(Contrast.relativeLuminance(Contrast.composite(fg, 0.75f, bg)) >
            Contrast.relativeLuminance(fg))
        // alpha 1 is the plain foreground, alpha 0 is the plain background.
        assertTrue(Contrast.ratio(Contrast.composite(fg, 1f, bg), bg) >
            Contrast.ratio(Contrast.composite(fg, 0.5f, bg), bg))
    }
}
