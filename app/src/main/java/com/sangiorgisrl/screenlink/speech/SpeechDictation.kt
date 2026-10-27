package com.sangiorgisrl.screenlink.speech

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The dictation state machine: one attempt at a time, at most one insert.
 *
 * It owns the rules the terminal cares about, and none of the Android speech stack:
 *
 *  - **Text is typed once, at the end.** A recognizer streams partials continuously;
 *    inserting them would type the sentence, then retype it corrected. The live
 *    transcript is only ever *shown* ([DictationState.Listening]), and exactly one
 *    string is handed to [onText] per attempt.
 *  - **Stopping still delivers.** Tapping the microphone again means "that is all I
 *    am saying", so it ends the utterance and waits for the recognizer's last word
 *    instead of dropping what was heard.
 *  - **A late partial beats a shrug.** If the recognizer ends with a no-match but we
 *    already heard a usable phrase, that phrase is what gets typed - the framework
 *    reports no-match for plenty of perfectly good endings (mic closed mid-word).
 *  - **Nothing is ever executed.** The text goes to the terminal at the cursor;
 *    Enter stays the user's decision, and [SpeechText] guarantees no newline in it.
 *
 * @param onText where finished text goes: typed into the active terminal.
 */
class SpeechDictation(
    private val engine: SpeechEngine,
    private val onText: (String) -> Unit,
) {

    private val _state = MutableStateFlow<DictationState>(DictationState.Off)

    /** The single source of truth the microphone key and the dictation bar draw. */
    val state: StateFlow<DictationState> = _state.asStateFlow()

    /** Best transcript heard so far in this attempt (partials included). */
    private var heard: String = ""

    /** True once this attempt's text has been handed over, so it cannot be typed twice. */
    private var delivered = false

    /**
     * True once [dispose] ran: the screen is gone for good.
     *
     * A callback can still arrive afterwards - the RECORD_AUDIO dialog answering a
     * request whose screen was closed while the dialog was up is the realistic one -
     * and [start] must not answer it by opening the microphone with no strip, no Back
     * handler and nobody watching.
     */
    private var disposed = false

    /** True while an attempt is in flight (waiting for a model, mic open, or finishing). */
    val active: Boolean
        get() = _state.value is DictationState.Starting ||
            _state.value is DictationState.Downloading ||
            _state.value is DictationState.Listening ||
            _state.value is DictationState.Finishing

    /**
     * Which recognizer is doing the work right now, for the strip to name it.
     *
     * Read through the engine (a [ChainedSpeechEngine] reports the engine it is
     * actually using), and only ever shown while the strip is redrawing for a state
     * change - the engine only changes together with one.
     */
    val backend: SpeechBackend get() = engine.backend

    /** The microphone key: start an attempt, or end the one that is running. */
    fun toggle() {
        if (active) stop() else start()
    }

    fun start() {
        if (disposed || active) return
        heard = ""
        delivered = false
        if (!engine.isAvailable()) {
            _state.value = DictationState.Failed(SpeechError.Unavailable)
            return
        }
        _state.value = DictationState.Starting
        engine.start(listener)
    }

    /** End the utterance on purpose; its last word is still inserted. */
    fun stop() {
        val s = _state.value
        if (s is DictationState.Starting || s is DictationState.Listening) {
            _state.value = DictationState.Finishing(heard)
            engine.stop()
        } else if (s is DictationState.Downloading) {
            // Nothing has been said yet - the engine is still fetching its model - so
            // there is no utterance to finish: this tap throws the attempt away.
            cancel()
        }
    }

    /** Discard the attempt: nothing is typed. */
    fun cancel() {
        if (_state.value is DictationState.Off) return
        engine.cancel()
        heard = ""
        _state.value = DictationState.Off
    }

    /** Clear a failure message without starting a new attempt. */
    fun dismiss() {
        if (_state.value is DictationState.Failed) _state.value = DictationState.Off
    }

    /** The answer to the RECORD_AUDIO dialog. */
    fun onPermissionResult(hasPermission: Boolean, canAskAgain: Boolean) {
        when (recordAudioStep(hasPermission, canAskAgain)) {
            PermissionStep.Grant -> start()
            PermissionStep.Ask -> _state.value = DictationState.Failed(SpeechError.Permission)
            PermissionStep.OpenSettings ->
                _state.value = DictationState.Failed(SpeechError.PermissionSettings)
        }
    }

    /** The screen is going away: the microphone goes back to the system. */
    fun dispose() {
        disposed = true
        engine.cancel()
        engine.release()
        heard = ""
        _state.value = DictationState.Off
    }

    private val listener = object : SpeechEngine.Listener {
        override fun onReady() {
            // Also from Downloading: the model arrived and the microphone is open.
            if (_state.value is DictationState.Starting ||
                _state.value is DictationState.Downloading
            ) {
                _state.value = DictationState.Listening("")
            }
        }

        override fun onDownloading(bytesDownloaded: Long) {
            // Only ML Kit's engines report this. The byte count is the only progress
            // signal the SDK gives, so it is shown as it arrives rather than as a
            // percentage that would be invented.
            val s = _state.value
            if (s is DictationState.Starting || s is DictationState.Downloading) {
                _state.value = DictationState.Downloading(bytesDownloaded)
            }
        }

        override fun onPartial(text: String) {
            // A late partial from an attempt the user already threw away (or that
            // ended with the screen) must not be remembered as this attempt's text.
            if (!active) return
            val heardNow = SpeechText.forTerminal(text)
            // Still remember it after the user pressed stop: that is the text we are
            // waiting for the recognizer to confirm.
            if (heardNow.isNotEmpty()) heard = heardNow
            val s = _state.value
            if (s is DictationState.Starting || s is DictationState.Downloading ||
                s is DictationState.Listening
            ) {
                _state.value = DictationState.Listening(heard)
            }
        }

        override fun onFinal(text: String) {
            // Only while the attempt is still in flight: a result that arrives after
            // "discard", or after the screen went away, must not type anything.
            if (!active) return
            val deliveredNow = when {
                text.isNotBlank() -> insert(text)
                heard.isNotBlank() -> insert(heard)
                else -> false
            }
            if (_state.value is DictationState.Failed) return
            // An empty final is not a success: the strip has to say why nothing
            // appeared instead of vanishing quietly, exactly as [onEnd] does.
            _state.value = if (deliveredNow) {
                DictationState.Off
            } else {
                DictationState.Failed(SpeechError.NoMatch)
            }
        }

        override fun onError(error: SpeechError) {
            // Same rule as every other callback: an attempt the user already threw
            // away stays thrown away. Engines do answer after cancel(), and a strip
            // that reappears with "something went wrong" right after the user
            // pressed the X is worse than silence.
            if (!active) return
            // "No match" after a usable partial is not a failure: type what was heard.
            if ((error == SpeechError.NoMatch || error == SpeechError.NoSpeech) &&
                heard.isNotBlank()
            ) {
                insert(heard)
                _state.value = DictationState.Off
            } else {
                // The first error is the one that explains what happened; engines do
                // answer twice sometimes, and the second is a generic "client" error
                // that would hide "check your network".
                _state.value = DictationState.Failed(error)
            }
        }

        override fun onEnd() {
            // Some engines end without ever calling onFinal (or after an error we
            // already handled). Only close the attempt if it is still open.
            val s = _state.value
            if (s is DictationState.Starting || s is DictationState.Downloading ||
                s is DictationState.Listening || s is DictationState.Finishing
            ) {
                if (!insert(heard)) {
                    _state.value = DictationState.Failed(SpeechError.NoMatch)
                } else {
                    _state.value = DictationState.Off
                }
            }
        }
    }

    /** @return true if [raw] produced text that was actually typed. */
    private fun insert(raw: String): Boolean {
        val text = SpeechText.forTerminal(raw)
        if (text.isEmpty() || delivered) return false
        delivered = true
        onText(text)
        return true
    }
}
