package com.sangiorgisrl.screenlink.ssh

/**
 * Decides whether a freshly-observed SSH server host-key fingerprint can be trusted
 * against what this app pinned for the connection.
 *
 * Older builds (<= 1.0.x) stored a non-standard fingerprint (SHA-256 of the base64
 * key text). Once a connection was saved that way, the app must not raise a
 * "possible man-in-the-middle" error just because the NEW standard fingerprint
 * differs - the underlying host key never changed. We therefore compare against
 * BOTH the fresh standard fingerprint and the recomputed legacy format of the
 * SAME key, and silently upgrade the pin in that case.
 */
sealed interface FingerprintVerdict {
    /** First contact, or the pinned value was the legacy format of the same key:
     *  store/overwrite with the fresh standard fingerprint and proceed. */
    data object TrustAndPin : FingerprintVerdict

    /** The pinned standard fingerprint matches the fresh one. Nothing to do. */
    data object Trust : FingerprintVerdict

    /** Neither the standard nor the legacy format matches the pinned value:
     *  the host key genuinely changed (or this is a different server). */
    data object Changed : FingerprintVerdict
}

object HostKeyTrust {

    /**
     * @param fresh   standard ssh-keygen-style fingerprint of the current host key.
     * @param legacy  legacy-format fingerprint of the current host key (or blank).
     * @param pinned  the fingerprint saved for this connection (null = first contact).
     */
    fun verdict(fresh: String, legacy: String, pinned: String?): FingerprintVerdict {
        if (pinned == null || pinned.isEmpty()) return FingerprintVerdict.TrustAndPin
        if (pinned == fresh) return FingerprintVerdict.Trust
        // Same key, but the pin was saved with the old algorithm: upgrade it.
        if (legacy.isNotEmpty() && pinned == legacy) return FingerprintVerdict.TrustAndPin
        return FingerprintVerdict.Changed
    }
}
