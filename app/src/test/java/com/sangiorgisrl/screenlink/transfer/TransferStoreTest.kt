package com.sangiorgisrl.screenlink.transfer

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.data.ConnectionDao
import com.sangiorgisrl.screenlink.data.ConnectionEntity
import com.sangiorgisrl.screenlink.data.ConnectionKeyCrossRef
import com.sangiorgisrl.screenlink.data.ConnectionRepository
import com.sangiorgisrl.screenlink.data.SshConnection
import com.sangiorgisrl.screenlink.data.SshKeyCredential
import com.sangiorgisrl.screenlink.data.SshKeyDao
import com.sangiorgisrl.screenlink.data.SshKeyEntity
import com.sangiorgisrl.screenlink.i18n.UiText
import com.sangiorgisrl.screenlink.security.CryptoService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.yield
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The copy queue: the part of the feature the user cannot live without, and the
 * part that is impossible to check by tapping (a 2 GB file, a cancel at 40 %, a
 * server that says no half-way through).
 *
 * Everything real is kept: the real [ConnectionRepository] over in-memory DAOs,
 * the real counting streams and the real estimator. Only the two edges are faked
 * - the device folder (a map of bytes) and the server (an in-memory filesystem).
 * The fake transport pumps through [CountingInputStream]/[CountingOutputStream]
 * exactly like JSch does, INCLUDING re-wrapping our cancel signal in
 * `SftpException(SSH_FX_FAILURE)`, because that is the detail that would otherwise
 * make a cancelled copy look like a failed one in the UI.
 *
 * Timing is deterministic: the store's scope is a child of [runBlocking], so a
 * launched group does not start until the test suspends in [CoroutineScope.join].
 * That is what makes "cancel a job that is still queued" testable at all.
 */
class TransferStoreTest {

    // --- fakes ----------------------------------------------------------------

    private class FakeConnectionDao : ConnectionDao {
        private val rows = LinkedHashMap<Long, ConnectionEntity>()
        private val stream = MutableStateFlow(emptyList<ConnectionEntity>())

        override fun observeAll(): Flow<List<ConnectionEntity>> = stream
        override suspend fun getById(id: Long): ConnectionEntity? = rows[id]
        override suspend fun count(): Int = rows.size
        override suspend fun insert(connection: ConnectionEntity): Long {
            rows[connection.id] = connection
            stream.value = rows.values.toList()
            return connection.id
        }

        override suspend fun update(connection: ConnectionEntity): Int {
            rows[connection.id] = connection
            stream.value = rows.values.toList()
            return 1
        }

        override suspend fun delete(connection: ConnectionEntity) {
            rows.remove(connection.id)
            stream.value = rows.values.toList()
        }
    }

    private class FakeSshKeyDao : SshKeyDao {
        override fun observeAll(): Flow<List<SshKeyEntity>> = MutableStateFlow(emptyList())
        override suspend fun getById(id: Long): SshKeyEntity? = null
        override suspend fun insert(key: SshKeyEntity): Long = key.id
        override suspend fun delete(key: SshKeyEntity) = Unit
        override suspend fun getKeysForConnection(connectionId: Long): List<SshKeyEntity> = emptyList()
        override suspend fun getKeyIdsForConnection(connectionId: Long): List<Long> = emptyList()
        override suspend fun clearConnectionKeys(connectionId: Long) = Unit
        override suspend fun deleteConnectionKeysByKeyId(keyId: Long) = Unit
        override suspend fun insertCrossRef(ref: ConnectionKeyCrossRef) = Unit
    }

    private object FakeCrypto : CryptoService {
        override fun encrypt(plain: String): String = plain
        override fun decrypt(encoded: String): String = encoded
    }

    /** The device side: a picked folder plus the files the user chose to send. */
    private class FakeLocalFiles : LocalFileGateway {
        val contents = LinkedHashMap<String, ByteArray>()
        val children = LinkedHashMap<String, MutableList<String>>()
        val written = LinkedHashMap<String, ByteArrayOutputStream>()
        /** Device files the queue threw away because a copy did not finish. */
        val discarded = mutableListOf<String>()
        /** Read grants the queue handed back. */
        val released = mutableListOf<String>()

        override fun describe(uri: String): LocalItem? {
            val bytes = contents[uri] ?: return null
            return LocalItem(uri = uri, name = uri.substringAfterLast('/'), sizeBytes = bytes.size.toLong())
        }

        override fun source(uri: String): LocalSource {
            val bytes = contents[uri] ?: throw FileNotFoundException(uri)
            return object : LocalSource {
                override val sizeBytes: Long = bytes.size.toLong()
                override fun open(): InputStream = ByteArrayInputStream(bytes)
            }
        }

        override fun sink(dirUri: String, name: String, mimeType: String?): LocalSink {
            val key = "$dirUri/$name"
            return object : LocalSink {
                override val targetName: String = name
                /** Mirrors the real gateway: only what THIS sink created may be deleted. */
                private var created = false
                override fun open(): OutputStream {
                    val buffer = ByteArrayOutputStream()
                    written[key] = buffer
                    created = true
                    return buffer
                }

                override fun discard() {
                    if (!created) return
                    created = false
                    discarded += key
                    written.remove(key)
                }
            }
        }

        override fun childNames(dirUri: String): List<String> = children[dirUri].orEmpty()
        override fun folderDisplayName(dirUri: String): String = dirUri.substringAfterLast('/')

        override fun releaseReadGrant(uri: String) {
            released += uri
        }
    }

