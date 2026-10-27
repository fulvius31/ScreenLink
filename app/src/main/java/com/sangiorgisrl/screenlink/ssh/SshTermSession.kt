package com.sangiorgisrl.screenlink.ssh

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.sangiorgisrl.screenlink.terminal.emulator.TermSession
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [TermSession] whose byte transport is an SSH shell channel instead of a local
 * subprocess. Bytes typed into the terminal go over the wire to the remote pty
 * (which echoes, like any pty), and remote output feeds the emulator.
 *
 * IMPORTANT: JSch writes straight to the socket, so all I/O must happen off the
 * main thread or Android throws NetworkOnMainThreadException. A dedicated
 * single-thread writer preserves the ordering of keystrokes.
 *
 * Byte decoding (emulator append) runs on the SSH reader thread and never on the
 * UI thread, so heavy output (TUIs, log tails, `cat`) cannot jank the UI. The view
 * is repainted by the cursor blinker and its own invalidation.
 *
 * @param initialCommand optional GNU Screen command (e.g. `screen -dr name`) sent
 *        to the remote shell as soon as it is observed to be alive.
 * @param onFinished invoked (on the main thread) when the transport closes.
 */
class SshTermSession(
    private val channel: ChannelShell,
    private val initialCommand: String? = null,
    private val onFinished: (() -> Unit)? = null,
) : TermSession(2000) {

    companion object {
        private const val LOG_TAG = "ScreenLinkTerm"

        /** Fallback: send the attach command even if the shell produced no output. */
        private const val INITIAL_COMMAND_FALLBACK_MS = 6000L

        /** Byte budget for in-flight (queued + currently-writing) input. */
        private const val MAX_QUEUED_BYTES = 256 * 1024

        /**
         * How long a producer may block waiting for queue room before the session is
         * declared stalled. Bound below Android's ANR window (5s) so a genuinely stuck
         * link closes loudly instead of silently eating keystrokes; a merely slow link
         * almost never reaches this because the writer drains far faster than typing.
         */
        private const val ENQUEUE_BLOCK_MS = 2000L

        /** Large writes (e.g. a big paste) are sliced so no single task can exceed the
         *  byte budget and stall forever; each slice applies real backpressure. */
        private const val WRITE_CHUNK_SIZE = 16 * 1024
    }

    /**
     * True when this session was opened *through* GNU Screen (an attach/create command
     * was sent to the remote shell). Only such sessions have a server-side scrollback
     * worth driving with copy mode, so the UI offers the toggle for them alone.
     */
    val isScreenSession: Boolean get() = !initialCommand.isNullOrBlank()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val readBuffer = ByteArray(16 * 1024)

    /** Serial writer thread - every byte the user produces goes through here. */
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "screenlink-ssh-writer").apply { isDaemon = true }
    }

    /**
     * Byte-bounded permit for [writer]. Interactive input is NEVER dropped: a producer
     * blocks (bounded by [ENQUEUE_BLOCK_MS]) until the writer drains. Only a truly
     * stalled link - one that stopped consuming for seconds - ends the session with a
     * visible "connection closed" instead of silently swallowing the user's typing.
     */
    private val pendingBytes = Semaphore(MAX_QUEUED_BYTES)

    @Volatile
    private var finished = false

    /** The initial command is sent exactly once, when the remote shell is confirmed alive. */
    private val initialCommandSent = AtomicBoolean(false)

    private val output = try {
        channel.outputStream
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Failed to get channel output stream", e)
        null
    }

    override fun onEmulatorInitialized() {
        Log.d(LOG_TAG, "onEmulatorInitialized columns=${columns} rows=${rows} command=$initialCommand")
        pushPtySize()
        startReader()
        if (!initialCommand.isNullOrBlank()) {
            // The shell may be silent for a while (no MOTD). Don't wait forever.
            mainHandler.postDelayed({ sendInitialCommand() }, INITIAL_COMMAND_FALLBACK_MS)
        }
    }

    override fun onResize(columns: Int, rows: Int) {
        pushPtySize()
    }

    private fun pushPtySize() {
        if (finished) return
        val columns = columns
        val rows = rows
        if (columns <= 0 || rows <= 0) return
        try {
            writer.execute {
                try {
                    channel.setPtySize(columns, rows, 0, 0)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "setPtySize failed", e)
                }
            }
        } catch (e: Exception) {
            // Writer already shut down (teardown race) - the resize is moot.
            Log.w(LOG_TAG, "setPtySize submit failed", e)
        }
    }

    private fun startReader() {
        val thread = Thread {
            try {
                val input = channel.inputStream ?: return@Thread
                var firstRead = true
                while (!finished) {
                    val read = try {
                        input.read(readBuffer)
                    } catch (_: java.io.IOException) {
                        -1
                    }
                    if (read <= 0) break
                    if (firstRead) {
                        firstRead = false
                        // First output is evidence the remote login shell is alive -
                        // that is the moment it is safe to type the attach command.
                        mainHandler.post { sendInitialCommand() }
                    }
                    // Decode on this (background) thread, never on the UI thread.
                    emulator?.append(readBuffer, read)
                    // Wake the attached view so output repaints promptly (coalesced per
                    // frame by Android) instead of waiting for the 500ms cursor blinker.
                    notifyOnOutput()
                }
            } catch (_: Exception) {
                // Transport closed.
            } finally {
                mainHandler.post { onTransportClosed() }
            }
        }
        thread.isDaemon = true
        thread.name = "screenlink-ssh-reader"
        thread.start()
    }

    private fun onTransportClosed() {
        if (finished) return
        finished = true
        mainHandler.removeCallbacksAndMessages(null)
        Log.d(LOG_TAG, "onTransportClosed")
        onFinished?.invoke()
    }

    /** Send the initial (attach/create) command exactly once, off the main thread. */
    private fun sendInitialCommand() {
        val command = initialCommand ?: return
        if (command.isBlank()) return
        if (!initialCommandSent.compareAndSet(false, true)) return
        if (finished) return
        Log.d(LOG_TAG, "sendRemoteCommand '$command'")
        sendToRemote((command + "\r").toByteArray(Charsets.UTF_8))
    }

    private fun sendToRemote(bytes: ByteArray) {
        if (finished || bytes.isEmpty()) return
        // Slice large writes (paste) into chunk-sized tasks so a huge paste flows through
        // the byte gate instead of exceeding it all at once (which would stall forever).
        var offset = 0
        while (offset < bytes.size) {
            val chunk = minOf(WRITE_CHUNK_SIZE, bytes.size - offset)
            val piece = if (chunk == bytes.size) bytes else bytes.copyOfRange(offset, offset + chunk)
            offset += chunk
            if (!enqueueChunk(piece)) break // session stalled/closed - nothing more to send
        }
    }

    private fun enqueueChunk(piece: ByteArray): Boolean {
        if (finished || piece.isEmpty()) return false
        // Each byte of in-flight input takes one permit, so the writer queue can never
        // balloon in RAM. The caller blocks here (bounded) for real backpressure instead
        // of silently dropping the user's keystrokes.
        val acquired = try {
            pendingBytes.tryAcquire(piece.size, ENQUEUE_BLOCK_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) {
            Log.w(LOG_TAG, "writer saturated for ${ENQUEUE_BLOCK_MS}ms - closing stalled session")
            closeOnStall()
            return false
        }
        try {
            writer.execute {
                try {
                    val out = output
                    if (out != null) {
                        out.write(piece, 0, piece.size)
                        out.flush()
                    }
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "write failed", e)
                } finally {
                    pendingBytes.release(piece.size)
                }
            }
        } catch (e: Exception) {
            // Executor rejected/closed (teardown race) - release the permit and move on.
            // Never lets a RejectedExecutionException reach the UI thread.
            pendingBytes.release(piece.size)
            return false
        }
        return true
    }

    /** A link that stops consuming output for [ENQUEUE_BLOCK_MS] is dead in practice
     *  (the 30s keepalive would confirm within ~60-90s). End the session loudly rather
     *  than silently dropping the user's typing. */
    private fun closeOnStall() {
        if (finished) return
        finished = true
        mainHandler.removeCallbacksAndMessages(null)
        Log.w(LOG_TAG, "closeOnStall")
        mainHandler.post { onFinished?.invoke() }
    }

    override fun write(data: ByteArray, offset: Int, count: Int) {
        if (finished || count <= 0) return
        Log.v(LOG_TAG, "write $count bytes")
        val copy = data.copyOfRange(offset, offset + count)
        sendToRemote(copy)
    }

    override fun finishIfRunning() {
        if (finished) return
        finished = true
        mainHandler.removeCallbacksAndMessages(null)
        Log.d(LOG_TAG, "finishIfRunning")
        try {
            writer.execute {
                try {
                    channel.disconnect()
                } catch (_: Exception) {
                    // Ignore.
                }
                try {
                    channel.session.disconnect()
                } catch (_: Exception) {
                    // Ignore.
                }
                mainHandler.post { onFinished?.invoke() }
            }
        } catch (e: Exception) {
            // Writer already gone - signal completion anyway.
            mainHandler.post { onFinished?.invoke() }
            Log.w(LOG_TAG, "final disconnect submit failed", e)
        }
        writer.shutdown()
    }
}
