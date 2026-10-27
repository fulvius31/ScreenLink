package com.sangiorgisrl.screenlink.ssh

import com.jcraft.jsch.HostKeyRepository
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The pre-authentication host key gate that strict=yes + PinnedHostKeyRepository give us. */
class PinnedHostKeyRepositoryTest {

    private val b64 = "AAAAC3NzaC1lZDI1NTE5AAAAIP1CHKTVFkrlit7McSsWlMzYmKlxS4BTkE7JKF+XQ6xu"
    private val std = "SHA256:AR7OpcbnQmySbAJ96ePWkRObp22oDgVUlv9QL8PXpA0"
    private val legacy = "SHA256:ScI0aF3Kh8bHFG5YlPt15jtkXtUsWRFJNHLF2MnrODE="
    private val blob = Base64.getDecoder().decode(b64)

    @Test
    fun `first contact is accepted (TOFU)`() {
        assertEquals(HostKeyRepository.OK, PinnedHostKeyRepository(null).check("h", blob))
        assertEquals(HostKeyRepository.OK, PinnedHostKeyRepository("").check("h", blob))
    }

    @Test
    fun `matching standard pin is accepted`() {
        assertEquals(HostKeyRepository.OK, PinnedHostKeyRepository(std).check("h", blob))
    }

    @Test
    fun `matching legacy pin is accepted so it can be upgraded`() {
        assertEquals(HostKeyRepository.OK, PinnedHostKeyRepository(legacy).check("h", blob))
    }

    @Test
    fun `unrelated pin is rejected as a changed key`() {
        assertEquals(
            HostKeyRepository.CHANGED,
            PinnedHostKeyRepository("SHA256:EVILFINGERPRINTeve").check("h", blob),
        )
    }
}
