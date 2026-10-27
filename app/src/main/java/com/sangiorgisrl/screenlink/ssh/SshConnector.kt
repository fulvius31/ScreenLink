package com.sangiorgisrl.screenlink.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import com.sangiorgisrl.screenlink.data.SshConnection
import com.sangiorgisrl.screenlink.data.SshKeyCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one place that connects *and* applies the host-key trust policy.
 *
 * [SshGateway.connect] refuses a changed host key during the handshake (before any
 * password or key is sent); this adds the other half of trust-on-first-use:
 *
 *  - **already pinned** - connect, and let the handshake enforce the pin.
 *  - **first contact** - probe the key without authenticating, ask the user to
 *    confirm it, persist the pin, and only then connect with credentials, against
 *    the confirmed key. Nothing leaves the device before the answer.
 *  - **key moved under us** - if the credential-bearing handshake presents a
 *    different key from the one just confirmed, abort before authenticating.
 *
 * Every entry point (terminal, overview, file transfer) goes through here, so no
 * feature can quietly skip either step.
 */
class SshConnector(
    private val ssh: SshGateway,
    private val rebindFingerprint: suspend (connectionId: Long, fingerprint: String) -> Unit,
    private val confirmation: HostKeyConfirmation,
    /**
     * How long to wait for the first-contact answer. Injectable so the "never
     * answered" path is testable without a two-minute sleep.
     */
    private val confirmWindowMs: Long = CONFIRM_WINDOW_MS,
) {

    companion object {
        /**
         * How long a first-contact question may stay unanswered.
         *
         * A connection can be started from a copy queue or a background tab while no
         * screen is showing the dialog, so "wait forever" is a hung transfer rather
         * than a safe one. Giving up is a refusal: no credential was ever sent.
         */
        const val CONFIRM_WINDOW_MS = 120_000L
    }

    suspend fun connect(
        connection: SshConnection,
        keys: List<SshKeyCredential> = emptyList(),
        timeoutMs: Int = 15_000,
    ): Session {
        var target = connection
        var confirmed: HostKeyChallenge? = null

        if (FirstContactTrust.mustConfirm(connection.serverFingerprint)) {
            val probed = withContext(Dispatchers.IO) { ssh.probeHostKey(connection, timeoutMs) }
            val challenge = HostKeyChallenge(
                connectionId = connection.id,
                connectionName = connection.displayName.ifBlank { connection.host },
                host = connection.host,
                port = connection.port,
                fingerprint = probed,
            )
            val accepted = withTimeoutOrNull(confirmWindowMs) {
                confirmation.confirm(challenge)
            } == true
            if (!accepted) throw HostKeyNotConfirmedException(challenge)

            // Pin what was just confirmed BEFORE connecting: from here on the real
            // handshake is checked against it, so the connection that carries the
            // password is a verified one.
            confirmed = challenge
            target = connection.copy(serverFingerprint = probed)
            rebindFingerprint(target.id, probed)
        }

        val session = withContext(Dispatchers.IO) { ssh.connect(target, keys, timeoutMs) }
        try {
            val verdict = withContext(Dispatchers.IO) {
                val fresh = ssh.serverFingerprint(session)
                val legacy = ssh.serverFingerprintLegacy(session)
                Triple(fresh, legacy, HostKeyTrust.verdict(fresh, legacy, target.serverFingerprint))
            }
            // The probe and the real connection must agree. Two different keys from
            // one host is the man-in-the-middle shape, and the pin above would then
            // be vouching for a key this session is not using.
            val challengeForRace = confirmed
            if (challengeForRace != null &&
                !FirstContactTrust.probeAgrees(challengeForRace.fingerprint, verdict.first)
            ) {
                throw HostKeyProbeMismatchException(challengeForRace, verdict.first)
            }
            if (verdict.third == FingerprintVerdict.TrustAndPin) {
                rebindFingerprint(target.id, verdict.first)
            }
        } catch (e: Exception) {
            runCatching { session.disconnect() }
            throw e
        }
        return session
    }

    /** Open the SFTP channel of a connected session (file transfers). */
    suspend fun openSftp(session: Session, timeoutMs: Int = 15_000): ChannelSftp =
        withContext(Dispatchers.IO) { ssh.openSftp(session, timeoutMs) }
}
