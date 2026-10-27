package com.sangiorgisrl.screenlink.speech

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The dictation contract, with a fake recognizer.
 *
 * Everything here is about what the TERMINAL receives, because that is where the
 * damage would be: text typed twice, a sentence typed while the user was still
 * saying it, a discarded attempt that typed anyway, or a newline that runs a
 * command. The framework's own behaviour is not tested (that belongs to the
 * device); what is tested is how [SpeechDictation] behaves when the engine is
 * well-behaved, half-behaved, or rude.
 */
class SpeechDictationTest {

    /** Records the calls and lets the test drive the callbacks by hand. */
    private class FakeEngine(var available: Boolean = true) : SpeechEngine {
        var listener: SpeechEngine.Listener? = null
        var starts = 0
        var stops = 0
        var cancels = 0
        var releases = 0

        override val backend: SpeechBackend = SpeechBackend.DeviceRecognizer

        override fun isAvailable(): Boolean = available

        override fun start(listener: SpeechEngine.Listener) {
            starts++
            this.listener = listener
        }

        override fun stop() {
            stops++
        }

        override fun cancel() {
            cancels++
        }

        override fun release() {
            releases++
        }

        /** The engine the tests talk to; fails loudly if nothing is listening. */
        fun engine(): SpeechEngine.Listener =
            listener ?: error("the engine was never started")
    }

    private class Fixture(available: Boolean = true) {
        val engine = FakeEngine(available)
        val typed = mutableListOf<String>()
        val dictation = SpeechDictation(engine) { typed += it }

        fun state(): DictationState = dictation.state.value
    }

    // ---- starting ------------------------------------------------------------

    @Test
    fun `a device with no recognizer never opens the microphone`() {
        val f = Fixture(available = false)

        f.dictation.start()

        assertEquals(DictationState.Failed(SpeechError.Unavailable), f.state())
        assertEquals(0, f.engine.starts, "no engine means no attempt to start")
        assertTrue(f.typed.isEmpty())
    }

    @Test
    fun `the microphone opens once per attempt`() {
        val f = Fixture()

        f.dictation.start()
        f.dictation.start()

        assertEquals(1, f.engine.starts)
        assertEquals(DictationState.Starting, f.state())
    }

    @Test
    fun `the recognizer being ready is what turns the strip to listening`() {
        val f = Fixture()
        f.dictation.start()

        f.engine.engine().onReady()

        assertEquals(DictationState.Listening(""), f.state())
        assertTrue(f.dictation.active)
    }

    // ---- what is shown while talking ----------------------------------------

    @Test
    fun `a partial transcript is shown but nothing is typed yet`() {
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onReady()

        f.engine.engine().onPartial("git status")

        assertEquals(DictationState.Listening("git status"), f.state())
        assertTrue(f.typed.isEmpty(), "partials are a preview, not terminal input")
    }

    @Test
    fun `a corrected partial replaces the earlier one instead of adding to it`() {
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onReady()

        f.engine.engine().onPartial("git st")
        f.engine.engine().onPartial("git status")

        assertEquals(DictationState.Listening("git status"), f.state())
    }

    @Test
    fun `the live transcript is sanitized too`() {
        // What the user reads must be what gets typed, or the strip lies: a
        // sentence boundary shown as a line break would be a surprise at insert.
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onReady()

        f.engine.engine().onPartial("cd /tmp\nls")

        assertEquals(DictationState.Listening("cd /tmp ls"), f.state())
    }

    // ---- finishing ------------------------------------------------------------

    @Test
    fun `the final result is typed exactly once`() {
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onReady()
        f.engine.engine().onPartial("ls -la")

        f.engine.engine().onFinal("ls -la")
        f.engine.engine().onEnd()

        assertEquals(listOf("ls -la"), f.typed)
        assertEquals(DictationState.Off, f.state())
        assertFalse(f.dictation.active)
    }

