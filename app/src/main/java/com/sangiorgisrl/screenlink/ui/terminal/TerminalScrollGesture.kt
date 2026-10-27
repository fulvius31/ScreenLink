package com.sangiorgisrl.screenlink.ui.terminal

/** A row-quantised scroll step plus the pixels carried over to the next event. */
data class ScrollStep(val rowsBack: Int, val remainderPx: Float)

/** The travel limits of a momentum fling, in pixels of scrollback. */
data class FlingBounds(val minPx: Int, val maxPx: Int)

/**
 * Direction and quantisation maths for scrolling the terminal with a finger.
 *
 * Kept pure (and unit tested) because the sign conventions here are the part that
 * keeps breaking: [android.view.GestureDetector] reports
 * `distance = previous - current`, so a finger dragged DOWN gives a NEGATIVE
 * distanceY, while a mouse wheel rolled up gives a POSITIVE AXIS_VSCROLL. Both mean
 * "pull older content into view", and every caller of [TerminalScrollRouter] uses the
 * single convention below:
 *
 *     rowsBack > 0  ->  back in time (older content, transcript)
 *     rowsBack < 0  ->  forward (toward the live prompt)
 *
 * Touch scrolling follows the platform convention that content follows the finger:
 * drag DOWN to read older output, drag UP to come back to the live prompt.
 */
object TerminalScrollGesture {

    /** Rows moved by one mouse-wheel / touchpad notch. */
    const val WHEEL_ROWS: Int = 3

    /**
     * Damping applied to the pointer velocity when starting a momentum fling. A text
     * row is much taller than a list row, so an undamped fling pages far too far.
     */
    const val FLING_VELOCITY_SCALE: Float = 0.5f

    /**
     * Convert one [android.view.GestureDetector.OnGestureListener.onScroll] delta into
     * whole rows, carrying the leftover pixels in [remainderPx] so slow drags never
     * lose distance.
     */
    fun dragStep(distanceY: Float, lineSpacingPx: Float, remainderPx: Float): ScrollStep {
        require(lineSpacingPx > 0f) { "lineSpacingPx must be positive" }
        val travelledPx = -distanceY + remainderPx
        val rows = (travelledPx / lineSpacingPx).toInt()
        return ScrollStep(rows, travelledPx - rows * lineSpacingPx)
    }

    /**
     * A mouse wheel / touchpad: a positive AXIS_VSCROLL is rolled up = back in time.
     * A zero axis value means "no scroll" and must not move the viewport.
     */
    fun wheelRows(axisVScroll: Float): Int = when {
        axisVScroll > 0f -> WHEEL_ROWS
        axisVScroll < 0f -> -WHEEL_ROWS
        else -> 0
    }

    /**
     * Pointer velocity (px/s, positive = finger moving down) to the velocity of the
     * fling scroller, whose axis is "pixels of scrollback travelled" (positive =
     * older). A downward flick therefore pages back through history.
     */
    fun flingVelocityPx(velocityY: Float): Int = (velocityY * FLING_VELOCITY_SCALE).toInt()

    /**
     * How far a fling started at [topRow] may travel, in pixels of scrollback.
     * [topRow] is the terminal viewport (0 = live, negative = scrolled back).
     */
    fun flingBounds(topRow: Int, transcriptRows: Int, lineSpacingPx: Int): FlingBounds {
        val forwardPx = topRow * lineSpacingPx // topRow <= 0: how far back toward live
        val backPx = (transcriptRows + topRow) * lineSpacingPx
        return FlingBounds(forwardPx.coerceAtMost(backPx), backPx.coerceAtLeast(forwardPx))
    }
}
