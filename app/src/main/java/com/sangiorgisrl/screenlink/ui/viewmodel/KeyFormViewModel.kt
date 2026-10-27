package com.sangiorgisrl.screenlink.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sangiorgisrl.screenlink.ScreenLinkApp
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.i18n.UiText
import com.sangiorgisrl.screenlink.ssh.KeyValidation
import com.sangiorgisrl.screenlink.ssh.KeyValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Add a new SSH private key (paste the PEM, optional passphrase / public key). */
class KeyFormViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as ScreenLinkApp).repository

    data class KeyFormState(
        val name: String = "",
        val privateKey: String = "",
        val passphrase: String = "",
        val publicKey: String = "",
        val showKey: Boolean = false,
        val showPassphrase: Boolean = false,
        val isLoading: Boolean = false,
        val error: UiText? = null,
    )

    private val _state = MutableStateFlow(KeyFormState())
    val state: StateFlow<KeyFormState> = _state.asStateFlow()

    fun updateName(value: String) = _state.update { it.copy(name = value) }
    fun updatePrivateKey(value: String) = _state.update { it.copy(privateKey = value) }
    fun updatePassphrase(value: String) = _state.update { it.copy(passphrase = value) }
    fun updatePublicKey(value: String) = _state.update { it.copy(publicKey = value) }
    fun toggleKeyVisibility() = _state.update { it.copy(showKey = !it.showKey) }
    fun togglePassphraseVisibility() = _state.update { it.copy(showPassphrase = !it.showPassphrase) }

    private fun validate(): UiText? {
        val s = _state.value
        return when {
            s.name.isBlank() -> UiText.Resource(R.string.err_key_name)
            !s.privateKey.contains("PRIVATE KEY") || !s.privateKey.trimStart().startsWith("-----BEGIN") ->
                UiText.Resource(R.string.err_key_pem)
            else -> null
        }
    }

    /**
     * Validate the key by asking JSch to load it (also checks the passphrase),
     * then persist it encrypted. Calls [onDone] with true on success.
     */
    fun save(onDone: (Boolean) -> Unit) {
        val validation = validate()
        if (validation != null) {
            _state.update { it.copy(error = validation) }
            onDone(false)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(error = null, isLoading = true) }
            val s = _state.value
            val ok = withContext(Dispatchers.IO) {
                when (val result = KeyValidation.validate(s.name, s.privateKey, s.passphrase)) {
                    is KeyValidationResult.Valid -> true
                    is KeyValidationResult.Invalid -> {
                        _state.value = _state.value.copy(error = result.text)
                        false
                    }
                }
            }
            if (ok) {
                repository.saveKey(
                    name = s.name,
                    privateKeyPlain = s.privateKey,
                    publicKeyPlain = s.publicKey.ifBlank { null },
                    passphrasePlain = s.passphrase,
                )
            }
            _state.update { it.copy(isLoading = false) }
            onDone(ok)
        }
    }

    private fun MutableStateFlow<KeyFormState>.update(transform: (KeyFormState) -> KeyFormState) {
        this.value = transform(this.value)
    }
}
