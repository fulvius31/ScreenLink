package com.sangiorgisrl.screenlink.ui.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the scroll-mode semantics:
 *  - mouse reporting always wins (wheels forwarded verbatim);
 *  - touch NEVER produces arrows unless the app explicitly requested alternate scroll;
 *  - wheel inside an alternate-screen app keeps xterm alt-scroll (arrows);
 *  - everything else scrolls the transcript.
 */
class TerminalScrollRouterTest {

    private fun route(
        mouseTracking: Boolean = false,
        altBuffer: Boolean = false,
        altScroll: Boolean = false,
        fromTouch: Boolean = false,
        up: Boolean = true,
    ) = TerminalScrollRouter.route(mouseTracking, altBuffer, altScroll, fromTouch, up)

    @Test
    fun `mouse tracking always forwards wheels`() {
        assertEquals(TerminalScrollAction.WHEEL_UP, route(mouseTracking = true, up = true))
        assertEquals(TerminalScrollAction.WHEEL_DOWN, route(mouseTracking = true, up = false))
        // Even in the alternate buffer and even from touch, tracking wins.
        assertEquals(TerminalScrollAction.WHEEL_UP, route(mouseTracking = true, altBuffer = true, fromTouch = true, up = true))
    }

    @Test
    fun `touch inside GNU Screen around bash scrolls the transcript and never arrows`() {
        // GNU Screen engages the alt buffer (1049h) but never sends 1007h:
        // a finger swipe must NOT become arrow keys (that recalls shell history).
        assertEquals(TerminalScrollAction.SCROLL_BACK, route(altBuffer = true, fromTouch = true, up = true))
        assertEquals(TerminalScrollAction.SCROLL_FORWARD, route(altBuffer = true, fromTouch = true, up = false))
    }

    @Test
    fun `touch inside an app that requested alternate scroll becomes arrows`() {
        // A text user interface asks for xterm alternate scroll (ESC[?1007h), which makes
        // touch scrolls page the app.
        assertEquals(TerminalScrollAction.ARROW_UP, route(altBuffer = true, altScroll = true, fromTouch = true, up = true))
        assertEquals(TerminalScrollAction.ARROW_DOWN, route(altBuffer = true, altScroll = true, fromTouch = true, up = false))
    }

    @Test
    fun `touch on the main buffer scrolls the transcript`() {
        assertEquals(TerminalScrollAction.SCROLL_BACK, route(fromTouch = true, up = true))
        assertEquals(TerminalScrollAction.SCROLL_FORWARD, route(fromTouch = true, up = false))
    }

    @Test
    fun `wheel inside an alternate-screen app uses xterm alt-scroll arrows`() {
        // less/htop/vim style: a real wheel in the alt screen pages the app.
        assertEquals(TerminalScrollAction.ARROW_UP, route(altBuffer = true, up = true))
        assertEquals(TerminalScrollAction.ARROW_DOWN, route(altBuffer = true, up = false))
        // Even without an explicit 1007h (legacy pagers don't send it).
        assertEquals(TerminalScrollAction.ARROW_UP, route(altBuffer = true, altScroll = false, up = true))
    }

    @Test
    fun `wheel on the main buffer scrolls the transcript`() {
        assertEquals(TerminalScrollAction.SCROLL_BACK, route(up = true))
        assertEquals(TerminalScrollAction.SCROLL_FORWARD, route(up = false))
    }

    @Test
    fun `alt screen with 1007 and a wheel still pages the app`() {
        assertEquals(TerminalScrollAction.ARROW_UP, route(altBuffer = true, altScroll = true, up = true))
    }

    // ---- gestures (gestureUsesScreenCopy) ---------------------------------
    // A drag must agree with the buttons: two scroll affordances driving two different
    // scrollbacks is the confusion this routing exists to remove.

    @Test
    fun `a drag inside GNU Screen drives copy mode and opens it on demand`() {
        assertTrue(TerminalScrollRouter.gestureUsesScreenCopy(false, true, false))
        assertTrue(TerminalScrollRouter.gestureUsesScreenCopy(true, true, false))
    }

    @Test
    fun `a drag inside GNU Screen still forwards wheels when the app wants the pointer`() {
        assertFalse(TerminalScrollRouter.gestureUsesScreenCopy(false, true, true))
        // ... unless copy mode was already opened by the user, which stays authoritative.
        assertTrue(TerminalScrollRouter.gestureUsesScreenCopy(true, true, true))
    }

    @Test
    fun `a drag in a plain shell never touches copy mode`() {
        assertFalse(TerminalScrollRouter.gestureUsesScreenCopy(false, false, false))
        assertFalse(TerminalScrollRouter.gestureUsesScreenCopy(false, false, true))
    }
}
