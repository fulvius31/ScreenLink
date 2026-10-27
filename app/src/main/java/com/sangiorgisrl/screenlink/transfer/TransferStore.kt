package com.sangiorgisrl.screenlink.transfer

import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.data.ConnectionRepository
import com.sangiorgisrl.screenlink.i18n.UiText
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Application-scoped queue of file transfers (the "scp" feature).
 *
 * Like [com.sangiorgisrl.screenlink.session.LiveTerminalStore], it deliberately
 * owns its work OUTSIDE the Activity and the view models: a copy that is halfway
 * through a 2 GB file must survive rotation, navigation and the user peeking at
 * another screen. Every job publishes its byte counter, smoothed speed and
 * remaining time into a [StateFlow], which is what the progress UI renders.
 *
 * A "group" is one batch for one connection: it shares a single SSH + SFTP
 * handshake and copies its files one after another, which keeps the speed
 * estimate honest and the server load predictable.
 */
class TransferStore(
    private val repository: ConnectionRepository,
    private val local: LocalFileGateway,
    private val transports: TransferTransportFactory,
    private val scope: CoroutineScope,
    /** Holds the process up while the queue has work (see [TransferForeground]). */
    private val foreground: TransferForeground = TransferForeground.None,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** One file in the queue - the whole model the progress UI needs. */
    data class Job(
        val id: String,
        val groupId: String,
        val direction: TransferDirection,
        val connectionId: Long,
        val connectionName: String,
        val fileName: String,
        /**
         * The name the user asked for, before any collision renaming.
         *
         * [fileName] is what the row shows and therefore what was really created
         * (`report (1).txt`); a retry has to start from the ORIGINAL name, or
         * retrying the same file three times ends up with `report (1) (1) (1).txt`.
         */
        val desiredName: String,
        val remotePath: String,
        val localUri: String,
        /** Destination folder for a download (null for an upload). */
        val localDirUri: String?,
        /** -1 while unknown: the bar goes indeterminate instead of lying. */
        val totalBytes: Long,
        val transferredBytes: Long = 0L,
        val status: TransferStatus = TransferStatus.Queued,
        val bytesPerSecond: Double = 0.0,
        val secondsLeft: Double? = null,
        val error: UiText? = null,
        /**
         * A temporary, non-fatal explanation for what the row is doing
         * ("reconnecting"), shown in place of the speed line.
         *
         * A copy that died on a dead link and is being run again on a fresh
         * connection sends its bar back to zero. Without a word about WHY, that
         * reads as the queue losing work or hanging, so the row says what is
         * happening until the first byte of the new attempt proves it is moving.
         */
        val note: UiText? = null,
        val startedAt: Long = 0L,
        val finishedAt: Long = 0L,
    )

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())

    /**
     * The queue, hot and current for as long as the application lives.
     *
     * Screens read THIS flow rather than re-wrap it in `stateIn(...)`: a share answers
     * with the initial value it was handed (`emptyList()`) until its upstream has
     * actually been dispatched, so wrapping a queue that is already hot buys a frame of
     * "no copies" - the badge missing, the Home card absent, the queue screen on its
     * empty state - before the real rows arrive. `stateIn` is for cold upstreams (Room
     * queries); this one is a StateFlow that never completes.
     *
     * Measured rather than remembered, and both halves are pinned by
     * `QueueFlowSharingTest`: the empty first frame is real, and the folklore version
     * is not - a `WhileSubscribed` share does NOT reset to its initial value once the
     * last subscriber leaves, because `replayExpirationMillis` defaults to
     * `Long.MAX_VALUE` and the last queue survives.
     */
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    private val cancelFlags = java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean>()
    private val lastPublish = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val counter = AtomicInteger(0)

    /**
     * One mutex per connection id.
     *
     * Two batches for the same server must not open two SFTP sessions at once:
     * sshd's per-user session cap makes the second handshake fail (and a server
     * that fails halfway looks like a broken app), and the two speed estimates
     * would fight over the same bandwidth. Different connections still run in
     * parallel, which is what makes a slow server not block a fast one.
     */
    private val connectionLocks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()

    /** Queue [files] for upload into [remoteDir]; returns the group id. */
    fun enqueueUpload(
        connectionId: Long,
        connectionName: String,
        remoteDir: String,
        files: List<LocalItem>,
    ): String = enqueue(
        direction = TransferDirection.Upload,
        connectionId = connectionId,
        connectionName = connectionName,
        localDirUri = null,
        specs = files.map { item ->
            // The name another app sent is only a label until it is made safe: the
            // queue row shows this text, so it must be the name really created.
            val name = RemotePaths.sanitizeName(item.name)
            Spec(
                fileName = name,
                desiredName = name,
                remotePath = RemotePaths.join(remoteDir, name),
                localUri = item.uri,
                totalBytes = item.sizeBytes,
            )
        },
    )

    /** Queue remote [entries] for download into the picked device folder. */
    fun enqueueDownload(
        connectionId: Long,
        connectionName: String,
        folderUri: String,
        entries: List<RemoteEntry>,
    ): String = enqueue(
        direction = TransferDirection.Download,
        connectionId = connectionId,
        connectionName = connectionName,
        localDirUri = folderUri,
        specs = entries.map { entry ->
            Spec(
                fileName = RemotePaths.sanitizeName(entry.name),
                desiredName = RemotePaths.sanitizeName(entry.name),
                remotePath = entry.path,
                localUri = "",
                totalBytes = entry.sizeBytes,
            )
        },
    )

    private data class Spec(
        val fileName: String,
        val desiredName: String,
        val remotePath: String,
        val localUri: String,
        val totalBytes: Long,
    )

    private fun enqueue(
        direction: TransferDirection,
        connectionId: Long,
        connectionName: String,
        localDirUri: String?,
        specs: List<Spec>,
    ): String {
        val groupId = "g${counter.incrementAndGet()}"
        val jobs = specs.mapIndexed { index, spec ->
            Job(
                id = "$groupId-${index + 1}",
                groupId = groupId,
                direction = direction,
                connectionId = connectionId,
                connectionName = connectionName,
                fileName = spec.fileName,
                desiredName = spec.desiredName,
                remotePath = spec.remotePath,
                localUri = spec.localUri,
                localDirUri = localDirUri,
                totalBytes = spec.totalBytes,
            ).also { cancelFlags[it.id] = AtomicBoolean(false) }
        }
        _jobs.update { it + jobs }
        // Ask for the process to be held up BEFORE the first handshake: a copy the
        // user just started must not be reclaimable while the queue is starting it.
        foreground.begin()
        runGroup(groupId, connectionId, jobs.map { it.id })
        return groupId
    }

    /** Ask one job to stop. A queued job is settled immediately. */
    fun cancel(jobId: String) {
        cancelFlags.computeIfAbsent(jobId) { AtomicBoolean(false) }.set(true)
        patch(jobId) {
            if (it.status == TransferStatus.Queued) it.copy(status = TransferStatus.Cancelled, note = null, finishedAt = clock())
            else it
        }
    }

    fun cancelGroup(groupId: String) {
        _jobs.value.filter { it.groupId == groupId }.forEach { cancel(it.id) }
    }

    /**
     * Stop everything the queue still owns - the "Stop all" button and the
     * notification action both land here.
     *
     * Cancelling row by row is not a reasonable thing to ask of a user who just
     * queued forty files to the wrong server, and a batch that keeps running after
     * they changed their mind is the worst possible outcome of this feature.
     */
    fun cancelAll() {
        _jobs.value
            .filter { it.status == TransferStatus.Queued || it.status == TransferStatus.Running }
            .forEach { cancel(it.id) }
    }

    /** Re-queue a finished/failed/cancelled job as a fresh one (same file, same paths). */
    fun retry(jobId: String): String? {
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return null
        return when (job.direction) {
            TransferDirection.Upload -> enqueueUpload(
                connectionId = job.connectionId,
                connectionName = job.connectionName,
                remoteDir = RemotePaths.parent(job.remotePath) ?: RemotePaths.ROOT,
                // Retry the name the user asked for, not the one the collision rule
                // produced: otherwise every retry adds another "(1)".
                files = listOf(
                    LocalItem(uri = job.localUri, name = job.desiredName, sizeBytes = job.totalBytes),
                ),
            )
            TransferDirection.Download -> enqueueDownload(
                connectionId = job.connectionId,
                connectionName = job.connectionName,
                folderUri = job.localDirUri ?: return null,
                entries = listOf(
                    RemoteEntry(
                        name = job.desiredName,
                        path = job.remotePath,
                        isDirectory = false,
                        sizeBytes = job.totalBytes,
                    ),
                ),
            )
        }
    }

    /** Drop finished rows so the list can get out of the way. */
    fun clearFinished() {
        val finished = _jobs.value
            .filter { it.status == TransferStatus.Completed || it.status == TransferStatus.Failed || it.status == TransferStatus.Cancelled }
            .map { it.id }
            .toSet()
        _jobs.update { list -> list.filterNot { it.id in finished } }
        finished.forEach { id -> cancelFlags.remove(id); lastPublish.remove(id) }
    }

    // --- execution ------------------------------------------------------------

    private fun runGroup(groupId: String, connectionId: Long, jobIds: List<String>) {
        scope.launch {
            try {
                // One session per connection at a time (see [connectionLocks]).
                connectionLocks.computeIfAbsent(connectionId) { Mutex() }.withLock {
                    runBatch(connectionId, jobIds)
                }
            } catch (e: CancellationException) {
                // The scope itself went away, or the group never got the lock before
                // the queue was dropped. Rows still reading "waiting" would lie, and
                // they are not failures either: the user never asked for them to stop.
                markRemainingCancelled(jobIds)
                throw e
            }
        }
    }

    private suspend fun runBatch(connectionId: Long, jobIds: List<String>) {
        var transport: TransferTransport? = null
        /**
         * Throw away the session this batch was using.
         *
         * Three things end a session: it went stale between two files, the user
         * stopped a copy mid-stream, or a copy failed. All three leave a channel we
         * cannot trust - a half-written SFTP handle, or a channel whose state the
         * server and we no longer agree about - and reusing one is how the SECOND
         * file of a batch fails in a new and more confusing way. So every one of
         * those paths goes through here, and the next file pays a fresh handshake.
         */
        fun abandonSession() {
            transport?.let { runCatching { it.close() } }
            transport = null
        }
        // Snapshot the documents this batch reads BEFORE any row can finish: the user
        // can tap "Clear finished" while a batch is still running, and a URI read back
        // off a row that is already gone is a grant that never gets handed back.
        val grantUris = readGrantUris(jobIds)
        try {
            val connection = repository.getSshConnection(connectionId)
            if (connection == null) {
                failRemaining(jobIds, UiText.Resource(R.string.err_connection_missing))
                return
            }
            val keys = repository.getSshKeysForConnection(connectionId)
            for (id in jobIds) {
                val job = _jobs.value.firstOrNull { it.id == id } ?: continue
                if (job.status != TransferStatus.Queued) continue // cancelled while queued
                // One attempt loop per file. The second attempt only ever happens for
                // a link that died underneath the copy (see [RetryPolicy]): a phone
                // whose Wi-Fi slept, a NAT that reaped the flow, a server that
                // restarted. The copy itself was fine, and the user watching a bar
                // that simply carries on is the whole point - they would reconnect
                // and tap Retry themselves otherwise, and should not have to.
                var attempt = 0
                while (true) {
                    attempt++
                    // Re-read the row: the previous attempt may have renamed it (the
                    // collision rule) and the retry must continue from THAT name, or
                    // a second pass adds another "(1)".
                    val current = _jobs.value.firstOrNull { it.id == id } ?: break
                    val est = TransferEstimator(clock).also { it.reset(clock()) }
                    patch(id) { it.copy(status = TransferStatus.Running, startedAt = clock()) }
                    try {
                        // Replace a session the server has already forgotten about
                        // BEFORE the copy speaks (see [TransferTransport.isAlive]):
                        // a long batch is exactly where an idle handshake goes stale,
                        // and the user should pay a reconnect, not read a pipe error.
                        val stale = transport
                        if (stale != null && !runCatching { stale.isAlive() }.getOrDefault(false)) {
                            abandonSession()
                        }
                        val active = transport
                            ?: transports.open(connection, keys).also { transport = it }
                        execute(current, active, est)
                        patch(id) {
                            it.copy(
                                status = TransferStatus.Completed,
                                transferredBytes = it.totalBytes.coerceAtLeast(it.transferredBytes),
                                bytesPerSecond = est.averageRate(clock(), it.transferredBytes),
                                secondsLeft = 0.0,
                                note = null,
                                finishedAt = clock(),
                            )
                        }
                        break
                    } catch (e: CancellationException) {
                        patch(id) { it.copy(status = TransferStatus.Cancelled, note = null, finishedAt = clock()) }
                        throw e
                    } catch (e: TransferCancelledException) {
                        patch(id) { it.copy(status = TransferStatus.Cancelled, note = null, finishedAt = clock()) }
                        // Aborting mid-stream leaves the SFTP handle half-written and
                        // the channel's state unknown to us: never reuse it for the
                        // next file, open a fresh session instead.
                        abandonSession()
                        break
                    } catch (e: Exception) {
                        // JSch wraps anything thrown inside its own pump (our cancel
                        // exception included) into SftpException(SSH_FX_FAILURE), so a
                        // cancel is recognised by the flag it set, not only by type.
                        val cancelled = cancelFlags[id]?.get() == true || causedByCancellation(e)
                        if (cancelled) {
                            patch(id) { it.copy(status = TransferStatus.Cancelled, note = null, finishedAt = clock()) }
                            // Same rule as a cancel that arrives as its own type: the
                            // copy stopped halfway, so this channel is not reusable.
                            abandonSession()
                            break
                        }
                        // Whatever just went wrong, this session is no longer
                        // trustworthy: the next attempt (or the next file) starts from
                        // a clean handshake.
                        abandonSession()
                        if (RetryPolicy.shouldRetry(e, attempt)) {
                            restartRow(id)
                            continue
                        }
                        patch(id) {
                            it.copy(
                                status = TransferStatus.Failed,
                                error = friendlyTransferError(e),
                                note = null,
                                finishedAt = clock(),
                            )
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            failRemaining(jobIds, friendlyTransferError(e))
        } finally {
            transport?.let { runCatching { it.close() } }
            // The batch is done with the documents it read: give the read grants
            // back unless another row is still using the same file.
            releaseGrantsOf(grantUris)
        }
    }

    private suspend fun execute(job: Job, transport: TransferTransport, est: TransferEstimator) {
        val flag = cancelFlags[job.id] ?: AtomicBoolean(false)
        // The total can still be unknown when the job was queued (a share that did
        // not report a size), so it is re-read here and the listener closes over it.
        var total = job.totalBytes
        val listener = object : ProgressListener {
            override fun onBytes(transferred: Long) {
                val estimate = est.update(clock(), transferred, total)
                publish(job.id, transferred, total, estimate)
            }

            override fun isCancelled(): Boolean = flag.get()
        }
        when (job.direction) {
            TransferDirection.Upload -> {
                val source = local.source(job.localUri)
                if (total <= 0L && source.sizeBytes > 0L) {
                    total = source.sizeBytes
                    patch(job.id) { it.copy(totalBytes = total) }
                }
                val target = uniqueRemotePath(transport, job.remotePath)
                // The row shows [fileName], so the name the server really got is
                // written back into it: a row reading "report.txt" while the server
                // holds "report (1).txt" is the queue lying about what it made.
                if (target != job.remotePath) {
                    val leaf = RemotePaths.leafName(target)
                    patch(job.id) { it.copy(remotePath = target, fileName = leaf) }
                }
                try {
                    transport.upload(source, target, listener)
                } catch (e: Exception) {
                    // [uniqueRemotePath] proved this name was free before the copy
                    // started, so whatever sits at it now is OUR half-written file.
                    // Leaving it behind is the worse failure: the user finds a
                    // truncated copy next to the real one and cannot tell them apart.
                    runCatching { transport.delete(target) }
                    throw e
                }
            }
            TransferDirection.Download -> {
                // The screen always asks for a folder first, but a job can outlive
                // the state that queued it: say "pick a folder" rather than write
                // the file nowhere.
                val dir = job.localDirUri?.takeIf { it.isNotBlank() } ?: throw NoDestinationException()
                val taken = runCatching { local.childNames(dir) }.getOrDefault(emptyList())
                val name = RemotePaths.uniqueName(job.desiredName, taken.toSet())
                if (name != job.fileName) patch(job.id) { it.copy(fileName = name) }
                val sink = local.sink(dir, name, null)
                try {
                    transport.download(job.remotePath, sink, listener)
                } catch (e: Exception) {
                    // Same rule on the device side: a cancelled or failed download
                    // must not leave a partial file in the folder the user picked.
                    runCatching { sink.discard() }
                    throw e
                }
            }
        }
    }

    /**
     * Never overwrite silently: if the name is taken, take `report (1).pdf`.
     *
     * The check asks the server about the exact path ([TransferTransport.exists])
     * rather than trusting a listing: a listing can be refused, can be truncated on
     * a huge folder, and is stale the moment it returns. Asking about the one path we
     * are about to write is both cheaper (no LIST per file) and the server's answer.
     *
     * One exception, and it lives inside the transport: servers below SFTP protocol
     * v3 answer a missing file with a generic FAILURE, so [SftpTransport.exists]
     * cannot tell "absent" from "refused" there and falls back to a listing of the
     * PARENT folder. That is still not a scan of the folder the user is looking at,
     * and if the listing is refused too the exception escapes to the rule below.
     *
     * A failure here is deliberately NOT swallowed: guessing "free" is exactly how
     * a user's existing file gets replaced, so the job fails with the server's
     * reason instead.
     */
    private fun uniqueRemotePath(transport: TransferTransport, path: String): String {
        val parent = RemotePaths.parent(path) ?: return path
        val leaf = RemotePaths.leafName(path)
        var name = leaf
        val tried = mutableSetOf(leaf)
        while (transport.exists(RemotePaths.join(parent, name))) {
            name = RemotePaths.uniqueName(leaf, tried)
            // uniqueName is deterministic for the same set, so the candidate must
            // join the set or the loop would ask about the same name forever.
            if (!tried.add(name)) break
        }
        return RemotePaths.join(parent, name)
    }

    private fun publish(jobId: String, transferred: Long, total: Long, estimate: TransferEstimate) {
        val now = clock()
        // The FIRST sample always gets through: a bar that only starts moving after
        // the throttle window would look frozen on a small or slow file.
        val last = lastPublish[jobId]
        // A 32 KB buffer would otherwise publish tens of times a second; the UI
        // only needs enough samples to look alive.
        if (last != null && now - last < PUBLISH_INTERVAL_MS) return
        lastPublish[jobId] = now
        patch(jobId) {
            it.copy(
                transferredBytes = transferred,
                totalBytes = if (total > 0L) total else it.totalBytes,
                bytesPerSecond = estimate.bytesPerSecond,
                secondsLeft = estimate.secondsLeft,
                // Bytes are flowing again: the "reconnecting" note has done its job.
                note = null,
            )
        }
    }

    private fun failRemaining(jobIds: List<String>, message: UiText) {
        val ids = jobIds.toSet()
        _jobs.update { list ->
            list.map {
                if (it.id in ids && (it.status == TransferStatus.Queued || it.status == TransferStatus.Running)) {
                    it.copy(status = TransferStatus.Failed, error = message, finishedAt = clock())
                } else it
            }
        }
    }

    /**
     * Settle the rows of a batch that was cut short by the queue itself dying.
     *
     * Without this, a process teardown leaves rows reading "waiting" or "running"
     * forever - a progress bar that never moves and never ends is the one thing a
     * progress UI must never do. They are cancelled, not failed: nothing went
     * wrong, the work was simply dropped.
     */
    private fun markRemainingCancelled(jobIds: List<String>) {
        val ids = jobIds.toSet()
        _jobs.update { list ->
            list.map {
                if (it.id in ids && (it.status == TransferStatus.Queued || it.status == TransferStatus.Running)) {
                    it.copy(status = TransferStatus.Cancelled, note = null, finishedAt = clock())
                } else it
            }
        }
    }

    /**
     * Give back the persistable read grants this batch was using.
     *
     * The picker hands out a grant that survives reboots, so holding one for every
     * file the user ever sent is a permission kept for nothing. Two rules keep this
     * honest:
     *  - ONE release per URI, not per row: a batch that queued the same document
     *    twice (two folders, or a retry) must not hand the grant back twice.
     *  - never while another row still needs it: a batch that is still Queued or
     *    Running on the same URI would lose the file under its own copy.
     *
     * The URI list is snapshotted when the batch STARTS (see [readGrantUris]) rather
     * than read off the rows at the end: "Clear finished" can drop the rows of a
     * running batch as they complete, and a URI that is no longer on any row is a
     * grant the app would hold forever.
     */
    private fun releaseGrantsOf(uris: List<String>) {
        uris.forEach { uri ->
            val stillInUse = _jobs.value.any {
                it.localUri == uri &&
                    (it.status == TransferStatus.Queued || it.status == TransferStatus.Running)
            }
            if (!stillInUse) runCatching { local.releaseReadGrant(uri) }
        }
    }

    /** The distinct non-blank source URIs behind [jobIds] (one entry per document). */
    private fun readGrantUris(jobIds: List<String>): List<String> =
        jobIds.mapNotNull { id -> _jobs.value.firstOrNull { it.id == id }?.localUri }
            .filter { it.isNotBlank() }
            .distinct()

    private fun patch(jobId: String, transform: (Job) -> Job) {
        _jobs.update { list -> list.map { if (it.id == jobId) transform(it) else it } }
    }

    /**
     * Put a row back to the start of its bar before a retry on a fresh connection.
     *
     * The bar has to go back to zero, not stay parked at 60 %: the second attempt
     * really does start from the first byte, and a row that jumps from 60 % to 5 %
     * would look like the queue lost work it had already done. The speed and the
     * remaining time go with it - they described a connection that no longer exists.
     */
    private fun restartRow(jobId: String) {
        // Let the first sample of the new attempt through the publish throttle.
        lastPublish.remove(jobId)
        patch(jobId) {
            it.copy(
                status = TransferStatus.Running,
                transferredBytes = 0L,
                bytesPerSecond = 0.0,
                secondsLeft = null,
                error = null,
                note = UiText.Resource(R.string.files_status_reconnecting),
            )
        }
    }

    /** True when [error] - or anything wrapped under it - is our own cancel signal. */
    private fun causedByCancellation(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is TransferCancelledException) return true
            cause = cause.cause
        }
        return false
    }

    companion object {
        /** Minimum gap between two progress publishes for one job. */
        const val PUBLISH_INTERVAL_MS = 200L
    }
}