    @Test
    fun `an engine that repeats its final cannot type the sentence twice`() {
        // Real engines have been seen to deliver onResults twice around a cancel;
        // a duplicated command line in a shell is not a cosmetic bug.
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onReady()

        f.engine.engine().onFinal("rm -rf build")
        f.engine.engine().onFinal("rm -rf build")
        f.engine.engine().onEnd()

        assertEquals(listOf("rm -rf build"), f.typed)
    }

    @Test
    fun `tapping the microphone again waits for the last word instead of dropping it`() {
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onReady()
        f.engine.engine().onPartial("tail -f log")

        f.dictation.stop()

        assertEquals(DictationState.Finishing("tail -f log"), f.state())
        assertEquals(1, f.engine.stops)
        assertTrue(f.dictation.active, "the attempt is still in flight until the result lands")

        f.engine.engine().onFinal("tail -f logs")

        assertEquals(listOf("tail -f logs"), f.typed)
        assertEquals(DictationState.Off, f.state())
    }

    @Test
    fun `a late partial beats a no-match`() {
        // The framework answers ERROR_NO_MATCH for plenty of good endings - the mic
        // closed mid-word, a trailing silence - after having streamed a usable
        // phrase. Throwing that away would make the user say it all again.
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onReady()
        f.engine.engine().onPartial("docker ps")
        f.dictation.stop()

        f.engine.engine().onError(SpeechError.NoMatch)

        assertEquals(listOf("docker ps"), f.typed)
        assertEquals(DictationState.Off, f.state())
    }

    @Test
    fun `an empty final falls back to what was heard`() {
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onReady()
        f.engine.engine().onPartial("uptime")

        f.engine.engine().onFinal("")

        assertEquals(listOf("uptime"), f.typed)
    }

    @Test
    fun `a final with nothing in it says nothing was recognised`() {
        // Closing the strip quietly would leave the user unsure whether the app heard
        // them at all: this is the same rule the end-of-attempt path already follows.
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onReady()

        f.engine.engine().onFinal("")

        assertTrue(f.typed.isEmpty())
        assertEquals(DictationState.Failed(SpeechError.NoMatch), f.state())
    }

    @Test
    fun `an error that arrives after the attempt was discarded stays discarded`() {
        // Engines do answer after cancel(). A strip reappearing with "something went
        // wrong" right after the user pressed discard is worse than silence.
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onReady()

        f.dictation.cancel()
        f.engine.engine().onError(SpeechError.Client)

        assertEquals(DictationState.Off, f.state())
        assertTrue(f.typed.isEmpty())
    }

    @Test
    fun `an engine that ends without ever answering still delivers its partial`() {
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onReady()
        f.engine.engine().onPartial("whoami")

        f.engine.engine().onEnd()

        assertEquals(listOf("whoami"), f.typed)
        assertEquals(DictationState.Off, f.state())
    }

    @Test
    fun `an engine that ends having heard nothing says so instead of typing nothing`() {
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onReady()

        f.engine.engine().onEnd()

        assertTrue(f.typed.isEmpty())
        assertEquals(DictationState.Failed(SpeechError.NoMatch), f.state())
    }

    @Test
    fun `a dictated sentence never contains Enter`() {
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onReady()

        f.engine.engine().onFinal("ls\n\u001B")

        assertEquals(listOf("ls"), f.typed)
        assertFalse(f.typed.single().any { it == '\n' || it == '\r' })
    }

    // ---- throwing it away -----------------------------------------------------

    @Test
    fun `discarding types nothing even when a whole sentence was heard`() {
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onReady()
        f.engine.engine().onPartial("shutdown now")

        f.dictation.cancel()

        assertTrue(f.typed.isEmpty())
        assertEquals(DictationState.Off, f.state())
        assertTrue(f.engine.cancels > 0, "the microphone has to be handed back")
    }

    @Test
    fun `a late result after a discard cannot resurrect the sentence`() {
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onReady()
        f.engine.engine().onPartial("shutdown now")
        f.dictation.cancel()

        // The recognizer is allowed to still be finishing when we walked away.
        f.engine.engine().onFinal("shutdown now")

        assertTrue(f.typed.isEmpty())
        assertEquals(DictationState.Off, f.state())
    }

