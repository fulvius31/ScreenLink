package com.sangiorgisrl.screenlink.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.i18n.rememberResolved
import com.sangiorgisrl.screenlink.data.ConnectionValidation
import com.sangiorgisrl.screenlink.ui.adaptive.AdaptiveContent
import com.sangiorgisrl.screenlink.ui.viewmodel.ConnectionFormViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionFormScreen(
    viewModel: ConnectionFormViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var confirmDelete by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    // M3 text-field error state: the message belongs under the field it refers to,
    // not in one shared line at the bottom of the form. Hidden until the first
    // save attempt, then recomputed live so it clears as soon as the field is fixed.
    val errors = if (state.showFieldErrors) {
        viewModel.fieldErrors
    } else {
        ConnectionValidation.FieldErrors()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (viewModel.isEditing) {
                                R.string.form_edit_title
                            } else {
                                R.string.form_new_title
                            }
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
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
                    if (viewModel.isEditing) {
                        IconButton(onClick = { menuOpen = true }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = stringResource(R.string.form_delete_connection),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                        // M3: a destructive menu item carries the error role.
                                        text = {
                                            Text(
                                                stringResource(R.string.form_delete_connection),
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
                                        // Destructive: confirm before it happens (the form
                                        // leaves the list, so there is no snackbar to undo into).
                                        onClick = { menuOpen = false; confirmDelete = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // Fitted column: on tablets / landscape the form is centered at a readable
        // width instead of stretching fields across the whole window.
        AdaptiveContent(
            modifier = Modifier.padding(padding),
            maxContentWidth = 640.dp,
        ) { _, contentModifier ->
            Column(
                modifier = contentModifier
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.form_section_server),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = viewModel::updateDisplayName,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.form_field_name)) },
                    leadingIcon = { Icon(Icons.Outlined.Dns, contentDescription = null) },
                    singleLine = true,
                    isError = errors.displayName != null,
                    supportingText = errors.displayName?.let { id ->
                        @Composable { Text(stringResource(id)) }
                    },
                    shape = MaterialTheme.shapes.large,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::updateUsername,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.form_field_username)) },
                    leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                    singleLine = true,
                    isError = errors.username != null,
                    supportingText = errors.username?.let { id ->
                        @Composable { Text(stringResource(id)) }
                    },
                    shape = MaterialTheme.shapes.large,
                )
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.host,
                        onValueChange = viewModel::updateHost,
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.form_field_host)) },
                        singleLine = true,
                        isError = errors.host != null,
                        supportingText = errors.host?.let { id ->
                            @Composable { Text(stringResource(id)) }
                        },
                        shape = MaterialTheme.shapes.large,
                    )
                    OutlinedTextField(
                        value = state.port,
                        onValueChange = viewModel::updatePort,
                        modifier = Modifier.width(110.dp),
                        label = { Text(stringResource(R.string.form_field_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = errors.port != null,
                        supportingText = errors.port?.let { id ->
                            @Composable { Text(stringResource(id)) }
                        },
                        shape = MaterialTheme.shapes.large,
                    )
                }
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::updatePassword,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.form_field_password)) },
                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = viewModel::togglePasswordVisibility) {
                            Icon(
                                if (state.showPassword) Icons.Outlined.VisibilityOff
                                else Icons.Outlined.Visibility,
                                contentDescription = stringResource(R.string.form_toggle_password),
                            )
                        }
                    },
                    visualTransformation = if (state.showPassword) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = MaterialTheme.shapes.large,
                )
                if (viewModel.isEditing) {
                    when {
                        state.clearPassword -> {
                            // The user asked to remove the stored password.
                            Text(
                                stringResource(R.string.form_password_cleared),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        state.hasSavedPassword -> {
                            // A stored secret exists and the blank field keeps it -
                            // offer a way OUT of that default (explicit, reversible).
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = stringResource(R.string.form_password_keep),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = viewModel::toggleClearPassword) {
                                    Text(
                                        stringResource(R.string.form_password_remove),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                        else -> Unit
                    }
                }
                Spacer(Modifier.height(24.dp))

                Text(
                    text = stringResource(R.string.form_section_screen),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.screenSessionName,
                    onValueChange = viewModel::updateScreenSessionName,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.form_field_screen_session)) },
                    supportingText = {
                        Text(stringResource(R.string.form_screen_session_help))
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                )
                Spacer(Modifier.height(24.dp))

                Text(
                    text = stringResource(R.string.form_section_keys),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                if (state.sshKeys.isEmpty()) {
                    Text(
                        text = stringResource(R.string.form_keys_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.sshKeys.forEach { key ->
                            FilterChip(
                                selected = key.id in state.selectedKeyIds,
                                onClick = { viewModel.toggleKey(key.id) },
                                label = { Text(key.name) },
                                leadingIcon = if (key.id in state.selectedKeyIds) {
                                    { Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null,
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.form_keys_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(24.dp))

                // Only non-field failures (e.g. a persistence problem) still need the
                // shared line; field problems are shown inline above.
                state.error?.let { error ->
                    if (errors.isEmpty) {
                        Text(
                            text = error.rememberResolved(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }

                Button(
                    // Only leave the screen when the save really succeeded: an invalid
                    // form must stay open so the inline errors can be fixed.
                    onClick = { viewModel.save { id -> if (id != null) onSaved() } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = MaterialTheme.shapes.medium,
                    enabled = !state.isLoading,
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(22.dp).height(22.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Outlined.Save, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (viewModel.isEditing) {
                                    R.string.form_save_changes
                                } else {
                                    R.string.home_add_connection
                                }
                            ),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.form_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(R.string.form_delete_confirm_body, state.displayName),
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete(onDeleted) }) {
                    Text(stringResource(R.string.action_delete), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
