package com.sangiorgisrl.screenlink.speech

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which of Android's two speech recognizers an attempt should bind.
 *
 * Android 13 split the API in two: the ordinary recognizer (which may recognise on
 * the phone or over the network, depending on what the device has installed) and
 * `createOnDeviceSpeechRecognizer`, whose model is guaranteed to be on the phone.
 * The choice matters twice over - it is what makes dictation work in airplane mode,
 * and it is the difference between "the audio stayed on this device" and "the audio
 * may have been uploaded" - so the rule is a pure function with a test rather than
 * an inline `if` inside the Android wrapper.
 */
class RecognizerChoiceTest {

    @Test
    fun `the on-device recognizer is used when the device really has one`() {
        // Android 13 (API 33) is where the API exists; asking an older device for it
        // would not compile, and asking a device that reports none fails the attempt.
        assertTrue(preferOnDevice(sdkInt = 33, onDeviceAvailable = true))
        assertTrue(preferOnDevice(sdkInt = 36, onDeviceAvailable = true))
    }

    @Test
    fun `the on-device recognizer is never asked for on a device that cannot do it`() {
        assertFalse(preferOnDevice(sdkInt = 26, onDeviceAvailable = true), "no such API below 33")
        assertFalse(preferOnDevice(sdkInt = 32, onDeviceAvailable = true), "no such API below 33")
        assertFalse(preferOnDevice(sdkInt = 34, onDeviceAvailable = false), "nothing installed to bind")
    }

    @Test
    fun `a language the on-device model lacks falls back to the full recognizer`() {
        // The on-device model only covers the language packs that were downloaded.
        // Staying on it would fail the same way on every retry, while the ordinary
        // recognizer can still do the language.
        assertTrue(backendAfterError(RecognizerBackend.OnDevice, SpeechError.LanguageUnavailable))
    }

    @Test
    fun `every other failure keeps the same recognizer`() {
        // Switching engines for a microphone, network or busy failure hides the real
        // cause and would fail identically anyway.
        listOf(
            SpeechError.Audio, SpeechError.Network, SpeechError.Busy, SpeechError.RecognizerBusy,
            SpeechError.NoSpeech, SpeechError.NoMatch, SpeechError.Client, SpeechError.Server,
            SpeechError.Permission, SpeechError.PermissionSettings, SpeechError.Unavailable,
            SpeechError.Unknown,
        ).forEach {
            assertFalse(
                backendAfterError(RecognizerBackend.OnDevice, it),
                "$it must not silently swap the recognizer",
            )
        }
        // Already on the full recognizer: there is nothing further to fall back to.
        assertFalse(backendAfterError(RecognizerBackend.Cloud, SpeechError.LanguageUnavailable))
    }
}
