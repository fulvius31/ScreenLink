package com.sangiorgisrl.screenlink.transfer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where a byte actually lands.
 *
 * These two rules are the ones that can silently destroy user data: a name that
 * arrives from another app (a share sheet hands over ANYTHING) must not escape the
 * folder it is aimed at, and a copy must never replace a file that is already
 * there. Both are pure string rules, so they are pinned here rather than by
 * clicking through a live server.
 */
class RemotePathsTest {

    // --- path arithmetic ------------------------------------------------------

    @Test
    fun `join never doubles the separator`() {
        assertEquals("/srv/uploads/report.pdf", RemotePaths.join("/srv/uploads", "report.pdf"))
        assertEquals("/report.pdf", RemotePaths.join("/", "report.pdf"))
        assertEquals("/srv/uploads/report.pdf", RemotePaths.join("/srv/uploads/", "report.pdf"))
        // A blank name is not a path: the folder itself is the answer.
        assertEquals("/srv/uploads", RemotePaths.join("/srv/uploads", ""))
    }

    @Test
    fun `parent walks up exactly one level and stops at the root`() {
        assertEquals("/srv", RemotePaths.parent("/srv/uploads"))
        assertEquals("/srv", RemotePaths.parent("/srv/uploads/"))
        // The root's parent is the root, expressed as ROOT, not "" or null-by-accident.
        assertEquals(RemotePaths.ROOT, RemotePaths.parent("/srv"))
        assertNull(RemotePaths.parent(RemotePaths.ROOT))
        assertNull(RemotePaths.parent("/"))
        assertNull(RemotePaths.parent("relative"))
    }

    @Test
    fun `leafName is the last segment and the root keeps its own name`() {
        assertEquals("report.pdf", RemotePaths.leafName("/srv/uploads/report.pdf"))
        assertEquals("uploads", RemotePaths.leafName("/srv/uploads/"))
        assertEquals(RemotePaths.ROOT, RemotePaths.leafName(RemotePaths.ROOT))
        assertEquals(RemotePaths.ROOT, RemotePaths.leafName("/"))
    }

    @Test
    fun `breadcrumbs are clickable from the root down`() {
        assertEquals(
            listOf("srv" to "/srv", "uploads" to "/srv/uploads"),
            RemotePaths.breadcrumbs("/srv/uploads"),
        )
        assertEquals(listOf("srv" to "/srv"), RemotePaths.breadcrumbs("/srv/"))
        // Always at least one crumb, so "go to /" is always offered.
        assertEquals(listOf(RemotePaths.ROOT to RemotePaths.ROOT), RemotePaths.breadcrumbs("/"))
    }

    @Test
    fun `isAbsolute only accepts a leading slash`() {
        assertTrue(RemotePaths.isAbsolute("/srv"))
        assertFalse(RemotePaths.isAbsolute("srv"))
    }

    // --- untrusted names ------------------------------------------------------

    @Test
    fun `a shared name can never climb out of the target folder`() {
        // The classic traversal attempt: only the last component survives.
        assertEquals("passwd", RemotePaths.sanitizeName("../../etc/passwd"))
        assertEquals("passwd", RemotePaths.sanitizeName("..\\..\\etc\\passwd"))
        assertEquals("etc", RemotePaths.sanitizeName("/etc/"))
        // "." and ".." would be a hidden/self reference: they become a plain name.
        assertEquals("file", RemotePaths.sanitizeName(".."))
        assertEquals("file", RemotePaths.sanitizeName("."))
    }

    @Test
    fun `control characters and separators are dropped, not escaped`() {
        assertEquals("report.pdf", RemotePaths.sanitizeName("report\n.pdf"))
        assertEquals("report.pdf", RemotePaths.sanitizeName("report\t\r.pdf"))
        assertEquals("report.pdf", RemotePaths.sanitizeName("report\u0000.pdf"))
        assertEquals("report.pdf", RemotePaths.sanitizeName("report\u007f.pdf"))
        // A Windows path from a desktop sender still yields just the file name.
        assertEquals("notes.txt", RemotePaths.sanitizeName("C:\\Users\\me\\notes.txt"))
    }

    @Test
    fun `a leading dot survives because it is part of the file's meaning`() {
        // ".bashrc", ".env" and ".gitignore" are ordinary names, and a share sheet
        // that renames ".bashrc" to "bashrc" quietly changes what the file does on
        // the server. Traversal is already impossible: only the last path
        // component survives, and a name made ONLY of dots collapses below.
        assertEquals(".bashrc", RemotePaths.sanitizeName(".bashrc"))
        assertEquals(".gitignore", RemotePaths.sanitizeName(".gitignore"))
        assertEquals(".env", RemotePaths.sanitizeName(".env"))
        assertEquals(".hidden.log", RemotePaths.sanitizeName("./.hidden.log"))
        // A dotfile handed over by a desktop sender still keeps its leading dot.
        assertEquals(".bashrc", RemotePaths.sanitizeName("/home/me/.bashrc"))
    }

    @Test
    fun `a name made only of dots collapses, and trailing dots or spaces are trimmed`() {
        // "." / ".." / "..." are self/parent references, never file names.
        assertEquals("file", RemotePaths.sanitizeName("..."))
        assertEquals("file", RemotePaths.sanitizeName(".."))
        // Trailing dots/spaces are rejected by several SAF providers and by SMB.
        assertEquals("report", RemotePaths.sanitizeName("report..."))
        assertEquals("report", RemotePaths.sanitizeName("report   "))
        assertEquals("report.pdf", RemotePaths.sanitizeName("  report.pdf  "))
        // Whitespace inside a name is legitimate - the user's file name is kept.
        assertEquals("quarterly report final.pdf", RemotePaths.sanitizeName("quarterly report final.pdf"))
    }

