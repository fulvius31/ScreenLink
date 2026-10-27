package com.sangiorgisrl.screenlink.speech

import android.content.Context
import android.os.Build
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ML Kit's GenAI speech recognition, wrapped in [SpeechEngine].
 *
 * This is the "advanced mode" path: [SpeechRecognizerOptions.Mode.MODE_ADVANCED]
 * runs the on-device Gemini model (Gemini Nano) through the AICore system service,
 * which is a much better dictation than the platform recognizer - it punctuates, it
 * keeps up with continuous speech, and the audio never leaves the phone. It is also
 * the most fragile path in the app, which is why everything here is defensive:
 *
 *  - the library is **alpha** (1.0.0-alpha1) with no compatibility promise;
 *  - Advanced mode needs AICore, which today means Pixel 10 / Pixel 11 - and NOT a
 *    device with an unlocked bootloader, where AICore refuses to run;
 *  - the model may not be on the device, so the first attempt can spend minutes
 *    downloading it, and can fail because the disk is full;
 *  - AICore shares one model between apps, so another app holding it is a normal,
 *    expected failure;
 *  - inference is only allowed while the app is in the foreground (the terminal
 *    screen is, by construction, since dictation is cancelled when the screen goes).
 *
 * Every one of those arrives as a [GenAiException] error code or a [FeatureStatus],
 * and each is translated into a [SpeechError] the user can act on - or into a
 * hand-off to the next engine in [ChainedSpeechEngine].
 *
 * Must be driven from the main thread; callbacks are delivered there.
 */
