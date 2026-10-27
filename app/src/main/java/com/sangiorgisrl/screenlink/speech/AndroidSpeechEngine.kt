package com.sangiorgisrl.screenlink.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * The device's own speech recognizer, wrapped in [SpeechEngine].
 *
 * Deliberately the framework API and not the `ACTION_RECOGNIZE_SPEECH` activity:
 * the terminal screen shows no system UI at all (the IME is switched off and the
 * keyboard is part of the layout), and a full-screen "listening" dialog would cover
 * the very prompt the user is dictating into. The API keeps the terminal visible and
 * streams partial results, which is what makes the live transcript possible.
 *
 * No audio ever leaves through this app: the microphone is opened by the system
 * recognizer, ScreenLink never reads the audio buffer, keeps no recording, and
 * prefers the recognizer that runs the model ON THE PHONE - see [createRecognizer].
 *
 * Must be constructed and driven from the main thread (the framework delivers its
 * callbacks there).
 */
class AndroidSpeechEngine(context: Context) : SpeechEngine {

    /** Application context: a dictation must not pin an Activity. */
    private val appContext = context.applicationContext

    private var recognizer: SpeechRecognizer? = null

    /**
     * The attempt whose callbacks are still wanted, and where they go.
     *
     * The recognizer instance is reused on purpose - building a new one per tap
     * rebinds the recognition service - and the framework posts its callbacks to the
     * main looper, so an event queued by the PREVIOUS attempt is delivered to
     * whichever listener happens to be installed when it runs. Without this guard a
     * NO_MATCH left in the queue by the attempt that was just replaced kills the fresh
     * one, and an old `onResults` types the previous sentence a second time.
     */
    private var attempt: Int = 0

    private var target: SpeechEngine.Listener? = null

    /**
     * This engine is the platform's own recognizer: on-device model when the device
     * reports one, cloud-capable otherwise. It never runs Gemini Nano - that is
     * [MlKitSpeechEngine]'s job, and [ChainedSpeechEngine] decides which of the two
     * is actually listening.
     */
    override val backend: SpeechBackend get() = SpeechBackend.DeviceRecognizer

    /**
     * Which recognizer the NEXT attempt should build. Starts on the on-device one
     * when the device has it, and steps back to the full recognizer if the
     * on-device model turns out not to cover the app's language.
     */
    private var recognizerKind: RecognizerBackend = RecognizerBackend.Auto

    /**
     * Dictation is offered when EITHER recognizer exists: some devices (and most
     * stripped-down emulators) have one and not the other.
     */
    override fun isAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(appContext) ||
            (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
                )

    override fun start(listener: SpeechEngine.Listener) {
        // Any previous utterance is over: cancel() is also what makes the framework
        // accept a new startListening() instead of answering ERROR_RECOGNIZER_BUSY.
        cancel()
        val engine = recognizer ?: createRecognizer(appContext, recognizerKind)
        if (engine == null) {
            listener.onError(SpeechError.Unavailable)
            return
        }
        recognizer = engine
        attempt++
        target = listener
        engine.setRecognitionListener(ForwardingListener { onFrameworkError(it) })
        runCatching { engine.startListening(recognizerIntent(appContext)) }
            .onFailure {
                target = null
                listener.onError(SpeechError.Unavailable)
            }
    }

    /**
     * Builds the recognizer for one attempt.
     *
     * Android 13+ separates the two: [SpeechRecognizer.createOnDeviceSpeechRecognizer]
     * binds the recognizer whose acoustic model is already on the phone, which is
     * what makes dictation work with no network at all and keeps the audio from
     * ever leaving the device. Older releases (and devices that only have the
     * cloud-capable recognizer) get the ordinary one, still asked to prefer offline
     * through [RecognizerIntent.EXTRA_PREFER_OFFLINE].
     */
    private fun createRecognizer(context: Context, wanted: RecognizerBackend): SpeechRecognizer? {
        val onDeviceReady = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val useOnDevice = wanted == RecognizerBackend.Auto &&
            preferOnDevice(Build.VERSION.SDK_INT, onDeviceReady)
        if (useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            recognizerKind = RecognizerBackend.OnDevice
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()?.let {
                return it
            }
        }
        recognizerKind = RecognizerBackend.Cloud
        return runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        // Detach first: anything this session still delivers afterwards belongs to an
        // attempt that is over.
        target = null
        recognizer?.cancel()
    }

