package com.sangiorgisrl.screenlink.transfer

import java.io.ByteArrayOutputStream

/**
 * A readable name for the device folder the user picked, worked out from the SAF
 * tree URI alone.
 *
 * The provider is asked first ([LocalFileGateway.folderDisplayName]) because its
 * answer is the name the user sees in their own files app. That answer is not
 * always reachable - a provider can refuse the metadata query, and a tree URI is
 * not a document URI at all - so the label must survive without it. Getting this
 * wrong is a UX bug, not a cosmetic one: a card that still says "No folder chosen
 * yet" after the user chose one sends them back through the picker.
 *
 * A tree URI looks like
 * `content://com.android.externalstorage.documents/tree/primary%3ADownload`,
 * where the last segment is the volume plus the path, percent-encoded.
 */
object DeviceFolderLabel {

    /**
     * Segment ids that name a provider's root rather than a folder the user could
     * recognise, so showing them would be worse than saying "Selected folder".
     */
    private val GENERIC_IDS = setOf("root", "home", "primary", "external_storage_root", "media")

    /** The folder's own name, or null when the URI says nothing readable. */
    fun fromTreeUri(treeUri: String): String? {
        val path = treeUri.substringBefore('?').trimEnd('/')
        val segments = path.split('/')
        val treeIndex = segments.indexOf("tree")
        if (treeIndex < 0 || treeIndex == segments.lastIndex) return null
        // Everything after "tree": a nested tree keeps its separators encoded, but a
        // provider that leaves them plain is handled the same way.
        val raw = segments.drop(treeIndex + 1).joinToString("/")
        return readableLeaf(percentDecode(raw))
    }

    /**
     * Drop the volume prefix and walk down to the folder's own name.
     *
     * "primary:Download" -> "Download", "1234-5678:Documents/2026" -> "2026",
     * "root:/Shared drives/Reports" -> "Reports". A bare volume ("primary:") names
     * nothing, which is answered honestly with null.
     */
    private fun readableLeaf(decoded: String): String? {
        val leaf = decoded.trim().trimEnd('/').substringAfterLast('/').substringAfterLast(':').trim()
        if (leaf.isEmpty() || leaf.lowercase() in GENERIC_IDS) return null
        val cleaned = RemotePaths.sanitizeName(leaf)
        // sanitizeName invents "file" for an empty name; that is not a folder label.
        return cleaned.takeIf { it.isNotBlank() && it != "file" }
    }

    /**
     * Percent-decoding that keeps non-ASCII folder names intact.
     *
     * Decoding escape by escape into chars would turn "Caf%C3%A9" into mojibake: the
     * bytes of one UTF-8 sequence have to be collected and decoded together. A
     * malformed escape is kept as it is rather than dropped, because a half-right
     * name still beats a blank one.
     */
    private fun percentDecode(raw: String): String {
        if (!raw.contains('%')) return raw
        val bytes = ByteArrayOutputStream(raw.length)
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            val code = if (ch == '%' && index + 2 < raw.length) {
                raw.substring(index + 1, index + 3).toIntOrNull(16)
            } else {
                null
            }
            if (code != null) {
                bytes.write(code)
                index += 3
            } else {
                ch.toString().toByteArray(Charsets.UTF_8).forEach { byte ->
                    bytes.write(byte.toInt() and 0xff)
                }
                index++
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
