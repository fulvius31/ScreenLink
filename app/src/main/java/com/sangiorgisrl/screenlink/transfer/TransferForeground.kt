package com.sangiorgisrl.screenlink.transfer

/**
 * The one thing the copy queue needs from the platform: keep the process alive
 * while bytes are moving.
 *
 * The queue is application-scoped, but an application-scoped coroutine is still
 * killed when the system reclaims the process, and a copy that dies at 60 % of a
 * 2 GB file is the worst possible failure of this feature. So the queue asks for a
 * foreground service (see [TransferProgressService]) the moment work is queued,
 * and the service lets go by itself once the queue is idle.
 *
 * It is an interface for one boring reason: the JVM unit tests must be able to run
 * the whole queue without an Android framework under it.
 */
fun interface TransferForeground {

    /**
     * Make sure the process is held up.
     *
     * Called synchronously from [TransferStore.enqueue], i.e. on the thread that
     * queued the work - always the UI thread with the app in the foreground. That
     * is deliberate: Android 12+ refuses a background start of a foreground
     * service, and a copy queued from a share sheet or a notification action has
     * that moment to get its service running.
     */
    fun begin()

    companion object {
        /** For tests: nothing to hold, nothing to show. */
        val None: TransferForeground = TransferForeground { }
    }
}
