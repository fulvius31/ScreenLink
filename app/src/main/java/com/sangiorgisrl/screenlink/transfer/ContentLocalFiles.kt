package com.sangiorgisrl.screenlink.transfer

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import androidx.core.net.toUri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Device-side file access through the Storage Access Framework.
 *
 * The app holds NO storage permission of its own: an upload reads a document the
 * user picked (or shared in), and a download writes into a folder the user picked
 * with the tree picker. Both grants are taken as *persistable* so a transfer that
 * is still running when the screen rotates - or one the user starts and then
 * backgrounds - can keep reading/writing.
 */
class ContentLocalFiles(private val context: Context) : LocalFileGateway {

    private val resolver get() = context.contentResolver

    override fun describe(uri: String): LocalItem? {
        val parsed = uri.toUri()
        var name: String? = null
        var size = -1L
        try {
            resolver.query(parsed, PROJECTION, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.optStringOrNull(PROJECTION.indexOf(OpenableColumns.DISPLAY_NAME))
                    size = cursor.optLongOrNull(PROJECTION.indexOf(OpenableColumns.SIZE)) ?: -1L
                }
            }
        } catch (_: Exception) {
            // Some providers refuse the metadata query; the name/size fall back below.
        }
        if (size < 0L) size = statSize(parsed)
        if (name.isNullOrBlank()) name = fallbackName(parsed)
        return LocalItem(
            uri = uri,
            name = RemotePaths.sanitizeName(name),
            sizeBytes = size,
            mimeType = resolver.getType(parsed),
        )
    }

    override fun source(uri: String): LocalSource {
        val parsed = uri.toUri()
        val size = describe(uri)?.sizeBytes ?: statSize(parsed)
        return object : LocalSource {
            override val sizeBytes: Long = size
            override fun open(): InputStream =
                resolver.openInputStream(parsed)
                    ?: throw FileNotFoundException("cannot read $uri")
        }
    }

    override fun sink(dirUri: String, name: String, mimeType: String?): LocalSink {
        val tree = dirUri.toUri()
        return object : LocalSink {
            override val targetName: String = name

            /** The document this sink created, so [discard] can remove it again. */
            private var created: Uri? = null

            override fun open(): OutputStream {
                // Created lazily (at the moment the transfer actually starts) so a
                // queued file does not leave an empty placeholder behind if the user
                // cancels the whole batch.
                val document = create(name, mimeType)
                // Second guard, on the provider's side of the race: several providers
                // answer createDocument with the document that is ALREADY there, and
                // the "wt" below would truncate the user's file. A fresh file is
                // empty, so anything that already holds bytes is not ours to replace.
                // Only a file that is empty (so: ours) may be claimed. The document is
                // recorded AFTER that check, because [discard] deletes what it records
                // and a pre-existing file is not ours to delete.
                if (documentSize(document) > 0L) throw DestinationExistsException(name)
                this.created = document
                return resolver.openOutputStream(document, "wt")
                    ?: throw IOException("cannot write \"$name\" in the chosen folder")
            }

            override fun discard() {
                val document = created ?: return
                created = null
                // Best effort: a provider that refuses the delete leaves a truncated
                // file behind, but the row already says cancelled/failed.
                runCatching { DocumentsContract.deleteDocument(resolver, document) }
            }

            /**
             * Ask the folder for a new document called [name].
             *
             * The parent has to be a **document** URI, never the tree URI the folder
             * picker handed back. `DocumentsContract.createDocument` forwards whatever
             * it is given to the provider, and `DocumentsProvider.call` resolves the
             * parent with `DocumentsContract.getDocumentId`, which throws
             * `IllegalArgumentException` on a bare `/tree/<id>` URI. Passing the tree
             * URI is exactly how every "copy to device" died with *could not create
             * "file" in the chosen folder* - the create call never reached the disk.
             */
            private fun create(name: String, mimeType: String?): Uri {
                val docId = treeDocumentId(tree)
                    ?: throw FolderNotWritableException(name)
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                var refusal: Exception? = null
                for (mime in SafMime.candidates(name, mimeType)) {
                    try {
                        DocumentsContract.createDocument(resolver, parent, mime, name)
                            ?.let { return it }
                        // The provider said "no" without throwing: the type is the
                        // only thing here worth changing.
                    } catch (e: SecurityException) {
                        // No write grant (the folder was revoked, or it is read-only).
                        // A different MIME type cannot fix that, so stop and say so.
                        throw FolderNotWritableException(name, e)
                    } catch (e: Exception) {
                        refusal = e
                    }
                }
                // Every candidate failed. The provider's own words stay in the cause
                // (logcat); what the user gets is the one thing they can act on.
                throw FolderNotWritableException(name, refusal)
            }
        }
    }

    override fun childNames(dirUri: String): List<String> {
        val tree = dirUri.toUri()
        val docId = treeDocumentId(tree) ?: return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val names = ArrayList<String>()
        try {
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.let { names += it }
                    }
                }
        } catch (_: Exception) {
            // A provider that will not list children just means we cannot pre-resolve
            // a collision; the create call below still reports the real failure.
        }
        return names
    }

    override fun folderDisplayName(dirUri: String): String {
        val tree = dirUri.toUri()
        val docId = treeDocumentId(tree)
        if (docId != null) {
            val document = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            try {
                resolver.query(
                    document,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0)?.let { return it }
                }
            } catch (_: Exception) {
                // A provider that refuses the metadata query still has a name the
                // URI can show, so this is not a dead end.
            }
        }
        return fallbackName(tree)
    }

    /**
     * Give back the persistable read grant of a document nobody is copying any more.
     * Silent: a staged share copy (`file://`) holds no grant at all, and the
     * platform throws when asked to release one it never handed out.
     */
    override fun releaseReadGrant(uri: String) {
        runCatching {
            resolver.releasePersistableUriPermission(
                uri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    // --- helpers --------------------------------------------------------------

    /**
     * The document id a tree URI stands for, or null when it is not a tree URI.
     *
     * `getDocumentId` is the WRONG call here: it reads `…/document/x` URIs and
     * throws `IllegalArgumentException` on the `…/tree/x` URIs the folder picker
     * hands back. That exception escaping this class is exactly how picking a
     * device folder left the screen still saying "No folder chosen yet", and why
     * a name clash in the user's folder was not pre-resolved.
     */
    private fun treeDocumentId(tree: Uri): String? = runCatching {
        DocumentsContract.getTreeDocumentId(tree)
    }.getOrNull()

    /** Bytes a freshly created document already holds (0 for a genuinely new file). */
    private fun documentSize(uri: Uri): Long = try {
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        } ?: 0L
    } catch (_: Exception) {
        // A provider that will not answer the size query is still covered by the
        // collision check the queue ran before opening this sink.
        0L
    }

    private fun statSize(uri: Uri): Long = try {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    } catch (_: Exception) {
        -1L
    }

    private fun fallbackName(uri: Uri): String {
        // A picked folder names itself: "…/tree/primary%3ADownload" is "Download".
        DeviceFolderLabel.fromTreeUri(uri.toString())?.let { return it }
        val last = uri.lastPathSegment ?: uri.pathSegments.lastOrNull() ?: "file"
        // "com.example.provider/document/1234" style ids: keep only the leaf.
        return RemotePaths.sanitizeName(last.substringAfterLast('/'))
    }

    private fun Cursor.optStringOrNull(index: Int): String? =
        if (index >= 0 && !isNull(index)) getString(index) else null

    private fun Cursor.optLongOrNull(index: Int): Long? =
        if (index >= 0 && !isNull(index)) getLong(index) else null

    private companion object {
        val PROJECTION = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    }
}
