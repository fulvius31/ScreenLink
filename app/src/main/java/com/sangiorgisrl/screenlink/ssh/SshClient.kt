package com.sangiorgisrl.screenlink.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.sangiorgisrl.screenlink.data.SshConnection
import com.sangiorgisrl.screenlink.data.SshKeyCredential
import java.io.ByteArrayOutputStream

/**
 * Thin, blocking wrapper around JSch (the maintained mwiede fork of the classic
 * pure-Java SSH client) so the rest of the app never touches JSch directly.
 *
 * JSch speaks the SSH wire protocol itself, which is why no native binaries are
 * needed on Android - unlike OpenSSH, which cannot be executed by an app process.
 */
class SshClient(private val jsch: JSch = JSch()) : SshGateway {

    companion object {
        /**
         * How long a connected session may sit silent before JSch pings the server.
         *
         * JSch drives its keepalive from the SOCKET READ TIMEOUT, not from
         * [Session.setServerAliveInterval]: its reader thread treats every read
         * timeout as "nothing arrived" and answers it with a ping. So the idle
         * window IS the ping cadence, and a cadence well under a minute is what
         * keeps a carrier NAT from reaping the flow between two keystrokes.
         */
        private const val IDLE_READ_TIMEOUT_MS = 15_000

        /**
         * Keepalives that may go unanswered before the session is called dead.
         *
         * With a 15 s window, four misses is ~75 s without a single reply before
         * JSch tears the session down: the reader pings once per window and gives
         * up on the window AFTER the fourth, so the budget is (countMax + 1)
         * windows. A healthy server answers every ping and the session survives
         * however long the user leaves it alone. Two was the old budget (~45 s): a
         * phone that slept through a minute came back to a session that had
         * already been written off, and the next tap paid for it. Four tolerates a
         * congested link without letting a genuinely dead one linger for minutes.
         */
        private const val KEEPALIVE_MISSES_TOLERATED = 4
    }

    /**
     * Connect a JSch session to the host described by [connection], offering the
     * given keys. [connection] and [keys] are the repository's decrypted SSH
     * credentials (see com.sangiorgisrl.screenlink.data.ConnectionRepository).
     */
    override fun connect(
        connection: SshConnection,
        keys: List<SshKeyCredential>,
        timeoutMs: Int,
    ): Session {
        val session = jsch.getSession(connection.username, connection.host, connection.port)
        if (connection.password.isNotEmpty()) {
            session.setPassword(connection.password)
        }
        // Register the connection's private keys (with their passphrases) as identities.
        for (key in keys) {
            try {
                val passphrase = key.passphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
                jsch.addIdentity(
                    key.name,
                    key.privateKey.toByteArray(Charsets.UTF_8),
                    null,
                    passphrase,
                )
            } catch (e: JSchException) {
                val reason = when {
                    e.message?.contains("passphrase", ignoreCase = true) == true ->
                        "wrong passphrase for key \"${key.name}\""
                    e.message?.contains("invalid privatekey", ignoreCase = true) == true ->
                        "invalid private key \"${key.name}\""
                    else -> e.message ?: "failed to load key \"${key.name}\""
                }
                throw JSchException(reason, e)
            }
        }
        // Refuse a host key that does not match this connection's pinned fingerprint
        // DURING the handshake - before any password or key is sent. PinnedHostKeyRepository
        // returns CHANGED for a mismatched key, and with StrictHostKeyChecking=yes JSch
        // aborts the connection (this enforces "no MITM" before credentials leave the
        // device, which used to only happen post-authentication). First contact / same-key
        // legacy pins are accepted and pinned after connect (TOFU).
        session.setHostKeyRepository(PinnedHostKeyRepository(connection.serverFingerprint))
        session.setConfig("StrictHostKeyChecking", "yes")
        // Keep the connection alive through NATs/firewalls and detect a silently dropped
        // link: a dead channel must surface to the reader as a disconnect instead of
        // freezing the terminal (or a folder listing) silently.
        session.setServerAliveInterval(IDLE_READ_TIMEOUT_MS)
        session.setServerAliveCountMax(KEEPALIVE_MISSES_TOLERATED)
        // Public-key first when keys are configured; otherwise password/keyboard-interactive.
        val auths = if (keys.isNotEmpty()) {
            "publickey,keyboard-interactive,password"
        } else {
            "password,keyboard-interactive"
        }
        session.setConfig("PreferredAuthentications", auths)
        session.setTimeout(timeoutMs)
        try {
            session.connect(timeoutMs)
        } catch (e: JSchException) {
            session.disconnect()
            throw e
        }
        // Re-arm the idle window now the handshake is over.
        //
        // [Session.setTimeout] before the connect does double duty in JSch: it is
        // the handshake timeout AND - because connect() re-applies it to the socket
        // once authentication succeeds - the read timeout of the idle session. So a
        // caller that asks for a slower handshake would quietly slow the keepalive
        // with it, and a keepalive that never fires is a session that looks alive
        // for hours and answers the next tap with "pipe is closed". Pinning the
        // socket here keeps the cadence a decision of ours, not a side effect of
        // the number the caller passed.
        runCatching { session.setTimeout(IDLE_READ_TIMEOUT_MS) }
        return session
    }

