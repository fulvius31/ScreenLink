package com.sangiorgisrl.screenlink.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two streams the progress bar is built on.
 *
 * JSch does the pumping, so the app only sees bytes that pass through these
 * wrappers. Two contracts matter to the user and are pinned here: the listener
 * always receives the CUMULATIVE total (a speed is computed from it, so a delta
 * would read as an absurd rate), and a cancel must abort inside one buffer
 * instead of after the whole file.
 */
class CountingStreamsTest {

    private class Listener : ProgressListener {
        val samples = mutableListOf<Long>()
        val cancelled = AtomicBoolean(false)
        val checks = AtomicInteger(0)

        override fun onBytes(transferred: Long) {
            samples += transferred
        }

        override fun isCancelled(): Boolean {
            checks.incrementAndGet()
            return cancelled.get()
        }
    }

    @Test
    fun `reads are counted and every sample is the running total`() {
        val listener = Listener()
        val data = ByteArray(1_000) { it.toByte() }
        val input = CountingInputStream(ByteArrayInputStream(data), listener)
        val buffer = ByteArray(300)
        var total = 0
        while (true) {
            val n = input.read(buffer, 0, buffer.size)
            if (n < 0) break
            total += n
        }

        assertEquals(1_000, total)
        assertEquals(1_000L, input.transferred)
        // Monotonic cumulative samples: 300, 600, 900, 1000 - never a per-chunk delta.
        assertEquals(listOf(300L, 600L, 900L, 1000L), listener.samples)
    }

    @Test
    fun `the single-byte read is counted too`() {
        val listener = Listener()
        val input = CountingInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3)), listener)

        assertEquals(1, input.read())
        assertEquals(2, input.read())
        assertEquals(3, input.read())
        // EOF is not a byte, so it must not inflate the counter.
        assertEquals(-1, input.read())

        assertEquals(3L, input.transferred)
        assertEquals(listOf(1L, 2L, 3L), listener.samples)
    }

    @Test
    fun `a zero-length read is a no-op - it does not even ask to be cancelled`() {
        val listener = Listener()
        val input = CountingInputStream(ByteArrayInputStream(byteArrayOf(1, 2)), listener)

        assertEquals(0, input.read(ByteArray(4), 0, 0))

        assertEquals(0L, input.transferred)
        assertTrue(listener.samples.isEmpty())
        assertEquals(0, listener.checks.get())
    }

    @Test
    fun `cancelling stops the stream on the very next chunk`() {
        val listener = Listener()
        val input = CountingInputStream(ByteArrayInputStream(ByteArray(10_000)), listener)
        val buffer = ByteArray(1_000)

        assertEquals(1_000, input.read(buffer, 0, buffer.size))
        listener.cancelled.set(true)

        assertThrows(TransferCancelledException::class.java) {
            input.read(buffer, 0, buffer.size)
        }
        // Only the bytes that really moved are reported, so the UI never shows a
        // transfer further along than the server actually received.
        assertEquals(1_000L, input.transferred)
    }

    @Test
    fun `close and available belong to the wrapped stream`() {
        var closed = false
        val delegate = object : InputStream() {
            override fun read(): Int = -1
            override fun available(): Int = 42
            override fun close() {
                closed = true
            }
        }
        val input = CountingInputStream(delegate, Listener())

        assertEquals(42, input.available())
        input.close()

        assertTrue(closed)
    }

    @Test
    fun `writes are counted with cumulative samples`() {
        val listener = Listener()
        val out = ByteArrayOutputStream()
        val output = CountingOutputStream(out, listener)

        output.write(ByteArray(400))
        output.write(0x41)
        output.write(ByteArray(100), 10, 50)

        assertEquals(451L, output.transferred)
        assertEquals(451, out.size())
        assertEquals(listOf(400L, 401L, 451L), listener.samples)
    }

    @Test
    fun `a zero-length write writes nothing and counts nothing`() {
        val listener = Listener()
        val out = ByteArrayOutputStream()
        val output = CountingOutputStream(out, listener)

        output.write(ByteArray(8), 0, 0)

        assertEquals(0L, output.transferred)
        assertEquals(0, out.size())
        assertEquals(0, listener.checks.get())
    }

    @Test
    fun `cancelling a download stops the write and keeps the partial file honest`() {
        val listener = Listener()
        val out = ByteArrayOutputStream()
        val output = CountingOutputStream(out, listener)

        output.write(ByteArray(2_000))
        listener.cancelled.set(true)

        assertThrows(TransferCancelledException::class.java) { output.write(ByteArray(2_000)) }

        assertEquals(2_000L, output.transferred)
        assertEquals(2_000, out.size())
    }

    @Test
    fun `flush and close reach the destination`() {
        var flushed = false
        var closed = false
        val delegate = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun flush() {
                flushed = true
            }
            override fun close() {
                closed = true
            }
        }
        val output = CountingOutputStream(delegate, Listener())

        output.flush()
        output.close()

        assertTrue(flushed)
        assertTrue(closed)
    }
}
