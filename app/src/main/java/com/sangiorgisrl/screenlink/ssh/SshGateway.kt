package com.sangiorgisrl.screenlink.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import com.sangiorgisrl.screenlink.data.SshConnection
import com.sangiorgisrl.screenlink.data.SshKeyCredential

/**
 * The slice of the SSH client that [SshConnector] drives.
 *
 * Extracted so the host-key trust policy - the part that decides whether a
 * password or private key may leave the device at all - is unit-testable without
 * a server. [SshClient] is the only production implementation.
 */
interface SshGateway {

    /** Full connect: key exchange AND authentication, credentials included. */
    fun connect(
        connection: SshConnection,
        keys: List<SshKeyCredential> = emptyList(),
        timeoutMs: Int = 15_000,
    ): Session

    /**
     * Key exchange only: the server's host-key fingerprint, with no credential
     * ever offered. See [SshClient.probeHostKey].
     */
    fun probeHostKey(connection: SshConnection, timeoutMs: Int = 15_000): String

    fun serverFingerprint(session: Session): String

    fun serverFingerprintLegacy(session: Session): String

    fun openSftp(session: Session, timeoutMs: Int = 15_000): ChannelSftp
}
