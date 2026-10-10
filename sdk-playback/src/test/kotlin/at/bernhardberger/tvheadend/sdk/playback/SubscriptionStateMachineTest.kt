@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.playback

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionStateMachineTest {
    @Test
    fun `subscription issues expose stable non-exhaustive categories`() {
        val categories = mapOf(
            SubscriptionIssue.NO_INPUT to SubscriptionIssueCategory.INPUT_OR_SIGNAL,
            SubscriptionIssue.NO_FREE_ADAPTER to SubscriptionIssueCategory.RESOURCE,
            SubscriptionIssue.SCRAMBLED to SubscriptionIssueCategory.CONFIGURATION_OR_ACCESS,
            SubscriptionIssue.BAD_SIGNAL to SubscriptionIssueCategory.INPUT_OR_SIGNAL,
            SubscriptionIssue.TUNING_FAILED to SubscriptionIssueCategory.INPUT_OR_SIGNAL,
            SubscriptionIssue.SUBSCRIPTION_OVERRIDDEN to SubscriptionIssueCategory.INTERRUPTION,
            SubscriptionIssue.MUX_NOT_ENABLED to SubscriptionIssueCategory.CONFIGURATION_OR_ACCESS,
            SubscriptionIssue.INVALID_TARGET to SubscriptionIssueCategory.CONFIGURATION_OR_ACCESS,
            SubscriptionIssue.USER_ACCESS to SubscriptionIssueCategory.CONFIGURATION_OR_ACCESS,
            SubscriptionIssue.USER_LIMIT to SubscriptionIssueCategory.CONFIGURATION_OR_ACCESS,
            SubscriptionIssue.WEAK_STREAM to SubscriptionIssueCategory.INPUT_OR_SIGNAL,
            SubscriptionIssue.NO_DISK_SPACE to SubscriptionIssueCategory.RESOURCE,
            SubscriptionIssue.UNKNOWN to SubscriptionIssueCategory.UNKNOWN,
        )
        val publicValues = SubscriptionIssue::class.java.fields
            .filter { field -> field.type == SubscriptionIssue::class.java }
            .mapTo(mutableSetOf()) { field -> field.get(null) as SubscriptionIssue }
        assertEquals(publicValues, categories.keys)

        categories.forEach { (issue, category) ->
            assertEquals(category, issue.category)
            assertEquals(
                category == SubscriptionIssueCategory.CONFIGURATION_OR_ACCESS,
                issue.isConfigurationOrAccessRelated,
            )
        }
        assertEquals("NO_INPUT", SubscriptionIssue.NO_INPUT.toString())
        assertTrue(SubscriptionIssue.NO_INPUT !== SubscriptionIssue.BAD_SIGNAL)
    }

    @Test
    fun `subscription options validate profile UUID and wire timeshift domain`() {
        val options = SubscriptionOptions(
            streamProfileUuid = "0123456789abcdef0123456789abcdef",
            timeshiftPeriod = 600.seconds,
        )

        assertEquals("0123456789abcdef0123456789abcdef", options.streamProfileUuid)
        assertEquals(600.seconds, options.timeshiftPeriod)
        assertEquals("SubscriptionOptions(<redacted>)", options.toString())
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionOptions(streamProfileUuid = "0123456789ABCDEF0123456789ABCDEF")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionOptions(timeshiftPeriod = (-1).seconds)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionOptions(timeshiftPeriod = Duration.INFINITE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionOptions(timeshiftPeriod = 0x1_0000_0000L.seconds)
        }
    }

    @Test
    fun `validated tracks callback completes before playable and later packets`() = runTest {
        val connection = RecordingSubscriptionConnection()
        val order = mutableListOf<String>()
        val manager = manager(connection)
        manager.startAdmission()
        val consumer = object : SubscriptionEventConsumer {
            override suspend fun accept(event: SubscriptionEvent) {
                order += if (event is SubscriptionEvent.Packet) "packet" else "event"
            }

            override fun tracksReady(tracks: SubscriptionTracks) {
                assertEquals(1, tracks.streams.size)
                order += "tracks"
            }
        }

        val opened = async { manager.open(SubscriptionChannelId(2L), consumer) }
        runCurrent()
        connection.emit(started(stream()))
        runCurrent()
        val active = (opened.await() as SubscriptionOpenResult.Opened).subscription
        order += "playable"
        connection.emit(packet())
        runCurrent()

        assertEquals(listOf("event", "tracks", "playable", "packet"), order)
        active.close()
        manager.closeAndJoin()
    }

    @Test
    fun `validated tracks callback failure prevents playable and cleans up`() = runTest {
        val connection = RecordingSubscriptionConnection()
        val manager = manager(connection)
        manager.startAdmission()
        val consumer = object : SubscriptionEventConsumer {
            override suspend fun accept(event: SubscriptionEvent): Unit = Unit

            override fun tracksReady(tracks: SubscriptionTracks) {
                error("unsafe fixture detail")
            }
        }

        val opened = async { manager.open(SubscriptionChannelId(3L), consumer) }
        runCurrent()
        connection.emit(started(stream()))
        runCurrent()

        val result = opened.await()
        assertTrue(result is SubscriptionOpenResult.Failed)
        assertSame(
            SubscriptionTerminalReason.ConsumerFailed,
            (result as SubscriptionOpenResult.Failed).reason,
        )
        assertEquals(1, connection.unsubscribeCount)
        manager.closeAndJoin()
    }

    @Test
    fun `collection registers before immediate subscribe and playable publishes once`() = runTest {
        val connection = RecordingSubscriptionConnection()
        val manager = manager(connection)
        manager.startAdmission()

        val opened = async {
            manager.open(SubscriptionChannelId(4L), SubscriptionEventConsumer {})
        }
        runCurrent()

        assertEquals(listOf(Call.COLLECTION_REGISTERED, Call.SUBSCRIBE), connection.calls)
        assertFalse(opened.isCompleted)

        connection.emit(started(stream()))
        runCurrent()

        val result = opened.await()
        assertTrue(result is SubscriptionOpenResult.Opened)
        val active = (result as SubscriptionOpenResult.Opened).subscription
        assertTrue(active.state.value is SubscriptionState.Playable)
        assertEquals(1, connection.liveCommitCount)

        connection.emit(SubscriptionEvent.Status(SubscriptionCondition.STATUS_REPORTED))
        runCurrent()
        assertEquals(1, connection.liveCommitCount)

        assertEquals(SubscriptionCloseResult.CLOSED, active.close())
        manager.closeAndJoin()
        assertEquals(1, connection.unsubscribeCount)
    }

    @Test
    fun `started before subscribe acknowledgement remains starting until acknowledgement`() = runTest {
        val connection = RecordingSubscriptionConnection()
        val subscribe = CompletableDeferred<SubscriptionOperationResult<SubscriptionConfirmation>>()
        connection.subscribeAction = { subscribe.await() }
        val manager = manager(connection)
        manager.startAdmission()

        val opened = async {
            manager.open(SubscriptionChannelId(5L), SubscriptionEventConsumer {})
        }
        runCurrent()
        connection.emit(started(stream()))
        runCurrent()
        assertFalse(opened.isCompleted)

        subscribe.complete(successfulConfirmation())
        runCurrent()

        assertTrue(opened.await() is SubscriptionOpenResult.Opened)
        assertEquals(1, connection.liveCommitCount)
        manager.closeAndJoin()
    }

    @Test
    fun `stop admission prevents a pending handle from becoming playable`() = runTest {
        val connection = RecordingSubscriptionConnection()
        lateinit var manager: SubscriptionManager
        connection.beforeLiveCommit = { manager.stopAdmission() }
        manager = manager(connection)
        manager.startAdmission()

        val opened = async {
            manager.open(SubscriptionChannelId(6L), SubscriptionEventConsumer {})
        }
        runCurrent()
        connection.emit(started(stream()))
        runCurrent()

        val result = opened.await()
        assertTrue(result is SubscriptionOpenResult.Failed)
        assertSame(
            SubscriptionTerminalReason.Closed,
            (result as SubscriptionOpenResult.Failed).reason,
        )
        assertTrue(connection.liveCommitCount >= 1)
        assertEquals(1, connection.unsubscribeCount)
        assertSame(
            SubscriptionOpenResult.NotReady,
            manager.open(SubscriptionChannelId(7L), SubscriptionEventConsumer {}),
        )
        manager.closeAndJoin()
    }

    @Test
    fun `local close drains queued transport terminal in exact event order`() = runTest {
        val connection = RecordingSubscriptionConnection()
        val packetEntered = CompletableDeferred<Unit>()
        val releasePacket = CompletableDeferred<Unit>()
        val received = ArrayList<Class<out SubscriptionEvent>>()
        val manager = manager(connection)
        manager.startAdmission()

        val opened = async {
            manager.open(
                SubscriptionChannelId(8L),
                SubscriptionEventConsumer { event ->
                    received += event.javaClass
                    if (event is SubscriptionEvent.Packet) {
                        packetEntered.complete(Unit)
                        releasePacket.await()
                    }
                },
            )
        }
        runCurrent()
        connection.emit(started(stream()))
        runCurrent()
        val active = (opened.await() as SubscriptionOpenResult.Opened).subscription

        connection.emit(packet())
        connection.emit(SubscriptionEvent.Terminated(SubscriptionTermination.GENERATION_LOST))
        runCurrent()
        packetEntered.await()

        manager.stopAdmission()
        val closing = async { manager.closeAndJoin() }
        runCurrent()
        assertFalse(closing.isCompleted)

        releasePacket.complete(Unit)
        runCurrent()
        closing.await()

        assertEquals(
            listOf(
                SubscriptionEvent.Started::class.java,
                SubscriptionEvent.Packet::class.java,
                SubscriptionEvent.Terminated::class.java,
            ),
            received,
        )
        val terminal = active.state.value as SubscriptionState.Terminal
        assertSame(SubscriptionTerminalReason.GenerationLost, terminal.reason)
        assertEquals(1, connection.unsubscribeCount)
    }

    @Test
    fun `ordered controls update diagnostics without losing repeated delivery`() = runTest {
        val connection = RecordingSubscriptionConnection()
        val received = ArrayList<SubscriptionEvent>()
        val manager = manager(connection)
        manager.startAdmission()
        val opened = async {
            manager.open(
                SubscriptionChannelId(9L),
                SubscriptionEventConsumer(received::add),
            )
        }
        runCurrent()
        connection.emit(started(stream()))
        runCurrent()
        val active = (opened.await() as SubscriptionOpenResult.Opened).subscription

        val status = SubscriptionEvent.Status(
            SubscriptionCondition.STATUS_REPORTED,
            SubscriptionIssue.BAD_SIGNAL,
        )
        connection.emit(status)
        connection.emit(status)
        connection.emit(SubscriptionEvent.Grace(12L))
        connection.emit(SubscriptionEvent.Dropped(Long.MAX_VALUE))
        connection.emit(SubscriptionEvent.Dropped(1L))
        connection.emit(
            SubscriptionEvent.Stopped(
                SubscriptionCondition.ERROR_REPORTED,
                SubscriptionIssue.NO_DISK_SPACE,
            ),
        )
        runCurrent()

        assertEquals(7, received.size)
        assertSame(status, received[1])
        assertSame(status, received[2])
        assertSame(SubscriptionIssue.BAD_SIGNAL, (received[1] as SubscriptionEvent.Status).issue)
        assertSame(SubscriptionIssue.NO_DISK_SPACE, (received.last() as SubscriptionEvent.Stopped).issue)
        assertEquals(SubscriptionCondition.ERROR_REPORTED, active.diagnostics.value.condition)
        assertEquals(12L, active.diagnostics.value.graceTimeoutSeconds)
        assertEquals(Long.MAX_VALUE, active.diagnostics.value.droppedPacketCount)
        assertTrue(active.diagnostics.value.droppedPacketCountOverflowed)
        assertSame(SubscriptionState.Starting, active.state.value)
        manager.closeAndJoin()
    }

    @Test
    fun `all typed subscribe failures terminate and unsubscribe exactly once`() = runTest {
        val cases = listOf(
            SubscriptionOperationResult.ServerRejected to SubscriptionOperationFailure.SERVER_REJECTED,
            SubscriptionOperationResult.AccessDenied to SubscriptionOperationFailure.ACCESS_DENIED,
            SubscriptionOperationResult.ConnectionLimit to SubscriptionOperationFailure.CONNECTION_LIMIT,
            SubscriptionOperationResult.Timeout to SubscriptionOperationFailure.TIMEOUT,
            SubscriptionOperationResult.MalformedResponse to SubscriptionOperationFailure.MALFORMED_RESPONSE,
            SubscriptionOperationResult.TransportUnavailable to
                SubscriptionOperationFailure.TRANSPORT_UNAVAILABLE,
            SubscriptionOperationResult.NotSupported to SubscriptionOperationFailure.NOT_SUPPORTED,
        )

        cases.forEach { (result, expected) ->
            val connection = RecordingSubscriptionConnection()
            connection.subscribeAction = { result }
            val manager = manager(connection)
            manager.startAdmission()

            val opened = async {
                manager.open(SubscriptionChannelId(10L), SubscriptionEventConsumer {})
            }
            runCurrent()
            val failure = opened.await() as SubscriptionOpenResult.Failed
            assertEquals(
                expected,
                (failure.reason as SubscriptionTerminalReason.OperationFailed).failure,
            )
            manager.closeAndJoin()
            assertEquals(1, connection.unsubscribeCount)
        }
    }

    @Test
    fun `subscribe rejection preserves the outcome in either terminal ordering`() = runTest {
        for (eventFirst in listOf(true, false)) {
            val reply = CompletableDeferred<SubscriptionOperationResult<SubscriptionConfirmation>>()
            val cleanup = CompletableDeferred<Unit>()
            val connection = RecordingSubscriptionConnection().apply {
                subscribeAction = { reply.await() }
                unsubscribeAction = { cleanup.await(); SubscriptionOperationResult.Ok(Unit) }
            }
            val manager = manager(connection)
            manager.startAdmission()
            val opened = async { manager.open(SubscriptionChannelId(10), SubscriptionEventConsumer {}) }
            runCurrent()
            if (eventFirst) {
                connection.emit(SubscriptionEvent.Terminated(SubscriptionTermination.SUBSCRIBE_REJECTED))
                connection.complete(connection.registeredIds.single())
                runCurrent()
                assertFalse(opened.isCompleted)
            }
            reply.complete(SubscriptionOperationResult.ConnectionLimit)
            runCurrent()
            if (!eventFirst) {
                connection.emit(SubscriptionEvent.Terminated(SubscriptionTermination.SUBSCRIBE_REJECTED))
                runCurrent()
            }
            cleanup.complete(Unit)
            runCurrent()
            val failure = opened.await() as SubscriptionOpenResult.Failed
            assertEquals(SubscriptionOperationFailure.CONNECTION_LIMIT,
                (failure.reason as SubscriptionTerminalReason.OperationFailed).failure)
            manager.closeAndJoin()
            assertEquals(1, connection.unsubscribeCount)
        }
    }

    @Test
    fun `parked subscribe rejection wins over late success and releases the subscription`() = runTest {
        val reply = CompletableDeferred<SubscriptionOperationResult<SubscriptionConfirmation>>()
        val connection = RecordingSubscriptionConnection().apply { subscribeAction = { reply.await() } }
        val manager = manager(connection)
        manager.startAdmission()
        val opened = async { manager.open(SubscriptionChannelId(10), SubscriptionEventConsumer {}) }
        runCurrent()

        connection.emit(started(stream()))
        connection.emit(SubscriptionEvent.Terminated(SubscriptionTermination.SUBSCRIBE_REJECTED))
        runCurrent()
        assertFalse(opened.isCompleted)

        reply.complete(successfulConfirmation())
        runCurrent()
        // Do not advance to the rejection deadline: the late reply itself must settle the open.
        assertTrue(opened.isCompleted)
        val failure = opened.await() as SubscriptionOpenResult.Failed
        assertEquals(SubscriptionOperationFailure.SERVER_REJECTED,
            (failure.reason as SubscriptionTerminalReason.OperationFailed).failure)
        assertEquals(1, connection.unsubscribeCount)
        manager.closeAndJoin()
        assertEquals(1, connection.unsubscribeCount)
    }

    @Test
    fun `rejected subscribe without an outcome expires and joins request before unsubscribe`() = runTest {
        val cancellationStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        val connection = RecordingSubscriptionConnection().apply {
            subscribeAction = {
                try {
                    awaitCancellation()
                } finally {
                    cancellationStarted.complete(Unit)
                    withContext(NonCancellable) { releaseRequest.await() }
                }
            }
        }
        val manager = manager(connection)
        manager.startAdmission()
        val opened = async { manager.open(SubscriptionChannelId(10), SubscriptionEventConsumer {}) }
        runCurrent()
        connection.emit(SubscriptionEvent.Terminated(SubscriptionTermination.SUBSCRIBE_REJECTED))
        runCurrent()
        advanceTimeBy(4_999)
        runCurrent()
        val completedBeforeDeadline = opened.isCompleted
        val cancelledBeforeDeadline = cancellationStarted.isCompleted
        advanceTimeBy(1)
        runCurrent()
        val cancelledAtDeadline = cancellationStarted.isCompleted
        val completedBeforeRequestJoined = opened.isCompleted
        val unsubscribedBeforeRequestJoined = connection.unsubscribeCount
        releaseRequest.complete(Unit)
        runCurrent()
        assertFalse(completedBeforeDeadline)
        assertFalse(cancelledBeforeDeadline)
        assertTrue(cancelledAtDeadline)
        assertFalse(completedBeforeRequestJoined)
        assertEquals(0, unsubscribedBeforeRequestJoined)
        val failure = opened.await() as SubscriptionOpenResult.Failed
        assertEquals(SubscriptionOperationFailure.SERVER_REJECTED,
            (failure.reason as SubscriptionTerminalReason.OperationFailed).failure)
        assertEquals(1, connection.unsubscribeCount)
        manager.closeAndJoin()
        assertEquals(1, connection.unsubscribeCount)
    }

    @Test
    fun `caller cancellation takes precedence during rejected subscribe deferral`() = runTest {
        var requestCancelled = false
        val connection = RecordingSubscriptionConnection().apply {
            subscribeAction = { try { awaitCancellation() } finally { requestCancelled = true } }
        }
        val manager = manager(connection)
        manager.startAdmission()
        val cancellation = CancellationException("fixed deferred open cancellation")
        var observed: CancellationException? = null
        val opening = launch {
            try {
                manager.open(SubscriptionChannelId(10), SubscriptionEventConsumer {})
            } catch (caught: CancellationException) {
                observed = caught
            }
        }
        runCurrent()
        connection.emit(SubscriptionEvent.Terminated(SubscriptionTermination.SUBSCRIBE_REJECTED))
        runCurrent()
        opening.cancel(cancellation)
        runCurrent()
        opening.join()
        assertSame(cancellation, observed)
        assertTrue(requestCancelled)
        assertEquals(1, connection.unsubscribeCount)
        manager.closeAndJoin()
    }

    @Test
    fun `owner close takes precedence during rejected subscribe deferral`() = runTest {
        var requestCancelled = false
        val connection = RecordingSubscriptionConnection().apply {
            subscribeAction = { try { awaitCancellation() } finally { requestCancelled = true } }
        }
        val manager = manager(connection)
        manager.startAdmission()
        val opened = async { manager.open(SubscriptionChannelId(10), SubscriptionEventConsumer {}) }
        runCurrent()
        connection.emit(SubscriptionEvent.Terminated(SubscriptionTermination.SUBSCRIBE_REJECTED))
        runCurrent()
        manager.closeAndJoin()
        val failure = opened.await() as SubscriptionOpenResult.Failed
        assertSame(SubscriptionTerminalReason.Closed, failure.reason)
        assertTrue(requestCancelled)
        assertEquals(1, connection.unsubscribeCount)
    }

    @Test
    fun `every attributed transport termination remains distinct in durable state`() = runTest {
        val cases = listOf(
            SubscriptionTermination.GENERATION_LOST to SubscriptionTerminalReason.GenerationLost,
            SubscriptionTermination.REMOTE_EOF to SubscriptionTerminalReason.RemoteEof,
            SubscriptionTermination.IO_FAILURE to SubscriptionTerminalReason.IoFailure,
            SubscriptionTermination.FRAMING_FAILURE to SubscriptionTerminalReason.FramingFailure,
            SubscriptionTermination.MALFORMED_MESSAGE to SubscriptionTerminalReason.MalformedMessage,
            SubscriptionTermination.TIMEOUT to SubscriptionTerminalReason.Timeout,
            SubscriptionTermination.LOCAL_RETIREMENT to SubscriptionTerminalReason.LocalRetirement,
            SubscriptionTermination.PUBLICATION_FAILURE to SubscriptionTerminalReason.PublicationFailure,
            SubscriptionTermination.INTERNAL_FAILURE to SubscriptionTerminalReason.InternalFailure,
            SubscriptionTermination.TRANSPORT_CLOSED to SubscriptionTerminalReason.TransportClosed,
        )

        cases.forEach { (termination, expected) ->
            val connection = RecordingSubscriptionConnection()
            val manager = manager(connection)
            manager.startAdmission()
            val opened = async {
                manager.open(SubscriptionChannelId(10L), SubscriptionEventConsumer {})
            }
            runCurrent()
            connection.emit(started(stream()))
            runCurrent()
            val subscription = (opened.await() as SubscriptionOpenResult.Opened).subscription

            connection.emit(SubscriptionEvent.Terminated(termination))
            runCurrent()

            assertSame(expected, (subscription.state.value as SubscriptionState.Terminal).reason)
            manager.closeAndJoin()
        }
    }

    @Test
    fun `startup failure waits for ordered drain before returning`() = runTest {
        val connection = RecordingSubscriptionConnection().apply {
            subscribeAction = { SubscriptionOperationResult.ServerRejected }
        }
        val unsubscribeEntered = CompletableDeferred<Unit>()
        val releaseUnsubscribe = CompletableDeferred<Unit>()
        connection.unsubscribeAction = {
            unsubscribeEntered.complete(Unit)
            releaseUnsubscribe.await()
            SubscriptionOperationResult.Ok(Unit)
        }
        val received = ArrayList<Class<out SubscriptionEvent>>()
        val manager = manager(connection)
        manager.startAdmission()

        val opened = async {
            manager.open(
                SubscriptionChannelId(17L),
                SubscriptionEventConsumer { event -> received += event.javaClass },
            )
        }
        unsubscribeEntered.await()
        connection.emit(packet())
        connection.emit(SubscriptionEvent.Terminated(SubscriptionTermination.TRANSPORT_CLOSED))
        runCurrent()

        assertFalse(opened.isCompleted)
        assertEquals(
            listOf(
                SubscriptionEvent.Packet::class.java,
                SubscriptionEvent.Terminated::class.java,
            ),
            received,
        )

        releaseUnsubscribe.complete(Unit)
        runCurrent()
        val result = opened.await() as SubscriptionOpenResult.Failed
        assertEquals(
            SubscriptionOperationFailure.SERVER_REJECTED,
            (result.reason as SubscriptionTerminalReason.OperationFailed).failure,
        )
        assertEquals(1, connection.unsubscribeCount)
        manager.closeAndJoin()
    }

    @Test
    fun `manager teardown propagates exact child cancellation after joining`() = runTest {
        val connection = RecordingSubscriptionConnection()
        val consumerEntered = CompletableDeferred<Unit>()
        val releaseConsumer = CompletableDeferred<Unit>()
        val cancellation = CancellationException("fixed manager child cancellation")
        val manager = manager(connection)
        manager.startAdmission()
        val opened = async {
            manager.open(
                SubscriptionChannelId(18L),
                SubscriptionEventConsumer { event ->
                    if (event is SubscriptionEvent.Packet) {
                        consumerEntered.complete(Unit)
                        releaseConsumer.await()
                        throw cancellation
                    }
                },
            )
        }
        runCurrent()
        connection.emit(SubscriptionId(0L), started(stream()))
        runCurrent()
        opened.await()
        connection.emit(SubscriptionId(0L), packet())
        consumerEntered.await()

        val closing = async { caughtCancellation { manager.closeAndJoin() } }
        runCurrent()
        assertFalse(closing.isCompleted)
        releaseConsumer.complete(Unit)
        runCurrent()

        assertSame(cancellation, closing.await())
        assertEquals(1, connection.unsubscribeCount)
    }

    @Test
    fun `joining close from the event consumer fails fast without deadlock`() = runTest {
        val connection = RecordingSubscriptionConnection()
        val failure = CompletableDeferred<IllegalStateException>()
        lateinit var active: ActiveSubscription
        val manager = manager(connection)
        manager.startAdmission()
        val opened = async {
            manager.open(
                SubscriptionChannelId(19L),
                SubscriptionEventConsumer { event ->
                    if (event is SubscriptionEvent.Packet) {
                        try {
                            active.close()
                        } catch (exception: IllegalStateException) {
                            failure.complete(exception)
                        }
                    }
                },
            )
        }
        runCurrent()
        connection.emit(SubscriptionId(0L), started(stream()))
        runCurrent()
        active = (opened.await() as SubscriptionOpenResult.Opened).subscription
        connection.emit(SubscriptionId(0L), packet())
        runCurrent()

        assertEquals(
            "Subscription close cannot join from its event consumer",
            failure.await().message,
        )
        manager.closeAndJoin()
        assertEquals(1, connection.unsubscribeCount)
    }

    @Test
    fun `joining manager close from the event consumer fails fast without closing admission`() =
        runTest {
            val connection = RecordingSubscriptionConnection()
            val failure = CompletableDeferred<IllegalStateException>()
            lateinit var manager: SubscriptionManager
            manager = manager(connection)
            manager.startAdmission()
            val opened = async {
                manager.open(
                    SubscriptionChannelId(27L),
                    SubscriptionEventConsumer { event ->
                        if (event is SubscriptionEvent.Packet) {
                            try {
                                manager.closeAndJoin()
                            } catch (exception: IllegalStateException) {
                                failure.complete(exception)
                            }
                        }
                    },
                )
            }
            runCurrent()
            connection.emit(SubscriptionId(0L), started(stream()))
            runCurrent()
            opened.await()
            connection.emit(SubscriptionId(0L), packet())
            runCurrent()

            assertEquals(
                "Subscription manager cannot join from its event consumer",
                failure.await().message,
            )
            val nextOpen = async {
                manager.open(SubscriptionChannelId(28L), SubscriptionEventConsumer {})
            }
            runCurrent()
            connection.emit(SubscriptionId(1L), started(stream()))
            runCurrent()
            assertTrue(nextOpen.await() is SubscriptionOpenResult.Opened)
            manager.closeAndJoin()
            assertEquals(2, connection.unsubscribeCount)
        }

    @ParameterizedTest
    @EnumSource(JoiningCall::class)
    fun `joining calls fail fast from a coroutine that inherits the delivery`(call: JoiningCall) = runTest {
        val connection = RecordingSubscriptionConnection()
        connection.subscribeAction = { successfulConfirmation(timeshiftPeriodSeconds = 300L) }
        val elsewhere = StandardTestDispatcher(testScheduler)
        val failure = CompletableDeferred<Throwable?>()
        val delivered = ArrayList<Long?>()
        lateinit var active: ActiveSubscription
        val manager = manager(connection)
        manager.startAdmission()
        try {
            active = openActive(manager, connection, SubscriptionChannelId(34L), 300.seconds) { event ->
                if (event is SubscriptionEvent.Packet) {
                    delivered += event.presentationTimeUs
                    if (event.presentationTimeUs == 200L) {
                        // The dispatcher switch runs the call in its own child job of the delivery.
                        val outcome = runCatching { withContext(elsewhere) { call.perform(active, manager) } }
                        failure.complete(outcome.exceptionOrNull())
                    }
                }
            }
            connection.emit(SubscriptionId(0L), packet(presentationTimeUs = 200L))
            connection.emit(SubscriptionId(0L), packet(presentationTimeUs = 210L))
            runCurrent()

            assertTrue(failure.isCompleted, "A nested joining call must fail fast, not wait on its own teardown")
            assertTrue(failure.await() is IllegalStateException)
            assertEquals(listOf<Long?>(200L, 210L), delivered)
            assertTrue(active.state.value is SubscriptionState.Playable)
            assertSame(SubscriptionCloseResult.CLOSED, active.close())
            val terminal = active.state.value as SubscriptionState.Terminal
            assertSame(SubscriptionTerminalReason.Closed, terminal.reason)
        } finally {
            // A regressed guard leaves the call waiting on its own teardown; joining would hang the suite.
            if (failure.isCompleted) manager.closeAndJoin()
        }
    }

    @Test
    fun `pre-cancelled admission propagates cancellation`() = runTest {
        val connection = RecordingSubscriptionConnection()
        val manager = manager(connection)
        val cancelledJob = Job().apply {
            cancel(CancellationException("fixed pre-cancelled admission"))
        }

        caughtCancellation {
            withContext(cancelledJob) {
                manager.open(SubscriptionChannelId(20L), SubscriptionEventConsumer {})
            }
        }
        assertTrue(connection.registeredIds.isEmpty())
        manager.closeAndJoin()
    }

    @Test
    fun `caller cancellation after admission waits for cleanup and preserves identity`() = runTest {
        val connection = RecordingSubscriptionConnection()
        val manager = manager(connection)
        manager.startAdmission()
        val cancellation = CancellationException("fixed admitted open cancellation")
        var observedCancellation: CancellationException? = null

        val opening = launch {
            try {
                manager.open(SubscriptionChannelId(29L), SubscriptionEventConsumer {})
            } catch (caught: CancellationException) {
                observedCancellation = caught
            }
        }
        runCurrent()
        assertTrue(
            connection.registeredIds == listOf(SubscriptionId(0L)),
            "The subscription must be admitted before caller cancellation",
        )

        opening.cancel(cancellation)
        runCurrent()
        opening.join()

        assertSame(cancellation, observedCancellation)
        assertEquals(1, connection.unsubscribeCount)
        manager.closeAndJoin()
    }

    @Test
    fun `caller cancellation while closing waits for unsubscribe and preserves identity`() = runTest {
        val connection = RecordingSubscriptionConnection()
        val unsubscribeEntered = CompletableDeferred<Unit>()
        val releaseUnsubscribe = CompletableDeferred<Unit>()
        connection.unsubscribeAction = {
            unsubscribeEntered.complete(Unit)
            releaseUnsubscribe.await()
            SubscriptionOperationResult.Ok(Unit)
        }
        val manager = manager(connection)
        manager.startAdmission()
        val opening = async {
            manager.open(SubscriptionChannelId(30L), SubscriptionEventConsumer {})
        }
        runCurrent()
        connection.emit(SubscriptionId(0L), started(stream()))
        runCurrent()
        val active = (opening.await() as SubscriptionOpenResult.Opened).subscription
        val cancellation = CancellationException("fixed close caller cancellation")
        var observedCancellation: CancellationException? = null

        val closing = launch {
            try {
                active.close()
            } catch (caught: CancellationException) {
                observedCancellation = caught
            }
        }
        unsubscribeEntered.await()
        closing.cancel(cancellation)
        runCurrent()
        assertFalse(closing.isCompleted)

        releaseUnsubscribe.complete(Unit)
        runCurrent()
        closing.join()

        assertSame(cancellation, observedCancellation)
        assertEquals(1, connection.unsubscribeCount)
        manager.closeAndJoin()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `failed unsubscribe stops collection between deliveries instead of cancelling the consumer`(
        closerCancelled: Boolean,
    ) = runTest {
        val connection = RecordingSubscriptionConnection()
        connection.unsubscribeAction = { SubscriptionOperationResult.Timeout }
        val delivered = ArrayList<Long?>()
        val consumerEntered = CompletableDeferred<Unit>()
        val releaseConsumer = CompletableDeferred<Unit>()
        var consumerCancellation: CancellationException? = null
        val manager = manager(connection)
        manager.startAdmission()
        try {
            val opening = async {
                manager.open(
                    SubscriptionChannelId(31L),
                    SubscriptionEventConsumer { event ->
                        if (event is SubscriptionEvent.Packet) {
                            delivered += event.presentationTimeUs
                            if (event.presentationTimeUs == 200L) {
                                consumerEntered.complete(Unit)
                                try {
                                    releaseConsumer.await()
                                } catch (cancellation: CancellationException) {
                                    consumerCancellation = cancellation
                                    throw cancellation
                                }
                            }
                        }
                    },
                )
            }
            runCurrent()
            connection.emit(SubscriptionId(0L), started(stream()))
            runCurrent()
            val active = (opening.await() as SubscriptionOpenResult.Opened).subscription
            connection.emit(SubscriptionId(0L), packet(presentationTimeUs = 200L))
            consumerEntered.await()
            connection.emit(SubscriptionId(0L), packet(presentationTimeUs = 210L))

            val closing = async { active.close() }
            runCurrent()
            assertEquals(1, connection.unsubscribeCount)
            assertFalse(closing.isCompleted, "Close must wait for the in-flight delivery")
            if (closerCancelled) {
                closing.cancel()
                runCurrent()
                assertFalse(closing.isCompleted, "A cancelled closer still joins teardown")
            }
            releaseConsumer.complete(Unit)
            runCurrent()

            if (closerCancelled) {
                assertTrue(closing.isCancelled)
                caughtCancellation { closing.await() }
            } else {
                assertSame(SubscriptionCloseResult.CLEANUP_FAILED, closing.await())
            }
            assertEquals(null, consumerCancellation)
            assertEquals(listOf<Long?>(200L), delivered)
            val terminal = active.state.value as SubscriptionState.Terminal
            assertSame(SubscriptionTerminalReason.Closed, terminal.reason)
            assertFalse(active.diagnostics.value.consumerDrainTimedOut)
        } finally {
            releaseConsumer.complete(Unit)
            manager.closeAndJoin()
        }
        assertEquals(1, connection.unsubscribeCount)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `failed unsubscribe cancels a delivery that outlasts the grace period`(closerCancelled: Boolean) = runTest {
        val connection = RecordingSubscriptionConnection()
        connection.unsubscribeAction = { SubscriptionOperationResult.Timeout }
        val consumerEntered = CompletableDeferred<Unit>()
        var consumerCancelled = false
        val manager = manager(connection)
        manager.startAdmission()
        try {
            val opening = async {
                manager.open(
                    SubscriptionChannelId(32L),
                    SubscriptionEventConsumer { event ->
                        if (event is SubscriptionEvent.Packet) {
                            consumerEntered.complete(Unit)
                            try {
                                awaitCancellation()
                            } catch (cancellation: CancellationException) {
                                consumerCancelled = true
                                throw cancellation
                            }
                        }
                    },
                )
            }
            runCurrent()
            connection.emit(SubscriptionId(0L), started(stream()))
            runCurrent()
            val active = (opening.await() as SubscriptionOpenResult.Opened).subscription
            connection.emit(SubscriptionId(0L), packet())
            consumerEntered.await()

            val closing = async { active.close() }
            runCurrent()
            if (closerCancelled) {
                closing.cancel()
                runCurrent()
            }
            advanceTimeBy(TEARDOWN_GRACE - 1.milliseconds)
            runCurrent()
            assertFalse(closing.isCompleted, "Close must wait for the whole grace period")
            assertFalse(consumerCancelled)
            advanceTimeBy(1.milliseconds)
            runCurrent()

            assertTrue(closing.isCompleted, "Close must not wait past the grace period")
            if (closerCancelled) {
                assertTrue(closing.isCancelled)
                caughtCancellation { closing.await() }
            } else {
                assertSame(SubscriptionCloseResult.CLEANUP_FAILED, closing.await())
            }
            assertTrue(consumerCancelled)
            val terminal = active.state.value as SubscriptionState.Terminal
            assertSame(SubscriptionTerminalReason.Closed, terminal.reason)
            assertTrue(active.diagnostics.value.consumerDrainTimedOut)
        } finally {
            manager.closeAndJoin()
        }
        assertEquals(1, connection.unsubscribeCount)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `ordered close drain waits for the consumer only within the grace period`(finishesInTime: Boolean) =
        runTest {
            val connection = RecordingSubscriptionConnection()
            val consumerEntered = CompletableDeferred<Unit>()
            val releaseConsumer = CompletableDeferred<Unit>()
            var consumerCancelled = false
            val delivered = ArrayList<Long?>()
            val manager = manager(connection)
            manager.startAdmission()
            try {
                val opening = async {
                    manager.open(
                        SubscriptionChannelId(33L),
                        SubscriptionEventConsumer { event ->
                            if (event is SubscriptionEvent.Packet) {
                                delivered += event.presentationTimeUs
                                if (event.presentationTimeUs == 200L) {
                                    consumerEntered.complete(Unit)
                                    try {
                                        releaseConsumer.await()
                                    } catch (cancellation: CancellationException) {
                                        consumerCancelled = true
                                        throw cancellation
                                    }
                                }
                            }
                        },
                    )
                }
                runCurrent()
                connection.emit(SubscriptionId(0L), started(stream()))
                runCurrent()
                val active = (opening.await() as SubscriptionOpenResult.Opened).subscription
                connection.emit(SubscriptionId(0L), packet(presentationTimeUs = 200L))
                consumerEntered.await()
                connection.emit(SubscriptionId(0L), packet(presentationTimeUs = 210L))

                val closing = async { active.close() }
                runCurrent()
                assertEquals(1, connection.unsubscribeCount)
                advanceTimeBy(TEARDOWN_GRACE - 1.milliseconds)
                runCurrent()
                assertFalse(closing.isCompleted, "Close must wait for the ordered drain")
                if (finishesInTime) releaseConsumer.complete(Unit) else advanceTimeBy(1.milliseconds)
                runCurrent()

                assertTrue(closing.isCompleted)
                val expectedResult = if (finishesInTime) {
                    SubscriptionCloseResult.CLOSED
                } else {
                    SubscriptionCloseResult.CLEANUP_FAILED
                }
                assertSame(expectedResult, closing.await())
                assertEquals(!finishesInTime, consumerCancelled)
                assertEquals(if (finishesInTime) listOf<Long?>(200L, 210L) else listOf<Long?>(200L), delivered)
                assertEquals(!finishesInTime, active.diagnostics.value.consumerDrainTimedOut)
                val terminal = active.state.value as SubscriptionState.Terminal
                assertSame(SubscriptionTerminalReason.Closed, terminal.reason)
            } finally {
                releaseConsumer.complete(Unit)
                manager.closeAndJoin()
            }
        }

    @Test
    fun `a close drain that ends exactly at the grace deadline is not reported as timed out`() = runTest {
        val connection = RecordingSubscriptionConnection()
        val delivered = ArrayList<Long?>()
        var consumerCancelled = false
        val manager = manager(connection)
        manager.startAdmission()
        try {
            val active = openActive(manager, connection, SubscriptionChannelId(35L)) { event ->
                if (event is SubscriptionEvent.Packet) {
                    delivered += event.presentationTimeUs
                    // Started before close, so this delay and the teardown deadline expire together.
                    if (event.presentationTimeUs == 200L) {
                        try {
                            delay(TEARDOWN_GRACE)
                        } catch (cancellation: CancellationException) {
                            consumerCancelled = true
                            throw cancellation
                        }
                    }
                }
            }
            connection.emit(SubscriptionId(0L), packet(presentationTimeUs = 200L))
            connection.emit(SubscriptionId(0L), packet(presentationTimeUs = 210L))
            runCurrent()
            val closing = async { active.close() }
            runCurrent()
            advanceTimeBy(TEARDOWN_GRACE)
            runCurrent()

            assertTrue(closing.isCompleted, "Close must end at the grace deadline")
            assertSame(SubscriptionCloseResult.CLOSED, closing.await())
            assertFalse(consumerCancelled)
            assertEquals(listOf<Long?>(200L, 210L), delivered)
            assertFalse(active.diagnostics.value.consumerDrainTimedOut)
            val terminal = active.state.value as SubscriptionState.Terminal
            assertSame(SubscriptionTerminalReason.Closed, terminal.reason)
        } finally {
            manager.closeAndJoin()
        }
    }

    @Test
    fun `a delivery that ends at the grace deadline after a failed unsubscribe is not reported as timed out`() =
        runTest {
            val connection = RecordingSubscriptionConnection()
            connection.unsubscribeAction = { SubscriptionOperationResult.Timeout }
            var consumerCancelled = false
            val manager = manager(connection)
            manager.startAdmission()
            try {
                val active = openActive(manager, connection, SubscriptionChannelId(36L)) { event ->
                    if (event is SubscriptionEvent.Packet) {
                        try {
                            delay(TEARDOWN_GRACE)
                        } catch (cancellation: CancellationException) {
                            consumerCancelled = true
                            throw cancellation
                        }
                    }
                }
                connection.emit(SubscriptionId(0L), packet())
                runCurrent()
                val closing = async { active.close() }
                runCurrent()
                advanceTimeBy(TEARDOWN_GRACE)
                runCurrent()

                assertTrue(closing.isCompleted, "Close must end at the grace deadline")
                assertSame(SubscriptionCloseResult.CLEANUP_FAILED, closing.await())
                assertFalse(consumerCancelled)
                assertFalse(active.diagnostics.value.consumerDrainTimedOut)
                val terminal = active.state.value as SubscriptionState.Terminal
                assertSame(SubscriptionTerminalReason.Closed, terminal.reason)
                manager.closeAndJoin()
                assertEquals(1, connection.unsubscribeCount)
            } finally {
                manager.closeAndJoin()
            }
        }

    @Test
    fun `a consumer waiting on an unrelated close is cancelled after the grace period`() = runTest {
        val connection = RecordingSubscriptionConnection()
        val unrelatedClose = CompletableDeferred<Deferred<SubscriptionCloseResult>>()
        var consumerCancelled = false
        lateinit var active: ActiveSubscription
        val manager = manager(connection)
        manager.startAdmission()
        try {
            active = openActive(manager, connection, SubscriptionChannelId(37L)) { event ->
                if (event is SubscriptionEvent.Packet) {
                    // The background scope does not inherit the delivery, so no guard can see this wait.
                    val closing = backgroundScope.async { active.close() }
                    unrelatedClose.complete(closing)
                    try {
                        closing.await()
                    } catch (cancellation: CancellationException) {
                        consumerCancelled = true
                        throw cancellation
                    }
                }
            }
            connection.emit(SubscriptionId(0L), packet())
            runCurrent()
            val closing = unrelatedClose.await()
            advanceTimeBy(TEARDOWN_GRACE - 1.milliseconds)
            runCurrent()
            assertFalse(closing.isCompleted, "A cancellable wait holds teardown for the grace period")
            assertFalse(consumerCancelled)
            advanceTimeBy(1.milliseconds)
            runCurrent()

            assertTrue(closing.isCompleted, "Cancelling the waiting consumer must release teardown")
            assertSame(SubscriptionCloseResult.CLEANUP_FAILED, closing.await())
            assertTrue(consumerCancelled)
            val terminal = active.state.value as SubscriptionState.Terminal
            assertSame(SubscriptionTerminalReason.Closed, terminal.reason)
            assertTrue(active.diagnostics.value.consumerDrainTimedOut)
        } finally {
            manager.closeAndJoin()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a consumer's own cancellation during the close drain still propagates`(afterGrace: Boolean) = runTest {
        val connection = RecordingSubscriptionConnection()
        val consumerEntered = CompletableDeferred<Unit>()
        val releaseConsumer = CompletableDeferred<Unit>()
        val manager = manager(connection)
        manager.startAdmission()
        try {
            val active = openActive(manager, connection, SubscriptionChannelId(38L)) { event ->
                if (event is SubscriptionEvent.Packet) {
                    consumerEntered.complete(Unit)
                    // Teardown's cancellation cannot end this wait; the consumer then raises its own.
                    withContext(NonCancellable) { releaseConsumer.await() }
                    throw CancellationException("consumer stopped")
                }
            }
            connection.emit(SubscriptionId(0L), packet())
            consumerEntered.await()
            val closing = async { active.close() }
            runCurrent()
            if (afterGrace) {
                advanceTimeBy(TEARDOWN_GRACE)
                runCurrent()
                assertFalse(closing.isCompleted, "Teardown still joins the delivery it cancelled")
            }
            releaseConsumer.complete(Unit)
            runCurrent()

            assertTrue(closing.isCompleted)
            assertEquals("consumer stopped", caughtCancellation { closing.await() }.message)
            val terminal = active.state.value as SubscriptionState.Terminal
            assertSame(SubscriptionTerminalReason.InfrastructureFailed, terminal.reason)
            assertEquals(afterGrace, active.diagnostics.value.consumerDrainTimedOut)
        } finally {
            releaseConsumer.complete(Unit)
            manager.closeAndJoin()
        }
    }

    @Test
    fun `a failure raised in response to the teardown cancellation keeps the close reason`() = runTest {
        val connection = RecordingSubscriptionConnection()
        val consumerEntered = CompletableDeferred<Unit>()
        val manager = manager(connection)
        manager.startAdmission()
        try {
            val active = openActive(manager, connection, SubscriptionChannelId(39L)) { event ->
                if (event is SubscriptionEvent.Packet) {
                    consumerEntered.complete(Unit)
                    try {
                        awaitCancellation()
                    } catch (cancellation: CancellationException) {
                        throw IllegalStateException("consumer stopped", cancellation)
                    }
                }
            }
            connection.emit(SubscriptionId(0L), packet())
            consumerEntered.await()
            val closing = async { active.close() }
            runCurrent()
            advanceTimeBy(TEARDOWN_GRACE)
            runCurrent()

            assertTrue(closing.isCompleted, "Close must not wait past the grace period")
            assertSame(SubscriptionCloseResult.CLEANUP_FAILED, closing.await())
            val terminal = active.state.value as SubscriptionState.Terminal
            assertSame(SubscriptionTerminalReason.Closed, terminal.reason)
            assertTrue(active.diagnostics.value.consumerDrainTimedOut)
        } finally {
            manager.closeAndJoin()
        }
    }

    @Test
    fun `manager allocates monotonically and forwards packet before start`() = runTest {
        val connection = RecordingSubscriptionConnection()
        val firstEvents = ArrayList<Class<out SubscriptionEvent>>()
        val manager = manager(connection)
        manager.startAdmission()

        val firstOpen = async {
            manager.open(
                SubscriptionChannelId(21L),
                SubscriptionEventConsumer { event -> firstEvents += event.javaClass },
            )
        }
        runCurrent()
        connection.emit(SubscriptionId(0L), packet())
        connection.emit(SubscriptionId(0L), started(stream()))
        runCurrent()
        val first = (firstOpen.await() as SubscriptionOpenResult.Opened).subscription
        assertEquals(
            listOf(SubscriptionEvent.Packet::class.java, SubscriptionEvent.Started::class.java),
            firstEvents,
        )

        val secondOpen = async {
            manager.open(SubscriptionChannelId(22L), SubscriptionEventConsumer {})
        }
        runCurrent()
        connection.emit(SubscriptionId(1L), started(stream()))
        runCurrent()
        val second = (secondOpen.await() as SubscriptionOpenResult.Opened).subscription
        assertEquals(SubscriptionCloseResult.CLOSED, first.close())

        val thirdOpen = async {
            manager.open(SubscriptionChannelId(23L), SubscriptionEventConsumer {})
        }
        runCurrent()
        connection.emit(SubscriptionId(2L), started(stream()))
        runCurrent()
        thirdOpen.await()

        assertTrue(
            connection.registeredIds == listOf(
                SubscriptionId(0L),
                SubscriptionId(1L),
                SubscriptionId(2L),
            ),
            "A generation must allocate monotonically without identifier reuse",
        )
        assertEquals(SubscriptionCloseResult.CLOSED, second.close())
        manager.closeAndJoin()
        assertEquals(3, connection.unsubscribeCount)
    }

    @Test
    fun `consumer failure unexpected closure and concurrent close each unsubscribe once`() = runTest {
        val consumerConnection = RecordingSubscriptionConnection()
        val consumerManager = manager(consumerConnection)
        consumerManager.startAdmission()
        val consumerOpen = async {
            consumerManager.open(
                SubscriptionChannelId(24L),
                SubscriptionEventConsumer { event ->
                    if (event is SubscriptionEvent.Packet) error("unsafe consumer detail")
                },
            )
        }
        runCurrent()
        consumerConnection.emit(SubscriptionId(0L), started(stream()))
        runCurrent()
        val consumerActive =
            (consumerOpen.await() as SubscriptionOpenResult.Opened).subscription
        consumerConnection.emit(SubscriptionId(0L), packet())
        runCurrent()
        assertSame(
            SubscriptionTerminalReason.ConsumerFailed,
            (consumerActive.state.value as SubscriptionState.Terminal).reason,
        )
        consumerManager.closeAndJoin()
        assertEquals(1, consumerConnection.unsubscribeCount)

        val closureConnection = RecordingSubscriptionConnection()
        val closureManager = manager(closureConnection)
        closureManager.startAdmission()
        val closureOpen = async {
            closureManager.open(SubscriptionChannelId(25L), SubscriptionEventConsumer {})
        }
        runCurrent()
        closureConnection.complete(SubscriptionId(0L))
        runCurrent()
        assertSame(
            SubscriptionTerminalReason.UnexpectedStreamClosure,
            (closureOpen.await() as SubscriptionOpenResult.Failed).reason,
        )
        closureManager.closeAndJoin()
        assertEquals(1, closureConnection.unsubscribeCount)

        val closeConnection = RecordingSubscriptionConnection()
        val closeManager = manager(closeConnection)
        closeManager.startAdmission()
        val closeOpen = async {
            closeManager.open(SubscriptionChannelId(26L), SubscriptionEventConsumer {})
        }
        runCurrent()
        closeConnection.emit(SubscriptionId(0L), started(stream()))
        runCurrent()
        val closeActive = (closeOpen.await() as SubscriptionOpenResult.Opened).subscription
        val firstClose = async { closeActive.close() }
        val secondClose = async { closeActive.close() }
        runCurrent()
        assertEquals(SubscriptionCloseResult.CLOSED, firstClose.await())
        assertEquals(SubscriptionCloseResult.CLOSED, secondClose.await())
        closeManager.closeAndJoin()
        assertEquals(1, closeConnection.unsubscribeCount)
    }

    @Test
    fun `subscribe consumer and unsubscribe cancellation preserve exact instances`() = runTest {
        val subscribeCancellation = CancellationException("fixed subscribe cancellation")
        val subscribeConnection = RecordingSubscriptionConnection().apply {
            subscribeAction = { throw subscribeCancellation }
        }
        val subscribeManager = manager(subscribeConnection)
        subscribeManager.startAdmission()
        assertSame(
            subscribeCancellation,
            caughtCancellation {
                subscribeManager.open(SubscriptionChannelId(11L), SubscriptionEventConsumer {})
            },
        )
        subscribeManager.closeAndJoin()
        assertEquals(1, subscribeConnection.unsubscribeCount)

        val consumerCancellation = CancellationException("fixed consumer cancellation")
        val consumerConnection = RecordingSubscriptionConnection()
        val consumerManager = manager(consumerConnection)
        consumerManager.startAdmission()
        val consumerOpen = async {
            consumerManager.open(
                SubscriptionChannelId(12L),
                SubscriptionEventConsumer { event ->
                    if (event is SubscriptionEvent.Packet) throw consumerCancellation
                },
            )
        }
        runCurrent()
        consumerConnection.emit(started(stream()))
        runCurrent()
        val consumerActive =
            (consumerOpen.await() as SubscriptionOpenResult.Opened).subscription
        consumerConnection.emit(packet())
        runCurrent()
        assertSame(consumerCancellation, caughtCancellation { consumerActive.close() })
        consumerManager.closeAndJoin()
        assertEquals(1, consumerConnection.unsubscribeCount)

        val unsubscribeCancellation = CancellationException("fixed unsubscribe cancellation")
        val unsubscribeConnection = RecordingSubscriptionConnection()
        unsubscribeConnection.unsubscribeAction = { throw unsubscribeCancellation }
        val unsubscribeManager = manager(unsubscribeConnection)
        unsubscribeManager.startAdmission()
        val unsubscribeOpen = async {
            unsubscribeManager.open(SubscriptionChannelId(13L), SubscriptionEventConsumer {})
        }
        runCurrent()
        unsubscribeConnection.emit(started(stream()))
        runCurrent()
        val active = (unsubscribeOpen.await() as SubscriptionOpenResult.Opened).subscription
        assertSame(unsubscribeCancellation, caughtCancellation { active.close() })
        unsubscribeManager.closeAndJoin()
        assertEquals(1, unsubscribeConnection.unsubscribeCount)
    }

    @Test
    fun `invalid tracks terminate but valid replacement tracks keep ordered delivery`() = runTest {
        val invalidConnection = RecordingSubscriptionConnection()
        val invalidReceived = ArrayList<SubscriptionEvent>()
        val invalidManager = manager(invalidConnection)
        invalidManager.startAdmission()
        val invalidOpen = async {
            invalidManager.open(
                SubscriptionChannelId(14L),
                SubscriptionEventConsumer(invalidReceived::add),
            )
        }
        runCurrent()
        invalidConnection.emit(started())
        invalidConnection.emit(SubscriptionEvent.Status(SubscriptionCondition.STATUS_REPORTED))
        runCurrent()
        assertSame(
            SubscriptionTerminalReason.InvalidTracks,
            (invalidOpen.await() as SubscriptionOpenResult.Failed).reason,
        )
        invalidManager.closeAndJoin()
        assertEquals(1, invalidReceived.size)

        val replacementConnection = RecordingSubscriptionConnection()
        val replacementReceived = ArrayList<SubscriptionEvent>()
        val replacementManager = manager(replacementConnection)
        replacementManager.startAdmission()
        val replacementOpen = async {
            replacementManager.open(
                SubscriptionChannelId(15L),
                SubscriptionEventConsumer(replacementReceived::add),
            )
        }
        runCurrent()
        replacementConnection.emit(started(stream()))
        runCurrent()
        val active = (replacementOpen.await() as SubscriptionOpenResult.Opened).subscription
        replacementConnection.emit(started(stream(1L)))
        replacementConnection.emit(SubscriptionEvent.Status(SubscriptionCondition.STATUS_REPORTED))
        runCurrent()
        assertEquals(StreamIndex(1L), (active.state.value as SubscriptionState.Playable).tracks.streams.single().index)
        replacementManager.closeAndJoin()
        assertEquals(3, replacementReceived.size)
    }

    @Test
    fun `packet payload is forwarded by identity without copying and public lists are immutable`() = runTest {
        val binary = CountingBinary(byteArrayOf(1, 2, 3))
        val connection = RecordingSubscriptionConnection()
        val received = ArrayList<SubscriptionEvent>()
        val sourceStreams = arrayListOf(stream(codecMetadata = binary))
        val started = started(*sourceStreams.toTypedArray())
        sourceStreams.clear()
        val manager = manager(connection)
        manager.startAdmission()
        val opened = async {
            manager.open(
                SubscriptionChannelId(16L),
                SubscriptionEventConsumer(received::add),
            )
        }
        runCurrent()
        connection.emit(started)
        runCurrent()
        val active = (opened.await() as SubscriptionOpenResult.Opened).subscription

        val packet = packet(binary)
        connection.emit(packet)
        runCurrent()

        assertSame(packet, received[1])
        assertSame(binary, (received[1] as SubscriptionEvent.Packet).payload)
        assertEquals(0, binary.copyCount)
        assertEquals(1, started.streams?.size)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (started.streams as MutableList<SubscriptionStream>).add(stream(2L))
        }
        val tracks = (active.state.value as SubscriptionState.Playable).tracks
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (tracks.streams as MutableList<SubscriptionStream>).add(stream(3L))
        }
        manager.closeAndJoin()
    }

    @Test
    fun `allocator includes unsigned maximum then remains exhausted`() {
        val allocator = SubscriptionIdAllocator(0xffff_fffeL)

        assertTrue(
            allocator.allocate() == SubscriptionId(0xffff_fffeL),
            "The penultimate unsigned identifier must be allocated",
        )
        assertTrue(
            allocator.allocate() == SubscriptionId(0xffff_ffffL),
            "The unsigned maximum identifier must be allocated",
        )
        assertTrue(allocator.allocate() == null, "The allocator must not wrap")
        assertTrue(allocator.allocate() == null, "Exhaustion must be permanent")
    }

    private suspend fun TestScope.openActive(
        manager: SubscriptionManager,
        connection: RecordingSubscriptionConnection,
        channelId: SubscriptionChannelId,
        timeshiftPeriod: Duration = Duration.ZERO,
        onEvent: suspend (SubscriptionEvent) -> Unit,
    ): ActiveSubscription {
        val opening = async {
            manager.open(channelId, SubscriptionEventConsumer { event -> onEvent(event) }, timeshiftPeriod)
        }
        runCurrent()
        connection.emit(SubscriptionId(0L), started(stream()))
        runCurrent()
        return (opening.await() as SubscriptionOpenResult.Opened).subscription
    }

    private fun TestScope.manager(connection: SubscriptionConnection): SubscriptionManager =
        createSubscriptionManager(connection, StandardTestDispatcher(testScheduler))
}

private val TEARDOWN_GRACE = 5.seconds
