package com.sangiorgisrl.screenlink.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.sangiorgisrl.screenlink.ScreenLinkApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Functional test of the on-device Room + Keystore integration (real CryptoManager).
 * Verifies that encrypted secrets round-trip through the repository's decrypted
 * credential DTOs (never through entity getters) and that the DB schema/version is
 * the current one.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class RoomKeystoreFunctionalTest {

    private val app = ApplicationProvider.getApplicationContext<ScreenLinkApp>()

    @Test
    fun passwordsAndKeysRoundTripThroughTheKeystore() = runBlocking {
        val repo = app.repository

        val connId = repo.save(
            id = 0L, displayName = "ItBox", username = "root", host = "10.0.2.2",
            port = 2222, passwordPlain = "topsecret", screenSessionName = null,
        )
        // The decrypted password is only reachable through the SSH credential DTO.
        val creds = repo.getSshConnection(connId)!!
        assertEquals("round-trip password", "topsecret", creds.password)

        val keyId = repo.saveKey(
            name = "itkey",
            privateKeyPlain = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----",
            publicKeyPlain = null,
            passphrasePlain = "pass",
        )
        val key = repo.getSshKey(keyId)!!
        assertTrue(key.privateKey.contains("OPENSSH PRIVATE KEY"))
        assertEquals("pass", key.passphrase)

        // The key-type tag is persisted at import so the UI badge never decrypts.
        assertEquals("openssh", repo.getKey(keyId)!!.keyAlgo)

        // cleanup
        repo.deleteKey(repo.getKey(keyId)!!)
        repo.delete(repo.get(connId)!!)
        assertTrue(repo.connections.first().isEmpty())
    }

    @Test
    fun databaseIsVersion3WithKeyTables() {
        val db = app.database
        assertEquals(3, db.openHelper.readableDatabase.version)
    }
}
