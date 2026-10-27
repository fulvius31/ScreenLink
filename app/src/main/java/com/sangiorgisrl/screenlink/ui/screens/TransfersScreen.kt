package com.sangiorgisrl.screenlink.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.progressSemantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.i18n.UiText
import com.sangiorgisrl.screenlink.i18n.rememberResolved
import com.sangiorgisrl.screenlink.i18n.resolve
import com.sangiorgisrl.screenlink.transfer.RemotePaths
import com.sangiorgisrl.screenlink.transfer.TransferDirection
import com.sangiorgisrl.screenlink.transfer.TransferFormatting
import com.sangiorgisrl.screenlink.transfer.TransferQueueView
import com.sangiorgisrl.screenlink.transfer.TransferStatus
import com.sangiorgisrl.screenlink.transfer.TransferStore
import com.sangiorgisrl.screenlink.ui.adaptive.AdaptiveContent
import java.util.Locale
import kotlinx.coroutines.channels.Channel

/**
 * The copy queue on a screen of its own.
 *
 * It used to be the tail of the Files screen, below a folder listing that is
 * routinely several screens long: the progress rows were the last thing on the page,
 * i.e. the one thing the user wants WITHOUT hunting. Here the queue IS the page -
 * reachable from the top bar of the Files screen (and from Home / the notification),
 * with what is still moving at the top and what finished below it.
 *
 * It renders [jobs] and calls back; it owns nothing. The copies live in the
 * application-scoped `TransferStore`, so closing this screen - or the app - changes
 * nothing about what is copying.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(
    jobs: List<TransferStore.Job>,
    messages: Channel<UiText>,
    onBack: () -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancelAll: () -> Unit,
    onClearFinished: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val locale: Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

    // One pass over the queue for the counts, one for the order: the header, the
    // two bands and the rows must agree on the same answer.
    val summary = remember(jobs) { TransferQueueView.Summary.of(jobs) }
    val sections = remember(jobs) { TransferQueueView.sections(jobs) }

    LaunchedEffect(Unit) {
        for (message in messages) {
            snackbarHostState.showSnackbar(message.resolve(resources))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.files_transfers),
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!summary.isEmpty) {
                            Text(
                                text = summaryLine(summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                // The count is the whole point of this screen while a
                                // batch runs, so a screen reader re-reads it as files
                                // land instead of leaving the user on a stale number.
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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
                    // Both actions only exist when they can actually do something:
                    // "Stop all" over an idle queue and "Clear" over an empty one are
                    // buttons that lie, and a lying button is tapped twice.
                    if (summary.canStopAll) {
                        IconButton(onClick = onCancelAll) {
                            Icon(
                                Icons.Outlined.StopCircle,
                                contentDescription = stringResource(R.string.files_transfers_cancel_all),
                            )
                        }
                    }
                    if (summary.canClearFinished) {
                        IconButton(onClick = onClearFinished) {
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                contentDescription = stringResource(R.string.files_clear_finished),
                            )
                        }
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
        AdaptiveContent(modifier = Modifier.padding(padding)) { _, contentModifier ->
            LazyColumn(
                modifier = contentModifier.semantics { paneTitle = resources.getString(R.string.files_transfers) },
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (summary.isEmpty) {
                    item(key = "empty-queue") { EmptyQueueCard() }
                    return@LazyColumn
                }

                if (sections.active.isNotEmpty()) {
                    stickyHeader(key = "active-header") {
                        SectionHeader(
                            text = pluralStringResource(
                                R.plurals.transfers_section_active,
                                sections.active.size,
                                sections.active.size,
                            ),
                            // Why the rows are still here after the user left the
                            // Files screen: nothing was cancelled by navigating away.
                            note = stringResource(R.string.files_transfers_running_hint),
                        )
                    }
                    items(sections.active, key = { it.id }) { job ->
                        TransferRow(
                            job = job,
                            locale = locale,
                            onCancel = { onCancel(job.id) },
                            onRetry = { onRetry(job.id) },
                        )
                    }
                }

                if (sections.finished.isNotEmpty()) {
                    stickyHeader(key = "finished-header") {
                        SectionHeader(
                            text = pluralStringResource(
                                R.plurals.transfers_section_finished,
                                sections.finished.size,
                                sections.finished.size,
                            ),
                        )
                    }
                    items(sections.finished, key = { it.id }) { job ->
                        TransferRow(
                            job = job,
                            locale = locale,
                            onCancel = { onCancel(job.id) },
                            onRetry = { onRetry(job.id) },
                        )
                    }
                }
            }
        }
    }
}

/** "3 in progress · 2 finished", skipping whatever is zero. */
@Composable
private fun summaryLine(summary: TransferQueueView.Summary): String {
    val parts = mutableListOf<String>()
    if (summary.active > 0) {
        parts += pluralStringResource(R.plurals.transfers_count_active, summary.active, summary.active)
    }
    if (summary.finished > 0) {
        parts += pluralStringResource(
            R.plurals.transfers_count_finished,
            summary.finished,
            summary.finished,
        )
    }
    return parts.joinToString(" \u00b7 ")
}

