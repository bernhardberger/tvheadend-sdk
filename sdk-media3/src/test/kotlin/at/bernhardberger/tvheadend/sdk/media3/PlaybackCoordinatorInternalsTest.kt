@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package at.bernhardberger.tvheadend.sdk.media3

import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class PlaybackCoordinatorInternalsTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `dispatch throwing after admission cannot replace the claimed result`(concurrent: Boolean) = runTest {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var worker: Thread? = null
        val looper = object : CoordinatorLooper {
            override fun isCurrent(): Boolean = false
            override fun remove(runnable: Runnable) = Unit
            override fun post(runnable: Runnable): Boolean {
                if (concurrent) {
                    worker = Thread(runnable).also { it.start() }
                    check(started.await(5, TimeUnit.SECONDS)) { "Claim did not start" }
                } else {
                    runnable.run()
                }
                throw IOException("scripted dispatch failure after admission")
            }
        }
        val result = async {
            PlayerLooperExecutor(looper).execute(PlayerOperationTicket()) {
                started.countDown()
                if (concurrent) check(finish.await(5, TimeUnit.SECONDS)) { "Claim did not finish" }
                42
            }
        }
        try {
            runCurrent()
            if (concurrent) assertFalse(result.isCompleted, "Claimed work must settle its own result")
        } finally {
            finish.countDown()
            worker?.join(5_000)
        }
        assertEquals(LooperOperationResult.Success(42), result.await())
    }

    @ParameterizedTest
    @ValueSource(strings = ["operation", "post", "looper check"])
    fun `foreign cancellation inside a looper operation reports unavailability`(stage: String) = runTest {
        val cancellation = CancellationException("scripted cancellation")
        val looper = object : CoordinatorLooper {
            override fun remove(runnable: Runnable) = Unit
            override fun post(runnable: Runnable): Boolean = throw cancellation
            override fun isCurrent(): Boolean {
                if (stage == "looper check") throw cancellation
                return stage == "operation"
            }
        }
        val result = PlayerLooperExecutor(looper).execute(PlayerOperationTicket()) { throw cancellation }
        assertSame(LooperOperationResult.Unavailable, result)
    }

    @Test
    fun `real caller cancellation propagates and fences queued looper work`() = runTest {
        val looper = QueuedCoordinatorLooper()
        val ticket = PlayerOperationTicket()
        var mutations = 0
        val operation = async { PlayerLooperExecutor(looper).execute(ticket) { mutations += 1 } }
        runCurrent()

        operation.cancel(CancellationException("scripted caller cancellation"))
        assertTrue(runCatching { operation.await() }.exceptionOrNull() is CancellationException)
        looper.runAll()
        assertTrue(ticket.isCancelled())
        assertEquals(0, mutations)
    }

    @Test
    fun `cancellation before current looper claim prevents inline mutation`() = runTest {
        val executor = PlayerLooperExecutor(CurrentCoordinatorLooper)
        val ticket = PlayerOperationTicket()
        var mutations = 0

        assertTrue(ticket.cancel())
        val result = executor.execute(ticket) {
            mutations += 1
        }

        assertSame(LooperOperationResult.Cancelled, result)
        assertEquals(0, mutations)
    }

    @Test
    fun `cancellation before looper claim prevents mutation`() = runTest {
        val looper = QueuedCoordinatorLooper()
        val executor = PlayerLooperExecutor(looper)
        val ticket = PlayerOperationTicket()
        var mutations = 0

        val result = async {
            executor.execute(ticket) {
                mutations += 1
            }
        }
        runCurrent()
        assertTrue(ticket.cancel())
        looper.runAll()
        runCurrent()

        assertSame(LooperOperationResult.Cancelled, result.await())
        assertEquals(0, mutations)
    }

    @Test
    fun `cancellation after claim cannot split a looper transaction`() = runTest {
        val looper = QueuedCoordinatorLooper()
        val executor = PlayerLooperExecutor(looper)
        val ticket = PlayerOperationTicket()
        val mutations = mutableListOf<String>()

        val result = async {
            executor.execute(ticket) {
                mutations += "first"
                assertFalse(ticket.cancel())
                mutations += "second"
            }
        }
        runCurrent()
        looper.runAll()
        runCurrent()

        assertTrue(result.await() is LooperOperationResult.Success)
        assertEquals(listOf("first", "second"), mutations)
    }

    @Test
    fun `checked looper failure completes unavailable and later work still runs`() = runTest {
        val looper = QueuedCoordinatorLooper()
        val executor = PlayerLooperExecutor(looper)
        val failed = async {
            executor.execute<Unit>(PlayerOperationTicket()) {
                throw IOException("scripted")
            }
        }
        runCurrent()
        looper.runAll()
        runCurrent()
        assertSame(LooperOperationResult.Unavailable, failed.await())

        val recovered = async {
            executor.execute(PlayerOperationTicket()) { "recovered" }
        }
        runCurrent()
        looper.runAll()
        runCurrent()
        assertEquals("recovered", (recovered.await() as LooperOperationResult.Success).value)
    }

    @Test
    fun `mailbox keeps latest observation and sticky terminal state in constant space`() = runTest {
        val epoch = PlaybackReportEpoch()
        val mailbox = PlaybackProgressMailbox()

        mailbox.offer(report(epoch, 90, terminal = false, watched = false))
        mailbox.offer(report(epoch, 10, terminal = true, watched = true))
        mailbox.offer(report(epoch, 5, terminal = false, watched = false))

        val pending = mailbox.next()
        assertEquals(5.seconds, pending?.progress?.position)
        assertTrue(requireNotNull(pending).progress.markWatched)
        assertTrue(pending.terminal)
    }

    @Test
    fun `mailbox replaces stale ordinary work but preserves an earlier terminal barrier`() = runTest {
        val first = PlaybackReportEpoch()
        val second = PlaybackReportEpoch()
        val mailbox = PlaybackProgressMailbox()

        mailbox.offer(report(first, 10, terminal = false, watched = false))
        mailbox.offer(report(second, 20, terminal = false, watched = false))
        assertSame(second, mailbox.next()?.epoch)

        mailbox.offer(report(first, 30, terminal = true, watched = false))
        mailbox.offer(report(second, 40, terminal = false, watched = false))
        assertSame(first, mailbox.next()?.epoch)
    }

    @Test
    fun `mailbox drops invalid generation work and seals without replay`() = runTest {
        var current = true
        val epoch = PlaybackReportEpoch { current }
        val mailbox = PlaybackProgressMailbox()
        mailbox.offer(report(epoch, 10, terminal = false, watched = false))

        current = false
        mailbox.discardInvalid()
        mailbox.seal()

        assertEquals(null, mailbox.next())
    }

    private fun report(
        epoch: PlaybackReportEpoch,
        positionSeconds: Long,
        terminal: Boolean,
        watched: Boolean,
    ): PendingPlaybackProgress = PendingPlaybackProgress(
        epoch = epoch,
        target = TestCoordinatorRecordingTarget(),
        growingLease = null,
        progress = DvrPlaybackProgress(positionSeconds.seconds, watched),
        terminal = terminal,
    )
}

private data object CurrentCoordinatorLooper : CoordinatorLooper {
    override fun post(runnable: Runnable): Boolean = error("current-looper work must not post")

    override fun remove(runnable: Runnable) = Unit

    override fun isCurrent(): Boolean = true
}

internal class QueuedCoordinatorLooper : CoordinatorLooper {
    private val queue = ArrayDeque<Runnable>()
    private var current = false
    var accepting = true

    override fun post(runnable: Runnable): Boolean {
        if (!accepting) return false
        queue += runnable
        return true
    }

    override fun remove(runnable: Runnable) {
        queue.remove(runnable)
    }

    override fun isCurrent(): Boolean = current

    fun runAll() {
        while (queue.isNotEmpty()) {
            val runnable = queue.removeFirst()
            current = true
            try {
                runnable.run()
            } finally {
                current = false
            }
        }
    }
}
