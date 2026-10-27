package com.sangiorgisrl.screenlink.terminal.emulator

/*
 * Derived from the terminal-emulator module of the Termux terminal emulator
 * (https://github.com/termux/termux-app), Copyright (c) the Termux contributors,
 * Apache License 2.0 - see the NOTICE file at the root of this repository.
 * Rewritten in Kotlin for this application.
 */

import java.util.UUID

/**
 * A terminal session backed by an arbitrary byte transport rather than a process.
 *
 * This app never runs a local shell: the concrete subclasses (see
 * `com.sangiorgisrl.screenlink.ssh.SshTermSession`) own a network channel and hand its
 * bytes to the [TerminalEmulator], and take the bytes the user produces from it.
 * Everything here is transport agnostic - size changes, the title, the client callbacks
 * and the repaint hook live in one place.
 */
abstract class TermSession(transcriptRows: Int?) : TerminalOutput() {

    /** Stable identity for this session, used to key it in the UI layer. */
    @JvmField
    val handle: String = UUID.randomUUID().toString()

    /** Null until the first [updateSize] lays out the emulator. */
    var emulator: TerminalEmulator? = null
        private set

    /** Notified when the session changes title, colours, and so on. */
    protected var client: TerminalSessionClient? = null

    /**
     * Run on any thread whenever new output bytes were appended, so the view can repaint.
     * Set by `TerminalView.attachSession(...)`.
     */
    @Volatile
    private var onOutputListener: Runnable? = null

    private val transcriptRowCount: Int = transcriptRows ?: TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS

    var columns = -1
        private set

    var rows = -1
        private set

    /**
     * Register a [Runnable] to be run (on any thread) whenever new output bytes were
     * appended for the emulator. The terminal view uses this to repaint promptly instead
     * of waiting for the passive cursor-blinker cadence.
     */
    fun setOnOutputListener(listener: Runnable?) {
        onOutputListener = listener
    }

    /** Tell the registered output listener that the emulator received new bytes. */
    fun notifyOnOutput() {
        onOutputListener?.run()
    }

    /** Replace the callback object used to report session state changes. */
    fun updateTerminalSessionClient(newClient: TerminalSessionClient) {
        client = newClient
        emulator?.updateTerminalSessionClient(newClient)
    }

    /**
     * Tell the session about the new terminal size in cells. The first call creates the
     * [TerminalEmulator] and lets subclasses start their background I/O; later calls
     * reflow it.
     */
    fun updateSize(newColumns: Int, newRows: Int) {
        columns = newColumns
        rows = newRows
        val existing = emulator
        if (existing == null) {
            emulator = TerminalEmulator(this, newColumns, newRows, transcriptRowCount, client)
            onEmulatorInitialized()
        } else {
            existing.resize(newColumns, newRows)
            onResize(newColumns, newRows)
        }
        notifyScreenUpdate()
    }

    /** Called the first time the emulator is created after [updateSize]. */
    protected open fun onEmulatorInitialized() {}

    /** Called every time the emulator is resized after the first layout. */
    protected open fun onResize(columns: Int, rows: Int) {}

    /** The terminal title as set through escape sequences, or null if unset. */
    val title: String?
        get() = emulator?.title

    /**
     * Write the given Unicode code point to the transport encoded as UTF-8, optionally
     * prefixed with an ESC character (for Alt-keys).
     */
    fun writeCodePoint(prependEscape: Boolean, codePoint: Int) {
        if (codePoint > 1114111 || codePoint in 0xD800..0xDFFF) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point,
            // [0xD800,0xDFFF] is the surrogate range.
            throw IllegalArgumentException("Invalid code point: $codePoint")
        }

        val buffer = ByteArray(5)
        var position = 0
        if (prependEscape) buffer[position++] = 27

        when {
            codePoint <= 0b1111111 -> { // 7 bits
                buffer[position++] = codePoint.toByte()
            }
            codePoint <= 0b11111111111 -> { // 11 bits
                buffer[position++] = (0b11000000 or (codePoint shr 6)).toByte()
                buffer[position++] = (0b10000000 or (codePoint and 0b111111)).toByte()
            }
            codePoint <= 0b1111111111111111 -> { // 16 bits
                buffer[position++] = (0b11100000 or (codePoint shr 12)).toByte()
                buffer[position++] = (0b10000000 or ((codePoint shr 6) and 0b111111)).toByte()
                buffer[position++] = (0b10000000 or (codePoint and 0b111111)).toByte()
            }
            else -> { // up to 21 bits
                buffer[position++] = (0b11110000 or (codePoint shr 18)).toByte()
                buffer[position++] = (0b10000000 or ((codePoint shr 12) and 0b111111)).toByte()
                buffer[position++] = (0b10000000 or ((codePoint shr 6) and 0b111111)).toByte()
                buffer[position++] = (0b10000000 or (codePoint and 0b111111)).toByte()
            }
        }
        write(buffer, 0, position)
    }

    /** Tell the [TerminalSessionClient] that the screen has changed. */
    protected fun notifyScreenUpdate() {
        client?.onTextChanged(this)
    }

    /** Reset the emulator's scrollback and screen. */
    fun reset() {
        emulator?.reset()
        notifyScreenUpdate()
    }

    /** Whether the transport is still active and has not been finished. */
    open val isRunning: Boolean
        get() = true

    // --- TerminalOutput callbacks, forwarded to the client -------------------

    override fun titleChanged(oldTitle: String?, newTitle: String?) {
        client?.onTitleChanged(this)
    }

    override fun onCopyTextToClipboard(text: String) {
        client?.onCopyTextToClipboard(this, text)
    }

    override fun onPasteTextFromClipboard() {
        client?.onPasteTextFromClipboard(this)
    }

    override fun onBell() {
        client?.onBell(this)
    }

    override fun onColorsChanged() {
        client?.onColorsChanged(this)
    }

    /** Release resources held by the transport. */
    abstract fun finishIfRunning()
}
