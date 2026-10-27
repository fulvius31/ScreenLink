package com.sangiorgisrl.screenlink

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The attributions must travel with the shipped artifact, not just with the sources.
 *
 * Apache-2.0 section 4 conditions redistribution of the vendored terminal code on passing on
 * the licence text and the "these files were modified" notices. A repository `NOTICE` is not
 * part of an APK, and javadoc (`package-info.java`, the per-file change notices) is stripped
 * at compile time - so without an explicit asset, the installed app credits nobody at all.
 * That is the gap this test closes: it pins the files that `merge*Assets` puts into the
 * package, and keeps them from drifting away from the repository's own `NOTICE`/`LICENSE`.
 *
 * [ShippedNoticeInstrumentedTest] closes the loop on a device by reading the same files back
 * through AssetManager out of the installed package.
 */
class ShippedNoticeTest {

    private val repoRoot: File by lazy { findRepoRoot() }
    private val assetsDir: File by lazy { File(repoRoot, "app/src/main/assets") }

    @Test
    fun `the package ships a notice and a licence asset`() {
        assertTrue(File(assetsDir, NOTICE_ASSET).isFile, "the APK would ship no $NOTICE_ASSET")
        assertTrue(File(assetsDir, LICENCE_ASSET).isFile, "the APK would ship no $LICENCE_ASSET")
    }

    @Test
    fun `the shipped licence text is the repository licence verbatim`() {
        assertArrayEquals(
            File(repoRoot, "LICENSE").readBytes(),
            File(assetsDir, LICENCE_ASSET).readBytes(),
            "$LICENCE_ASSET must be a verbatim copy of the repository LICENSE, so the two cannot disagree",
        )
        val licence = File(assetsDir, LICENCE_ASSET).readTextSafe()
        assertTrue(licence.contains("Apache License, Version 2.0"), "$LICENCE_ASSET is not the Apache-2.0 text")
        assertTrue(
            licence.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"),
            "$LICENCE_ASSET must carry the whole licence, not a pointer to a website",
        )
    }

    @Test
    fun `the shipped notice credits the vendored code properly`() {
        val notice = File(assetsDir, NOTICE_ASSET).readTextSafe()
        for (pkg in VENDORED_PACKAGES) {
            assertTrue(notice.contains(pkg), "$NOTICE_ASSET must name the vendored package $pkg")
        }
        // The three things section 4 is actually about: who, which licence, which version.
        assertTrue(notice.contains("Termux"), "$NOTICE_ASSET must name the upstream project")
        assertTrue(notice.contains("Apache License, Version 2.0"), "$NOTICE_ASSET must name the licence")
        assertTrue(notice.contains("v0.118.0"), "$NOTICE_ASSET must name the upstream tag")
        assertTrue(
            notice.contains("rewritten in Kotlin"),
            "$NOTICE_ASSET must say the files were modified, as section 4(b) requires",
        )
        assertTrue(
            notice.contains("not affiliated with") || notice.contains("not affiliated"),
            "$NOTICE_ASSET must make clear the credit is not an endorsement or a dependency",
        )
    }

    @Test
    fun `the shipped notice and the repository notice agree on the facts`() {
        // Two files will drift unless the shared facts are pinned. Byte equality is the wrong
        // check here - the shipped one is written for a recipient, the repository one for a
        // developer - so every substantive claim has to appear in both.
        val shipped = File(assetsDir, NOTICE_ASSET).readTextSafe()
        val repository = File(repoRoot, "NOTICE").readTextSafe()
        for (fact in SHARED_FACTS) {
            assertTrue(repository.contains(fact), "the repository NOTICE does not state '$fact'")
            assertTrue(shipped.contains(fact), "$NOTICE_ASSET does not state '$fact'")
        }
    }

    @Test
    fun `the build does not exclude the attribution assets`() {
        // AGP packages app/src/main/assets by default; the only way it stops shipping is an
        // explicit packaging exclude, so that is the thing worth pinning.
        val buildFile = File(repoRoot, "app/build.gradle.kts").readTextSafe()
        val excludesAssets = Regex("""exclude\s*\(\s*"[^"]*assets""").containsMatchIn(buildFile)
        assertTrue(!excludesAssets, "app/build.gradle.kts must not exclude the assets the attributions ship in")
    }

    @Test
    fun `the shipped notice does not advertise another application`() {
        // The credit names the upstream project; it must not read as "this app needs that app".
        val notice = File(assetsDir, NOTICE_ASSET).readTextSafe()
        for (forbidden in listOf("com.termux", "install Termux", "requires Termux", "download Termux")) {
            assertTrue(!notice.contains(forbidden, ignoreCase = true), "$NOTICE_ASSET must not say '$forbidden'")
        }
    }

    // --- helpers -------------------------------------------------------------

    private fun File.readTextSafe(): String = readText(StandardCharsets.UTF_8)

    /** Unit tests run with the module dir as cwd; walk up so IDE runs work too. */
    private fun findRepoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main").isDirectory &&
                (File(dir, "settings.gradle.kts").isFile || File(dir, "settings.gradle").isFile)
            ) {
                return dir
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("could not locate the repository root from ${File("").absolutePath}")
    }

    private companion object {
        const val NOTICE_ASSET = "NOTICE.txt"
        const val LICENCE_ASSET = "LICENSE-Apache-2.0.txt"

        val VENDORED_PACKAGES = listOf(
            "com.sangiorgisrl.screenlink.terminal.emulator",
            "com.sangiorgisrl.screenlink.terminal.view",
            "com.sangiorgisrl.screenlink.terminal.view.textselection",
        )

        /** Facts both notices must state, so neither can quietly fall out of date. */
        val SHARED_FACTS = listOf(
            "termux/termux-app",
            "v0.118.0",
            "Apache License, Version 2.0",
            "terminal-emulator",
            "terminal-view",
            "com.sangiorgisrl.screenlink.terminal.emulator",
            "com.sangiorgisrl.screenlink.terminal.view.textselection",
        )
    }
}
