package com.sangiorgisrl.screenlink.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.ssh.HostKeyTrustPrompt

/**
 * The first-contact host-key question, drawn once for the whole app.
 *
 * It is mounted at the Activity root rather than inside a screen because the
 * connection that asks may belong to a terminal tab or a copy queue: the dialog has
 * to appear over whatever the user happens to be looking at, and answering it is the
 * only way the waiting connect proceeds.
 *
 * Dismissing by tapping outside is a REFUSAL, not a "still thinking": the connect
 * that asked throws and never sends a credential.
 */
@Composable
fun HostKeyTrustDialog(prompt: HostKeyTrustPrompt) {
    val challenge by prompt.pending.collectAsStateWithLifecycle()
    val pending = challenge ?: return
    AlertDialog(
        onDismissRequest = { prompt.respond(false) },
        icon = { Icon(Icons.Outlined.Shield, contentDescription = null) },
        title = { Text(stringResource(R.string.hostkey_first_contact_title, pending.connectionName)) },
        text = {
            // The address is part of the question, not a detail: a fingerprint is
            // only meaningful next to the host it was seen on.
            Text(
                stringResource(
                    R.string.hostkey_first_contact_body,
                    "${pending.host}:${pending.port}",
                    pending.fingerprint,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { prompt.respond(true) }) {
                Text(stringResource(R.string.hostkey_first_contact_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = { prompt.respond(false) }) {
                Text(stringResource(R.string.hostkey_first_contact_cancel))
            }
        },
    )
}
