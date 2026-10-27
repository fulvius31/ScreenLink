package com.sangiorgisrl.screenlink.terminal.emulator

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** ESC, the escape character every sequence in these tests starts with. */
const val ESC = "\u001B"

/**
 * Shared scaffolding for the terminal tests.
 *
 * The emulator is plain Kotlin - it only needs a [TerminalOutput] (the byte sink) and a
 * [TerminalSessionClient] (the state callbacks) - so the whole VT layer can be pinned on
 * the JVM, with no session, no view and no device. Everything the emulator *sends back*
 * (the bytes a real transport would put on the SSH channel, title changes, bells, colour
 * changes) is recorded here so a test can assert on it.
 */
class RecordingOutput : TerminalOutput() {

    private val sink = ByteArrayOutputStream()

    /** (oldTitle, newTitle) pairs, in order, exactly as the emulator reported them. */
    val titles = mutableListOf<Pair<String?, String?>>()

    var bells = 0
        private set
    var colorChanges = 0
        private set
    var clipboardWrites = 0
        private set
    var clipboardRequests = 0
        private set

    val bytes: ByteArray get() = sink.toByteArray()

    /** Everything the emulator wrote back, decoded as UTF-8 (escape codes included). */
    val text: String get() = String(bytes, StandardCharsets.UTF_8)

    fun clearOutput() = sink.reset()

    override fun write(data: ByteArray, offset: Int, count: Int) = sink.write(data, offset, count)

    override fun titleChanged(oldTitle: String?, newTitle: String?) {
        titles += oldTitle to newTitle
    }

    override fun onCopyTextToClipboard(text: String) {
        clipboardWrites++
    }

    override fun onPasteTextFromClipboard() {
        clipboardRequests++
    }

    override fun onBell() {
        bells++
    }

    override fun onColorsChanged() {
        colorChanges++
    }
}

/** A [TerminalSessionClient] that only counts, so callback wiring is observable. */
class RecordingClient(private val cursorStyle: Int? = null) : TerminalSessionClient {

    var textChanges = 0
        private set
    var titleChanges = 0
        private set
    var bells = 0
        private set
    var colorChanges = 0
        private set
    var finished = 0
        private set
    val cursorStates = mutableListOf<Boolean>()

    override fun onTextChanged(changedSession: TermSession) {
        textChanges++
    }

    override fun onTitleChanged(changedSession: TermSession) {
        titleChanges++
    }

    override fun onSessionFinished(finishedSession: TermSession) {
        finished++
    }

    override fun onCopyTextToClipboard(session: TermSession, text: String) {
        bells += 0 // no-op, kept explicit
    }

    override fun onPasteTextFromClipboard(session: TermSession) = Unit

    override fun onBell(session: TermSession) {
        bells++
    }

    override fun onColorsChanged(session: TermSession) {
        colorChanges++
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        cursorStates += state
    }

    override fun getTerminalCursorStyle(): Int? = cursorStyle

    override fun logError(tag: String, message: String) = Unit
    override fun logWarn(tag: String, message: String) = Unit
    override fun logInfo(tag: String, message: String) = Unit
    override fun logDebug(tag: String, message: String) = Unit
    override fun logVerbose(tag: String, message: String) = Unit
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
    override fun logStackTrace(tag: String, e: Exception) = Unit
}

/**
 * A small, fixed-size emulator plus the two recording collaborators.
 *
 * Small screens (10x5 by default) keep the assertions readable: scrolling, margins and
 * autowrap all fit in a handful of rows.
 */
class EmulatorHarness(
    columns: Int = DEFAULT_COLUMNS,
    rows: Int = DEFAULT_ROWS,
    transcriptRows: Int? = null,
) {
    val output = RecordingOutput()
    val client = RecordingClient()
    val emulator = TerminalEmulator(output, columns, rows, transcriptRows, client)

    /** Feed a character stream (escape sequences written literally) to the emulator. */
    fun feed(stream: String): EmulatorHarness {
        val bytes = stream.toByteArray(StandardCharsets.UTF_8)
        emulator.append(bytes, bytes.size)
        return this
    }

    /** Feed raw bytes, for the UTF-8 / binary-input cases. */
    fun feedBytes(vararg unsignedBytes: Int): EmulatorHarness {
        val bytes = ByteArray(unsignedBytes.size) { i -> unsignedBytes[i].toByte() }
        emulator.append(bytes, bytes.size)
        return this
    }

    /** The visible rows, top to bottom, with trailing blanks removed. */
    fun rows(): List<String> = (0 until emulator.rows).map { row(it) }

    fun row(y: Int): String =
        emulator.screen.getSelectedText(0, y, emulator.columns - 1, y, false, false)

    fun screen(): String = rows().joinToString("\n")

    /** The cells of a row as a string padded with spaces to the full width. */
    fun paddedRow(y: Int): String = row(y).padEnd(emulator.columns, ' ')

    companion object {
        const val DEFAULT_COLUMNS = 10
        const val DEFAULT_ROWS = 5
    }
}
