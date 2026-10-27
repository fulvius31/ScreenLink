package com.sangiorgisrl.screenlink.transfer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The MIME types a downloaded file is offered to the device folder with.
 *
 * A DocumentsProvider may refuse a type it does not handle, and the platform does
 * not know every extension this app copies (".md" is the case that broke: the create
 * call was refused and the user saw "could not create dossier.md"). So the list must
 * always end in the type every provider accepts, and must never repeat itself.
 */
class SafMimeTest {

    @Test
    fun `an unusual extension still ends on the type every provider accepts`() {
        assertEquals(
            listOf("text/markdown", SafMime.OCTET_STREAM),
            SafMime.candidates("dossier.md"),
        )
    }

    @Test
    fun `a name with no extension is offered as a binary file only`() {
        assertEquals(listOf(SafMime.OCTET_STREAM), SafMime.candidates("LICENSE"))
        assertEquals(listOf(SafMime.OCTET_STREAM), SafMime.candidates(""))
    }

    @Test
    fun `the extension is read case-insensitively and from the last dot`() {
        assertEquals(listOf("application/pdf", SafMime.OCTET_STREAM), SafMime.candidates("Plan.PDF"))
        assertEquals(
            listOf("application/gzip", SafMime.OCTET_STREAM),
            SafMime.candidates("backup.2026.tar.gz"),
        )
    }

    @Test
    fun `a mime the caller already knows wins, and is not tried twice`() {
        assertEquals(
            listOf("image/png", SafMime.OCTET_STREAM),
            SafMime.candidates("photo.jpg", "image/png"),
        )
        // The fallback is always there as a second chance, so a caller that already
        // named it must not make the provider be asked twice.
        assertEquals(listOf("text/markdown"), SafMime.candidates("dossier.md", "text/markdown").take(1))
        assertEquals(
            listOf(SafMime.OCTET_STREAM),
            SafMime.candidates("dossier.md", SafMime.OCTET_STREAM),
        )
    }

    @Test
    fun `a wildcard or blank mime is not offered to the provider`() {
        // "*/*" is a picker filter, not a file type; a provider would refuse it.
        assertEquals(
            listOf("text/plain", SafMime.OCTET_STREAM),
            SafMime.candidates("notes.txt", "*/*"),
        )
        assertEquals(listOf("text/plain", SafMime.OCTET_STREAM), SafMime.candidates("notes.txt", "   "))
    }

    @Test
    fun `the fallback is always the last candidate`() {
        for (name in listOf("dossier.md", "a.bin", "no-extension", "x.tar.gz", "photo.jpeg")) {
            val candidates = SafMime.candidates(name)
            assertTrue(candidates.isNotEmpty(), name)
            assertEquals(SafMime.OCTET_STREAM, candidates.last(), name)
            assertEquals(candidates.distinct(), candidates, name)
        }
    }
}
