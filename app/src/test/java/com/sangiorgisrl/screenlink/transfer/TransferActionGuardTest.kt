package com.sangiorgisrl.screenlink.transfer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one rule behind the transfer button's colour and behind the sentence the view
 * model answers a refused tap with.
 *
 * Both sides read the same numbers, and the order of the checks is the part a user
 * feels: a button that goes live on a weaker rule than the one that accepts the tap
 * promises a copy and then refuses it, and a refusal that names the wrong missing
 * thing sends the user to the wrong card. So the order is pinned here.
 */
class TransferActionGuardTest {

    // --- send to server ------------------------------------------------------

    @Test
    fun `sending needs files and an open remote folder`() {
        assertEquals(FabReadiness.Ready, TransferActionGuard.upload(3, remoteFolderOpen = true))
        assertEquals(FabReadiness.NeedsFiles, TransferActionGuard.upload(0, remoteFolderOpen = true))
        assertEquals(
            FabReadiness.NeedsRemoteFolder,
            TransferActionGuard.upload(3, remoteFolderOpen = false),
        )
    }

    @Test
    fun `an empty tap asks for the files before the folder`() {
        // Nothing staged AND no folder open: the "Files to send" card is the one the
        // user is standing on in this mode, so it is what gets named. Naming the
        // folder instead sends them to a card they were not looking at.
        assertEquals(FabReadiness.NeedsFiles, TransferActionGuard.upload(0, remoteFolderOpen = false))
    }

    @Test
    fun `a folder that is still loading is not a destination`() {
        // The screen still shows the OLD listing while the new one is on its way, so
        // "Ready" here would queue a copy into a folder the user never chose.
        assertEquals(
            FabReadiness.NeedsRemoteFolder,
            TransferActionGuard.upload(1, remoteFolderOpen = false),
        )
    }

    // --- copy to device ------------------------------------------------------

    @Test
    fun `copying needs ticks and a device folder`() {
        assertEquals(FabReadiness.Ready, TransferActionGuard.download(2, deviceFolderChosen = true))
        assertEquals(
            FabReadiness.NeedsSelection,
            TransferActionGuard.download(0, deviceFolderChosen = true),
        )
        assertEquals(
            FabReadiness.NeedsDeviceFolder,
            TransferActionGuard.download(2, deviceFolderChosen = false),
        )
    }

    @Test
    fun `an empty tap asks for the ticks before the device folder`() {
        // The device folder card already says, in red, that nothing is chosen; the
        // ticks are the thing that is invisible until it is asked for.
        assertEquals(
            FabReadiness.NeedsSelection,
            TransferActionGuard.download(0, deviceFolderChosen = false),
        )
    }

    @Test
    fun `ticks survive a folder that is not open`() {
        // The selection carries its own entries, so a remote folder that failed to
        // re-open must not turn into "tick the files first".
        assertEquals(FabReadiness.NeedsDeviceFolder, TransferActionGuard.download(4, deviceFolderChosen = false))
        assertEquals(FabReadiness.Ready, TransferActionGuard.download(4, deviceFolderChosen = true))
    }

    // --- the button's colour -------------------------------------------------

    @Test
    fun `only a Ready state lights the button up`() {
        assertTrue(TransferActionGuard.isReady(FabReadiness.Ready))
        assertFalse(TransferActionGuard.isReady(FabReadiness.NeedsFiles))
        assertFalse(TransferActionGuard.isReady(FabReadiness.NeedsRemoteFolder))
        assertFalse(TransferActionGuard.isReady(FabReadiness.NeedsSelection))
        assertFalse(TransferActionGuard.isReady(FabReadiness.NeedsDeviceFolder))
    }

    @Test
    fun `the button and the refusal agree on every input`() {
        // Whatever the counts, the button may only look live when a tap would really
        // start something: isReady() must be false exactly when a reason is named.
        for (count in 0..3) {
            for (folder in listOf(true, false)) {
                val upload = TransferActionGuard.upload(count, folder)
                assertEquals(count > 0 && folder, TransferActionGuard.isReady(upload))

                val download = TransferActionGuard.download(count, folder)
                assertEquals(count > 0 && folder, TransferActionGuard.isReady(download))
            }
        }
    }

    @Test
    fun `a negative count is treated as nothing staged`() {
        // Defensive: a count that cannot happen must not make the button lie.
        assertEquals(FabReadiness.NeedsFiles, TransferActionGuard.upload(-1, remoteFolderOpen = true))
        assertEquals(FabReadiness.NeedsSelection, TransferActionGuard.download(-1, deviceFolderChosen = true))
    }
}
