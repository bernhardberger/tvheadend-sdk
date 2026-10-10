@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.playback

import java.util.concurrent.CancellationException
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
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

    @ParameterizedTest
    @EnumSource(JoiningCall::class)
    fun `joining calls fail fast from a coroutine that inherits a replay`(call: JoiningCall) = runTest {
        val elsewhere = StandardTestDispatcher(testScheduler)
        val failure = CompletableDeferred<Throwable?>()
        lateinit var handle: ActiveSubscription
        lateinit var owner: SubscriptionManager
        val fixture = openSeekable(
            onEvent = { event ->
                if (event is SubscriptionEvent.Packet && event.presentationTimeUs == 170L) {
                    // The dispatcher switch runs the call in its own child job of the replaying driver.
                    val outcome = runCatching { withContext(elsewhere) { call.perform(handle, owner) } }
                    failure.complete(outcome.exceptionOrNull())
                }
            },
        )
        handle = fixture.subscription
        owner = fixture.manager
        val packetWithheld = CompletableDeferred<Unit>()
        fixture.connection.skipAction = {
            packetWithheld.await()
            SubscriptionOperationResult.NotSupported
        }
        try {
            val seeking = async { fixture.subscription.seek(absoluteSeek()) }
            runCurrent()
            fixture.connection.emit(packet(presentationTimeUs = 170L))
            fixture.connection.emit(packet(presentationTimeUs = 180L))
            runCurrent()
            assertEquals(listOf("started"), fixture.received)
            packetWithheld.complete(Unit)
            runCurrent()

            assertTrue(failure.isCompleted, "A nested joining call must fail fast, not wait on its own teardown")
            assertTrue(failure.await() is IllegalStateException)
            assertTrue(seeking.await() is SubscriptionSeekResult.Refused)
            assertEquals(listOf("started", "packet:170", "packet:180"), fixture.received)
            assertTrue(fixture.subscription.state.value is SubscriptionState.Playable)
            assertSame(SubscriptionCloseResult.CLOSED, fixture.subscription.close())
            val terminal = fixture.subscription.state.value as SubscriptionState.Terminal
            assertSame(SubscriptionTerminalReason.Closed, terminal.reason)
        } finally {
            packetWithheld.complete(Unit)
            // A regressed guard leaves the call waiting on its own teardown; joining would hang the suite.
            if (failure.isCompleted) fixture.manager.closeAndJoin()
        }
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
    fun `a timed out or malformed command reply is uncertain and never replays withheld packets`() = runTest {
        listOf(SubscriptionOperationResult.Timeout, SubscriptionOperationResult.MalformedResponse).forEach {
            verifyUncertainSeek(it)
        }
    }

    private suspend fun TestScope.verifyUncertainSeek(failure: SubscriptionOperationResult<Nothing>) {
        val fixture = openSeekable()
        val packetWithheld = CompletableDeferred<Unit>()
        fixture.connection.skipAction = {
            packetWithheld.await()
            failure
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
    fun `priority changes need no timeshift grant and propagate typed outcomes`() = runTest {
        val subscriptionDispatcher = StandardTestDispatcher(testScheduler, "subscription")
        val fixture = openSeekable(
            grantedTimeshiftSeconds = null,
            dispatcher = subscriptionDispatcher,
        )
        var observedDispatcher: ContinuationInterceptor? = null
        fixture.connection.priorityAction = {
            observedDispatcher = currentCoroutineContext()[ContinuationInterceptor]
            SubscriptionOperationResult.Ok(Unit)
        }

        assertTrue(
            fixture.subscription.setPriority(LiveSubscriptionPriority.YIELD) is
                SubscriptionOperationResult.Ok,
        )
        assertSame(subscriptionDispatcher, observedDispatcher)
        fixture.connection.priorityAction = { SubscriptionOperationResult.NotSupported }
        assertSame(
            SubscriptionOperationResult.NotSupported,
            fixture.subscription.setPriority(LiveSubscriptionPriority.NORMAL),
        )
        fixture.connection.priorityAction = { SubscriptionOperationResult.Timeout }
        assertSame(
            SubscriptionOperationResult.Timeout,
            fixture.subscription.setPriority(LiveSubscriptionPriority.NORMAL),
        )
        fixture.connection.priorityAction = { error("transport failed") }
        assertSame(
            SubscriptionOperationResult.TransportUnavailable,
            fixture.subscription.setPriority(LiveSubscriptionPriority.NORMAL),
        )
        val cancellation = CancellationException("scripted")
        fixture.connection.priorityAction = { throw cancellation }
        val caught = try {
            fixture.subscription.setPriority(LiveSubscriptionPriority.YIELD)
            null
        } catch (failure: CancellationException) {
            failure
        }
        assertSame(cancellation, caught)
        assertEquals(
            listOf(
                LiveSubscriptionPriority.YIELD,
                LiveSubscriptionPriority.NORMAL,
                LiveSubscriptionPriority.NORMAL,
                LiveSubscriptionPriority.NORMAL,
                LiveSubscriptionPriority.YIELD,
            ),
            fixture.connection.priorities,
        )

        assertSame(SubscriptionCloseResult.CLOSED, fixture.subscription.close())
        assertSame(
            SubscriptionOperationResult.TransportUnavailable,
            fixture.subscription.setPriority(LiveSubscriptionPriority.NORMAL),
        )
        assertEquals(5, fixture.connection.calls.count { it == Call.PRIORITY })
        fixture.manager.closeAndJoin()
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
    fun `teardown abandons a pending seek before the close drain and discards its packets`() = runTest {
        val releaseUnsubscribe = CompletableDeferred<Unit>()
        val fixture = openSeekable()
        fixture.connection.unsubscribeAction = {
            releaseUnsubscribe.await()
            SubscriptionOperationResult.Ok(Unit)
        }
        try {
            val seeking = async { fixture.subscription.seek(absoluteSeek()) }
            runCurrent()
            val closing = async { fixture.subscription.close() }
            runCurrent()
            assertEquals(1, fixture.connection.unsubscribeCount)
            assertTrue(seeking.isCompleted, "Teardown must settle the request before the drain")
            assertSame(SubscriptionSeekResult.SubscriptionEnded, seeking.await())
            repeat(3) { index -> fixture.connection.emit(packet(presentationTimeUs = index.toLong())) }
            fixture.connection.emit(SubscriptionEvent.Status(SubscriptionCondition.STATUS_REPORTED))
            runCurrent()
            releaseUnsubscribe.complete(Unit)
            runCurrent()

            assertSame(SubscriptionCloseResult.CLOSED, closing.await())
            val terminal = fixture.subscription.state.value as SubscriptionState.Terminal
            assertSame(SubscriptionTerminalReason.Closed, terminal.reason)
            assertEquals(
                listOf("started", "status"),
                fixture.received,
                "Packets of an abandoned request must not reach the consumer during the close drain",
            )
        } finally {
            releaseUnsubscribe.complete(Unit)
            fixture.manager.closeAndJoin()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a gate decision while teardown waits for the seek driver abandons the request for close`(
        unrecognisedAcknowledgement: Boolean,
    ) = runTest {
        val releaseSkip = CompletableDeferred<Unit>()
        val fixture = openSeekable(gate = SeekGateSettings(maximumPendingEvents = 2))
        // A skip command that ignores cancellation holds teardown before its own abandonment.
        fixture.connection.skipAction = {
            withContext(NonCancellable) { releaseSkip.await() }
            SubscriptionOperationResult.Ok(Unit)
        }
        try {
            val seeking = async { fixture.subscription.seek(absoluteSeek()) }
            runCurrent()
            val closing = async { fixture.subscription.close() }
            runCurrent()
            assertFalse(seeking.isCompleted, "Teardown must still be waiting for the seek driver")
            if (unrecognisedAcknowledgement) {
                fixture.connection.emit(packet(presentationTimeUs = 0L))
                fixture.connection.emit(skipped(SkipOutcome.UNKNOWN))
                fixture.connection.emit(packet(presentationTimeUs = 1L))
            } else {
                repeat(3) { index -> fixture.connection.emit(packet(presentationTimeUs = index.toLong())) }
            }
            fixture.connection.emit(SubscriptionEvent.Status(SubscriptionCondition.STATUS_REPORTED))
            runCurrent()

            assertEquals(0, fixture.connection.unsubscribeCount, "The gate, not teardown, must settle the request")
            assertTrue(seeking.isCompleted)
            assertSame(SubscriptionSeekResult.SubscriptionEnded, seeking.await())
            releaseSkip.complete(Unit)
            runCurrent()

            assertSame(SubscriptionCloseResult.CLOSED, closing.await())
            val terminal = fixture.subscription.state.value as SubscriptionState.Terminal
            assertSame(SubscriptionTerminalReason.Closed, terminal.reason)
            val expected = if (unrecognisedAcknowledgement) {
                listOf("started", "skipped", "status")
            } else {
                listOf("started", "status")
            }
            assertEquals(expected, fixture.received)
            assertEquals(1, fixture.connection.unsubscribeCount)
        } finally {
            releaseSkip.complete(Unit)
            fixture.manager.closeAndJoin()
        }
    }

    @Test
    fun `an ordered terminal delivered across the acknowledgement deadline keeps its reason`() = runTest {
        val terminalEntered = CompletableDeferred<Unit>()
        val releaseTerminal = CompletableDeferred<Unit>()
        val fixture = openSeekable(
            onEvent = { event ->
                if (event is SubscriptionEvent.Terminated) {
                    terminalEntered.complete(Unit)
                    releaseTerminal.await()
                }
            },
        )
        try {
            val seeking = async { fixture.subscription.seek(absoluteSeek()) }
            runCurrent()
            fixture.connection.emit(packet(presentationTimeUs = 200L))
            fixture.connection.emit(SubscriptionEvent.Terminated(SubscriptionTermination.REMOTE_EOF))
            runCurrent()
            assertTrue(terminalEntered.isCompleted)
            advanceTimeBy(GATE_TIMEOUT + 1.seconds)
            runCurrent()

            assertTrue(seeking.isCompleted)
            assertSame(SubscriptionSeekResult.SubscriptionEnded, seeking.await())
            releaseTerminal.complete(Unit)
            runCurrent()
            val terminal = fixture.subscription.state.value as SubscriptionState.Terminal
            assertSame(SubscriptionTerminalReason.RemoteEof, terminal.reason)
            assertEquals(listOf("started", "terminated"), fixture.received)
        } finally {
            releaseTerminal.complete(Unit)
            fixture.manager.closeAndJoin()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `close waits for a committed replay that finishes within the grace period`(closerCancelled: Boolean) = runTest {
        val replayEntered = CompletableDeferred<Unit>()
        val releaseReplay = CompletableDeferred<Unit>()
        val fixture = openSeekable(
            onEvent = { event ->
                if (event is SubscriptionEvent.Packet && event.presentationTimeUs == 200L) {
                    replayEntered.complete(Unit)
                    releaseReplay.await()
                }
            },
        )
        val packetWithheld = CompletableDeferred<Unit>()
        fixture.connection.skipAction = {
            packetWithheld.await()
            SubscriptionOperationResult.AccessDenied
        }
        try {
            val seeking = async { fixture.subscription.seek(absoluteSeek()) }
            runCurrent()
            fixture.connection.emit(packet(presentationTimeUs = 200L))
            fixture.connection.emit(packet(presentationTimeUs = 210L))
            runCurrent()
            packetWithheld.complete(Unit)
            runCurrent()
            assertTrue(replayEntered.isCompleted)
            assertTrue(seeking.await() is SubscriptionSeekResult.Refused)

            val closing = async { fixture.subscription.close() }
            runCurrent()
            assertFalse(closing.isCompleted, "Close must join the committed replay")
            if (closerCancelled) {
                closing.cancel()
                runCurrent()
                assertFalse(closing.isCompleted, "A cancelled closer still joins teardown")
            }
            releaseReplay.complete(Unit)
            runCurrent()

            if (closerCancelled) {
                assertTrue(closing.isCancelled)
                caughtCancellation { closing.await() }
            } else {
                assertSame(SubscriptionCloseResult.CLOSED, closing.await())
            }
            assertEquals(listOf("started", "packet:200", "packet:210"), fixture.received)
            val terminal = fixture.subscription.state.value as SubscriptionState.Terminal
            assertSame(SubscriptionTerminalReason.Closed, terminal.reason)
            assertFalse(fixture.subscription.diagnostics.value.consumerDrainTimedOut)
            fixture.manager.closeAndJoin()
            assertEquals(1, fixture.connection.unsubscribeCount)
        } finally {
            packetWithheld.complete(Unit)
            releaseReplay.complete(Unit)
            fixture.manager.closeAndJoin()
        }
    }

    @Test
    fun `close cancels a committed replay that outlasts the grace period`() = runTest {
        val replayEntered = CompletableDeferred<Unit>()
        var consumerCancelled = false
        val fixture = openSeekable(
            onEvent = { event ->
                if (event is SubscriptionEvent.Packet && event.presentationTimeUs == 200L) {
                    replayEntered.complete(Unit)
                    try {
                        awaitCancellation()
                    } catch (cancellation: CancellationException) {
                        consumerCancelled = true
                        throw cancellation
                    }
                }
            },
        )
        val packetWithheld = CompletableDeferred<Unit>()
        fixture.connection.skipAction = {
            packetWithheld.await()
            SubscriptionOperationResult.AccessDenied
        }
        try {
            val seeking = async { fixture.subscription.seek(absoluteSeek()) }
            runCurrent()
            fixture.connection.emit(packet(presentationTimeUs = 200L))
            fixture.connection.emit(packet(presentationTimeUs = 210L))
            runCurrent()
            packetWithheld.complete(Unit)
            runCurrent()
            assertTrue(replayEntered.isCompleted)
            assertTrue(seeking.await() is SubscriptionSeekResult.Refused)

            val closing = async { fixture.subscription.close() }
            runCurrent()
            advanceTimeBy(TEARDOWN_GRACE - 1.milliseconds)
            runCurrent()
            assertFalse(closing.isCompleted, "Close must wait for the whole grace period")
            assertFalse(consumerCancelled)
            advanceTimeBy(1.milliseconds)
            runCurrent()

            assertTrue(closing.isCompleted, "Close must not wait past the grace period")
            assertSame(SubscriptionCloseResult.CLEANUP_FAILED, closing.await())
            assertTrue(consumerCancelled)
            assertEquals(listOf("started", "packet:200"), fixture.received)
            val terminal = fixture.subscription.state.value as SubscriptionState.Terminal
            assertSame(SubscriptionTerminalReason.Closed, terminal.reason)
            assertTrue(fixture.subscription.diagnostics.value.consumerDrainTimedOut)
            assertEquals(1, fixture.connection.unsubscribeCount)
        } finally {
            packetWithheld.complete(Unit)
            fixture.manager.closeAndJoin()
        }
    }

    @Test
    fun `a committed replay that ends exactly at the grace deadline is not reported as timed out`() = runTest {
        var consumerCancelled = false
        val fixture = openSeekable(
            onEvent = { event ->
                if (event is SubscriptionEvent.Packet && event.presentationTimeUs == 200L) {
                    // Started before close, so this delay and the teardown deadline expire together.
                    try {
                        delay(TEARDOWN_GRACE)
                    } catch (cancellation: CancellationException) {
                        consumerCancelled = true
                        throw cancellation
                    }
                }
            },
        )
        val packetWithheld = CompletableDeferred<Unit>()
        val releaseUnsubscribe = CompletableDeferred<Unit>()
        fixture.connection.skipAction = {
            packetWithheld.await()
            SubscriptionOperationResult.AccessDenied
        }
        fixture.connection.unsubscribeAction = {
            releaseUnsubscribe.await()
            SubscriptionOperationResult.Ok(Unit)
        }
        try {
            val seeking = async { fixture.subscription.seek(absoluteSeek()) }
            runCurrent()
            fixture.connection.emit(packet(presentationTimeUs = 200L))
            fixture.connection.emit(packet(presentationTimeUs = 210L))
            runCurrent()
            packetWithheld.complete(Unit)
            runCurrent()
            assertTrue(seeking.await() is SubscriptionSeekResult.Refused)

            val closing = async { fixture.subscription.close() }
            runCurrent()
            advanceTimeBy(TEARDOWN_GRACE)
            runCurrent()
            // The replay counts as finished, so the consumer still receives the close drain.
            fixture.connection.emit(packet(presentationTimeUs = 300L))
            runCurrent()
            assertEquals(listOf("started", "packet:200", "packet:210", "packet:300"), fixture.received)
            releaseUnsubscribe.complete(Unit)
            runCurrent()

            assertTrue(closing.isCompleted)
            assertSame(SubscriptionCloseResult.CLOSED, closing.await())
            assertFalse(consumerCancelled)
            assertFalse(fixture.subscription.diagnostics.value.consumerDrainTimedOut)
            val terminal = fixture.subscription.state.value as SubscriptionState.Terminal
            assertSame(SubscriptionTerminalReason.Closed, terminal.reason)
        } finally {
            packetWithheld.complete(Unit)
            releaseUnsubscribe.complete(Unit)
            fixture.manager.closeAndJoin()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `an expired replay wait cannot attribute the next collection failure to teardown`(
        replayChildStillActive: Boolean,
    ) = runTest {
        val cancellationReached = CompletableDeferred<Unit>()
        val resumeCancellation = CompletableDeferred<Unit>()
        val releaseReplay = CompletableDeferred<Unit>()
        val collectionEntered = CompletableDeferred<Unit>()
        val releaseFailure = CompletableDeferred<Unit>()
        val fixture = openSeekable(
            beforeTeardownCancellation = {
                cancellationReached.complete(Unit)
                resumeCancellation.await()
            },
            onEvent = { event ->
                if (event is SubscriptionEvent.Packet) {
                    when (event.presentationTimeUs) {
                        200L -> {
                            if (replayChildStillActive) {
                                // Keep the replay job active even after its body releases delivery.
                                CoroutineScope(currentCoroutineContext()).launch { awaitCancellation() }
                            }
                            releaseReplay.await()
                        }
                        300L -> {
                            collectionEntered.complete(Unit)
                            releaseFailure.await()
                            error("consumer failed")
                        }
                    }
                }
            },
        )
        val packetWithheld = CompletableDeferred<Unit>()
        fixture.connection.skipAction = {
            packetWithheld.await()
            SubscriptionOperationResult.AccessDenied
        }
        try {
            val seeking = async { fixture.subscription.seek(absoluteSeek()) }
            runCurrent()
            fixture.connection.emit(packet(presentationTimeUs = 200L))
            runCurrent()
            packetWithheld.complete(Unit)
            runCurrent()
            assertTrue(seeking.await() is SubscriptionSeekResult.Refused)
            fixture.connection.emit(packet(presentationTimeUs = 300L))
            val closing = async { fixture.subscription.close() }
            runCurrent()
            advanceTimeBy(TEARDOWN_GRACE)
            runCurrent()
            assertTrue(cancellationReached.isCompleted, "The replay wait must expire before the handoff")
            assertFalse(collectionEntered.isCompleted)

            // Pause between timeout detection and cancellation, then finish the replay and admit
            // collection's next call. Cancellation targets either the completed replay job or its
            // remaining child, never the collection call now owning the delivery mutex.
            releaseReplay.complete(Unit)
            runCurrent()
            assertTrue(collectionEntered.isCompleted)
            resumeCancellation.complete(Unit)
            runCurrent()
            assertEquals(1, fixture.connection.unsubscribeCount)
            assertFalse(closing.isCompleted)
            releaseFailure.complete(Unit)
            runCurrent()

            assertTrue(closing.isCompleted)
            closing.await()
            val terminal = fixture.subscription.state.value as SubscriptionState.Terminal
            assertSame(SubscriptionTerminalReason.ConsumerFailed, terminal.reason)
            assertFalse(fixture.subscription.diagnostics.value.consumerDrainTimedOut)
            assertEquals(listOf("started", "packet:200", "packet:300"), fixture.received)
        } finally {
            releaseReplay.complete(Unit)
            resumeCancellation.complete(Unit)
            releaseFailure.complete(Unit)
            packetWithheld.complete(Unit)
            fixture.manager.closeAndJoin()
        }
    }

    @Test
    fun `the replay and the close drain each get their own grace period`() = runTest {
        var drainCancelled = false
        val fixture = openSeekable(
            onEvent = { event ->
                if (event is SubscriptionEvent.Packet) {
                    when (event.presentationTimeUs) {
                        200L -> delay(3.seconds)
                        300L -> try {
                            awaitCancellation()
                        } catch (cancellation: CancellationException) {
                            drainCancelled = true
                            throw cancellation
                        }
                    }
                }
            },
        )
        val packetWithheld = CompletableDeferred<Unit>()
        fixture.connection.skipAction = {
            packetWithheld.await()
            SubscriptionOperationResult.AccessDenied
        }
        try {
            val seeking = async { fixture.subscription.seek(absoluteSeek()) }
            runCurrent()
            fixture.connection.emit(packet(presentationTimeUs = 200L))
            runCurrent()
            packetWithheld.complete(Unit)
            runCurrent()
            assertTrue(seeking.await() is SubscriptionSeekResult.Refused)
            // Committed after the request resolved, so the close drain delivers it once the replay ends.
            fixture.connection.emit(packet(presentationTimeUs = 300L))
            val closing = async { fixture.subscription.close() }
            runCurrent()

            advanceTimeBy(3.seconds + TEARDOWN_GRACE - 1.milliseconds)
            runCurrent()
            assertFalse(closing.isCompleted, "The drain gets its own grace period after the replay finished")
            assertFalse(drainCancelled)
            advanceTimeBy(1.milliseconds)
            runCurrent()

            assertTrue(closing.isCompleted, "Close must not wait past the drain's grace period")
            assertSame(SubscriptionCloseResult.CLEANUP_FAILED, closing.await())
            assertTrue(drainCancelled)
            assertEquals(listOf("started", "packet:200", "packet:300"), fixture.received)
            assertTrue(fixture.subscription.diagnostics.value.consumerDrainTimedOut)
            val terminal = fixture.subscription.state.value as SubscriptionState.Terminal
            assertSame(SubscriptionTerminalReason.Closed, terminal.reason)
            assertEquals(1, fixture.connection.unsubscribeCount)
        } finally {
            packetWithheld.complete(Unit)
            fixture.manager.closeAndJoin()
        }
    }

    @Test
    fun `transport cancellation settles the seek and discards uncertain packets`() = runTest {
        val fixture = openSeekable()
        val release = CompletableDeferred<Unit>()
        val cancellation = CancellationException("scripted skip cancellation")
        fixture.connection.skipAction = {
            release.await()
            throw cancellation
        }
        try {
            val seeking = async { fixture.subscription.seek(absoluteSeek()) }
            runCurrent()
            fixture.connection.emit(packet(presentationTimeUs = 130L))
            runCurrent()
            release.complete(Unit)
            runCurrent()

            assertTrue(seeking.isCompleted, "Transport cancellation must not strand the seek gate")
            val caught = runCatching { seeking.await() }.exceptionOrNull()
            assertTrue(caught === cancellation || caught?.cause === cancellation)
            val terminal = fixture.subscription.state.value as SubscriptionState.Terminal
            assertEquals(
                SubscriptionSeekInvalidation.UNCERTAIN_REQUEST_OUTCOME,
                (terminal.reason as SubscriptionTerminalReason.SeekInvalidated).cause,
            )
            assertEquals(listOf("started"), fixture.received)
            assertSame(SubscriptionSeekResult.SubscriptionEnded, fixture.subscription.seek(absoluteSeek()))
            advanceTimeBy(GATE_TIMEOUT)
            runCurrent()
            assertEquals(terminal, fixture.subscription.state.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `transport cancellation after ordered acknowledgement does not invalidate settled seek`() = runTest {
        val fixture = openSeekable()
        val release = CompletableDeferred<Unit>()
        fixture.connection.skipAction = {
            release.await()
            throw CancellationException("scripted late skip cancellation")
        }
        val seeking = async { fixture.subscription.seek(absoluteSeek()) }
        runCurrent()
        fixture.connection.emit(skipped(SkipOutcome.REJECTED))
        runCurrent()
        assertSame(SubscriptionSeekResult.Rejected, seeking.await())
        release.complete(Unit)
        runCurrent()
        assertTrue(fixture.subscription.state.value is SubscriptionState.Playable)
        fixture.close()
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
        fixture.connection.emit(SubscriptionEvent.Terminated(SubscriptionTermination.LOCAL_RETIREMENT))
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
        beforeTeardownCancellation: (suspend () -> Unit)? = null,
        onEvent: suspend (SubscriptionEvent) -> Unit = {},
    ): SeekFixture {
        val connection = RecordingSubscriptionConnection()
        connection.subscribeAction = { successfulConfirmation(grantedTimeshiftSeconds) }
        val manager = createSubscriptionManager(
            connection,
            dispatcher,
            gate,
            beforeTeardownCancellation = beforeTeardownCancellation,
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

private val TEARDOWN_GRACE = 5.seconds

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
