package com.sangiorgisrl.screenlink.speech

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rules behind "advanced mode where the device has it".
 *
 * Gemini Nano is not a switch: it exists on some phones, needs a large download, and
 * is shared between apps. So the app asks for it first and keeps the recognizers
 * below it, and the two decisions that make that work - which engines to build, and
 * when a failure means "ask the next one" - are pure functions with tests here rather
 * than `if`s inside the Android wrappers.
 */
class AsrChainPolicyTest {

    @Test
    fun `the best recognizer is tried first and the universal one second`() {
        assertEquals(
            listOf(AsrEngineKind.MlKitAdvanced, AsrEngineKind.Framework, AsrEngineKind.MlKitBasic),
            asrEngineOrder(sdkInt = 36, frameworkAvailable = true),
        )
    }

    @Test
    fun `a device the library does not support only gets the platform recognizer`() {
        // Android 11 and below: no ML Kit GenAI at all, and dictation must still work.
        assertEquals(
            listOf(AsrEngineKind.Framework),
            asrEngineOrder(sdkInt = 30, frameworkAvailable = true),
        )
    }

    @Test
    fun `a device with no platform recognizer still gets the model engines`() {
        // Stripped-down devices (and most emulators) have no platform recognizer, but a
        // recent Android can still run ML Kit's own on-device model.
        assertEquals(
            listOf(AsrEngineKind.MlKitAdvanced, AsrEngineKind.MlKitBasic),
            asrEngineOrder(sdkInt = 36, frameworkAvailable = false),
        )
    }

    @Test
    fun `a device with nothing at all gets an empty chain`() {
        // Old Android and no recognizer: the strip then says dictation is not available
        // here instead of opening a microphone that cannot work.
        assertEquals(emptyList<AsrEngineKind>(), asrEngineOrder(sdkInt = 30, frameworkAvailable = false))
    }

    @Test
    fun `failures about the engine step down to the next one`() {
        // Each of these is a property of ONE recognizer: AICore missing or outdated,
        // the model's language pack absent, the service down or busy. The next engine
        // may well do the dictation.
        listOf(
            SpeechError.Unavailable, SpeechError.LanguageUnavailable, SpeechError.Network,
            SpeechError.Server, SpeechError.Busy, SpeechError.NeedsSystemUpdate,
        ).forEach {
            assertTrue(handsOffToNextEngine(it), "$it should not end the attempt")
        }
    }

    @Test
    fun `failures about the microphone or the user do not`() {
        // Stepping through three recognizers for a blocked permission, an unusable mic
        // or a full disk replaces one honest message with three confusing ones, and
        // every engine would answer the same way.
        listOf(
            SpeechError.NoSpeech, SpeechError.NoMatch, SpeechError.Audio, SpeechError.Client,
            SpeechError.RecognizerBusy, SpeechError.Permission,
            SpeechError.PermissionSettings, SpeechError.NoStorage, SpeechError.Unknown,
        ).forEach {
            assertFalse(handsOffToNextEngine(it), "$it must be reported as it is")
        }
    }
}
