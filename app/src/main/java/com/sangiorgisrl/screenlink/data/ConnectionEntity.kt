package com.sangiorgisrl.screenlink.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved SSH connection - a pure persistence model.
 *
 * The password is stored encrypted (AES/GCM with the Android Keystore) in
 * [passwordEnc]. Decryption NEVER happens on this entity: the repository (which
 * owns the injected [com.sangiorgisrl.screenlink.security.CryptoService]) turns
 * entities into plain [SshConnection] credentials at connect time, so no code
 * path can accidentally read a secret through a getter.
 */
@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "host") val host: String,
    @ColumnInfo(name = "port") val port: Int = 22,
    @ColumnInfo(name = "password_enc") val passwordEnc: String = "",
    /** Optional GNU Screen session name used as the default attach target. */
    @ColumnInfo(name = "screen_session_name") val screenSessionName: String? = null,
    /** SHA-256 fingerprint of the server host key (trust on first use). */
    @ColumnInfo(name = "server_ssh_fingerprint") val serverFingerprint: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)
