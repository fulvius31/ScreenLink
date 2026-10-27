package com.sangiorgisrl.screenlink.ui.screens

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.annotation.StringRes
import androidx.activity.compose.LocalActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.data.ConnectionEntity
import com.sangiorgisrl.screenlink.i18n.AppLocale
import com.sangiorgisrl.screenlink.session.LiveTerminalStore
import com.sangiorgisrl.screenlink.session.RecentSessions
import com.sangiorgisrl.screenlink.transfer.TransferQueueView
import com.sangiorgisrl.screenlink.ui.adaptive.AdaptiveContent
import com.sangiorgisrl.screenlink.ui.adaptive.AdaptiveLayout
import com.sangiorgisrl.screenlink.ui.theme.Emphasis
import com.sangiorgisrl.screenlink.ui.theme.statusColors
import com.sangiorgisrl.screenlink.ui.viewmodel.HomeViewModel

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenConnection: (Long) -> Unit,
    onNewConnection: () -> Unit,
    onEditConnection: (Long) -> Unit,
    onManageKeys: () -> Unit,
    onOpenRecent: (id: Long, mode: String, session: String?) -> Unit,
    onRemoveRecent: (id: Long, mode: String, session: String?) -> Unit,
    onOpenTerminal: () -> Unit,
    /**
     * Open the copy-queue screen. The card that calls this is only shown while the
     * queue has something to report (copies in flight, or copies that failed), so
     * this is the way back in exactly when the user needs it; the Files screen
     * carries the permanent top-bar entry with the badge.
     */
    onOpenTransfers: () -> Unit,
) {
    val connections by viewModel.connections.collectAsStateWithLifecycle()
    val transferJobs by viewModel.transfers.collectAsStateWithLifecycle()
    // What the queue looks like from Home. Counted by the SAME pure rule the queue
    // screen and the Files badge use, so "still copying" and "did not finish" mean
    // the same thing on every screen (see TransferQueueView).
    val transferSummary = remember(transferJobs) { TransferQueueView.Summary.of(transferJobs) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { RecentSessions.ensureLoaded(context.applicationContext) }
    val recents by RecentSessions.items.collectAsState()
    val runningTabs by LiveTerminalStore.tabs.collectAsStateWithLifecycle()

    // Material 3: a destructive action is either confirmed up front or reversible.
    // Swipe-to-dismiss has no confirmation step, so every delete (swipe or overflow
    // menu) reports itself in a snackbar with an Undo that puts the row back.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        for (deleted in viewModel.deleted) {
            val result = snackbarHostState.showSnackbar(
                message = context.getString(R.string.home_deleted_snackbar, deleted.connection.displayName),
                actionLabel = context.getString(R.string.action_undo),
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restoreConnection(deleted)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewConnection,
                shape = MaterialTheme.shapes.large,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = {
                    Text(stringResource(R.string.home_add_connection), fontWeight = FontWeight.SemiBold)
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // On tablets / landscape the list is fitted into a centered column and
        // the connections flow into an adaptive multi-column grid.
        AdaptiveContent(modifier = Modifier.padding(padding)) { layout, contentModifier ->
            HomeContent(
                modifier = contentModifier,
                layout = layout,
                connections = connections,
                onOpenConnection = onOpenConnection,
                onNewConnection = onNewConnection,
                onEditConnection = onEditConnection,
                onDeleteConnection = viewModel::deleteConnection,
                searchQuery = viewModel.searchText.collectAsStateWithLifecycle().value,
                onSearchQueryChange = viewModel::setSearchQuery,
                onManageKeys = onManageKeys,
                recents = recents,
                onOpenRecent = onOpenRecent,
                onRemoveRecent = onRemoveRecent,
                runningTabs = runningTabs,
                onOpenTerminal = onOpenTerminal,
                transferSummary = transferSummary,
                onOpenTransfers = onOpenTransfers,
            )
        }
    }
}

@Composable
private fun HomeContent(
    modifier: Modifier,
    layout: AdaptiveLayout,
    connections: List<ConnectionEntity>,
    onOpenConnection: (Long) -> Unit,
    onNewConnection: () -> Unit,
    onEditConnection: (Long) -> Unit,
    onDeleteConnection: (ConnectionEntity) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onManageKeys: () -> Unit,
    recents: List<RecentSessions.RecentSession>,
    onOpenRecent: (Long, String, String?) -> Unit,
    onRemoveRecent: (Long, String, String?) -> Unit,
    runningTabs: List<LiveTerminalStore.Tab>,
    onOpenTerminal: () -> Unit,
    transferSummary: TransferQueueView.Summary,
    onOpenTransfers: () -> Unit,
) {
    Column(modifier = modifier.padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_title),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = if (connections.isEmpty()) {
                        stringResource(R.string.home_tagline)
                    } else {
                        // A plural, not "%d saved": Spanish inflects it
                        // (1 guardada / 3 guardadas).
                        pluralStringResource(
                            R.plurals.home_saved_count,
                            connections.size,
                            connections.size,
                        )
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LanguageMenu()
            IconButton(onClick = onManageKeys) {
                Icon(
                    Icons.Outlined.Key,
                    contentDescription = stringResource(R.string.home_manage_keys),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        if (runningTabs.isNotEmpty()) {
            val anyActive = runningTabs.any { it.state is LiveTerminalStore.SessionState.Active }
            val anyConnecting = runningTabs.any { it.state is LiveTerminalStore.SessionState.Connecting }
            val status = statusColors()
            val dotColor = when {
                anyActive -> status.active
                anyConnecting -> status.connecting
                else -> status.idle
            }
            Card(
                onClick = onOpenTerminal,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.home_open_terminal),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = pluralStringResource(
                                R.plurals.home_running_sessions,
                                runningTabs.size,
                                runningTabs.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = Emphasis.MEDIUM_ALPHA),
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // --- File copies the queue still owns ----------------------------------
        // A copy outlives the Files screen on purpose (the queue lives in the app),
        // so Home has to say "something is still moving" and be the way back to it.
        // A batch that FAILED counts too: with nothing running that card is the only
        // thing on this screen that can tell the user a file never arrived.
        if (transferSummary.active > 0 || transferSummary.failed > 0) {
            Card(
                onClick = onOpenTransfers,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.home_open_transfers),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = if (transferSummary.active > 0) {
                                pluralStringResource(
                                    R.plurals.home_running_transfers,
                                    transferSummary.active,
                                    transferSummary.active,
                                )
                            } else {
                                pluralStringResource(
                                    R.plurals.home_failed_transfers,
                                    transferSummary.failed,
                                    transferSummary.failed,
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = Emphasis.MEDIUM_ALPHA),
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (recents.isNotEmpty()) {
            Text(
                text = stringResource(R.string.home_recent_sessions),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                recents.forEach { r ->
                    RecentChip(
                        session = r,
                        onClick = { onOpenRecent(r.connectionId, r.mode, r.sessionName) },
                        onRemove = { onRemoveRecent(r.connectionId, r.mode, r.sessionName) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        SearchField(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
        )
        Spacer(Modifier.height(8.dp))

        if (connections.isEmpty()) {
            EmptyState(searchActive = searchQuery.isNotBlank(), onNewConnection = onNewConnection)
        } else {
            if (layout.isWide) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 300.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    gridItems(connections, key = { it.id }) { connection ->
                        ConnectionRow(
                            connection = connection,
                            onOpen = { onOpenConnection(connection.id) },
                            onEdit = onEditConnection,
                            onDelete = onDeleteConnection,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(connections, key = { it.id }) { connection ->
                        ConnectionRow(
                            connection = connection,
                            onOpen = { onOpenConnection(connection.id) },
                            onEdit = onEditConnection,
                            onDelete = onDeleteConnection,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentChip(
    session: RecentSessions.RecentSession,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Terminal,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.width(132.dp)) {
            Text(
                text = session.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    if (session.sessionName != null) {
                        R.string.home_mode_screen
                    } else {
                        R.string.home_mode_shell
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                // 32dp hit area: above the 24dp WCAG 2.5.8 floor and as large as the
                // chip body allows (the chip itself is a dense, inline component).
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.home_remove_from_recents),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.home_search_hint)) },
        leadingIcon = {
            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.home_search))
        },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
    )
}

@Composable
private fun ConnectionRow(
    connection: ConnectionEntity,
    onOpen: () -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: (ConnectionEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete(connection)
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        // M3 motion: the row animates out on delete and animates back in when the
        // snackbar's Undo re-inserts it, which is what makes the undo legible.
        modifier = modifier,
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(end = 24.dp),
                )
            }
        },
    ) {
        Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Computer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = connection.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            R.string.home_user_at_host,
                            connection.username,
                            connection.host,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    connection.screenSessionName?.let { name ->
                        Text(
                            text = stringResource(R.string.home_screen_label, name),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.action_more_actions),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_edit)) },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onEdit(connection.id)
                            },
                        )
                        DropdownMenuItem(
                            // M3: a destructive menu item carries the error role. The
                            // delete itself is instant because the snackbar offers Undo.
                            text = {
                                Text(
                                    stringResource(R.string.action_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onDelete(connection)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    searchActive: Boolean,
    onNewConnection: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Computer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(56.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(
                if (searchActive) R.string.home_empty_no_matches else R.string.home_empty_no_connections
            ),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (searchActive) {
                stringResource(R.string.home_empty_other_term)
            } else {
                stringResource(R.string.home_empty_add_server)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        if (!searchActive) {
            ExtendedFloatingActionButton(
                onClick = onNewConnection,
                shape = MaterialTheme.shapes.large,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = {
                    Text(stringResource(R.string.home_add_connection), fontWeight = FontWeight.SemiBold)
                },
            )
        }
    }
}

/**
 * In-app language picker.
 *
 * Android 13+ already offers this in Settings > Apps > Languages (via
 * `android:localeConfig`), but the in-app entry is the only way on 8.1-12 and it
 * is friendlier than sending people into system settings.
 *
 * What switching costs differs by platform, and the UI says so:
 *  - 8.1-12: we recreate the Activity ourselves, so the foreground service - and
 *    every live SSH session with it - is untouched.
 *  - 13+: the platform owns the setting and restarts the app when it changes,
 *    which DOES drop live sessions. Worth warning about, because GNU Screen keeps
 *    running server-side and re-attaching is one tap once the app is back.
 */
@Composable
private fun LanguageMenu() {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val runningTabs by LiveTerminalStore.tabs.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    // A choice held while the restart warning is open. Wrapped in a type so that a
    // null tag ("follow the system language") is not confused with "no dialog".
    var pending by remember { mutableStateOf<PendingLanguage?>(null) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Outlined.Translate,
                contentDescription = stringResource(R.string.language_menu),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            // Only read the (binder-backed) explicit choice while the menu is open.
            val configured = AppLocale.configuredTag(context)
            val select: (String?) -> Unit = { tag ->
                expanded = false
                if (tag != configured) {
                    if (AppLocale.needsManualRecreate || runningTabs.isEmpty()) {
                        applyLanguage(context, activity, tag)
                    } else {
                        pending = PendingLanguage(tag)
                    }
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                LanguageItem(
                    label = stringResource(R.string.language_system),
                    selected = configured == null,
                    onClick = { select(null) },
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                AppLocale.SUPPORTED.forEach { tag ->
                    LanguageItem(
                        label = stringResource(languageLabelRes(tag)),
                        selected = configured?.equals(tag, ignoreCase = true) ?: false,
                        onClick = { select(tag) },
                    )
                }
            }
        }
        pending?.let { choice ->
            AlertDialog(
                onDismissRequest = { pending = null },
                title = { Text(stringResource(R.string.language_switch_title)) },
                text = {
                    Text(
                        pluralStringResource(
                            R.plurals.language_switch_body,
                            runningTabs.size,
                            runningTabs.size,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pending = null
                            applyLanguage(context, activity, choice.tag)
                        },
                    ) { Text(stringResource(R.string.language_switch_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { pending = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }
}

/** A language choice waiting behind the restart warning. */
private data class PendingLanguage(val tag: String?)

private fun applyLanguage(context: Context, activity: Activity?, tag: String?) {
    AppLocale.setTag(context, tag)
    // API 33+ applies the change and restarts the app itself; below that we do it.
    if (AppLocale.needsManualRecreate) activity?.recreate()
}

@Composable
private fun LanguageItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = {
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

/** Language names are shown in their own language, in every locale. */
@StringRes
private fun languageLabelRes(tag: String): Int = when (tag.lowercase()) {
    "es" -> R.string.language_spanish
    else -> R.string.language_english
}
