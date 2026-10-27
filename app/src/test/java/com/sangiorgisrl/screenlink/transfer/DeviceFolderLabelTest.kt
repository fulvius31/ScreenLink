package com.sangiorgisrl.screenlink.transfer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The name shown on the "device folder" card when the provider will not say it.
 *
 * The bug this pins: the card kept reading "No folder chosen yet" after the user
 * had picked a folder, because the name lookup threw on a tree URI and nothing
 * else was left to show. A tree URI is not a document URI, so the label has to be
 * readable from the URI itself.
 */
class DeviceFolderLabelTest {

    @Test
    fun `the internal storage folder names itself`() {
        assertEquals(
            "Download",
            DeviceFolderLabel.fromTreeUri(
                "content://com.android.externalstorage.documents/tree/primary%3ADownload",
            ),
        )
    }

    @Test
    fun `a nested folder on an SD card shows its own leaf, not the volume`() {
        assertEquals(
            "2026",
            DeviceFolderLabel.fromTreeUri(
                "content://com.android.externalstorage.documents/tree/1234-5678%3ADocuments%2F2026",
            ),
        )
        // A provider that leaves the separators plain instead of encoded.
        assertEquals(
            "2026",
            DeviceFolderLabel.fromTreeUri(
                "content://com.android.externalstorage.documents/tree/1234-5678:Documents/2026",
            ),
        )
    }

    @Test
    fun `a cloud root id is not a folder name a user recognises`() {
        // "root" would be shown as if the user had named a folder that.
        assertNull(
            DeviceFolderLabel.fromTreeUri(
                "content://com.google.android.apps.docs.io.documents/tree/root",
            ),
        )
        // ...but a folder inside it is still readable.
        assertEquals(
            "Reports",
            DeviceFolderLabel.fromTreeUri(
                "content://com.google.android.apps.docs.io.documents/tree/root%3A%2FShared%20drives%2FReports",
            ),
        )
    }

    @Test
    fun `a non-ASCII folder name survives its own escaping`() {
        // Decoding escape-by-escape into chars would produce mojibake here.
        assertEquals(
            "Café",
            DeviceFolderLabel.fromTreeUri(
                "content://com.android.externalstorage.documents/tree/primary%3ACaf%C3%A9",
            ),
        )
    }

    @Test
    fun `a bare volume names nothing`() {
        assertNull(
            DeviceFolderLabel.fromTreeUri(
                "content://com.android.externalstorage.documents/tree/primary%3A",
            ),
        )
    }

    @Test
    fun `anything that is not a tree URI says nothing rather than guessing`() {
        assertNull(DeviceFolderLabel.fromTreeUri(""))
        assertNull(DeviceFolderLabel.fromTreeUri("content://media/external/images/media/12"))
        assertNull(DeviceFolderLabel.fromTreeUri("content://com.example.documents/tree"))
        // A document URI (what a FILE picker returns) has no tree segment.
        assertNull(
            DeviceFolderLabel.fromTreeUri(
                "content://com.android.externalstorage.documents/document/primary%3Anotes.txt",
            ),
        )
    }

    @Test
    fun `a broken percent escape is kept rather than guessed`() {
        // A provider (or a hand-written URI) can emit something that is not a valid
        // escape. Dropping it would silently rename the user's folder; keeping the
        // text is the honest answer, and it still has to be readable.
        assertEquals(
            "100%ZZ",
            DeviceFolderLabel.fromTreeUri(
                "content://com.android.externalstorage.documents/tree/primary%3A100%ZZ",
            ),
        )
        assertEquals(
            "Trip%",
            DeviceFolderLabel.fromTreeUri(
                "content://com.android.externalstorage.documents/tree/primary%3ATrip%",
            ),
        )
    }

    @Test
    fun `a query string or trailing slash does not eat the name`() {
        assertEquals(
            "Download",
            DeviceFolderLabel.fromTreeUri(
                "content://com.android.externalstorage.documents/tree/primary%3ADownload/?x=1",
            ),
        )
        assertEquals(
            "Download",
            DeviceFolderLabel.fromTreeUri(
                "content://com.android.externalstorage.documents/tree/primary%3ADownload/",
            ),
        )
    }
}
