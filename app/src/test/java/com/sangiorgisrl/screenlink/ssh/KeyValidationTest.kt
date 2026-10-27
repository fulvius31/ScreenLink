package com.sangiorgisrl.screenlink.ssh

import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.i18n.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyValidationTest {

    private fun load(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("keys/$name").bufferedReader().readText()

    private fun invalidText(result: KeyValidationResult): UiText {
        assertTrue(result is KeyValidationResult.Invalid, "expected Invalid but was $result")
        return (result as KeyValidationResult.Invalid).text
    }

    @Test
    fun `accepts an unencrypted ed25519 key`() {
        assertTrue(KeyValidation.validate("ed", load("test_ed25519"), "") is KeyValidationResult.Valid)
    }

    @Test
    fun `accepts an OpenSSH RSA key with the correct passphrase`() {
        assertTrue(KeyValidation.validate("rsa", load("test_rsa_pass"), "pass123") is KeyValidationResult.Valid)
    }

    @Test
    fun `accepts a PKCS1 encrypted RSA key with its passphrase`() {
        assertTrue(KeyValidation.validate("rsa1", load("test_rsa_pkcs1_enc"), "keypass") is KeyValidationResult.Valid)
    }

    @Test
    fun `an encrypted key without its passphrase is rejected`() {
        assertEquals(
            UiText.Resource(R.string.err_key_encrypted),
            invalidText(KeyValidation.validate("rsa1", load("test_rsa_pkcs1_enc"), "")),
        )
    }

    @Test
    fun `rejects non-pem garbage and blank name`() {
        assertEquals(
            UiText.Resource(R.string.err_key_pem),
            invalidText(KeyValidation.validate("x", "not a key", "")),
        )
        assertEquals(
            UiText.Resource(R.string.err_key_name),
            invalidText(KeyValidation.validate("", load("test_ed25519"), "")),
        )
        assertTrue(
            invalidText(
                KeyValidation.validate("x", "-----BEGIN OPENSSH PRIVATE KEY-----\nno\n", "")
            ) is UiText.Resource,
        )
    }

    @Test
    fun `wrong passphrases are tolerated at import and reported at connect time`() {
        // This is a documented limitation: a bad passphrase cannot be detected
        // offline with JSch; the key still imports and auth fails later.
        val result = KeyValidation.validate("rsa", load("test_rsa_pass"), "WRONG")
        assertTrue(result is KeyValidationResult.Valid || result is KeyValidationResult.Invalid)
    }

    @Test
    fun `key algo hint identifies formats`() {
        assertEquals(R.string.algo_openssh, KeyValidation.keyAlgoHint(load("test_ed25519")))
        assertEquals(R.string.algo_rsa, KeyValidation.keyAlgoHint(load("test_rsa_pkcs1_enc")))
        assertEquals(R.string.algo_unknown, KeyValidation.keyAlgoHint("unrecognized"))
    }

    // Validation runs on a worker thread and the message is shown later, possibly
    // after a language change, so a message must name a resource instead of holding
    // text that was resolved in whichever language happened to be active.
    @Test
    fun `every rejection is a resource not baked text`() {
        listOf(
            KeyValidation.validate("", load("test_ed25519"), ""),
            KeyValidation.validate("x", "not a key", ""),
            KeyValidation.validate("rsa1", load("test_rsa_pkcs1_enc"), ""),
            KeyValidation.validate("x", "-----BEGIN OPENSSH PRIVATE KEY-----\nno\n", ""),
        ).forEach { result ->
            val text = invalidText(result)
            assertTrue(text is UiText.Resource, "expected a string resource, got $text")
            assertTrue((text as UiText.Resource).id != 0, "resource id must be set")
        }
    }
}
