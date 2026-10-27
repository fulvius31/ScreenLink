package com.sangiorgisrl.screenlink.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.data.ConnectionEntity
import com.sangiorgisrl.screenlink.i18n.rememberResolved
import com.sangiorgisrl.screenlink.i18n.resolve
import com.sangiorgisrl.screenlink.ssh.ScreenCommands
import com.sangiorgisrl.screenlink.ssh.ScreenSession
import com.sangiorgisrl.screenlink.ui.adaptive.AdaptiveContent
import com.sangiorgisrl.screenlink.ui.viewmodel.OverviewViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    viewModel: OverviewViewModel,
    onBack: () -> Unit,
    onOpenTerminal: (mode: String, session: String?) -> Unit,
    onEdit: () -> Unit,
    /** Open the file-transfer ("scp") screen pre-set to this connection. */
    onOpenFiles: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    LaunchedEffect(Unit) {
        for (message in viewModel.snackbar) {
            // Resolved here, not in the view model: the message was produced on an IO
            // thread and may be shown after the user switched language.
            snackbarHostState.showSnackbar(message.resolve(resources))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = connection?.displayName
                                ?: stringResource(R.string.overview_connection),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        connection?.let {
                            Text(
                                text = "${it.username}@${it.host}:${it.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.overview_edit_connection),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val openTerminal: (String, String?) -> Unit = { mode, session ->
            // Don't keep this overview tunnel alive while the terminal opens its own.
            viewModel.releaseSshSession()
            onOpenTerminal(mode, session)
        }
        // Fitted column: on tablets / landscape the session cards stay at a
        // comfortable reading width, centered, instead of stretching edge to edge.
        AdaptiveContent(modifier = Modifier.padding(padding)) { _, contentModifier ->
            OverviewContent(
                modifier = contentModifier,
                state = state,
                connection = connection,
                onRetry = viewModel::retry,
                onRefresh = viewModel::refresh,
                onCreateSession = viewModel::createSession,
                onKillSession = viewModel::killSession,
                onDetachSession = viewModel::detachSession,
                onOpenTerminal = openTerminal,
                onOpenFiles = onOpenFiles,
            )
        }
    }
}

@Composable
private fun OverviewContent(
    modifier: Modifier,
    state: OverviewViewModel.OverviewState,
    connection: ConnectionEntity?,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onCreateSession: (String) -> Unit,
    onKillSession: (ScreenSession) -> Unit,
    onDetachSession: (ScreenSession) -> Unit,
    onOpenTerminal: (mode: String, session: String?) -> Unit,
    onOpenFiles: () -> Unit,
) {
    when (state) {
        OverviewViewModel.OverviewState.Loading -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = connection?.host?.let {
                            stringResource(R.string.overview_connecting, it)
                        } ?: stringResource(R.string.overview_connecting_unknown),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        is OverviewViewModel.OverviewState.Error -> {
            Column(
                modifier = modifier.padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.overview_could_not_connect),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.message.rememberResolved(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onRetry) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_retry))
                }
            }
        }

        is OverviewViewModel.OverviewState.Connected -> {
            ConnectedContent(
                modifier = modifier,
                state = state,
                connection = connection,
                onRefresh = onRefresh,
                onCreateSession = onCreateSession,
                onKillSession = onKillSession,
                onDetachSession = onDetachSession,
                onOpenTerminal = onOpenTerminal,
                onOpenFiles = onOpenFiles,
            )
        }
    }
}

