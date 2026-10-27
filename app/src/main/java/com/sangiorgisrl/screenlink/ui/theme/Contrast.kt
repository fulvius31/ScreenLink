package com.sangiorgisrl.screenlink.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * WCAG 2.x relative luminance / contrast ratio.
 *
 * Pure math (no Android framework calls) so the unit-test suite can pin the
 * contrast of the semantic status colours against the Material surfaces they
 * are drawn on. Material 3 requires 3:1 for meaningful non-text elements
 * (a status dot conveys state, so it counts).
 */
object Contrast {

    /** M3 / WCAG 2.5.8 minimum contrast for meaningful graphics. */
    const val MIN_NON_TEXT_CONTRAST = 3.0

    /** WCAG AA minimum for body-size text (M3 secondary text must still clear it). */
    const val MIN_TEXT_CONTRAST = 4.5

    fun relativeLuminance(color: Color): Double {
        fun linearize(component: Float): Double {
            val c = component.toDouble()
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linearize(color.red) + 0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)
    }

    /** Contrast ratio in [1, 21]. */
    fun ratio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    fun meetsNonTextContrast(foreground: Color, background: Color): Boolean =
        ratio(foreground, background) >= MIN_NON_TEXT_CONTRAST

    /**
     * Result of painting [foreground] at [alpha] over [background] (normal blend,
     * the same compositing Compose does for `color.copy(alpha = ...)`). Used to
     * check that an emphasis alpha never drops text below AA.
     */
    fun composite(foreground: Color, alpha: Float, background: Color): Color {
        val a = alpha.toDouble()
        fun mix(f: Float, b: Float): Float = (a * f + (1 - a) * b).toFloat()
        return Color(
            mix(foreground.red, background.red),
            mix(foreground.green, background.green),
            mix(foreground.blue, background.blue),
        )
    }
}
