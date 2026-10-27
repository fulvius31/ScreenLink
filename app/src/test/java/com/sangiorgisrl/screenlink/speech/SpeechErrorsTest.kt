package com.sangiorgisrl.screenlink.speech

import android.speech.SpeechRecognizer
import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.i18n.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The recognizer's error codes, translated into something a person can act on.
 *
 * The framework reports failures as bare ints. Left untranslated, "11" is what the
 * user would read for "the recognizer service dropped the connection" - and the
 * whole reason this layer exists is that each failure has a DIFFERENT fix: speak
 * again, check the network, close the other app holding the microphone, or allow
 * the permission in Settings.
 *
 * The mapping test is deliberately exhaustive: a code that silently lands in
 * [SpeechError.Unknown] still "works", it just stops telling the user anything
 * useful, which is exactly the kind of regression nothing else would catch.
 */
class SpeechErrorsTest {

    /** Every ERROR_* constant the platform can send, with the meaning we promise. */
    private val frameworkCodes: List<Pair<Int, SpeechError>> = listOf(
        SpeechRecognizer.ERROR_AUDIO to SpeechError.Audio,
        SpeechRecognizer.ERROR_CLIENT to SpeechError.Client,
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to SpeechError.Permission,
        SpeechRecognizer.ERROR_NETWORK to SpeechError.Network,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT to SpeechError.Network,
        SpeechRecognizer.ERROR_NO_MATCH to SpeechError.NoMatch,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY to SpeechError.RecognizerBusy,
        SpeechRecognizer.ERROR_SERVER to SpeechError.Server,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT to SpeechError.NoSpeech,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED to SpeechError.LanguageUnavailable,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE to SpeechError.LanguageUnavailable,
        // Added by newer engines; each still has a sentence worth showing.
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED to SpeechError.Network,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS to SpeechError.Busy,
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT to SpeechError.LanguageUnavailable,
        SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS to SpeechError.Unknown,
    )

    @Test
    fun `every framework error code has a meaning`() {
        frameworkCodes.forEach { (code, expected) ->
            assertEquals(expected, speechErrorForCode(code), "code $code mapped wrong")
        }
    }

    @Test
    fun `the framework codes are all distinct so the mapping cannot collapse them`() {
        // Two codes sharing a value would mean the when() above silently swallowed a
        // branch (and the compiler does not warn about a duplicate when branch).
        val codes = frameworkCodes.map { it.first }
        assertEquals(codes.distinct().size, codes.size, "duplicate error constant: $codes")
    }

    @Test
    fun `a code from a future engine still says something`() {
        assertEquals(SpeechError.Unknown, speechErrorForCode(4242))
        assertEquals(UiText.Resource(R.string.dictate_error_unknown), SpeechError.Unknown.message())
    }

    @Test
    fun `every failure has its own sentence`() {
        // One message for two failures is a bug in disguise: the fix differs, so the
        // user is told to do the wrong thing. Busy/RecognizerBusy are the single
        // deliberate pair - both mean "someone else has the microphone".
        val ids = SpeechError.entries.associateWith { (it.message() as UiText.Resource).id }
        assertTrue(ids.values.all { it != 0 }, "a failure with no message: $ids")

        val duplicates: Set<Set<SpeechError>> = ids.entries
            .groupBy { entry -> entry.value }
            .filterValues { group -> group.size > 1 }
            .map { group -> group.value.map { entry -> entry.key }.toSet() }
            .toSet()
        assertEquals(
            setOf(setOf(SpeechError.Busy, SpeechError.RecognizerBusy)),
            duplicates,
            "these failures share a sentence; add a string if the fix differs",
        )
    }

    @Test
    fun `a failure that cannot get better on its own does not offer a retry`() {
        // "Try again" on a device with no recognizer, or on a permission that was
        // refused for good, hides the real fix behind a loop.
        listOf(SpeechError.Unavailable, SpeechError.Permission, SpeechError.PermissionSettings)
            .forEach { assertFalse(it.isRetryable(), "$it must not offer a retry") }

        listOf(
            SpeechError.NoSpeech, SpeechError.NoMatch, SpeechError.Audio, SpeechError.Network,
            SpeechError.Server, SpeechError.Client, SpeechError.Busy, SpeechError.RecognizerBusy,
            SpeechError.LanguageUnavailable, SpeechError.Unknown,
        ).forEach { assertTrue(it.isRetryable(), "$it should be retryable") }
    }

    @Test
    fun `the two busy failures read the same to the user`() {
        assertEquals(SpeechError.Busy.message(), SpeechError.RecognizerBusy.message())
        assertNotEquals(SpeechError.Busy, SpeechError.RecognizerBusy)
    }

    // ---- RECORD_AUDIO ---------------------------------------------------------

    @Test
    fun `the permission decision table`() {
        // Granted -> listen. Refused once -> the system will show the dialog again.
        // Refused with "don't ask again" -> only Settings can undo it, and asking
        // again would silently do nothing at all.
        assertEquals(PermissionStep.Grant, recordAudioStep(hasPermission = true, canAskAgain = true))
        assertEquals(PermissionStep.Grant, recordAudioStep(hasPermission = true, canAskAgain = false))
        assertEquals(PermissionStep.Ask, recordAudioStep(hasPermission = false, canAskAgain = true))
        assertEquals(PermissionStep.OpenSettings, recordAudioStep(hasPermission = false, canAskAgain = false))
    }

    // ---- the language the recognizer is asked in ------------------------------

    @Test
    fun `the recognizer is asked in the language the app is drawn in`() {
        // A tag, not Locale.toString(): older engines want "es_ES", newer ones want
        // "es-ES", and toLanguageTag() is the form that is never empty.
        assertEquals("es-ES", speechLanguageTag(java.util.Locale.forLanguageTag("es-ES")))
        assertEquals("en-US", speechLanguageTag(java.util.Locale.forLanguageTag("en-US")))
        assertEquals("fr", speechLanguageTag(java.util.Locale.forLanguageTag("fr")))
    }
}