    /**
     * Standard SSHFK-style SHA-256 fingerprint of the server host key (matches
     * `ssh-keygen -lf` so users can cross-check it). jsch hashes the raw wire-format
     * key blob, which is exactly what OpenSSH fingerprints are computed over.
     */
    override fun serverFingerprint(session: Session): String {
        val fp = session.hostKey.getFingerPrint(jsch)
        // jsch returns e.g. "SHA256:base64..." - normalize the prefix to be safe.
        return if (fp.contains(":")) fp else "SHA256:$fp"
    }

    /**
     * The fingerprint format older builds (<= 1.0.x) stored for the same host key:
     * SHA-256 of the base64 key *text* rather than the wire-format blob. It is
     * deterministic, so we can recompute it for the CURRENT key and recognise a
     * connection whose saved pin came from the old scheme - and upgrade it to the
     * standard format instead of raising a false "host key changed" alert.
     */
    override fun serverFingerprintLegacy(session: Session): String {
        // jsch stores the host key as base64 *text* (HostKey#getKey); hash that text,
        // exactly like the old-build pins this function exists to recognise.
        return Fingerprints.legacyFromBase64KeyText(session.hostKey.key)
    }

    /**
     * Learn the server host key WITHOUT authenticating, so first contact can be
     * confirmed before any credential exists on the wire.
     *
     * The handshake is a normal one - same kex, same [PinnedHostKeyRepository]
     * enforcement - but no password is set, no identity is added, and
     * `PreferredAuthentications` is set to a method this client never offers. JSch
     * therefore gets as far as the key exchange and then fails authentication; the
     * host key is already in hand at that point, which is exactly the point.
     *
     * A server that somehow accepts "none" is handled too: the fingerprint is read
     * the same way and the session is dropped immediately either way.
     *
     * @throws JSchException when no host key could be learned at all (unreachable
     *   host, refused connection, timeout). Callers must treat that as "we cannot
     *   verify this host" rather than as permission to connect unverified.
     */
    override fun probeHostKey(connection: SshConnection, timeoutMs: Int): String {
        val session = jsch.getSession(connection.username, connection.host, connection.port)
        session.setHostKeyRepository(PinnedHostKeyRepository(null))
        session.setConfig("StrictHostKeyChecking", "yes")
        // Nothing here authenticates: no setPassword, no addIdentity, and an
        // authentication method list that JSch cannot satisfy.
        session.setConfig("PreferredAuthentications", "none")
        session.setTimeout(timeoutMs)
        try {
            session.connect(timeoutMs)
        } catch (_: JSchException) {
            // Expected: authentication fails AFTER the key exchange delivered the
            // host key. Anything else (DNS, refused, timeout) shows up as no key.
        }
        try {
            if (session.hostKey == null) {
                throw JSchException("could not learn the host key for ${connection.host}")
            }
            // Same computation as the live connection, so the value the user
            // confirmed and the value the real handshake presents are comparable.
            return serverFingerprint(session)
        } finally {
            runCatching { session.disconnect() }
        }
    }

    data class ExecResult(val exitStatus: Int, val stdout: String, val stderr: String)

