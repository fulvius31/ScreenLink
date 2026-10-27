package com.sangiorgisrl.screenlink.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The prompt is the only thing standing between a first connection and a sent
 * password, so every "did the user actually say yes" path is pinned here.
 */
class HostKeyTrustPromptTest {

    private fun challenge(name: String = "lab", host: String = "host.example") =
        HostKeyChallenge(7L, name, host, 22, "SHA256:AAA")

    /**
     * Every test below waits for the prompt to reach a state. The wait is bounded so
     * a broken implementation fails the test instead of hanging the suite, which is
     * what makes the concurrency assertions usable as regression guards.
     */
    private suspend fun awaitUntil(condition: () -> Boolean, what: String) {
        val reached = withTimeoutOrNull(2_000) {
            while (!condition()) yield()
            true
        }
        assertTrue(reached == true, "timed out waiting for $what")
    }

    @Test
    fun `trusting the key releases the waiting connect`() = runBlocking {
        val prompt = HostKeyTrustPrompt()
        val asking = async { prompt.confirm(challenge()) }
        awaitUntil({ prompt.pending.value != null }, "the question to be published")

        prompt.respond(true)

        assertTrue(asking.await())
        assertNull(prompt.pending.value, "the dialog must disappear once it has answered")
        assertFalse(prompt.isAsking())
    }

    @Test
    fun `refusing the key answers no`() = runBlocking {
        val prompt = HostKeyTrustPrompt()
        val asking = async { prompt.confirm(challenge()) }
        awaitUntil({ prompt.pending.value != null }, "the question to be published")

        prompt.respond(false)

        assertFalse(asking.await())
    }

    @Test
    fun `the challenge on screen is the challenge being answered`() = runBlocking {
        val prompt = HostKeyTrustPrompt()
        val asking = async { prompt.confirm(challenge("prod", "prod.example")) }
        awaitUntil({ prompt.pending.value != null }, "the question to be published")

        val shown = prompt.pending.value
        assertNotNull(shown, "the dialog needs something to show before it can answer")
        assertEquals("prod", shown!!.connectionName)
        assertEquals("prod.example", shown.host)
        assertEquals(22, shown.port)
        assertEquals("SHA256:AAA", shown.fingerprint)
        assertTrue(prompt.isAsking())

        prompt.respond(true)
        asking.await()
    }

    @Test
    fun `a second concurrent question is refused instead of queued`() = runBlocking {
        // Two dialogs would let the user confirm the key of a connection they are
        // not looking at, so the loser of the race must be refused outright.
        val prompt = HostKeyTrustPrompt()
        val first = async { prompt.confirm(challenge("first")) }
        awaitUntil({ prompt.pending.value != null }, "the question to be published")

        // withTimeout, not a bare call: an implementation that QUEUES the second
        // question must fail this test rather than hang the suite.
        val second = withTimeoutOrNull(500L) { prompt.confirm(challenge("second")) }
        assertEquals(false, second, "a second question while one is open must be refused, not queued")
        assertEquals("first", prompt.pending.value?.connectionName, "the open question stays the visible one")

        prompt.respond(true)
        assertTrue(first.await())
    }

    @Test
    fun `an answer given while nothing is asked is not banked`() = runBlocking {
        // Otherwise a stray tap could pre-approve the NEXT first-contact question,
        // which is the exact failure this class exists to prevent.
        val prompt = HostKeyTrustPrompt()
        prompt.respond(true)

        val asking = async { prompt.confirm(challenge()) }
        awaitUntil({ prompt.pending.value != null }, "the question to be published")
        assertTrue(asking.isActive, "the connect must still be waiting for a real answer")

        prompt.respond(false)
        assertFalse(asking.await())
    }

    @Test
    fun `a cancelled question frees the prompt for the next one`() = runBlocking {
        val prompt = HostKeyTrustPrompt()
        val asking = async { prompt.confirm(challenge()) }
        awaitUntil({ prompt.pending.value != null }, "the question to be published")

        asking.cancel()

        // The single waiter slot must be released even though nobody answered,
        // otherwise one abandoned tab silences every later first-contact question.
        val answer = CompletableDeferred<Boolean>()
        val retry = launch { answer.complete(prompt.confirm(challenge("second"))) }
        awaitUntil({ prompt.pending.value?.connectionName == "second" }, "the freed prompt to take the next question")
        prompt.respond(true)

        assertTrue(answer.await())
        retry.join()
    }
}
