package com.sangiorgisrl.screenlink.ssh

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app-wide [HostKeyConfirmation]: publishes the key that needs confirming and
 * waits for the dialog to answer.
 *
 * Application-scoped on purpose (like LiveTerminalStore and TransferStore): the
 * connection that is asking may belong to a copy queue or a terminal tab, while the
 * dialog that answers it is drawn by whichever screen happens to be in front.
 *
 * Fails closed everywhere it can:
 *  - a second concurrent question is refused rather than queued, because two
 *    pending challenges would let the user confirm the key of a connection they
 *    are not looking at;
 *  - nobody answering is a refusal, not a timeout that eventually connects.
 */
class HostKeyTrustPrompt : HostKeyConfirmation {

    private val _pending = MutableStateFlow<HostKeyChallenge?>(null)

    /** What the UI should show right now, or null when nothing is being asked. */
    val pending: StateFlow<HostKeyChallenge?> = _pending.asStateFlow()

    private val waiter = AtomicReference<CompletableDeferred<Boolean>?>(null)

    override suspend fun confirm(challenge: HostKeyChallenge): Boolean {
        val answer = CompletableDeferred<Boolean>()
        // Only one question at a time: whoever loses the race is refused instead of
        // stacking a second dialog whose buttons would answer the wrong connection.
        if (!waiter.compareAndSet(null, answer)) return false
        _pending.value = challenge
        return try {
            answer.await()
        } finally {
            waiter.compareAndSet(answer, null)
            if (_pending.value == challenge) _pending.value = null
        }
    }

    /** Called by the dialog: did the user vouch for the key currently on screen? */
    fun respond(trusted: Boolean) {
        waiter.getAndSet(null)?.complete(trusted)
        _pending.value = null
    }

    /** True while a question is open - used by tests and by the dialog's state. */
    fun isAsking(): Boolean = waiter.get() != null
}
