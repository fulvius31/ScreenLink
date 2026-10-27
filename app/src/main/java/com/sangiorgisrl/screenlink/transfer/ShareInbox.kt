package com.sangiorgisrl.screenlink.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * The inbox for files another app shared into ScreenLink (ACTION_SEND /
 * ACTION_SEND_MULTIPLE), so any MIME type - any file at all - can arrive.
 *
 * The inbox is also the **single source of truth for the "Files to send" list**:
 * the Files screen renders [incoming] and edits it through [add] / [remove], so
 * the list survives leaving the screen, a rotation and a share that arrives while
 * the screen is closed. Nothing consumes it implicitly - a file the user staged is
 * only gone when they send it or remove it.
 *
 * Why the files are *copied* rather than just remembered: the read grant a share
 * sheet hands over is **temporary** - it dies with the Activity that received it.
 * A copy that is still running after the user rotates, navigates away or has the
 * system reclaim the grant must not fail half-way, so every shared URI is staged
 * into the app's own cache directory first and the queue only ever sees a
 * `file://` path the app owns. The original display name is kept (sanitised),
 * because that is the name the user expects to see on the server.
 *
 * The inbox is process-scoped (like [TransferStore]) so a share that arrives while
 * the Files screen is closed is still there when the screen opens.
 */
object ShareInbox {

    /** Folder (below cacheDir) that holds the staged copies. */
    private const val STAGE_DIR = "shared"

    private val _incoming = MutableStateFlow<List<LocalItem>>(emptyList())

    /** Staged files ready to be sent - the "Files to send" list itself. */
    val incoming: StateFlow<List<LocalItem>> = _incoming.asStateFlow()

    private val _preparing = MutableStateFlow(0)

    /** How many shared files are still being copied in (drives the "Preparing…" row). */
    val preparing: StateFlow<Int> = _preparing.asStateFlow()

    private val counter = AtomicInteger(0)

    /**
     * One-shot outcome of a share hand-off, so the screen can say what happened.
     *
     * A channel rather than a StateFlow because it is an *event*: a state would
     * still be there after the snackbar was shown and would be re-shown on the
     * next rotation.
     */
    data class Report(val staged: Int, val failed: Int, val nothingToStage: Boolean = false)

    private val _reports = Channel<Report>(Channel.BUFFERED)
    val reports: Channel<Report> = _reports

    /**
     * What one share intent handed the app, already split into what can be staged.
     *
     * Both EXTRA_STREAM shapes are read (single + multiple) and the clip data is
     * used as a fallback, because several apps put the payload only there; the
     * naming/dedup/drop rules themselves live in [SharePayload] where they can be
     * unit tested.
     */
    data class Payload(val uris: List<Uri>, val dropped: Int, val text: String?, val title: String?)

