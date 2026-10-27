package com.sangiorgisrl.screenlink

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The release build is shrunk, so the only thing protecting the parts of the app
 * that are reached by NAME rather than by code is `app/proguard-rules.pro`.
 *
 * A shrinker failure in this app does not show up as a build error: it shows up as
 * the first SSH connection failing on a user's phone, because JSch resolves its
 * algorithm table and BouncyCastle resolves its JCA engine classes through
 * `Class.forName`. These checks exist so deleting a rule breaks the build instead.
 */
class ReleaseHardeningTest {

    private val repoRoot: File by lazy { findRepoRoot() }

    @Test
    fun `the release build actually runs the shrinker`() {
        val gradle = File(repoRoot, "app/build.gradle.kts").readText()
        val release = gradle.substringAfter("release {").substringBefore("    }")
        assertTrue(
            release.contains("isMinifyEnabled = true"),
            "release must be minified, otherwise dead code ships in the dex",
        )
    }

    @Test
    fun `the rules keep the classes that are resolved by name`() {
        val rules = File(repoRoot, "app/proguard-rules.pro").readText()
        // JSch: algorithm name -> implementation class, resolved at connect time.
        assertTrue(
            rules.contains("-keep class com.jcraft.jsch.**"),
            "JSch resolves its ciphers/kexes by class name; they must not be shrunk away",
        )
        // BouncyCastle: a JCA provider is a registry of class names.
        assertTrue(
            rules.contains("-keep class org.bouncycastle.jce.provider.BouncyCastleProvider"),
            "the JCA provider is looked up by the platform, not by our code",
        )
        assertTrue(
            rules.contains("-keep class org.bouncycastle.jcajce.provider."),
            "the provider's engine classes are instantiated reflectively by name",
        )
    }

    @Test
    fun `nothing looks resources up by name`() {
        // Resource shrinking is only safe while every resource reference is a real
        // code reference. `getIdentifier` is how that guarantee silently breaks.
        val offenders =
            File(repoRoot, "app/src/main/java").walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .filter { it.readText().contains("getIdentifier(") }
                .map { it.relativeTo(repoRoot).path }
                .toList()
        assertTrue(offenders.isEmpty(), "getIdentifier defeats resource shrinking: $offenders")
    }

    private fun findRepoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "app/build.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("could not locate the repository root from ${File("").absolutePath}")
    }
}
