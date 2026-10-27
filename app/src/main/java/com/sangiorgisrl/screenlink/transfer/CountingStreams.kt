package com.sangiorgisrl.screenlink.transfer

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Thrown from inside a transfer stream when the user cancels.
 *
 * The SSH libraries only stop streaming when the stream they are pumping stops,
 * so cancellation is expressed as an exception on the byte path rather than as a
 * flag the (blocking) library would never check. Callers classify it as
 * "cancelled", not as a failure.
 */
class TransferCancelledException : IOException("transfer cancelled")

/**
 * Counts bytes flowing through a stream and reports the CUMULATIVE total, so the
 * UI can show "12.4 MB of 45.0 MB" and compute a speed.
 *
 * The cancel check runs before every read/write, so a cancel lands within one
 * buffer (a few tens of KB) instead of waiting for a large file to finish.
 */
class CountingInputStream(
    private val delegate: InputStream,
    private val listener: ProgressListener,
) : InputStream() {

    var transferred: Long = 0L
        private set

    private fun check() {
        if (listener.isCancelled()) throw TransferCancelledException()
    }

    private fun counted(n: Int) {
        transferred += n
        listener.onBytes(transferred)
    }

    override fun read(): Int {
        check()
        val b = delegate.read()
        if (b >= 0) counted(1)
        return b
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        check()
        val n = delegate.read(buffer, offset, length)
        if (n > 0) counted(n)
        return n
    }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()
}

/** The download-side twin: counts bytes written and honours cancellation. */
class CountingOutputStream(
    private val delegate: OutputStream,
    private val listener: ProgressListener,
) : OutputStream() {

    var transferred: Long = 0L
        private set

    private fun check() {
        if (listener.isCancelled()) throw TransferCancelledException()
    }

    private fun counted(n: Int) {
        transferred += n
        listener.onBytes(transferred)
    }

    override fun write(b: Int) {
        check()
        delegate.write(b)
        counted(1)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        check()
        delegate.write(buffer, offset, length)
        counted(length)
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
}
