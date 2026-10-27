package com.sangiorgisrl.screenlink.transfer

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.Session
import com.sangiorgisrl.screenlink.data.SshConnection
import com.sangiorgisrl.screenlink.data.SshKeyCredential
import com.sangiorgisrl.screenlink.ssh.SshConnector
import java.util.Vector

/**
 * SFTP-backed [TransferTransport] - scp's modern, structured twin: same SSH
 * connection, same pinned host key, same credentials as the terminal, but with a
 * real directory listing instead of scraping `ls` output.
 *
 * JSch's SFTP channel is blocking and single-threaded, so one transport serves
 * one queue group (a batch of files for one connection) and is closed afterwards.
 */
class SftpTransport(
    private val session: Session,
    private val channel: ChannelSftp,
) : TransferTransport {

    override fun home(): String = runCatching { channel.home }
        .getOrElse { runCatching { channel.pwd() }.getOrDefault(RemotePaths.ROOT) }

    /**
     * JSch's own answer about the session and the channel underneath us.
     *
     * When the reader thread of a session dies - a keepalive that went unanswered,
     * a Wi-Fi that slept, a server that restarted - JSch marks the session and all
     * its channels disconnected, so this goes false a whole tap BEFORE the next
     * call would surface it as "pipe is closed". That is the difference between a
     * silent reconnect and an error message about a pipe.
     *
     * The channel is asked as well as the session: a server can close one SFTP
     * channel (idle timeout, MaxSessions) while leaving the session standing, and
     * reusing THAT channel is how the next tap dies on a closed pipe.
     */
    override fun isAlive(): Boolean =
        session.isConnected && channel.isConnected && !channel.isClosed && !channel.isEOF

    override fun list(path: String): List<RemoteEntry> {
        val raw: Vector<ChannelSftp.LsEntry> = channel.ls(path)
        val entries = ArrayList<RemoteEntry>(raw.size)
        for (entry in raw) {
            val name = entry.filename
            if (name == "." || name == "..") continue
            val attrs = entry.attrs
            val isDir = attrs.isDir
            entries += RemoteEntry(
                name = name,
                path = RemotePaths.join(path, name),
                isDirectory = isDir,
                sizeBytes = if (isDir) -1L else attrs.size,
                modifiedAtMillis = attrs.mTime.toLong() * 1000L,
                // Sockets, devices and FIFOs answer a listing but cannot be streamed;
                // ticking one would only produce a confusing failure later on.
                isCopyable = isDir || attrs.isReg || attrs.isLink,
            )
        }
        return RemotePaths.sort(entries)
    }

    override fun mkdir(path: String) {
        channel.mkdir(path)
    }

    /**
     * Ask the server about the exact path instead of trusting a listing.
     *
     * `stat` follows symlinks on purpose: writing through a symlink replaces the
     * file the user already owns, so a link counts as "taken".
     */
    override fun exists(path: String): Boolean = try {
        channel.stat(path)
        true
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
            false
        } else {
            // Servers below protocol v3 answer a missing file with a generic
            // FAILURE, so a bare "no" is not proof of a clash: fall back to the
            // parent listing. If THAT is refused too, the exception escapes and the
            // job fails - guessing "free" is how an existing file gets replaced.
            clashesInListing(path)
        }
    }

    override fun delete(path: String) {
        channel.rm(path)
    }

    override fun upload(source: LocalSource, remotePath: String, progress: ProgressListener) {
        source.open().use { input ->
            channel.put(CountingInputStream(input, progress), remotePath, ChannelSftp.OVERWRITE)
        }
    }

    override fun download(remotePath: String, sink: LocalSink, progress: ProgressListener) {
        sink.open().use { output ->
            channel.get(remotePath, CountingOutputStream(output, progress))
        }
    }

    override fun close() {
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }

    private fun clashesInListing(path: String): Boolean {
        val parent = RemotePaths.parent(path) ?: return false
        val leaf = RemotePaths.leafName(path)
        return list(parent).any { it.name.equals(leaf, ignoreCase = true) }
    }
}

/**
 * Opens the SFTP channel of an already-pinned connection.
 *
 * Goes through [SshConnector] so a file copy can never bypass the host-key policy
 * the terminal enforces: a changed host key aborts the handshake BEFORE the
 * password or private key leaves the device.
 */
class SftpTransportFactory(
    private val connector: SshConnector,
    private val timeoutMs: Int = 15_000,
) : TransferTransportFactory {

    override suspend fun open(
        connection: SshConnection,
        keys: List<SshKeyCredential>,
    ): TransferTransport {
        val session = connector.connect(connection, keys)
        try {
            val channel = connector.openSftp(session, timeoutMs)
            return SftpTransport(session, channel)
        } catch (e: Exception) {
            runCatching { session.disconnect() }
            throw e
        }
    }
}
