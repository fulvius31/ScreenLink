package com.sangiorgisrl.screenlink.ssh

/**
 * The first-contact half of trust-on-first-use.
 *
 * [HostKeyTrust] answers "does this key match what we already pinned?". This answers
 * the other question - "may we connect at all when nothing has been pinned yet?".
 * Answering it with a plain yes (the old behaviour) means the very first connection
 * to a host has no man-in-the-middle protection: the pin is written *after* the
 * password has already been sent, so an attacker on that first connection is pinned
 * by us, not caught by us.
 *
 * The rule is therefore: with no pin on record, learn the key WITHOUT authenticating
 * ([SshGateway.probeHostKey]), have the user confirm it, and only then connect with
 * credentials - against the confirmed key, so the connection that carries the
 * password is the one that is verified.
 */
object FirstContactTrust {

    /** True when nothing has ever been pinned for this connection. */
    fun mustConfirm(pinnedFingerprint: String?): Boolean = pinnedFingerprint.isNullOrEmpty()

    /**
     * True when the key seen on the credential-bearing connection is the key the
     * user just confirmed.
     *
     * A mismatch is not a nitpick: it means the server answered the probe and the
     * real connection with two different keys, which is what an attacker between us
     * and the host looks like. The confirmed value must be the one enforced.
     */
    fun probeAgrees(confirmedFingerprint: String?, liveFingerprint: String): Boolean =
        confirmedFingerprint != null && confirmedFingerprint == liveFingerprint
}

/** What the user is being asked to vouch for, and why. */
data class HostKeyChallenge(
    val connectionId: Long,
    val connectionName: String,
    val host: String,
    val port: Int,
    val fingerprint: String,
)

/** Answered by whoever shows the confirmation UI. */
interface HostKeyConfirmation {
    /**
     * Suspend until the user has answered [challenge].
     *
     * Implementations must fail closed: no answer, or a refusal, is `false`.
     */
    suspend fun confirm(challenge: HostKeyChallenge): Boolean
}

/** Raised when the user refused, or never answered, the first-contact question. */
class HostKeyNotConfirmedException(val challenge: HostKeyChallenge) :
    Exception("The host key for ${challenge.host}:${challenge.port} was not confirmed")

/**
 * Raised when the host key presented on the credential-bearing connection differs
 * from the one just confirmed on the probe connection.
 */
class HostKeyProbeMismatchException(
    challenge: HostKeyChallenge,
    liveFingerprint: String,
) : Exception(
    "The host key for ${challenge.host}:${challenge.port} changed between the " +
        "confirmation (${challenge.fingerprint}) and the connection ($liveFingerprint)",
)
