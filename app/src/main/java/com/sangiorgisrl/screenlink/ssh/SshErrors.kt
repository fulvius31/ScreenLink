package com.sangiorgisrl.screenlink.ssh

import com.jcraft.jsch.JSchException
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.i18n.UiText
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Turn a thrown SSH/IO exception into a short, human friendly message.
 *
 * Returns a [UiText] rather than a String: the message is produced on a worker
 * thread long before it is shown, and the language may change in between. Only
 * text that comes from the server itself stays a [UiText.Literal] - translating
 * a server's own words would misreport what it said.
 */
fun friendlySshError(error: Throwable): UiText {
    val message = error.message ?: ""
    return when (error) {
        // Our own trust decisions first: these are not failures to retry, they are
        // the app refusing to send a credential, and the screen has to say so plainly.
        is HostKeyNotConfirmedException ->
            UiText.Resource(R.string.err_hostkey_not_confirmed)
        is HostKeyProbeMismatchException ->
            UiText.Resource(R.string.err_hostkey_changed)
        is UnknownHostException ->
            if (message.isBlank()) UiText.Resource(R.string.err_host_not_found_plain)
            else UiText.Resource(R.string.err_host_not_found, UiText.Literal(message))
        is SocketTimeoutException -> UiText.Resource(R.string.err_timeout)
        // JSch usually wraps these, but a plain socket failure reaching us directly
        // still deserves the friendly wording rather than the raw exception message.
        is ConnectException ->
            if (message.contains("refused", ignoreCase = true) || message.isBlank()) {
                UiText.Resource(R.string.err_refused)
            } else {
                UiText.Literal(message)
            }
        is JSchException -> when {
            message.contains("Auth fail", ignoreCase = true) ->
                UiText.Resource(R.string.err_auth_failed)
            message.contains("UnknownHostException", ignoreCase = true) ->
                UiText.Resource(
                    R.string.err_host_not_found,
                    UiText.Literal(message.substringAfter("UnknownHostException:", message).trim()),
                )
            message.contains("ConnectException", ignoreCase = true) &&
                message.contains("refused", ignoreCase = true) -> UiText.Resource(R.string.err_refused)
            message.lowercase().contains("timed out") -> UiText.Resource(R.string.err_timeout)
            message.contains("no such file", ignoreCase = true) ->
                UiText.Resource(R.string.err_server_closed)
            message.contains("HostKey has been changed", ignoreCase = true) ||
                message.contains("reject HostKey", ignoreCase = true) ->
                UiText.Resource(R.string.err_hostkey_changed)
            // "pipe is closed", "session is down", "inputstream is closed",
            // "channel is down": all one event - the connection stopped existing
            // underneath the call. Left alone, each of those library sentences
            // reaches the screen verbatim, in English, and names a part of the
            // implementation nobody reading it can act on.
            isDeadTransport(error) -> UiText.Resource(R.string.err_connection_dropped)
            message.isBlank() -> UiText.Resource(R.string.err_ssh_failed)
            else -> UiText.Literal(message)
        }
        else -> when {
            isDeadTransport(error) -> UiText.Resource(R.string.err_connection_dropped)
            message.isBlank() -> UiText.Resource(R.string.err_unexpected)
            else -> UiText.Literal(message)
        }
    }
}
