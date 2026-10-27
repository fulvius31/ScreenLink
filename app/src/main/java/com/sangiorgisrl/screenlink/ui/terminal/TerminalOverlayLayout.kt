package com.sangiorgisrl.screenlink.ui.terminal

/**
 * Size rules for the terminal's status strip - the row of controls between the pty
 * canvas and the keyboard (GNU Screen history toggle, caption, live status).
 *
 * The strip is in the layout rather than floating over the canvas. Floating allowed
 * three different collisions, all of them reported on real devices: the chip and the
 * live pill landing on each other (the Spanish labels are wider than the English
 * ones), the caption running under the pill, and - in a window shorter than the strip
 * itself - the whole row being drawn on top of the tab strip above the canvas,
 * because a Box does not clip its children. In flow the controls cannot overlap
 * anything: the canvas simply keeps the rows that are left.
 *
 * The arithmetic lives here as pure functions so the rules are unit-testable on the
 * JVM; the composable only applies them.
 */
object TerminalOverlayLayout {

    /**
     * Narrowest window (dp) that can carry the caption beside the controls. The chip
     * plus the live pill already need roughly 300 dp once their padding is counted,
     * so below this there is nothing left for a sentence.
     */
    const val MIN_WIDTH_FOR_HINT_DP: Double = 480.0

    /** Tallest control in the full strip: the Live pill (20 dp label + 2 x 14 dp). */
    const val LIVE_PILL_HEIGHT_DP: Double = 48.0

    /** Tallest control in the compact strip: the icon-only Live pill (18 + 2 x 11). */
    const val LIVE_PILL_COMPACT_HEIGHT_DP: Double = 40.0

    /** Strip padding, full and compact. */
    const val ROW_PADDING_DP: Double = 12.0
    const val ROW_PADDING_COMPACT_DP: Double = 6.0

    /**
     * Short window (landscape phone): the strip goes icon-only and tighter, because
     * every dp of it is a dp taken from a canvas that is only a handful of rows tall.
     */
    @JvmStatic
    fun compactStrip(shortHeight: Boolean): Boolean = shortHeight

    /**
     * Height the strip takes from the canvas. Pinned here so a change to the
     * controls has to be a deliberate one (the tests below assert it is tall enough
     * for the tallest control it holds).
     */
    @JvmStatic
    fun stripHeightDp(shortHeight: Boolean): Double =
        if (shortHeight) {
            LIVE_PILL_COMPACT_HEIGHT_DP + 2 * ROW_PADDING_COMPACT_DP
        } else {
            LIVE_PILL_HEIGHT_DP + 2 * ROW_PADDING_DP
        }

    /** Whether the history caption shows (it needs room beside both controls). */
    @JvmStatic
    fun showHistoryHint(widthDp: Double, shortHeight: Boolean): Boolean =
        !shortHeight && widthDp >= MIN_WIDTH_FOR_HINT_DP

    /**
     * Narrowest window (dp, at the user's own font scale) that can carry the
     * history chip's TEXT label beside the live control.
     *
     * The chip and the live control are placed in a Row, so they can never land on
     * top of each other - but a label that does not fit makes the chip wrap to a
     * second line, which grows the strip and takes rows off the canvas. The Spanish
     * labels are ~40% wider than the English ones ("Historial activo" vs "History
     * on") and the user's font scale multiplies them again, so the window width is
     * compared in text-dp: what the layout has, divided by the scale the text is
     * drawn at. Below this the chip goes icon-only, which is the same thing a Short
     * window does.
     */
    const val MIN_WIDTH_FOR_CHIP_LABEL_DP: Double = 340.0

    /**
     * Whether the history chip keeps its text label. [fontScale] is the system font
     * scale (1.0 default); a large-text user runs out of room sooner.
     */
    @JvmStatic
    fun showHistoryLabel(widthDp: Double, fontScale: Float, shortHeight: Boolean): Boolean =
        !shortHeight && widthDp / fontScale.toDouble() >= MIN_WIDTH_FOR_CHIP_LABEL_DP
}