    /** Run a single remote command through an exec channel and wait for it to finish. */
    fun exec(session: Session, command: String, timeoutMs: Int = 30_000): ExecResult {
        val channel = session.openChannel("exec") as ChannelExec
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        var exitStatus = -1
        try {
            channel.setCommand(command)
            // Drain stdout + stderr CONCURRENTLY (this JSch fork has no blocking
            // Channel.waitFor). Reading both pipes at once is required so a full pipe
            // can't deadlock; the pump threads block on read() and the latch wakes
            // exactly when the command finishes (or the deadline hits).
            val done = java.util.concurrent.CountDownLatch(2)
            fun pump(stream: java.io.InputStream?, sink: ByteArrayOutputStream) {
                Thread {
                    try {
                        if (stream != null) {
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = stream.read(buf)
                                if (n <= 0) break
                                sink.write(buf, 0, n)
                            }
                        }
                    } catch (_: Exception) {
                        // Remote closed / channel disconnected - nothing more to read.
                    } finally {
                        done.countDown()
                    }
                }.apply {
                    isDaemon = true
                    name = "screenlink-exec-pump"
                    start()
                }
            }
            pump(channel.inputStream, out)
            pump(channel.errStream, err)
            channel.connect(timeoutMs)
            // Block (efficiently) until both streams hit EOF or the deadline elapses.
            // The deadline is fixed BEFORE connect so a slow handshake cannot eat the
            // command's own budget.
            val deadline = System.currentTimeMillis() + timeoutMs
            val waited = done.await(
                (deadline - System.currentTimeMillis()).coerceAtLeast(0L),
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
            if (!waited) {
                // Command outlived its budget: close the channel so the blocked pump
                // reads unwind, then give them a moment to finish (no thread leak).
                runCatching { channel.disconnect() }
                done.await(1000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            // Only trust the exit status once the channel is actually closed (both
            // streams can sit at EOF for a transient window while isClosed is still
            // false, which would otherwise read as a bogus exit status -1).
            exitStatus = if (channel.isClosed) channel.exitStatus else -1
        } finally {
            channel.disconnect()
        }
        return ExecResult(
            exitStatus = exitStatus,
            stdout = String(out.toByteArray(), Charsets.UTF_8).trim(),
            stderr = String(err.toByteArray(), Charsets.UTF_8).trim(),
        )
    }

    fun isScreenInstalled(session: Session): Boolean {
        val r = exec(session, "command -v screen")
        return r.exitStatus == 0 && r.stdout.isNotEmpty()
    }

    /** Parse the output of `screen -ls` into a list of sessions. */
    fun listScreenSessions(session: Session): List<ScreenSession> {
        val r = exec(session, ScreenCommands.listSessionsCommand())
        if (r.exitStatus != 0 && r.stdout.contains("No Sockets found")) return emptyList()
        return ScreenSessions.parse(r.stdout)
    }

    /** Create a detached GNU Screen session on the remote host. */
    fun createScreenSession(session: Session, name: String): ExecResult {
        return exec(session, ScreenCommands.createSessionCommand(name))
    }

    /** Gracefully terminate a GNU Screen session (sends `quit` to its process). */
    fun killScreenSession(session: Session, socket: String): ExecResult {
        return exec(session, ScreenCommands.killSessionCommand(socket))
    }

    /** Detach a session without killing it. */
    fun detachScreenSession(session: Session, socket: String): ExecResult {
        return exec(session, ScreenCommands.detachSessionCommand(socket))
    }

    /** Open an interactive pseudo-terminal shell channel, ready for a terminal emulator. */
    @Throws(JSchException::class)
    fun openShell(session: Session, columns: Int, rows: Int, timeoutMs: Int = 15_000): ChannelShell {
        val channel = session.openChannel("shell") as ChannelShell
        channel.setPty(true)
        channel.setPtyType("xterm-256color")
        channel.setPtySize(columns, rows, 0, 0)
        channel.setAgentForwarding(false)
        channel.connect(timeoutMs)
        return channel
    }

    /**
     * Open an SFTP channel (the SSH "subsystem" that carries file transfers).
     *
     * A server without `Subsystem sftp` in sshd_config fails here with a
     * "subsystem request failed" JSchException, which the transfer layer turns
     * into a plain-language message instead of a stack trace.
     */
    @Throws(JSchException::class)
    override fun openSftp(session: Session, timeoutMs: Int): ChannelSftp {
        val channel = session.openChannel("sftp") as ChannelSftp
        try {
            channel.connect(timeoutMs)
        } catch (e: Exception) {
            runCatching { channel.disconnect() }
            throw e
        }
        return channel
    }
}