    @Test
    fun `cancel while idle does not touch the engine`() {
        val f = Fixture()

        f.dictation.cancel()

        assertEquals(0, f.engine.cancels)
        assertEquals(DictationState.Off, f.state())
    }

    // ---- failures -------------------------------------------------------------

    @Test
    fun `a failure keeps its message on screen but is not an open microphone`() {
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onReady()

        f.engine.engine().onError(SpeechError.Network)

        assertEquals(DictationState.Failed(SpeechError.Network), f.state())
        assertFalse(f.dictation.active, "nothing is listening any more")
    }

    @Test
    fun `no speech with nothing heard is a failure the user can read`() {
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onReady()

        f.engine.engine().onError(SpeechError.NoSpeech)

        assertEquals(DictationState.Failed(SpeechError.NoSpeech), f.state())
        assertTrue(f.typed.isEmpty())
    }

    @Test
    fun `dismiss clears the message without opening the microphone again`() {
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onError(SpeechError.Busy)

        f.dictation.dismiss()

        assertEquals(DictationState.Off, f.state())
        assertEquals(1, f.engine.starts)
    }

    @Test
    fun `retrying a failure starts a fresh attempt`() {
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onError(SpeechError.Network)

        f.dictation.start()
        f.engine.engine().onReady()
        f.engine.engine().onFinal("ping -c 3 host")

        assertEquals(listOf("ping -c 3 host"), f.typed)
        assertEquals(2, f.engine.starts)
    }

    @Test
    fun `an error after a failure does not overwrite the first reason`() {
        // Some engines answer onError twice (the second one a generic client error).
        // The first is the one that explains what happened.
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onReady()

        f.engine.engine().onError(SpeechError.Network)
        f.engine.engine().onError(SpeechError.Client)

        assertEquals(DictationState.Failed(SpeechError.Network), f.state())
    }

    // ---- the microphone key ---------------------------------------------------

    @Test
    fun `the microphone key opens the mic, then closes the utterance`() {
        val f = Fixture()

        f.dictation.toggle()
        assertEquals(1, f.engine.starts)
        f.engine.engine().onReady()
        f.dictation.toggle()

        assertEquals(1, f.engine.stops)
        assertTrue(f.state() is DictationState.Finishing)
    }

    @Test
    fun `the microphone key after a failure starts over`() {
        val f = Fixture()
        f.dictation.toggle()
        f.engine.engine().onError(SpeechError.Audio)

        f.dictation.toggle()

        assertEquals(2, f.engine.starts, "a failed attempt is not an open microphone")
    }

    // ---- RECORD_AUDIO ---------------------------------------------------------

    @Test
    fun `an allowed microphone starts dictating straight away`() {
        val f = Fixture()

        f.dictation.onPermissionResult(hasPermission = true, canAskAgain = false)

        assertEquals(1, f.engine.starts)
        assertEquals(DictationState.Starting, f.state())
    }

    @Test
    fun `a refusal that can be asked again asks again`() {
        val f = Fixture()

        f.dictation.onPermissionResult(hasPermission = false, canAskAgain = true)

        assertEquals(DictationState.Failed(SpeechError.Permission), f.state())
        assertEquals(0, f.engine.starts)
    }

    @Test
    fun `a permanent refusal points at Settings instead of a dialog that never appears`() {
        val f = Fixture()

        f.dictation.onPermissionResult(hasPermission = false, canAskAgain = false)

        assertEquals(DictationState.Failed(SpeechError.PermissionSettings), f.state())
        assertEquals(0, f.engine.starts)
    }

    // ---- leaving the screen ---------------------------------------------------

    @Test
    fun `dispose closes the microphone and gives it back`() {
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onReady()
        f.engine.engine().onPartial("half a sentence")

        f.dictation.dispose()

        assertEquals(1, f.engine.cancels)
        assertEquals(1, f.engine.releases)
        assertEquals(DictationState.Off, f.state())
        assertTrue(f.typed.isEmpty(), "leaving the screen is not a reason to type")
    }

