package com.sangiorgisrl.screenlink.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.JSch
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * ServerFingerprint uses jsch's HostKey#getFingerPrint, which hashes the raw wire-format
 * host key blob - exactly what OpenSSH does. Pin that it matches ssh-keygen -lf output.
 *
 * The blob/fingerprint below were cross-checked with `ssh-keygen -lf` on a real ed25519 key:
 *   256 SHA256:lgUApzop4ld/nEz1U9a8+JI7jWUNwrJn2hrQYxCUJ8I (ED25519)
 */
class FingerprintTest {

    @Test
    fun `fingerprint matches the standard ssh-keygen SHA-256`() {
        val blobBase64 = "AAAAC3NzaC1lZDI1NTE5AAAAICCizIwxhSbPhE0/E1671gbBqbsgidsTlRL0e8S6uyRR"
        val hostKey = HostKey("test", Base64.getDecoder().decode(blobBase64))
        val fp = hostKey.getFingerPrint(JSch())
        assertEquals("SHA256:lgUApzop4ld/nEz1U9a8+JI7jWUNwrJn2hrQYxCUJ8I", fp)
        // And it is NOT the (old, wrong) hash of the base64 *text* - prove the difference.
        val wrongBytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(blobBase64.toByteArray(Charsets.US_ASCII))
        val wrongFp = "SHA256:" + Base64.getEncoder().encodeToString(wrongBytes).trimEnd('=')
        assertNotEquals(wrongFp, fp)
    }
}