@Composable
private fun ConnectedContent(
    modifier: Modifier,
    state: OverviewViewModel.OverviewState.Connected,
    connection: ConnectionEntity?,
    onRefresh: () -> Unit,
    onCreateSession: (String) -> Unit,
    onKillSession: (ScreenSession) -> Unit,
    onDetachSession: (ScreenSession) -> Unit,
    onOpenTerminal: (mode: String, session: String?) -> Unit,
    onOpenFiles: () -> Unit,
) {
    var newSessionName by remember { mutableStateOf("") }
    var sessionToDelete by remember { mutableStateOf<ScreenSession?>(null) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // --- Server card ------------------------------------------------------
        item(key = "server") {
            ServerCard(connection = connection, fingerprint = state.fingerprint, isBusy = state.isBusy)
        }

        // --- Default session / quick attach -----------------------------------
        connection?.screenSessionName?.let { defaultName ->
            item(key = "default") {
                Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    onClick = { onOpenTerminal("create", defaultName) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.overview_open_named, defaultName),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.overview_open_default_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalIconButton(onClick = { onOpenTerminal("create", defaultName) }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.CallMade,
                                contentDescription = stringResource(R.string.overview_open),
                            )
                        }
                    }
                }
            }
        }

        // --- screen availability ----------------------------------------------
        item(key = "screen-status") {
            if (!state.hasScreen) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = stringResource(R.string.overview_screen_missing),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.overview_screen_missing_help),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.overview_sessions_title, state.sessions.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(onClick = onRefresh, enabled = !state.isBusy) {
                        if (state.isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_refresh))
                    }
                }
            }
        }

        // --- New session ------------------------------------------------------
        if (state.hasScreen) {
            item(key = "new") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = newSessionName,
                        onValueChange = { input ->
                            newSessionName = input.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
                                .take(64)
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.overview_new_session_name)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        enabled = !state.isBusy,
                    )
                    FilledTonalButton(
                        onClick = {
                            if (newSessionName.isNotBlank() && ScreenCommands.NAME_REGEX.matches(newSessionName)) {
                                onCreateSession(newSessionName)
                                newSessionName = ""
                            }
                        },
                        enabled = newSessionName.isNotBlank() && !state.isBusy,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.overview_create))
                    }
                }
            }
        }

        // --- Session rows -----------------------------------------------------
        if (state.hasScreen && state.sessions.isEmpty()) {
            item(key = "no-sessions") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Outlined.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.overview_no_sessions),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.overview_no_sessions_help),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (state.hasScreen) {
            items(state.sessions, key = { it.socket }) { session ->
                SessionRow(
                    session = session,
                    enabled = !state.isBusy,
                    onAttach = { onOpenTerminal("attach", session.socket) },
                    onDetach = { onDetachSession(session) },
                    onKill = { sessionToDelete = session },
                )
            }
        }

        // --- Plain shell ------------------------------------------------------
        item(key = "plain-shell") {
            OutlinedButton(
                onClick = { onOpenTerminal("shell", null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Code, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.overview_plain_shell))
            }
        }

        // --- File transfer -----------------------------------------------------
        // Same connection, same pinned host key: copying files is offered right
        // where the server is already proven reachable.
        item(key = "files") {
            OutlinedButton(
                onClick = onOpenFiles,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.overview_files))
            }
        }
    }

    sessionToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.overview_delete_session_title)) },
            text = {
                Text(stringResource(R.string.overview_delete_session_body, session.name, session.socket))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onKillSession(session)
                        sessionToDelete = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ServerCard(
    connection: ConnectionEntity?,
    fingerprint: String?,
    isBusy: Boolean,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = connection?.host ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.overview_ssh_port, connection?.port ?: 22),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            fingerprint?.let { fp ->
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = fp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: ScreenSession,
    enabled: Boolean,
    onAttach: () -> Unit,
    onDetach: () -> Unit,
    onKill: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(10.dp))
                    StateBadge(state = session.state)
                }
                Text(
                    text = stringResource(R.string.overview_socket, session.socket),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (session.state == "Attached") {
                FilledTonalIconButton(onClick = onDetach, enabled = enabled) {
                    Icon(
                        Icons.Outlined.Pause,
                        contentDescription = stringResource(R.string.overview_detach),
                    )
                }
            }
            FilledTonalIconButton(onClick = onAttach, enabled = enabled) {
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(R.string.overview_attach),
                )
            }
            FilledTonalIconButton(onClick = onKill, enabled = enabled) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StateBadge(state: String) {
    val (container, content) = when (state) {
        "Attached" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "Detached" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    // The raw value comes from the server's `screen -ls` output, so it is compared
    // untranslated and only the label is localized (anything unexpected is shown as-is).
    val label = when (state) {
        "Attached" -> stringResource(R.string.state_attached)
        "Detached" -> stringResource(R.string.state_detached)
        else -> state
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
    }
}
