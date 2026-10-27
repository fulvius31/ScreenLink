package com.sangiorgisrl.screenlink.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jcraft.jsch.Session
import com.sangiorgisrl.screenlink.ScreenLinkApp
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.i18n.UiText
import com.sangiorgisrl.screenlink.i18n.uiArg
import com.sangiorgisrl.screenlink.data.ConnectionEntity
import com.sangiorgisrl.screenlink.ssh.ScreenSession
import com.sangiorgisrl.screenlink.ssh.friendlySshError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connects to a saved connection and drives the GNU Screen session list:
 * listing, creating, detaching and deleting sessions on the remote host.
 */
class OverviewViewModel(
    application: Application,
    private val connectionId: Long,
) : AndroidViewModel(application) {

    private val app = application as ScreenLinkApp
    private val repository = app.repository
    private val ssh = app.sshClient
    private val sshConnector = app.sshConnector

    sealed interface OverviewState {
        data object Loading : OverviewState
        data class Connected(
            val hasScreen: Boolean,
            val sessions: List<ScreenSession>,
            val fingerprint: String?,
            val isBusy: Boolean = false,
        ) : OverviewState
        data class Error(val message: UiText) : OverviewState
    }

    private val _state = MutableStateFlow<OverviewState>(OverviewState.Loading)
    val state: StateFlow<OverviewState> = _state.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionEntity?>(null)
    val connection: StateFlow<ConnectionEntity?> = _connection.asStateFlow()

    private val _snackbar = Channel<UiText>(Channel.BUFFERED)
    val snackbar: Channel<UiText> = _snackbar

    private var jschSession: Session? = null

    init {
        viewModelScope.launch {
            _connection.value = repository.get(connectionId)
            connect()
        }
    }

    fun retry() = viewModelScope.launch { connect() }

    private suspend fun connect() {
        _state.value = OverviewState.Loading
        val connection = _connection.value
        if (connection == null) {
            _state.value = OverviewState.Error(UiText.Resource(R.string.err_connection_missing))
            return
        }
        try {
            runCatching { jschSession?.disconnect() }
            // Decrypted credentials are produced by the repository (the only crypto
            // site); the entity flow above stays for display only.
            val sshConnection = repository.getSshConnection(connectionId)
                ?: run {
                    _state.value = OverviewState.Error(UiText.Resource(R.string.err_connection_missing))
                    return
                }
            val credentials = repository.getSshKeysForConnection(connectionId)
            // The ONE way to connect: SshConnector enforces the pin during the
            // handshake, asks the user to confirm a key that has never been seen,
            // and writes the pin only for a key that was confirmed or already
            // trusted. A genuinely changed key throws before this line returns,
            // so the pin can never be silently overwritten from here.
            val session = sshConnector.connect(sshConnection, credentials)
            jschSession = session
            // What is left is the display copy of the fingerprint, which the
            // connector has already verified against the pin.
            val fingerprint = withContext(Dispatchers.IO) { ssh.serverFingerprint(session) }
            _connection.value = repository.get(connectionId)
                ?: connection.copy(serverFingerprint = fingerprint)
            refreshSessions(session)
        } catch (e: Exception) {
            _state.value = OverviewState.Error(friendlySshError(e))
        }
    }

    private suspend fun refreshSessions(session: Session) {
        try {
            val hasScreen = withContext(Dispatchers.IO) { ssh.isScreenInstalled(session) }
            val sessions = if (hasScreen) {
                withContext(Dispatchers.IO) { ssh.listScreenSessions(session) }
            } else {
                emptyList()
            }
            _state.value = OverviewState.Connected(
                hasScreen = hasScreen,
                sessions = sessions,
                fingerprint = _connection.value?.serverFingerprint,
            )
        } catch (e: Exception) {
            _state.value = OverviewState.Error(friendlySshError(e))
        }
    }

    fun refresh() = viewModelScope.launch {
        _state.value = (_state.value as? OverviewState.Connected)?.copy(isBusy = true) ?: _state.value
        val session = jschSession
        if (session == null || !session.isConnected) {
            // The old tunnel died (NAT timeout, server restart, keepalive drop) -
            // reconnect and re-list instead of silently showing a stale list.
            connect()
        } else {
            refreshSessions(session)
        }
    }

    /**
     * Release the SSH session when the user is about to open a terminal screen.
     * Prevents the overview keeping a second live tunnel open while the terminal
     * screen runs (the next Refresh reconnects automatically).
     */
    fun releaseSshSession() {
        runCatching { jschSession?.disconnect() }
        jschSession = null
    }

    fun createSession(name: String) = viewModelScope.launch {
        setBusy(true)
        val session = jschSession
        if (session == null) {
            setBusy(false)
            return@launch
        }
        val result = runCatching { withContext(Dispatchers.IO) { ssh.createScreenSession(session, name) } }
        setBusy(false)
        val res = result.getOrNull()
        _snackbar.trySend(
            when {
                res == null -> UiText.Resource(
                    R.string.snack_create_failed,
                    friendlySshError(result.exceptionOrNull()!!),
                )
                res.exitStatus == 0 -> UiText.Resource(R.string.snack_created, uiArg(name))
                else -> UiText.Resource(
                    R.string.snack_create_failed,
                    uiArg(res.stderr.ifBlank { res.stdout }),
                )
            }
        )
        refresh()
    }

    fun killSession(sessionInfo: ScreenSession) = viewModelScope.launch {
        setBusy(true)
        val session = jschSession
        if (session == null) {
            setBusy(false)
            return@launch
        }
        val result = runCatching { withContext(Dispatchers.IO) { ssh.killScreenSession(session, sessionInfo.socket) } }
        setBusy(false)
        val res = result.getOrNull()
        _snackbar.trySend(
            when {
                res == null -> UiText.Resource(
                    R.string.snack_delete_failed,
                    friendlySshError(result.exceptionOrNull()!!),
                )
                res.exitStatus == 0 -> UiText.Resource(R.string.snack_deleted, uiArg(sessionInfo.socket))
                else -> UiText.Resource(
                    R.string.snack_delete_failed,
                    uiArg(res.stderr.ifBlank { res.stdout }),
                )
            }
        )
        refresh()
    }

    fun detachSession(sessionInfo: ScreenSession) = viewModelScope.launch {
        setBusy(true)
        val session = jschSession
        if (session == null) {
            setBusy(false)
            return@launch
        }
        val result = runCatching { withContext(Dispatchers.IO) { ssh.detachScreenSession(session, sessionInfo.socket) } }
        setBusy(false)
        val res = result.getOrNull()
        _snackbar.trySend(
            when {
                res == null -> UiText.Resource(
                    R.string.snack_detach_failed,
                    friendlySshError(result.exceptionOrNull()!!),
                )
                res.exitStatus == 0 -> UiText.Resource(R.string.snack_detached, uiArg(sessionInfo.socket))
                else -> UiText.Resource(
                    R.string.snack_detach_failed,
                    uiArg(res.stderr.ifBlank { res.stdout }),
                )
            }
        )
        refresh()
    }

    private fun setBusy(busy: Boolean) {
        _state.value = (_state.value as? OverviewState.Connected)?.copy(isBusy = busy) ?: _state.value
    }

    override fun onCleared() {
        runCatching { jschSession?.disconnect() }
        jschSession = null
        super.onCleared()
    }
}
