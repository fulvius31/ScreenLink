package com.sangiorgisrl.screenlink.transfer

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The screen-scoped file-transfer session the remote browser talks through.
 *
 * Two rules live here rather than at the call sites, because both are easy to
 * forget on a new button and both stay invisible until they break:
 *
 *  - **Never on the UI thread.** JSch speaks on a socket, and Android kills a socket
 *    call made from the main thread with `NetworkOnMainThreadException` - which the
 *    user reads as "Could not open the folder: android.os.NetworkOnMainThreadException".
 *    Every call hops to [blocking] first, so a browse call cannot repeat that mistake.
 *  - **One channel, one call at a time.** A JSch SFTP channel is single-threaded, so
 *    a refresh tapped while a listing is still in flight must not share it.
 *
 * A channel that failed is dropped instead of reused: a half-dead channel makes every
 * later tap fail in a new and more confusing way.
 *
 * A channel that is ALREADY dead is replaced before the tap ever speaks on it
 * (see [TransferTransport.isAlive]): a phone whose Wi-Fi slept, a network that
 * switched from Wi-Fi to data or a server that restarted all leave a session that
 * LOOKS open to us and answers the next call with "pipe is closed". Asking the
 * session first turns that into a one second reconnect the user never has to read
 * about; the retry below is only there for the race that check cannot see.
 */
class BrowseSession(
    /** Opens a fresh channel. Main-safe: the connector does its own IO hop. */
    private val open: suspend () -> TransferTransport,
    private val blocking: CoroutineDispatcher = Dispatchers.IO,
) {

    private val lock = Mutex()
    private var channel: TransferTransport? = null

    /** True while a channel is open (for tests and for the "connecting" UI). */
    val isOpen: Boolean get() = channel != null

    /**
     * Run [block] against the open channel, opening it on first use.
     *
     * A call that died because the LINK died is run once more on a fresh channel
     * (see [RetryPolicy]): an idle SSH session is routinely gone by the time the
     * user taps, and making them tap twice for that is a bug they experience as an
     * unreliable app. Reading a folder twice is harmless, which is why browsing can
     * afford the retry at all.
     *
     * [retryOnDeadLink] turns that off for a call that is not safe to repeat. A
     * "make this folder" whose reply never arrived may already have been carried
     * out, and running it again answers "folder already exists" - a failure the
     * user did not cause and cannot understand.
     */
    suspend fun <T> use(
        retryOnDeadLink: Boolean = true,
        block: (TransferTransport) -> T,
    ): T = lock.withLock { runAttempts(retryOnDeadLink, block) }

    /**
     * The attempt loop, with the lock already held.
     *
     * A separate function rather than a lambda body: a `while (true)` is typed
     * `Unit`, so it cannot be the last expression of the [withLock] lambda whose
     * value has to be the caller's `T`.
     */
    private suspend fun <T> runAttempts(
        retryOnDeadLink: Boolean,
        block: (TransferTransport) -> T,
    ): T {
        var attempt = 0
        while (true) {
            attempt++
            // A session the server already forgot about is not worth a failed call:
            // drop it before speaking, so the user waits for a handshake instead of
            // reading a pipe error (and, for a call that may not be repeated, so
            // there is no failed call at all).
            val stale = channel
            if (stale != null && !isAlive(stale)) dropChannel()
            val opened = channel ?: open().also { channel = it }
            try {
                return withContext(blocking) { block(opened) }
            } catch (e: Throwable) {
                dropChannel()
                // The caller is going away: no retry, and no swallowing the cancel.
                if (e is CancellationException) throw e
                if (!retryOnDeadLink || !RetryPolicy.shouldRetry(e, attempt)) throw e
                // Falls through to the top of the loop, which opens a new channel.
            }
        }
    }

    /**
     * Is this channel still able to answer?
     *
     * [runCatching] because asking is itself a call into a library that is in the
     * middle of falling apart: a transport that throws while being asked is a
     * transport that is not alive, and the honest answer must not become a new
     * failure the user has to read.
     */
    private fun isAlive(transport: TransferTransport): Boolean =
        runCatching { transport.isAlive() }.getOrDefault(false)

    /**
     * Hand the channel back (the screen is gone). The next [use] opens a new one.
     *
     * Closing an SSH session is a socket write, so it goes off-thread like everything
     * else here - and under [NonCancellable], because this is exactly the moment the
     * caller is being cancelled and the server still deserves a clean goodbye rather
     * than a channel left open until the GC notices.
     */
    suspend fun close() = lock.withLock { dropChannel() }

    /** Only ever called with [lock] held. */
    private suspend fun dropChannel() {
        val closing = channel
        channel = null
        if (closing != null) {
            withContext(blocking + NonCancellable) { runCatching { closing.close() } }
        }
    }
}
