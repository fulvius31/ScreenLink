package com.sangiorgisrl.screenlink.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sangiorgisrl.screenlink.ScreenLinkApp
import com.sangiorgisrl.screenlink.i18n.UiText
import com.sangiorgisrl.screenlink.data.ConnectionValidation
import com.sangiorgisrl.screenlink.data.SshKeyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Holds the add/edit connection form state and performs validation + persistence. */
class ConnectionFormViewModel(
    application: Application,
    private val connectionId: Long,
) : AndroidViewModel(application) {

    private val repository = (application as ScreenLinkApp).repository

    data class FormState(
        val displayName: String = "",
        val username: String = "",
        val host: String = "",
        val port: String = "22",
        val password: String = "",
        val screenSessionName: String = "",
        val showPassword: Boolean = false,
        /**
         * True when this is an edit of a connection that already has a stored
         * password. Lets the form explain that leaving the field empty keeps it.
         */
        val hasSavedPassword: Boolean = false,
        /**
         * Explicit "wipe the saved password" flag. Without an explicit action a
         * blank field on an edit silently keeps the old secret; the user must ask.
         * (Typing a new password turns this off again.)
         */
        val clearPassword: Boolean = false,
        val isLoading: Boolean = true,
        val error: UiText? = null,
        /**
         * Field errors stay hidden until the user has tried to save (M3: don't
         * scold before the first submit), then they recompute live so a field
         * stops reporting an error as soon as it is fixed.
         */
        val showFieldErrors: Boolean = false,
        val sshKeys: List<SshKeyEntity> = emptyList(),
        val selectedKeyIds: Set<Long> = emptySet(),
    )

    private val _state = MutableStateFlow(FormState())
    val state: StateFlow<FormState> = _state.asStateFlow()

    /** Toggle whether a saved key is associated with this connection (multi-select). */
    fun toggleKey(keyId: Long) = _state.update {
        val selected = it.selectedKeyIds
        val next = if (keyId in selected) selected - keyId else selected + keyId
        it.copy(selectedKeyIds = next)
    }

    val isEditing: Boolean get() = connectionId != 0L

    init {
        viewModelScope.launch {
            val existing = if (connectionId != 0L) repository.get(connectionId) else null
            val keys = repository.sshKeys.first()
            val selected = if (connectionId != 0L) repository.getKeyIdsForConnection(connectionId).toSet() else emptySet()
            _state.value = FormState(
                displayName = existing?.displayName ?: "",
                username = existing?.username ?: "",
                host = existing?.host ?: "",
                port = existing?.port?.toString() ?: "22",
                password = "",
                hasSavedPassword = existing?.passwordEnc?.isNotEmpty() == true,
                screenSessionName = existing?.screenSessionName ?: "",
                sshKeys = keys,
                selectedKeyIds = selected,
                isLoading = false,
            )
            // Keep the key list live (e.g. after adding a key elsewhere).
            repository.sshKeys.collect { updated ->
                _state.value = _state.value.copy(sshKeys = updated)
            }
        }
    }

    fun updateDisplayName(value: String) = _state.update { it.copy(displayName = value) }
    fun updateUsername(value: String) = _state.update { it.copy(username = value) }
    fun updateHost(value: String) = _state.update { it.copy(host = value) }
    fun updatePort(value: String) = _state.update {
        it.copy(port = ConnectionValidation.sanitizePort(value))
    }
    /** Typing a new password replaces the saved one, so any pending wipe is moot. */
    fun updatePassword(value: String) = _state.update {
        it.copy(password = value, clearPassword = if (value.isNotEmpty()) false else it.clearPassword)
    }
    fun updateScreenSessionName(value: String) = _state.update {
        it.copy(screenSessionName = value.replace(Regex("[\r\n\u0000]"), ""))
    }
    fun togglePasswordVisibility() = _state.update { it.copy(showPassword = !it.showPassword) }

    /** Flip the explicit "remove the saved password" flag (form show/clear only). */
    fun toggleClearPassword() = _state.update { it.copy(clearPassword = !it.clearPassword) }

    fun validate(): UiText? = ConnectionValidation.validate(
        displayName = _state.value.displayName,
        username = _state.value.username,
        host = _state.value.host,
        port = _state.value.port,
    )?.let { UiText.Resource(it) }

    /** Per-field messages, for the inline error state of each text field. */
    val fieldErrors: ConnectionValidation.FieldErrors
        get() = ConnectionValidation.validateFields(
            displayName = _state.value.displayName,
            username = _state.value.username,
            host = _state.value.host,
            port = _state.value.port,
        )

    /** Persist the form. Returns the saved connection id or null on failure. */
    fun save(onDone: (Long?) -> Unit) {
        val validation = validate()
        if (validation != null) {
            _state.update { it.copy(error = validation, showFieldErrors = true) }
            onDone(null)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(error = null, showFieldErrors = false, isLoading = true) }
            val s = _state.value
            val id = withContext(Dispatchers.IO) {
                repository.save(
                    id = connectionId,
                    displayName = s.displayName,
                    username = s.username,
                    host = s.host,
                    port = s.port.toInt(),
                    passwordPlain = s.password,
                    screenSessionName = s.screenSessionName.ifBlank { null },
                    keyIds = s.selectedKeyIds.toList(),
                    clearPassword = s.clearPassword,
                )
            }
            _state.update { it.copy(isLoading = false) }
            onDone(id)
        }
    }

    fun delete(onDone: () -> Unit) {
        if (connectionId == 0L) {
            onDone()
            return
        }
        viewModelScope.launch {
            repository.get(connectionId)?.let { repository.delete(it) }
            onDone()
        }
    }

    private fun MutableStateFlow<FormState>.update(transform: (FormState) -> FormState) {
        this.value = transform(this.value)
    }
}
