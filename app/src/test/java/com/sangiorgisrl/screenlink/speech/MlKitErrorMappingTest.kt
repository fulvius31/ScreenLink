package com.sangiorgisrl.screenlink.speech

import com.google.mlkit.genai.common.GenAiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ML Kit's GenAI error codes translated into sentences the user can act on.
 *
 * The GenAI SDK reports ~15 distinct failures as bare ints, and most of them are
 * normal on a real phone: another app holding the shared model, a device without
 * AICore, an unlocked bootloader, a disk too full for the model, an AICore older than
 * the library. Each needs a different fix, so the mapping is a pure function with a
 * test rather than a `when` buried in the wrapper - and a code that arrives unmapped
 * has to be asserted as [SpeechError.Unknown] on purpose, not by accident.
 */
class MlKitErrorMappingTest {

    @Test
    fun `a device without the model service falls back instead of failing`() {
        // No AICore (any phone that is not a Pixel 10/11), an unlocked bootloader, or
        // background use: all mean "this engine is not here", which is what lets
        // ChainedSpeechEngine step down to a recognizer that works.
        listOf(
            GenAiException.ErrorCode.NOT_AVAILABLE,
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED,
        ).forEach { assertEquals(SpeechError.Unavailable, speechErrorForGenAiCode(it)) }
    }

    @Test
    fun `the two failures with their own fix get their own sentence`() {
        // "Free some space" and "update the system" are not things a retry fixes, and
        // neither belongs in the generic "dictation stopped".
        assertEquals(
            SpeechError.NoStorage,
            speechErrorForGenAiCode(GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE),
        )
        assertEquals(
            SpeechError.NeedsSystemUpdate,
            speechErrorForGenAiCode(GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE),
        )
        // A full disk cannot be fixed by the next engine either, so no retry loop.
        assertFalse(SpeechError.NoStorage.isRetryable())
        assertTrue(SpeechError.NeedsSystemUpdate.isRetryable())
    }

    @Test
    fun `another app in front of the shared model is a busy recognizer`() {
        // AICore serves one client at a time, so this is the most common failure on a
        // supported device - and the honest answer is "try again in a moment".
        assertEquals(SpeechError.Busy, speechErrorForGenAiCode(GenAiException.ErrorCode.BUSY))
    }

    @Test
    fun `a model that answered nothing usable is a no-match, not a broken app`() {
        listOf(
            GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR,
            GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR,
        ).forEach { assertEquals(SpeechError.NoMatch, speechErrorForGenAiCode(it)) }
        // Too little audio reached the model: the honest answer is "nothing was said".
        assertEquals(
            SpeechError.NoSpeech,
            speechErrorForGenAiCode(GenAiException.ErrorCode.REQUEST_TOO_SMALL),
        )
    }

    @Test
    fun `a request that never reached the service is a server failure`() {
        listOf(
            GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR,
            GenAiException.ErrorCode.REQUEST_TOO_LARGE,
            GenAiException.ErrorCode.CACHE_PROCESSING_ERROR,
        ).forEach { assertEquals(SpeechError.Server, speechErrorForGenAiCode(it)) }
    }

    @Test
    fun `codes with no honest sentence stay unknown`() {
        // The per-app quota codes would need to name the app that used the quota, and
        // CANCELLED is our own stop() coming back around. Inventing a cause here would
        // send the user looking in the wrong place.
        listOf(
            GenAiException.ErrorCode.UNKNOWN,
            GenAiException.ErrorCode.CANCELLED,
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
            GenAiException.ErrorCode.INVALID_INPUT_IMAGE,
            // A code from a newer alpha than this app was built against.
            9999,
        ).forEach { assertEquals(SpeechError.Unknown, speechErrorForGenAiCode(it)) }
    }

    @Test
    fun `every mapped code is reachable`() {
        // Guards the mapping against a silent fall-through: each code the alpha
        // declares is asserted somewhere, so renaming one breaks a test.
        val mapped = listOf(
            GenAiException.ErrorCode.NOT_AVAILABLE, GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED, GenAiException.ErrorCode.BUSY,
            GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE,
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
            GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR,
            GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR,
            GenAiException.ErrorCode.REQUEST_TOO_SMALL,
            GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR,
            GenAiException.ErrorCode.REQUEST_TOO_LARGE, GenAiException.ErrorCode.CACHE_PROCESSING_ERROR,
            GenAiException.ErrorCode.CANCELLED, GenAiException.ErrorCode.UNKNOWN,
        )
        assertEquals(mapped.size, mapped.toSet().size, "a code is listed twice")
    }

    @Test
    fun `the library is only asked for on an Android it supports`() {
        // ML Kit's GenAI floor is Android 12 (API 31); below it the engine is not even
        // built, so the device never spends an attempt discovering that.
        assertTrue(mlKitSpeechSupported(31))
        assertTrue(mlKitSpeechSupported(36))
        assertFalse(mlKitSpeechSupported(26), "minSdk: the library cannot bind anything here")
        assertFalse(mlKitSpeechSupported(30))
    }
}
