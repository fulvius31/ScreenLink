package com.sangiorgisrl.screenlink.data

import com.sangiorgisrl.screenlink.security.CryptoManager
import com.sangiorgisrl.screenlink.security.CryptoService
import com.sangiorgisrl.screenlink.ssh.KeyValidation
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for saved connections and SSH keys.
 *
 * This is the ONLY place secrets are (en)crypted, through the injected
 * [CryptoService] - the entities are pure persistence models and never decrypt
 * themselves. Callers that need credentials for the SSH layer ask for plain
 * [SshConnection] / [SshKeyCredential] objects here.
 */
class ConnectionRepository(
    private val dao: ConnectionDao,
    private val keyDao: SshKeyDao,
    private val crypto: CryptoService = CryptoManager,
) {
    val connections: Flow<List<ConnectionEntity>> = dao.observeAll()
    val sshKeys: Flow<List<SshKeyEntity>> = keyDao.observeAll()

    suspend fun get(id: Long): ConnectionEntity? = dao.getById(id)

    suspend fun count(): Int = dao.count()

    /** Insert or update a connection. Encrypts the password and replaces the key list. */
    suspend fun save(
        id: Long,
        displayName: String,
        username: String,
        host: String,
        port: Int,
        passwordPlain: String,
        screenSessionName: String?,
        keyIds: List<Long> = emptyList(),
        /**
         * Explicitly wipe a previously-saved password. Without this, an empty
         * [passwordPlain] on an edit keeps the stored secret (so a user who does
         * not touch the field never loses it); a user who WANTS the password gone
         * has to say so. `true` wins over any typed text.
         */
        clearPassword: Boolean = false,
    ): Long {
        val now = System.currentTimeMillis()
        val existing = if (id != 0L) dao.getById(id) else null
        val passwordEnc = when {
            clearPassword -> ""
            passwordPlain.isNotEmpty() -> crypto.encrypt(passwordPlain)
            else -> existing?.passwordEnc ?: ""
        }
        val connectionId = if (id != 0L) {
            dao.update(
                ConnectionEntity(
                    id = id,
                    displayName = displayName.trim(),
                    username = username.trim(),
                    host = host.trim(),
                    port = port,
                    passwordEnc = passwordEnc,
                    screenSessionName = screenSessionName?.trim()?.takeIf { it.isNotEmpty() },
                    serverFingerprint = existing?.serverFingerprint,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
            )
            id
        } else {
            dao.insert(
                ConnectionEntity(
                    id = 0L,
                    displayName = displayName.trim(),
                    username = username.trim(),
                    host = host.trim(),
                    port = port,
                    passwordEnc = passwordEnc,
                    screenSessionName = screenSessionName?.trim()?.takeIf { it.isNotEmpty() },
                    serverFingerprint = null,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
        // Replace the associated key set.
        keyDao.clearConnectionKeys(connectionId)
        keyIds.distinct().forEach { keyId ->
            keyDao.insertCrossRef(ConnectionKeyCrossRef(connectionId, keyId))
        }
        return connectionId
    }

    suspend fun delete(connection: ConnectionEntity) {
        keyDao.clearConnectionKeys(connection.id)
        dao.delete(connection)
    }

    /**
     * Put a deleted connection back, keeping its ORIGINAL id and key links.
     *
     * This is what makes the Home list's swipe-to-dismiss / overflow delete
     * reversible (Material 3: a destructive action is either confirmed up front
     * or undoable afterwards). The id must survive because recent-session entries
     * and live terminal tabs reference the connection by id.
     */
    suspend fun restore(connection: ConnectionEntity, keyIds: List<Long>) {
        // Update first: it preserves the primary key. Only if the row really is
        // gone do we re-insert it (Room keeps an explicit non-zero id on insert).
        if (dao.update(connection) == 0) dao.insert(connection)
        keyDao.clearConnectionKeys(connection.id)
        keyIds.distinct().forEach { keyId ->
            keyDao.insertCrossRef(ConnectionKeyCrossRef(connection.id, keyId))
        }
    }

    /**
     * Overwrite the pinned host-key fingerprint. Used ONLY when the caller has
     * positively identified the key (first contact, or a match against the legacy
     * fingerprint format of the same key) - never on a genuine mismatch.
     */
    suspend fun rebindFingerprint(id: Long, fingerprint: String) {
        dao.getById(id)?.let {
            dao.update(it.copy(serverFingerprint = fingerprint, updatedAt = System.currentTimeMillis()))
        }
    }

    // --- Decrypted credentials for the SSH layer (the ONLY decryption site) -------

    /** The connection's plain credentials, or null if the row is gone. */
    suspend fun getSshConnection(id: Long): SshConnection? {
        val c = dao.getById(id) ?: return null
        return SshConnection(
            id = c.id,
            displayName = c.displayName,
            username = c.username,
            host = c.host,
            port = c.port,
            password = crypto.decrypt(c.passwordEnc),
            screenSessionName = c.screenSessionName,
            serverFingerprint = c.serverFingerprint,
        )
    }

    /** Plain credentials for every key attached to a connection. */
    suspend fun getSshKeysForConnection(connectionId: Long): List<SshKeyCredential> =
        keyDao.getKeysForConnection(connectionId).map { it.toCredential(crypto) }

    /** Plain credentials for a single key, or null when it is gone. */
    suspend fun getSshKey(id: Long): SshKeyCredential? =
        keyDao.getById(id)?.toCredential(crypto)

    // --- SSH keys ------------------------------------------------------------

    suspend fun getKey(id: Long): SshKeyEntity? = keyDao.getById(id)

    suspend fun getKeyIdsForConnection(connectionId: Long): List<Long> =
        keyDao.getKeyIdsForConnection(connectionId)

    /**
     * Save a new SSH key, encrypting the private key + passphrase before storage.
     * The key-type tag is captured here (a cheap string check on the plaintext we
     * already hold), so the Keys list never has to decrypt to show a badge.
     */
    suspend fun saveKey(
        name: String,
        privateKeyPlain: String,
        publicKeyPlain: String?,
        passphrasePlain: String,
    ): Long {
        val key = SshKeyEntity(
            id = 0L,
            name = name.trim(),
            privateKeyEnc = crypto.encrypt(privateKeyPlain),
            publicKey = publicKeyPlain?.trim()?.takeIf { it.isNotEmpty() },
            passphraseEnc = if (passphrasePlain.isNotEmpty()) crypto.encrypt(passphrasePlain) else "",
            keyAlgo = KeyValidation.keyAlgoTag(privateKeyPlain),
            createdAt = System.currentTimeMillis(),
        )
        return keyDao.insert(key)
    }

    /** Delete a key and remove it from all connections. */
    suspend fun deleteKey(key: SshKeyEntity) {
        keyDao.deleteConnectionKeysByKeyId(key.id)
        keyDao.delete(key)
    }

    private fun SshKeyEntity.toCredential(crypto: CryptoService) = SshKeyCredential(
        id = id,
        name = name,
        privateKey = crypto.decrypt(privateKeyEnc),
        passphrase = crypto.decrypt(passphraseEnc),
    )
}
