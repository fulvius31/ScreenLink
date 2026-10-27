package com.sangiorgisrl.screenlink.ui.adaptive

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Window-size breakpoints matching the Material 3 window size classes
 * (Compact < 600dp, Medium 600..840dp, Expanded >= 840dp wide; the height
 * classes mirror M3's Short/Medium/Tall bands). The arithmetic lives in pure
 * functions so the rules are unit-testable on the JVM, and the Compose helper
 * works from measured constraints (no Activity context needed - it also
 * follows multi-window resizes and foldables live).
 */
enum class WidthSize { Compact, Medium, Expanded }

/** Height bands: Short is the landscape-phone band where a full in-app
 *  keyboard would leave no room for the terminal itself. */
enum class HeightSize { Short, MediumHeight, Tall }

data class AdaptiveLayout(val widthSize: WidthSize, val heightSize: HeightSize) {

    /** Wide enough to fit line length and/or use multi-column grids (tablet, landscape). */
    val isWide: Boolean get() = widthSize != WidthSize.Compact

    /** Short enough that tall in-app chrome (the terminal keyboard) must go compact. */
    val isShort: Boolean get() = heightSize == HeightSize.Short

    companion object {
        // M3 window-size-class breakpoints (dp).
        const val WIDTH_MEDIUM = 600.0
        const val WIDTH_EXPANDED = 840.0
        const val HEIGHT_SHORT = 480.0
        const val HEIGHT_TALL = 900.0

        fun widthSizeOf(widthDp: Double): WidthSize = when {
            widthDp >= WIDTH_EXPANDED -> WidthSize.Expanded
            widthDp >= WIDTH_MEDIUM -> WidthSize.Medium
            else -> WidthSize.Compact
        }

        fun heightSizeOf(heightDp: Double): HeightSize = when {
            heightDp >= HEIGHT_TALL -> HeightSize.Tall
            heightDp >= HEIGHT_SHORT -> HeightSize.MediumHeight
            else -> HeightSize.Short
        }

        fun from(widthDp: Double, heightDp: Double): AdaptiveLayout =
            AdaptiveLayout(widthSizeOf(widthDp), heightSizeOf(heightDp))
    }
}

/**
 * Wraps screen content and hands the screen both the measured [AdaptiveLayout]
 * and a modifier that fits the content into a readable column on wide windows
 * (centered, at most [maxContentWidth] wide) while staying full-bleed on phones.
 */
@Composable
fun AdaptiveContent(
    modifier: Modifier = Modifier,
    maxContentWidth: Dp = 720.dp,
    content: @Composable (layout: AdaptiveLayout, contentModifier: Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val layout = remember(maxWidth, maxHeight) {
            AdaptiveLayout.from(maxWidth.value.toDouble(), maxHeight.value.toDouble())
        }
        val contentModifier = if (layout.isWide) {
            Modifier
                .fillMaxSize()
                .widthIn(max = maxContentWidth)
                .align(Alignment.TopCenter)
        } else {
            Modifier.fillMaxSize()
        }
        content(layout, contentModifier)
    }
}
