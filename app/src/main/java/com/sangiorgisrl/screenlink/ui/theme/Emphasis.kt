package com.sangiorgisrl.screenlink.ui.theme

/**
 * Material 3 content emphasis.
 *
 * M3 expresses secondary / supporting text on a coloured container by lowering
 * the alpha of the matching "on <container>" colour (Material3 dropped the old
 * M2 `ContentAlpha` helper, so the alpha is applied by hand). The value is a
 * named token instead of a magic number because the result must still clear
 * WCAG AA for body text: EmphasisTest pins it against the app's real
 * light/dark containers, so retuning a scheme cannot quietly push supporting
 * text under 4.5:1.
 */
object Emphasis {

    /** Medium emphasis: supporting text sitting on a filled container. */
    const val MEDIUM_ALPHA = 0.75f
}
