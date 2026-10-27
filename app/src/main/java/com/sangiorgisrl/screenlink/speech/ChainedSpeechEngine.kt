package com.sangiorgisrl.screenlink.speech

import android.content.Context
import android.os.Build
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions

/**
 * The recognizers this device can use, tried in order, as one [SpeechEngine].
 *
 * "Advanced mode" is not a switch that can simply be turned on: the on-device Gemini
 * model only exists on some phones (Pixel 10/11 today), refuses unlocked bootloaders,
 * may need a multi-hundred-megabyte download first, and shares one instance between
 * all apps. So the app asks for the best engine and keeps the ones below it:
 *
 *  1. **ML Kit Advanced** - Gemini Nano through AICore: the best dictation, and the
 *     one that fails most often, because most devices do not have AICore.
 *  2. **The platform recognizer** - on-device model when the device reports one,
 *     otherwise the ordinary recognizer. Present on essentially every phone.
 *  3. **ML Kit Basic** - ML Kit's own on-device model, for the devices that have it
 *     but whose platform recognizer cannot do the app's language.
 *
 * The chain only steps down when the failure is about the ENGINE ("this recognizer
 * is not here", "its model cannot be downloaded", "another app owns it") and nothing
 * has been heard yet, so a half-finished sentence is never retyped by the next
 * engine and a microphone or permission failure is still reported honestly.
 *
 * Must be driven from the main thread; callbacks are delivered there.
 */
class ChainedSpeechEngine(private val engines: List<SpeechEngine>) : SpeechEngine {

    /** The engine that is listening right now (null between attempts). */
    private var current: SpeechEngine? = null

    /**
     * Bumped whenever the attempt changes, so a callback from an engine that was
     * already dropped cannot reach the UI or type anything.
     */
    private var attemptId: Int = 0

    private var nextIndex: Int = -1

    /** Where the winning engine's callbacks go. */
    private var out: SpeechEngine.Listener? = null

    /** True once the current attempt has heard anything usable. */
    private var heard = false

    /** The most recent failure, reported if the chain runs out of engines. */
    private var lastError: SpeechError? = null

    /**
     * The engine actually doing the work.
     *
     * Between engines (during a hand-off) it falls back to the first one, which is
     * what the attempt is going to end up using; the strip only reads this while it
     * is redrawing for a state change anyway.
     */
    override val backend: SpeechBackend
        get() = current?.backend
            ?: engines.firstOrNull()?.backend
            ?: SpeechBackend.DeviceRecognizer

    /** Dictation is offered if ANY engine in the chain could work. */
    override fun isAvailable(): Boolean = engines.any { it.isAvailable() }

    override fun start(listener: SpeechEngine.Listener) {
        releaseCurrent()
        attemptId++
        out = listener
        heard = false
        lastError = null
        // Skip engines this device cannot use at all, so a device without AICore
        // never spends an attempt finding that out.
        nextIndex = engines.indexOfFirst { it.isAvailable() }
        startNext()
    }

    override fun stop() {
        current?.stop()
    }

    override fun cancel() {
        attemptId++
        out = null
        heard = false
        lastError = null
        releaseCurrent()
    }

    /** Gives every engine's resources back, including the ones never reached. */
    override fun release() {
        cancel()
        engines.forEach { engine -> runCatching { engine.release() } }
    }

    /** Starts the next usable engine, or reports the last failure if there is none. */
    private fun startNext() {
        val target = out ?: return
        val index = nextIndex
        if (index !in engines.indices) {
            current = null
            // The last engine's own error explains what happened better than a
            // generic "unavailable" would: it is the one the user could act on.
            target.onError(lastError ?: SpeechError.Unavailable)
            target.onEnd()
            return
        }
        nextIndex = index + 1
        val engine = engines[index]
        attemptId++
        current = engine
        // An engine that fails by THROWING instead of reporting through onError would
        // otherwise crash the tap that started dictation. The chain's contract is that
        // every failure is reported, so a throw is treated exactly like the engine
        // saying "I am not here" and the next one gets the attempt.
        try {
            engine.start(Forwarding(target))
        } catch (failure: Throwable) {
            lastError = SpeechError.Unavailable
            releaseCurrent()
            startNext()
        }
    }

    /** Cancels whatever is running; its callbacks are ignored from here on. */
    private fun releaseCurrent() {
        val engine = current ?: return
        current = null
        runCatching { engine.cancel() }
    }

