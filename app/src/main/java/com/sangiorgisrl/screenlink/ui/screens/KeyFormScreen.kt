package com.sangiorgisrl.screenlink.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.i18n.rememberResolved
import com.sangiorgisrl.screenlink.ui.adaptive.AdaptiveContent
import com.sangiorgisrl.screenlink.ui.viewmodel.KeyFormViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyFormScreen(
    viewModel: KeyFormViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.keyform_title), fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // Fitted column for tablets / landscape (readable line length, centered).
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
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::updateName,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.keyform_field_name)) },
                    leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.privateKey,
                    onValueChange = viewModel::updatePrivateKey,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.keyform_field_private)) },
                    supportingText = {
                        Text(stringResource(R.string.keyform_private_help))
                    },
                    minLines = 8,
                    maxLines = 12,
                    shape = MaterialTheme.shapes.large,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.passphrase,
                    onValueChange = viewModel::updatePassphrase,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.keyform_field_passphrase)) },
                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = viewModel::togglePassphraseVisibility) {
                            Icon(
                                if (state.showPassphrase) Icons.Outlined.VisibilityOff
                                else Icons.Outlined.Visibility,
                                contentDescription = stringResource(R.string.keyform_toggle_passphrase),
                            )
                        }
                    },
                    visualTransformation = if (state.showPassphrase) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.publicKey,
                    onValueChange = viewModel::updatePublicKey,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.keyform_field_public)) },
                    supportingText = {
                        Text(stringResource(R.string.keyform_public_help))
                    },
                    minLines = 3,
                    maxLines = 6,
                    shape = MaterialTheme.shapes.large,
                )
                Spacer(Modifier.height(16.dp))

                state.error?.let { error ->
                    Text(
                        text = error.rememberResolved(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Button(
                    onClick = { viewModel.save { ok -> if (ok) onSaved() } },
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
                        Text(stringResource(R.string.keyform_save), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
