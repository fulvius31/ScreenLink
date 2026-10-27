package com.sangiorgisrl.screenlink.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo

/**
 * JSch [HostKeyRepository] that enforces ScreenLink's trust policy during the SSH
 * key exchange - i.e. BEFORE the client sends any password or key to the server.
 *
 * JSch consults this repository's [check] during the handshake. Combined with
 * `StrictHostKeyChecking=yes`, a host key that matches neither the pinned standard
 * fingerprint nor the pinned legacy fingerprint makes JSch abort the connection as
 * a changed host key, and no credential ever reaches the (possible) attacker.
 *
 * First contact (no pin) and same-key legacy pins are accepted; the standard pin is
 * then written by the caller after the connection is established (TOFU).
 */
class PinnedHostKeyRepository(
    private val pinnedFingerprint: String?,
) : HostKeyRepository {

    override fun check(host: String?, key: ByteArray): Int {
        val pinned = pinnedFingerprint
        if (pinned.isNullOrEmpty()) return HostKeyRepository.OK
        if (pinned == Fingerprints.standardFromWireBlob(key)) return HostKeyRepository.OK
        val legacy = try {
            Fingerprints.legacyFromWireBlob(key)
        } catch (_: Exception) {
            // Cannot build the legacy text for this blob - fall through to standard only.
            null
        }
        if (legacy != null && pinned == legacy) return HostKeyRepository.OK
        return HostKeyRepository.CHANGED
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) {
        // Pinning is written by the caller (repository.rebindFingerprint); this
        // in-memory repository never persists keys on its own.
    }

    override fun remove(host: String?, alg: String?) {}

    override fun remove(host: String?, alg: String?, key: ByteArray?) {}

    override fun getKnownHostsRepositoryID(): String = "screenlink-in-memory"

    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(host: String?, alg: String?): Array<HostKey> = emptyArray()
}
