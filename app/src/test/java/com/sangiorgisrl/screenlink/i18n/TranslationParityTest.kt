package com.sangiorgisrl.screenlink.i18n

import java.io.File
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/**
 * Translation completeness, checked on the JVM instead of at runtime.
 *
 * A missing translation is invisible in the language you are developing in and
 * silently falls back to English, so every guard here is a build-time one:
 *  - every string in the default `values/` exists in every locale folder;
 *  - no locale carries a string the app stopped using;
 *  - placeholders (`%1$s`) and plural quantities match, so a locale can never
 *    crash a `getString(id, arg)` call or hide a branch;
 *  - `res/xml/locales_config.xml` (which drives the Android 13+ per-app Languages
 *    page) lists exactly the languages [AppLocale.SUPPORTED] offers in the picker.
 */
class TranslationParityTest {

    private data class Bundle(
        /** name -> raw text, for placeholder checks. */
        val strings: Map<String, String>,
        /** name -> the set of plural quantities it defines. */
        val plurals: Map<String, Set<String>>,
    ) {
        val names: Set<String> get() = strings.keys + plurals.keys
    }

    private val resDir: File by lazy { findResDir() }

    private val default: Bundle by lazy { readBundle(File(resDir, "values")) }

    /** Locale folders that hold translations, e.g. `values-es` -> "es". */
    private val localeTags: List<String> by lazy {
        resDir.listFiles()
            ?.mapNotNull { f ->
                val m = NAME_ONLY.matchEntire(f.name)
                if (f.isDirectory && m != null && m.groupValues[1] != "night") m.groupValues[1] else null
            }
            ?.sorted()
            ?: emptyList()
    }

    @Test
    fun `the app has at least one translation beyond the default`() {
        assertTrue(
            localeTags.isNotEmpty(),
            "expected at least one values-<tag> folder, found ${resDir.nameList()}",
        )
        assertEquals(
            AppLocale.SUPPORTED.filterNot { it == DEFAULT_TAG }.sorted(),
            localeTags,
            "a locale folder must be offered in the language picker, and vice versa",
        )
    }

    @Test
    fun `every locale translates every string`() {
        localeTags.forEach { tag ->
            val bundle = readBundle(File(resDir, "values-$tag"))
            val missing = default.names - bundle.names
            assertTrue(
                missing.isEmpty(),
                "values-$tag is missing ${missing.size} string(s): ${missing.sorted()}",
            )
        }
    }

    @Test
    fun `no locale keeps a string the app no longer has`() {
        localeTags.forEach { tag ->
            val bundle = readBundle(File(resDir, "values-$tag"))
            val extra = bundle.names - default.names
            assertTrue(
                extra.isEmpty(),
                "values-$tag has ${extra.size} unused string(s): ${extra.sorted()}",
            )
        }
    }

    @Test
    fun `placeholders match the default text`() {
        localeTags.forEach { tag ->
            val bundle = readBundle(File(resDir, "values-$tag"))
            val bad = buildString {
                default.strings.forEach { (name, text) ->
                    val translated = bundle.strings[name] ?: return@forEach
                    val expected = specifiers(text)
                    val actual = specifiers(translated)
                    if (expected != actual) {
                        append("\n$name: default=$expected $tag=$actual")
                    }
                }
            }
            assertTrue(bad.isEmpty(), "placeholder mismatch in values-$tag:$bad")
        }
    }

    /**
     * A string that uses `%2$s` but never `%1$s` is nearly always a placeholder that
     * a rewrite or a translation dropped: the formatter silently ignores the argument
     * that went missing, so the UI just loses a hostname or a number and nothing else
     * here would notice. Lint warns about this (StringFormatCount) but a warning does
     * not fail a build, so the same rule is asserted here.
     */
    @Test
    fun `format strings use their arguments in order`() {
        val bundles = LinkedHashMap<String, Bundle>()
        bundles["values"] = default
        localeTags.forEach { bundles["values-$it"] = readBundle(File(resDir, "values-$it")) }
        val bad = buildString {
            bundles.forEach { (folder, bundle) ->
                bundle.strings.forEach { (name, text) ->
                    val skipped = skippedIndexes(text)
                    if (skipped.isNotEmpty()) {
                        append("\n$folder/$name skips argument(s) $skipped: ${specifiers(text)}")
                    }
                }
            }
        }
        assertTrue(
            bad.isEmpty(),
            "positional placeholders must be used 1..n with no gap:$bad",
        )
    }

    @Test
    fun `every plural quantity the default uses is translated`() {
        localeTags.forEach { tag ->
            val bundle = readBundle(File(resDir, "values-$tag"))
            val bad = buildString {
                default.plurals.forEach { (name, quantities) ->
                    val actual = bundle.plurals[name] ?: return@forEach
                    // A locale may need MORE classes than English (Spanish has a
                    // "many" class), but never fewer: a missing class silently
                    // falls back to "other" and mis-inflects that number.
                    val missing = quantities - actual
                    if (missing.isNotEmpty()) {
                        append("\n$name: missing $missing (default=$quantities, $tag=$actual)")
                    }
                }
            }
            assertTrue(bad.isEmpty(), "plural quantity mismatch in values-$tag:$bad")
        }
    }