    override fun release() {
        target = null
        val engine = recognizer ?: return
        recognizer = null
        runCatching {
            engine.setRecognitionListener(null)
            engine.destroy()
        }
    }

    /**
     * Reacts to a framework failure before the user is told about it.
     *
     * The on-device model only carries the language packs that were downloaded. When
     * it answers "this language is not available", the next tap has to go through the
     * full recognizer, or every attempt fails identically forever.
     */
    private fun onFrameworkError(error: SpeechError) {
        if (!backendAfterError(recognizerKind, error)) return
        recognizerKind = RecognizerBackend.Cloud
        val held = recognizer ?: return
        recognizer = null
        runCatching {
            held.setRecognitionListener(null)
            held.destroy()
        }
    }

    /**
     * The recognition request.
     *
     * [RecognizerIntent.EXTRA_LANGUAGE] follows the language the app is DRAWN in, not
     * the system language: an app set to Spanish dictates in Spanish even on an
     * English device. [RecognizerIntent.EXTRA_PREFER_OFFLINE] asks the engine to use
     * its on-device model when it has one, which is both faster and keeps the audio
     * on the phone where possible.
     */
    private fun recognizerIntent(context: Context): Intent {
        val tag = speechLanguageTag(context.resources.configuration.locales[0])
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
    }

    /** Framework callbacks -> the small [SpeechEngine.Listener] vocabulary. */
    private inner class ForwardingListener(
        /** Lets the owner react to a raw code before it is reported (recognizer swap). */
        private val onFailed: (SpeechError) -> Unit,
    ) : RecognitionListener {

        /** The attempt this listener was installed for. */
        private val id = attempt

        /** False once another attempt started or this one was thrown away. */
        private fun live(): Boolean = id == attempt && target != null

        override fun onReadyForSpeech(params: Bundle?) {
            if (live()) target?.onReady()
        }

        override fun onBeginningOfSpeech() = Unit

        /** Level meter: not drawn, and reading it is not worth a callback per frame. */
        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            if (!live()) return
            val mapped = speechErrorForCode(error)
            // Only a live attempt may swap recognizers too: a stale language error
            // must not silently change which recognizer the NEXT attempt builds.
            onFailed(mapped)
            target?.onError(mapped)
        }

        override fun onResults(results: Bundle?) {
            if (!live()) return
            target?.onFinal(best(results))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!live()) return
            val text = best(partialResults)
            if (text.isNotEmpty()) target?.onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        /** The engine ranks its own hypotheses; the first is the one to type. */
        private fun best(bundle: Bundle?): String {
            val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return ""
            return list.firstOrNull().orEmpty()
        }
    }
}

/**
 * The locale tag the recognizer is asked to dictate in.
 *
 * `Locale.toString()` gives the legacy `es_ES` form, which older engines want;
 * `toLanguageTag()` gives `es-ES`, which newer ones want and which is never empty
 * for a real locale - so the tag is preferred and the legacy form is the fallback.
 */
internal fun speechLanguageTag(locale: Locale): String =
    locale.toLanguageTag().ifEmpty { locale.toString() }

/**
 * The two recognizers Android offers, as a choice.
 *
 * [OnDevice] is the API 33+ recognizer whose model lives on the phone; [Cloud] is
 * the long-standing recognizer, which may recognise on the phone or over the
 * network depending on what the device has installed.
 */
internal enum class RecognizerBackend { Auto, OnDevice, Cloud }

/**
 * Whether to bind the on-device recognizer.
 *
 * `createOnDeviceSpeechRecognizer` only exists on Android 13+, and only works when
 * the device actually reports an on-device recognizer - asking for one otherwise
 * means a dictation that fails before the user says a word.
 */
internal fun preferOnDevice(sdkInt: Int, onDeviceAvailable: Boolean): Boolean =
    sdkInt >= 33 && onDeviceAvailable

/**
 * Whether a failure means "this recognizer cannot do the app's language", so the
 * next attempt should be made through the other one.
 *
 * Only the language errors qualify: a network or microphone failure would repeat
 * itself, and switching recognizers there just hides the real cause.
 */
internal fun backendAfterError(backend: RecognizerBackend, error: SpeechError): Boolean =
    backend == RecognizerBackend.OnDevice &&
        error == SpeechError.LanguageUnavailable