    /** The server side: folders, files, and the copy that is currently landing. */
    private class RemoteFs {
        val dirs = LinkedHashMap<String, MutableList<RemoteEntry>>()
        val files = LinkedHashMap<String, ByteArray>()
        val uploads = LinkedHashMap<String, ByteArray>()
        val madeDirs = mutableListOf<String>()
        /** Remote files the queue removed because a copy did not finish. */
        val deleted = mutableListOf<String>()
        var closes = 0
        /** Remote paths whose copy must fail, with the error to throw. */
        val failures = LinkedHashMap<String, Throwable>()
        /** Number of upcoming failures for ANY path (used to fail only the first attempt). */
        var failNext = 0
        /**
         * Upcoming copies that die the way a dead link dies: a codeless SFTP
         * failure wrapping the JDK's pipe error. This is the "pipe is closed" the
         * user reports, and the queue must reconnect and run the copy again.
         */
        var failNextDeadLink = 0
        /**
         * Copies that die the same way but AFTER bytes started flowing, which is how
         * a Wi-Fi sleep really interrupts a copy: half the file is already on its
         * way. This is what exercises the cleanup of a partial file together with
         * the retry, which a failure thrown before the copy starts cannot show.
         */
        var failStreamDeadLink = 0
        /** A server that will not answer "is this name taken?" (the honest failure). */
        var refusesStat = false

        fun addDir(path: String, vararg names: String) {
            dirs[path] = names.map { RemoteEntry(name = it, path = "$path/$it", isDirectory = false) }
                .toMutableList()
        }
    }

    private class FakeTransport(
        private val fs: RemoteFs,
        private val chunkSize: Int,
        private val onChunk: (Long) -> Unit,
    ) : TransferTransport {

        /** False once the link under this session died (see [deadLink]). */
        var alive = true

        /** What JSch throws when the session dies under a call: a codeless failure wrapping a pipe error. */
        private fun deadLink(): Throwable = SftpException(
            ChannelSftp.SSH_FX_FAILURE,
            "java.io.IOException: Pipe closed",
            java.io.IOException("Pipe closed"),
        )

        override fun isAlive(): Boolean = alive

        private fun failIfAny(path: String) {
            // A session the queue kept past its own death answers with a pipe error,
            // which is exactly what the liveness check is there to avoid.
            if (!alive) throw deadLink()
            fs.failures[path]?.let { throw it }
            if (fs.failNextDeadLink > 0) {
                fs.failNextDeadLink -= 1
                throw deadLink()
            }
            if (fs.failNext > 0) {
                fs.failNext -= 1
                throw SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "Permission denied")
            }
        }

        /** The link dying between two chunks (see [RemoteFs.failStreamDeadLink]). */
        private fun failMidStream() {
            if (fs.failStreamDeadLink > 0) {
                fs.failStreamDeadLink -= 1
                throw deadLink()
            }
        }

        /** JSch rethrows anything our pump throws as a generic SFTP failure. */
        private fun jschWrap(e: TransferCancelledException): Throwable =
            SftpException(ChannelSftp.SSH_FX_FAILURE, e.toString(), e)

        override fun home(): String = "/home/ops"

        override fun list(path: String): List<RemoteEntry> = fs.dirs[path].orEmpty()

