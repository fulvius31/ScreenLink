package com.sangiorgisrl.screenlink.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpException
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.UnknownHostException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one question the app has to get right when a copy dies: did the LINK stop
 * existing, or did the server answer?
 *
 * The bug this exists for is the message users report as "pipe is closed". It is
 * not one error but a family: JSch feeds an SFTP channel's replies through a
 * [java.io.PipedInputStream], so a session that dies mid-request surfaces as
 * "Pipe closed", "inputstream is closed", "session is down" or "channel is down"
 * depending on where it was noticed. Every one of them must be recognised, because
 * the answer decides both the sentence the user reads and whether the app is
 * allowed to reconnect and run the call again.
 */
class DeadTransportTest {

    @Test
    fun `the JDK pipe family is a dead link`() {
        // These are java.io.PipedInputStream / PipedOutputStream's own words, which
        // is exactly what reaches the screen as "pipe is closed".
        listOf(
            "Pipe closed",
            "Pipe broken",
            "Pipe not connected",
            "Read end dead",
            "Write end dead",
            "Broken pipe",
        ).forEach { message ->
            assertTrue(isDeadTransport(IOException(message)), "\"$message\" is a dead link")
            assertTrue(isDeadTransportSignal(IOException(message)), "\"$message\" is a dead link")
        }
    }

    @Test
    fun `JSch's words for a gone session or channel are a dead link`() {
        listOf(
            "session is down",
            "channel is down",
            "channel is broken",
            "channel is not opened.",
            "inputstream is closed",
            "End of session.",
        ).forEach { message ->
            assertTrue(isDeadTransport(JSchException(message)), "\"$message\" is a dead link")
        }
    }

    @Test
    fun `a socket that stopped is a dead link`() {
        listOf(
            SocketException("Connection reset"),
            SocketException("Broken pipe (Write failed)"),
            SocketException("Socket closed"),
            SocketException("Software caused connection abort"),
            // What Android throws for a copy that outlived its Wi-Fi: the route is
            // gone mid-transfer, which is a link that died, not a server that said no.
            SocketException("Network is unreachable"),
            EOFException("unexpected end of stream"),
        ).forEach { error ->
            assertTrue(isDeadTransport(error), "$error is a dead link")
        }
    }

    @Test
    fun `the typed SFTP code for a lost connection counts too`() {
        assertTrue(
            isDeadTransport(SftpException(ChannelSftp.SSH_FX_CONNECTION_LOST, "channel is down.")),
        )
    }

    @Test
    fun `a dead link nested inside a generic SFTP failure still counts`() {
        // This is the shape JSch actually throws: our stream's IOException comes
        // back as the cause of a codeless SftpException.
        val wrapped = SftpException(
            ChannelSftp.SSH_FX_FAILURE,
            "java.io.IOException: Pipe closed",
            IOException("Pipe closed"),
        )
        assertTrue(isDeadTransport(wrapped))
    }

    @Test
    fun `a server that answered is not a dead link`() {
        // Retrying any of these would only waste a handshake: the server said no,
        // and it will say no again.
        assertFalse(isDeadTransport(SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "Permission denied")))
        assertFalse(isDeadTransport(SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "no such file")))
        assertFalse(isDeadTransport(SftpException(ChannelSftp.SSH_FX_FAILURE, "Disk quota exceeded")))
        assertFalse(isDeadTransport(JSchException("Auth fail")))
        assertFalse(isDeadTransport(JSchException("reject HostKey: host.example")))
        assertFalse(isDeadTransport(FileNotFoundException("content://share/42")))
        assertFalse(isDeadTransport(IOException("the chosen folder does not allow writing \"x\"")))
        assertFalse(isDeadTransport(UnknownHostException("nope.example")))
        assertFalse(isDeadTransport(null))
    }

    @Test
    fun `a refused connection is the network answering, not a link that died`() {
        // All three ARE SocketExceptions, and the sentence for each is the opposite
        // one ("nothing is listening there", "nothing routes to that host"), so they
        // must stay out of the family: re-dialling a host that just told us it is not
        // there only makes the user wait longer for the same answer.
        assertFalse(isDeadTransport(ConnectException("Connection refused")))
        assertFalse(isDeadTransport(NoRouteToHostException("No route to host")))
        assertFalse(isDeadTransport(PortUnreachableException("Port unreachable")))
        assertFalse(isDeadTransportSignal(NoRouteToHostException("No route to host")))
    }

    @Test
    fun `a cancellation is not a dead link`() {
        assertFalse(isDeadTransport(IOException("transfer cancelled")))
    }

    @Test
    fun `a self-referencing cause chain cannot hang the error path`() {
        // getCause is overridable, and an error path that loops forever turns a
        // failure into an ANR. The walk is bounded instead of trusted.
        val loop = object : IOException("Pipe closed") {
            override val cause: Throwable get() = this
        }
        assertTrue(isDeadTransport(loop))

        val silentLoop = object : IOException("something else") {
            override val cause: Throwable get() = this
        }
        assertFalse(isDeadTransport(silentLoop))
    }
}