    /**
     * One engine's callbacks, filtered for the chain.
     *
     * Two jobs: drop anything an abandoned engine still sends (it can arrive late,
     * after its `cancel()`), and decide whether an error means "try the next one".
     */
    private inner class Forwarding(
        private val target: SpeechEngine.Listener,
    ) : SpeechEngine.Listener {

        private val id = attemptId

        /** False once this engine has been replaced or the attempt was thrown away. */
        private fun live(): Boolean = id == attemptId && current != null

        override fun onReady() {
            if (live()) target.onReady()
        }

        override fun onDownloading(bytesDownloaded: Long) {
            if (live()) target.onDownloading(bytesDownloaded)
        }

        override fun onPartial(text: String) {
            if (text.isNotBlank()) heard = true
            if (live()) target.onPartial(text)
        }

        override fun onFinal(text: String) {
            if (text.isNotBlank()) heard = true
            if (live()) target.onFinal(text)
        }

        override fun onError(error: SpeechError) {
            if (!live()) return
            lastError = error
            if (!heard && handsOffToNextEngine(error)) {
                // The engine is out (or its model is), not the microphone: step down
                // without telling the user about an engine they never asked for.
                releaseCurrent()
                startNext()
            } else {
                target.onError(error)
            }
        }

        override fun onEnd() {
            if (live()) target.onEnd()
        }
    }
}

/** The recognizers the chain can be built from, named so the rule is testable. */
internal enum class AsrEngineKind { MlKitAdvanced, Framework, MlKitBasic }

/**
 * Which engines to build, in the order to try them.
 *
 * Advanced first because it is the best dictation; the framework recognizer second
 * because it is the one that exists everywhere; ML Kit Basic last as the spare
 * on-device model. Both ML Kit engines are only offered on the Android version the
 * library supports.
 */
internal fun asrEngineOrder(sdkInt: Int, frameworkAvailable: Boolean): List<AsrEngineKind> =
    buildList {
        if (mlKitSpeechSupported(sdkInt)) add(AsrEngineKind.MlKitAdvanced)
        if (frameworkAvailable) add(AsrEngineKind.Framework)
        if (mlKitSpeechSupported(sdkInt)) add(AsrEngineKind.MlKitBasic)
    }

/**
 * Whether a failure is about the ENGINE, so the next one may still work.
 *
 * Deliberately excludes anything about the microphone, the permission, or the user's
 * speech: those repeat themselves on every engine, and stepping through three
 * recognizers would replace one honest message with three confusing ones.
 * [SpeechError.NoStorage] stays out too - a model that does not fit on the disk does
 * not fit for the next engine either.
 */
internal fun handsOffToNextEngine(error: SpeechError): Boolean = when (error) {
    SpeechError.Unavailable,
    SpeechError.LanguageUnavailable,
    SpeechError.Network,
    SpeechError.Server,
    SpeechError.Busy,
    SpeechError.NeedsSystemUpdate,
    -> true

    SpeechError.NoSpeech, SpeechError.NoMatch, SpeechError.Audio, SpeechError.Client,
    SpeechError.RecognizerBusy, SpeechError.Permission, SpeechError.PermissionSettings,
    SpeechError.NoStorage, SpeechError.Unknown,
    -> false
}

/**
 * The recognizer stack for this device.
 *
 * Built once per screen and reused: each engine holds whatever it needs (a bound
 * recognizer, an AICore client) between attempts.
 */
fun dictationEngines(context: Context): SpeechEngine {
    val app = context.applicationContext
    val framework = AndroidSpeechEngine(app)
    val order = asrEngineOrder(
        sdkInt = Build.VERSION.SDK_INT,
        frameworkAvailable = framework.isAvailable(),
    )
    val engines = order.map { kind ->
        when (kind) {
            AsrEngineKind.MlKitAdvanced ->
                MlKitSpeechEngine(app, SpeechRecognizerOptions.Mode.MODE_ADVANCED)
            AsrEngineKind.MlKitBasic ->
                MlKitSpeechEngine(app, SpeechRecognizerOptions.Mode.MODE_BASIC)
            AsrEngineKind.Framework -> framework
        }
    }
    // One engine needs no chain: hand back the engine itself so its callbacks are
    // direct and its backend is its own.
    return engines.singleOrNull() ?: ChainedSpeechEngine(engines)
}
