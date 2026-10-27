package com.sangiorgisrl.screenlink

import android.content.Intent

/**
 * What an incoming Intent is asking the Activity to show.
 *
 * The Activity used to ask "did this Intent carry files?" and, when it did not,
 * conclude that a share had arrived with nothing usable in it - so a plain launcher
 * tap (ACTION_MAIN) opened the Files screen with a complaint on it, and the copy
 * screen became the screen the app "starts" on. The action is the answer: only a
 * SEND / SEND_MULTIPLE Intent is a share, and only the notification extras ask for
 * the terminal or the copy-queue screen. Everything else is "open the app", which
 * means Home and nothing else.
 *
 * A pure function of (action, extras) because "which screen does the app open on"
 * is exactly the promise a device cannot be trusted to be checked the same way
 * twice - and it is a promise that was broken once already.
 */
object LaunchRequest {

    /** The screen the app has to put in front of the user for one Intent. */
    enum class Kind { Home, Share, Terminal, Transfers }

    /** A share-sheet hand-off: another app handing files or text to this one. */
    fun isShare(action: String?): Boolean =
        action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE

    /**
     * The notification extras win over the action: tapping a notification is a
     * deliberate "come and look at this", and it is the only way back to a live
     * session or a running copy from the shade. A share Intent never carries them.
     */
    fun classify(action: String?, openTerminal: Boolean, openTransfers: Boolean): Kind = when {
        openTransfers -> Kind.Transfers
        openTerminal -> Kind.Terminal
        isShare(action) -> Kind.Share
        else -> Kind.Home
    }
}
