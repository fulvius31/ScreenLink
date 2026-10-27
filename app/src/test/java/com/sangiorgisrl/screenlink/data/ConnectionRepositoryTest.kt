package com.sangiorgisrl.screenlink.data

import com.sangiorgisrl.screenlink.security.CryptoService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The repository is the single source of truth for connections, keys and their
 * junction rows, and it is what makes the Home list's delete reversible
 * (Material 3: destructive = confirm or undo). Both DAOs are interfaces, so the
 * whole contract is testable on the JVM with in-memory fakes.
 */
class ConnectionRepositoryTest {

    private class FakeConnectionDao : ConnectionDao {
        private val rows = LinkedHashMap<Long, ConnectionEntity>()
        private val stream = MutableStateFlow(emptyList<ConnectionEntity>())
        private var nextId = 1L

        override fun observeAll(): Flow<List<ConnectionEntity>> = stream

        override suspend fun getById(id: Long): ConnectionEntity? = rows[id]

        override suspend fun count(): Int = rows.size

        override suspend fun insert(connection: ConnectionEntity): Long {
            val id = if (connection.id == 0L) nextId++ else {
                // Room keeps an explicit non-zero primary key on insert.
                if (connection.id >= nextId) nextId = connection.id + 1
                connection.id
            }
            rows[id] = connection.copy(id = id)
            stream.value = rows.values.toList()
            return id
        }

        override suspend fun update(connection: ConnectionEntity): Int =
            if (rows.containsKey(connection.id)) {
                rows[connection.id] = connection
                stream.value = rows.values.toList()
                1
            } else {
                0
            }

        override suspend fun delete(connection: ConnectionEntity) {
            rows.remove(connection.id)
            stream.value = rows.values.toList()
        }
    }

    private class FakeSshKeyDao : SshKeyDao {
        val refs = mutableListOf<ConnectionKeyCrossRef>()
        private val rows = LinkedHashMap<Long, SshKeyEntity>()
        private val stream = MutableStateFlow(emptyList<SshKeyEntity>())
        private var nextId = 1L

        override fun observeAll(): Flow<List<SshKeyEntity>> = stream

        override suspend fun getById(id: Long): SshKeyEntity? = rows[id]

        override suspend fun insert(key: SshKeyEntity): Long {
            val id = if (key.id == 0L) nextId++ else key.id
            rows[id] = key.copy(id = id)
            stream.value = rows.values.toList()
            return id
        }

        override suspend fun delete(key: SshKeyEntity) {
            rows.remove(key.id)
            stream.value = rows.values.toList()
        }

        override suspend fun getKeysForConnection(connectionId: Long): List<SshKeyEntity> =
            refs.filter { it.connectionId == connectionId }.mapNotNull { rows[it.keyId] }

        override suspend fun getKeyIdsForConnection(connectionId: Long): List<Long> =
            refs.filter { it.connectionId == connectionId }.map { it.keyId }

        override suspend fun clearConnectionKeys(connectionId: Long) {
            refs.removeAll { it.connectionId == connectionId }
        }

        override suspend fun deleteConnectionKeysByKeyId(keyId: Long) {
            refs.removeAll { it.keyId == keyId }
        }

        override suspend fun insertCrossRef(ref: ConnectionKeyCrossRef) {
            refs.add(ref)
        }
    }

    /** Reversible stand-in for the Keystore: prefix instead of AES/GCM. */
    private object FakeCrypto : CryptoService {
        override fun encrypt(plain: String): String = if (plain.isEmpty()) "" else "enc:$plain"
        override fun decrypt(encoded: String): String = encoded.removePrefix("enc:")
    }

    private fun repo() = ConnectionRepository(FakeConnectionDao(), FakeSshKeyDao(), FakeCrypto)

    @Test
    fun `save creates a row and encrypts the password`() = runBlocking {
        val repository = repo()
        val id = repository.save(
            id = 0L, displayName = "  Lab  ", username = " ops ", host = " lab.example ",
            port = 22, passwordPlain = "hunter2", screenSessionName = " work ",
        )
        val saved = repository.get(id)!!
        assertEquals("Lab", saved.displayName)
        assertEquals("ops", saved.username)
        assertEquals("lab.example", saved.host)
        assertEquals("work", saved.screenSessionName)
        assertEquals("enc:hunter2", saved.passwordEnc)
    }

    @Test
    fun `delete removes the connection and its key links`() = runBlocking {
        val repository = repo()
        val keyId = repository.saveKey("ed25519", "PEM", null, "")
        val id = repository.save(
            id = 0L, displayName = "Lab", username = "ops", host = "lab", port = 22,
            passwordPlain = "", screenSessionName = null, keyIds = listOf(keyId),
        )
        assertEquals(listOf(keyId), repository.getKeyIdsForConnection(id))

        repository.delete(repository.get(id)!!)

        assertNull(repository.get(id))
        assertTrue(repository.getKeyIdsForConnection(id).isEmpty())
    }

