package com.sangiorgisrl.screenlink.transfer

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rules that keep the file browser from crashing the app.
 *
 * The bug this exists for: a JSch SFTP call made from the main thread dies with
 * `NetworkOnMainThreadException`, and the user just sees "Could not open the
 * folder". That is invisible in a code review and instant in the field, so the
 * rule is asserted here instead of trusted.
 *
 * The second rule is JSch's own: one SFTP channel is single-threaded, so two taps
 * must queue rather than share it.
 */
class BrowseSessionTest {

    /** What the fake transport records about every call it receives. */
    private class Recorder {
        val calls = CopyOnWriteArrayList<String>()
        val threads = CopyOnWriteArrayList<String>()
        private val live = AtomicInteger()
        val peak = AtomicInteger()

        fun enter(transportId: Int, call: String, holdMillis: Long, gateMillis: Long) {
            val now = live.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            calls += "$transportId.$call"
            threads += Thread.currentThread().name
            if (gateMillis > 0L) {
                // Stands in for a socket that takes a moment: makes overlap observable.
                runCatching { Thread.sleep(gateMillis) }
            }
            if (holdMillis > 0L) runCatching { Thread.sleep(holdMillis) }
            live.decrementAndGet()
        }
    }

    private class Fake(
        private val id: Int,
        private val recorder: Recorder,
        private val holdMillis: Long = 0L,
        private val failOn: String? = null,
        private val entered: CountDownLatch? = null,
        private val slowMillis: Long = 0L,
        private val failWith: () -> Throwable = { IOException("server said no") },
        private val aliveAnswer: () -> Boolean = { true },
    ) : TransferTransport {

        val closes = AtomicInteger()

        /**
         * JSch's answer about the session underneath the channel. The default is
         * "alive"; the dead-link tests below hand in the other answer, or one that
         * throws, because a library falling apart is entitled to fail at being asked.
         */
        override fun isAlive(): Boolean = aliveAnswer()

        private fun call(name: String) {
            entered?.countDown()
            recorder.enter(id, name, holdMillis, slowMillis)
            if (name == failOn) throw failWith()
        }

        override fun home(): String {
            call("home")
            return "/home/user"
        }

        override fun list(path: String): List<RemoteEntry> {
            call("list")
            return listOf(
                RemoteEntry(name = "notes.txt", path = "$path/notes.txt", isDirectory = false, sizeBytes = 12L),
            )
        }

        override fun mkdir(path: String) {
            call("mkdir")
        }

        override fun upload(source: LocalSource, remotePath: String, progress: ProgressListener) {
            call("upload")
        }

        override fun download(remotePath: String, sink: LocalSink, progress: ProgressListener) {
            call("download")
        }

        override fun close() {
            closes.incrementAndGet()
        }
    }

