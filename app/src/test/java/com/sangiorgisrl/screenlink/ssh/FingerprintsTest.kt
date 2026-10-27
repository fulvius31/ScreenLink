package com.sangiorgisrl.screenlink.ssh

import com.jcraft.jsch.HostKey
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Pins the fingerprint computations against output cross-checked with a real
 * `ssh-keygen -lf` run on a throwaway ed25519 host key (and jsch's own
 * HostKey#getKey text, which is what the legacy pins were built from).
 */
class FingerprintsTest {

    private fun blob(b64: String) = Base64.getDecoder().decode(b64)

    // ssh-keygen -lf  ->  256 SHA256:AR7OpcbnQmySbAJ96ePWkRObp22oDgVUlv9QL8PXpA0 (ED25519)
    private val b64A = "AAAAC3NzaC1lZDI1NTE5AAAAIP1CHKTVFkrlit7McSsWlMzYmKlxS4BTkE7JKF+XQ6xu"

    // ssh-keygen -lf  ->  256 SHA256:lgUApzop4ld/nEz1U9a8+JI7jWUNwrJn2hrQYxCUJ8I (ED25519)
    private val b64B = "AAAAC3NzaC1lZDI1NTE5AAAAICCizIwxhSbPhE0/E1671gbBqbsgidsTlRL0e8S6uyRR"

    @Test
    fun `standard fingerprint matches ssh-keygen output`() {
        assertEquals(
            "SHA256:AR7OpcbnQmySbAJ96ePWkRObp22oDgVUlv9QL8PXpA0",
            Fingerprints.standardFromWireBlob(blob(b64A)),
        )
        assertEquals(
            "SHA256:lgUApzop4ld/nEz1U9a8+JI7jWUNwrJn2hrQYxCUJ8I",
            Fingerprints.standardFromWireBlob(blob(b64B)),
        )
    }

    @Test
    fun `legacy fingerprint matches the old base64-text pin scheme`() {
        assertEquals(
            "SHA256:ScI0aF3Kh8bHFG5YlPt15jtkXtUsWRFJNHLF2MnrODE=",
            Fingerprints.legacyFromWireBlob(blob(b64A)),
        )
        assertEquals(
            "SHA256:pO1kJF1NIK3W2PmCewRMR5AnTzrcFTjUaI5j0H0qxq8=",
            Fingerprints.legacyFromWireBlob(blob(b64B)),
        )
    }

    @Test
    fun `standard and legacy differ for the same key`() {
        val std = Fingerprints.standardFromWireBlob(blob(b64B))
        val legacy = Fingerprints.legacyFromWireBlob(blob(b64B))
        assertNotEquals(std, legacy)
        assertNotEquals("SHA256:pO1kJF1NIK3W2PmCewRMR5AnTzrcFTjUaI5j0H0qxq8=", std)
    }

    @Test
    fun `legacy-from-blob is consistent with legacy-from-hostkey-text`() {
        val raw = blob(b64B)
        val viaText = Fingerprints.legacyFromBase64KeyText(HostKey("h", raw).key)
        assertEquals(Fingerprints.legacyFromWireBlob(raw), viaText)
    }
}
