package com.sangiorgisrl.screenlink

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one invariant the notification shade depends on: no two PendingIntents in this
 * app may share a request code.
 *
 * A PendingIntent is looked up by (kind, request code, `Intent.filterEquals`), and
 * `filterEquals` ignores extras. The app has two foreground notifications whose content
 * Intents both name MainActivity and differ ONLY by the extra that says which screen to
 * open - so a shared code silently merges them, and the notification painted second
 * sends the user to the first one's screen. That is a bug no JVM test can reproduce
 * (it needs the real PendingIntent cache), but the collision is fully determined by
 * these integers, so the integers are what is pinned.
 */
class NotificationIntentsTest {

    @Test
    fun `every notification PendingIntent has its own request code`() {
        val codes = listOf(
            NotificationIntents.OPEN_TERMINAL,
            NotificationIntents.OPEN_TRANSFERS,
            NotificationIntents.TERMINAL_DISCONNECT_ALL,
            NotificationIntents.TRANSFERS_CANCEL_ALL,
        )
        assertEquals(
            codes.distinct().size,
            codes.size,
            "two notifications would share one PendingIntent token: their taps open the wrong screen",
        )
    }

    @Test
    fun `the two content intents that both name MainActivity are told apart by their code`() {
        // The pair that actually collided: same PendingIntent kind (getActivity), same
        // component, extras ignored by the lookup. Everything else in the app either
        // names a different component or is a different kind, so this is the check that
        // has to keep failing if someone reuses a code.
        assertTrue(
            NotificationIntents.OPEN_TERMINAL != NotificationIntents.OPEN_TRANSFERS,
            "the terminal tap and the copy tap must not share a request code",
        )
    }
}
