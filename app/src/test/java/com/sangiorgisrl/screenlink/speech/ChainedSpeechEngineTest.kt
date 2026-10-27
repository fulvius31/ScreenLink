package com.sangiorgisrl.screenlink.speech

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The fallback chain, with fake recognizers.
 *
 * This is where "advanced mode" could go wrong in a way no single engine test
 * catches: a device without Gemini Nano being told dictation is broken, a half-finished
 * sentence being retyped by the next recognizer, an abandoned engine still typing
 * after it was dropped, or the microphone being left open in the engine that gave up.
 * The chain is pure Kotlin, so all of that is testable without a device.
 */
class ChainedSpeechEngineTest {

    /** A recognizer that does exactly what the test tells it, and records the rest. */
    private class FakeAsr(
        override val backend: SpeechBackend,
        private val available: Boolean = true,
        /** Delivered from [start] itself, i.e. before the user says anything. */
        private val failOnStart: SpeechError? = null,
        /** Fails the rude way: by throwing out of [start] instead of reporting. */
        private val throwOnStart: Boolean = false,
    ) : SpeechEngine {
        var listener: SpeechEngine.Listener? = null
            private set
        var starts = 0
        var stops = 0
        var cancels = 0
        var releases = 0

        override fun isAvailable(): Boolean = available

        override fun start(listener: SpeechEngine.Listener) {
            starts++
            if (throwOnStart) throw IllegalStateException("the recognizer refused to bind")
            this.listener = listener
            if (failOnStart == null) listener.onReady() else listener.onError(failOnStart)
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
    }

    /** Records what the UI (SpeechDictation) would have seen. */
    private class Recording : SpeechEngine.Listener {
        val events = mutableListOf<String>()

        override fun onReady() {
            events += "ready"
        }

        override fun onDownloading(bytesDownloaded: Long) {
            events += "download:$bytesDownloaded"
        }

        override fun onPartial(text: String) {
            events += "partial:$text"
        }

        override fun onFinal(text: String) {
            events += "final:$text"
        }

        override fun onError(error: SpeechError) {
            events += "error:$error"
        }

        override fun onEnd() {
            events += "end"
        }
    }

    @Test
    fun `the first engine that works keeps the microphone`() {
        val advanced = FakeAsr(SpeechBackend.Advanced)
        val framework = FakeAsr(SpeechBackend.DeviceRecognizer)
        val chain = ChainedSpeechEngine(listOf(advanced, framework))

        chain.start(Recording())

        assertEquals(1, advanced.starts)
        assertEquals(0, framework.starts, "a working engine must not be replaced")
        assertEquals(SpeechBackend.Advanced, chain.backend)
    }

    @Test
    fun `a device without the model service steps down silently`() {
        // The common case: every phone that is not a Pixel 10/11. AICore answers
        // UNAVAILABLE, and the user must never see an error about an engine they did
        // not choose - the dictation just works through the platform recognizer.
        val advanced = FakeAsr(SpeechBackend.Advanced, failOnStart = SpeechError.Unavailable)
        val framework = FakeAsr(SpeechBackend.DeviceRecognizer)
        val out = Recording()
        val chain = ChainedSpeechEngine(listOf(advanced, framework))

        chain.start(out)

        assertEquals(1, advanced.starts)
        assertEquals(1, framework.starts)
        assertEquals(listOf("ready"), out.events)
        assertEquals(SpeechBackend.DeviceRecognizer, chain.backend)
        assertEquals(1, advanced.cancels, "the abandoned engine gives the microphone back")
    }

    @Test
    fun `an engine this device cannot use is never started`() {
        // isAvailable() is the cheap check (no AICore on this Android), so the attempt
        // does not spend a round trip discovering it.
        val advanced = FakeAsr(SpeechBackend.Advanced, available = false)
        val framework = FakeAsr(SpeechBackend.DeviceRecognizer)
        val chain = ChainedSpeechEngine(listOf(advanced, framework))

        chain.start(Recording())

        assertEquals(0, advanced.starts)
        assertEquals(1, framework.starts)
    }

    @Test
    fun `a half-finished sentence is never handed to the next recognizer`() {
        // Retyping what the user already said is the failure mode to avoid: once a word
        // has been heard, the attempt belongs to the engine that heard it.
        val advanced = FakeAsr(SpeechBackend.Advanced)
        val framework = FakeAsr(SpeechBackend.DeviceRecognizer)
        val out = Recording()
        ChainedSpeechEngine(listOf(advanced, framework)).start(out)

        advanced.listener!!.onPartial("turn on the")
        advanced.listener!!.onError(SpeechError.Unavailable)

        assertEquals(0, framework.starts)
        assertEquals(listOf("ready", "partial:turn on the", "error:Unavailable"), out.events)
    }

    @Test
    fun `a microphone or permission failure is reported, not retried elsewhere`() {
        val advanced = FakeAsr(SpeechBackend.Advanced, failOnStart = SpeechError.Audio)
        val framework = FakeAsr(SpeechBackend.DeviceRecognizer)
        val out = Recording()
        ChainedSpeechEngine(listOf(advanced, framework)).start(out)

        assertEquals(0, framework.starts)
        assertEquals(listOf("error:Audio"), out.events)
    }

    @Test
    fun `running out of engines reports the last real failure`() {
        // "The speech recognizer stopped answering" is actionable; a generic
        // "no recognizer here" would be a lie, since the device does have one.
        val advanced = FakeAsr(SpeechBackend.Advanced, failOnStart = SpeechError.Unavailable)
        val basic = FakeAsr(SpeechBackend.OnDeviceModel, failOnStart = SpeechError.Server)
        val out = Recording()
        ChainedSpeechEngine(listOf(advanced, basic)).start(out)

        assertEquals(listOf("error:Server", "end"), out.events)
        assertEquals(1, advanced.starts)
        assertEquals(1, basic.starts)
    }

    @Test
    fun `a device with no recognizer at all says so once`() {
        val out = Recording()
        ChainedSpeechEngine(emptyList()).start(out)

        assertEquals(listOf("error:Unavailable", "end"), out.events)
    }

    @Test
    fun `an abandoned engine's late callbacks reach nobody`() {
        // The framework and AICore both answer late sometimes; a callback from the
        // engine that was dropped must not type into the terminal.
        val advanced = FakeAsr(SpeechBackend.Advanced, failOnStart = SpeechError.Unavailable)
        val framework = FakeAsr(SpeechBackend.DeviceRecognizer)
        val out = Recording()
        ChainedSpeechEngine(listOf(advanced, framework)).start(out)

        advanced.listener!!.onPartial("late from the dead engine")
        advanced.listener!!.onFinal("late final")
        advanced.listener!!.onEnd()

        assertEquals(listOf("ready"), out.events)
    }

    @Test
    fun `stop and cancel go to the engine that is actually listening`() {
        val advanced = FakeAsr(SpeechBackend.Advanced, failOnStart = SpeechError.Busy)
        val framework = FakeAsr(SpeechBackend.DeviceRecognizer)
        val chain = ChainedSpeechEngine(listOf(advanced, framework))
        chain.start(Recording())

        chain.stop()
        chain.cancel()

        // The busy engine was cancelled once, at the hand-off; after that only the
        // engine that took over owns the microphone.
        assertEquals(1, advanced.cancels)
        assertEquals(0, advanced.stops, "the busy engine no longer owns the microphone")
        assertEquals(1, framework.stops)
        assertEquals(1, framework.cancels)
    }

    @Test
    fun `a new attempt replaces the one in flight`() {
        val advanced = FakeAsr(SpeechBackend.Advanced)
        val chain = ChainedSpeechEngine(listOf(advanced))
        val first = Recording()
        val second = Recording()

        chain.start(first)
        chain.start(second)

        assertEquals(2, advanced.starts)
        assertEquals(1, advanced.cancels, "the previous attempt is closed before the new one")
        // Only the newest attempt is still wired to the engine: the replaced one must
        // not type into the terminal behind the user's back.
        advanced.listener!!.onPartial("only the newest attempt hears this")
        // "ready" is all the first attempt got out before it was replaced; the live
        // transcript belongs to the newest attempt alone.
        assertEquals(listOf("ready"), first.events, "the replaced attempt's callbacks go nowhere")
        assertEquals(
            listOf("ready", "partial:only the newest attempt hears this"),
            second.events,
        )
    }

    @Test
    fun `release gives every engine its resources back`() {
        // Including the ones the attempt never reached: the screen is going away and no
        // bound recognizer may be left behind.
        val advanced = FakeAsr(SpeechBackend.Advanced)
        val framework = FakeAsr(SpeechBackend.DeviceRecognizer)
        val chain = ChainedSpeechEngine(listOf(advanced, framework))
        chain.start(Recording())

        chain.release()

        assertEquals(1, advanced.releases)
        assertEquals(1, framework.releases)
        assertTrue(chain.isAvailable())
    }

    @Test
    fun `the chain is available when any engine could work`() {
        val dead = FakeAsr(SpeechBackend.Advanced, available = false)
        val alive = FakeAsr(SpeechBackend.DeviceRecognizer, available = true)
        assertTrue(ChainedSpeechEngine(listOf(dead, alive)).isAvailable())
        assertFalse(
            ChainedSpeechEngine(
                listOf(dead, FakeAsr(SpeechBackend.OnDeviceModel, available = false)),
            ).isAvailable(),
            "an all-dead chain must not offer a microphone that cannot work",
        )
    }

    @Test
    fun `download progress is passed through untouched`() {
        // The strip needs the byte count to show movement during the model download;
        // the chain must not swallow it while deciding what to do next.
        val advanced = FakeAsr(SpeechBackend.Advanced)
        val out = Recording()
        ChainedSpeechEngine(listOf(advanced)).start(out)

        advanced.listener!!.onDownloading(2_000_000)
        advanced.listener!!.onDownloading(9_000_000)
        advanced.listener!!.onReady()

        // The trailing "ready" is the test's own: the model arrived and the mic opened.
        assertEquals(
            listOf("ready", "download:2000000", "download:9000000", "ready"),
            out.events,
        )
    }

    @Test
    fun `an engine that fails by throwing hands the attempt to the next one`() {
        // A binding that throws out of start() must not crash the tap that asked for
        // dictation. The chain promises that every failure is REPORTED, so a throw is
        // treated exactly like an engine saying "I am not here".
        val broken = FakeAsr(SpeechBackend.Advanced, throwOnStart = true)
        val framework = FakeAsr(SpeechBackend.DeviceRecognizer)
        val out = Recording()
        ChainedSpeechEngine(listOf(broken, framework)).start(out)

        assertEquals(1, broken.starts, "a throwing engine is not retried")
        assertEquals(1, framework.starts, "the next engine gets the attempt")
        assertEquals(listOf("ready"), out.events, "the user hears nothing about the broken one")
    }

    @Test
    fun `a chain whose every engine throws reports one failure and ends`() {
        // Otherwise the strip would sit on "listening" forever with no engine behind it.
        val advanced = FakeAsr(SpeechBackend.Advanced, throwOnStart = true)
        val framework = FakeAsr(SpeechBackend.DeviceRecognizer, throwOnStart = true)
        val out = Recording()
        ChainedSpeechEngine(listOf(advanced, framework)).start(out)

        assertEquals(1, advanced.starts)
        assertEquals(1, framework.starts)
        assertEquals(listOf("error:Unavailable", "end"), out.events)
    }

    @Test
    fun `a cancelled attempt cannot be resurrected`() {
        val advanced = FakeAsr(SpeechBackend.Advanced)
        val out = Recording()
        val chain = ChainedSpeechEngine(listOf(advanced))
        chain.start(out)

        chain.cancel()
        advanced.listener!!.onPartial("typed after the user pressed discard")
        advanced.listener!!.onFinal("typed after discard")
        advanced.listener!!.onEnd()

        assertEquals(listOf("ready"), out.events, "only what happened before the discard is real")
        assertNull(out.events.firstOrNull { it.startsWith("final") }, "nothing may be typed")
    }
}
