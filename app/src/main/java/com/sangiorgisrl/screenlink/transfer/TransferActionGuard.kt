package com.sangiorgisrl.screenlink.transfer

/**
 * Why the transfer button cannot act yet - or that it can.
 *
 * The same question is asked from two places that must never disagree:
 *  - the floating button, which decides whether it looks live or dim;
 *  - the view model, which decides which sentence to answer a tap with.
 *
 * When they drift apart the app lies: the button looks ready, the tap is refused,
 * and the refusal names the wrong thing (the classic "I have files but no folder,
 * and the app tells me to tick files"). So the order of the checks is written once,
 * here, as pure data.
 *
 * The order is deliberate: the thing the user was just looking at is asked for
 * first. In "send" mode the file list is the card on screen, so a missing file is
 * named before a missing remote folder; in "copy to device" mode the ticks are what
 * the user just did in the listing, so they come before the device folder - whose
 * own card already says, in red, that nothing is chosen.
 */
enum class FabReadiness {
    /** Everything the action needs is there. */
    Ready,

    /** Nothing has been picked to send. */
    NeedsFiles,

    /** Files are waiting, but no remote folder is open to receive them. */
    NeedsRemoteFolder,

    /** Nothing is ticked to copy down. */
    NeedsSelection,

    /** Files are ticked, but no device folder was chosen to write them into. */
    NeedsDeviceFolder,
}

/** The two rules of the transfer button, kept next to the data they read. */
object TransferActionGuard {

    /**
     * "Send to server": the pending files first, then the folder they land in.
     *
     * [remoteFolderOpen] is true only while the browser really is showing a folder;
     * a listing that is still loading, or that failed, is not a destination.
     */
    fun upload(pendingFiles: Int, remoteFolderOpen: Boolean): FabReadiness = when {
        pendingFiles <= 0 -> FabReadiness.NeedsFiles
        !remoteFolderOpen -> FabReadiness.NeedsRemoteFolder
        else -> FabReadiness.Ready
    }

    /**
     * "Copy to device": the ticked files first, then the folder they are written into.
     *
     * The ticks travel with their own entry, so a folder that failed to re-open does
     * not invalidate what the user ticked - which is exactly why the ticks are asked
     * for before the folder.
     */
    fun download(selectedFiles: Int, deviceFolderChosen: Boolean): FabReadiness = when {
        selectedFiles <= 0 -> FabReadiness.NeedsSelection
        !deviceFolderChosen -> FabReadiness.NeedsDeviceFolder
        else -> FabReadiness.Ready
    }

    /** True when the button may act, i.e. nothing is missing. */
    fun isReady(readiness: FabReadiness): Boolean = readiness == FabReadiness.Ready
}
