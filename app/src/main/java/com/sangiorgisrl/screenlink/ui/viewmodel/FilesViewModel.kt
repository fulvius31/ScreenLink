package com.sangiorgisrl.screenlink.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.ScreenLinkApp
import com.sangiorgisrl.screenlink.data.ConnectionEntity
import com.sangiorgisrl.screenlink.i18n.UiText
import com.sangiorgisrl.screenlink.i18n.uiArg
import com.sangiorgisrl.screenlink.transfer.BrowseGeneration
import com.sangiorgisrl.screenlink.transfer.BrowseSession
import com.sangiorgisrl.screenlink.transfer.DeviceFolderLabel
import com.sangiorgisrl.screenlink.transfer.FabReadiness
import com.sangiorgisrl.screenlink.transfer.LocalItem
import com.sangiorgisrl.screenlink.transfer.RemoteEntry
import com.sangiorgisrl.screenlink.transfer.RemoteFilter
import com.sangiorgisrl.screenlink.transfer.RemoteSelection
import com.sangiorgisrl.screenlink.transfer.RemotePaths
import com.sangiorgisrl.screenlink.transfer.ShareInbox
import com.sangiorgisrl.screenlink.transfer.TransferActionGuard
import com.sangiorgisrl.screenlink.transfer.TransferDirection
import com.sangiorgisrl.screenlink.transfer.TransferStatus
import com.sangiorgisrl.screenlink.transfer.TransferStore
import com.sangiorgisrl.screenlink.transfer.TransferTransport
import com.sangiorgisrl.screenlink.transfer.friendlyBrowseError
import com.sangiorgisrl.screenlink.transfer.friendlyFolderCreateError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the file-transfer ("scp") screen: pick a connection, browse the server's
 * folders, choose what to send or what to copy back, and watch the queue.
 *
 * Two lifetimes deliberately differ here:
 *  - the BROWSING session belongs to this view model (screen-scoped, closed in
 *    [onCleared]) and every call is serialised by a mutex, because a JSch SFTP
 *    channel is single-threaded;
 *  - the COPIES belong to the application-scoped [TransferStore], so a transfer
 *    keeps running - and keeps reporting progress - after this screen is gone.
 */
