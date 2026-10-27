package com.sangiorgisrl.screenlink.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.sangiorgisrl.screenlink.data.SshConnection
import com.sangiorgisrl.screenlink.data.SshKeyCredential
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The trust policy in [SshConnector], with no server anywhere.
 *
 * The fake gateway records the ORDER of everything that happened, because the
 * security claim here is an ordering claim: the host key must be learned, shown
 * and pinned before the password or private key is put on the wire.
 */
class SshConnectorTest {

    private val keys = listOf(SshKeyCredential(1L, "id_ed25519", "PRIVATE KEY MATERIAL", ""))

    private fun connection(pinned: String?) = SshConnection(
        id = 42L,
        displayName = "lab",
        username = "operator",
        host = "host.example",
        port = 22,
        password = "hunter2",
        screenSessionName = null,
        serverFingerprint = pinned,
    )

    /** Every call the connector makes on the SSH layer, in the order it made them. */
    private val trace = mutableListOf<String>()

    private class FakeGateway(
        val probed: String = "SHA256:PROBED",
        val live: String = probed,
        val legacy: String = "LEGACY:PROBED",
        val probeFailure: String? = null,
        val trace: MutableList<String>,
    ) : SshGateway {
        val jsch = JSch()
        var connectSaw: SshConnection? = null
        var connectSawKeys: List<SshKeyCredential>? = null
        var probeSaw: SshConnection? = null

        override fun connect(
            connection: SshConnection,
            keys: List<SshKeyCredential>,
            timeoutMs: Int,
        ): Session {
            trace += "connect"
            connectSaw = connection
            connectSawKeys = keys
            // A real Session object that was never connected: the connector only
            // hands it back and asks the gateway about it, so no socket is involved.
            return jsch.getSession(connection.username, connection.host, connection.port)
        }

        override fun probeHostKey(connection: SshConnection, timeoutMs: Int): String {
            trace += "probe"
            probeSaw = connection
            probeFailure?.let { throw JSchException(it) }
            return probed
        }

        override fun serverFingerprint(session: Session): String {
            trace += "fingerprint"
            return live
        }

        override fun serverFingerprintLegacy(session: Session): String = legacy

        override fun openSftp(session: Session, timeoutMs: Int): ChannelSftp =
            throw UnsupportedOperationException("file transfer is not part of the trust decision")
    }

    private class FakeConfirmation(
        val trace: MutableList<String>,
        private val answer: (HostKeyChallenge) -> Boolean = { true },
    ) : HostKeyConfirmation {
        val asked = mutableListOf<HostKeyChallenge>()

        override suspend fun confirm(challenge: HostKeyChallenge): Boolean {
            trace += "ask"
            asked += challenge
            return answer(challenge)
        }
    }

    private val pins = mutableListOf<Pair<Long, String>>()

    private fun connector(
        gateway: SshGateway,
        confirmation: HostKeyConfirmation,
        confirmWindowMs: Long = SshConnector.CONFIRM_WINDOW_MS,
    ) = SshConnector(
        ssh = gateway,
        rebindFingerprint = { id, fingerprint ->
            trace += "pin"
            pins += id to fingerprint
        },
        confirmation = confirmation,
        confirmWindowMs = confirmWindowMs,
    )

    @Test
    fun `an already pinned host connects without asking anyone`() = runBlocking {
        val gateway = FakeGateway(live = "SHA256:AAA", legacy = "LEGACY:AAA", trace = trace)
        val confirmation = FakeConfirmation(trace, answer = { error("a known host must not be questioned") })

        connector(gateway, confirmation).connect(connection("SHA256:AAA"), keys)

        assertEquals(listOf("connect", "fingerprint"), trace)
        assertEquals(keys, gateway.connectSawKeys, "the credential must be delivered once the pin vouches for the key")
        assertTrue(pins.isEmpty(), "a matching pin must not be rewritten")
    }

    @Test
    fun `first contact learns and confirms the key before any credential exists`() = runBlocking {
        val gateway = FakeGateway(trace = trace)
        val confirmation = FakeConfirmation(trace, answer = { challenge ->
            // The whole point: at the moment the user is asked, nothing has been
            // authenticated and nothing has been stored.
            assertEquals(listOf("probe", "ask"), trace)
            assertNull(gateway.connectSaw, "no session may exist before the answer")
            assertEquals("SHA256:PROBED", challenge.fingerprint)
            assertEquals("lab", challenge.connectionName)
            assertEquals("host.example", challenge.host)
            assertEquals(22, challenge.port)
            true
        })

        connector(gateway, confirmation).connect(connection(null), keys)

        // probe -> ask -> pin -> connect: the pin is in place before the password.
        assertEquals(listOf("probe", "ask", "pin", "connect", "fingerprint"), trace)
        assertEquals(listOf(42L to "SHA256:PROBED"), pins)
        assertEquals("SHA256:PROBED", gateway.connectSaw?.serverFingerprint, "the real handshake must be checked against the confirmed key")
        assertEquals(keys, gateway.connectSawKeys)
    }

