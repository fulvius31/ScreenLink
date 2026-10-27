package com.sangiorgisrl.screenlink.data

/**
 * Plain, decrypted SSH connection material.
 *
 * Produced by the repository (which holds its injected CryptoService) and consumed
 * by the SSH layer, so neither the Room entities nor the UI ever decide whether a
 * field is ciphertext or plaintext: the SSH layer asks for exactly the credentials
 * it needs, and the repository is the only place decryption (or the crypto service
 * test double) happens.
 */
data class SshConnection(
    val id: Long,
    val displayName: String,
    val username: String,
    val host: String,
    val port: Int,
    val password: String,
    val screenSessionName: String?,
    val serverFingerprint: String?,
)

/** Decrypted SSH private-key material for one saved key. */
data class SshKeyCredential(
    val id: Long,
    val name: String,
    val privateKey: String,
    val passphrase: String,
)
