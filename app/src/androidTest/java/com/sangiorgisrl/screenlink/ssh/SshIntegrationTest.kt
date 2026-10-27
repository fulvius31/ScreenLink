package com.sangiorgisrl.screenlink.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.LargeTest
import com.sangiorgisrl.screenlink.data.SshConnection
import com.sangiorgisrl.screenlink.data.SshKeyCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end SSH tests against a live server. They target the local test sshd at
 * 10.0.2.2:2222 (root/screenlink, plus throwaway keys in assets/keys). If the
 * server is not reachable the tests are skipped, not failed.
 *
 * These exercise the app's real connection path, but with the decrypted credential
 * DTOs ([SshConnection] / [SshKeyCredential]) that production passes to
 * [SshClient.connect] - exactly the plain material the repository produces.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SshIntegrationTest {

    private val host = "10.0.2.2"
    private val port = 2222
    private val ssh = SshClient()

    private fun keyFromAssets(name: String): String =
        InstrumentationRegistry.getInstrumentation().context
            .assets.open("keys/$name").bufferedReader().readText()

    private fun conn(password: String = "screenlink") = SshConnection(
        id = 0L,
        displayName = "it",
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
    } catch (e: Exception) {
        false
    }

    @Before
    fun ensureServer() {
        assumeTrue("Test SSH server at $host:$port is not reachable - skipping", serverReachable())
    }

    @Test
    fun passwordAuthConnectsAndRunsCommands() {
        // Password is supplied as the (decrypted) connection password - like the app.
        val session = ssh.connect(conn(), keys = emptyList())
        assertNotNull(session)
        val r = ssh.exec(session, "echo PWDOK")
        assertEquals("PWDOK", r.stdout)
        ssh.disconnectQuietly(session)
    }

    @Test
    fun publicKeyAuthConnectsWithoutAnyPassword() {
        // No password: must authenticate purely with the key.
        val key = keyCredential("it-ed25519", keyFromAssets("test_ed25519"))
        val session = ssh.connect(conn(password = ""), keys = listOf(key))
        assertNotNull(session)
        val r = ssh.exec(session, "whoami")
        assertEquals("root", r.stdout)
        ssh.disconnectQuietly(session)
    }

    @Test
    fun passphraseProtectedKeyConnects() {
        val key = keyCredential("it-rsa", keyFromAssets("test_rsa_pass"), passphrase = "pass123")
        val session = ssh.connect(conn(password = ""), keys = listOf(key))
        assertNotNull(session)
        val r = ssh.exec(session, "id -un")
        assertEquals("root", r.stdout)
        ssh.disconnectQuietly(session)
    }

    @Test
    fun screenLifecycleCreateListKill() {
        val key = keyCredential("it-ed25519", keyFromAssets("test_ed25519"))
        val session = ssh.connect(conn(), keys = listOf(key))
        val name = "it-test-${System.currentTimeMillis() % 100000}"
        try {
            val created = ssh.createScreenSession(session, name)
            assertEquals("create should succeed", 0, created.exitStatus)

            val listed = ScreenSessions.parse(ssh.exec(session, ScreenCommands.listSessionsCommand()).stdout)
            assertTrue("expected to find $name in $listed", listed.any { it.name == name })

            val kill = ssh.killScreenSession(session, listed.first { it.name == name }.socket)
            assertEquals("kill should succeed", 0, kill.exitStatus)
        } finally {
            ssh.disconnectQuietly(session)
        }
    }

    private fun SshClient.disconnectQuietly(session: com.jcraft.jsch.Session?) {
        if (session != null) { try { session.disconnect() } catch (_: Exception) {} }
    }
}
