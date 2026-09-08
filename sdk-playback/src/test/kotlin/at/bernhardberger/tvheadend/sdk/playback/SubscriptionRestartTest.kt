@file:OptIn(SubscriptionInfrastructureApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package at.bernhardberger.tvheadend.sdk.playback

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Duration.Companion.seconds

internal class SubscriptionRestartTest {
    @Test
    fun `restart deadline before first playable waits for unsubscribe and ordered drain`() = runTest {
        val unsubscribeEntered = CompletableDeferred<Unit>()
        val releaseUnsubscribe = CompletableDeferred<Unit>()
        val consumerEntered = CompletableDeferred<Unit>()
        val releaseConsumer = CompletableDeferred<Unit>()
        val connection = RecordingSubscriptionConnection().apply {
            unsubscribeAction = {
                unsubscribeEntered.complete(Unit)
                releaseUnsubscribe.await()
                SubscriptionOperationResult.Ok(Unit)
            }
        }
        val manager = createSubscriptionManager(connection, StandardTestDispatcher(testScheduler))
        manager.startAdmission()
        val opening = async {
            manager.open(SubscriptionChannelId(1), SubscriptionEventConsumer { event ->
                if (event is SubscriptionEvent.Stopped) {
                    consumerEntered.complete(Unit)
                    releaseConsumer.await()
                }
            })
        }
        try {
            runCurrent()
            connection.emit(SubscriptionEvent.Stopped(SubscriptionCondition.NO_DETAIL))
            runCurrent()
            assertTrue(consumerEntered.isCompleted)
            advanceTimeBy(5.seconds)
            runCurrent()
            assertTrue(unsubscribeEntered.isCompleted)
            assertFalse(opening.isCompleted, "Deadline must not bypass unsubscribe")
            releaseUnsubscribe.complete(Unit)
            runCurrent()
            assertFalse(opening.isCompleted, "Deadline must not bypass ordered consumer drain")
            releaseConsumer.complete(Unit)
            runCurrent()
            assertSame(SubscriptionTerminalReason.Timeout, (opening.await() as SubscriptionOpenResult.Failed).reason)
            assertEquals(1, connection.unsubscribeCount)
        } finally {
            releaseUnsubscribe.complete(Unit)
            releaseConsumer.complete(Unit)
            manager.closeAndJoin()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `restart below an established seek floor cannot discard forever while playable`(makesProgress: Boolean) = runTest {
        val fixture = openRestartFixture()
        try {
            fixture.connection.emit(packet(presentationTimeUs = 9_000_000_000L, durationUs = 40_000))
            runCurrent()
            val seeking = async { fixture.subscription.seek(SubscriptionSeekTarget.Live) }
            runCurrent()
            fixture.connection.emit(skipped(SkipOutcome.ACCEPTED))
            fixture.connection.emit(packet(presentationTimeUs = 1_000_000L))
            runCurrent()
            assertSame(SubscriptionSeekResult.Accepted, seeking.await())
            fixture.connection.emit(SubscriptionEvent.Stopped(SubscriptionCondition.NO_DETAIL))
            fixture.connection.emit(started(stream()))
            repeat(1_023) { fixture.connection.emit(packet(presentationTimeUs = 0L)) }
            runCurrent()
            assertTrue(fixture.subscription.state.value is SubscriptionState.Playable)
            assertEquals(2, fixture.events.filterIsInstance<SubscriptionEvent.Packet>().size)
            if (makesProgress) {
                fixture.connection.emit(packet(presentationTimeUs = 2_000_000L))
                repeat(1_023) { fixture.connection.emit(packet(presentationTimeUs = 0L)) }
                runCurrent()
                assertTrue(fixture.subscription.state.value is SubscriptionState.Playable)
                assertEquals(9_001_040_000L, fixture.events.filterIsInstance<SubscriptionEvent.Packet>().last().presentationTimeUs)
            }
            // Repeated metadata without any media progress must not replenish the discard budget.
            fixture.connection.emit(started(stream()))
            fixture.connection.emit(packet(presentationTimeUs = 0L))
            runCurrent()
            val reason = (fixture.subscription.state.value as SubscriptionState.Terminal).reason
            assertEquals(SubscriptionSeekInvalidation.RESUMED_SEGMENT_UNANCHORABLE,
                (reason as SubscriptionTerminalReason.SeekInvalidated).cause)
            assertEquals(if (makesProgress) 2_047L else 1_024L, fixture.subscription.diagnostics.value.rebaseDiscardedPacketCount)
            assertEquals(1, fixture.connection.unsubscribeCount)
        } finally { fixture.manager.closeAndJoin() }
    }

    @Test
    fun `restart deadline does not replace initial subscribe acknowledgement ownership`() = runTest {
        val acknowledgement = CompletableDeferred<Unit>()
        val connection = RecordingSubscriptionConnection().apply {
            subscribeAction = { acknowledgement.await(); successfulConfirmation(120) }
        }
        val manager = createSubscriptionManager(connection, StandardTestDispatcher(testScheduler))
        manager.startAdmission()
        val opening = async { manager.open(SubscriptionChannelId(1), SubscriptionEventConsumer {}) }
        try {
            runCurrent()
            connection.emit(started(stream()))
            runCurrent()
            advanceTimeBy(6.seconds)
            runCurrent()
            assertFalse(opening.isCompleted)
            assertEquals(0, connection.unsubscribeCount)
            acknowledgement.complete(Unit)
            runCurrent()
            assertTrue(opening.await() is SubscriptionOpenResult.Opened)
        } finally { manager.closeAndJoin() }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `stop and direct repeated start replace tracks without replacing transport`(stopFirst: Boolean) = runTest {
        val fixture = openRestartFixture()
        try {
            val old = (fixture.subscription.state.value as SubscriptionState.Playable).tracks
            if (stopFirst) {
                fixture.connection.emit(SubscriptionEvent.Stopped(SubscriptionCondition.NO_DETAIL))
                fixture.connection.emit(packet())
                fixture.connection.emit(SubscriptionEvent.Status(SubscriptionCondition.STATUS_REPORTED))
                runCurrent()
                assertSame(SubscriptionState.Starting, fixture.subscription.state.value)
                assertEquals(0, fixture.events.filterIsInstance<SubscriptionEvent.Packet>().size)
                assertTrue(fixture.events.last() is SubscriptionEvent.Status)
                assertSame(SubscriptionSeekResult.SegmentUnavailable, fixture.subscription.seek(SubscriptionSeekTarget.Live))
            }
            fixture.connection.emit(started(stream(type = SubscriptionStreamType.AAC), stream(1)))
            fixture.connection.emit(packet(presentationTimeUs = 9_000_000_000L))
            runCurrent()
            val current = (fixture.subscription.state.value as SubscriptionState.Playable).tracks
            assertNotSame(old, current)
            assertEquals(2, current.streams.size)
            assertEquals(SubscriptionStreamType.AAC, current.streams.first().type)
            assertEquals(2, fixture.tracks.size)
            assertEquals(120.seconds, fixture.subscription.grantedTimeshiftPeriod)
            assertEquals(1, fixture.connection.calls.count { it == Call.SUBSCRIBE })
            assertEquals(0, fixture.connection.unsubscribeCount)
            assertSame(SubscriptionSeekResult.SegmentUnavailable,
                fixture.subscription.seek(SubscriptionSeekTarget.Absolute(1.seconds), old))
            assertTrue(fixture.connection.seekTargets.isEmpty())
        } finally { fixture.manager.closeAndJoin() }
        assertEquals(1, fixture.connection.unsubscribeCount)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `restart intersecting pending seek fails closed including before dispatch`(dispatched: Boolean) = runTest {
        val fixture = openRestartFixture()
        try {
            if (!dispatched) fixture.connection.emit(SubscriptionEvent.Stopped(SubscriptionCondition.NO_DETAIL))
            val seek = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.subscription.seek(SubscriptionSeekTarget.Live)
            }
            if (dispatched) {
                runCurrent()
                fixture.connection.emit(SubscriptionEvent.Stopped(SubscriptionCondition.NO_DETAIL))
            }
            fixture.connection.emit(skipped(SkipOutcome.ACCEPTED))
            fixture.connection.emit(started(stream()))
            fixture.connection.emit(packet())
            runCurrent()
            assertEquals(SubscriptionSeekInvalidation.UNCERTAIN_REQUEST_OUTCOME,
                (seek.await() as SubscriptionSeekResult.Invalidated).cause)
            val terminal = fixture.subscription.state.value as SubscriptionState.Terminal
            assertEquals(SubscriptionSeekInvalidation.UNCERTAIN_REQUEST_OUTCOME,
                (terminal.reason as SubscriptionTerminalReason.SeekInvalidated).cause)
            assertEquals(if (dispatched) 1 else 0, fixture.connection.seekTargets.size)
            assertEquals(1, fixture.events.size)
            assertEquals(1, fixture.connection.unsubscribeCount)
            assertTrue(fixture.connection.live)
        } finally { fixture.manager.closeAndJoin() }
    }

    @Test
    fun `repeated stops share deadline and stale timer cannot kill restarted segment`() = runTest {
        val fixture = openRestartFixture()
        try {
            fixture.connection.emit(SubscriptionEvent.Stopped(SubscriptionCondition.NO_DETAIL))
            runCurrent()
            advanceTimeBy(4.seconds)
            fixture.connection.emit(started(stream()))
            runCurrent()
            advanceTimeBy(2.seconds)
            runCurrent()
            assertTrue(fixture.subscription.state.value is SubscriptionState.Playable)
            fixture.connection.emit(SubscriptionEvent.Stopped(SubscriptionCondition.NO_DETAIL))
            runCurrent()
            advanceTimeBy(4.seconds)
            fixture.connection.emit(SubscriptionEvent.Stopped(SubscriptionCondition.NO_DETAIL))
            runCurrent()
            advanceTimeBy(1.seconds)
            runCurrent()
            assertSame(SubscriptionTerminalReason.Timeout,
                (fixture.subscription.state.value as SubscriptionState.Terminal).reason)
            fixture.connection.emit(started(stream()))
            runCurrent()
            assertEquals(2, fixture.tracks.size)
            assertEquals(1, fixture.connection.unsubscribeCount)
        } finally { fixture.manager.closeAndJoin() }
    }

    @Test
    fun `stop invalidates segment before boundary callback`() = runTest {
        var subscription: ActiveSubscription? = null
        var observed: SubscriptionState? = null
        val fixture = openRestartFixture { event ->
            if (event is SubscriptionEvent.Stopped || event is SubscriptionEvent.Started) {
                observed = subscription?.state?.value
            }
        }
        subscription = fixture.subscription
        try {
            fixture.connection.emit(SubscriptionEvent.Stopped(SubscriptionCondition.NO_DETAIL))
            runCurrent()
            assertSame(SubscriptionState.Starting, observed)
            fixture.connection.emit(started(stream()))
            runCurrent()
            assertSame(SubscriptionState.Starting, observed)
            assertTrue(subscription.state.value is SubscriptionState.Playable)
        } finally { fixture.manager.closeAndJoin() }
    }

    @Test
    fun `restart preserves established delivered offset and relative track positions`() = runTest {
        val fixture = openRestartFixture()
        try {
            fixture.connection.emit(packet(presentationTimeUs = 9_000_000_000L, durationUs = 40_000))
            runCurrent()
            val seeking = async { fixture.subscription.seek(SubscriptionSeekTarget.Live) }
            runCurrent()
            fixture.connection.emit(skipped(SkipOutcome.ACCEPTED))
            fixture.connection.emit(packet(presentationTimeUs = 1_000_000L))
            runCurrent()
            assertSame(SubscriptionSeekResult.Accepted, seeking.await())
            fixture.connection.emit(SubscriptionEvent.Stopped(SubscriptionCondition.NO_DETAIL))
            fixture.connection.emit(started(stream(type = SubscriptionStreamType.AAC), stream(1)))
            fixture.connection.emit(packet(presentationTimeUs = 2_000_000L, streamIndex = 1))
            fixture.connection.emit(packet(presentationTimeUs = 2_020_000L))
            runCurrent()
            val packets = fixture.events.filterIsInstance<SubscriptionEvent.Packet>()
            assertEquals(9_001_040_000L, packets[2].presentationTimeUs)
            assertEquals(20_000L, packets[3].presentationTimeUs!! - packets[2].presentationTimeUs!!)
            assertEquals(2_000_000L, packets[2].serverPresentationTimeUs)
            assertFalse(fixture.subscription.state.value is SubscriptionState.Terminal)
        } finally { fixture.manager.closeAndJoin() }
    }

    @Test
    fun `accepted seek awaiting anchor survives restart and uses new track candidates`() = runTest {
        val fixture = openRestartFixture()
        try {
            fixture.connection.emit(packet(presentationTimeUs = 9_000_000_000L, durationUs = 40_000))
            runCurrent()
            val seeking = async { fixture.subscription.seek(SubscriptionSeekTarget.Live) }
            runCurrent()
            fixture.connection.emit(skipped(SkipOutcome.ACCEPTED))
            runCurrent()
            assertSame(SubscriptionSeekResult.Accepted, seeking.await())
            fixture.connection.emit(SubscriptionEvent.Stopped(SubscriptionCondition.NO_DETAIL))
            fixture.connection.emit(started(stream(type = SubscriptionStreamType.AAC)))
            fixture.connection.emit(packet(presentationTimeUs = 1_000_000, frameType = MuxFrameType.UNKNOWN))
            runCurrent()
            assertEquals(9_000_040_000L, fixture.events.filterIsInstance<SubscriptionEvent.Packet>().last().presentationTimeUs)
            assertTrue(fixture.subscription.state.value is SubscriptionState.Playable)
            assertEquals(1L, fixture.subscription.diagnostics.value.timestampAnchorCount)
        } finally { fixture.manager.closeAndJoin() }
    }

    private suspend fun TestScope.openRestartFixture(
        onEvent: (SubscriptionEvent) -> Unit = {},
    ): RestartFixture {
        val connection = RecordingSubscriptionConnection().apply {
            subscribeAction = { successfulConfirmation(120) }
        }
        val manager = createSubscriptionManager(connection, StandardTestDispatcher(testScheduler))
        val events = mutableListOf<SubscriptionEvent>()
        val validatedTracks = mutableListOf<SubscriptionTracks>()
        manager.startAdmission()
        val opening = async {
            manager.open(SubscriptionChannelId(1), object : SubscriptionEventConsumer {
                override suspend fun accept(event: SubscriptionEvent) { events += event; onEvent(event) }
                override fun tracksReady(tracks: SubscriptionTracks) { validatedTracks.add(tracks) }
            }, 120.seconds)
        }
        runCurrent()
        connection.emit(started(stream()))
        runCurrent()
        return RestartFixture(connection, manager, (opening.await() as SubscriptionOpenResult.Opened).subscription, events, validatedTracks)
    }
}

private class RestartFixture(
    val connection: RecordingSubscriptionConnection,
    val manager: SubscriptionManager,
    val subscription: ActiveSubscription,
    val events: List<SubscriptionEvent>,
    val tracks: List<SubscriptionTracks>,
)
