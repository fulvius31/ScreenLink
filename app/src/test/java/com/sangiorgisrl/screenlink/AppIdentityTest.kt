package com.sangiorgisrl.screenlink

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The app must be self-contained and must not advertise anybody else.
 *
 * The terminal emulator and the terminal view are compiled into this APK as ordinary
 * application code, so nothing in the shipped sources may name another app: no foreign
 * package/namespace, no resource named after another product, no "install X" wording.
 * Apache-2.0 section 4 still requires crediting where the vendored code came from, so the
 * upstream project is named in the `NOTICE`, in one `package-info.java` per vendored
 * package, in the change notice each vendored source file carries, and in the attribution
 * asset that ships inside the APK. That is the line this test pins: the credit lives in
 * comments and notices, while package names, identifiers, resource names and shipped
 * strings stay brand-neutral.
 */
class AppIdentityTest {

    private val repoRoot: File by lazy { findRepoRoot() }
    private val mainDir: File by lazy { File(repoRoot, "app/src/main") }

    private val vendoredPackages =
        listOf(
            "com.sangiorgisrl.screenlink.terminal.emulator",
            "com.sangiorgisrl.screenlink.terminal.view",
            "com.sangiorgisrl.screenlink.terminal.view.textselection",
        )

    /** Package ids that belong to other applications; none of them may appear. */
    private val foreignAppIds =
        listOf(
            "com.termux",
            "com.google.android.inputmethod",
            "org.pocketworkstation",
            "com.connectbot",
            "jackpal.androidterm",
        )

    private val textExtensions = setOf("java", "kt", "xml", "pro", "json", "txt", "png", "webp")