class MlKitSpeechEngine(
    context: Context,
    /** [SpeechRecognizerOptions.Mode.MODE_ADVANCED] (Gemini Nano) or MODE_BASIC. */
    private val mode: Int = SpeechRecognizerOptions.Mode.MODE_ADVANCED,
) : SpeechEngine {

    private val appContext = context.applicationContext

    override val backend: SpeechBackend =
        if (mode == SpeechRecognizerOptions.Mode.MODE_ADVANCED) {
            SpeechBackend.Advanced
        } else {
            SpeechBackend.OnDeviceModel
        }

    /**
     * Main-thread scope for the recognition attempt.
     *
     * The SDK's API is coroutine/Flow based, and [SpeechEngine] promises its callbacks
     * on the main thread, so the whole attempt runs on Main.immediate rather than
     * hopping back from a background collector.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var job: Job? = null

    /** The client is reused across attempts: creating one binds the AICore service. */
    private var client: SpeechRecognizer? = null

    /** The locale [client] was built for, so a language change rebuilds it. */
    private var clientLocale: String? = null

    /**
     * Android 12 (API 31) is the floor the library declares; below it the SDK cannot
     * even bind what it needs, so dictation should not be offered through this engine
     * at all (the framework recognizer still can be).
     */
    override fun isAvailable(): Boolean = mlKitSpeechSupported(Build.VERSION.SDK_INT)

    override fun start(listener: SpeechEngine.Listener) {
        // One attempt at a time: a new tap replaces whatever was in flight. The
        // coroutine of the old one is dropped here, but the SERVICE-side stop is
        // awaited inside the new coroutine below: Dispatchers.Main.immediate starts
        // each launch undispatched when already on the main thread, so launching
        // stopRecognition() and then startRecognition() would have them in flight at
        // the same time - AICore answers that with BUSY, or stops the session that
        // just opened.
        job?.cancel()
        job = null
        val engine = clientOrNull()
        if (engine == null) {
            listener.onError(SpeechError.Unavailable)
            return
        }
        job = scope.launch {
            // Checked again next to the call, not only in the engine-order rule: the
            // SDK's microphone entry point is itself annotated API 31, and this
            // wrapper can be constructed directly rather than through
            // dictationEngines().
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                listener.onError(SpeechError.Unavailable)
                return@launch
            }
            try {
                // Let go of the previous utterance before asking for the next one.
                // A no-op (swallowed) when nothing is running.
                runCatching { engine.stopRecognition() }
                if (!awaitModel(engine, listener)) return@launch
                // The model is ready and the mic is about to open: this is the point
                // where the strip stops saying "getting ready".
                listener.onReady()
                // The SDK's own mic source: ScreenLink never opens AudioRecord or
                // reads a buffer, the recognition service captures the audio.
                val request = SpeechRecognizerRequest.Builder().apply {
                    audioSource = AudioSource.fromMic()
                }.build()
                engine.startRecognition(request).collect { response ->
                    when (response) {
                        is SpeechRecognizerResponse.PartialTextResponse ->
                            listener.onPartial(response.text)

                        is SpeechRecognizerResponse.FinalTextResponse ->
                            listener.onFinal(response.text)

                        is SpeechRecognizerResponse.ErrorResponse ->
                            listener.onError(speechErrorForGenAiCode(response.e.errorCode))

                        // The response type is sealed, so a newer library that adds
                        // one fails the build here instead of silently dropping it.
                        is SpeechRecognizerResponse.CompletedResponse -> listener.onEnd()
                    }
                }
                // The SDK only emits CompletedResponse when the session ends
                // cleanly, and the flow can simply complete without it. Without a
                // terminal callback the strip would sit on "Listening" forever with
                // the microphone held, so close the attempt here as well. It is a
                // no-op when onEnd already ran: SpeechDictation ignores callbacks
                // once the attempt is closed.
                listener.onEnd()
            } catch (cancelled: CancellationException) {
                // Our own stop/cancel: the attempt was thrown away on purpose.
                throw cancelled
            } catch (error: Exception) {
                // The SDK also throws instead of emitting an ErrorResponse for some
                // failures (AICore not bindable, service killed mid-utterance).
                listener.onError(
                    if (error is GenAiException) {
                        speechErrorForGenAiCode(error.errorCode)
                    } else {
                        SpeechError.Unknown
                    },
                )
            }
        }
    }

    /**
     * Makes sure the model is on the device, waiting for the download if it is not.
     *
     * @return false when the model cannot be had; [listener] already got the reason.
     */
    private suspend fun awaitModel(
        engine: SpeechRecognizer,
        listener: SpeechEngine.Listener,
    ): Boolean = when (engine.checkStatus()) {
        FeatureStatus.AVAILABLE -> true

        // The first dictation on a fresh device lands here: the model is fetched on
        // demand. Progress is reported so the strip can show movement instead of
        // looking frozen, and the user keeps the ✕ that cancels the wait.
        FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
            val outcome = engine.download()
                .onEach { status ->
                    if (status is DownloadStatus.DownloadProgress) {
                        listener.onDownloading(status.totalBytesDownloaded)
                    }
                }
                // firstOrNull, not first: a flow that completes without ever
                // reporting the end of the download must become an honest failure,
                // not a NoSuchElementException landing in the generic catch below.
                .firstOrNull {
                    it is DownloadStatus.DownloadCompleted || it is DownloadStatus.DownloadFailed
                }
            when (outcome) {
                null -> {
                    listener.onError(SpeechError.Unavailable)
                    false
                }

                is DownloadStatus.DownloadFailed -> {
                    listener.onError(speechErrorForGenAiCode(outcome.e.errorCode))
                    false
                }

                else -> true
            }
        }

        // UNAVAILABLE: no AICore (any non-Pixel-10/11 device, or an unlocked
        // bootloader). Not a fault to report - the chain tries the next engine.
        else -> {
            listener.onError(SpeechError.Unavailable)
            false
        }
    }

    override fun stop() {
        // Ends the utterance but keeps the client: the model still has to deliver the
        // last words, which arrive as FinalTextResponse and then CompletedResponse.
        val engine = client ?: return
        scope.launch { runCatching { engine.stopRecognition() } }
    }

    override fun cancel() {
        job?.cancel()
        job = null
        val engine = client ?: return
        scope.launch { runCatching { engine.stopRecognition() } }
    }

    override fun release() {
        job?.cancel()
        job = null
        val engine = client ?: return
        // Detach first: a later start() must build a fresh client, not reuse a
        // closing one.
        client = null
        clientLocale = null
        // Stop and close in ONE coroutine. Closing while a stopRecognition() call is
        // still in flight is a use-after-close on the AICore binding, and the SDK
        // gives no way to await either from the outside.
        scope.launch {
            runCatching { engine.stopRecognition() }
            runCatching { engine.close() }
        }
    }

    /**
     * The client for the next attempt, built on first use.
     *
     * The locale follows the language the app is DRAWN in, so an app set to Spanish
     * dictates in Spanish on an English device - the same rule the framework engine
     * follows. Advanced mode covers es-ES, so both app languages are served.
     */
    private fun clientOrNull(): SpeechRecognizer? {
        val locale = appContext.resources.configuration.locales[0]
        val tag = speechLanguageTag(locale)
        client?.let { held ->
            if (clientLocale == tag) return held
            // The app's language changed since this client was built: its options
            // (and therefore the dictated language) are fixed at creation, so the
            // cached client has to go. The framework engine re-reads the locale on
            // every attempt; this makes the two behave the same way.
            runCatching { held.close() }
            client = null
            clientLocale = null
        }
        val created = runCatching {
            SpeechRecognition.getClient(
                speechRecognizerOptions {
                    this.locale = locale
                    preferredMode = mode
                },
            )
        }.getOrNull()
        client = created
        clientLocale = if (created == null) null else tag
        return created
    }
}

