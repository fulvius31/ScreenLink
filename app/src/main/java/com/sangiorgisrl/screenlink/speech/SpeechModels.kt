package com.sangiorgisrl.screenlink.speech

import android.speech.SpeechRecognizer
import com.sangiorgisrl.screenlink.i18n.UiText
import com.sangiorgisrl.screenlink.R

/**
 * Why a dictation attempt produced nothing, in terms the user can act on.
 *
 * The framework reports failures as bare ints (SpeechRecognizer.ERROR_*), which say
 * nothing about what to do next. Each value here is a different sentence and a
 * different fix: say it again, check the connection, close the other app that owns
 * the microphone, or allow the permission in Settings.
 */
enum class SpeechError {
    /** This device has no speech recognizer at all, so dictation cannot be offered. */
    Unavailable,

    /** The microphone opened but no speech was heard. */
    NoSpeech,

    /** Speech was heard but nothing could be made of it. */
    NoMatch,

    /** The microphone itself failed, or another app owns it. */
    Audio,

    /** Recognition is server-side and the network did not answer. */
    Network,

    /** The recognizer service refused or crashed. */
    Server,

    /** Our own request was unusable (bad intent, bad language argument). */
    Client,

    /** Another app is recognising speech right now. */
    Busy,

    /** Our recognizer instance was still busy when it was asked again. */
    RecognizerBusy,

    /** RECORD_AUDIO was refused, but the system will ask again. */
    Permission,

    /** RECORD_AUDIO was refused permanently: only Settings can undo that. */
    PermissionSettings,

    /** The app's language has no recognizer model on this device. */
    LanguageUnavailable,

    /** The speech model cannot be downloaded: the device is out of storage. */
    NoStorage,

    /** The on-device model service (AICore) needs a system update first. */
    NeedsSystemUpdate,

    /** Anything else: still worth saying that dictation stopped. */
    Unknown,
}

/**
 * Which recognizer is actually doing the work.
 *
 * The three are NOT interchangeable in the privacy story the app tells: [Advanced]
 * and [OnDeviceModel] run a model on the phone, while [DeviceRecognizer] may
 * recognise on the phone or through the platform's speech service depending on what
 * the device has installed. The strip names the one in use rather than claiming the
 * best case.
 */
enum class SpeechBackend {
    /** ML Kit GenAI "Advanced": the on-device Gemini (Gemini Nano) model via AICore. */
    Advanced,

    /** ML Kit GenAI "Basic": the traditional on-device speech model. */
    OnDeviceModel,

    /** The platform's own recognizer (on-device when the device offers that engine). */
    DeviceRecognizer,
}

/** The words the dictation strip uses for this engine. */
fun SpeechBackend.labelRes(): Int = when (this) {
    SpeechBackend.Advanced -> R.string.dictate_backend_advanced
    SpeechBackend.OnDeviceModel -> R.string.dictate_backend_model
    SpeechBackend.DeviceRecognizer -> R.string.dictate_backend_device
}

/** The sentence this failure is reported with (resolved in the current language). */
fun SpeechError.message(): UiText = UiText.Resource(
    when (this) {
        SpeechError.Unavailable -> R.string.dictate_error_unavailable
        SpeechError.NoSpeech -> R.string.dictate_error_no_speech
        SpeechError.NoMatch -> R.string.dictate_error_no_match
        SpeechError.Audio -> R.string.dictate_error_audio
        SpeechError.Network -> R.string.dictate_error_network
        SpeechError.Server -> R.string.dictate_error_server
        SpeechError.Client -> R.string.dictate_error_client
        SpeechError.Busy -> R.string.dictate_error_busy
        SpeechError.RecognizerBusy -> R.string.dictate_error_busy
        SpeechError.Permission -> R.string.dictate_error_permission
        SpeechError.PermissionSettings -> R.string.dictate_error_permission_settings
        SpeechError.LanguageUnavailable -> R.string.dictate_error_language
        SpeechError.NoStorage -> R.string.dictate_error_no_storage
        SpeechError.NeedsSystemUpdate -> R.string.dictate_error_needs_update
        SpeechError.Unknown -> R.string.dictate_error_unknown
    },
)

