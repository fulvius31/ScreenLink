package com.sangiorgisrl.screenlink.ssh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Pins the host-key trust decision, especially the legacy-fingerprint migration. */
class HostKeyTrustTest {

    @Test
    fun `first contact pins the fresh fingerprint`() {
        assertEquals(FingerprintVerdict.TrustAndPin, HostKeyTrust.verdict("SHA256:NEW", "SHA256:LEG", null))
        assertEquals(FingerprintVerdict.TrustAndPin, HostKeyTrust.verdict("SHA256:NEW", "SHA256:LEG", ""))
    }

    @Test
    fun `matching current fingerprint is trusted and not rewritten`() {
        assertEquals(FingerprintVerdict.Trust, HostKeyTrust.verdict("SHA256:AAA", "SHA256:LEG", "SHA256:AAA"))
    }

    @Test
    fun `legacy pin of the same key is upgraded not flagged`() {
        // Old builds pinned SHA-256 of the base64 text; the fresh standard fingerprint
        // of the SAME host key differs, but the legacy recomputation matches it.
        assertEquals(
            FingerprintVerdict.TrustAndPin,
            HostKeyTrust.verdict("SHA256:AAA", "SHA256:LEG", "SHA256:LEG"),
        )
    }

    @Test
    fun `unrelated fingerprint means the key genuinely changed`() {
        assertEquals(
            FingerprintVerdict.Changed,
            HostKeyTrust.verdict("SHA256:AAA", "SHA256:LEG", "SHA256:EVIL"),
        )
    }

    @Test
    fun `empty legacy never silently matches`() {
        assertEquals(
            FingerprintVerdict.Changed,
            HostKeyTrust.verdict("SHA256:AAA", "", "SHA256:EVIL"),
        )
    }
}