    @Test
    fun `the probe itself never carries a credential`() = runBlocking {
        // The probe is a key-exchange-only connection: if it ever reached the
        // authentication stage with our password attached, first contact would be
        // asking the question after the answer had already left the device.
        val gateway = FakeGateway(trace = trace)
        val confirmation = FakeConfirmation(trace)

        connector(gateway, confirmation).connect(connection(null), keys)

        assertEquals(connection(null), gateway.probeSaw, "the probe runs against the record as saved, before any pin")
        assertNull(gateway.probeSaw?.serverFingerprint, "the probe is given no pin to enforce and no credential to offer")
        assertEquals(listOf("probe", "ask", "pin", "connect", "fingerprint"), trace, "the probe is its own connection, taken before the real one")
    }

    @Test
    fun `refusing the key stops before the credential is sent`() {
        val gateway = FakeGateway(trace = trace)
        val confirmation = FakeConfirmation(trace, answer = { false })

        val error = assertThrows(HostKeyNotConfirmedException::class.java) {
            runBlocking { connector(gateway, confirmation).connect(connection(null), keys) }
        }


        assertEquals(listOf("probe", "ask"), trace)
        assertNull(gateway.connectSaw, "a refusal must never open an authenticated session")
        assertTrue(pins.isEmpty(), "a refused key must not be pinned")
        assertTrue(error.message!!.contains("host.example"), "the error has to say which host was refused")
        assertEquals("SHA256:PROBED", error.challenge.fingerprint)
    }

    @Test
    fun `an unanswered question is a refusal, not a pass`() {
        // A connection can start from a copy queue with no screen showing the
        // dialog; waiting forever would hang the queue, and timing out into a
        // successful connect would be the exact hole this closes.
        val gateway = FakeGateway(trace = trace)
        val silent = object : HostKeyConfirmation {
            override suspend fun confirm(challenge: HostKeyChallenge): Nothing {
                trace += "ask"
                return awaitCancellation()
            }
        }

        val error = assertThrows(HostKeyNotConfirmedException::class.java) {
            runBlocking { connector(gateway, silent, confirmWindowMs = 50L).connect(connection(null), keys) }
        }

        assertEquals(listOf("probe", "ask"), trace)
        assertNull(gateway.connectSaw)
        assertTrue(pins.isEmpty())
        assertTrue(error.message!!.isNotBlank())
    }

    @Test
    fun `a key that changes between the probe and the connection aborts`() {
        // Two different keys from one host is the man-in-the-middle shape. The pin
        // just written must not end up vouching for a key this session is not using.
        val gateway = FakeGateway(probed = "SHA256:GOOD", live = "SHA256:EVIL", trace = trace)
        val confirmation = FakeConfirmation(trace)

        val error = assertThrows(HostKeyProbeMismatchException::class.java) {
            runBlocking { connector(gateway, confirmation).connect(connection(null), keys) }
        }

        assertEquals(listOf("probe", "ask", "pin", "connect", "fingerprint"), trace)
        assertEquals(
            listOf(42L to "SHA256:GOOD"),
            pins,
            "the confirmed key stays the pin; the other key is never trusted",
        )
        assertTrue(error.message!!.contains("SHA256:GOOD"), error.message!!)
        assertTrue(error.message!!.contains("SHA256:EVIL"), error.message!!)
    }

    @Test
    fun `a failed probe asks nothing and connects nothing`() {
        // DNS failure, refused port, timeout: no host key was ever learned, so there
        // is nothing to show the user and nothing to connect with.
        val gateway = FakeGateway(probeFailure = "java.net.UnknownHostException", trace = trace)
        val confirmation = FakeConfirmation(trace, answer = { error("nothing was learned, so nothing can be confirmed") })

        assertThrows(JSchException::class.java) {
            runBlocking { connector(gateway, confirmation).connect(connection(null), keys) }
        }

        assertEquals(listOf("probe"), trace)
        assertTrue(confirmation.asked.isEmpty())
        assertNull(gateway.connectSaw)
    }

    @Test
    fun `a legacy pin is upgraded without questioning the user`() = runBlocking {
        // Builds <= 1.0.x pinned a different fingerprint format for the SAME key:
        // that is a migration, not first contact, and must not nag.
        val gateway = FakeGateway(live = "SHA256:AAA", legacy = "LEGACY:AAA", trace = trace)
        val confirmation = FakeConfirmation(trace, answer = { error("a known host must not be questioned") })

        connector(gateway, confirmation).connect(connection("LEGACY:AAA"), keys)

        assertEquals(listOf("connect", "fingerprint", "pin"), trace)
        assertEquals(listOf(42L to "SHA256:AAA"), pins, "the pin is upgraded to the standard format")
    }

    @Test
    fun `a changed pin is never quietly rewritten`() = runBlocking {
        // The handshake normally rejects this first; if a session ever comes back
        // anyway, the stored pin must still be the old one, not the new key.
        val gateway = FakeGateway(live = "SHA256:EVIL", legacy = "LEGACY:EVIL", trace = trace)
        val confirmation = FakeConfirmation(trace, answer = { error("a pinned host must not be questioned") })

        connector(gateway, confirmation).connect(connection("SHA256:OLD"), keys)

        assertEquals(listOf("connect", "fingerprint"), trace)
        assertTrue(pins.isEmpty(), "overwriting the pin here would erase the evidence of the attack")
    }
}