        /**
         * The real transport asks the server about the exact path (a stat) instead
         * of trusting a listing, so the fake answers from everything it holds:
         * committed uploads, staged remote files, and the folder listing.
         */
        override fun exists(path: String): Boolean {
            if (fs.refusesStat) throw SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "stat refused")
            if (fs.uploads.containsKey(path) || fs.files.containsKey(path)) return true
            val parent = RemotePaths.parent(path) ?: return false
            val leaf = RemotePaths.leafName(path)
            return fs.dirs[parent].orEmpty().any { it.name.equals(leaf, ignoreCase = true) }
        }

        override fun delete(path: String) {
            fs.deleted += path
            fs.uploads.remove(path)
        }

        override fun mkdir(path: String) {
            fs.madeDirs += path
        }

        override fun upload(source: LocalSource, remotePath: String, progress: ProgressListener) {
            failIfAny(remotePath)
            try {
                source.open().use { raw ->
                    val counting = CountingInputStream(raw, progress)
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(chunkSize)
                    while (true) {
                        val n = counting.read(buffer, 0, buffer.size)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        onChunk(counting.transferred)
                        failMidStream()
                    }
                    fs.uploads[remotePath] = out.toByteArray()
                }
            } catch (e: TransferCancelledException) {
                throw jschWrap(e)
            }
        }

        override fun download(remotePath: String, sink: LocalSink, progress: ProgressListener) {
            failIfAny(remotePath)
            val bytes = fs.files[remotePath]
                ?: throw SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "no such file")
            try {
                sink.open().use { raw ->
                    val counting = CountingOutputStream(raw, progress)
                    var offset = 0
                    while (offset < bytes.size) {
                        val n = minOf(chunkSize, bytes.size - offset)
                        counting.write(bytes, offset, n)
                        offset += n
                        onChunk(counting.transferred)
                        failMidStream()
                    }
                }
            } catch (e: TransferCancelledException) {
                throw jschWrap(e)
            }
        }

        override fun close() {
            fs.closes += 1
        }
    }

    /** Wires the store to the fakes; one per test. */
    private inner class Ctx(
        private val withConnection: Boolean = true,
        private val chunkSize: Int = 1_024,
    ) {
        var now = 0L
        val local = FakeLocalFiles()
        val fs = RemoteFs()
        var opens = 0
        /** Every session the queue dialled, in order (so a test can kill one). */
        val transports = mutableListOf<FakeTransport>()
        /**
         * What the row was showing at each handshake: its `note` is how a test can
         * tell a silent reconnect (nothing shown) from a retry after a failed copy
         * ("reconnecting"), which the finished row cannot tell apart on its own.
         */
        val notesAtOpen = mutableListOf<UiText?>()
        /** Bytes on the row at each handshake: a retry must send its bar back to zero. */
        val bytesAtOpen = mutableListOf<Long>()
        /** How many times the queue asked to be held up by a foreground service. */
        var begins = 0
        /** Sessions open at the same instant, and the high-water mark of that. */
        var concurrentOpens = 0
        var maxConcurrentOpens = 0
        /** Makes the handshake suspend, so overlapping batches can be observed. */
        var yieldOnOpen = false
        private var running: TransferStore? = null

        /**
         * Suspend until every copy queued so far has settled.
         *
         * Waiting on the scope's Job is not an option: a hand-made Job never
         * completes on its own, so joining it would hang. Yielding hands the
         * (single) thread to the queue, which is exactly what a queued group needs
         * to start - and it keeps "cancel a job that has not started" testable.
         */
        suspend fun settle() {
            val store = running ?: return
            while (store.jobs.value.any {
                    it.status == TransferStatus.Queued || it.status == TransferStatus.Running
                }
            ) {
                yield()
            }
        }

        /** Runs after every copied chunk: the test snapshots or cancels mid-transfer here. */
        var onChunk: (() -> Unit)? = null
        /** What the UI could see after each chunk (sampled from the store itself). */
        val published = mutableListOf<Long>()
        private var storeRef: TransferStore? = null

        val repository = ConnectionRepository(FakeConnectionDao(), FakeSshKeyDao(), FakeCrypto)

        suspend fun prepare() {
            if (withConnection) {
                repository.save(
                    id = CONNECTION_ID,
                    displayName = "Lab server",
                    username = "ops",
                    host = "lab.example",
                    port = 22,
                    passwordPlain = "hunter2",
                    screenSessionName = null,
                )
            }
        }

        fun store(scope: CoroutineScope): TransferStore {
            val factory = object : TransferTransportFactory {
                override suspend fun open(
                    connection: SshConnection,
                    keys: List<SshKeyCredential>,
                ): TransferTransport {
                    // The queue must hand over the decrypted credentials of the picked row.
                    assertEquals("hunter2", connection.password)
                    assertEquals("lab.example", connection.host)
                    opens += 1
                    // Snapshot the row this handshake is FOR before dialling: its note
                    // tells a test whether the queue reconnected on its own (nothing
                    // shown) or after a copy had already failed ("reconnecting"), and
                    // its counter shows whether the retry really started from zero.
                    storeRef?.jobs?.value?.firstOrNull { it.status == TransferStatus.Running }?.let {
                        notesAtOpen += it.note
                        bytesAtOpen += it.transferredBytes
                    }
                    concurrentOpens += 1
                    maxConcurrentOpens = maxOf(maxConcurrentOpens, concurrentOpens)
                    if (yieldOnOpen) yield()
                    concurrentOpens -= 1
                    val opened = FakeTransport(fs, chunkSize) { _ ->
                        onChunk?.invoke()
                        // Read the queue's own state, not the transport's counter:
                        // what is being tested is how much of the copy the user sees.
                        storeRef?.jobs?.value?.firstOrNull()
                            ?.let { published += it.transferredBytes }
                    }
                    transports += opened
                    return opened
                }
            }
            return TransferStore(
                repository,
                local,
                factory,
                scope,
                // The queue must ask for the process to be held up the moment work
                // is queued, or a copy of a 2 GB file is reclaimable mid-stream.
                foreground = TransferForeground { begins += 1 },
                clock = { now },
            )
                .also {
                    storeRef = it
                    running = it
                }
        }

        fun job(store: TransferStore, id: String): TransferStore.Job =
            store.jobs.value.first { it.id == id }
    }

    /** Runs [body] with the store's scope as a child, then waits for every copy. */
    private fun transfer(
        withConnection: Boolean = true,
        chunkSize: Int = 1_024,
        body: suspend (Ctx, CoroutineScope) -> Unit,
    ) = runBlocking {
        val ctx = Ctx(withConnection, chunkSize)
        ctx.prepare()
        coroutineScope {
            body(ctx, CoroutineScope(coroutineContext + SupervisorJob()))
            ctx.settle()
        }
    }

    // --- uploads --------------------------------------------------------------

    @Test
    fun `a shared file is copied into the chosen remote folder`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://notes.txt"] = ByteArray(3_000) { 7 }
        ctx.fs.addDir(REMOTE_DIR)
        ctx.onChunk = { ctx.now += 1_000L }

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://notes.txt", name = "notes.txt", sizeBytes = 3_000L),
        ))

        ctx.settle()

        val job = ctx.job(store, "g1-1")
        assertEquals(TransferStatus.Completed, job.status)
        assertEquals("$REMOTE_DIR/notes.txt", job.remotePath)
        assertEquals(3_000L, job.totalBytes)
        assertEquals(3_000L, job.transferredBytes)
        assertEquals("Lab server", job.connectionName)
        // The bytes really arrived, intact.
        assertEquals(3_000, ctx.fs.uploads.getValue("$REMOTE_DIR/notes.txt").size)
        assertEquals(1, ctx.opens)
        // A finished row can report an average speed, and has no time left.
        assertTrue(job.bytesPerSecond > 0.0)
        assertEquals(0.0, job.secondsLeft!!)
        assertNull(job.error)
    }

    @Test
    fun `a name that already exists on the server is never overwritten`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://notes.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR, "notes.txt")

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://notes.txt", name = "notes.txt", sizeBytes = 10L),
        ))

        ctx.settle()

        val job = ctx.job(store, "g1-1")
        assertEquals(TransferStatus.Completed, job.status)
        assertEquals("$REMOTE_DIR/notes (1).txt", job.remotePath)
        assertTrue(ctx.fs.uploads.containsKey("$REMOTE_DIR/notes (1).txt"))
    }

    @Test
    fun `a hostile display name cannot escape the folder the user picked`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://x"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://x", name = "../../etc/passwd", sizeBytes = 10L),
        ))

        ctx.settle()

        // The queue row shows the safe name, and the path stays inside the folder.
        assertEquals("passwd", ctx.job(store, "g1-1").fileName)
        assertEquals("$REMOTE_DIR/passwd", ctx.job(store, "g1-1").remotePath)
    }

    @Test
    fun `a file whose size was unknown still gets a real progress total`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://mystery.bin"] = ByteArray(2_048)
        ctx.fs.addDir(REMOTE_DIR)

        // A share sheet that refuses to report a size: -1 means "indeterminate".
        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://mystery.bin", name = "mystery.bin", sizeBytes = -1L),
        ))

        ctx.settle()

        val job = ctx.job(store, "g1-1")
        assertEquals(TransferStatus.Completed, job.status)
        // Re-read from the source once the copy actually opened the file.
        assertEquals(2_048L, job.totalBytes)
        assertEquals(2_048L, job.transferredBytes)
    }

    // --- downloads ------------------------------------------------------------

    @Test
    fun `a remote file lands in the one device folder the user picked`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.fs.files["$REMOTE_DIR/plan.pdf"] = ByteArray(1_500) { 3 }

        store.enqueueDownload(CONNECTION_ID, "Lab server", DEVICE_DIR, listOf(
            RemoteEntry(name = "plan.pdf", path = "$REMOTE_DIR/plan.pdf", isDirectory = false, sizeBytes = 1_500L),
        ))

        ctx.settle()

        val job = ctx.job(store, "g1-1")
        assertEquals(TransferStatus.Completed, job.status)
        assertEquals(1_500L, job.transferredBytes)
        assertEquals(1_500, ctx.local.written.getValue("$DEVICE_DIR/plan.pdf").size())
    }

    @Test
    fun `a download never replaces a file already in the device folder`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.fs.files["$REMOTE_DIR/plan.pdf"] = ByteArray(500)
        ctx.local.children[DEVICE_DIR] = mutableListOf("plan.pdf")

        store.enqueueDownload(CONNECTION_ID, "Lab server", DEVICE_DIR, listOf(
            RemoteEntry(name = "plan.pdf", path = "$REMOTE_DIR/plan.pdf", isDirectory = false, sizeBytes = 500L),
        ))

        ctx.settle()

        // The row renames itself, because that is the file the user will find.
        assertEquals("plan (1).pdf", ctx.job(store, "g1-1").fileName)
        assertEquals(500, ctx.local.written.getValue("$DEVICE_DIR/plan (1).pdf").size())
    }

    @Test
    fun `a download without a device folder fails instead of writing nowhere`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.fs.files["$REMOTE_DIR/plan.pdf"] = ByteArray(10)

        store.enqueueDownload(CONNECTION_ID, "Lab server", "", listOf(
            RemoteEntry(name = "plan.pdf", path = "$REMOTE_DIR/plan.pdf", isDirectory = false, sizeBytes = 10L),
        ))

        ctx.settle()

        val job = ctx.job(store, "g1-1")
        assertEquals(TransferStatus.Failed, job.status)
        // The user is told what is missing, in words they can act on.
        assertEquals(UiText.Resource(R.string.files_snack_no_folder), job.error)
        assertTrue(ctx.local.written.isEmpty())
    }

    // --- progress -------------------------------------------------------------

    @Test
    fun `progress is published often enough to look alive but not per buffer`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://big.bin"] = ByteArray(4_096)
        ctx.fs.addDir(REMOTE_DIR)
        // Four chunks, and the clock only moves when the test says so.
        ctx.onChunk = { ctx.now += TransferStore.PUBLISH_INTERVAL_MS }

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://big.bin", name = "big.bin", sizeBytes = 4_096L),
        ))

        ctx.settle()

        // Every chunk reached the UI: the bar advances, it does not jump at the end.
        assertEquals(listOf(1_024L, 2_048L, 3_072L, 4_096L), ctx.published)
        val running = ctx.job(store, "g1-1")
        assertEquals(4_096L, running.transferredBytes)
        assertTrue(running.bytesPerSecond > 0.0)
    }

    @Test
    fun `a frozen clock still publishes the first sample then throttles`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://big.bin"] = ByteArray(4_096)
        ctx.fs.addDir(REMOTE_DIR)

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://big.bin", name = "big.bin", sizeBytes = 4_096L),
        ))

        ctx.settle()

        // Four chunks inside one throttle window: only the first may reach the UI,
        // otherwise a fast copy would repaint tens of times a second.
        assertEquals(4, ctx.published.size)
        assertEquals(listOf(1_024L), ctx.published.distinct())
        // The completed row still ends at the true total.
        assertEquals(4_096L, ctx.job(store, "g1-1").transferredBytes)
    }

    // --- cancelling -----------------------------------------------------------

    @Test
    fun `cancelling a job that has not started settles it immediately`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://one.txt"] = ByteArray(10)
        ctx.local.contents["file://two.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://one.txt", name = "one.txt", sizeBytes = 10L),
            LocalItem(uri = "file://two.txt", name = "two.txt", sizeBytes = 10L),
        ))
        // Nothing has been awaited yet, so the group has not started: this is the
        // "user hit cancel while it was still waiting" case.
        store.cancel("g1-2")
        ctx.settle()

        assertEquals(TransferStatus.Cancelled, ctx.job(store, "g1-2").status)
        assertEquals(TransferStatus.Completed, ctx.job(store, "g1-1").status)
        // The cancelled file was never even opened on the server.
        assertEquals(listOf("$REMOTE_DIR/one.txt"), ctx.fs.uploads.keys.toList())
    }

    @Test
    fun `cancelling mid-copy is a cancelled transfer, not a failed one`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://big.bin"] = ByteArray(100_000)
        ctx.fs.addDir(REMOTE_DIR)
        // The user presses cancel while the first chunk is being written. The fake
        // re-wraps the signal exactly like JSch, which is what used to mislabel it.
        ctx.onChunk = { store.cancel("g1-1") }

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://big.bin", name = "big.bin", sizeBytes = 100_000L),
        ))

        ctx.settle()

        val job = ctx.job(store, "g1-1")
        assertEquals(TransferStatus.Cancelled, job.status)
        assertNull(job.error)
        // Only the bytes that really moved are shown, and nothing was committed.
        assertEquals(1_024L, job.transferredBytes)
        assertTrue(ctx.fs.uploads.isEmpty())
    }

    @Test
    fun `a cancelled copy reopens the session for the next file`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://big.bin"] = ByteArray(10_000)
        ctx.local.contents["file://small.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)
        ctx.onChunk = { store.cancel("g1-1") }

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://big.bin", name = "big.bin", sizeBytes = 10_000L),
            LocalItem(uri = "file://small.txt", name = "small.txt", sizeBytes = 10L),
        ))

        ctx.settle()

        // Half-written handles are never reused: the second file gets a fresh session.
        assertEquals(TransferStatus.Cancelled, ctx.job(store, "g1-1").status)
        assertEquals(TransferStatus.Completed, ctx.job(store, "g1-2").status)
        assertEquals(2, ctx.opens)
        assertTrue(ctx.fs.closes >= 1)
    }

    @Test
    fun `cancelling the whole batch stops everything that has not run`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://a.txt"] = ByteArray(10)
        ctx.local.contents["file://b.txt"] = ByteArray(10)
        ctx.local.contents["file://c.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)

        val group = store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://a.txt", name = "a.txt", sizeBytes = 10L),
            LocalItem(uri = "file://b.txt", name = "b.txt", sizeBytes = 10L),
            LocalItem(uri = "file://c.txt", name = "c.txt", sizeBytes = 10L),
        ))
        store.cancelGroup(group)
        ctx.settle()

        assertEquals(listOf(TransferStatus.Cancelled, TransferStatus.Cancelled, TransferStatus.Cancelled),
            store.jobs.value.map { it.status })
        assertEquals(0, ctx.opens)
    }

    // --- failures -------------------------------------------------------------

    @Test
    fun `a refused upload shows the reason and the batch carries on`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://one.txt"] = ByteArray(10)
        ctx.local.contents["file://two.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)
        ctx.fs.failures["$REMOTE_DIR/one.txt"] =
            SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "Permission denied")

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://one.txt", name = "one.txt", sizeBytes = 10L),
            LocalItem(uri = "file://two.txt", name = "two.txt", sizeBytes = 10L),
        ))

        ctx.settle()

        val failed = ctx.job(store, "g1-1")
        assertEquals(TransferStatus.Failed, failed.status)
        // The user is told what to do, not "SSH_FX_FAILURE: 4".
        assertEquals(UiText.Resource(R.string.err_transfer_permission), failed.error)
        // One bad file must not abandon the rest of the selection.
        assertEquals(TransferStatus.Completed, ctx.job(store, "g1-2").status)
        // The broken session is discarded, so the next file is not sent down it.
        assertEquals(2, ctx.opens)
    }

    // --- the link that died under the copy (what users report as "pipe is closed") --

    @Test
    fun `a copy whose link died is run again on a fresh session and finishes`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://one.txt"] = ByteArray(10) { 4 }
        ctx.fs.addDir(REMOTE_DIR)
        ctx.fs.failNextDeadLink = 1

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://one.txt", name = "one.txt", sizeBytes = 10L),
        ))

        ctx.settle()

        // A phone whose Wi-Fi slept is not the user's mistake, and "Pipe closed" is
        // not an answer: the queue reconnects and carries on, exactly as they would.
        val job = ctx.job(store, "g1-1")
        assertEquals(TransferStatus.Completed, job.status)
        assertEquals(2, ctx.opens, "the dead link was never retried")
        assertEquals(10, ctx.fs.uploads.getValue("$REMOTE_DIR/one.txt").size)
        // The second handshake was dialled while the row was saying WHY its bar had
        // gone back to zero, and that bar really was at zero.
        assertEquals(listOf(null, UiText.Resource(R.string.files_status_reconnecting)), ctx.notesAtOpen)
        assertEquals(listOf(0L, 0L), ctx.bytesAtOpen)
        // The note is gone once the bytes proved the copy was moving again.
        assertNull(job.note)
    }

    @Test
    fun `a link that dies twice fails with the honest sentence and stops dialling`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://one.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)
        ctx.fs.failures["$REMOTE_DIR/one.txt"] = SftpException(
            ChannelSftp.SSH_FX_FAILURE,
            "java.io.IOException: Pipe closed",
            java.io.IOException("Pipe closed"),
        )

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://one.txt", name = "one.txt", sizeBytes = 10L),
        ))

        ctx.settle()

        val job = ctx.job(store, "g1-1")
        assertEquals(TransferStatus.Failed, job.status)
        // The library's sentence ("Pipe closed") names a part of the app nobody can
        // act on; the network the user can go and check is what the row says.
        assertEquals(UiText.Resource(R.string.err_transfer_connection_lost), job.error)
        assertNull(job.note)
        // ONE retry. A server that is down is down, and a retry loop turns one
        // outage into a hundred handshakes.
        assertEquals(2, ctx.opens)
    }

    @Test
    fun `a link that dies mid-file cleans up its partial copy before dialling again`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://big.bin"] = ByteArray(4_096)
        ctx.fs.addDir(REMOTE_DIR)
        // Every attempt dies with bytes already moving: the realistic outage, and the
        // one where a truncated file can be left on the server AND a second copy run
        // over it.
        ctx.fs.failStreamDeadLink = 5

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://big.bin", name = "big.bin", sizeBytes = 4_096L),
        ))

        ctx.settle()

        val job = ctx.job(store, "g1-1")
        assertEquals(TransferStatus.Failed, job.status)
        assertEquals(UiText.Resource(R.string.err_transfer_connection_lost), job.error)
        assertEquals(2, ctx.opens)
        // Each attempt cleaned up after ITSELF: the retry never writes on top of the
        // previous attempt's half file, and the row ends with nothing half-written
        // left on the server.
        assertEquals(listOf("$REMOTE_DIR/big.bin", "$REMOTE_DIR/big.bin"), ctx.fs.deleted)
        assertTrue(ctx.fs.uploads.isEmpty())
        // The retry started from byte zero, so the bar never lied about progress it
        // had not made on the new connection.
        assertEquals(listOf(0L, 0L), ctx.bytesAtOpen)
    }

    @Test
    fun `a download whose link dies mid-file leaves the picked folder clean`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.fs.files["$REMOTE_DIR/plan.pdf"] = ByteArray(4_096)
        ctx.fs.failStreamDeadLink = 5

        store.enqueueDownload(CONNECTION_ID, "Lab server", DEVICE_DIR, listOf(
            RemoteEntry(name = "plan.pdf", path = "$REMOTE_DIR/plan.pdf", isDirectory = false, sizeBytes = 4_096L),
        ))

        ctx.settle()

        val job = ctx.job(store, "g1-1")
        assertEquals(TransferStatus.Failed, job.status)
        assertEquals(UiText.Resource(R.string.err_transfer_connection_lost), job.error)
        assertEquals(2, ctx.opens)
        // Both attempts threw their partial file away: the folder the user picked
        // ends up with nothing in it rather than a "plan.pdf" that is half a PDF.
        assertEquals(listOf("$DEVICE_DIR/plan.pdf", "$DEVICE_DIR/plan.pdf"), ctx.local.discarded)
        assertTrue(ctx.local.written.isEmpty())
    }

    @Test
    fun `a copy the server refused is never dialled a second time`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://one.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)
        ctx.fs.failNext = 1 // "Permission denied": an ANSWER, and it will be the same twice

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://one.txt", name = "one.txt", sizeBytes = 10L),
        ))

        ctx.settle()

        assertEquals(TransferStatus.Failed, ctx.job(store, "g1-1").status)
        assertEquals(1, ctx.opens, "a refused copy was retried")
        assertTrue(ctx.notesAtOpen.all { it == null }, "a refusal showed a reconnect")
    }

    @Test
    fun `a session that went stale between two files is replaced before the next copy`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://a.txt"] = ByteArray(10)
        ctx.local.contents["file://b.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)
        // The link dies the moment the first file lands: a long batch is exactly
        // where an idle handshake goes stale.
        ctx.onChunk = { ctx.transports.last().alive = false }

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://a.txt", name = "a.txt", sizeBytes = 10L),
            LocalItem(uri = "file://b.txt", name = "b.txt", sizeBytes = 10L),
        ))

        ctx.settle()

        assertEquals(TransferStatus.Completed, ctx.job(store, "g1-1").status)
        assertEquals(TransferStatus.Completed, ctx.job(store, "g1-2").status)
        assertEquals(2, ctx.opens)
        // The queue asked the session whether it was still there BEFORE copying, so
        // no copy ever failed and no "reconnecting" was ever shown: the user just
        // watched two files land.
        assertEquals(listOf(null, null), ctx.notesAtOpen)
    }

    @Test
    fun `a download whose link died is restarted and leaves no half file behind`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.fs.files["$REMOTE_DIR/plan.pdf"] = ByteArray(10) { 6 }
        // The link goes while bytes are already moving, which is the case that can
        // leave junk in the folder the user picked: the aborted attempt had already
        // opened the file.
        ctx.fs.failStreamDeadLink = 1

        store.enqueueDownload(CONNECTION_ID, "Lab server", DEVICE_DIR, listOf(
            RemoteEntry(name = "plan.pdf", path = "$REMOTE_DIR/plan.pdf", isDirectory = false, sizeBytes = 10L),
        ))

        ctx.settle()

        val job = ctx.job(store, "g1-1")
        assertEquals(TransferStatus.Completed, job.status)
        assertEquals(2, ctx.opens)
        // The aborted attempt was thrown away, not left in the folder the user picked,
        // and the retry wrote the file once.
        assertEquals(listOf("$DEVICE_DIR/plan.pdf"), ctx.local.discarded)
        assertEquals(10, ctx.local.written.getValue("$DEVICE_DIR/plan.pdf").size())
        // The row explained why its bar went back to zero, and really was at zero.
        assertEquals(listOf(null, UiText.Resource(R.string.files_status_reconnecting)), ctx.notesAtOpen)
        assertEquals(listOf(0L, 0L), ctx.bytesAtOpen)
        assertNull(job.note)
    }

    @Test
    fun `a deleted connection fails the batch without dialling out`() = transfer(withConnection = false) { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://one.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://one.txt", name = "one.txt", sizeBytes = 10L),
        ))

        ctx.settle()

        val job = ctx.job(store, "g1-1")
        assertEquals(TransferStatus.Failed, job.status)
        assertEquals(UiText.Resource(R.string.err_connection_missing), job.error)
        assertEquals(0, ctx.opens)
    }

    @Test
    fun `a failed copy can be retried as a new row`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://one.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)
        ctx.fs.failNext = 1

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://one.txt", name = "one.txt", sizeBytes = 10L),
        ))

        ctx.settle()
        assertEquals(TransferStatus.Failed, ctx.job(store, "g1-1").status)

        val newGroup = store.retry("g1-1")
        assertEquals("g2", newGroup)
        ctx.settle()

        // The old row stays (the user can still see what happened) and the new one runs.
        assertEquals(TransferStatus.Failed, ctx.job(store, "g1-1").status)
        assertEquals(TransferStatus.Completed, ctx.job(store, "g2-1").status)
        assertEquals("$REMOTE_DIR/one.txt", ctx.job(store, "g2-1").remotePath)
    }

    @Test
    fun `clearing finished rows keeps the ones still running`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://one.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://one.txt", name = "one.txt", sizeBytes = 10L),
        ))
        // Nothing has started yet, so this row must survive the sweep.
        store.clearFinished()
        assertEquals(1, store.jobs.value.size)
        assertEquals(TransferStatus.Queued, ctx.job(store, "g1-1").status)
    }

    @Test
    fun `clearing finished rows drops completed and failed ones`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://one.txt"] = ByteArray(10)
        ctx.local.contents["file://two.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)
        ctx.fs.failures["$REMOTE_DIR/two.txt"] =
            SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "no such file")

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://one.txt", name = "one.txt", sizeBytes = 10L),
            LocalItem(uri = "file://two.txt", name = "two.txt", sizeBytes = 10L),
        ))

        ctx.settle()
        store.clearFinished()

        assertTrue(store.jobs.value.isEmpty())
    }

    // --- never destroy what the user already has -------------------------------

    @Test
    fun `a name the listing does not show is still dodged when the server knows it`() = transfer() { ctx, scope ->
        // The collision check is a stat of the exact path, not a scan of the
        // listing: a huge folder, a refused LIST, or a file created after the
        // listing are all cases where a listing-only check would overwrite.
        val store = ctx.store(scope)
        ctx.local.contents["file://notes.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR) // the listing is empty
        ctx.fs.files["$REMOTE_DIR/notes.txt"] = ByteArray(99) // but the file IS there

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://notes.txt", name = "notes.txt", sizeBytes = 10L),
        ))

        ctx.settle()

        assertEquals(TransferStatus.Completed, ctx.job(store, "g1-1").status)
        assertEquals("$REMOTE_DIR/notes (1).txt", ctx.job(store, "g1-1").remotePath)
        // The user's file was never touched.
        assertEquals(99, ctx.fs.files.getValue("$REMOTE_DIR/notes.txt").size)
    }

    @Test
    fun `a server that will not say whether a name is taken refuses the copy`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://notes.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)
        ctx.fs.refusesStat = true

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://notes.txt", name = "notes.txt", sizeBytes = 10L),
        ))

        ctx.settle()

        // Guessing "free" is exactly how an existing file gets replaced, so the
        // honest answer is a failed row that says why.
        assertEquals(TransferStatus.Failed, ctx.job(store, "g1-1").status)
        assertEquals(UiText.Resource(R.string.err_transfer_permission), ctx.job(store, "g1-1").error)
        assertTrue(ctx.fs.uploads.isEmpty())
    }

    @Test
    fun `a failed upload removes the half-written remote file`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://big.bin"] = ByteArray(4_096)
        ctx.fs.addDir(REMOTE_DIR)
        // The server dies after the first chunk: this name was proved free before
        // the copy started, so whatever sits at it now is our truncated copy.
        // A refusal rather than a dead link, so this row tests the cleanup alone and
        // is not quietly also testing the retry.
        ctx.onChunk = { throw SftpException(ChannelSftp.SSH_FX_FAILURE, "Disk quota exceeded") }

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://big.bin", name = "big.bin", sizeBytes = 4_096L),
        ))

        ctx.settle()

        assertEquals(TransferStatus.Failed, ctx.job(store, "g1-1").status)
        assertEquals(listOf("$REMOTE_DIR/big.bin"), ctx.fs.deleted)
        assertEquals(1, ctx.opens, "a server refusal was dialled twice")
        // Nothing was committed, and no half file is left behind for the user to
        // mistake for a real copy.
        assertTrue(ctx.fs.uploads.isEmpty())
    }

    @Test
    fun `a cancelled download does not leave half a file in the picked folder`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.fs.files["$REMOTE_DIR/plan.pdf"] = ByteArray(100_000) { 5 }
        ctx.onChunk = { store.cancel("g1-1") }

        store.enqueueDownload(CONNECTION_ID, "Lab server", DEVICE_DIR, listOf(
            RemoteEntry(name = "plan.pdf", path = "$REMOTE_DIR/plan.pdf", isDirectory = false, sizeBytes = 100_000L),
        ))

        ctx.settle()

        assertEquals(TransferStatus.Cancelled, ctx.job(store, "g1-1").status)
        // The row says cancelled, and the device folder really does have nothing in it.
        assertEquals(listOf("$DEVICE_DIR/plan.pdf"), ctx.local.discarded)
        assertTrue(ctx.local.written.isEmpty())
    }

    @Test
    fun `a failed download throws away the partial file too`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.fs.files["$REMOTE_DIR/plan.pdf"] = ByteArray(4_096)
        // Same as the upload case: a refusal, so the retry path stays out of it.
        ctx.onChunk = { throw SftpException(ChannelSftp.SSH_FX_FAILURE, "Disk quota exceeded") }

        store.enqueueDownload(CONNECTION_ID, "Lab server", DEVICE_DIR, listOf(
            RemoteEntry(name = "plan.pdf", path = "$REMOTE_DIR/plan.pdf", isDirectory = false, sizeBytes = 4_096L),
        ))

        ctx.settle()

        assertEquals(TransferStatus.Failed, ctx.job(store, "g1-1").status)
        assertEquals(listOf("$DEVICE_DIR/plan.pdf"), ctx.local.discarded)
        assertEquals(1, ctx.opens, "a server refusal was dialled twice")
        assertTrue(ctx.local.written.isEmpty())
    }

    @Test
    fun `a retried file does not stack collision suffixes`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://one.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR, "one.txt")

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://one.txt", name = "one.txt", sizeBytes = 10L),
        ))
        ctx.settle()
        assertEquals("$REMOTE_DIR/one (1).txt", ctx.job(store, "g1-1").remotePath)

        store.retry("g1-1")
        ctx.settle()

        // Retrying starts from the name the USER asked for, so three retries of the
        // same file never end up as "one (1) (1) (1).txt".
        val retried = ctx.job(store, "g2-1")
        assertEquals("one.txt", retried.desiredName)
        assertEquals("$REMOTE_DIR/one (2).txt", retried.remotePath)
        assertEquals("one (2).txt", retried.fileName)
        assertTrue(ctx.fs.uploads.keys.none { it.contains("(1) (1)") })
    }

    // --- the queue as a whole --------------------------------------------------

    @Test
    fun `stop all settles every row the queue still owns`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://a.txt"] = ByteArray(10)
        ctx.local.contents["file://b.txt"] = ByteArray(10)
        ctx.local.contents["file://c.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://a.txt", name = "a.txt", sizeBytes = 10L),
            LocalItem(uri = "file://b.txt", name = "b.txt", sizeBytes = 10L),
            LocalItem(uri = "file://c.txt", name = "c.txt", sizeBytes = 10L),
        ))
        store.cancelAll()
        ctx.settle()

        assertEquals(
            listOf(TransferStatus.Cancelled, TransferStatus.Cancelled, TransferStatus.Cancelled),
            store.jobs.value.map { it.status },
        )
        assertEquals(0, ctx.opens)
    }

    @Test
    fun `stop all also stops the copy that is already running`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://big.bin"] = ByteArray(100_000)
        ctx.local.contents["file://next.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)
        ctx.onChunk = { store.cancelAll() }

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://big.bin", name = "big.bin", sizeBytes = 100_000L),
            LocalItem(uri = "file://next.txt", name = "next.txt", sizeBytes = 10L),
        ))

        ctx.settle()

        // The running row stops within one buffer, and the batch does not carry on
        // with files the user has just said they do not want.
        assertEquals(TransferStatus.Cancelled, ctx.job(store, "g1-1").status)
        assertEquals(TransferStatus.Cancelled, ctx.job(store, "g1-2").status)
        assertTrue(ctx.fs.uploads.isEmpty())
    }

    @Test
    fun `a queue that dies mid-batch settles the rows it never ran`() = transfer() { ctx, scope ->
        // The process is going away: the scope that owns the queue is cancelled
        // while one copy is mid-flight and another is still waiting for the
        // connection's lock.
        val queueJob = Job()
        val store = ctx.store(CoroutineScope(scope.coroutineContext + queueJob))
        ctx.local.contents["file://one.txt"] = ByteArray(2_048)
        ctx.local.contents["file://two.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)
        ctx.yieldOnOpen = true
        ctx.onChunk = { queueJob.cancel() }

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://one.txt", name = "one.txt", sizeBytes = 2_048L),
        ))
        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://two.txt", name = "two.txt", sizeBytes = 10L),
        ))

        ctx.settle()

        // A row that keeps saying "waiting" forever is the one thing a progress list
        // must never do; and it is cancelled, not failed - nothing went wrong.
        assertEquals(TransferStatus.Completed, ctx.job(store, "g1-1").status)
        val orphan = ctx.job(store, "g2-1")
        assertEquals(TransferStatus.Cancelled, orphan.status)
        assertNull(orphan.error)
    }

    @Test
    fun `queueing work asks for the process to be held up`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://one.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://one.txt", name = "one.txt", sizeBytes = 10L),
        ))

        // Asked synchronously, at enqueue time: Android 12+ refuses a background
        // start, so the moment the user queued the copy is the moment to hold on.
        assertEquals(1, ctx.begins)
        ctx.settle()
    }

    @Test
    fun `two batches for one server never open two sessions at once`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://one.txt"] = ByteArray(10)
        ctx.local.contents["file://two.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)
        ctx.yieldOnOpen = true

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://one.txt", name = "one.txt", sizeBytes = 10L),
        ))
        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://two.txt", name = "two.txt", sizeBytes = 10L),
        ))

        ctx.settle()

        // sshd's per-user session cap makes the second handshake fail, and two
        // copies would fight over the same bandwidth (and lie about their speed).
        assertEquals(2, ctx.opens)
        assertEquals(1, ctx.maxConcurrentOpens)
        assertEquals(
            listOf(TransferStatus.Completed, TransferStatus.Completed),
            store.jobs.value.map { it.status },
        )
    }

    @Test
    fun `a slow server does not hold up a different connection`() = transfer() { ctx, scope ->
        ctx.repository.save(
            id = OTHER_CONNECTION_ID,
            displayName = "Other server",
            username = "ops",
            host = "lab.example",
            port = 22,
            passwordPlain = "hunter2",
            screenSessionName = null,
        )
        val store = ctx.store(scope)
        ctx.local.contents["file://one.txt"] = ByteArray(10)
        ctx.local.contents["file://two.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)
        ctx.yieldOnOpen = true

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://one.txt", name = "one.txt", sizeBytes = 10L),
        ))
        store.enqueueUpload(OTHER_CONNECTION_ID, "Other server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://two.txt", name = "two.txt", sizeBytes = 10L),
        ))

        ctx.settle()

        // Per-connection locking, not one global lock: a slow server must not block
        // a copy aimed at a different one.
        assertEquals(2, ctx.maxConcurrentOpens)
        assertEquals(
            listOf(TransferStatus.Completed, TransferStatus.Completed),
            store.jobs.value.map { it.status },
        )
    }

    // --- permissions the app should not keep -----------------------------------

    @Test
    fun `the read grant of a file is given back once the queue is done with it`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://one.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://one.txt", name = "one.txt", sizeBytes = 10L),
        ))

        ctx.settle()

        // A persistable grant outlives a reboot: keeping one for every file the user
        // ever sent is a permission held for nothing.
        assertEquals(listOf("file://one.txt"), ctx.local.released)
    }

    @Test
    fun `a grant is kept while another row is still reading the same file`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://same.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)

        // The same document queued twice (two folders, or a retry): the first row to
        // settle must not pull the grant out from under the second.
        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://same.txt", name = "same.txt", sizeBytes = 10L),
            LocalItem(uri = "file://same.txt", name = "same.txt", sizeBytes = 10L),
        ))

        ctx.settle()

        assertEquals(listOf("file://same.txt"), ctx.local.released)
        assertEquals(
            listOf(TransferStatus.Completed, TransferStatus.Completed),
            store.jobs.value.map { it.status },
        )
    }

    @Test
    fun `a download releases nothing because it never held a read grant`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.fs.files["$REMOTE_DIR/plan.pdf"] = ByteArray(10)

        store.enqueueDownload(CONNECTION_ID, "Lab server", DEVICE_DIR, listOf(
            RemoteEntry(name = "plan.pdf", path = "$REMOTE_DIR/plan.pdf", isDirectory = false, sizeBytes = 10L),
        ))

        ctx.settle()

        assertEquals(TransferStatus.Completed, ctx.job(store, "g1-1").status)
        assertTrue(ctx.local.released.isEmpty())
    }

    @Test
    fun `clearing finished rows mid-batch still gives the grant back`() = transfer() { ctx, scope ->
        val store = ctx.store(scope)
        ctx.local.contents["file://one.txt"] = ByteArray(10)
        ctx.local.contents["file://two.txt"] = ByteArray(10)
        ctx.fs.addDir(REMOTE_DIR)

        // "Clear finished" while a batch is still running drops the row of the file
        // that just landed. The grant has to be remembered by the batch itself, or
        // the URI disappears with the row and the app keeps the permission forever.
        ctx.onChunk = {
            if (store.jobs.value.count { it.status == TransferStatus.Completed } == 1) {
                store.clearFinished()
            }
        }

        store.enqueueUpload(CONNECTION_ID, "Lab server", REMOTE_DIR, listOf(
            LocalItem(uri = "file://one.txt", name = "one.txt", sizeBytes = 10L),
            LocalItem(uri = "file://two.txt", name = "two.txt", sizeBytes = 10L),
        ))

        ctx.settle()

        assertEquals(listOf("file://one.txt", "file://two.txt"), ctx.local.released)
        assertEquals(2, ctx.fs.uploads.size)
        // The row of the first file really was dropped mid-batch, so this test would
        // fail if the queue still looked the URI up on the rows at release time.
        assertEquals(listOf("g1-2"), store.jobs.value.map { it.id })
    }

    private companion object {
        const val CONNECTION_ID = 42L
        const val OTHER_CONNECTION_ID = 77L
        const val REMOTE_DIR = "/srv/uploads"
        const val DEVICE_DIR = "content://device/downloads"
    }
}
