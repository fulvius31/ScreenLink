package com.sangiorgisrl.screenlink.ssh

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The trust policy is only worth what its call graph is.
 *
 * [SshConnector] decides whether a password or private key may leave the device.
 * That decision is worthless the moment a feature holds an [SshClient] and calls
 * `connect` on it directly - which is exactly how this code used to be written, with
 * the policy duplicated inline in two places and only one of them tested. These
 * checks are source scans rather than mocks because the regression is a call site,
 * and no amount of unit testing a fake gateway can notice a call that goes around it.
 */
class SshCallPathTest {

    private val javaDir: File by lazy { File(findRepoRoot(), "app/src/main/java") }

    private val sources by lazy {
        javaDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun relative(file: File) = file.relativeTo(File(findRepoRoot(), "app/src/main")).path.replace(File.separatorChar, '/')

    @Test
    fun `nobody connects through the ssh client directly`() {
        val offenders =
            sources.filter { it.readText().contains("sshClient.connect(") }.map { relative(it) }.toList()
        assertTrue(offenders.isEmpty(), "a feature is bypassing SshConnector, and with it the host-key policy: $offenders")
    }

    @Test
    fun `only the connector drives the gateway connect`() {
        val allowed = setOf("java/com/sangiorgisrl/screenlink/ssh/SshConnector.kt")
        val offenders =
            sources.filter { relative(it) !in allowed }
                .filter { it.readText().contains("ssh.connect(") }
                .map { relative(it) }
                .toList()
        assertTrue(offenders.isEmpty(), "SshGateway.connect must be driven from SshConnector alone: $offenders")
    }

    @Test
    fun `the ssh gateway type stays inside the ssh package`() {
        // Handing the raw gateway to a feature class is the same bypass with a new
        // name: the gateway has a connect() that knows nothing about trust.
        val offenders =
            sources.filterNot { relative(it).startsWith("java/com/sangiorgisrl/screenlink/ssh/") }
                .filter { it.readText().contains("SshGateway") }
                .map { relative(it) }
                .toList()
        assertTrue(offenders.isEmpty(), "SshGateway leaks outside the ssh package: $offenders")
    }

    @Test
    fun `the three entry points really do go through the connector`() {
        // The scan above passes trivially if the connector is simply unused, so pin
        // the positive half: terminal, overview and file transfer each name it.
        val namesConnector = Regex("[sS]shConnector")
        val users = sources.filter { namesConnector.containsMatchIn(it.readText()) }.map { relative(it) }.toSet()
        for (entry in listOf(
            "java/com/sangiorgisrl/screenlink/session/TerminalSessionService.kt",
            "java/com/sangiorgisrl/screenlink/ui/viewmodel/OverviewViewModel.kt",
            "java/com/sangiorgisrl/screenlink/transfer/SftpTransport.kt",
        )) {
            assertTrue(entry in users, "$entry must connect through SshConnector, found: $users")
        }
    }

    private fun findRepoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main").isDirectory) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("could not locate the repository root from ${File("").absolutePath}")
    }
}
