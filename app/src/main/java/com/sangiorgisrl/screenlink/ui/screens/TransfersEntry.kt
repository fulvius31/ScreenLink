package com.sangiorgisrl.screenlink.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.transfer.TransferQueueView

/**
 * The door to the copy-queue screen, from any other screen.
 *
 * The badge is the whole point: a queue the user cannot see is a queue they do not
 * trust. What it counts - work in flight, or (with nothing running) work that
 * FAILED, or nothing at all - is decided by [TransferQueueView.badge], which counts
 * the same [TransferQueueView.Summary] the queue screen header and the Home card
 * count, so the number never means two different things.
 *
 * It lives in its own file because the badge rule belongs to the entry point, not to
 * the screen that happens to host it: copies can be running for a server the Files
 * screen is not even looking at, which is why the queue is a screen of its own and
 * Home opens it through a card instead of another icon in a crowded header.
 *
 * The badge is drawn as a bare number, which is precisely what a screen reader
 * cannot use: "Show transfers, 3" never says what the 3 counts, and past the cap it
 * reads "ninety-nine plus". So the count and its meaning go into the icon's own
 * label ([badgeDescription]), while the drawn badge keeps the short "99+" form that
 * still fits in the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersEntry(badge: TransferQueueView.Badge, onOpen: () -> Unit) {
    val description = badgeDescription(badge)
    IconButton(onClick = onOpen) {
        BadgedBox(
            badge = {
                // null means "nothing to point at": the badge disappears entirely
                // rather than showing a zero the user has to decode.
                val label = TransferQueueView.badgeLabel(badge)
                if (label != null) {
                    Badge(
                        // The number is drawn here and SAID by the icon's label below.
                        // A Badge is a Text, and an IconButton merges its descendants,
                        // so leaving it in the tree makes TalkBack read the count twice
                        // ("3, Show transfers: 3 copies in progress"). The sentence is
                        // the useful one, so the drawn digit carries no semantics.
                        modifier = Modifier.clearAndSetSemantics { },
                        containerColor = if (badge.kind == TransferQueueView.BadgeKind.Attention) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        contentColor = if (badge.kind == TransferQueueView.BadgeKind.Attention) {
                            MaterialTheme.colorScheme.onError
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                    ) {
                        Text(label)
                    }
                }
            },
        ) {
            Icon(
                Icons.Outlined.SwapVert,
                contentDescription = description,
            )
        }
    }
}

/**
 * What the entry point has to say out loud, badge included.
 *
 * The number spoken is the REAL count rather than [TransferQueueView.badgeLabel]'s
 * "99+" floor: the floor exists so the icon is not pushed off the bar, and someone
 * asking the button what it holds deserves the exact answer.
 */
@Composable
private fun badgeDescription(badge: TransferQueueView.Badge): String {
    if (badge.kind == TransferQueueView.BadgeKind.None) {
        return stringResource(R.string.files_open_transfers)
    }
    val detail = when (badge.kind) {
        TransferQueueView.BadgeKind.Active -> pluralStringResource(
            R.plurals.files_badge_active,
            badge.count,
            badge.count,
        )
        TransferQueueView.BadgeKind.Attention -> pluralStringResource(
            R.plurals.files_badge_failed,
            badge.count,
            badge.count,
        )
        TransferQueueView.BadgeKind.None -> ""
    }
    return stringResource(R.string.files_open_transfers_desc, detail)
}
