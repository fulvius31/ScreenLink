package com.sangiorgisrl.screenlink.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.data.ConnectionEntity
import com.sangiorgisrl.screenlink.i18n.rememberResolved
import com.sangiorgisrl.screenlink.i18n.resolve
import com.sangiorgisrl.screenlink.transfer.LocalItem
import com.sangiorgisrl.screenlink.transfer.RemoteEntry
import com.sangiorgisrl.screenlink.transfer.RemoteFilter
import com.sangiorgisrl.screenlink.transfer.RemotePaths
import com.sangiorgisrl.screenlink.transfer.TransferActionGuard
import com.sangiorgisrl.screenlink.transfer.TransferDirection
import com.sangiorgisrl.screenlink.transfer.TransferFormatting
import com.sangiorgisrl.screenlink.transfer.TransferQueueView
import com.sangiorgisrl.screenlink.ui.adaptive.AdaptiveContent
import com.sangiorgisrl.screenlink.ui.viewmodel.FilesViewModel
import java.util.Locale

/**
 * The file-transfer screen ("scp" for a saved connection).
 *
 * Two directions, one shared remote browser:
 *  - **Send to server**: pick files (system picker, or just share them into the app
 *    from anywhere), browse to a folder, send.
 *  - **Copy to device**: tick remote files, pick ONE device folder (SAF - the app
 *    holds no storage permission), copy them down.
 *
 * This screen is the BROWSER. The progress rows are not here: they live on the copy
 * queue screen, one tap from the top bar (with a badge for what is still moving),
 * because a server folder is routinely pages long and "how is my copy going?" was
 * the last row of a list nobody scrolls to. The queue itself lives in the
 * application, so nothing here owns a transfer either.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    viewModel: FilesViewModel,
    onBack: () -> Unit,
    onAddConnection: () -> Unit,
    onOpenTransfers: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connections by viewModel.connections.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    // Sizes/speeds follow the language the UI is drawn in, not the process default.
    val locale: Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    // What the top-bar entry point has to say about the queue. Computed here rather
    // than in the view model because it is a rendering decision (see [TransferQueueView]).
    val queueBadge = remember(jobs) {
        TransferQueueView.badge(TransferQueueView.Summary.of(jobs))
    }

    var showNewFolderDialog by remember { mutableStateOf(false) }

    // Any file type: the document picker is opened with "*/*".
    val pickFiles = rememberLauncherForActivityResult(
        PersistableDocumentPicker,
    ) { uris ->
        // Persistable, so a copy that outlives this screen can still read the file.
        // A refusal is REPORTED rather than swallowed: the temporary grant the picker
        // handed over is enough to start the copy, so the cost of saying nothing is a
        // queue that comes back "file not found" after the next reboot - the worst
        // possible moment to discover it. One message per pick, not per URI.
        val refused = uris.count { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.isFailure
        }
        if (refused > 0) viewModel.reportUnpersistableGrant()
        viewModel.addPickedFiles(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(
        WriteableTreePicker,
    ) { uri ->
        if (uri != null) {
            val refused = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.isFailure
            // Same rule as the file picker, and worse here: without the write grant
            // every "copy to device" into this folder dies once the process restarts.
            // The folder is still set - the copies that run now really do work.
            if (refused) viewModel.reportUnpersistableGrant()
            viewModel.setDeviceFolder(uri)
        }
    }

    LaunchedEffect(Unit) {
        for (message in viewModel.snackbar) {
            val result = snackbarHostState.showSnackbar(
                message = message.text.resolve(resources),
                // A batch that was just queued is the one moment the user actually
                // wants the queue screen: the snackbar is where "where did my files
                // go?" is asked, so the answer is one tap away rather than a hunt
                // through the top bar.
                actionLabel = message.actionLabel?.resolve(resources),
            )
            if (result == SnackbarResult.ActionPerformed) onOpenTransfers()
        }
    }

    val selectedConnection = connections.firstOrNull { it.id == state.connectionId }

    // Filtering happens here rather than inside the rows: the count line, the
    // "nothing matches" row and the rows themselves must agree on ONE answer, and
    // [RemoteFilter] is pure enough to be pinned by JVM tests.
    val listState = rememberLazyListState()
    val readyEntries = (state.browse as? FilesViewModel.BrowseState.Ready)?.entries.orEmpty()
    val visibleEntries = remember(readyEntries, state.query) {
        RemoteFilter.filter(readyEntries, state.query)
    }

    // Two moments where the listing has to be re-anchored, and one where it must be
    // left alone. The rules are pure (see [ListingScrollAnchor]) because "where am I
    // standing in this list?" is exactly the question a device test cannot answer
    // twice the same way:
    //  - a NEW folder: inheriting the scroll offset of the 300-entry folder the user
    //    just left shows the middle of nowhere, which reads as a broken app. A reload
    //    of the folder ALREADY open must not jump at all, so the comparison is with
    //    the last folder that actually produced a listing;
    //  - a NEW filter word: the box is a sticky header, so it is already readable -
    //    the listing is only pulled back when it really has scrolled out of view;
    //  - a rotation: [openedPath] is saveable, so the listing that comes back is not
    //    mistaken for a new folder (which would snap the scroll to the top and throw
    //    away where the user had got to).
    var openedPath by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.browse) {
        val path = (state.browse as? FilesViewModel.BrowseState.Ready)?.path ?: return@LaunchedEffect
        if (path == openedPath) return@LaunchedEffect
        openedPath = path
        val keys = listState.layoutInfo.visibleItemsInfo.map { it.key }
        listState.scrollToItem(
            ListingScrollAnchor.folderAnchorIndex(
                browserIndex = ListingScrollAnchor.indexOfKey(
                    keys,
                    ListingScrollAnchor.BROWSER_CARD_KEY,
                ),
                searchIndex = ListingScrollAnchor.indexOfKey(
                    keys,
                    ListingScrollAnchor.SEARCH_ROW_KEY,
                ),
            ),
        )
    }
    LaunchedEffect(state.query) {
        if (state.query.isEmpty()) return@LaunchedEffect
        val keys = listState.layoutInfo.visibleItemsInfo.map { it.key }
        val anchor = ListingScrollAnchor.queryAnchorIndex(
            searchIndex = ListingScrollAnchor.indexOfKey(keys, ListingScrollAnchor.SEARCH_ROW_KEY),
            browserIndex = ListingScrollAnchor.indexOfKey(
                keys,
                ListingScrollAnchor.BROWSER_CARD_KEY,
            ),
        )
        // null means the box is already on screen: the rows stay exactly where the
        // user left them, instead of sliding under their fingers on every keystroke.
        if (anchor != null) listState.scrollToItem(anchor)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.files_title),
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (selectedConnection != null) {
                            Text(
                                text = "${selectedConnection.username}@${selectedConnection.host}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (state.connectionId > 0L) {
                        IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.action_refresh),
                            )
                        }
                    }
                    // Rightmost, so it stays under the thumb whatever else the bar
                    // shows, and always there: the queue is app-wide, so copies can be
                    // running for a server this screen is not even looking at.
                    TransfersEntry(badge = queueBadge, onOpen = onOpenTransfers)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // The one action this screen exists for, floating so it stays under a thumb
        // at any scroll position: it used to be the last row of a list that is
        // routinely longer than one screen.
        floatingActionButton = {
            if (state.connectionId > 0L) {
                TransferFab(
                    mode = state.mode,
                    uploadCount = state.pendingFiles.size,
                    downloadCount = state.selected.size,
                    remoteFolderOpen = state.browse is FilesViewModel.BrowseState.Ready,
                    deviceFolderChosen = state.deviceFolderUri != null,
                    onUpload = viewModel::startUpload,
                    onDownload = viewModel::copySelectedToDevice,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        // The keyboard is part of the listing's available height.
        //
        // The activity is edge-to-edge, so the window is NOT resized when the IME
        // appears - the keyboard arrives as an inset instead, and a Scaffold that only
        // consumes the system bars lets the keyboard cover the bottom half of the
        // results the user just typed a search for. Union (not extra padding) matters:
        // the IME inset already contains the navigation bar, so adding the two would
        // reserve the nav bar twice.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
    ) { padding ->
        AdaptiveContent(modifier = Modifier.padding(padding)) { _, contentModifier ->
            LazyColumn(
                state = listState,
                // The listing IS a pane whose content changes under the user's
                // fingers (a new folder, a filter word). Naming it with the folder it
                // shows is what lets a screen reader say WHERE the change happened
                // instead of just that something moved.
                modifier = contentModifier.semantics {
                    (state.browse as? FilesViewModel.BrowseState.Ready)?.path?.let { paneTitle = it }
                },
                // The bottom band belongs to the FAB: without it the last transfer
                // row would sit permanently under the button.
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "mode") {
                    ModeSelector(mode = state.mode, onMode = viewModel::setMode)
                }

                if (connections.isEmpty()) {
                    item(key = "no-connections") {
                        EmptyConnectionsCard(onAddConnection = onAddConnection)
                    }
                    return@LazyColumn
                }

                item(key = "connection") {
                    ConnectionPicker(
                        connections = connections,
                        selected = selectedConnection,
                        onSelect = viewModel::selectConnection,
                    )
                }

                when (state.mode) {
                    TransferDirection.Upload -> item(key = "pending") {
                        FilesToSendCard(
                            files = state.pendingFiles,
                            preparing = state.preparingShares,
                            locale = locale,
                            onAdd = { pickFiles.launch(arrayOf("*/*")) },
                            onRemove = viewModel::removePendingFile,
                        )
                    }

                    TransferDirection.Download -> item(key = "device-folder") {
                        DeviceFolderCard(
                            folderName = state.deviceFolderName,
                            chosen = state.deviceFolderUri != null,
                            onChoose = { pickFolder.launch(null) },
                        )
                    }
                }

                item(key = ListingScrollAnchor.BROWSER_CARD_KEY) {
                    BrowserCard(
                        state = state.browse,
                        mode = state.mode,
                        selectedCount = state.selected.size,
                        busy = state.busy,
                        canBrowse = state.connectionId > 0L,
                        onUp = viewModel::goUp,
                        onCrumb = viewModel::goTo,
                        onNewFolder = { showNewFolderDialog = true },
                        onRetry = viewModel::refresh,
                    )
                }

                val ready = state.browse as? FilesViewModel.BrowseState.Ready
                if (ready != null && ready.entries.isNotEmpty()) {
                    // A STICKY header: on a server folder with three hundred entries
                    // the box has to stay put, or the user cannot read what they
                    // typed while watching the rows change under it.
                    stickyHeader(key = ListingScrollAnchor.SEARCH_ROW_KEY) {
                        SearchRow(
                            query = state.query,
                            shown = visibleEntries.size,
                            total = ready.entries.size,
                            onQuery = viewModel::setQuery,
                            onClear = viewModel::clearQuery,
                        )
                    }
                }
                if (ready != null) {
                    when {
                        ready.entries.isEmpty() -> item(key = "empty-dir") { EmptyDirRow() }

                        visibleEntries.isEmpty() -> item(key = "no-matches") {
                            // The words are echoed back so it is clear the FILTER,
                            // not the server, is what emptied the list.
                            NoMatchesRow(query = state.query, onClear = viewModel::clearQuery)
                        }

                        else -> items(visibleEntries, key = { it.path }) { entry ->
                            RemoteEntryRow(
                                entry = entry,
                                locale = locale,
                                selectable = state.mode == TransferDirection.Download,
                                selected = entry.path in state.selected,
                                onClick = {
                                    // Tapping a row is an answer to the search, so the
                                    // keyboard goes away with it: force=true, because a
                                    // plain clearFocus() can hand focus to the next
                                    // field and leave the IME exactly where it was -
                                    // over the folder that just opened.
                                    focusManager.clearFocus(force = true)
                                    // A folder opens; a file toggles its tick.
                                    if (entry.isDirectory) viewModel.enter(entry) else viewModel.toggleRemote(entry)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewFolderDialog) {
        var name by remember { mutableStateOf("") }
        val path = (state.browse as? FilesViewModel.BrowseState.Ready)?.path ?: ""
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            icon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
            title = { Text(stringResource(R.string.files_new_folder_title, path)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.files_new_folder_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createFolder(name)
                        showNewFolderDialog = false
                    },
                    enabled = name.isNotBlank(),
                ) {
                    Text(stringResource(R.string.files_new_folder_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

// --- pieces ------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(mode: TransferDirection, onMode: (TransferDirection) -> Unit) {
    val options = listOf(TransferDirection.Upload, TransferDirection.Download)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = mode == option,
                onClick = { onMode(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                icon = {
                    Icon(
                        imageVector = if (option == TransferDirection.Upload) {
                            Icons.Outlined.FileUpload
                        } else {
                            Icons.Outlined.FileDownload
                        },
                        contentDescription = null,
                    )
                },
            ) {
                Text(
                    text = stringResource(
                        if (option == TransferDirection.Upload) R.string.files_mode_send
                        else R.string.files_mode_receive,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionPicker(
    connections: List<ConnectionEntity>,
    selected: ConnectionEntity?,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            // Read-only: it is a picker, not a text field.
            value = selected?.displayName ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.files_connection)) },
            leadingIcon = { Icon(Icons.Outlined.Dns, contentDescription = null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            supportingText = if (selected == null) {
                { Text(stringResource(R.string.files_browse_idle)) }
            } else {
                null
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            connections.forEach { connection ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(connection.displayName)
                            Text(
                                text = "${connection.username}@${connection.host}:${connection.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(connection.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyConnectionsCard(onAddConnection: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.files_no_connections_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.files_no_connections_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onAddConnection, shape = MaterialTheme.shapes.medium) {
                Text(stringResource(R.string.files_no_connections_action))
            }
        }
    }
}

@Composable
private fun FilesToSendCard(
    files: List<LocalItem>,
    preparing: Int,
    locale: Locale,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.files_files_to_send),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onAdd, shape = MaterialTheme.shapes.small) {
                    Text(stringResource(R.string.files_add_files))
                }
            }
            if (files.isEmpty() && preparing == 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.files_no_files_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            files.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // A staged copy came through the share sheet: say where it is from.
                        val fromShare = item.uri.startsWith("file://")
                        Text(
                            text = if (fromShare) {
                                stringResource(R.string.files_shared_from_share)
                            } else {
                                TransferFormatting.bytes(item.sizeBytes, locale)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onRemove(item.uri) }) {
                        Icon(
                            Icons.Outlined.Cancel,
                            contentDescription = stringResource(R.string.files_remove_file, item.name),
                        )
                    }
                }
            }
            if (preparing > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = pluralStringResource(R.plurals.files_shared_pending, preparing, preparing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceFolderCard(
    folderName: String?,
    chosen: Boolean,
    onChoose: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.files_device_folder),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // "No folder chosen yet" is only true while NO folder is chosen.
                    // A folder whose name the provider would not give still has to read
                    // as chosen, or the user goes back through the picker believing the
                    // tap was ignored.
                    Text(
                        text = when {
                            folderName != null -> folderName
                            chosen -> stringResource(R.string.files_device_folder_unnamed)
                            else -> stringResource(R.string.files_no_device_folder)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (chosen) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(onClick = onChoose, shape = MaterialTheme.shapes.small) {
                    Text(stringResource(R.string.files_choose_folder))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.files_device_folder_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BrowserCard(
    state: FilesViewModel.BrowseState,
    mode: TransferDirection,
    selectedCount: Int,
    busy: Boolean,
    canBrowse: Boolean,
    onUp: () -> Unit,
    onCrumb: (String) -> Unit,
    onNewFolder: () -> Unit,
    onRetry: () -> Unit,
) {
    val path = when (state) {
        is FilesViewModel.BrowseState.Ready -> state.path
        is FilesViewModel.BrowseState.Error -> state.path
        else -> null
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.files_remote_folder),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = path ?: stringResource(R.string.files_browse_idle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state is FilesViewModel.BrowseState.Loading || busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            if (path != null) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(onClick = onUp, enabled = RemotePaths.parent(path) != null) {
                        Icon(
                            Icons.Outlined.ArrowUpward,
                            contentDescription = stringResource(R.string.files_browse_up),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    // Breadcrumbs: every ancestor is one tap away (deep paths scroll
                    // sideways instead of wrapping into the card).
                    val crumbs = RemotePaths.breadcrumbs(path)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        crumbs.forEachIndexed { index, crumb ->
                            if (index > 0) {
                                Text(
                                    text = "/",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                            }
                            val isCurrent = crumb.second == path
                            Text(
                                text = crumb.first,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isCurrent) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier
                                    .clickable(enabled = !isCurrent) { onCrumb(crumb.second) }
                                    .padding(horizontal = 2.dp),
                            )
                        }
                    }
                    if (mode == TransferDirection.Upload) {
                        FilledTonalIconButton(onClick = onNewFolder) {
                            Icon(
                                Icons.Outlined.CreateNewFolder,
                                contentDescription = stringResource(R.string.files_new_folder),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            when (state) {
                is FilesViewModel.BrowseState.Idle -> Text(
                    text = stringResource(R.string.files_browse_idle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is FilesViewModel.BrowseState.Loading -> Text(
                    text = stringResource(R.string.files_connecting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is FilesViewModel.BrowseState.Error -> Column {
                    Text(
                        text = stringResource(R.string.files_browser_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = state.message.rememberResolved(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRetry, enabled = canBrowse, shape = MaterialTheme.shapes.small) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_retry))
                    }
                }

                is FilesViewModel.BrowseState.Ready -> Column {
                    Text(
                        text = stringResource(
                            if (mode == TransferDirection.Upload) R.string.files_remote_folder_help
                            else R.string.files_tap_to_select,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Ticking files must be visible: the count is what tells the user
                    // the tap registered, and it matches the number in the action button.
                    if (mode == TransferDirection.Download && selectedCount > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = pluralStringResource(
                                R.plurals.files_files_selected,
                                selectedCount,
                                selectedCount,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            // A polite live region: this count is the only proof a tap
                            // registered, so a screen reader has to re-read it when it
                            // changes rather than leave the user guessing.
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDirRow() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.files_empty_dir),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Composable
private fun RemoteEntryRow(
    entry: RemoteEntry,
    locale: Locale,
    selectable: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier
            .fillMaxWidth()
            // Only what actually does something is tappable: a folder always opens,
            // a file only toggles while "Copy to device" is the active mode.
            .clickable(enabled = entry.isDirectory || (selectable && entry.isCopyable), onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = if (entry.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!entry.isDirectory) {
                    Text(
                        text = TransferFormatting.bytes(entry.sizeBytes, locale),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selectable && !entry.isDirectory && entry.isCopyable) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    // The row's text is not read out with the box, so without this a
                    // screen reader says "checkbox, unchecked" about an unknown file.
                    modifier = Modifier.semantics { contentDescription = entry.name },
                )
            } else if (entry.isDirectory) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * The floating send / copy button.
 *
 * It is deliberately never switched off: Material 3 has no disabled FAB, and a
 * greyed rectangle that swallows the tap is the worse half of this trade. The
 * label carries the count (so the reason nothing can go yet is visible), the
 * colour dims while there is nothing to act on, and an empty tap is answered by
 * the view model with the sentence that names what is missing.
 *
 * "Is there something to act on" is asked of [TransferActionGuard] - the very rule
 * the view model uses to refuse a tap. A button that goes live on a weaker rule than
 * the one that accepts the tap promises a copy and then refuses it, which is how
 * "I picked the files, why does it tell me to pick files?" bugs are born.
 */
@Composable
private fun TransferFab(
    mode: TransferDirection,
    uploadCount: Int,
    downloadCount: Int,
    remoteFolderOpen: Boolean,
    deviceFolderChosen: Boolean,
    onUpload: () -> Unit,
    onDownload: () -> Unit,
) {
    val upload = mode == TransferDirection.Upload
    val count = if (upload) uploadCount else downloadCount
    val readiness = if (upload) {
        TransferActionGuard.upload(uploadCount, remoteFolderOpen)
    } else {
        TransferActionGuard.download(downloadCount, deviceFolderChosen)
    }
    val hasWork = TransferActionGuard.isReady(readiness)
    val label = when {
        count == 0 -> stringResource(if (upload) R.string.files_fab_send else R.string.files_fab_copy)
        upload -> pluralStringResource(R.plurals.files_fab_send_n, count, count)
        else -> pluralStringResource(R.plurals.files_fab_copy_n, count, count)
    }
    ExtendedFloatingActionButton(
        onClick = if (upload) onUpload else onDownload,
        expanded = true,
        shape = MaterialTheme.shapes.large,
        containerColor = if (hasWork) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (hasWork) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        icon = {
            Icon(
                imageVector = if (upload) Icons.Outlined.FileUpload else Icons.Outlined.FileDownload,
                contentDescription = null,
            )
        },
        // Spanish runs longer than English ("Copiar 3 archivos"); an ellipsis beats
        // the button clipping a word in half at the edge of the screen.
        text = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

/**
 * The search box of the folder listing, drawn as a sticky header.
 *
 * The count line appears only while something IS filtered ("Showing 12 of 340"):
 * that is the difference between "the filter works" and "the listing broke", and it is
 * the first thing a user wants after the rows jump under their fingers. It follows the
 * WORDS rather than the row count, so a query that happens to match everything still
 * says so instead of looking like the box did nothing.
 */
@Composable
private fun SearchRow(
    query: String,
    shown: Int,
    total: Int,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
) {
    val filtering = RemoteFilter.isFiltering(query)
    val focusManager = LocalFocusManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // A sticky header floats over the rows scrolling under it, so it needs
            // the screen background or the listing shows through the box.
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 6.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.files_search_hint)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = onClear) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.files_search_clear),
                        )
                    }
                }
            } else {
                null
            },
            singleLine = true,
            // The keyboard offers "search" rather than "enter", and the action hands
            // the screen back: an IME parked over a listing the user has just
            // filtered hides half of the answer they typed the query for.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { focusManager.clearFocus() },
            ),
            shape = MaterialTheme.shapes.large,
            supportingText = if (filtering) {
                {
                    Text(
                        text = stringResource(R.string.files_search_matches, shown, total),
                        // Polite live region: the number is the only proof the box
                        // is doing anything, so a screen reader re-reads it.
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            } else {
                null
            },
        )
    }
}

/** An empty listing caused by WORDS is not an empty folder: say which one it is. */
@Composable
private fun NoMatchesRow(query: String, onClear: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.files_search_no_matches, query),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.files_search_reset))
            }
        }
    }
}