    @Test
    fun `an empty or blank name still produces a real file`() {
        assertEquals("file", RemotePaths.sanitizeName(""))
        assertEquals("file", RemotePaths.sanitizeName("   "))
        assertEquals("file", RemotePaths.sanitizeName("\n\t"))
        assertEquals("file", RemotePaths.sanitizeName("/"))
    }

    @Test
    fun `a very long name is capped but keeps its extension`() {
        val long = "a".repeat(300) + ".tar.gz"
        val safe = RemotePaths.sanitizeName(long)

        assertTrue(safe.length <= RemotePaths.MAX_NAME_LENGTH)
        // Cutting the middle of the name is fine; cutting the extension is not:
        // ".gz" is what tells the server (and the user) what the file is.
        assertTrue(safe.endsWith(".gz"))
        assertTrue(safe.startsWith("aaa"))
    }

    @Test
    fun `truncate keeps the extension only when it is short enough to be one`() {
        assertEquals("aaa.tar.gz", RemotePaths.truncateKeepingExtension("aaa.tar.gz", 99))
        val short = RemotePaths.truncateKeepingExtension("a".repeat(40) + ".pdf", 20)
        assertEquals(20, short.length)
        assertTrue(short.endsWith(".pdf"))
        // A trailing blob that is not plausibly an extension is just cut away.
        val noExt = RemotePaths.truncateKeepingExtension("a".repeat(40) + ".notanextensionatall", 20)
        assertEquals(20, noExt.length)
        assertFalse(noExt.contains('.'))
    }

    // --- collisions -----------------------------------------------------------

    @Test
    fun `an existing name is never overwritten - it gets a numbered variant`() {
        assertEquals(
            "report (1).pdf",
            RemotePaths.uniqueName("report.pdf", setOf("report.pdf")),
        )
        assertEquals(
            "report (2).pdf",
            RemotePaths.uniqueName("report.pdf", setOf("report.pdf", "report (1).pdf")),
        )
        // Nothing to dodge.
        assertEquals("report.pdf", RemotePaths.uniqueName("report.pdf", setOf("other.pdf")))
        assertEquals("report.pdf", RemotePaths.uniqueName("report.pdf", emptySet()))
    }

    @Test
    fun `collision checks ignore case because the target may be case-insensitive`() {
        // SMB/NFS mounts and some SFTP servers fold case: "Report.PDF" IS a clash,
        // and in a device folder it would silently replace the user's file.
        assertEquals("report (1).PDF", RemotePaths.uniqueName("report.PDF", setOf("Report.PDF")))
        assertEquals("a (1)", RemotePaths.uniqueName("a", setOf("A")))
    }

    @Test
    fun `a nameless file still gets a unique name`() {
        assertEquals("file (1)", RemotePaths.uniqueName("file", setOf("file")))
    }

    @Test
    fun `a numbered variant of a long name still fits the cap`() {
        val base = "b".repeat(RemotePaths.MAX_NAME_LENGTH)
        val unique = RemotePaths.uniqueName(base, setOf(base))

        assertTrue(unique.length <= RemotePaths.MAX_NAME_LENGTH)
        assertTrue(unique.endsWith(")"))
    }

    @Test
    fun `a folder full of numbered variants falls back to a stamped name`() {
        // 999 clashes is a queue that retried the same file over and over; the
        // counter has nothing left to offer, so the name gets a time stamp instead
        // of either overwriting the user's file or looping forever.
        val taken = (0..999).map { "report ($it).pdf" }.toSet() + "report.pdf"
        val unique = RemotePaths.uniqueName("report.pdf", taken, stamp = 1_698_765_432L)

        assertEquals("report-1698765432.pdf", RemotePaths.stampedName("report.pdf", 1_698_765_432L))
        assertEquals("report-1698765432.pdf", unique)
        assertTrue(unique !in taken)
        // A name that is free never needs the stamp.
        assertEquals("other.pdf", RemotePaths.uniqueName("other.pdf", taken, stamp = 1_698_765_432L))
    }

    @Test
    fun `a stamped name of a long name still fits the cap and keeps its extension`() {
        val base = "c".repeat(RemotePaths.MAX_NAME_LENGTH)
        val stamped = RemotePaths.stampedName("$base.tar.gz", 1_698_765_432L)

        assertTrue(stamped.length <= RemotePaths.MAX_NAME_LENGTH)
        assertTrue(stamped.endsWith(".gz"))
        assertTrue(stamped.contains("1698765432"))
    }

    // --- listing order --------------------------------------------------------

    @Test
    fun `folders come first then names case-insensitively`() {
        val entries = listOf(
            entry("zeta.txt", isDir = false),
            entry("Beta", isDir = true),
            entry("alpha.txt", isDir = false),
            entry("alpha.md", isDir = false),
            entry("beta.txt", isDir = false),
        )

        val sorted = RemotePaths.sort(entries).map { it.name }

        assertEquals(listOf("Beta", "alpha.md", "alpha.txt", "beta.txt", "zeta.txt"), sorted)
    }

    private fun entry(name: String, isDir: Boolean) = RemoteEntry(
        name = name,
        path = "/srv/$name",
        isDirectory = isDir,
    )
}