class FilesViewModel(
    application: Application,
    initialConnectionId: Long,
) : AndroidViewModel(application) {

    private val app = application as ScreenLinkApp
    private val repository = app.repository
    private val local = app.localFiles
    private val transfers = app.transfers

    /** Saved connections, for the picker. */
    val connections: StateFlow<List<ConnectionEntity>> = repository.connections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Every queued / running / finished copy (what drives the top-bar badge).
     *
     * Read straight off the application-scoped queue, with no `stateIn`: that flow is
     * already hot, while a share opens every subscription on the `emptyList()` it was
     * given, which would blink the badge to "nothing to see" over a queue that is
     * copying. See the note on [TransferStore.jobs].
     */
    val jobs: StateFlow<List<TransferStore.Job>> = transfers.jobs

    /** Where the remote browser currently is. */
    sealed interface BrowseState {
        data object Idle : BrowseState
        data object Loading : BrowseState
        data class Ready(val path: String, val entries: List<RemoteEntry>) : BrowseState
        data class Error(val path: String?, val message: UiText) : BrowseState
    }

    data class UiState(
        val connectionId: Long = -1L,
        val mode: TransferDirection = TransferDirection.Upload,
        /** Files waiting to be sent (picked in-app or handed over by the share sheet). */
        val pendingFiles: List<LocalItem> = emptyList(),
        /** Shared files still being copied into the app cache. */
        val preparingShares: Int = 0,
        val deviceFolderUri: String? = null,
        val deviceFolderName: String? = null,
        /**
         * Remote files ticked for "copy to device" (path -> entry).
         *
         * The entry travels with the tick so the queue can be built even after the
         * user navigated somewhere else - see [RemoteSelection].
         */
        val selected: Map<String, RemoteEntry> = emptyMap(),
        /**
         * Text typed into the search box of the folder listing.
         *
         * It is a filter on the listing that is on screen, never part of the path:
         * the folder the upload lands in stays the folder the server reported,
         * whatever the box says.
         */
        val query: String = "",
        val browse: BrowseState = BrowseState.Idle,
        val busy: Boolean = false,
    )

    private val _state = MutableStateFlow(
        UiState(connectionId = if (initialConnectionId > 0L) initialConnectionId else -1L),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * One snackbar: the sentence, plus the single action that sentence may need.
     *
     * Only the "queued" messages carry an action. A batch that was just sent is the
     * one moment the user asks "where did my files go?" - the progress rows no longer
     * live at the bottom of this screen, so the answer has to be one tap from the
     * message that raised the question, not a hunt through the top bar.
     */
    data class SnackbarMessage(val text: UiText, val actionLabel: UiText? = null)

    private val _snackbar = Channel<SnackbarMessage>(Channel.BUFFERED)
    val snackbar: Channel<SnackbarMessage> = _snackbar

    /**
     * The SFTP session the browser talks through. [BrowseSession] owns the two
     * rules that used to live here - every call hops off the main thread (JSch on
     * the UI thread dies with `NetworkOnMainThreadException`, which the user reads
     * as "Could not open the folder"), and one call at a time on one channel - so
     * a new button cannot forget them.
     */
    private val browse = BrowseSession(open = { ensureTransport() })

    /**
     * Which browse answer still counts (see [BrowseGeneration]): a late listing from
     * the folder - or the server - the user left must not become the folder the next
     * upload writes into.
     */
    private val browseGeneration = BrowseGeneration()

    /** Share URIs this screen has already announced (so a refresh is not a new share). */
    private val seenShares = mutableSetOf<String>()
    /** Transfer rows whose effect on the listing has already been applied. */
    private val settledRefreshed = mutableSetOf<String>()

    /** The connection row was deleted while the screen was open. */
    private class ConnectionGoneException : Exception()

    init {
        // The inbox IS the "Files to send" list (see [ShareInbox]): collected rather
        // than read once, so a share that lands while this screen is open appears
        // immediately, and what the user staged survives leaving the screen instead
        // of being silently thrown away the first time it was consumed.
        viewModelScope.launch {
            ShareInbox.incoming.collect { items ->
                val fresh = items.filter { it.uri !in seenShares }
                seenShares += items.map { it.uri }
                _state.update { s ->
                    s.copy(
                        pendingFiles = items,
                        // A file that arrives from a share sheet is obviously meant
                        // for the server, so the mode follows it - but only for a
                        // file that is actually new, never for a plain recomposition.
                        mode = if (fresh.isNotEmpty() && s.mode != TransferDirection.Upload) {
                            TransferDirection.Upload
                        } else {
                            s.mode
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            ShareInbox.preparing.collect { count -> _state.update { it.copy(preparingShares = count) } }
        }
        // One-shot share outcomes. A share that arrived with three of five files, or
        // with nothing the app can copy at all, has to be said out loud: silence is
        // what makes a user re-share the same file ten times.
        viewModelScope.launch {
            for (report in ShareInbox.reports) {
                val message = when {
                    report.nothingToStage -> UiText.Resource(R.string.files_share_unsupported)
                    report.failed > 0 -> UiText.Plural(R.plurals.files_share_partial, report.failed)
                    report.staged > 0 -> UiText.Plural(R.plurals.files_shared_ready, report.staged)
                    else -> null
                }
                message?.let { say(it) }
            }
        }
        // A copy that finished changed the folder on screen: refresh the listing so
        // the new file is there without the user hunting for the reload button.
        viewModelScope.launch {
            transfers.jobs.collect { jobs ->
                val dir = (currentBrowse() as? BrowseState.Ready)?.path ?: return@collect
                val settledHere = jobs.filter { job ->
                    job.direction == TransferDirection.Upload &&
                        job.status != TransferStatus.Queued &&
                        job.status != TransferStatus.Running &&
                        RemotePaths.parent(job.remotePath) == dir &&
                        job.id !in settledRefreshed
                }
                if (settledHere.isEmpty()) return@collect
                settledRefreshed += settledHere.map { it.id }
                load(dir)
            }
        }
        if (_state.value.connectionId > 0L) load(null)
    }

    // --- selection ------------------------------------------------------------

    fun selectConnection(id: Long) {
        if (id == _state.value.connectionId) return
        // Anything the old server is still sending is void from here on, and the
        // old channel is handed back BEFORE the new browse starts, so the close
        // cannot drop the channel the new connection just opened.
        browseGeneration.next()
        _state.update {
            it.copy(connectionId = id, selected = emptyMap(), query = "", browse = BrowseState.Idle)
        }
        viewModelScope.launch {
            browse.close()
            load(null)
        }
    }

    fun setMode(mode: TransferDirection) {
        _state.update { s ->
            // Ticks only mean something in "Copy to device"; leaving them behind
            // while the user is in the other mode makes the action button lie.
            s.copy(mode = mode, selected = if (mode == TransferDirection.Upload) emptyMap() else s.selected)
        }
    }

    /** Called by the screen after the system document picker returned URIs. */
    fun addPickedFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                uris.mapNotNull { local.describe(it.toString()) }
            }
            if (items.isEmpty()) {
                say(UiText.Resource(R.string.err_transfer_not_found))
                return@launch
            }
            // Straight into the inbox: the screen renders the inbox, so the new rows
            // appear through the same path a share takes.
            ShareInbox.add(items)
        }
    }

    fun removePendingFile(uri: String) {
        ShareInbox.remove(uri)
        seenShares -= uri
        // Nothing is copying this file, so the app has no reason to keep a permission
        // that outlives a reboot. The check matters: re-picking a file that is already
        // halfway through a copy and then removing it must not pull the grant out from
        // under the running copy. A staged share copy (`file://`) holds no grant.
        val stillCopying = transfers.jobs.value.any {
            it.localUri == uri &&
                (it.status == TransferStatus.Queued || it.status == TransferStatus.Running)
        }
        if (!stillCopying) {
            // Giving the grant back is a binder call to whoever owns the file, so it
            // does not belong on the main thread either.
            viewModelScope.launch { withContext(Dispatchers.IO) { local.releaseReadGrant(uri) } }
        }
    }

    /**
     * The one device folder this screen may write into (SAF tree).
     *
     * Asking the provider for the folder's display name is a ContentResolver query,
     * i.e. a binder call to another process, so it goes to an IO thread too; the
     * folder itself is usable immediately even if the name never comes back.
     */
    fun setDeviceFolder(uri: Uri) {
        val value = uri.toString()
        // The card has to stop saying "No folder chosen yet" the moment the folder
        // IS chosen, so the label comes from the URI first (instant, no IO) and the
        // provider's own name only replaces it once it answers.
        _state.update {
            it.copy(deviceFolderUri = value, deviceFolderName = DeviceFolderLabel.fromTreeUri(value))
        }
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching { local.folderDisplayName(value) }.getOrNull()
            }
            // Only if the user has not picked a different folder in the meantime.
            if (_state.value.deviceFolderUri == value && !name.isNullOrBlank()) {
                _state.update { it.copy(deviceFolderName = name) }
            }
        }
    }

    fun toggleRemote(entry: RemoteEntry) {
        _state.update { s -> s.copy(selected = RemoteSelection.toggle(s.selected, entry)) }
    }

    // --- browsing -------------------------------------------------------------

    /**
     * Text of the search box.
     *
     * Kept in the state rather than in a composable so the box survives a
     * recomposition, a rotation and the listing arriving late - and so the filter
     * rules ([RemoteFilter]) can be applied where the listing is rendered.
     */
    fun setQuery(query: String) {
        if (query == _state.value.query) return
        _state.update { it.copy(query = query) }
    }

    fun clearQuery() {
        if (_state.value.query.isEmpty()) return
        _state.update { it.copy(query = "") }
    }

    fun openHome() {
        // Walking somewhere else is a new folder, so the old words are dropped with
        // it: a box that still says "pdf" over a listing that has none looks broken.
        clearQuery()
        load(null)
    }

    fun enter(entry: RemoteEntry) {
        clearQuery()
        load(entry.path)
    }

    fun goUp() {
        val parent = currentPath()?.let { RemotePaths.parent(it) } ?: return
        clearQuery()
        load(parent)
    }

    fun goTo(path: String) {
        clearQuery()
        load(path)
    }

    /** Reload of the folder already open: the search box KEEPS its words. */
    fun refresh() {
        // While a listing is still on its way there is no folder to reload: guessing
        // the home folder would silently walk the user out of the directory they are
        // waiting for. An ERROR state does keep its path, so "retry" still works.
        if (currentBrowse() is BrowseState.Loading) return
        load(currentPath())
    }

    /**
     * List [path], or the account's home when [path] is null.
     *
     * The listing itself happens inside [BrowseSession.use], i.e. on an IO thread;
     * everything in this function is state bookkeeping on the main thread.
     */
    private fun load(path: String?) {
        if (_state.value.connectionId <= 0L) return
        val ticket = browseGeneration.next()
        // A reload of the folder that is ALREADY on screen keeps that listing on
        // screen. Swapping it for Loading would throw away the three hundred rows the
        // user is reading, close the keyboard, snap the scroll back to the top and
        // dim the action button - for a refresh that ends in the very same folder.
        // The card still spins, because [busy] is set either way. Walking to ANOTHER
        // folder does go through Loading: the rows on screen belong to the folder the
        // user is leaving, and showing them under a new path is a lie.
        val sameFolder = path != null && (currentBrowse() as? BrowseState.Ready)?.path == path
        _state.update {
            it.copy(
                browse = if (sameFolder) it.browse else BrowseState.Loading,
                busy = true,
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                browse.use { opened ->
                    val target = path ?: opened.home()
                    target to opened.list(target)
                }
            }
            // A newer tap (or a connection change) already owns the screen: the
            // listing that just arrived belongs to a folder the user left.
            if (!browseGeneration.isCurrent(ticket)) return@launch
            result.onSuccess { (resolved, entries) ->
                _state.update {
                    it.copy(
                        browse = BrowseState.Ready(resolved, entries),
                        // Keep the user's ticks across a refresh, but re-read the
                        // entry (size, date) from the fresh listing.
                        selected = RemoteSelection.sync(it.selected, entries),
                        busy = false,
                    )
                }
            }.onFailure { error ->
                // The session already dropped the channel that failed, so the next
                // tap starts from a clean handshake instead of a half-dead one.
                val message = browseError(error)
                _state.update {
                    it.copy(browse = BrowseState.Error(path ?: currentPath(), message), busy = false)
                }
                say(message)
            }
        }
    }

    /** Create a folder in the folder currently open (the "make a dir, then send" flow). */
    fun createFolder(rawName: String) {
        val dir = (currentBrowse() as? BrowseState.Ready)?.path ?: return
        val trimmed = rawName.trim()
        val name = RemotePaths.sanitizeName(trimmed)
        // sanitizeName silently rewrites anything unsafe; a rewrite means the user
        // typed something a folder name cannot hold, so say so instead of creating
        // a surprise name.
        if (trimmed.isEmpty() || name != trimmed) {
            say(UiText.Resource(R.string.files_folder_name_invalid))
            return
        }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            // No retry here, unlike a browse: a mkdir whose reply was lost to a dead
            // link may already have happened on the server, and repeating it answers
            // "folder already exists" - a failure the user did not cause.
            val result = runCatching {
                browse.use(retryOnDeadLink = false) { it.mkdir(RemotePaths.join(dir, name)) }
            }
            _state.update { it.copy(busy = false) }
            result.onSuccess {
                say(UiText.Resource(R.string.files_folder_created, uiArg(name)))
                load(dir)
            }.onFailure { error ->
                // The nested arg is itself a UiText, so the reason is still
                // translated when the snackbar is finally shown.
                say(
                    UiText.Resource(R.string.files_folder_create_failed, folderCreateError(error)),
                )
            }
        }
    }

    private fun currentBrowse(): BrowseState = _state.value.browse

    private fun currentPath(): String? = when (val b = currentBrowse()) {
        is BrowseState.Ready -> b.path
        is BrowseState.Error -> b.path
        else -> null
    }

    /**
     * The sentence for a [FabReadiness] that is not [FabReadiness.Ready], null when
     * nothing is missing.
     *
     * Kept as resource ids so the wording stays in strings.xml (and in Spanish), and
     * so the button and the view model cannot name two different problems.
     */
    private fun missingMessage(readiness: FabReadiness): Int? = when (readiness) {
        FabReadiness.Ready -> null
        FabReadiness.NeedsFiles -> R.string.files_snack_no_files
        FabReadiness.NeedsRemoteFolder -> R.string.files_snack_no_remote_folder
        FabReadiness.NeedsSelection -> R.string.files_snack_no_selection
        FabReadiness.NeedsDeviceFolder -> R.string.files_snack_no_folder
    }

    /** Worded for someone who was only opening a folder - nothing was copying. */
    private fun browseError(error: Throwable): UiText = when (error) {
        is ConnectionGoneException -> UiText.Resource(R.string.err_connection_missing)
        else -> friendlyBrowseError(error)
    }

    /** A mkdir IS a write, so it keeps the write wording inside its own sentence. */
    private fun folderCreateError(error: Throwable): UiText = when (error) {
        is ConnectionGoneException -> UiText.Resource(R.string.err_connection_missing)
        else -> friendlyFolderCreateError(error)
    }

    // --- queue actions --------------------------------------------------------

    /** Send the pending files into the folder currently open on the server. */
    fun startUpload() {
        val state = _state.value
        val files = state.pendingFiles
        val dir = (state.browse as? BrowseState.Ready)?.path
        // The action button is never greyed into silence (see the FAB note in
        // FilesScreen), so a tap that cannot go has to be answered with the sentence
        // naming what is missing. Which one is missing is decided by the SAME rule the
        // button uses to decide whether it looks live - otherwise the two drift apart
        // and the app refuses a tap for a reason the user cannot see.
        missingMessage(TransferActionGuard.upload(files.size, dir != null))?.let { blocker ->
            say(UiText.Resource(blocker))
            return
        }
        // The guard above already proved the folder is there; this only hands the
        // compiler a non-null value.
        val target = dir ?: return
        viewModelScope.launch {
            val connection = connectionEntity(state.connectionId)
            if (connection == null) {
                say(UiText.Resource(R.string.files_snack_no_connection))
                return@launch
            }
            transfers.enqueueUpload(connection.id, connection.displayName, target, files)
            // The inbox is the list, so the batch leaves it here - and only here: the
            // queue now owns the bytes and may still be reading them.
            ShareInbox.removeAll(files.map { it.uri })
            sayWithView(UiText.Plural(R.plurals.files_snack_upload_queued, files.size))
            // The listing is refreshed by the queue's own completion (see init), so
            // the file shows up when it really exists rather than as an empty row.
        }
    }

    /** Copy the ticked remote files into the one device folder the user picked. */
    fun copySelectedToDevice() {
        val state = _state.value
        // The ticks carry their own entry: the user can navigate away (or the folder
        // can fail to re-open) after ticking, and their selection must survive that.
        val entries = state.selected.values.toList()
        val folder = state.deviceFolderUri
        // Same rule as the button's colour, and in the same order: the ticks are
        // asked for first, because the device folder's own card already says in red
        // that nothing is chosen.
        missingMessage(TransferActionGuard.download(entries.size, folder != null))?.let { blocker ->
            say(UiText.Resource(blocker))
            return
        }
        val target = folder ?: return
        viewModelScope.launch {
            val connection = connectionEntity(state.connectionId)
            if (connection == null) {
                say(UiText.Resource(R.string.files_snack_no_connection))
                return@launch
            }
            transfers.enqueueDownload(connection.id, connection.displayName, target, entries)
            _state.update { it.copy(selected = emptyMap()) }
            sayWithView(UiText.Plural(R.plurals.files_snack_download_queued, entries.size))
        }
    }

    /**
     * The picker returned something whose permission could NOT be kept.
     *
     * Reported instead of ignored on purpose. `takePersistableUriPermission` fails on
     * providers that hand out a one-shot grant (and on a document the provider no
     * longer owns), and in both cases the copy still starts: the temporary grant the
     * result carried is enough while the process lives. What breaks is the queue that
     * outlives a reboot, i.e. the failure appears minutes later with no visible cause,
     * so the user has to be told at the moment of picking.
     *
     * Nothing else changes: the file is still queued and the folder is still set,
     * because both really do work right now.
     */
    fun reportUnpersistableGrant() {
        say(UiText.Resource(R.string.files_error_grant_not_persisted))
    }

    // --- one-shot messages ----------------------------------------------------

    /** Say something once, in the language of the screen that finally shows it. */
    private fun say(text: UiText) {
        _snackbar.trySend(SnackbarMessage(text))
    }

    /** The same, with the "View" action that opens the copy-queue screen. */
    private fun sayWithView(text: UiText) {
        _snackbar.trySend(SnackbarMessage(text, UiText.Resource(R.string.action_view)))
    }

    // --- internals ------------------------------------------------------------

    /**
     * The saved connection behind the picked id, with its decrypted credentials.
     *
     * Read from the repository rather than from [connections]: that flow is
     * `WhileSubscribed(5s)`, so its value can legitimately be empty at the moment a
     * button is pressed, and "Choose a connection first" would be a lie.
     */
    private suspend fun connectionEntity(id: Long): com.sangiorgisrl.screenlink.data.SshConnection? =
        if (id <= 0L) null else repository.getSshConnection(id)

    /**
     * Open a fresh browsing SFTP session through the pinned-host connector.
     *
     * Called by [BrowseSession] only when it has no channel to reuse, and only from
     * inside its lock. The connector already hops to an IO thread for the handshake.
     */
    private suspend fun ensureTransport(): TransferTransport {
        val id = _state.value.connectionId
        if (id <= 0L) throw ConnectionGoneException()
        val connection = repository.getSshConnection(id) ?: throw ConnectionGoneException()
        val keys = repository.getSshKeysForConnection(id)
        return app.transferTransports.open(connection, keys)
    }

    /** Hand the browsing channel back (connection changed, or the screen is gone). */
    private fun closeBrowseSession() {
        // NOT viewModelScope: [onCleared] runs this at the exact moment that scope is
        // already cancelled, so the launch would never run and the SSH session would
        // be left to the GC (a server holding a dead SFTP channel open).
        app.appScope.launch(Dispatchers.IO) { browse.close() }
    }

    override fun onCleared() {
        closeBrowseSession()
        super.onCleared()
    }
}
