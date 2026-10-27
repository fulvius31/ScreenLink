package com.sangiorgisrl.screenlink.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved SSH private key (optionally with a passphrase and an identifying
 * public key) - a pure persistence model.
 *
 * The private key and passphrase are stored encrypted at rest with the Android
 * Keystore. Decryption NEVER happens on this entity: the repository (which owns
 * the injected [com.sangiorgisrl.screenlink.security.CryptoService]) returns
 * plain [SshKeyCredential] objects to the SSH layer instead.
 *
 * [keyAlgo] is a STABLE tag ("openssh" | "ec" | "dsa" | "rsa" | "pkcs8" | "unknown"),
 * captured at import time so the UI can show the key type without ever having to
 * decrypt the private key just to render a badge. It is NOT a resource id (those
 * can change between builds); the UI maps the tag to a label.
 */
@Entity(tableName = "ssh_keys")
data class SshKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "private_key_enc") val privateKeyEnc: String,
    /** Optional public key (`.pub`) content - not secret, used for identification. */
    @ColumnInfo(name = "public_key") val publicKey: String? = null,
    /** Encrypted passphrase of the private key, or empty. */
    @ColumnInfo(name = "passphrase_enc") val passphraseEnc: String = "",
    /** Stable key-type tag captured at import (see class doc). */
    @ColumnInfo(name = "key_algo") val keyAlgo: String = "unknown",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

/** Junction between [ConnectionEntity] and [SshKeyEntity] (many-to-many). */
@Entity(tableName = "connection_keys", primaryKeys = ["connectionId", "keyId"])
data class ConnectionKeyCrossRef(
    @ColumnInfo(name = "connectionId") val connectionId: Long,
    @ColumnInfo(name = "keyId") val keyId: Long,
)
