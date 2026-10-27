package com.sangiorgisrl.screenlink.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sangiorgisrl.screenlink.ScreenLinkApp
import com.sangiorgisrl.screenlink.data.ConnectionEntity
import com.sangiorgisrl.screenlink.transfer.TransferStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as ScreenLinkApp).repository

    private val searchQuery = MutableStateFlow("")

    /** Exposed for the UI to bind the search field. */
    val searchText: StateFlow<String> = searchQuery.asStateFlow()

    val allConnections: StateFlow<List<ConnectionEntity>> = repository.connections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val connections: StateFlow<List<ConnectionEntity>> = combine(
        searchQuery, allConnections
    ) { query, list ->
        if (query.isBlank()) list
        else list.filter {
            it.displayName.contains(query, ignoreCase = true) ||
                it.host.contains(query, ignoreCase = true) ||
                it.username.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    /**
     * Every copy the queue still remembers.
     *
     * The queue is application-scoped, so a copy keeps running after the Files screen
     * is closed - Home is where the user needs to see that and get back in. Home needs
     * more than a running count: a batch that FAILED has to keep asking for attention
     * once nothing is moving, and how the rows are counted is a rendering decision
     * (see `transfer/TransferQueueView.kt`), so the rows are handed over and the
     * screen counts them.
     *
     * Handed over as-is, with no `stateIn`: `TransferStore.jobs` is already a hot
     * StateFlow holding the current queue, while a share starts every subscription on
     * the `emptyList()` it was given - the card would be missing for a frame over a
     * queue that is really running. See the note on [TransferStore.jobs].
     */
    val transfers: StateFlow<List<TransferStore.Job>> = (application as ScreenLinkApp).transfers.jobs

    /**
     * A connection that was just removed, kept (with its key links) so the UI can
     * offer "Undo" in a snackbar. Material 3 requires a destructive action to be
     * either confirmed beforehand or reversible afterwards - swipe-to-dismiss has
     * no confirmation step, so the list deletes are undoable instead.
     */
    data class DeletedConnection(val connection: ConnectionEntity, val keyIds: List<Long>)

    private val _deleted = Channel<DeletedConnection>(Channel.BUFFERED)
    val deleted: Channel<DeletedConnection> = _deleted

    fun deleteConnection(connection: ConnectionEntity) {
        viewModelScope.launch {
            // Capture the key links BEFORE deleting: the junction rows are removed
            // with the connection, and Undo has to put them back.
            val keyIds = repository.getKeyIdsForConnection(connection.id)
            repository.delete(connection)
            _deleted.send(DeletedConnection(connection, keyIds))
        }
    }

    /** Undo a deletion: re-inserts the row with its original id and key links. */
    fun restoreConnection(deleted: DeletedConnection) {
        viewModelScope.launch { repository.restore(deleted.connection, deleted.keyIds) }
    }
}
