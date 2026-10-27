package com.sangiorgisrl.screenlink.ui.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the sign conventions of finger scrolling - the part that kept breaking.
 *
 * android.view.GestureDetector reports distance = previous - current, so a finger
 * dragged DOWN yields a NEGATIVE distanceY. Content follows the finger, therefore a
 * downward drag must pull OLDER content into view, i.e. rowsBack > 0.
 */
class TerminalScrollGestureTest {

    private val lineHeight = 20f

    @Test
    fun `finger dragged down scrolls back in time`() {
        val step = TerminalScrollGesture.dragStep(distanceY = -25f, lineSpacingPx = lineHeight, remainderPx = 0f)
        assertEquals(1, step.rowsBack, "dragging down must page to older content")
        assertEquals(5f, step.remainderPx, 0f)
    }

    @Test
    fun `finger dragged up returns toward the live prompt`() {
        val step = TerminalScrollGesture.dragStep(distanceY = 25f, lineSpacingPx = lineHeight, remainderPx = 0f)
        assertEquals(-1, step.rowsBack, "dragging up must move toward live")
        assertEquals(-5f, step.remainderPx, 0f)
    }

    @Test
    fun `sub-row pixels carry over instead of being dropped`() {
        // Two slow 15px events on a 20px line: nothing moves on the first, the
        // second must still produce a row because the pixels were carried.
        val first = TerminalScrollGesture.dragStep(-15f, lineHeight, 0f)
        assertEquals(0, first.rowsBack)
        assertEquals(15f, first.remainderPx, 0f)

        val second = TerminalScrollGesture.dragStep(-10f, lineHeight, first.remainderPx)
        assertEquals(1, second.rowsBack)
        assertEquals(5f, second.remainderPx, 0f)
    }

    @Test
    fun `a fast drag moves several rows at once`() {
        val step = TerminalScrollGesture.dragStep(-95f, lineHeight, 0f)
        assertEquals(4, step.rowsBack)
        assertEquals(15f, step.remainderPx, 0f)
    }

    @Test
    fun `remainder never grows to a full row`() {
        var remainder = 0f
        for (i in 0 until 50) {
            val step = TerminalScrollGesture.dragStep(-3f, lineHeight, remainder)
            remainder = step.remainderPx
            assertTrue(remainder < lineHeight, "remainder leaked a row: $remainder")
        }
    }

    @Test
    fun `mouse wheel rolls up pages back and rolls down pages forward`() {
        assertEquals(TerminalScrollGesture.WHEEL_ROWS, TerminalScrollGesture.wheelRows(1f))
        assertEquals(-TerminalScrollGesture.WHEEL_ROWS, TerminalScrollGesture.wheelRows(-1f))
        assertEquals(0, TerminalScrollGesture.wheelRows(0f), "a no-op wheel must not scroll")
    }

    @Test
    fun `fling velocity keeps the drag direction and is damped`() {
        // velocityY > 0 = finger moving down = same direction as a downward drag.
        assertTrue(TerminalScrollGesture.flingVelocityPx(4000f) > 0, "downward flick must keep paging back")
        assertEquals(2000, TerminalScrollGesture.flingVelocityPx(4000f))
        assertEquals(-2000, TerminalScrollGesture.flingVelocityPx(-4000f))
        assertTrue(
            TerminalScrollGesture.flingVelocityPx(4000f) < 4000,
            "an undamped fling pages far too far for a text row",
        )
    }

    @Test
    fun `fling bounds allow the travel still available from the current position`() {
        // 5 rows back of a 100-row transcript: 5 rows of travel toward live, 95 away.
        val bounds = TerminalScrollGesture.flingBounds(topRow = -5, transcriptRows = 100, lineSpacingPx = 20)
        assertEquals(-100, bounds.minPx)
        assertEquals(1900, bounds.maxPx)

        // Fully scrolled back: only forward travel is available.
        val atBottom = TerminalScrollGesture.flingBounds(topRow = -100, transcriptRows = 100, lineSpacingPx = 20)
        assertEquals(-2000, atBottom.minPx)
        assertEquals(0, atBottom.maxPx)

        // Degenerate: nothing to scroll, so the scroller must not move at all.
        val none = TerminalScrollGesture.flingBounds(topRow = 0, transcriptRows = 0, lineSpacingPx = 20)
        assertEquals(0, none.minPx)
        assertEquals(0, none.maxPx)
    }

    @Test
    fun `fling bounds stay ordered for any viewport`() {
        listOf(0 to 0, 0 to 50, -50 to 50, -10 to 500, -1 to 1).forEach { (topRow, transcript) ->
            val b = TerminalScrollGesture.flingBounds(topRow, transcript, 20)
            assertTrue(b.minPx <= b.maxPx, "inverted bounds for topRow=$topRow transcript=$transcript")
            assertTrue(b.minPx <= 0 && b.maxPx >= 0, "the fling must start inside its bounds")
        }
    }
}
