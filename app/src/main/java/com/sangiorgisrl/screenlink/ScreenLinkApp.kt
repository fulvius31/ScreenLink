package com.sangiorgisrl.screenlink

import android.content.Context
import android.app.Application
import com.jcraft.jsch.JSch
import com.sangiorgisrl.screenlink.data.ConnectionDatabase
import com.sangiorgisrl.screenlink.data.ConnectionRepository
import com.sangiorgisrl.screenlink.i18n.AppLocale
import com.sangiorgisrl.screenlink.ssh.SshClient
import com.sangiorgisrl.screenlink.ssh.HostKeyTrustPrompt
import com.sangiorgisrl.screenlink.ssh.SshConnector
import com.sangiorgisrl.screenlink.transfer.ContentLocalFiles
import com.sangiorgisrl.screenlink.transfer.SftpTransportFactory
import com.sangiorgisrl.screenlink.transfer.ShareInbox
import com.sangiorgisrl.screenlink.transfer.TransferForeground
import com.sangiorgisrl.screenlink.transfer.TransferProgressService
import com.sangiorgisrl.screenlink.transfer.TransferStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class ScreenLinkApp : Application() {

    val database: ConnectionDatabase by lazy { ConnectionDatabase.getInstance(this) }
    val repository: ConnectionRepository by lazy {
        ConnectionRepository(database.connectionDao(), database.sshKeyDao())
    }
    val sshClient: SshClient by lazy { SshClient() }

    /**
     * The first-contact host-key question, answered by whichever screen is on
     * screen (see [com.sangiorgisrl.screenlink.ssh.HostKeyTrustPrompt]).
     */
    val hostKeyTrustPrompt: HostKeyTrustPrompt by lazy { HostKeyTrustPrompt() }

    /**
     * Connect + host-key pin policy in one place (terminal, overview, transfers).
     * Every connection in the app is started through this object: the trust policy
     * is only worth having if there is exactly one way to connect.
     */
    val sshConnector: SshConnector by lazy {
        SshConnector(
            ssh = sshClient,
            rebindFingerprint = { id, fingerprint -> repository.rebindFingerprint(id, fingerprint) },
            confirmation = hostKeyTrustPrompt,
        )
    }

    /**
     * Application-lifetime scope for work that must not die with a screen: file
     * copies and the copies staged from the share sheet.
     */
    val appScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    // --- file transfer ("scp") ------------------------------------------------
    val localFiles: ContentLocalFiles by lazy { ContentLocalFiles(this) }
    val transferTransports: SftpTransportFactory by lazy { SftpTransportFactory(sshConnector) }

    /**
     * The live copy queue. Application-scoped on purpose (like
     * [com.sangiorgisrl.screenlink.session.LiveTerminalStore]): a copy halfway
     * through a large file survives rotation, navigation and the user peeking at
     * another screen.
     */
    val transfers: TransferStore by lazy {
        TransferStore(repository, localFiles, transferTransports, appScope, transferForeground)
    }

    /**
     * What the queue calls the moment work lands in it: put the process under a
     * foreground service so the system cannot reclaim it halfway through a file.
     *
     * Started from the UI thread that queued the copy (Android 12+ only allows a
     * foreground-service start while the app is visible), and it stops itself again
     * once the queue is idle - see [TransferProgressService].
     */
    val transferForeground: TransferForeground by lazy {
        TransferForeground { runCatching { TransferProgressService.start(this) } }
    }

    /**
     * Apply the app's own language before any resource is read. On Android 13+ the
     * platform already injected it (wrap() is a no-op); on 8.1-12 this is the only
     * place a language choice can take effect.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        bootstrapCryptoAndSsh()
        // A share-sheet grant cannot outlive the process, so staged copies left
        // behind by an earlier run are unreachable garbage in the cache.
        ShareInbox.pruneStaged(this)
    }

    private fun bootstrapCryptoAndSsh() {
        // Register BouncyCastle so SSH ciphers/kexes missing from the stock Android
        // provider (e.g. chacha20-poly1305, curve25519) can still be negotiated.
        try {
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        } catch (_: Throwable) {
            // BouncyCastle unavailable - stock providers will still cover common ciphers.
        }

        // Explicit, broad cipher/kex/mac sets for talking to a wide range of servers.
        try {
            JSch.setConfig("server_host_key",
                "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa")
            JSch.setConfig("kex",
                "curve25519-sha256,curve25519-sha256@libssh.org,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group-exchange-sha256,diffie-hellman-group16-sha512,diffie-hellman-group18-sha512,diffie-hellman-group14-sha256,diffie-hellman-group14-sha1")
            JSch.setConfig("cipher.s2c",
                "aes128-ctr,aes192-ctr,aes256-ctr,aes128-cbc,aes192-cbc,aes256-cbc,3des-cbc,chacha20-poly1305@openssh.com")
            JSch.setConfig("cipher.c2s",
                "aes128-ctr,aes192-ctr,aes256-ctr,aes128-cbc,aes192-cbc,aes256-cbc,3des-cbc,chacha20-poly1305@openssh.com")
            JSch.setConfig("mac.s2c", "hmac-sha2-256,hmac-sha2-512,hmac-sha1,hmac-sha1-96")
            JSch.setConfig("mac.c2s", "hmac-sha2-256,hmac-sha2-512,hmac-sha1,hmac-sha1-96")
            // Key types the client is willing to present to the server (rsa-sha2 needed for modern OpenSSH).
            JSch.setConfig("pubkey.accept.types",
                "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa")
            JSch.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
            JSch.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
        } catch (_: Throwable) {
            // Fall back to JSch defaults.
        }
    }
}
