package com.sangiorgisrl.screenlink.transfer

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpException
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.i18n.UiText
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What the user reads when a copy fails.
 *
 * The whole point of this layer is that "SSH_FX_FAILURE: 4" is not an answer: the
 * SFTP error code decides whether the user should check folder permissions, pick
 * another folder, or re-enter their password. JSch also rethrows our own cancel
 * signal inside a generic SftpException, so the ordering of these checks is part
 * of the contract (a cancelled copy must never be labelled "failed").
 */
class TransferErrorsTest {

    @Test
    fun `typed SFTP codes become actionable messages`() {
        assertEquals(
            UiText.Resource(R.string.err_transfer_permission),
            friendlyTransferError(SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "Permission denied")),
        )
        assertEquals(
            UiText.Resource(R.string.err_transfer_not_found),
            friendlyTransferError(SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "no such file")),
        )
        assertEquals(
            UiText.Resource(R.string.err_transfer_connection_lost),
            friendlyTransferError(SftpException(ChannelSftp.SSH_FX_CONNECTION_LOST, "lost")),
        )
        assertEquals(
            UiText.Resource(R.string.err_transfer_unsupported),
            friendlyTransferError(SftpException(ChannelSftp.SSH_FX_OP_UNSUPPORTED, "unsupported")),
        )
    }

    @Test
    fun `a code that is only wrapped deeper still maps correctly`() {
        // JSch rethrows the real failure as the cause of a generic one.
        val wrapped = SftpException(
            ChannelSftp.SSH_FX_FAILURE,
            "java.io.IOException: broken pipe",
            SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "Permission denied"),
        )

        assertEquals(UiText.Resource(R.string.err_transfer_permission), friendlyTransferError(wrapped))
    }

    @Test
    fun `an unexplained server refusal keeps the server's words as the detail`() {
        val text = friendlyTransferError(SftpException(ChannelSftp.SSH_FX_FAILURE, "Disk quota exceeded"))

        assertEquals(
            UiText.Resource(R.string.err_transfer_failed, UiText.Literal("Disk quota exceeded")),
            text,
        )
    }

    @Test
    fun `a refusal with no message still says something`() {
        val text = friendlyTransferError(SftpException(ChannelSftp.SSH_FX_FAILURE, ""))

        assertEquals(
            UiText.Resource(R.string.err_transfer_failed, UiText.Literal("SFTP error 4")),
            text,
        )
    }

    @Test
    fun `a cancelled copy is never reported as a failure`() {
        assertEquals(
            UiText.Resource(R.string.err_transfer_cancelled),
            friendlyTransferError(TransferCancelledException()),
        )
        // The real shape: JSch's pump wraps whatever our stream threw. The cancel
        // must win over the generic failure code sitting on top of it.
        val jschShape = SftpException(
            ChannelSftp.SSH_FX_FAILURE,
            "java.io.IOException: transfer cancelled",
            TransferCancelledException(),
        )
        assertEquals(UiText.Resource(R.string.err_transfer_cancelled), friendlyTransferError(jschShape))
    }

    @Test
    fun `a server without the SFTP subsystem says so in copy terms`() {
        assertEquals(
            UiText.Resource(R.string.err_transfer_sftp_unavailable),
            friendlyTransferError(JSchException("channel is not opened, but it is connected: server request failed for subsystem: sftp")),
        )
    }

    @Test
    fun `a clash in the user's own folder is not blamed on the server`() {
        // Several SAF providers answer createDocument with the document that is
        // ALREADY there; writing into it would truncate a file the user made, so
        // this is its own failure, worded about the folder rather than about SSH.
        assertEquals(
            UiText.Resource(R.string.err_transfer_exists, UiText.Literal("plan.pdf")),
            friendlyTransferError(DestinationExistsException("plan.pdf")),
        )
        // The real shape: the gateway wraps it in an IOException.
        assertEquals(
            UiText.Resource(R.string.err_transfer_exists, UiText.Literal("plan.pdf")),
            friendlyTransferError(IOException("could not create a file", DestinationExistsException("plan.pdf"))),
        )
    }

    @Test
    fun `a device folder that will not take the file says what to do about it`() {
        // The bug this pins: a SAF create failure fell through to the SSH wording, so
        // a problem with the user's own folder was reported as a server problem (and
        // in English only, because the raw exception message was shown verbatim).
        assertEquals(
            UiText.Resource(R.string.err_folder_not_writable, UiText.Literal("dossier.md")),
            friendlyTransferError(FolderNotWritableException("dossier.md")),
        )
        // The real shape: a SecurityException from the provider, wrapped by the queue.
        assertEquals(
            UiText.Resource(R.string.err_folder_not_writable, UiText.Literal("dossier.md")),
            friendlyTransferError(
                IOException(
                    "boom",
                    FolderNotWritableException("dossier.md", SecurityException("no write grant")),
                ),
            ),
        )
        // A cancelled copy still wins: the folder wording must not steal it.
        assertEquals(
            UiText.Resource(R.string.err_transfer_cancelled),
            friendlyTransferError(IOException("aborted", TransferCancelledException())),
        )
    }

    @Test
    fun `a local file that vanished reads as missing, not as a mystery error`() {
        assertEquals(
            UiText.Resource(R.string.err_transfer_not_found),
            friendlyTransferError(FileNotFoundException("content://x/share-1.pdf")),
        )
    }

    @Test
    fun `opening a folder is never reported as a failed copy`() {
        // The user tapped a folder: nothing was copying, and nothing was being
        // written. "The copy failed" / "no permission to write there" sends them to
        // fix something that was never involved.
        assertEquals(
            UiText.Resource(R.string.err_browse_permission),
            friendlyBrowseError(SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "Permission denied")),
        )
        assertEquals(
            UiText.Resource(R.string.err_browse_connection_lost),
            friendlyBrowseError(SftpException(ChannelSftp.SSH_FX_CONNECTION_LOST, "lost")),
        )
        assertEquals(
            UiText.Resource(R.string.err_browse_failed, UiText.Literal("Disk quota exceeded")),
            friendlyBrowseError(SftpException(ChannelSftp.SSH_FX_FAILURE, "Disk quota exceeded")),
        )
        // A code that is neutral in both directions stays shared.
        assertEquals(
            UiText.Resource(R.string.err_transfer_not_found),
            friendlyBrowseError(SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "no such file")),
        )
        assertEquals(
            UiText.Resource(R.string.err_timeout),
            friendlyBrowseError(SocketTimeoutException()),
        )
    }

    @Test
    fun `a failed mkdir keeps the write wording but does not call itself a copy`() {
        // The sentence is already "Could not create the folder: ...", so the detail
        // must not re-wrap it in another sentence.
        assertEquals(
            UiText.Resource(R.string.err_transfer_permission),
            friendlyFolderCreateError(SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "Permission denied")),
        )
        assertEquals(
            UiText.Literal("Disk quota exceeded"),
            friendlyFolderCreateError(SftpException(ChannelSftp.SSH_FX_FAILURE, "Disk quota exceeded")),
        )
    }

    @Test
    fun `a link that died is reported as the network, in the sentence of what was in flight`() {
        // The message users reported ("pipe is closed") is this chain reaching the
        // screen verbatim: JSch nests the JDK's pipe error inside a codeless SFTP
        // failure, so nothing typed is left to map and the library's own sentence -
        // about a pipe inside the app, in English - is what gets shown.
        val jschShape = SftpException(
            ChannelSftp.SSH_FX_FAILURE,
            "java.io.IOException: Pipe closed",
            IOException("Pipe closed"),
        )
        assertEquals(
            UiText.Resource(R.string.err_transfer_connection_lost),
            friendlyTransferError(jschShape),
        )
        // Opening a folder is not a copy, and making a folder is not a copy either.
        assertEquals(
            UiText.Resource(R.string.err_browse_connection_lost),
            friendlyBrowseError(jschShape),
        )
        assertEquals(
            UiText.Resource(R.string.err_transfer_connection_lost),
            friendlyFolderCreateError(jschShape),
        )
        // Un-wrapped: the JDK's and JSch's own words, arriving on their own.
        assertEquals(
            UiText.Resource(R.string.err_transfer_connection_lost),
            friendlyTransferError(IOException("Pipe closed")),
        )
        assertEquals(
            UiText.Resource(R.string.err_browse_connection_lost),
            friendlyBrowseError(JSchException("session is down")),
        )
        // A typed code further down the chain is still the more actionable answer:
        // "the connection dropped" would send the user to check the wrong thing.
        assertEquals(
            UiText.Resource(R.string.err_transfer_permission),
            friendlyTransferError(
                SftpException(
                    ChannelSftp.SSH_FX_FAILURE,
                    "java.io.IOException: Broken pipe",
                    SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "Permission denied"),
                ),
            ),
        )
        // A network that ANSWERED is not a dropped link, and keeps its own sentence.
        assertEquals(
            UiText.Resource(R.string.err_refused),
            friendlyTransferError(ConnectException("Connection refused")),
        )
    }

    @Test
    fun `anything else falls back to the app's existing SSH wording`() {
        // The transfer layer must not reinvent the connection-error wording.
        assertEquals(UiText.Resource(R.string.err_timeout), friendlyTransferError(SocketTimeoutException()))
        assertEquals(
            UiText.Resource(R.string.err_auth_failed),
            friendlyTransferError(JSchException("Auth fail")),
        )
        assertEquals(
            UiText.Literal("share provider closed the file"),
            friendlyTransferError(IOException("share provider closed the file")),
        )
        assertEquals(UiText.Resource(R.string.err_unexpected), friendlyTransferError(IOException()))
    }
}