    /**
     * The queue badge is a bare number on screen, so the only way a screen reader
     * hears it at all is the icon's own label ("Show transfers: 3 copies in
     * progress"). If that label loses its placeholder, or a plural stops naming the
     * number, the count silently disappears from the spoken UI while the screen
     * still looks correct - and nothing else here would notice.
     */
    @Test
    fun `the transfers badge is spoken with its number`() {
        val label = default.strings["files_open_transfers_desc"]
        assertTrue(
            label != null && specifiers(label) == listOf("%1\$s"),
            "files_open_transfers_desc has to wrap the spoken badge: \"$label\"",
        )
        val texts = pluralTexts(File(resDir, "values"))
        listOf("files_badge_active", "files_badge_failed").forEach { name ->
            val items = texts[name].orEmpty()
            assertTrue(
                items.isNotEmpty() && items.all { specifiers(it).contains("%1\$d") },
                "$name must say the number in every quantity: ${texts[name]}",
            )
        }
    }

    /**
     * The per-app "Languages" entry on Android 13+ only lists what
     * `locales_config.xml` declares, so it must agree with the in-app picker.
     */
    @Test
    fun `locales_config declares exactly the supported languages`() {
        val file = File(resDir, "xml/locales_config.xml")
        assertTrue(file.isFile, "missing ${file.path}")
        val declared = parse(file).let { doc ->
            doc.getElementsByTagName("locale").let { nodes ->
                (0 until nodes.length).mapNotNull { i ->
                    (nodes.item(i) as? Element)?.let { e ->
                        // The attribute is namespaced (android:name), so ask for it both
                        // ways: DOM only returns the plain name if the file omits the prefix.
                        e.getAttributeNS(ANDROID_NS, "name")
                            .ifEmpty { e.getAttribute("android:name") }
                            .takeIf { it.isNotEmpty() }
                    }
                }
            }
        }
        assertEquals(AppLocale.SUPPORTED, declared, "locales_config order/content drifted")
    }

    // ---- helpers -------------------------------------------------------------

    private fun readBundle(dir: File): Bundle {
        assertTrue(dir.isDirectory, "no such resource folder: ${dir.path}")
        val strings = LinkedHashMap<String, String>()
        val plurals = LinkedHashMap<String, Set<String>>()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                val doc = parse(file)
                doc.getElementsByTagName("string").let { nodes ->
                    (0 until nodes.length).forEach { i ->
                        val e = nodes.item(i) as? Element ?: return@forEach
                        strings[e.getAttribute("name")] = e.textContent
                    }
                }
                doc.getElementsByTagName("plurals").let { nodes ->
                    (0 until nodes.length).forEach { i ->
                        val e = nodes.item(i) as? Element ?: return@forEach
                        val quantities = e.getElementsByTagName("item").let { items ->
                            (0 until items.length).mapNotNull { j ->
                                (items.item(j) as? Element)?.getAttribute("quantity")
                                    ?.takeIf { it.isNotEmpty() }
                            }.toSet()
                        }
                        plurals[e.getAttribute("name")] = quantities
                    }
                }
            }
        return Bundle(strings, plurals)
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply {
        // Resources are trusted source, but never let a parser fetch anything.
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(file)

    /** name -> the raw text of every quantity item, for wording checks. */
    private fun pluralTexts(dir: File): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                val doc = parse(file)
                doc.getElementsByTagName("plurals").let { nodes ->
                    (0 until nodes.length).forEach { i ->
                        val e = nodes.item(i) as? Element ?: return@forEach
                        val quantities = e.getElementsByTagName("item").let { items ->
                            (0 until items.length).mapNotNull { j ->
                                (items.item(j) as? Element)?.textContent
                            }
                        }
                        out[e.getAttribute("name")] = quantities
                    }
                }
            }
        return out
    }

    /** Argument indexes a string leaves unused, e.g. `%2$s` alone skips 1. */
    private fun skippedIndexes(text: String): List<Int> {
        val used = POSITIONAL.findAll(text).map { it.groupValues[1].toInt() }.toSet()
        if (used.isEmpty()) return emptyList()
        return (1..used.max()).filterNot { it in used }
    }

    /** `%1$s` style placeholders, in order, with the argument index kept. */
    private fun specifiers(text: String): List<String> =
        SPECIFIER.findAll(text).map { it.value }.toList()

    /** Unit tests run with the module dir as cwd; walk up so IDE runs work too. */
    private fun findResDir(): File {
        val candidates = sequence {
            var dir: File? = Paths.get("").toAbsolutePath().toFile()
            while (dir != null) {
                yield(File(dir, "src/main/res"))
                yield(File(dir, "app/src/main/res"))
                dir = dir.parentFile
            }
        }
        return candidates.firstOrNull { File(it, "values/strings.xml").isFile }
            ?: throw IllegalStateException("could not locate src/main/res from ${Paths.get("").toAbsolutePath()}")
    }

    private fun File.nameList(): String = list()?.sorted()?.joinToString(", ") ?: "<empty>"

    private companion object {
        const val DEFAULT_TAG = "en"
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        val NAME_ONLY = Regex("""values-([a-zA-Z0-9-]+)""")
        val SPECIFIER = Regex("""%(\d+\$)?[a-zA-Z]""")
        val POSITIONAL = Regex("""%(\d+)\$[a-zA-Z]""")
    }
}
