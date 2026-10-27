package com.sangiorgisrl.screenlink.ui.screens

import android.text.format.Formatter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.i18n.rememberResolved
import com.sangiorgisrl.screenlink.speech.DictationState
import com.sangiorgisrl.screenlink.speech.SpeechBackend
import com.sangiorgisrl.screenlink.speech.SpeechError
import com.sangiorgisrl.screenlink.speech.isRetryable
import com.sangiorgisrl.screenlink.speech.labelRes
import com.sangiorgisrl.screenlink.speech.message

/**
 * The dictation strip: what the microphone is doing right now, and the way out.
 *
 * It is pinned to the bottom of the terminal canvas as an OVERLAY rather than being
 * another row in the column. A row that appears and disappears changes the height of
 * the canvas, which resizes the remote pty and makes the server redraw the whole
 * screen - twice per dictation, in the middle of reading output. Over the canvas
 * bottom nothing moves; the last line of output is briefly behind the strip, which is
 * the cheaper price, and the strip is only ever up while the user is talking.
 *
 * The live transcript is the point of the whole feature: the user reads exactly what
 * was heard - and what the terminal is about to receive - and can throw it away
 * before a character is typed. Nothing is ever executed: inserting stops at the
 * cursor and Enter stays a separate key press.
 */
@Composable
fun DictationBar(
    state: DictationState,
    /** Which recognizer is listening, named on the strip (see [SpeechBackend]). */
    backend: SpeechBackend,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val failed = state is DictationState.Failed
    // Read once, in the composition: the download text is built in a `when` branch
    // that runs on every recomposition, and LocalContext must not be read from there.
    val context = LocalContext.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (failed) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusGlyph(state)

            Spacer(Modifier.width(4.dp))

            Text(
                text = when (state) {
                    is DictationState.Starting -> stringResource(R.string.dictate_starting)
                    is DictationState.Finishing -> stringResource(R.string.dictate_finishing)

                    // The engine is named while nothing has been heard yet; once a
                    // word arrives the transcript takes the line, which is what the
                    // user needs to read before it is typed.
                    is DictationState.Listening -> state.partial.ifEmpty {
                        stringResource(
                            R.string.dictate_listening_mode,
                            stringResource(backend.labelRes()),
                        )
                    }

                    // ML Kit fetches its model on the first dictation, which can take
                    // minutes: bytes arrived is the only honest progress it reports.
                    is DictationState.Downloading -> stringResource(
                        R.string.dictate_downloading,
                        Formatter.formatShortFileSize(context, state.bytesDownloaded),
                    )

                    is DictationState.Failed -> state.error.message().rememberResolved()
                    is DictationState.Off -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // Takes only what the buttons leave over, so a long transcript (or a
                // long Spanish error sentence) shrinks the text instead of pushing the
                // actions off the screen.
                modifier = Modifier.weight(1f),
            )

            when (state) {
                // Waiting for a model is not listening: there is nothing to insert,
                // only the ✕ that gives up on the download.
                is DictationState.Downloading -> {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.dictate_discard),
                        )
                    }
                }

                is DictationState.Starting, is DictationState.Listening,
                is DictationState.Finishing,
                -> {
                    if (state !is DictationState.Finishing) {
                        IconButton(onClick = onFinish) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = stringResource(R.string.dictate_insert),
                            )
                        }
                    }
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.dictate_discard),
                        )
                    }
                }

                is DictationState.Failed -> {
                    if (state.error == SpeechError.PermissionSettings) {
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = stringResource(R.string.dictate_open_settings),
                            )
                        }
                    }
                    if (state.error.isRetryable()) {
                        IconButton(onClick = onRetry) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.action_retry),
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.dictate_dismiss),
                        )
                    }
                }

                is DictationState.Off -> Unit
            }
        }
    }
}

/**
 * The listening indicator.
 *
 * A slow breath on the microphone is what tells the user the app is hearing them
 * rather than frozen - the same reason a recorder shows a moving level. A failed
 * attempt shows a struck-through microphone so the strip cannot be misread as live
 * (and so it is not confused with the "dismiss" cross the strip also carries).
 */
@Composable
private fun StatusGlyph(state: DictationState) {
    val listening = state is DictationState.Listening || state is DictationState.Starting
    // Finishing and Downloading are both "waiting for the engine", so both get the
    // spinner; a breathing microphone would claim the app is hearing the user.
    if (state is DictationState.Finishing || state is DictationState.Downloading) {
        Box(modifier = Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        return
    }
    val alpha = if (!listening) 1f else {
        val transition = rememberInfiniteTransition(label = "dictation")
        val animated by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 850, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dictation-alpha",
        )
        animated
    }
    Icon(
        imageVector = if (state is DictationState.Failed) Icons.Outlined.MicOff else Icons.Outlined.Mic,
        contentDescription = null,
        modifier = Modifier.size(26.dp).heightIn(min = 26.dp),
        tint = LocalContentColor.current.copy(alpha = alpha),
    )
}
