package com.sangiorgisrl.screenlink.terminal.emulator

import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The session layer between the transport and the emulator!!.
 *
 * A session owns the emulator lifecycle (created on the first size report, reflowed on
 * every later one), encodes what the user types as UTF-8 for the SSH channel, and routes
 * emulator callbacks to the UI client. None of that needs a socket, so it is all pinned
 * here with a fake transport.
 */
class TermSessionTest {

    /** A session whose "transport" is a byte recorder - what SshTermSession does for real. */
    private class RecordingSession(transcriptRows: Int?, withClient: Boolean = true) : TermSession(transcriptRows) {
        val recordingClient = RecordingClient()
        val written = mutableListOf<ByteArray>()
        var finishes = 0
            private set
        var emulatorStarts = 0
            private set
        var resizes = 0
            private set

        init {
            if (withClient) updateTerminalSessionClient(recordingClient)
        }

        override fun write(data: ByteArray, offset: Int, count: Int) {
            written += data.copyOfRange(offset, offset + count)
        }

        override fun finishIfRunning() {
            finishes++
        }

        override fun onEmulatorInitialized() {
            emulatorStarts++
        }

        override fun onResize(columns: Int, rows: Int) {
            resizes++
        }
    }

    private fun session(transcriptRows: Int? = 100): RecordingSession = RecordingSession(transcriptRows)

    private fun RecordingSession.feed(stream: String) {
        val bytes = stream.toByteArray(StandardCharsets.UTF_8)
        emulator!!.append(bytes, bytes.size)
    }

    private fun RecordingSession.screenRow(y: Int): String =
        emulator!!.screen.getSelectedText(0, y, emulator!!.columns - 1, y, false, false)

    @Test
    fun `the emulator only exists once a size is known`() {
        val session = session()
        assertNull(session.emulator)
        assertEquals(-1, session.columns)
        assertEquals(-1, session.rows)

        session.updateSize(80, 24)
        assertNotNull(session.emulator)
        assertEquals(80, session.columns)
        assertEquals(24, session.rows)
        assertEquals(1, session.emulatorStarts)
        assertEquals(0, session.resizes)
        assertEquals(1, session.recordingClient.textChanges, "the view must be asked to draw after the first size")
    }

    @Test
    fun `a later size report reflows the same emulator instead of rebuilding it`() {
        val session = session()
        session.updateSize(20, 5)
        val first = session.emulator
        session.feed("kept\r\n")
        session.updateSize(12, 4)

        assertSame(first, session.emulator)
        assertEquals(1, session.emulatorStarts)
        assertEquals(1, session.resizes)
        assertEquals(12, session.emulator!!.columns)
        assertEquals(4, session.emulator!!.rows)
    }

    @Test
    fun `output written before a resize survives the reflow`() {
        val session = session()
        session.updateSize(20, 5)
        session.feed("hello\r\n")
        session.updateSize(20, 4)
        assertEquals("hello", session.screenRow(0))
    }