    @Test
    fun `no shipped file uses another app's package id`() {
        val offenders =
            sourceFiles().flatMap { file ->
                val text = file.readTextSafe()
                foreignAppIds.filter { text.contains(it) }.map { "$it in ${relative(file)}" }
            }.toList()
        assertTrue(offenders.isEmpty(), "foreign app ids found:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `the upstream project is named in comments and notices only, never in code`() {
        // The credit is allowed; a package, import, identifier or shipped string naming
        // another app is not. Stripping comments is what makes that distinction checkable -
        // the shipped attribution asset is exempt because it is prose by design.
        val offenders =
            sourceFiles()
                .filterNot { relative(it) in ATTRIBUTION_FILES }
                .filter { file -> codeOnly(file.readTextSafe()).contains("termux", ignoreCase = true) }
                .map { relative(it) }
                .toList()
        assertTrue(offenders.isEmpty(), "the upstream project may only be credited in comments:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `only the vendored packages and the shipped notice credit the upstream project`() {
        val offenders =
            sourceFiles()
                .filter { it.readTextSafe().contains("termux", ignoreCase = true) }
                .filterNot { relative(it).startsWith(VENDORED_DIR) || relative(it) in ATTRIBUTION_FILES }
                .map { relative(it) }
                .toList()
        assertTrue(offenders.isEmpty(), "attribution belongs in the vendored packages and the shipped notice:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `every vendored source file carries the change notice`() {
        // Apache-2.0 section 4(b): modified files must say so prominently.
        val missing =
            vendoredSources()
                .filterNot { it.readTextSafe().contains("Apache") }
                .map { relative(it) }
                .toList()
        assertTrue(missing.isEmpty(), "files without the licence/change notice:\n" + missing.joinToString("\n"))
    }

    @Test
    fun `the scan is not silently empty`() {
        // Every check above passes on an empty file set, so pin the scan itself.
        assertTrue(sourceFiles().count() > 60, "the source scan found almost nothing: ${sourceFiles().count()} files")
        assertTrue(vendoredSources().count() >= 15, "the vendored packages look empty: ${vendoredSources().count()} files")
    }

    @Test
    fun `the shipped strings never mention another app`() {
        val offenders =
            resFiles().flatMap { file ->
                val text = file.readTextSafe()
                (listOf("termux") + foreignAppIds)
                    .filter { text.contains(it, ignoreCase = true) }
                    .map { "$it in ${relative(file)}" }
            }.toList()
        assertTrue(offenders.isEmpty(), "resource files must stay brand-neutral:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `no resource is named after another app`() {
        // File names are checked for every file under res/, not just the xml ones: a
        // drawable called termux_icon.png would otherwise slip through the xml-only walk.
        val offenders =
            File(mainDir, "res").walkTopDown().filter { it.isFile }.flatMap { file ->
                val declared =
                    if (file.extension.lowercase() == "xml") {
                        Regex("""<(\w+)[^>]*\bname="([^"]+)"""").findAll(file.readTextSafe())
                            .map { it.groupValues[2] }.toList()
                    } else {
                        emptyList<String>()
                    }
                (listOf(file.name, file.parentFile?.name.orEmpty()) + declared)
                    .filter { it.contains("termux", ignoreCase = true) }
                    .map { "$it in ${relative(file)}" }
            }.toList()
        assertTrue(offenders.isEmpty(), "resource names must not carry another app's name:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `every source file declares a package inside this app namespace`() {
        // Structural rather than a denylist: the denylist only catches names someone
        // thought to list, this catches any package that is not this app's own.
        val javaRoot = File(mainDir, "java")
        val offenders =
            javaRoot.walkTopDown()
                .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
                .map { file ->
                    val declared = PACKAGE.find(file.readTextSafe())?.groupValues?.get(1)
                    if (declared == null || !declared.startsWith(APP_NAMESPACE)) {
                        "$declared in ${relative(file)}"
                    } else {
                        null
                    }
                }
                .filterNotNull()
                .toList()
        assertTrue(offenders.isEmpty(), "sources must live under $APP_NAMESPACE:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `no source directory sits outside this app namespace`() {
        // The reverse of the package-declaration check: a stray com/termux/... tree would be
        // caught here even if every file inside it declared some other package.
        val javaRoot = File(mainDir, "java")
        val offenders =
            javaRoot.walkTopDown()
                .filter { it.isDirectory && it != javaRoot }
                .map { it.relativeTo(javaRoot).path.replace(File.separatorChar, '.') }
                // Either this app's own package, or a leading segment of it (com/,
                // com/sangiorgisrl/) on the way down.
                .filterNot { it.startsWith(APP_NAMESPACE) || APP_NAMESPACE.startsWith(it) }
                .toList()
        assertTrue(offenders.isEmpty(), "the source tree must not carry another app's package: $offenders")
    }

    @Test
    fun `the terminal code lives inside this app's own namespace`() {
        val terminalDir = File(mainDir, "java/com/sangiorgisrl/screenlink/terminal")
        assertTrue(terminalDir.isDirectory, "expected the vendored code under terminal/")

        val mismatched =
            terminalDir.walkTopDown()
                .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
                .filter { file ->
                    val declared = PACKAGE.find(file.readTextSafe())?.groupValues?.get(1)
                    val expected = file.parentFile?.relativeTo(File(mainDir, "java"))?.path?.replace(File.separatorChar, '.')
                    declared != expected
                }.toList()
        assertTrue(mismatched.isEmpty(), "package declaration must match the directory:\n" + mismatched.joinToString("\n"))
    }

    @Test
    fun `there is no stray termux directory in the sources`() {
        val offenders =
            File(repoRoot, "app/src").walkTopDown()
                .filter { it.isDirectory && it.name.equals("termux", ignoreCase = true) }
                .map { relative(it) }
                .toList()
        assertTrue(offenders.isEmpty(), "found directory named after another app: $offenders")
    }

    @Test
    fun `every vendored package carries its own attribution`() {
        for (pkg in vendoredPackages) {
            val info = File(File(mainDir, "java/" + pkg.replace('.', '/')), "package-info.java")
            assertTrue(info.isFile, "missing attribution file for $pkg")
            val text = info.readTextSafe()
            assertTrue(text.contains("Apache"), "$pkg: attribution must name the licence")
            assertTrue(text.contains("v0.118.0"), "$pkg: attribution must name the upstream tag")
            assertTrue(text.startsWith("/**") || text.contains("/**"), "$pkg: attribution must be documented")
        }
    }

    @Test
    fun `the root NOTICE covers the vendored packages`() {
        val notice = File(repoRoot, "NOTICE")
        assertTrue(notice.isFile, "the APK must ship a NOTICE with the third-party credits")
        val text = notice.readTextSafe()
        for (pkg in vendoredPackages) {
            assertTrue(text.contains(pkg), "NOTICE must list $pkg")
        }
        assertTrue(text.contains("Apache License"), "NOTICE must name the licence")
        assertTrue(text.contains("v0.118.0"), "NOTICE must name the upstream tag")
    }

    @Test
    fun `the build files describe only this app`() {
        val buildFiles =
            listOf(
                File(repoRoot, "app/build.gradle.kts"),
                File(repoRoot, "app/proguard-rules.pro"),
                File(repoRoot, "build.gradle.kts"),
                File(repoRoot, "settings.gradle.kts"),
            )
        val gradle = buildFiles.filter { it.isFile }.joinToString("\n") { it.readTextSafe() }
        assertTrue(gradle.contains("com.sangiorgisrl.screenlink"), "the app namespace must be its own")
        assertTrue(
            !gradle.contains("com.termux", ignoreCase = true),
            "build files must not keep rules for another app's package",
        )
    }

    @Test
    fun `the manifest declares no queries for other apps`() {
        val manifest = File(mainDir, "AndroidManifest.xml").readTextSafe()
        assertTrue(!manifest.contains("<queries>"), "the app must not look up other apps on the device")
    }


    // --- the lexer itself ----------------------------------------------------
    //
    // Everything above trusts codeOnly() to tell comments from code, so the
    // trust is pinned directly. A marker word is used instead of the upstream
    // name: what is being tested is the classification, not the vocabulary.

    @Test
    fun `the lexer drops line comments but keeps the code after them`() {
        val code = codeOnly("// NEEDLE\nval KEPT = 1")
        assertTrue(!code.contains("NEEDLE"), "a line comment must not count as code")
        assertTrue(code.contains("KEPT"), "the code after a line comment must survive")
    }

    @Test
    fun `the lexer drops block comments and knows they nest`() {
        // Kotlin nests block comments, so the inner `*/` must not end the outer
        // one: stopping early would leave commented-out text in the scan.
        val code = codeOnly("/* outer /* NEEDLE */ still comment */ val KEPT = 1")
        assertTrue(!code.contains("NEEDLE"), "a nested comment must stay a comment")
        assertTrue(code.contains("KEPT"), "the code after a nested comment must survive")
    }

    @Test
    fun `the lexer survives an unterminated block comment`() {
        val code = codeOnly("val KEPT = 1 /* NEEDLE")
        assertTrue(code.contains("KEPT"), "an unterminated comment must not lose the code before it")
        assertTrue(!code.contains("NEEDLE"), "text inside an open comment is not code")
    }

    @Test
    fun `the lexer keeps string literals because they are code`() {
        val code = codeOnly("val s = \"NEEDLE\"")
        assertTrue(code.contains("NEEDLE"), "a literal is shipped content, so the scan must still see it")
    }

    @Test
    fun `the lexer does not end a literal at an escaped quote`() {
        // A naive scanner closes the string at the escaped quote, then reads the
        // `/*` as a comment and goes blind for the rest of the file.
        val code = codeOnly("val s = \"a\\\"/*\"; val NEEDLE = 1")
        assertTrue(code.contains("NEEDLE"), "an escaped quote must not reopen the scanner in comment mode")
    }

    @Test
    fun `the lexer does not end a char literal at an escaped quote`() {
        val code = codeOnly("val c = '\\''; val NEEDLE = 1")
        assertTrue(code.contains("NEEDLE"), "an escaped quote must not start a second char literal")
    }

    @Test
    fun `the lexer keeps raw strings and ignores comment markers inside them`() {
        // The quote inside the raw string is what makes this bite: a scanner that
        // pairs quotes two by two leaves the `/*` outside any literal, opens a
        // comment there, and goes blind for the rest of the file.
        val code = codeOnly("val banner = " + RAW_QUOTE + "a" + QUOTE + " /* not a comment " + RAW_QUOTE + "\nval NEEDLE = 1")
        assertTrue(code.contains("NEEDLE"), "a comment marker inside a raw string must not swallow the file")
        assertTrue(code.contains("not a comment"), "a raw string is code, so its text is kept")
    }

    @Test
    fun `the lexer keeps a plain string and a raw string side by side`() {
        val code = codeOnly("val a = " + QUOTE + "x" + QUOTE + " val b = " + RAW_QUOTE + "y" + RAW_QUOTE + " val NEEDLE = 1")
        assertTrue(code.contains("NEEDLE"), "quote pairing must not desynchronise after a raw string")
    }

    @Test
    fun `the lexer keeps the closing delimiter of a raw string`() {
        val code = codeOnly("val banner = " + RAW_QUOTE + "a" + RAW_QUOTE + " val KEPT = 1")
        assertTrue(code.contains(RAW_QUOTE), "the delimiters must survive, or the scan no longer mirrors the source")
        assertTrue(code.contains("KEPT"))
    }

    // --- helpers -------------------------------------------------------------

    private fun sourceFiles(): Sequence<File> =
        mainDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in textExtensions }
            .filterNot { it.extension.lowercase() in setOf("png", "webp") }

    /** The sources of the vendored terminal packages. */
    private fun vendoredSources(): Sequence<File> =
        File(mainDir, "java/" + VENDORED_DIR.removePrefix("app/src/main/java/")).walkTopDown()
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }

    /**
     * The file with its comments removed, string literals kept: enough to tell "the
     * upstream project is credited" from "the upstream project is part of the code".
     *
     * Deliberately a small state machine rather than a regex, because the two mistakes
     * it can make pull in opposite directions and both matter:
     *  - treating a string as a comment hides real code from the scan (a false pass);
     *  - treating a comment as code fails the build over a credit that is correctly
     *    placed, which trains people to delete the guard.
     *
     * So it knows Kotlin raw strings (`"""..."""`), escapes inside quoted literals,
     * char literals, and the fact that block comments NEST in Kotlin - all three of
     * which a naive scan mis-parses. The lexer is pinned by its own tests below.
     */
    private fun codeOnly(text: String): String {
        val out = StringBuilder()
        var i = 0
        var blockDepth = 0
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
            when {
                blockDepth > 0 -> when {
                    c == '/' && next == '*' -> { blockDepth++; i += 2 }
                    c == '*' && next == '/' -> { blockDepth--; i += 2 }
                    else -> i++
                }
                c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && next == '*' -> { blockDepth = 1; i += 2 }
                text.startsWith(RAW_QUOTE, i) -> {
                    // A raw string is code, kept verbatim: it has no escapes, so only
                    // a closing triple quote can end it.
                    out.append(RAW_QUOTE)
                    i += RAW_QUOTE.length
                    while (i < text.length && !text.startsWith(RAW_QUOTE, i)) {
                        out.append(text[i]); i++
                    }
                    if (i < text.length) { out.append(RAW_QUOTE); i += RAW_QUOTE.length }
                }
                c == '"' || c == '\'' -> {
                    out.append(c)
                    i++
                    while (i < text.length && text[i] != c) {
                        if (text[i] == '\\' && i + 1 < text.length) {
                            out.append(text[i]); i++
                        }
                        out.append(text[i]); i++
                    }
                    if (i < text.length) { out.append(text[i]); i++ }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun resFiles(): Sequence<File> =
        File(mainDir, "res").walkTopDown().filter { it.isFile && it.extension.lowercase() == "xml" }

    private fun relative(file: File): String = file.relativeTo(repoRoot).path.replace(File.separatorChar, '/')

    private fun File.readTextSafe(): String = readText(StandardCharsets.UTF_8)

    /** Unit tests run with the module dir as cwd; walk up so IDE runs work too. */
    private fun findRepoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main").isDirectory && (File(dir, "settings.gradle.kts").isFile || File(dir, "settings.gradle").isFile)) {
                return dir
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("could not locate the repository root from ${File("").absolutePath}")
    }

    private companion object {
        val PACKAGE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)

        /** A Kotlin raw string delimiter. */
        /** One double quote, as it appears in source. */
        const val QUOTE = "\""
        const val RAW_QUOTE = "\"\"\""

        const val APP_NAMESPACE = "com.sangiorgisrl.screenlink"

        /** Where the vendored code - and therefore the attribution - is allowed to live. */
        const val VENDORED_DIR = "app/src/main/java/com/sangiorgisrl/screenlink/terminal/"

        /**
         * Prose that ships inside the APK and is allowed to name the upstream project:
         * Apache-2.0 requires the credit to travel with the artifact. ShippedNoticeTest is
         * what pins what these actually say.
         */
        val ATTRIBUTION_FILES = setOf(
            "app/src/main/assets/NOTICE.txt",
            "app/src/main/assets/LICENSE-Apache-2.0.txt",
        )
    }
}
