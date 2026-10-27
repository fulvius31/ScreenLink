package com.sangiorgisrl.screenlink.ssh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The first-contact half of trust-on-first-use, pinned as a pure decision.
 *
 * [HostKeyTrustTest] pins "does this key match the pin?". These pin the other
 * question - "may a credential leave the device when nothing is pinned yet?" -
 * because answering it `true` is what makes the very first connection to a host
 * man-in-the-middle-proof.
 */
class FirstContactTrustTest {

    @Test
    fun `no pin at all means the user has to be asked`() {
        assertTrue(FirstContactTrust.mustConfirm(null))
        assertTrue(FirstContactTrust.mustConfirm(""))
    }

    @Test
    fun `an existing pin needs no question`() {
        assertFalse(FirstContactTrust.mustConfirm("SHA256:AAA"))
        // A legacy-format pin is still a pin: the question is "is anything known",
        // not "is what we know in the newest format".
        assertFalse(FirstContactTrust.mustConfirm("LEGACY:AAA"))
    }

    @Test
    fun `the confirmed key must be the key the session uses`() {
        assertTrue(FirstContactTrust.probeAgrees("SHA256:AAA", "SHA256:AAA"))
        assertFalse(FirstContactTrust.probeAgrees("SHA256:AAA", "SHA256:EVIL"))
    }

    @Test
    fun `nothing confirmed can never agree with a live key`() {
        // Guards the call site: comparing against a missing confirmation must not
        // read as "fine", which is how a null-check bug becomes a silent MITM pass.
        assertFalse(FirstContactTrust.probeAgrees(null, "SHA256:AAA"))
        assertFalse(FirstContactTrust.probeAgrees("", "SHA256:AAA"))
    }
}
