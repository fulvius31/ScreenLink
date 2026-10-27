package com.sangiorgisrl.screenlink.transfer

/**
 * Pure POSIX-path and file-naming rules for the file transfer feature.
 *
 * Everything that decides *where a byte lands* lives here rather than in a
 * composable or in the SFTP call, so it can be unit tested: joining paths without
 * doubling slashes, walking up to a parent, and - most importantly - turning a
 * name that came from another app (a share sheet hands over anything: `../../x`,
 * `report\n.pdf`, `a/b.txt`) into one safe file name, plus a collision-free
 * variant of it (`report (1).pdf`).
 */
object RemotePaths {

    const val ROOT = "/"

    /** Longest name we will create; keeps a path under the usual 255-byte limit. */
    const val MAX_NAME_LENGTH = 120

    fun isAbsolute(path: String): Boolean = path.startsWith(ROOT)

    /** Join a directory and a name without ever doubling the separator. */
    fun join(dir: String, name: String): String {
        if (name.isEmpty()) return dir
        return if (dir.endsWith(ROOT)) dir + name else "$dir/$name"
    }

    /** Parent directory, or null when already at the root. */
    fun parent(path: String): String? {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return null
        val cut = trimmed.lastIndexOf('/')
        return if (cut < 0) null else if (cut == 0) ROOT else trimmed.substring(0, cut)
    }

    /** Last path segment ("" for "/"), used for titles and breadcrumbs. */
    fun leafName(path: String): String {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return ROOT
        return trimmed.substringAfterLast('/')
    }

    /**
     * Split a path into breadcrumb segments, root first (always at least one,
     * the root itself, so the UI can always offer "up to /").
     */
    fun breadcrumbs(path: String): List<Pair<String, String>> {
        val parts = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
        val out = ArrayList<Pair<String, String>>(parts.size + 1)
        var acc = StringBuilder()
        parts.forEach { part ->
            acc.append('/').append(part)
            out += part to acc.toString()
        }
        if (out.isEmpty()) out += ROOT to ROOT
        return out
    }

    /**
     * Make an untrusted name safe to create.
     *
     * A share sheet can hand over any string, and this name is used verbatim as a
     * remote path segment and as a SAF document name, so: only the last path
     * component survives (no `../` traversal), control characters and separators
     * go away, a name made ONLY of dots (`.` / `..` / `...`) is rewritten, trailing
     * dots/spaces (rejected by many providers) are trimmed, and the result is
     * capped.
     *
     * A leading dot is NOT stripped: `.bashrc`, `.gitignore` and `.env` are ordinary
     * file names, and a share that renames `.bashrc` to `bashrc` quietly changes
     * what the file means on the server. Traversal is already impossible because
     * only the last path component survives, and a bare `.`/`..` collapses to
     * "file" through the blank fallback below.
     */
    fun sanitizeName(raw: String): String {
        // A trailing separator ("/srv/uploads/") still names "uploads", not nothing.
        val leaf = raw.replace('\\', '/').trimEnd('/').substringAfterLast('/')
        val cleaned = buildString {
            for (ch in leaf) {
                if (ch.code < 0x20 || ch.code == 0x7f) continue
                append(ch)
            }
        }.trim()
        // Only an all-dots name is a self/parent reference; ".bashrc" is a name.
        val withoutLeadingDots = if (cleaned.all { it == '.' }) "" else cleaned
        val withoutTrailing = withoutLeadingDots.trimEnd('.', ' ')
        val safe = withoutTrailing.ifBlank { "file" }
        return truncateKeepingExtension(safe, MAX_NAME_LENGTH)
    }

    /** Shorten to [max] without cutting the extension off a long name. */
    fun truncateKeepingExtension(name: String, max: Int): String {
        if (name.length <= max) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 12) name.substring(dot) else ""
        val base = name.substring(0, (name.length - ext.length).coerceAtLeast(0))
        return base.take((max - ext.length).coerceAtLeast(1)) + ext
    }

    /**
     * A name that does not clash with [taken]: `report.pdf` -> `report (1).pdf`.
     *
     * Comparisons are case-insensitive because the target may be a case-insensitive
     * filesystem (SMB/NFS mounts, some SFTP servers) and because the same name in
     * a device folder would silently overwrite the user's file.
     */
    fun uniqueName(desired: String, taken: Set<String>, stamp: Long = System.currentTimeMillis()): String {
        val lower = taken.map { it.lowercase() }.toSet()
        if (desired.lowercase() !in lower) return desired
        val dot = desired.lastIndexOf('.')
        val base = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        for (n in 1..999) {
            // The counter is appended AFTER the base is shortened, so a name that is
            // already at the cap still gets a visible "(1)" instead of having the
            // number - and the whole point of the fix - cut off again.
            val suffix = " ($n)$ext"
            val candidate = base.take((MAX_NAME_LENGTH - suffix.length).coerceAtLeast(1)) + suffix
            if (candidate.lowercase() !in lower) return candidate
        }
        return stampedName(desired, stamp)
    }

    /**
     * A name stamped with [stamp] instead of another counter: `report-1698.pdf`.
     *
     * The last resort when a folder already holds every numbered variant of a name
     * (a queue that retried the same file 999 times). [stamp] is a parameter rather
     * than a clock call so the rule can be pinned by a test.
     */
    fun stampedName(name: String, stamp: Long): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val suffix = "-$stamp$ext"
        return base.take((MAX_NAME_LENGTH - suffix.length).coerceAtLeast(1)) + suffix
    }

    /** Directory-first, then case-insensitive name - the order a file manager uses. */
    fun sort(entries: List<RemoteEntry>): List<RemoteEntry> = entries.sortedWith(
        compareByDescending<RemoteEntry> { it.isDirectory }
            .thenBy { it.name.lowercase() }
            .thenBy { it.name },
    )
}
