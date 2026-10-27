package com.sangiorgisrl.screenlink.transfer

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpException
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.i18n.UiText
import com.sangiorgisrl.screenlink.ssh.friendlySshError
import com.sangiorgisrl.screenlink.ssh.isDeadTransportSignal

/**
 * A download was queued without a device folder.
 *
 * The screen asks for one before it queues anything, so this only fires if the job
 * outlived the state that produced it - worth naming, because "content://null" is
 * not something a user can act on.
 */
class NoDestinationException : java.io.IOException("no destination folder selected")

/**
 * The picked device folder already holds a file with the name we were about to
 * create.
 *
 * The queue resolves collisions before opening a file, but a provider can answer
 * `createDocument` with the document that is ALREADY there (several do), and
 * writing into it would truncate a file the user made. Naming this separately is
 * what keeps the message about the user's folder rather than about SSH.
 */
class DestinationExistsException(val fileName: String) :
    java.io.IOException("a file named \"$fileName\" is already in the chosen folder")

/**
 * The picked device folder refused a downloaded file: no write grant (revoked, or
 * the folder was picked read-only), a provider that takes no new files, or a URI
 * that is not a writable folder at all.
 *
 * Worth its own type because the fix is on the user's side and is one tap away -
 * re-pick the folder, or pick a different one - while "could not create file" on
 * its own reads like a bug in the app.
 */
class FolderNotWritableException(
    val fileName: String,
    cause: Throwable? = null,
) : java.io.IOException("the chosen folder does not allow writing \"$fileName\"", cause)

/**
 * The sentence family a failure is reported in.
 *
 * The SFTP *code* is the same whether the app was copying a file or only opening a
 * folder, but the sentence around it cannot be: telling someone who just tapped a
 * folder that "the copy failed", or that they lack permission to "write there",
 * sends them to fix the wrong thing. So the mapping below is shared and only the
 * wording is swapped.
 */
private class ErrorWording(
    val permission: Int,
    val connectionLost: Int,
    /**
     * Sentence for a server that refused without a code (full disk, quota, bad
     * path). Null means the server's own words stand alone, which is what a
     * message nested inside another sentence needs - otherwise the user reads
     * "Could not create the folder: the copy failed: disk quota exceeded".
     */
    val refusal: Int?,
)

private val CopyWording = ErrorWording(
    permission = R.string.err_transfer_permission,
    connectionLost = R.string.err_transfer_connection_lost,
    refusal = R.string.err_transfer_failed,
)

private val BrowseWording = ErrorWording(
    permission = R.string.err_browse_permission,
    connectionLost = R.string.err_browse_connection_lost,
    refusal = R.string.err_browse_failed,
)

/** A mkdir is a WRITE, so it keeps the write wording, nested in its own sentence. */
private val FolderCreateWording = ErrorWording(
    permission = R.string.err_transfer_permission,
    connectionLost = R.string.err_transfer_connection_lost,
    refusal = null,
)

/** Turn a failed COPY into something a user can act on. */
fun friendlyTransferError(error: Throwable): UiText = friendlyError(error, CopyWording)

/** Turn a failed folder listing into something a user can act on. */
fun friendlyBrowseError(error: Throwable): UiText = friendlyError(error, BrowseWording)

/** Turn a failed "make a folder" into something a user can act on. */
fun friendlyFolderCreateError(error: Throwable): UiText = friendlyError(error, FolderCreateWording)

/**
 * SFTP reports typed error codes (permission denied, no such file, connection
 * lost), and those are the difference between "check the folder permissions" and
 * a bare "SSH_FX_FAILURE: 4". Anything unrecognised still falls back to the app's
 * existing SSH wording, which already covers DNS, refused, timeout, auth and the
 * host-key-changed case.
 */
private fun friendlyError(error: Throwable, wording: ErrorWording): UiText {
    // The SFTP code is usually wrapped (JSch rethrows it as the cause, and wraps
    // anything our own streams throw), so walk the whole chain rather than only
    // the top exception.
    var genericSftp: SftpException? = null
    // A dead link is remembered rather than answered on the spot: the same chain
    // can hold a typed code further down ("Permission denied" wrapped inside a
    // generic failure that mentions a broken pipe), and that code is the more
    // actionable of the two.
    var deadLink: Throwable? = null
    var cause: Throwable? = error
    while (cause != null) {
        when (cause) {
            // JSch rethrows our cancel signal as the cause of an SftpException, so
            // "you cancelled this" must win over the generic failure code.
            is TransferCancelledException ->
                return UiText.Resource(R.string.err_transfer_cancelled)
            // A local collision is not a server problem: say which file, and where.
            is DestinationExistsException ->
                return UiText.Resource(R.string.err_transfer_exists, UiText.Literal(cause.fileName))
            // Neither is a folder that will not take the file. Nothing here is about
            // the server, so the SSH wording below would send the user to check the
            // wrong machine.
            is FolderNotWritableException ->
                return UiText.Resource(
                    R.string.err_folder_not_writable,
                    UiText.Literal(cause.fileName),
                )
            is SftpException -> when (cause.id) {
                ChannelSftp.SSH_FX_PERMISSION_DENIED ->
                    return UiText.Resource(wording.permission)
                ChannelSftp.SSH_FX_NO_SUCH_FILE ->
                    return UiText.Resource(R.string.err_transfer_not_found)
                ChannelSftp.SSH_FX_CONNECTION_LOST ->
                    return UiText.Resource(wording.connectionLost)
                ChannelSftp.SSH_FX_OP_UNSUPPORTED ->
                    return UiText.Resource(R.string.err_transfer_unsupported)
                // SSH_FX_FAILURE ("the server just said no") is what a full disk, a
                // quota or a bad path arrive as. Keep it as a fallback rather than
                // answering immediately: a more specific cause may still be below it.
                else -> if (genericSftp == null) genericSftp = cause
            }
            is JSchException -> if (cause.message?.contains("subsystem", ignoreCase = true) == true) {
                return UiText.Resource(R.string.err_transfer_sftp_unavailable)
            }
        }
        if (deadLink == null && isDeadTransportSignal(cause)) deadLink = cause
        cause = cause.cause
    }
    // Nothing answered us, and the pipe did: say so in the sentence of whatever
    // was in flight, because "the copy failed: Pipe closed" describes a library,
    // not the network the user can actually go and check.
    if (deadLink != null) return UiText.Resource(wording.connectionLost)
    genericSftp?.let { failure ->
        val detail = failure.message.orEmpty()
            .ifBlank { "SFTP error ${failure.id}" }
            // Our own exception text can reach here (JSch rethrows it); the class
            // name is noise the user cannot act on.
            .removePrefix("java.io.IOException: ")
        val sentence = wording.refusal ?: return UiText.Literal(detail)
        return UiText.Resource(sentence, UiText.Literal(detail))
    }
    return when {
        error is TransferCancelledException -> UiText.Resource(R.string.err_transfer_cancelled)
        error is java.io.FileNotFoundException -> UiText.Resource(R.string.err_transfer_not_found)
        error is NoDestinationException -> UiText.Resource(R.string.files_snack_no_folder)
        else -> friendlySshError(error)
    }
}