    @Test
    fun `a browse call never runs on the thread that tapped the button`() {
        // Stands in for the main thread: a single thread that Android would kill the
        // moment a socket is touched on it.
        val fakeMain = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "fake-main") }
            .asCoroutineDispatcher()
        try {
            val recorder = Recorder()
            var opens = 0
            val session = BrowseSession(open = { opens++; Fake(1, recorder) })

            runBlocking(fakeMain) {
                // The view model's bookkeeping DOES belong on the caller's thread;
                // only the SFTP call has to leave it.
                assertTrue(Thread.currentThread().name.startsWith("fake-main"))
                val result = session.use { transport -> transport.home() to transport.list("/home/user") }
                assertEquals(1, result.second.size)
            }

            assertEquals(listOf("1.home", "1.list"), recorder.calls.toList())
            assertEquals(1, opens)
            assertFalse(
                recorder.threads.any { it.startsWith("fake-main") },
                "SFTP ran on the caller's thread: ${recorder.threads}",
            )
        } finally {
            fakeMain.close()
        }
    }

    @Test
    fun `one session serves every browse call with a single handshake`() = runBlocking {
        val recorder = Recorder()
        var opens = 0
        val session = BrowseSession(open = { opens++; Fake(1, recorder) })

        session.use { transport -> transport.home() }
        session.use { transport -> transport.list("/srv") }
        session.use { transport -> transport.mkdir("/srv/incoming") }

        // A handshake per tap would be a handshake per tap: slow, and sshd's session
        // cap makes the second one fail outright.
        assertEquals(1, opens)
        assertTrue(session.isOpen)
        assertEquals(listOf("1.home", "1.list", "1.mkdir"), recorder.calls.toList())
    }

    @Test
    fun `a call that failed drops the channel so the next tap reconnects`() = runBlocking {
        val recorder = Recorder()
        val made = CopyOnWriteArrayList<Fake>()
        val session = BrowseSession(open = {
            Fake(made.size + 1, recorder, failOn = if (made.isEmpty()) "list" else null)
                .also { made += it }
        })

        val failed = runCatching { session.use { transport -> transport.list("/srv") } }
        assertTrue(failed.exceptionOrNull() is IOException)
        assertEquals(1, made[0].closes.get())
        assertFalse(session.isOpen)

        val entries = session.use { transport -> transport.list("/srv") }
        assertEquals(2, made.size)
        assertEquals(1, entries.size)
        assertEquals(0, made[1].closes.get())
        assertTrue(session.isOpen)
    }

    @Test
    fun `a failed handshake leaves nothing behind to close`() = runBlocking {
        val session = BrowseSession(open = { throw IOException("Auth fail") })

        val failed = runCatching { session.use { transport -> transport.home() } }

        assertTrue(failed.exceptionOrNull() is IOException)
        assertFalse(session.isOpen)
    }

    @Test
    fun `two taps never share the single-threaded channel`() = runBlocking {
        val recorder = Recorder()
        val session = BrowseSession(open = { Fake(1, recorder, slowMillis = 25L) })

        val taps = (1..4).map { index ->
            launch {
                session.use { transport -> transport.list("/srv/$index") }
            }
        }
        taps.forEach { job -> job.join() }

        assertEquals(4, recorder.calls.size)
        assertEquals(1, recorder.peak.get(), "two SFTP calls overlapped: ${recorder.calls}")
    }

    @Test
    fun `closing the screen hands the channel back and the next screen reconnects`() = runBlocking {
        val recorder = Recorder()
        val made = CopyOnWriteArrayList<Fake>()
        val session = BrowseSession(open = { Fake(made.size + 1, recorder).also { made += it } })

        session.use { transport -> transport.home() }
        session.close()
        assertEquals(1, made[0].closes.get())
        assertFalse(session.isOpen)
        // Leaving the screen twice (back press, then onCleared) must not double-close.
        session.close()
        assertEquals(1, made[0].closes.get())

        session.use { transport -> transport.home() }
        assertEquals(2, made.size)
        assertEquals(listOf("1.home", "2.home"), recorder.calls.toList())
    }

    @Test
    fun `a cancel in the middle of a browse still says goodbye to the server`() = runBlocking {
        val recorder = Recorder()
        val entered = CountDownLatch(1)
        // The call is still inside the fake socket when the user leaves the screen.
        val fake = Fake(1, recorder, entered = entered, slowMillis = 300L)
        val session = BrowseSession(open = { fake })

        val browse = launch { runCatching { session.use { transport -> transport.list("/srv") } } }
        val started = withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) }
        assertTrue(started, "the browse never started")
        browse.cancelAndJoin()

        // A channel left open until the GC notices is a server holding a dead SFTP
        // session, so closing survives the cancellation.
        assertEquals(1, fake.closes.get())
        assertFalse(session.isOpen)
    }

    // --- the connection that died between two taps ("pipe is closed") ---------

    /** The shape JSch really throws: our stream's pipe error, wrapped in a codeless SftpException. */
    private fun deadLink(): Throwable = SftpException(
        ChannelSftp.SSH_FX_FAILURE,
        "java.io.IOException: Pipe closed",
        IOException("Pipe closed"),
    )

    @Test
    fun `a session the server already forgot about is replaced before the tap speaks`() = runBlocking {
        val recorder = Recorder()
        val made = CopyOnWriteArrayList<Fake>()
        var linkAlive = true
        val session = BrowseSession(open = {
            Fake(made.size + 1, recorder, aliveAnswer = { linkAlive }).also { made += it }
        })

        session.use { transport -> transport.list("/srv") }
        // Between the two taps the link under the channel died - a Wi-Fi that slept,
        // a network that changed, a server that restarted. JSch notices that before
        // we do, and says so.
        linkAlive = false
        val entries = session.use { transport -> transport.list("/srv") }

        // The second tap never spoke on the dead channel, so it never produced a pipe
        // error for the user to read: it paid a handshake instead.
        assertEquals(2, made.size, "the stale channel was reused")
        assertEquals(listOf("1.list", "2.list"), recorder.calls.toList())
        assertEquals(1, made[0].closes.get(), "the dead channel was left open on the server")
        assertEquals(1, entries.size)
    }

    @Test
    fun `a transport that cannot answer whether it is alive is treated as dead`() = runBlocking {
        val recorder = Recorder()
        val made = CopyOnWriteArrayList<Fake>()
        val session = BrowseSession(open = {
            val index = made.size + 1
            Fake(
                index,
                recorder,
                aliveAnswer = {
                    if (index == 1) throw IOException("Pipe closed")
                    true
                },
            ).also { made += it }
        })

        session.use { transport -> transport.home() }
        val home = session.use { transport -> transport.home() }

        // Asking is a hint, not a result: a transport that throws while being asked is
        // simply replaced, and the failure never reaches the screen.
        assertEquals("/home/user", home)
        assertEquals(2, made.size)
        assertEquals(listOf("1.home", "2.home"), recorder.calls.toList())
    }

    @Test
    fun `a browse that died on a closed pipe is run once more on a fresh channel`() = runBlocking {
        val recorder = Recorder()
        val made = CopyOnWriteArrayList<Fake>()
        val session = BrowseSession(open = {
            Fake(
                made.size + 1,
                recorder,
                failOn = if (made.isEmpty()) "list" else null,
                failWith = { deadLink() },
            ).also { made += it }
        })

        val entries = session.use { transport -> transport.list("/srv") }

        // The link died in the MIDDLE of the call, which no liveness check can see.
        // Reading a folder twice is harmless, so the user gets the listing rather
        // than a sentence about a pipe.
        assertEquals(2, made.size)
        assertEquals(listOf("1.list", "2.list"), recorder.calls.toList())
        assertEquals(1, entries.size)
        assertEquals(1, made[0].closes.get())
    }

    @Test
    fun `a folder the server refused is never asked twice`() = runBlocking {
        val recorder = Recorder()
        val made = CopyOnWriteArrayList<Fake>()
        val session = BrowseSession(open = {
            Fake(
                made.size + 1,
                recorder,
                failOn = "list",
                failWith = { SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "Permission denied") },
            ).also { made += it }
        })

        val failed = runCatching { session.use { transport -> transport.list("/srv") } }

        // "Permission denied" is an ANSWER, and a second handshake only makes the
        // user wait ten seconds to be told the same thing again.
        assertTrue(failed.exceptionOrNull() is SftpException)
        assertEquals(1, made.size)
        assertEquals(listOf("1.list"), recorder.calls.toList())
    }

    @Test
    fun `a mkdir is not repeated even when the link died`() = runBlocking {
        val recorder = Recorder()
        val made = CopyOnWriteArrayList<Fake>()
        val session = BrowseSession(open = {
            Fake(
                made.size + 1,
                recorder,
                failOn = if (made.size < 2) "mkdir" else null,
                failWith = { deadLink() },
            ).also { made += it }
        })

        val failed = runCatching {
            session.use(retryOnDeadLink = false) { transport -> transport.mkdir("/srv/incoming") }
        }

        // The first mkdir may have happened on the server and only its reply was
        // lost; repeating it answers "folder already exists", a failure the user
        // did not cause and cannot understand.
        assertTrue(failed.exceptionOrNull() is SftpException)
        assertEquals(1, made.size, "the mkdir was repeated")
        assertEquals(listOf("1.mkdir"), recorder.calls.toList())
    }

    @Test
    fun `a dead link never costs more than one extra handshake`() = runBlocking {
        val recorder = Recorder()
        val made = CopyOnWriteArrayList<Fake>()
        val session = BrowseSession(open = {
            // Every channel this test is given is already dead: the browser must
            // give up rather than dial the server forever.
            Fake(made.size + 1, recorder, failOn = "list", failWith = { deadLink() })
                .also { made += it }
        })

        val failed = runCatching { session.use { transport -> transport.list("/srv") } }

        assertTrue(failed.exceptionOrNull() is SftpException)
        assertEquals(2, made.size, "the browser kept reconnecting: ${recorder.calls}")
    }
}
