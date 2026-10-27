package com.sangiorgisrl.screenlink.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.data.SshConnection
import com.sangiorgisrl.screenlink.i18n.UiText
import com.sangiorgisrl.screenlink.data.SshKeyCredential
import com.sangiorgisrl.screenlink.transfer.CountingInputStream
import com.sangiorgisrl.screenlink.transfer.LocalSink
import com.sangiorgisrl.screenlink.transfer.LocalSource
import com.sangiorgisrl.screenlink.transfer.ProgressListener
import com.sangiorgisrl.screenlink.transfer.RemotePaths
import com.sangiorgisrl.screenlink.transfer.SftpTransport
import com.sangiorgisrl.screenlink.transfer.friendlyTransferError
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end SFTP tests - the "scp" feature - against the same live test sshd as
 * [SshIntegrationTest] (10.0.2.2:2222). Skipped, not failed, when the server is
 * down.
 *
 * They run the production transport: [SshClient.openSftp] over a pinned session,
 * wrapped in [SftpTransport], so byte counting, directory listing, the cancel
 * path and the error wording are exercised against a real OpenSSH server rather
 * than a fake.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SftpIntegrationTest {

    private val host = "10.0.2.2"
    private val port = 2222
    private val ssh = SshClient()

    private fun keyFromAssets(name: String): String =
        InstrumentationRegistry.getInstrumentation().context
            .assets.open("keys/$name").bufferedReader().readText()

    private fun conn(password: String = "") = SshConnection(
        id = 0L,
        displayName = "it-sftp",
        username = "root",
        host = host,
        port = port,
        password = password,
        screenSessionName = null,
        serverFingerprint = null,
    )

    private fun keyCredential(name: String, pem: String, passphrase: String = "") = SshKeyCredential(
        id = 0L,
        name = name,
        privateKey = pem,
        passphrase = passphrase,
    )

    private fun serverReachable(): Boolean = try {
        ssh.connect(conn()).let { session -> ssh.exec(session, "echo ok"); session.disconnect() }
        true
    } catch (_: Exception) {
        false
    }

    @Before
    fun ensureServer() {
        assumeTrue("Test SSH server at $host:$port is not reachable - skipping", serverReachable())
    }

    /** A live session + SFTP channel + the transport the app actually uses. */
    private class Live(val session: Session, val channel: ChannelSftp, val transport: SftpTransport)

    private fun openLive(): Live {
        val session = ssh.connect(
            conn(),
            keys = listOf(keyCredential("it-ed25519", keyFromAssets("test_ed25519"))),
        )
        val channel = ssh.openSftp(session, 15_000)
        return Live(session, channel, SftpTransport(session, channel))
    }

    private fun Session.execQuiet(command: String) {
        runCatching { ssh.exec(this, command) } // cleanup is best-effort
    }

    private fun source(bytes: ByteArray) = object : LocalSource {
        override val sizeBytes: Long = bytes.size.toLong()
        override fun open(): InputStream = ByteArrayInputStream(bytes)
    }

    private fun sink(name: String, buffer: ByteArrayOutputStream) = object : LocalSink {
        override val targetName: String = name
        override fun open(): OutputStream = buffer
    }

    /** Counts what flowed; cancels as soon as [cancelAfterBytes] is reached. */
    private class Tally(private val cancelAfterBytes: Long = Long.MAX_VALUE) : ProgressListener {
        val seen = AtomicLong(0L)
        override fun onBytes(transferred: Long) {
            seen.set(transferred)
        }

        override fun isCancelled(): Boolean = seen.get() >= cancelAfterBytes
    }

    @Test
    fun homeAndListingAreUsableByTheBrowser() {
        val live = openLive()
        try {
            val home = live.transport.home()
            assertTrue("home must be absolute, was '$home'", RemotePaths.isAbsolute(home))
            assertTrue("pwd must be absolute too", RemotePaths.isAbsolute(live.channel.pwd()))

            val entries = live.transport.list(home)
            assertTrue("no dot entries may leak into the UI", entries.none { it.name == "." || it.name == ".." })
            assertTrue("every entry needs an absolute path", entries.all { RemotePaths.isAbsolute(it.path) })
            assertTrue(
                "listed paths must be children of the folder that was listed",
                entries.all { RemotePaths.parent(it.path)?.trimEnd('/') == home.trimEnd('/') },
            )
        } finally {
            runCatching { live.transport.close() }
        }
    }

    @Test
    fun mkdirUploadListDownloadRoundTripsBytes() {
        val live = openLive()
        val dir = RemotePaths.join(live.transport.home(), "screenlink-it-${System.currentTimeMillis()}")
        try {
            live.transport.mkdir(dir)
            assertTrue("a new folder lists empty", live.transport.list(dir).isEmpty())

            // Deterministic payload, bigger than one SFTP window so the counter
            // has to be called repeatedly.
            val payload = ByteArray(200_000) { (it * 31 and 0xff).toByte() }
            val remote = RemotePaths.join(dir, "payload.bin")
            val up = Tally()
            live.transport.upload(source(payload), remote, up)
            assertEquals("upload progress must reach the full size", payload.size.toLong(), up.seen.get())

            val listed = live.transport.list(dir).single { it.name == "payload.bin" }
            assertEquals("the server reports the real size", payload.size.toLong(), listed.sizeBytes)
            assertTrue("regular files are copyable", listed.isCopyable)
            assertTrue("modified time must be populated", listed.modifiedAtMillis > 0L)

            val back = ByteArrayOutputStream()
            val down = Tally()
            live.transport.download(remote, sink("payload.bin", back), down)
            val got = back.toByteArray()
            assertEquals("payload size must survive the round trip", payload.size, got.size)
            var firstDiff = -1
            for (i in payload.indices) {
                if (payload[i] != got[i]) {
                    firstDiff = i
                    break
                }
            }
            assertTrue("payload differs at byte $firstDiff", firstDiff < 0)
            assertEquals("download progress must reach the full size", payload.size.toLong(), down.seen.get())
        } finally {
            live.session.execQuiet("rm -rf $dir")
            runCatching { live.transport.close() }
        }
    }

    @Test
    fun uploadIntoMissingDirectoryMapsToNotFound() {
        val live = openLive()
        try {
            val missing = RemotePaths.join(live.transport.home(), "nope-${System.currentTimeMillis()}")
            val failure = runCatching {
                live.transport.upload(
                    source(byteArrayOf(1, 2, 3)),
                    RemotePaths.join(missing, "x.bin"),
                    Tally(),
                )
            }.exceptionOrNull()
            assertNotNull("uploading into a missing folder must fail", failure)
            // The user must be told what went wrong in words they can act on, never
            // a bare SFTP code. (OpenSSH answers NO_SUCH_FILE; a server that just
            // says FAILURE still has to land on the wording with the reason in it.)
            val text = friendlyTransferError(failure!!)
            assertTrue(
                "unexpected mapping: $failure -> $text",
                text is UiText.Resource &&
                    (text.id == R.string.err_transfer_not_found || text.id == R.string.err_transfer_failed),
            )
        } finally {
            runCatching { live.transport.close() }
        }
    }

    @Test
    fun cancellingAnUploadStopsWithinOneBuffer() {
        val live = openLive()
        val dir = RemotePaths.join(live.transport.home(), "screenlink-it-cancel-${System.currentTimeMillis()}")
        try {
            live.transport.mkdir(dir)
            val payload = ByteArray(400_000)
            // JSch pumps whatever stream it is handed, so wrapping the source in the
            // app's counting stream reproduces the real cancel path.
            val counted = Tally(cancelAfterBytes = 32_768L)
            val failure = runCatching {
                live.transport.upload(
                    object : LocalSource {
                        override val sizeBytes: Long = payload.size.toLong()
                        override fun open(): InputStream =
                            CountingInputStream(ByteArrayInputStream(payload), counted)
                    },
                    RemotePaths.join(dir, "cancel-me.bin"),
                    Tally(),
                )
            }.exceptionOrNull()
            assertNotNull("a cancelled upload must abort", failure)
            val text = friendlyTransferError(failure!!)
            assertTrue(
                "a cancel must never read as a failure: $failure -> $text",
                text is UiText.Resource && text.id == R.string.err_transfer_cancelled,
            )
            assertTrue(
                "cancel must land early: wrote ${counted.seen.get()} of ${payload.size}",
                counted.seen.get() < payload.size / 2,
            )
        } finally {
            live.session.execQuiet("rm -rf $dir")
            runCatching { live.transport.close() }
        }
    }

    @Test
    fun closingTheTransportDropsChannelAndSession() {
        val live = openLive()
        assertTrue(live.channel.isConnected)
        assertTrue(live.session.isConnected)
        live.transport.close()
        assertTrue("the SFTP channel must be gone", !live.channel.isConnected)
        assertTrue("the SSH session must not be left hanging", !live.session.isConnected)
    }

    @Test
    fun listingAMissingPathFailsWithAMappedCode() {
        val live = openLive()
        try {
            val failure = runCatching {
                live.transport.list(
                    RemotePaths.join(live.transport.home(), "ghost-${System.currentTimeMillis()}"),
                )
            }.exceptionOrNull()
            assertNotNull(failure)
            if (failure is SftpException) {
                assertEquals(ChannelSftp.SSH_FX_NO_SUCH_FILE, failure.id)
            }
            val text = friendlyTransferError(failure!!)
            assertTrue(
                "unexpected mapping: $failure -> $text",
                text is UiText.Resource && text.id == R.string.err_transfer_not_found,
            )
        } finally {
            runCatching { live.transport.close() }
        }
    }
}