/**
 * A sticky band name. It sticks because a queue of forty rows is read in two
 * questions - "what is still going?" and "what happened?" - and the answer has to
 * stay readable while the rows scroll under it.
 */
@Composable
private fun SectionHeader(text: String, note: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Sticky headers float over the rows: without the screen background the
            // progress bars show through the text.
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A queue with nothing in it is not an error, and it is not nothing either: it is
 * the screen the user opened by mistake or after clearing, and it has to say where
 * the copies come from.
 */
@Composable
private fun EmptyQueueCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.transfers_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.transfers_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransferRow(
    job: TransferStore.Job,
    locale: Locale,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val active = job.status == TransferStatus.Running || job.status == TransferStatus.Queued
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (job.direction == TransferDirection.Upload) {
                        Icons.Outlined.FileUpload
                    } else {
                        Icons.Outlined.FileDownload
                    },
                    contentDescription = null,
                    tint = when (job.status) {
                        TransferStatus.Failed -> MaterialTheme.colorScheme.error
                        TransferStatus.Completed -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${job.connectionName} \u00b7 ${RemotePaths.leafName(job.remotePath)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    active -> FilledTonalIconButton(onClick = onCancel) {
                        Icon(
                            Icons.Outlined.Cancel,
                            contentDescription = stringResource(R.string.files_cancel_transfer),
                        )
                    }

                    job.status == TransferStatus.Failed || job.status == TransferStatus.Cancelled ->
                        FilledTonalIconButton(onClick = onRetry) {
                            Icon(
                                Icons.Outlined.Replay,
                                contentDescription = stringResource(R.string.files_retry_transfer),
                            )
                        }

                    else -> Icon(
                        imageVector = if (job.status == TransferStatus.Completed) {
                            Icons.Outlined.CheckCircle
                        } else {
                            Icons.Outlined.Error
                        },
                        contentDescription = null,
                        tint = if (job.status == TransferStatus.Completed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            val fraction = TransferFormatting.fraction(job.transferredBytes, job.totalBytes)
            when {
                job.status == TransferStatus.Running && fraction != null ->
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .progressSemantics(fraction)
                            .semantics { contentDescription = job.fileName },
                    )

                job.status == TransferStatus.Running ->
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = job.fileName },
                    )

                // A queued row gets a loader too (dimmer, so it reads as "next"): in a
                // batch of forty files the ones waiting behind the running one must not
                // look dead, or the user thinks the queue stopped.
                job.status == TransferStatus.Queued ->
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(0.45f)
                            .progressSemantics()
                            .semantics { contentDescription = job.fileName },
                    )

                job.status == TransferStatus.Completed ->
                    LinearProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .progressSemantics(1f)
                            .semantics { contentDescription = job.fileName },
                    )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = progressLine(job, locale),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (job.status == TransferStatus.Failed && job.error != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = job.error.rememberResolved(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The live line under a progress bar: `12.4 MB of 45.0 MB · 2.1 MB/s · 4 min left`.
 *
 * Every piece is optional on purpose: an unknown total has no percentage to show,
 * the first samples have no speed yet, and a finished copy reports what it moved
 * rather than what is left.
 */
@Composable
private fun progressLine(job: TransferStore.Job, locale: Locale): String {
    val parts = mutableListOf<String>()
    when (job.status) {
        TransferStatus.Queued -> parts += stringResource(R.string.files_status_queued)

        TransferStatus.Running -> {
            val note = job.note
            if (note != null) {
                // A copy that lost its connection is being restarted on a fresh one:
                // the bar has gone back to zero, so the line says WHY rather than
                // quoting a speed and a time left that belong to a session that no
                // longer exists. The bar itself still carries the total.
                parts += note.rememberResolved()
            } else {
                if (job.totalBytes > 0L) {
                    parts += stringResource(
                        R.string.files_progress_sizes,
                        TransferFormatting.bytes(job.transferredBytes, locale),
                        TransferFormatting.bytes(job.totalBytes, locale),
                    )
                } else if (job.transferredBytes > 0L) {
                    parts += stringResource(
                        R.string.files_progress_size_only,
                        TransferFormatting.bytes(job.transferredBytes, locale),
                    )
                }
                if (job.bytesPerSecond > 0.0) {
                    parts += TransferFormatting.rate(job.bytesPerSecond, locale)
                }
                val left = job.secondsLeft
                parts += if (left != null && left.isFinite() && left >= 0.0) {
                    stringResource(
                        R.string.files_progress_time_left,
                        TransferFormatting.duration(left, locale),
                    )
                } else {
                    stringResource(R.string.files_progress_eta_unknown)
                }
            }
        }

        TransferStatus.Completed -> {
            parts += stringResource(R.string.files_status_completed)
            parts += TransferFormatting.bytes(job.transferredBytes.coerceAtLeast(job.totalBytes), locale)
            if (job.bytesPerSecond > 0.0) parts += TransferFormatting.rate(job.bytesPerSecond, locale)
        }

        TransferStatus.Failed -> parts += stringResource(R.string.files_status_failed)
        TransferStatus.Cancelled -> parts += stringResource(R.string.files_status_cancelled)
    }
    return parts.joinToString(" \u00b7 ")
}
