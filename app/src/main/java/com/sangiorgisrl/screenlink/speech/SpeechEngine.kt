package com.sangiorgisrl.screenlink.speech

/**
 * The device's speech recognizer, as a seam.
 *
 * [SpeechDictation] holds the rules (what counts as a result, when text is typed,
 * what a failure means) and this interface is the only thing that touches the
 * recognition stack. Keeping the split means the whole dictation contract is a
 * JVM test with a fake engine, and each wrapper (framework, ML Kit) stays small
 * enough to read in one screen.
 *
 * Implementations deliver callbacks on the main thread, and only between
 * [start] and [stop]/[cancel]/[release].
 */
interface SpeechEngine {

    /**
     * Which recognizer this engine is, so the strip can say what is ACTUALLY
     * listening ("Gemini Nano", "on-device model", "device recognizer") instead of
     * implying a privacy story the device may not be delivering.
     */
    val backend: SpeechBackend

    /** False when this device simply cannot recognise speech (no engine, no model). */
    fun isAvailable(): Boolean

    /** Begin listening. Results arrive on [listener]; a previous session is dropped. */
    fun start(listener: Listener)

    /**
     * End the utterance on purpose: the recognizer finishes the phrase it is
     * hearing and still delivers its best [Listener.onFinal].
     */
    fun stop()

    /** Throw the utterance away: no result is delivered. */
    fun cancel()

    /** Give the microphone back to the rest of the system. */
    fun release()

    interface Listener {
        /** The mic is open and listening. */
        fun onReady()

        /**
         * The engine's model is not on the device yet and is being fetched;
         * [bytesDownloaded] is how much has arrived so far. Only ML Kit's engines
         * report this - the framework recognizer's models belong to the system.
         */
        fun onDownloading(bytesDownloaded: Long) = Unit

        /** A live, still-changing transcript of what has been heard so far. */
        fun onPartial(text: String)

        /** The recognizer's best answer. Sent once, immediately before [onEnd]. */
        fun onFinal(text: String)

        /** The attempt failed. */
        fun onError(error: SpeechError)

        /** The recognizer is done, however it got there. */
        fun onEnd()
    }
}