    @Test
    fun `restore puts back the same id and re-attaches the keys`() = runBlocking {
        val repository = repo()
        val keyA = repository.saveKey("key-a", "PEM-A", null, "")
        val keyB = repository.saveKey("key-b", "PEM-B", null, "")
        val id = repository.save(
            id = 0L, displayName = "Lab", username = "ops", host = "lab", port = 2222,
            passwordPlain = "pw", screenSessionName = "work", keyIds = listOf(keyA, keyB),
        )
        val deleted = repository.get(id)!!
        val links = repository.getKeyIdsForConnection(id)
        repository.delete(deleted)
        assertNull(repository.get(id))

        // The Undo path of the Home list snackbar.
        repository.restore(deleted, links)

        val restored = repository.get(id)
        assertNotNull(restored)
        // The id must survive: recents and live tabs reference connections by id.
        assertEquals(id, restored!!.id)
        assertEquals(deleted.displayName, restored.displayName)
        assertEquals(deleted.port, restored.port)
        assertEquals(deleted.passwordEnc, restored.passwordEnc)
        assertEquals(deleted.serverFingerprint, restored.serverFingerprint)
        assertEquals(links.toSet(), repository.getKeyIdsForConnection(id).toSet())
        assertEquals(1, repository.count())
    }

    @Test
    fun `restore of a row that is still there does not duplicate it`() = runBlocking {
        val repository = repo()
        val id = repository.save(
            id = 0L, displayName = "Lab", username = "ops", host = "lab",
            port = 22, passwordPlain = "", screenSessionName = null,
        )
        repository.restore(repository.get(id)!!, emptyList())
        assertEquals(1, repository.count())
    }

    @Test
    fun `save on an existing id updates in place and replaces the key set`() = runBlocking {
        val repository = repo()
        val keyA = repository.saveKey("key-a", "PEM-A", null, "")
        val keyB = repository.saveKey("key-b", "PEM-B", null, "")
        val id = repository.save(
            id = 0L, displayName = "Lab", username = "ops", host = "lab",
            port = 22, passwordPlain = "", screenSessionName = null, keyIds = listOf(keyA),
        )
        repository.save(
            id = id, displayName = "Lab 2", username = "ops", host = "lab",
            port = 22, passwordPlain = "", screenSessionName = null, keyIds = listOf(keyB),
        )
        assertEquals(1, repository.count())
        assertEquals("Lab 2", repository.get(id)!!.displayName)
        assertEquals(listOf(keyB), repository.getKeyIdsForConnection(id))
    }

    @Test
    fun `empty password on edit keeps the stored secret`() = runBlocking {
        val repository = repo()
        val id = repository.save(
            id = 0L, displayName = "Lab", username = "ops", host = "lab",
            port = 22, passwordPlain = "hunter2", screenSessionName = null,
        )
        repository.save(
            id = id, displayName = "Lab", username = "ops", host = "lab",
            port = 22, passwordPlain = "", screenSessionName = null,
        )
        assertEquals("enc:hunter2", repository.get(id)!!.passwordEnc)
    }

    @Test
    fun `explicit clearPassword wipes the stored secret on edit`() = runBlocking {
        val repository = repo()
        val id = repository.save(
            id = 0L, displayName = "Lab", username = "ops", host = "lab",
            port = 22, passwordPlain = "hunter2", screenSessionName = null,
        )
        repository.save(
            id = id, displayName = "Lab", username = "ops", host = "lab",
            port = 22, passwordPlain = "", screenSessionName = null, clearPassword = true,
        )
        // The user's explicit wipe wins over the "keep on blank" default.
        assertEquals("", repository.get(id)!!.passwordEnc)
    }

    @Test
    fun `keep-on-blank does not touch an already-cleared secret`() = runBlocking {
        val repository = repo()
        val id = repository.save(
            id = 0L, displayName = "Lab", username = "ops", host = "lab",
            port = 22, passwordPlain = "hunter2", screenSessionName = null,
        )
        repository.save(
            id = id, displayName = "Lab", username = "ops", host = "lab",
            port = 22, passwordPlain = "", screenSessionName = null, clearPassword = true,
        )
        // Second edit, field still blank, no wipe requested -> stays cleared.
        repository.save(
            id = id, displayName = "Lab", username = "ops", host = "lab",
            port = 22, passwordPlain = "", screenSessionName = null,
        )
        assertEquals("", repository.get(id)!!.passwordEnc)
    }

    @Test
    fun `saveKey records a stable key-type tag`() = runBlocking {
        val repository = repo()
        val id = repository.saveKey(
            name = "ed",
            privateKeyPlain = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----",
            publicKeyPlain = null,
            passphrasePlain = "",
        )
        // Persisted tag (not a build-dependent resource id) so the list badge
        // never has to decrypt the key to render.
        assertEquals("openssh", repository.getKey(id)!!.keyAlgo)
    }
}