/**
 * Whether tapping "Try again" can plausibly work.
 *
 * A missing permission or a device with no recognizer will fail identically every
 * time, so offering a retry there hides the real fix (Settings, or no dictation on
 * this hardware) behind a loop.
 */
fun SpeechError.isRetryable(): Boolean = when (this) {
    SpeechError.Unavailable, SpeechError.Permission, SpeechError.PermissionSettings,
    SpeechError.NoStorage,
    -> false
    SpeechError.NoSpeech, SpeechError.NoMatch, SpeechError.Audio, SpeechError.Network,
    SpeechError.Server, SpeechError.Client, SpeechError.Busy, SpeechError.RecognizerBusy,
    SpeechError.LanguageUnavailable, SpeechError.NeedsSystemUpdate, SpeechError.Unknown -> true
}

/**
 * The framework error code -> [SpeechError].
 *
 * Every ERROR_* constant the platform can send is listed on purpose: an unmapped
 * code would land in [SpeechError.Unknown] and tell the user "something went wrong"
 * when the recognizer actually said "check your network".
 */
fun speechErrorForCode(code: Int): SpeechError = when (code) {
    SpeechRecognizer.ERROR_AUDIO -> SpeechError.Audio
    SpeechRecognizer.ERROR_CLIENT -> SpeechError.Client
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.Permission
    SpeechRecognizer.ERROR_NETWORK -> SpeechError.Network
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechError.Network
    SpeechRecognizer.ERROR_NO_MATCH -> SpeechError.NoMatch
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechError.RecognizerBusy
    SpeechRecognizer.ERROR_SERVER -> SpeechError.Server
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.NoSpeech
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> SpeechError.LanguageUnavailable
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SpeechError.LanguageUnavailable
    // Newer engines add these; each still has a sentence the user can act on.
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> SpeechError.Network
    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> SpeechError.Busy
    SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> SpeechError.LanguageUnavailable
    SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> SpeechError.Unknown
    else -> SpeechError.Unknown
}

/** What to do about RECORD_AUDIO, given the system's answer. */
enum class PermissionStep {
    /** Already granted: start listening. */
    Grant,

    /** Not granted, and the system will show the dialog again. */
    Ask,

    /** Not granted and the dialog is gone ("Don't ask again"): only Settings can fix it. */
    OpenSettings,
}

/**
 * The RECORD_AUDIO decision, kept apart from the Activity call that asks.
 *
 * `shouldShowRequestPermissionRationale` is the only signal that a refusal was
 * permanent, and reading it in the wrong branch turns a one-tap fix (allow in
 * Settings) into a permission dialog that never appears.
 */
fun recordAudioStep(hasPermission: Boolean, canAskAgain: Boolean): PermissionStep = when {
    hasPermission -> PermissionStep.Grant
    canAskAgain -> PermissionStep.Ask
    else -> PermissionStep.OpenSettings
}

/**
 * True while an attempt is in flight: the strip is up and the microphone key means
 * "stop", not "start". A failure is NOT active - the microphone is already closed -
 * but its message stays on screen until the user dismisses or retries it.
 */
val DictationState.isActive: Boolean
    get() = this is DictationState.Starting ||
        this is DictationState.Downloading ||
        this is DictationState.Listening ||
        this is DictationState.Finishing

/** Everything the dictation UI can show, as values. */
sealed interface DictationState {

    /** Not dictating. The microphone key is idle. */
    data object Off : DictationState

    /** Asked for the microphone; waiting for the recognizer to open it. */
    data object Starting : DictationState

    /**
     * The engine's model is being downloaded before it can listen.
     *
     * Only ML Kit's engines get here: the framework recognizer's models are managed
     * by the system. [bytesDownloaded] is what has arrived so far, which is the only
     * honest progress signal the SDK gives.
     */
    data class Downloading(val bytesDownloaded: Long) : DictationState

    /** Listening. [partial] is the live transcript, empty until the first word. */
    data class Listening(val partial: String) : DictationState

    /** The user pressed the mic again; waiting for the recognizer's last word. */
    data class Finishing(val partial: String) : DictationState

    /** The attempt ended with no text. */
    data class Failed(val error: SpeechError) : DictationState
}
