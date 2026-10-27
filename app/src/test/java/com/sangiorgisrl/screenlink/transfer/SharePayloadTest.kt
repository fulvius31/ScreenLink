package com.sangiorgisrl.screenlink.transfer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a share intent is allowed to become.
 *
 * "I shared a file and the app showed nothing" is the single most damaging failure
 * of this feature, and it is decided entirely by these string rules (the URIs are
 * plain strings so the rules are testable at all - `android.net.Uri` is a stub on
 * the JVM). So every way a provider can hand over a payload gets pinned here.
 */
class SharePayloadTest {

    @Test
    fun `a single shared file is staged`() {
        val decision = SharePayload.decide(
            streamUris = listOf("content://media/external/images/12/photo.jpg"),
            clipUris = emptyList(),
            text = null,
            title = null,
        )

        assertEquals(listOf("content://media/external/images/12/photo.jpg"), decision.stageable)
        assertTrue(decision.dropped.isEmpty())
        assertFalse(decision.isEmpty)
    }

    @Test
    fun `a file offered only through clip data is still found`() {
        // Several providers (and most "share to app" implementations on tablets)
        // put the payload ONLY in the clip: reading EXTRA_STREAM alone loses it.
        val decision = SharePayload.decide(
            streamUris = emptyList(),
            clipUris = listOf("content://com.foo.provider/document/42"),
            text = null,
            title = null,
        )

        assertEquals(listOf("content://com.foo.provider/document/42"), decision.stageable)
    }

    @Test
    fun `a provider that fills both slots queues the file once`() {
        val decision = SharePayload.decide(
            streamUris = listOf("content://x/document/42"),
            clipUris = listOf("content://x/document/42"),
            text = null,
            title = null,
        )

        // Order is kept (the user's selection order is the queue order).
        assertEquals(listOf("content://x/document/42"), decision.stageable)
    }

    @Test
    fun `multiple selection keeps its order and drops blanks`() {
        val decision = SharePayload.decide(
            streamUris = listOf("content://a/1", "", "  ", "content://b/2"),
            clipUris = emptyList(),
            text = null,
            title = null,
        )

        assertEquals(listOf("content://a/1", "content://b/2"), decision.stageable)
    }

    @Test
    fun `a uri with no scheme is refused rather than opened later and failing`() {
        val decision = SharePayload.decide(
            streamUris = listOf("/storage/emulated/0/Download/notes.txt"),
            clipUris = emptyList(),
            text = null,
            title = null,
        )

        assertTrue(decision.stageable.isEmpty())
        // Refused, but NAMED: the UI can say what happened instead of staying silent.
        assertEquals(listOf("/storage/emulated/0/Download/notes.txt"), decision.dropped)
        assertTrue(decision.isEmpty)
    }

    @Test
    fun `a shared folder is named as dropped instead of quietly vanishing`() {
        // A tree URI is a folder: the app cannot copy a folder, and pretending
        // otherwise would queue something that fails with a server error later.
        val decision = SharePayload.decide(
            streamUris = listOf("content://com.android.documentsui/tree/primary%3ADownload"),
            clipUris = emptyList(),
            text = null,
            title = null,
        )

        assertTrue(decision.stageable.isEmpty())
        assertEquals(1, decision.dropped.size)
    }

    @Test
    fun `a shared text becomes a file rather than an empty list`() {
        val decision = SharePayload.decide(
            streamUris = emptyList(),
            clipUris = emptyList(),
            text = "  sudo systemctl restart sshd\n",
            title = "  fix ssh  ",
        )

        assertNull(decision.stageable.singleOrNull())
        assertEquals("sudo systemctl restart sshd", decision.text)
        assertEquals("fix ssh", decision.title)
        assertFalse(decision.isEmpty)
    }

    @Test
    fun `blank text and blank title are not content`() {
        val decision = SharePayload.decide(
            streamUris = emptyList(),
            clipUris = emptyList(),
            text = "   \n ",
            title = "   ",
        )

        assertNull(decision.text)
        assertNull(decision.title)
        assertTrue(decision.isEmpty)
    }

    @Test
    fun `a share that carried nothing at all is reported as empty`() {
        val decision = SharePayload.decide(emptyList(), emptyList(), null, null)

        assertTrue(decision.isEmpty)
        assertTrue(decision.dropped.isEmpty())
    }
}
