package com.sangiorgisrl.screenlink.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sangiorgisrl.screenlink.ScreenLinkApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class KeysViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as ScreenLinkApp).repository

    /**
     * UI model for the keys list. Deliberately carries only the persisted key-type
     * tag (never the private key), so rendering a row requires no decryption and
     * the composable never touches a secret.
     */
    data class KeyListItem(
        val id: Long,
        val name: String,
        val keyAlgo: String,
        val hasPassphrase: Boolean,
    )

    val keys: StateFlow<List<KeyListItem>> = repository.sshKeys
        .map { list ->
            list.map { e ->
                KeyListItem(
                    id = e.id,
                    name = e.name,
                    keyAlgo = e.keyAlgo,
                    hasPassphrase = e.passphraseEnc.isNotEmpty(),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteKey(item: KeyListItem) {
        viewModelScope.launch {
            repository.getKey(item.id)?.let { repository.deleteKey(it) }
        }
    }
}