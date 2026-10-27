package com.sangiorgisrl.screenlink.ssh

import com.jcraft.jsch.JSchException
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.i18n.UiText
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The SSH layer names a message instead of building one, so a failure that
 * happened before a language change is still shown in the new language.
 */
class FriendlySshErrorTest {

    @Test
    fun `maps known failures to resources`() {
        assertEquals(
            UiText.Resource(R.string.err_host_not_found, UiText.Literal("nope.example")),
            friendlySshError(UnknownHostException("nope.example")),
        )
        assertEquals(UiText.Resource(R.string.err_host_not_found_plain), friendlySshError(UnknownHostException()))
        assertEquals(UiText.Resource(R.string.err_timeout), friendlySshError(SocketTimeoutException()))
        assertEquals(UiText.Resource(R.string.err_auth_failed), friendlySshError(JSchException("Auth fail")))
        assertEquals(
            UiText.Resource(R.string.err_refused),
            friendlySshError(ConnectException("Connection refused")),
        )
        assertEquals(
            UiText.Resource(R.string.err_timeout),
            friendlySshError(JSchException("Session.connect: java.net.SocketTimeoutException: Connect timed out")),
        )
    }

    @Test
    fun `maps a changed host key to the MITM warning`() {
        assertEquals(
            UiText.Resource(R.string.err_hostkey_changed),
            friendlySshError(JSchException("HostKey has been changed: host.example:22")),
        )
        assertEquals(
            UiText.Resource(R.string.err_hostkey_changed),
            friendlySshError(JSchException("reject HostKey: host.example")),
        )
    }

    @Test
    fun `our own trust refusals win over the library wording`() {
        // Both of these are the app refusing to send a credential. They must read
        // as a decision the user made, not as a network fault to retry.
        val challenge = HostKeyChallenge(1L, "lab", "host.example", 22, "SHA256:AAA")
        assertEquals(
            UiText.Resource(R.string.err_hostkey_not_confirmed),
            friendlySshError(HostKeyNotConfirmedException(challenge)),
        )
        assertEquals(
            UiText.Resource(R.string.err_hostkey_changed),
            friendlySshError(HostKeyProbeMismatchException(challenge, "SHA256:EVIL")),
        )
    }

    @Test
    fun `an UnknownHostException nested in a JSch message keeps the host name`() {
        val text = friendlySshError(
            JSchException("java.net.UnknownHostException: server.internal: Name or service not known"),
        ) as UiText.Resource
        assertEquals(R.string.err_host_not_found, text.id)
        assertEquals(
            UiText.Literal("server.internal: Name or service not known"),
            text.arg,
        )
    }

    @Test
    fun `server wording is never translated`() {
        // Anything we did not write stays a Literal: re-wording a server's own
        // message would misreport what it said.
        assertEquals(UiText.Literal("weird"), friendlySshError(JSchException("weird")))
        assertEquals(UiText.Literal("boom"), friendlySshError(RuntimeException("boom")))
        assertEquals(UiText.Resource(R.string.err_unexpected), friendlySshError(RuntimeException()))
        assertEquals(UiText.Literal("x"), friendlySshError(NoRouteToHostException("x")))
    }

    @Test
    fun `a connection that stopped existing is never reported as a pipe problem`() {
        // The bug this pins: the terminal used to show the library's own sentence -
        // "pipe is closed" - which names a part of the implementation the reader
        // cannot act on. Every one of these is the same event (the session died
        // underneath the call), and the user needs the network, not the JDK.
        listOf(
            "java.io.IOException: Pipe closed",
            "session is down",
            "channel is down.",
            "channel is broken",
            "inputstream is closed",
            "End of session.",
        ).forEach { message ->
            assertEquals(
                UiText.Resource(R.string.err_connection_dropped),
                friendlySshError(JSchException(message)),
                "\"$message\" reached the user verbatim",
            )
        }
        // The JDK's own words, arriving un-wrapped.
        assertEquals(
            UiText.Resource(R.string.err_connection_dropped),
            friendlySshError(java.io.IOException("Pipe closed")),
        )
        assertEquals(
            UiText.Resource(R.string.err_connection_dropped),
            friendlySshError(java.net.SocketException("Broken pipe (Write failed)")),
        )
    }
}
