package com.sangiorgisrl.screenlink.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException

/**
 * Did the LINK die, or did the server actually answer?
 *
 * The two need different answers and the app cannot tell them apart by type: JSch
 * reports a session that vanished underneath it as a plain `SftpException` with a
 * generic code, and the JDK reports the same thing as `IOException("Pipe closed")`,
 * because an SFTP channel receives its replies through a pipe that the reader
 * thread feeds. So the honest wording for the user - "the connection dropped, try
 * again" - has to come from matching the family of messages, not one class.
 *
 * This matters twice over: it is what the user reads, and it is what decides
 * whether the app may quietly reconnect and run the call again (see
 * [com.sangiorgisrl.screenlink.transfer.RetryPolicy]). A "permission denied" must
 * never be retried; a closed pipe must.
 */

/**
 * Phrases that mean "the transport stopped existing" rather than "the server said no".
 *
 * Lower-cased, matched with [contains] because JSch nests the real cause inside its
 * own sentence ("java.io.IOException: Broken pipe") and the JDK's own text is
 * prefixed by the exception class name.
 */
private val DEAD_LINK_MARKERS = listOf(
    // java.io.PipedInputStream / PipedOutputStream: the pipe that carries an SFTP
    // channel's replies. This is what "pipe is closed" on screen really is.
    "pipe closed",
    "pipe broken",
    "pipe not connected",
    "read end dead",
    "write end dead",
    "broken pipe",
    // JSch's own words for a session or channel that is gone.
    "session is down",
    "channel is down",
    "channel is broken",
    "channel is not opened",
    "inputstream is closed",
    "stream is closed",
    "end of session",
    // The socket underneath everything.
    "connection reset",
    "socket closed",
    "connection abort",
    "unexpected end of stream",
)

/**
 * Failures where the NETWORK ANSWERED rather than dying under us.
 *
 * All three are [SocketException]s, so without this the type rule below would pull
 * them into the dead-link family - and both answers would then be wrong twice over:
 * the sentence ("nothing is reachable there") is nothing like "the connection
 * dropped", and a retry only spends another handshake on a host that just told us
 * it is not there.
 */
private fun isNetworkAnswer(error: Throwable): Boolean =
    error is ConnectException ||
        error is NoRouteToHostException ||
        error is PortUnreachableException

/**
 * True when THIS exception (and only it) is a dead-link signal.
 *
 * Kept separate from [isDeadTransport] so a caller that is already walking the
 * cause chain - the error wording does exactly that - can ask about one link at a
 * time and still let a more specific cause further down win.
 */
fun isDeadTransportSignal(error: Throwable): Boolean {
    // Everything else that is a raw socket failure IS the link stopping: a reset, a
    // broken pipe, "Network is unreachable" after the Wi-Fi dropped, a closed socket.
    if (error is SocketException && !isNetworkAnswer(error)) return true
    if (error is EOFException) return true
    if (error is SftpException && error.id == ChannelSftp.SSH_FX_CONNECTION_LOST) return true
    val message = error.message?.lowercase() ?: return false
    return DEAD_LINK_MARKERS.any(message::contains)
}

/**
 * True when anything in [error]'s chain says the connection died.
 *
 * The chain is walked rather than trusted at the top: JSch rethrows our streams,
 * the JDK's pipe errors and the socket itself as the *cause* of a generic SFTP
 * failure, so the top exception is usually the least informative one.
 */
fun isDeadTransport(error: Throwable?): Boolean {
    var cause: Throwable? = error
    var depth = 0
    while (cause != null && depth < MAX_CAUSE_DEPTH) {
        if (isDeadTransportSignal(cause)) return true
        cause = cause.cause
        depth++
    }
    return false
}

/**
 * Bound on the walk.
 *
 * A `cause` chain is supposed to be finite, but `getCause` is overridable and a
 * self-referencing exception would otherwise hang the thread that is only trying
 * to name the failure - and a hung error path is how a crash becomes an ANR.
 */
private const val MAX_CAUSE_DEPTH = 32
