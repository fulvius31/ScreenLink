package com.sangiorgisrl.screenlink.transfer

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpException
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When the app is allowed to reconnect and run a call again.
 *
 * This decision is the difference between an app that shrugs off a phone whose
 * Wi-Fi slept and one that hammers a server that is simply down, so the boundary
 * is written down here rather than left inside an `if`.
 */
class RetryPolicyTest {

    /** The shape JSch actually throws when an SFTP channel's pipe dies mid-call. */
    private fun deadLink(): Throwable = SftpException(
        ChannelSftp.SSH_FX_FAILURE,
        "java.io.IOException: Pipe closed",
        IOException("Pipe closed"),
    )

    @Test
    fun `a link that died under the first attempt is run once more`() {
        assertTrue(RetryPolicy.shouldRetry(deadLink(), attempt = 1))
        assertTrue(RetryPolicy.shouldRetry(JSchException("session is down"), attempt = 1))
        assertTrue(RetryPolicy.shouldRetry(IOException("Broken pipe"), attempt = 1))
    }

    @Test
    fun `the same call is never retried twice`() {
        // A server that is down is down: one reconnect is the fix, a loop is an
        // outage multiplied by a hundred handshakes.
        assertFalse(RetryPolicy.shouldRetry(deadLink(), attempt = 2))
        assertFalse(RetryPolicy.shouldRetry(deadLink(), attempt = 3))
    }

    @Test
    fun `an attempt number out of range is not a retry`() {
        // A 0 would mean "nothing has failed yet", and retrying on it would restart
        // a copy that was still going.
        assertFalse(RetryPolicy.shouldRetry(deadLink(), attempt = 0))
        assertFalse(RetryPolicy.shouldRetry(deadLink(), attempt = -1))
    }

    @Test
    fun `a failure the server answered is never dialled again`() {
        listOf(
            SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "Permission denied"),
            SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "no such file"),
            SftpException(ChannelSftp.SSH_FX_FAILURE, "Disk quota exceeded"),
            JSchException("Auth fail"),
            ConnectException("Connection refused"),
            IOException("the chosen folder does not allow writing \"report.pdf\""),
        ).forEach { error ->
            assertFalse(RetryPolicy.shouldRetry(error, attempt = 1), "retried $error")
        }
    }

    @Test
    fun `a copy the user stopped is never started again`() {
        // Both shapes of "stop": the coroutine's own cancellation, and the app's
        // cancel signal that JSch rethrows inside an SftpException.
        assertFalse(RetryPolicy.shouldRetry(CancellationException(), attempt = 1))
        assertFalse(RetryPolicy.shouldRetry(TransferCancelledException(), attempt = 1))
        assertFalse(
            RetryPolicy.shouldRetry(
                SftpException(ChannelSftp.SSH_FX_FAILURE, "cancelled", TransferCancelledException()),
                attempt = 1,
            ),
            "a cancelled copy was queued again",
        )
    }

    @Test
    fun `the budget is one retry`() {
        // Anything wider turns one flaky network into a burst of connections, and
        // the queue's own wording ("reconnecting") promises exactly one second try.
        assertTrue(RetryPolicy.shouldRetry(deadLink(), attempt = RetryPolicy.MAX_ATTEMPTS - 1))
        assertFalse(RetryPolicy.shouldRetry(deadLink(), attempt = RetryPolicy.MAX_ATTEMPTS))
    }
}
