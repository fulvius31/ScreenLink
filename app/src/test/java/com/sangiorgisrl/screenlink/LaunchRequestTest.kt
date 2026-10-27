package com.sangiorgisrl.screenlink

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which screen an incoming Intent is allowed to put in front of the user.
 *
 * The bug this pins: the Activity decided "this Intent carries no files, so a share
 * must have arrived empty" and opened the Files screen - for EVERY Intent, including
 * the launcher tap. The copy screen then presented itself as the app's home screen.
 * The action is what says whether an Intent is a share at all, so the action is what
 * is checked here.
 *
 * The action strings are written out literally on purpose: they are the values on the
 * wire between apps, and a test that only compares the constants would keep passing
 * if a constant were swapped.
 */
class LaunchRequestTest {

    private val main = "android.intent.action.MAIN"
    private val send = "android.intent.action.SEND"
    private val sendMultiple = "android.intent.action.SEND_MULTIPLE"
    private val view = "android.intent.action.VIEW"

    // --- the regression: a launcher tap is not a share -----------------------

    @Test
    fun `a launcher tap opens the app on Home and asks for nothing else`() {
        assertEquals(
            LaunchRequest.Kind.Home,
            LaunchRequest.classify(action = main, openTerminal = false, openTransfers = false),
        )
        assertFalse(LaunchRequest.isShare(main))
    }

    @Test
    fun `an Intent with no action at all is not a share either`() {
        // Restored tasks and internal relaunches can hand over an Intent whose action
        // was never set. Guessing "share" from an empty payload is exactly how the
        // Files screen used to become the start screen.
        assertEquals(
            LaunchRequest.Kind.Home,
            LaunchRequest.classify(action = null, openTerminal = false, openTransfers = false),
        )
        assertFalse(LaunchRequest.isShare(null))
    }

    @Test
    fun `an unrelated action does not open the Files screen`() {
        assertEquals(
            LaunchRequest.Kind.Home,
            LaunchRequest.classify(action = view, openTerminal = false, openTransfers = false),
        )
    }

    // --- shares --------------------------------------------------------------

    @Test
    fun `only the two share actions are shares`() {
        assertTrue(LaunchRequest.isShare(send))
        assertTrue(LaunchRequest.isShare(sendMultiple))
        assertEquals(
            LaunchRequest.Kind.Share,
            LaunchRequest.classify(action = send, openTerminal = false, openTransfers = false),
        )
        assertEquals(
            LaunchRequest.Kind.Share,
            LaunchRequest.classify(action = sendMultiple, openTerminal = false, openTransfers = false),
        )
    }

    // --- notification taps ---------------------------------------------------

    @Test
    fun `the notification extras open the screen they promise`() {
        assertEquals(
            LaunchRequest.Kind.Terminal,
            LaunchRequest.classify(action = main, openTerminal = true, openTransfers = false),
        )
        assertEquals(
            LaunchRequest.Kind.Transfers,
            LaunchRequest.classify(action = main, openTerminal = false, openTransfers = true),
        )
    }

    @Test
    fun `a notification tap beats the action it arrived with`() {
        // The queue notification is a deliberate "come and look at this"; whatever
        // action it happens to carry, the tap has to land on the queue.
        assertEquals(
            LaunchRequest.Kind.Transfers,
            LaunchRequest.classify(action = send, openTerminal = false, openTransfers = true),
        )
        assertEquals(
            LaunchRequest.Kind.Transfers,
            LaunchRequest.classify(action = null, openTerminal = true, openTransfers = true),
        )
    }
}