/**
 * Whether this release of Android can run ML Kit's GenAI speech recognition at all.
 *
 * Kept as a pure function so the engine-order rule (and its tests) can be written
 * without a device: the library's own floor is Android 12 (API 31).
 */
internal fun mlKitSpeechSupported(sdkInt: Int): Boolean = sdkInt >= 31

/**
 * A GenAI error code -> the sentence and the fix.
 *
 * The mapping is deliberately narrow about what it claims. AICore refusing the app
 * (no service, unlocked bootloader, background use) is [SpeechError.Unavailable],
 * which lets [ChainedSpeechEngine] fall through to a recognizer that works; a full
 * disk and an outdated AICore get their own sentences because the fix is somewhere
 * else entirely (free space, system update); and the quota/battery codes stay
 * [SpeechError.Unknown] rather than pretending to know which app used the quota.
 */
internal fun speechErrorForGenAiCode(code: Int): SpeechError = when (code) {
    GenAiException.ErrorCode.NOT_AVAILABLE,
    GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
    GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED,
    -> SpeechError.Unavailable

    // Another app is in front of the shared model: waiting and retrying works.
    GenAiException.ErrorCode.BUSY -> SpeechError.Busy

    // The model download cannot fit: "free some space" is the only real fix.
    GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> SpeechError.NoStorage

    // AICore is older than the library needs: a system update, not a retry.
    GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> SpeechError.NeedsSystemUpdate

    // The model answered something it could not turn into text: worth saying again.
    GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR,
    GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR,
    -> SpeechError.NoMatch

    // The request carried too little audio: nothing was said.
    GenAiException.ErrorCode.REQUEST_TOO_SMALL -> SpeechError.NoSpeech

    // The request never made it through the service.
    GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR,
    GenAiException.ErrorCode.REQUEST_TOO_LARGE,
    GenAiException.ErrorCode.CACHE_PROCESSING_ERROR,
    -> SpeechError.Server

    // CANCELLED is our own stop() coming back around; the attempt already ended.
    GenAiException.ErrorCode.CANCELLED -> SpeechError.Unknown

    // Quota limits, image-only errors, and anything a newer alpha adds.
    else -> SpeechError.Unknown
}
