@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.playback

import java.util.concurrent.CancellationException
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionSeekGateTest {
    @Test
    fun `absolute acknowledgement is correlated in the result before consumer delivery`() = runTest {
        val releaseDelivery = CompletableDeferred<Unit>()
        val fixture = openSeekable(onEvent = { if (it is SubscriptionEvent.Skipped) releaseDelivery.await() })
        val seeking = async { fixture.subscription.seek(absoluteSeek()) }
        runCurrent()
        fixture.connection.emit(SubscriptionEvent.Skipped(true, SkipOutcome.ACCEPTED, 39_000_000, null))
        runCurrent()
        assertEquals(39.seconds, (seeking.await() as SubscriptionSeekResult.AcceptedAt).position)
        releaseDelivery.complete(Unit)
        fixture.close()
    }

    @Test
    fun `reached reader coordinate rejects invalid duration`() {
        assertThrows(IllegalArgumentException::class.java) { SubscriptionSeekResult.AcceptedAt(Duration.INFINITE) }
        assertThrows(IllegalArgumentException::class.java) { SubscriptionSeekResult.AcceptedAt((-1).seconds) }
    }

    @Test
    fun `accepted skip discards withheld packets before the acknowledgement`() = runTest {
        val fixture = openSeekable()
        fixture.connection.emit(packet(presentationTimeUs = 10L))
        runCurrent()

        val seeking = async { fixture.subscription.seek(absoluteSeek()) }
        runCurrent()
        fixture.connection.emit(packet(presentationTimeUs = 20L))
        fixture.connection.emit(SubscriptionEvent.Dropped(3L))
        fixture.connection.emit(SubscriptionEvent.Status(SubscriptionCondition.STATUS_REPORTED))
        runCurrent()

        assertEquals(
            listOf("started", "packet:10", "status"),
            fixture.received,
            "Withheld packets must not reach the consumer while the gate is pending",
        )

        fixture.connection.emit(skipped(SkipOutcome.ACCEPTED))
        runCurrent()

        assertSame(SubscriptionSeekResult.Accepted, seeking.await())
        assertEquals(listOf("started", "packet:10", "status", "skipped"), fixture.received)
        assertEquals(3L, fixture.subscription.diagnostics.value.droppedPacketCount)
        fixture.connection.emit(packet(presentationTimeUs = 30L))
        runCurrent()
        assertEquals(
            listOf("started", "packet:10", "status", "skipped", "packet:13"),
            fixture.received,
            "The resumed keyframe is rebased one frame after the last delivered packet",
        )
        fixture.close()
    }

    @Test
    fun `rejected skip replays withheld packets in committed order before the acknowledgement`() =
        runTest {
            val fixture = openSeekable()
            val seeking = async { fixture.subscription.seek(relativeSeek()) }
            runCurrent()
            fixture.connection.emit(packet(presentationTimeUs = 40L))
            fixture.connection.emit(SubscriptionEvent.Dropped(2L))
            fixture.connection.emit(packet(presentationTimeUs = 50L))
            runCurrent()
            assertEquals(listOf("started"), fixture.received)

            fixture.connection.emit(skipped(SkipOutcome.REJECTED))
            runCurrent()

            assertSame(SubscriptionSeekResult.Rejected, seeking.await())
            assertEquals(
                listOf("started", "packet:40", "dropped", "packet:50", "skipped"),
                fixture.received,
                "A rejected skip must replay every withheld event before its acknowledgement",
            )
            assertTrue(fixture.subscription.state.value is SubscriptionState.Playable)
            fixture.close()
        }

    @Test
    fun `refused skip command replays withheld packets without a discontinuity`() = runTest {
        val fixture = openSeekable()
        val packetWithheld = CompletableDeferred<Unit>()
        fixture.connection.skipAction = {
            packetWithheld.await()
            SubscriptionOperationResult.AccessDenied
        }
        val seeking = async { fixture.subscription.seek(absoluteSeek()) }
        runCurrent()
        fixture.connection.emit(packet(presentationTimeUs = 60L))
        runCurrent()
        assertEquals(
            listOf("started"),
            fixture.received,
            "The gate must withhold packets until the command resolves",
        )

        packetWithheld.complete(Unit)
        runCurrent()

        val result = seeking.await()
        assertTrue(result is SubscriptionSeekResult.Refused)
        assertEquals(
            SubscriptionOperationFailure.ACCESS_DENIED,
            (result as SubscriptionSeekResult.Refused).failure,
        )
        assertEquals(listOf("started", "packet:60"), fixture.received)
        assertTrue(fixture.subscription.state.value is SubscriptionState.Playable)
        fixture.close()
    }

    @Test
    fun `a refused command waits for in-flight delivery before replaying`() = runTest {
        val statusEntered = CompletableDeferred<Unit>()
        val releaseStatus = CompletableDeferred<Unit>()
        val fixture = openSeekable(
            onEvent = { event ->
                if (event is SubscriptionEvent.Status) {
                    statusEntered.complete(Unit)
                    releaseStatus.await()
                }
            },
        )
        fixture.connection.skipAction = {
            statusEntered.await()
            SubscriptionOperationResult.ServerRejected
        }

        val seeking = async { fixture.subscription.seek(absoluteSeek()) }
        runCurrent()
        fixture.connection.emit(packet(presentationTimeUs = 150L))
        fixture.connection.emit(SubscriptionEvent.Status(SubscriptionCondition.ERROR_REPORTED))
        runCurrent()

        assertEquals(listOf("started", "status"), fixture.received)
        assertFalse(
            seeking.isCompleted,
            "A replay must not start while the consumer is mid-delivery",
        )

        releaseStatus.complete(Unit)
        runCurrent()

        assertTrue(seeking.await() is SubscriptionSeekResult.Refused)
        assertEquals(listOf("started", "status", "packet:150"), fixture.received)
        fixture.close()
    }

    @Test
    fun `a consumer that joins from a replay fails loudly instead of deadlocking`() = runTest {
        lateinit var handle: ActiveSubscription
        val fixture = openSeekable(
            onEvent = { event -> if (event is SubscriptionEvent.Packet) handle.close() },
        )
        handle = fixture.subscription
        val packetWithheld = CompletableDeferred<Unit>()
        fixture.connection.skipAction = {
            packetWithheld.await()
            SubscriptionOperationResult.NotSupported
        }

        val seeking = async { fixture.subscription.seek(absoluteSeek()) }
        runCurrent()
        fixture.connection.emit(packet(presentationTimeUs = 160L))
        runCurrent()
        assertEquals(listOf("started"), fixture.received)

        packetWithheld.complete(Unit)
        runCurrent()

        assertTrue(seeking.await() is SubscriptionSeekResult.Refused)
        val terminal = fixture.subscription.state.value as SubscriptionState.Terminal
        assertSame(SubscriptionTerminalReason.ConsumerFailed, terminal.reason)
        fixture.manager.closeAndJoin()
    }

    @Test
    fun `missing acknowledgement invalidates the subscription instead of mixing packets`() =
        runTest {
            val fixture = openSeekable()
            val seeking = async { fixture.subscription.seek(absoluteSeek()) }
            runCurrent()
            fixture.connection.emit(packet(presentationTimeUs = 70L))
            runCurrent()

            advanceTimeBy(GATE_TIMEOUT - 1.milliseconds)
            runCurrent()
            assertFalse(seeking.isCompleted, "The gate must wait for its full deadline")

            advanceTimeBy(2.milliseconds)
            runCurrent()

            val result = seeking.await()
            assertTrue(result is SubscriptionSeekResult.Invalidated)
            assertEquals(
                SubscriptionSeekInvalidation.ACKNOWLEDGEMENT_TIMEOUT,
                (result as SubscriptionSeekResult.Invalidated).cause,
            )
            assertEquals(listOf("started"), fixture.received)
            val terminal = fixture.subscription.state.value as SubscriptionState.Terminal
            val reason = terminal.reason as SubscriptionTerminalReason.SeekInvalidated
            assertEquals(SubscriptionSeekInvalidation.ACKNOWLEDGEMENT_TIMEOUT, reason.cause)
            fixture.manager.closeAndJoin()
            assertEquals(1, fixture.connection.unsubscribeCount)
        }

    @Test
    fun `unacknowledged near-live positioning invalidates without replaying packets`() = runTest {
        val fixture = openSeekable()
        val seeking = async { fixture.subscription.seek(SubscriptionSeekTarget.Live) }
        runCurrent()
        fixture.connection.emit(packet(presentationTimeUs = 80L))
        runCurrent()

        advanceTimeBy(GATE_TIMEOUT - 1.milliseconds)
        runCurrent()
        assertFalse(
            seeking.isCompleted,
            "Near-live positioning must wait for the normal seek deadline",
        )
        assertEquals(listOf("started"), fixture.received)

        advanceTimeBy(2.milliseconds)
        runCurrent()

        val result = seeking.await() as SubscriptionSeekResult.Invalidated
        assertEquals(SubscriptionSeekInvalidation.ACKNOWLEDGEMENT_TIMEOUT, result.cause)
        assertEquals(listOf("started"), fixture.received)
        val terminal = fixture.subscription.state.value as SubscriptionState.Terminal
        val reason = terminal.reason as SubscriptionTerminalReason.SeekInvalidated
        assertEquals(SubscriptionSeekInvalidation.ACKNOWLEDGEMENT_TIMEOUT, reason.cause)
        assertEquals(listOf(SubscriptionSeekTarget.Live), fixture.connection.seekTargets)
        fixture.manager.closeAndJoin()
        assertEquals(1, fixture.connection.unsubscribeCount)
    }

    @Test
    fun `a timed out command is uncertain and never replays withheld packets`() = runTest {
        val fixture = openSeekable()
        val packetWithheld = CompletableDeferred<Unit>()
        fixture.connection.skipAction = {
            packetWithheld.await()
            SubscriptionOperationResult.Timeout
        }
        val seeking = async { fixture.subscription.seek(absoluteSeek()) }
        runCurrent()
        fixture.connection.emit(packet(presentationTimeUs = 170L))
        runCurrent()

        packetWithheld.complete(Unit)
        runCurrent()

        val result = seeking.await()
        assertEquals(
            SubscriptionSeekInvalidation.UNCERTAIN_REQUEST_OUTCOME,
            (result as SubscriptionSeekResult.Invalidated).cause,
        )
        assertEquals(listOf("started"), fixture.received)
        fixture.manager.closeAndJoin()
    }

    @Test
    fun `no packet reaches the consumer after the gate invalidates the subscription`() = runTest {
        val fixture = openSeekable()
        val seeking = async { fixture.subscription.seek(absoluteSeek()) }
        runCurrent()
        advanceTimeBy(GATE_TIMEOUT + 1.milliseconds)
        runCurrent()
        assertTrue(seeking.await() is SubscriptionSeekResult.Invalidated)

        fixture.connection.emit(packet(presentationTimeUs = 180L))
        runCurrent()

        assertEquals(
            listOf("started"),
            fixture.received,
            "An invalidated gate must not hand one more uncertain packet to the readers",
        )
        fixture.manager.closeAndJoin()
    }

    @Test
    fun `gate overflow fails loudly and ends the subscription`() = runTest {
        val fixture = openSeekable(gate = SeekGateSettings(maximumPendingEvents = 2))
        val seeking = async { fixture.subscription.seek(absoluteSeek()) }
        runCurrent()
        repeat(3) { index -> fixture.connection.emit(packet(presentationTimeUs = index.toLong())) }
        runCurrent()

        val result = seeking.await()
        assertTrue(result is SubscriptionSeekResult.Invalidated)
        assertEquals(
            SubscriptionSeekInvalidation.PENDING_QUEUE_OVERFLOW,
            (result as SubscriptionSeekResult.Invalidated).cause,
        )
        assertEquals(listOf("started"), fixture.received)
        val terminal = fixture.subscription.state.value as SubscriptionState.Terminal
        val reason = terminal.reason as SubscriptionTerminalReason.SeekInvalidated
        assertEquals(SubscriptionSeekInvalidation.PENDING_QUEUE_OVERFLOW, reason.cause)
        fixture.manager.closeAndJoin()
    }

    @Test
    fun `gate overflow also bounds withheld payload bytes`() = runTest {
        val fixture = openSeekable(gate = SeekGateSettings(maximumPendingBytes = 6L))
        val seeking = async { fixture.subscription.seek(absoluteSeek()) }
        runCurrent()
        fixture.connection.emit(packet(payload = CountingBinary(ByteArray(4))))
        fixture.connection.emit(packet(payload = CountingBinary(ByteArray(4))))
        runCurrent()

        val result = seeking.await()
        assertEquals(
            SubscriptionSeekInvalidation.PENDING_QUEUE_OVERFLOW,
            (result as SubscriptionSeekResult.Invalidated).cause,
        )
        fixture.manager.closeAndJoin()
    }

    @Test
    fun `unrecognized acknowledgement invalidates rather than guessing`() = runTest {
        val fixture = openSeekable()
        val seeking = async { fixture.subscription.seek(absoluteSeek()) }
        runCurrent()
        fixture.connection.emit(packet(presentationTimeUs = 90L))
        fixture.connection.emit(skipped(SkipOutcome.UNKNOWN))
        runCurrent()

        val result = seeking.await()
        assertEquals(
            SubscriptionSeekInvalidation.UNRECOGNIZED_ACKNOWLEDGEMENT,
            (result as SubscriptionSeekResult.Invalidated).cause,
        )
        assertEquals(
            listOf("started", "skipped"),
            fixture.received,
            "Uncertain acknowledgements must discard withheld packets",
        )
        fixture.manager.closeAndJoin()
    }

    @Test
    fun `a second request is rejected while one acknowledgement is pending`() = runTest {
        val fixture = openSeekable()
        val first = async { fixture.subscription.seek(absoluteSeek()) }
        runCurrent()

        assertSame(SubscriptionSeekResult.AlreadyPending, fixture.subscription.seek(relativeSeek()))
        assertEquals(1, fixture.connection.seekTargets.size)

        fixture.connection.emit(skipped(SkipOutcome.ACCEPTED))
        runCurrent()
        assertSame(SubscriptionSeekResult.Accepted, first.await())
        fixture.close()
    }

    @Test
    fun `a subscription without a granted timeshift buffer is never gated`() = runTest {
        val fixture = openSeekable(grantedTimeshiftSeconds = null)
        assertNull(fixture.subscription.grantedTimeshiftPeriod)
        assertSame(SubscriptionSeekResult.NotSeekable, fixture.subscription.seek(absoluteSeek()))
        assertTrue(fixture.connection.seekTargets.isEmpty())

        fixture.connection.emit(packet(presentationTimeUs = 100L))
        runCurrent()
        assertEquals(listOf("started", "packet:100"), fixture.received)
        fixture.close()
    }

    @Test
    fun `a refused timeshift grant of zero seconds is not seekable`() = runTest {
        val fixture = openSeekable(grantedTimeshiftSeconds = 0L)
        assertNull(fixture.subscription.grantedTimeshiftPeriod)
        assertSame(SubscriptionSeekResult.NotSeekable, fixture.subscription.seek(absoluteSeek()))
        fixture.close()
    }

    @Test
    fun `the requested timeshift period reaches subscribe and its grant is published`() = runTest {
        val fixture = openSeekable(requested = 120.seconds, grantedTimeshiftSeconds = 90L)
        assertEquals(120.seconds, fixture.connection.requestedTimeshiftPeriod)
        assertEquals(90.seconds, fixture.subscription.grantedTimeshiftPeriod)
        fixture.close()
    }

    @Test
    fun `server pause and resume require a live positive grant and propagate typed outcomes`() =
        runTest {
            val fixture = openSeekable()

            assertTrue(fixture.subscription.setSpeed(0) is SubscriptionOperationResult.Ok)
            assertTrue(fixture.subscription.setSpeed(100) is SubscriptionOperationResult.Ok)
            assertEquals(listOf(0, 100), fixture.connection.speeds)

            fixture.connection.speedAction = { SubscriptionOperationResult.ServerRejected }
            assertSame(
                SubscriptionOperationResult.ServerRejected,
                fixture.subscription.setSpeed(0),
            )
            val cancellation = CancellationException("scripted")
            fixture.connection.speedAction = { throw cancellation }
            val caught = try {
                fixture.subscription.setSpeed(100)
                null
            } catch (failure: CancellationException) {
                failure
            }
            assertSame(cancellation, caught)

            assertSame(SubscriptionCloseResult.CLOSED, fixture.subscription.close())
            assertSame(SubscriptionCloseResult.CLOSED, fixture.subscription.close())
            assertSame(
                SubscriptionOperationResult.TransportUnavailable,
                fixture.subscription.setSpeed(100),
            )
            fixture.manager.closeAndJoin()
            assertEquals(1, fixture.connection.calls.count { it == Call.UNSUBSCRIBE })
        }

    @Test
    fun `speed commands run on the subscription dispatcher`() = runTest {
        val subscriptionDispatcher = StandardTestDispatcher(testScheduler, "subscription")
        val fixture = openSeekable(dispatcher = subscriptionDispatcher)
        var observedDispatcher: ContinuationInterceptor? = null
        fixture.connection.speedAction = {
            observedDispatcher = currentCoroutineContext()[ContinuationInterceptor]
            SubscriptionOperationResult.Ok(Unit)
        }

        assertTrue(fixture.subscription.setSpeed(0) is SubscriptionOperationResult.Ok)
        assertSame(subscriptionDispatcher, observedDispatcher)
        fixture.close()
    }

    @Test
    fun `a stream terminal resolves a pending request without a seek terminal reason`() = runTest {
        val fixture = openSeekable()
        val seeking = async { fixture.subscription.seek(absoluteSeek()) }
        runCurrent()
        fixture.connection.emit(packet(presentationTimeUs = 110L))
        fixture.connection.emit(
            SubscriptionEvent.Terminated(SubscriptionTermination.TRANSPORT_CLOSED),
        )
        runCurrent()

        assertSame(SubscriptionSeekResult.SubscriptionEnded, seeking.await())
        val terminal = fixture.subscription.state.value as SubscriptionState.Terminal
        assertSame(SubscriptionTerminalReason.TransportClosed, terminal.reason)
        fixture.manager.closeAndJoin()
    }

    @Test
    fun `local close resolves a pending request and never invalidates the gate`() = runTest {
        val fixture = openSeekable()
        val seeking = async { fixture.subscription.seek(absoluteSeek()) }
        runCurrent()
        fixture.connection.emit(packet(presentationTimeUs = 120L))
        runCurrent()

        assertEquals(SubscriptionCloseResult.CLOSED, fixture.subscription.close())
        assertSame(SubscriptionSeekResult.SubscriptionEnded, seeking.await())
        val terminal = fixture.subscription.state.value as SubscriptionState.Terminal
        assertSame(SubscriptionTerminalReason.Closed, terminal.reason)
        fixture.manager.closeAndJoin()
    }

    @Test
    fun `caller cancellation propagates and leaves the gate under subscription ownership`() =
        runTest {
            val fixture = openSeekable()
            val seeking = async { fixture.subscription.seek(absoluteSeek()) }
            runCurrent()
            fixture.connection.emit(packet(presentationTimeUs = 130L))
            runCurrent()

            seeking.cancel()
            runCurrent()
            assertTrue(seeking.isCancelled)
            assertEquals(listOf("started"), fixture.received)

            fixture.connection.emit(skipped(SkipOutcome.REJECTED))
            runCurrent()
            assertEquals(
                listOf("started", "packet:130", "skipped"),
                fixture.received,
                "An abandoned request must still resolve its gate in committed order",
            )
            assertTrue(fixture.subscription.state.value is SubscriptionState.Playable)
            fixture.close()
        }

    @Test
    fun `an acknowledgement outside a pending request is delivered unchanged`() = runTest {
        val fixture = openSeekable()
        fixture.connection.emit(skipped(SkipOutcome.ACCEPTED))
        fixture.connection.emit(packet(presentationTimeUs = 140L))
        runCurrent()

        assertEquals(listOf("started", "skipped", "packet:140"), fixture.received)
        assertTrue(fixture.connection.seekTargets.isEmpty())
        fixture.close()
    }

    @Test
    fun `seek is rejected after the subscription reached a terminal state`() = runTest {
        val fixture = openSeekable()
        fixture.connection.emit(SubscriptionEvent.Stopped(SubscriptionCondition.NO_DETAIL))
        runCurrent()

        assertSame(
            SubscriptionSeekResult.SubscriptionEnded,
            fixture.subscription.seek(absoluteSeek()),
        )
        assertTrue(fixture.connection.seekTargets.isEmpty())
        fixture.manager.closeAndJoin()
    }

    @Test
    fun `seek targets validate their media coordinates`() {
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionSeekTarget.Absolute((-1).seconds)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionSeekTarget.Absolute(Duration.INFINITE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionSeekTarget.Relative(-Duration.INFINITE)
        }
        assertEquals(
            "SubscriptionSeekTarget.Absolute(<redacted>)",
            SubscriptionSeekTarget.Absolute(5.seconds).toString(),
        )
        assertEquals(
            "SubscriptionSeekTarget.Relative(<redacted>)",
            SubscriptionSeekTarget.Relative((-5).seconds).toString(),
        )
    }

    @Test
    fun `an invalid timeshift request is rejected before an identifier is allocated`() = runTest {
        val connection = RecordingSubscriptionConnection()
        val manager = createSubscriptionManager(
            connection,
            StandardTestDispatcher(testScheduler),
            SeekGateSettings(),
        )
        manager.startAdmission()

        val failure = runCatching {
            manager.open(SubscriptionChannelId(1L), SubscriptionEventConsumer {}, (-1).seconds)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(connection.calls.isEmpty())
        manager.closeAndJoin()
    }

    private suspend fun TestScope.openSeekable(
        requested: Duration = 300.seconds,
        grantedTimeshiftSeconds: Long? = 300L,
        gate: SeekGateSettings = SeekGateSettings(),
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        onEvent: suspend (SubscriptionEvent) -> Unit = {},
    ): SeekFixture {
        val connection = RecordingSubscriptionConnection()
        connection.subscribeAction = { successfulConfirmation(grantedTimeshiftSeconds) }
        val manager = createSubscriptionManager(
            connection,
            dispatcher,
            gate,
        )
        manager.startAdmission()
        val received = ArrayList<String>()
        val opened = async {
            manager.open(
                SubscriptionChannelId(1L),
                SubscriptionEventConsumer { event ->
                    received += event.label()
                    onEvent(event)
                },
                requested,
            )
        }
        runCurrent()
        connection.emit(started(stream()))
        runCurrent()
        val subscription = (opened.await() as SubscriptionOpenResult.Opened).subscription
        return SeekFixture(connection, manager, subscription, received)
    }
}

private class SeekFixture(
    internal val connection: RecordingSubscriptionConnection,
    internal val manager: SubscriptionManager,
    internal val subscription: ActiveSubscription,
    internal val received: List<String>,
) {
    internal suspend fun close() {
        subscription.close()
        manager.closeAndJoin()
    }
}

private val GATE_TIMEOUT = 5.seconds

private fun absoluteSeek(): SubscriptionSeekTarget = SubscriptionSeekTarget.Absolute(30.seconds)

private fun relativeSeek(): SubscriptionSeekTarget = SubscriptionSeekTarget.Relative((-10).seconds)

private fun SubscriptionEvent.label(): String = when (this) {
    is SubscriptionEvent.Started -> "started"
    is SubscriptionEvent.Packet -> "packet:${presentationTimeUs}"
    is SubscriptionEvent.Dropped -> "dropped"
    is SubscriptionEvent.Skipped -> "skipped"
    is SubscriptionEvent.Status -> "status"
    is SubscriptionEvent.Stopped -> "stopped"
    is SubscriptionEvent.Terminated -> "terminated"
    is SubscriptionEvent.Grace -> "grace"
    is SubscriptionEvent.Speed -> "speed"
    is SubscriptionEvent.Timeshift -> "timeshift"
    is SubscriptionEvent.Queue -> "queue"
    is SubscriptionEvent.Signal -> "signal"
    is SubscriptionEvent.Descramble -> "descramble"
}
