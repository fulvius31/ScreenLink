package com.sangiorgisrl.screenlink.ssh

import androidx.annotation.StringRes
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.KeyPair
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.i18n.UiText

/**
 * Result of validating a pasted private key (via JSch, which checks format + passphrase).
 *
 * [UiText] rather than a String: validation runs on a worker thread and the
 * message is shown later, possibly after a language change.
 */
sealed interface KeyValidationResult {
    data object Valid : KeyValidationResult
    data class Invalid(val text: UiText) : KeyValidationResult
}

object KeyValidation {

    /**
     * Validate key metadata and that JSch can parse the key. If the key claims to
     * be encrypted, a passphrase must be supplied.
     *
     * NOTE: JSch cannot reliably distinguish a *wrong* passphrase at import time
     * for common formats (it defers decryption and ships garbage signatures for
     * wrong-pass keys), so a wrong passphrase surfaces at connect time as an
     * authentication failure. That mirrors every mainstream SSH client.
     */
    fun validate(name: String, privateKey: String, passphrase: String): KeyValidationResult {
        if (name.isBlank()) return KeyValidationResult.Invalid(UiText.Resource(R.string.err_key_name))
        if (!privateKey.trimStart().startsWith("-----BEGIN") || !privateKey.contains("PRIVATE KEY")) {
            return KeyValidationResult.Invalid(UiText.Resource(R.string.err_key_pem))
        }
        return try {
            val jsch = JSch()
            val keyBytes = privateKey.toByteArray(Charsets.UTF_8)
            val passBytes = passphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)

            val keyPair = try {
                KeyPair.load(jsch, keyBytes, passBytes)
            } catch (e: JSchException) {
                if (e.message?.contains("encrypted", ignoreCase = true) == true ||
                    e.message?.contains("passphrase", ignoreCase = true) == true
                ) {
                    return KeyValidationResult.Invalid(
                        UiText.Resource(R.string.err_key_encrypted)
                    )
                }
                throw e
            }
            if (keyPair.isEncrypted && passBytes == null) {
                return KeyValidationResult.Invalid(UiText.Resource(R.string.err_key_encrypted))
            }
            // Best-effort structural check (not a passphrase check - see class doc).
            keyPair.getSignature(byteArrayOf(1, 2, 3))
            KeyValidationResult.Valid
        } catch (e: JSchException) {
            val detail = e.message ?: e.javaClass.simpleName
            KeyValidationResult.Invalid(
                if (e.message?.contains("passphrase", ignoreCase = true) == true ||
                    e.message?.contains("encrypted", ignoreCase = true) == true
                ) {
                    UiText.Resource(R.string.err_key_passphrase)
                } else {
                    UiText.Resource(R.string.err_key_invalid, UiText.Literal(detail))
                }
            )
        } catch (e: Exception) {
            KeyValidationResult.Invalid(
                UiText.Resource(
                    R.string.err_key_read,
                    UiText.Literal(e.message ?: e.javaClass.simpleName),
                )
            )
        }
    }

    /**
     * Stable, persistable key-type tag. Deliberately NOT a [StringRes]: resource
     * ids are build-dependent and must never be stored in the database, so the
     * entity keeps this tag and the UI maps it to a label at render time.
     */
    fun keyAlgoTag(pem: String): String {
        val blob = pem.replace("\r", "")
        return when {
            blob.contains("OPENSSH PRIVATE KEY") -> "openssh"
            "--BEGIN EC PRIVATE KEY--" in blob || "--BEGIN EC PARAMETERS--" in blob -> "ec"
            "--BEGIN DSA PRIVATE KEY--" in blob || "--BEGIN DSA PARAMETERS--" in blob -> "dsa"
            "--BEGIN RSA PRIVATE KEY--" in blob -> "rsa"
            "--BEGIN PRIVATE KEY--" in blob -> "pkcs8"
            else -> "unknown"
        }
    }

    /** Localized label for a [keyAlgoTag] (unknown tags fall back to the generic one). */
    @StringRes
    fun keyAlgoLabelRes(tag: String): Int = when (tag) {
        "openssh" -> R.string.algo_openssh
        "ec" -> R.string.algo_ec
        "dsa" -> R.string.algo_dsa
        "rsa" -> R.string.algo_rsa
        "pkcs8" -> R.string.algo_pkcs8
        else -> R.string.algo_unknown
    }

    /** Friendly identification of the key type (derived from the stable tag). */
    @StringRes
    fun keyAlgoHint(pem: String): Int = keyAlgoLabelRes(keyAlgoTag(pem))
}
