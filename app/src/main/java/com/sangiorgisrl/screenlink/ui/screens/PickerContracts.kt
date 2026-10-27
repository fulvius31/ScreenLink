package com.sangiorgisrl.screenlink.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract

/**
 * Pick one folder and get back a grant the copy can actually use.
 *
 * androidx's own `OpenDocumentTree` adds **no** grant flags to the intent, so what
 * the picker hands back is up to whichever picker the OEM ships: the tree may come
 * with a read-only grant, or with a grant that cannot be persisted. Both failures
 * surface far from here - as a SecurityException in the middle of a download, or as
 * a copy that works now and fails after a reboot - so the flags are spelled out
 * where the request is made.
 */
internal object WriteableTreePicker : ActivityResultContract<Uri?, Uri?>() {

    override fun createIntent(context: Context, input: Uri?): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    // The copy writes files UNDER the picked folder; a prefix grant is
                    // what makes those child documents usable without a new grant.
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            input?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.takeIf { resultCode == Activity.RESULT_OK }?.data
}

/**
 * Pick any number of files, with a read grant that can be kept.
 *
 * A share or a picked file may still be copying when the screen closes or the phone
 * sleeps, and the queue reads it later; without a persistable grant the URI can die
 * under a running copy.
 */
internal object PersistableDocumentPicker : ActivityResultContract<Array<String>, List<Uri>>() {

    override fun createIntent(context: Context, input: Array<String>): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.firstOrNull() ?: "*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, input)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )

    /** Same shape androidx uses: a single `data` URI, a `clipData` list, or nothing. */
    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val uris = ArrayList<Uri>()
        intent.data?.let { uris += it }
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) uris += clip.getItemAt(index).uri
        }
        return uris
    }
}
