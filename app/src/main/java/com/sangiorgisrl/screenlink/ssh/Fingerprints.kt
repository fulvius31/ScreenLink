package com.sangiorgisrl.screenlink.ssh

import com.jcraft.jsch.HostKey
import java.security.MessageDigest
import java.util.Base64

/**
 * Pure host-key fingerprint computations, shared by the live-connection checks
 * (SshClient) and the pre-authentication check that refuses a changed host key
 * before any password/key is sent to the server (PinnedHostKeyRepository).
 *
 *  - Standard: SHA-256 over the raw wire-format host key blob, base64 without
 *    padding - byte-for-byte what `ssh-keygen -lf` prints (and what jsch's
 *    HostKey#getFingerPrint produces).
 *  - Legacy: the scheme older builds pinned - SHA-256 over the base64 *text* of
 *    the key. It has to be computed here the same way jsch itself builds that text
 *    (HostKey#getKey), otherwise a saved pin from an older build would look like a
 *    host-key change.
 */
object Fingerprints {

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Standard ssh-keygen SHA-256 fingerprint of a wire-format host key blob. */
    fun standardFromWireBlob(blob: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(sha256(blob))

    /**
     * Legacy fingerprint from the raw wire blob. Reuses jsch's `HostKey(host, blob)`
     * so the base64 *text* it hashes is byte-identical to SshClient's
     * serverFingerprintLegacy / old-build pin (HostKey#getKey returns that same
     * text), including its line-break behaviour for larger keys.
     */
    fun legacyFromWireBlob(blob: ByteArray): String {
        val keyText = HostKey("screenlink", blob).key
        return legacyFromBase64KeyText(keyText)
    }

    /** Legacy fingerprint from the base64 key *text* (exactly what old pins stored). */
    fun legacyFromBase64KeyText(base64KeyText: String): String {
        val digest = sha256(base64KeyText.toByteArray(Charsets.US_ASCII))
        return "SHA256:" + Base64.getEncoder().encodeToString(digest)
    }
}
