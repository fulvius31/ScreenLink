package com.sangiorgisrl.screenlink.transfer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Why the queue is handed to the screens as the hot StateFlow it already is, and
 * never re-wrapped in `stateIn(...)`.
 *
 * `TransferStore.jobs` is backed by a `MutableStateFlow`: it is hot, it always holds
 * the current queue, and it never completes. Wrapping it again - which is what the
 * Home card, the Files top-bar badge and the queue screen used to do - adds a share
 * whose value is the **initial value it was given** until that share has actually
 * been dispatched. So the first frame of a screen can answer "no copies" over a
 * queue that is really running: the badge is missing, the Home card is not there, the
 * queue screen shows its empty state, and the real rows arrive one frame later.
 *
 * What this file deliberately does NOT claim: a `WhileSubscribed` share does *not*
 * wipe its value back to the initial one once the last subscriber has gone. That is
 * what `replayExpirationMillis` controls and it defaults to `Long.MAX_VALUE`, so the
 * last queue survives. The second test pins that, because the folklore version of
 * this bug would "fix" the wrong thing.
 *
 * The app's timeout is 5 s; 100 ms here with a 500 ms wait, so the timing cannot race.
 */
class QueueFlowSharingTest {

    private val queue = listOf("g1-1 running")

    @Test
    fun `a fresh wrapper answers no copies until its share is dispatched`() = runBlocking {
        val hot = MutableStateFlow(queue)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val wrapped = hot.stateIn(scope, SharingStarted.WhileSubscribed(100), emptyList())

        // The screen subscribes. The share has been queued, not run, so the wrapper is
        // still sitting on the emptyList() it was handed at construction.
        val watcher = launch { wrapped.collect { } }
        assertEquals(
            emptyList<String>(),
            wrapped.value,
            "the wrapper answered \"no copies\" for a queue that was never empty - " +
                "this is the frame that hides the badge and opens the queue screen empty",
        )

        // One dispatch later the rows appear: on screen, a blink rather than a bug.
        delay(50)
        assertEquals(queue, wrapped.value)
        watcher.cancel()
    }

    @Test
    fun `the hot queue answers correctly on the very first frame`() = runBlocking {
        val hot = MutableStateFlow(queue)
        // No share, no dispatch, no first empty frame: a StateFlow already holds the
        // answer at the moment a screen asks for it.
        assertEquals(queue, hot.value)

        val watcher = launch { hot.collect { } }
        assertEquals(queue, hot.value)
        watcher.cancel()
    }

    @Test
    fun `a stopped share keeps the queue it was holding`() = runBlocking {
        val hot = MutableStateFlow(queue)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val wrapped = hot.stateIn(scope, SharingStarted.WhileSubscribed(100), emptyList())

        val watcher = launch { wrapped.collect { } }
        delay(50)
        assertEquals(queue, wrapped.value)

        // Gone for far longer than the stop timeout: the upstream has stopped, but the
        // replay cache does not expire, so the value is NOT reset to emptyList().
        watcher.cancel()
        delay(500)
        assertEquals(
            queue,
            wrapped.value,
            "a WhileSubscribed share does not reset its value between subscriptions; " +
                "if this ever changes, the empty first frame gets much worse",
        )
    }
}