    @Test
    fun `typed code points are encoded as UTF-8 for the transport`() {
        val session = session()

        session.writeCodePoint(false, 'a'.code)
        assertBytes(byteArrayOf(0x61), session.written.last())

        session.writeCodePoint(false, 'é'.code) // U+00E9, two bytes
        assertBytes(byteArrayOf(0xC3.toByte(), 0xA9.toByte()), session.written.last())

        session.writeCodePoint(false, 0x6F22) // 漢, three bytes
        assertBytes(byteArrayOf(0xE6.toByte(), 0xBC.toByte(), 0xA2.toByte()), session.written.last())

        session.writeCodePoint(false, 0x1F600) // emoji, four bytes
        assertBytes(byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte()), session.written.last())
    }

    @Test
    fun `an alt key is prefixed with escape`() {
        val session = session()
        session.writeCodePoint(true, 'f'.code)
        assertBytes(byteArrayOf(0x1B, 'f'.code.toByte()), session.written.last())
    }

    @Test
    fun `surrogates and out-of-range code points are rejected before encoding`() {
        val session = session()
        for (bad in listOf(0xD800, 0xDFFF, 1_114_112)) {
            assertThrows(IllegalArgumentException::class.java) { session.writeCodePoint(false, bad) }
        }
        assertTrue(session.written.isEmpty(), "nothing must reach the transport for an invalid code point")
    }

    @Test
    fun `a title from an escape sequence reaches both the session and the client`() {
        val session = session()
        session.updateSize(20, 5)
        assertNull(session.title)

        session.feed("\u001B]0;build server\u0007")
        assertEquals("build server", session.title)
        assertEquals(1, session.recordingClient.titleChanges)

        session.feed("\u001B]2;renamed\u0007")
        assertEquals("renamed", session.title)
        assertEquals(2, session.recordingClient.titleChanges)
    }

    @Test
    fun `no title means null, both before and after the emulator exists`() {
        val session = session()
        assertNull(session.title)
        session.updateSize(20, 5)
        assertNull(session.title)
    }

    @Test
    fun `reset restores the palette and always reports a change`() {
        val session = session()
        session.updateSize(10, 3)
        session.feed("\u001B[31mred\u001B]4;1;#00FF00\u0007")
        val dynamicGreen = 0xFF00FF00.toInt()
        assertEquals(dynamicGreen, session.emulator!!.colors.currentColors[1])

        val client = session.recordingClient
        val textBefore = client.textChanges
        session.reset()

        assertEquals(
            session.emulator!!.colors.scheme.defaultColors[1],
            session.emulator!!.colors.currentColors[1],
            "OSC 4 colours must go back to the theme",
        )
        assertEquals(textBefore + 1, client.textChanges, "the view must repaint after a reset")
        assertTrue(client.colorChanges >= 1, "reset reports the palette change through the client too")
    }

    @Test
    fun `reset without an emulator still notifies the client`() {
        val session = session()
        session.reset()
        assertEquals(1, session.recordingClient.textChanges)
        assertNull(session.emulator)
    }

    @Test
    fun `the output listener is optional and runs when notified`() {
        val session = session()
        session.notifyOnOutput() // no listener registered yet: must not throw

        var runs = 0
        session.setOnOutputListener { runs++ }
        session.notifyOnOutput()
        session.notifyOnOutput()
        assertEquals(2, runs)

        session.setOnOutputListener(null)
        session.notifyOnOutput()
        assertEquals(2, runs)
    }

    @Test
    fun `emulator events are forwarded to the current client`() {
        val session = session()
        session.updateSize(20, 5)
        // Creating the emulator already reports the palette once.
        val colorChangesAtStart = session.recordingClient.colorChanges
        session.onBell()
        session.onColorsChanged()
        session.onCopyTextToClipboard("text")
        session.onPasteTextFromClipboard()
        assertEquals(1, session.recordingClient.bells)
        assertEquals(colorChangesAtStart + 1, session.recordingClient.colorChanges)

        val replacement = RecordingClient()
        session.updateTerminalSessionClient(replacement)
        session.onBell()
        session.feed("\u001B]0;later\u0007")
        assertEquals(1, session.recordingClient.bells, "the old client must stop receiving events")
        assertEquals(1, replacement.bells)
        assertEquals(1, replacement.titleChanges)
        assertEquals("later", session.title)
    }

    @Test
    fun `a session without a client survives output and callbacks`() {
        val bare = RecordingSession(10, withClient = false)
        bare.updateSize(20, 5)
        bare.feed("plain output\r\n\u001B]0;ignored\u0007")
        bare.onBell()
        bare.titleChanged(null, "ignored")
        bare.reset()
        assertEquals("ignored", bare.title)
        assertEquals(0, bare.finishes)
        bare.finishIfRunning()
        assertEquals(1, bare.finishes)
    }

    @Test
    fun `every session gets its own handle`() {
        assertTrue(session().handle.isNotEmpty())
        assertTrue(session().handle != session().handle)
    }

    private fun assertBytes(expected: ByteArray, actual: ByteArray) {
        assertTrue(
            expected.contentEquals(actual),
            "expected ${expected.joinToString(",")}, got ${actual.joinToString(",")}",
        )
    }
}
