package com.sangiorgisrl.screenlink.transfer

/** Which way a file moves: into the server (upload) or onto the device (download). */
enum class TransferDirection { Upload, Download }

/** Lifecycle of one file inside the transfer queue. */
enum class TransferStatus { Queued, Running, Completed, Failed, Cancelled }

/**
 * A file the app can read on the device - either picked with the system document
 * picker (`content://…`) or handed over by another app through the share sheet.
 *
 * [uri] is kept as a String (not an `android.net.Uri`) so the whole transfer
 * layer - queue, naming, progress maths - stays testable on the JVM.
 * [sizeBytes] is -1 when the provider does not report a size, which turns the
 * progress bar indeterminate instead of lying about a total.
 */
data class LocalItem(
    val uri: String,
    val name: String,
    val sizeBytes: Long = -1L,
    val mimeType: String? = null,
)

/** One entry of a remote directory listing (already sorted for display). */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = -1L,
    val modifiedAtMillis: Long = 0L,
    /** True for things the app will not copy (symlinks, sockets, devices). */
    val isCopyable: Boolean = true,
)

/**
 * Live speed + remaining time for one running transfer, computed by
 * [TransferEstimator]. Both are null-ish by design: a transfer with an unknown
 * total has no time left to promise, and the first samples have no rate yet.
 */
data class TransferEstimate(
    val bytesPerSecond: Double,
    val secondsLeft: Double?,
)

/**
 * Byte-level progress hook handed to a [TransferTransport]. The transport calls
 * [onBytes] with the CUMULATIVE byte count as it streams, and checks
 * [isCancelled] before every chunk so a cancel stops within one buffer.
 */
interface ProgressListener {
    fun onBytes(transferred: Long)
    fun isCancelled(): Boolean
}

/** A readable local file, abstracted so the queue never needs a ContentResolver. */
interface LocalSource {
    val sizeBytes: Long
    fun open(): java.io.InputStream
}

/** A writable local destination (the app may still rename the created file). */
interface LocalSink {
    /** The name the file will actually get, once collisions are resolved. */
    val targetName: String
    fun open(): java.io.OutputStream

    /**
     * Throw away whatever [open] created.
     *
     * A cancelled or failed download leaves a truncated file behind in the folder
     * the user picked, which is worse than no file at all: it looks like a copy
     * that worked. The queue calls this whenever a copy did not finish, and an
     * implementation that created nothing simply does nothing.
     */
    fun discard() = Unit
}

/**
 * Device-side file access (SAF / ContentResolver). Injected into the transfer
 * queue so the queue logic can be unit tested against plain temp files.
 */
interface LocalFileGateway {
    /** Name / size / mime of a `content://` file, or null when it is gone. */
    fun describe(uri: String): LocalItem?

    fun source(uri: String): LocalSource

    /** Create (or overwrite) [name] inside the picked tree folder [dirUri]. */
    fun sink(dirUri: String, name: String, mimeType: String?): LocalSink

    /** Names already present in the picked folder, for collision-free naming. */
    fun childNames(dirUri: String): List<String>

    /** Human-readable name of a picked folder (for the UI). */
    fun folderDisplayName(dirUri: String): String

    /**
     * Give back a persistable read grant the app no longer needs.
     *
     * The document picker hands out a grant that survives a reboot, so keeping one
     * for every file the user ever looked at is a permission the app holds for
     * nothing. The queue calls this once the last job that referenced a URI has
     * settled.
     */
    fun releaseReadGrant(uri: String) = Unit
}

/**
 * One SSH file-transfer session: browsing plus the two copy directions.
 *
 * The only implementation today is SFTP over the same JSch session (and therefore
 * the same pinned host key) the terminal uses; keeping it behind an interface is
 * what lets the queue be tested with a fake and lets another transport (e.g. the
 * `scp -t`/`scp -f` exec protocol) be dropped in without touching the UI.
 */
interface TransferTransport : java.io.Closeable {
    /** Directory the browser opens on (the account's home). */
    fun home(): String

    fun list(path: String): List<RemoteEntry>

    fun mkdir(path: String)

    fun upload(source: LocalSource, remotePath: String, progress: ProgressListener)

    fun download(remotePath: String, sink: LocalSink, progress: ProgressListener)

    /**
     * Can this transport still answer?
     *
     * A phone whose Wi-Fi slept, a network that changed from Wi-Fi to data or a
     * server that restarted leaves an SSH session that still LOOKS open to us and
     * answers the next call with a pipe error. Asking first turns that into a
     * reconnect the user never reads about, and it is the only defence for a call
     * that may not be repeated (a mkdir, a copy that already wrote half a file).
     *
     * Default `true` because a transport that has nothing to check - the fakes, an
     * in-memory transport - is always able to answer; a real one must implement it.
     * It must never THROW: the answer is a hint about which channel to use, not a
     * result the user reads.
     */
    fun isAlive(): Boolean = true

    /**
     * True when something already lives at [path].
     *
     * This is what makes "never overwrite" a promise instead of a hope: listing a
     * folder can be refused or can go stale between the listing and the copy, while
     * a stat of the exact path is the server's own answer. A transport that cannot
     * answer must THROW rather than return false, because guessing "free" is exactly
     * how a user's existing file gets replaced.
     */
    fun exists(path: String): Boolean {
        val parent = RemotePaths.parent(path) ?: return false
        val leaf = RemotePaths.leafName(path)
        // Fallback for transports that only implement a listing (fakes, other
        // protocols): case-insensitive, because the target may be a folded-name
        // filesystem where "Report.PDF" IS a clash.
        return list(parent).any { it.name.equals(leaf, ignoreCase = true) }
    }

    /**
     * Best-effort removal of a half-written remote file.
     *
     * Only ever called for a name this queue proved was free before the copy
     * started, so deleting it can never destroy a file the user already had.
     */
    fun delete(path: String) = Unit
}

/** Opens a transport for a connection's decrypted credentials. */
interface TransferTransportFactory {
    /** May do a real SSH handshake, so it is a suspending call. */
    suspend fun open(
        connection: com.sangiorgisrl.screenlink.data.SshConnection,
        keys: List<com.sangiorgisrl.screenlink.data.SshKeyCredential>,
    ): TransferTransport
}
