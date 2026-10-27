package com.sangiorgisrl.screenlink.transfer

import com.sangiorgisrl.screenlink.ssh.isDeadTransport
import java.util.concurrent.CancellationException

/**
 * Whether an operation may be run again on a fresh connection.
 *
 * A phone whose Wi-Fi slept, a NAT that reaped an idle flow or a server that
 * restarted all kill an SSH session underneath an app that still believes it is
 * connected. The user's next tap then fails with a message about pipes, and the
 * only sensible thing to do is exactly what they would do by hand: reconnect and
 * run the same call again. Doing it once, silently, is the difference between a
 * flaky app and one that never seems to notice.
 *
 * The limits are the point:
 *  - **only a dead link** - a wrong password, a refused permission or a full disk
 *    fails identically twice, and a second handshake only makes the user wait;
 *  - **only once** - a server that is down is down, and retry loops are how one
 *    outage turns into a hundred connections.
 */
object RetryPolicy {

    /** Attempts allowed for one operation: the original plus one retry. */
    const val MAX_ATTEMPTS = 2

    /**
     * [attempt] is 1-based: pass the attempt that JUST failed.
     *
     * A cancellation is never a retry candidate - the user asked this to stop, and
     * starting it again would be the app ignoring them.
     */
    fun shouldRetry(error: Throwable, attempt: Int, maxAttempts: Int = MAX_ATTEMPTS): Boolean {
        if (error is CancellationException) return false
        // Our own cancel signal is an IOException, so it is worth naming: the user
        // asked this copy to stop, and the queue must not start it again.
        if (error is TransferCancelledException) return false
        if (attempt < 1 || attempt >= maxAttempts) return false
        return isDeadTransport(error)
    }
}