    /** The URIs and text an ACTION_SEND / ACTION_SEND_MULTIPLE intent carries. */
    fun collect(intent: Intent?): Payload {
        val empty = Payload(emptyList(), 0, null, null)
        val action = intent?.action ?: return empty
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return empty
        val streams = ArrayList<String>()
        if (action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { streams += it.toString() }
        } else {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.forEach { uri -> if (uri != null) streams += uri.toString() }
        }
        val clips = ArrayList<String>()
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { clips += it.toString() }
            }
        }
        val decision = SharePayload.decide(
            streamUris = streams,
            clipUris = clips,
            text = intent.getStringExtra(Intent.EXTRA_TEXT),
            title = intent.getStringExtra(Intent.EXTRA_SUBJECT),
        )
        return Payload(
            uris = decision.stageable.map { it.toUri() },
            dropped = decision.dropped.size,
            text = decision.text,
            title = decision.title,
        )
    }

    /**
     * Copy [uris] into the app cache and publish them as [LocalItem]s.
     *
     * Runs on the caller's scope (the Activity hands this to the application scope:
     * the copy must survive the Activity). A file that cannot be read is skipped -
     * one refusing provider must not swallow the rest of a multi-select share - and
     * the count of what was lost is reported, because a share that silently
     * delivered three of five files is a lie by omission.
     */
    suspend fun stage(context: Context, gateway: LocalFileGateway, uris: List<Uri>) {
        if (uris.isEmpty()) return
        withContext(Dispatchers.IO) {
            _preparing.update { it + uris.size }
            val staged = ArrayList<LocalItem>(uris.size)
            uris.forEach { uri ->
                val item = stageOne(context, gateway, uri)
                if (item != null) staged += item
                _preparing.update { (it - 1).coerceAtLeast(0) }
            }
            add(staged)
            _reports.trySend(Report(staged = staged.size, failed = uris.size - staged.size))
        }
    }

    /**
     * Turn a shared *text* (a link, a snippet, a command) into a staged file.
     *
     * A text/plain share used to be collected and then dropped on the floor: the
     * screen opened with an empty list and the user had no idea the app had thrown
     * their text away. Writing it to a `.txt` keeps the whole point of the share -
     * get this content onto the server - and the subject line becomes the file name.
     */
    suspend fun stageText(context: Context, text: String, title: String?) {
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, STAGE_DIR)
            if (!dir.isDirectory && !dir.mkdirs()) {
                _reports.trySend(Report(staged = 0, failed = 1))
                return@withContext
            }
            val name = "${counter.incrementAndGet()}-${System.currentTimeMillis()}-" +
                displayTextName(title)
            val target = File(dir, name)
            val ok = try {
                target.writeText(text)
                true
            } catch (_: Exception) {
                runCatching { target.delete() }
                false
            }
            if (!ok) {
                _reports.trySend(Report(staged = 0, failed = 1))
                return@withContext
            }
            add(
                listOf(
                    LocalItem(
                        uri = Uri.fromFile(target).toString(),
                        name = displayTextName(title),
                        sizeBytes = target.length(),
                        mimeType = "text/plain",
                    ),
                ),
            )
            _reports.trySend(Report(staged = 1, failed = 0))
        }
    }

    /**
     * File name for a shared text: the subject line (made safe) plus `.txt`.
     *
     * A subject that already names a file ("patch.md") is left as it is, so the
     * user does not get `patch.md.txt` on the server.
     */
    private fun displayTextName(title: String?): String {
        val base = RemotePaths.sanitizeName(title ?: "shared")
        val ext = base.substringAfterLast('.', "")
        val alreadyNamed = ext.isNotEmpty() && ext.length <= 5 && ext.all { it.isLetter() }
        return if (alreadyNamed) base else "$base.txt"
    }

    /** Say out loud that a share arrived but carried nothing this app can copy. */
    fun reportNothingToStage() {
        _reports.trySend(Report(staged = 0, failed = 0, nothingToStage = true))
    }

    /** Add files the user picked in-app to the same list a share feeds. */
    fun add(items: List<LocalItem>) {
        if (items.isEmpty()) return
        _incoming.update { existing ->
            val known = existing.map { it.uri }.toSet()
            existing + items.filter { it.uri !in known }
        }
    }

    /** Take one file back out of the list (the "x" on a row). */
    fun remove(uri: String) {
        _incoming.update { existing -> existing.filterNot { it.uri == uri } }
    }

    /** Take the whole batch out, once a send has queued it. */
    fun removeAll(uris: List<String>) {
        if (uris.isEmpty()) return
        val cut = uris.toSet()
        _incoming.update { existing -> existing.filterNot { it.uri in cut } }
    }

    private fun stageOne(context: Context, gateway: LocalFileGateway, uri: Uri): LocalItem? {
        val raw = runCatching { gateway.describe(uri.toString()) }
        val info = raw.getOrNull()
        // Keep the name the sender showed the user, made safe for a filesystem.
        val name = RemotePaths.sanitizeName(
            info?.name ?: uri.lastPathSegment ?: uri.pathSegments.lastOrNull() ?: "file",
        )
        val dir = File(context.cacheDir, STAGE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        // A per-share unique on-disk name: two apps can share "notes.txt" at the
        // same time, and the queue must be able to tell the two copies apart.
        val target = File(dir, "${counter.incrementAndGet()}-${System.currentTimeMillis()}-$name")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, BUFFER_BYTES) }
            } ?: throw IOException("no stream for $uri")
            LocalItem(
                uri = Uri.fromFile(target).toString(),
                name = name,
                sizeBytes = if (target.length() > 0L) target.length() else info?.sizeBytes ?: -1L,
                mimeType = info?.mimeType,
            )
        } catch (_: Exception) {
            runCatching { target.delete() }
            null
        }
    }

    /**
     * Drop staged copies at process start.
     *
     * A share grant cannot outlive the process, so anything still sitting in the
     * staging folder belongs to a copy that can never be read again - it is just
     * cache space the user will never see again.
     */
    fun pruneStaged(context: Context) {
        runCatching { File(context.cacheDir, STAGE_DIR).deleteRecursively() }
    }

    private const val BUFFER_BYTES = 64 * 1024
}