    @Test
    fun `a result that arrives after dispose is ignored`() {
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onReady()
        val listener = f.engine.listener
        f.dictation.dispose()

        listener?.onFinal("ls")
        listener?.onEnd()

        assertTrue(f.typed.isEmpty(), "the screen is gone: nothing may be typed")
        assertEquals(DictationState.Off, f.state())
    }

    @Test
    fun `a dictation never starts again after the screen went away`() {
        val f = Fixture()
        f.dictation.dispose()

        f.dictation.start()
        f.dictation.toggle()

        assertEquals(0, f.engine.starts, "the screen is gone: the microphone stays shut")
        assertEquals(DictationState.Off, f.state())
        assertTrue(f.typed.isEmpty())
    }

    @Test
    fun `a permission answer that arrives after dispose does not open the microphone`() {
        // The realistic version: the RECORD_AUDIO dialog is up, the user leaves the
        // terminal, and the answer still comes back to this object.
        val f = Fixture()
        f.dictation.start()
        val listener = f.engine.listener
        f.dictation.dispose()

        f.dictation.onPermissionResult(hasPermission = true, canAskAgain = false)
        listener?.onReady()
        listener?.onFinal("who is there")

        assertEquals(1, f.engine.starts, "only the attempt that was already in flight")
        assertEquals(1, f.engine.releases)
        assertTrue(f.typed.isEmpty(), "nothing may be typed into a screen that is gone")
        assertEquals(DictationState.Off, f.state())
    }

    // ---- fetching the model (advanced mode) -----------------------------------
    // ML Kit downloads its speech model on the first dictation, which can take
    // minutes. The strip has to show movement, keep the ✕ that gives up, and above
    // all never treat a download as an utterance.

    @Test
    fun `a model download shows how much has arrived`() {
        val f = Fixture()
        f.dictation.start()

        f.engine.engine().onDownloading(2_000_000)
        assertEquals(DictationState.Downloading(2_000_000), f.state())
        assertTrue(f.dictation.active, "the attempt is still in flight: the mic key means stop")

        f.engine.engine().onDownloading(9_000_000)
        assertEquals(DictationState.Downloading(9_000_000), f.state())
        assertTrue(f.typed.isEmpty())
    }

    @Test
    fun `the microphone opens as soon as the model lands`() {
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onDownloading(1_000_000)

        f.engine.engine().onReady()

        assertEquals(DictationState.Listening(""), f.state())
    }

    @Test
    fun `tapping the microphone during a download throws the attempt away`() {
        // There is no utterance to finish while the engine is still fetching its model,
        // so "stop" has to mean discard rather than the waiting-for-the-last-word path.
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onDownloading(1_000_000)

        f.dictation.toggle()

        assertEquals(DictationState.Off, f.state())
        assertEquals(1, f.engine.cancels)
        assertEquals(0, f.engine.stops, "nothing was listening, so there is nothing to finish")
        assertTrue(f.typed.isEmpty())
    }

    @Test
    fun `a model that does not fit on the device says so without a retry loop`() {
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onDownloading(50_000_000)

        f.engine.engine().onError(SpeechError.NoStorage)

        assertEquals(DictationState.Failed(SpeechError.NoStorage), f.state())
        assertFalse(SpeechError.NoStorage.isRetryable(), "a full disk is not fixed by tapping again")
    }

    @Test
    fun `an engine that ends while still downloading did not hear anything`() {
        val f = Fixture()
        f.dictation.start()
        f.engine.engine().onDownloading(500_000)

        f.engine.engine().onEnd()

        assertEquals(DictationState.Failed(SpeechError.NoMatch), f.state())
        assertTrue(f.typed.isEmpty())
    }

    @Test
    fun `the strip names the engine that is listening`() {
        // The privacy story differs between the recognizers, so the strip says which
        // one is in use instead of implying the best case.
        val f = Fixture()

        assertEquals(SpeechBackend.DeviceRecognizer, f.dictation.backend)
    }
}
