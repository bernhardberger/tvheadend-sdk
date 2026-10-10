@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.playback

import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionMonitorTest {
    /** Teardown waits whose cancellation can invoke a consumer-installed handler synchronously. */
    enum class Delivery { COLLECTION, FAILED_UNSUBSCRIBE, REPLAY }

    @Test
    fun `expiring a replay publication wait reports skipped delivery without a consumer timeout`() = runTest {
        val connection = RecordingSubscriptionConnection()
        val dispatcher = PausedNotificationDispatcher(StandardTestDispatcher(testScheduler))
        val manager = createSubscriptionManager(connection, dispatcher)
        manager.startAdmission()
        val refuse = CompletableDeferred<Unit>()
        val received = ArrayList<SubscriptionEvent>()
        val closer = Executors.newSingleThreadExecutor()
        try {
            val active = open(manager, connection) { received += it }
            connection.skipAction = {
                refuse.await()
                SubscriptionOperationResult.AccessDenied
            }
            val seeking = async { active.seek(SubscriptionSeekTarget.Absolute(30.seconds)) }
            runCurrent()
            connection.emit(packet())
            runCurrent()

            // Pin the foreign notifier inside closeRequested.complete, before it posts the owner.
            // Close is requested, but seek admission has not shut: the refusal can still commit.
            dispatcher.pauseNextDispatch.set(true)
            val closing = closer.submit<SubscriptionCloseResult> { runBlocking { active.close() } }
            assertTrue(dispatcher.intercepted.await(5, TimeUnit.SECONDS), "The notifier must reach owner dispatch")
            refuse.complete(Unit)
            runCurrent()
            // The committed replay now awaits its publication barrier; no consumer call runs.
            assertFalse(seeking.isCompleted)
            assertEquals(0, connection.unsubscribeCount)

            // Post the owner, but keep the notifier busy so the replay's marker cannot complete.
            dispatcher.allowPosting.countDown()
            assertTrue(dispatcher.posted.await(5, TimeUnit.SECONDS), "The owner must be posted")
            runCurrent()
            advanceTimeBy(5.seconds)
            runCurrent()
            assertEquals(1, connection.unsubscribeCount, "Grace expiry must retire the parked replay")
            assertFalse(active.diagnostics.value.consumerDrainTimedOut)

            dispatcher.allowReturn.countDown()
            assertSame(SubscriptionCloseResult.CLEANUP_FAILED, closing.get(5, TimeUnit.SECONDS))
            runCurrent()
            assertTrue(seeking.await() is SubscriptionSeekResult.Refused)
            assertEquals(1, received.size, "The replay packet was skipped")
            assertTrue(received.single() is SubscriptionEvent.Started)
            assertFalse(active.diagnostics.value.consumerDrainTimedOut)
            assertSame(SubscriptionTerminalReason.Closed, (active.state.value as SubscriptionState.Terminal).reason)
            assertFalse(dispatcher.timedOut.get(), "The notifier must be explicitly released")
        } finally {
            dispatcher.allowPosting.countDown()
            dispatcher.allowReturn.countDown()
            refuse.complete(Unit)
            manager.closeAndJoin()
            closer.shutdown()
            assertTrue(closer.awaitTermination(5, TimeUnit.SECONDS), "The closer must finish")
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `boundary delivery awaits its state while another notifier is active`(restart: Boolean) = runTest {
        val connection = RecordingSubscriptionConnection()
        val acknowledge = CompletableDeferred<Unit>()
        connection.subscribeAction = {
            acknowledge.await()
            successfulConfirmation(timeshiftPeriodSeconds = 300L)
        }
        val initialTracksApplied = CompletableFuture<Unit>()
        val orderedConnection = object : SubscriptionConnection by connection {
            override fun events(id: SubscriptionId): Flow<SubscriptionEvent> = flow {
                connection.events(id).collect { event ->
                    emit(event)
                    if (event is SubscriptionEvent.Started) initialTracksApplied.complete(Unit)
                }
            }
        }
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        val manager = createSubscriptionManager(orderedConnection, dispatcher)
        manager.startAdmission()
        val exposed = CompletableFuture<ActiveSubscription>()
        val releaseNotifier = CountDownLatch(1)
        val notifierTimedOut = AtomicBoolean()
        val observed = CompletableFuture<SubscriptionState>()
        var active: ActiveSubscription? = null
        try {
            val opening = async(Dispatchers.Unconfined) {
                val result = manager.open(SubscriptionChannelId(1L), SubscriptionEventConsumer { event ->
                    val subscription = active
                    if (subscription != null &&
                        (event is SubscriptionEvent.Stopped || event is SubscriptionEvent.Started)
                    ) {
                        observed.complete(subscription.state.value)
                    }
                }) as SubscriptionOpenResult.Opened
                // This continuation runs inside openCompletion.complete on the notifying thread.
                exposed.complete(result.subscription)
                notifierTimedOut.set(!releaseNotifier.await(5, TimeUnit.SECONDS))
            }
            connection.emit(started(stream()))
            initialTracksApplied.get(5, TimeUnit.SECONDS)
            // Let the owner publish the open result on a real thread, then pin that drainer.
            acknowledge.complete(Unit)
            active = exposed.get(5, TimeUnit.SECONDS)
            assertTrue(active.state.value is SubscriptionState.Playable)

            // One worker is pinned in the notifier. The other processes the collection resumption
            // before this FIFO executor sentinel, proving delivery ran up to its next suspension.
            connection.emit(if (restart) started(stream()) else SubscriptionEvent.Stopped(SubscriptionCondition.NO_DETAIL))
            executor.submit {}.get(5, TimeUnit.SECONDS)
            releaseNotifier.countDown()
            assertSame(SubscriptionState.Starting, observed.get(5, TimeUnit.SECONDS),
                "Boundary delivery must observe its committed state")
            opening.await()
            assertFalse(notifierTimedOut.get(), "The notifier must be explicitly released")
        } finally {
            releaseNotifier.countDown()
            manager.closeAndJoin()
            dispatcher.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "The notifier must finish")
        }
    }

    @ParameterizedTest
    @EnumSource(Delivery::class)
    fun `teardown cancellation handlers run outside the subscription monitor`(delivery: Delivery) = runTest {
        MonitorProbe().use { probe ->
            val connection = RecordingSubscriptionConnection()
            val manager = manager(connection)
            val releaseSkip = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val handlerRan = AtomicBoolean()
            val monitorAccessible = AtomicBoolean()
            lateinit var active: ActiveSubscription
            try {
                active = open(manager, connection) { event ->
                    if (event is SubscriptionEvent.Packet) {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            continuation.invokeOnCancellation {
                                handlerRan.set(true)
                                monitorAccessible.set(probe.read { active.grantedTimeshiftPeriod })
                            }
                            entered.complete(Unit)
                        }
                    }
                }
                if (delivery == Delivery.FAILED_UNSUBSCRIBE) {
                    connection.unsubscribeAction = { SubscriptionOperationResult.Timeout }
                }
                if (delivery == Delivery.REPLAY) {
                    connection.skipAction = {
                        releaseSkip.await()
                        SubscriptionOperationResult.AccessDenied
                    }
                    val seeking = async { active.seek(SubscriptionSeekTarget.Absolute(30.seconds)) }
                    runCurrent()
                    connection.emit(packet())
                    runCurrent()
                    assertFalse(entered.isCompleted)
                    releaseSkip.complete(Unit)
                    runCurrent()
                    assertTrue(seeking.await() is SubscriptionSeekResult.Refused)
                } else {
                    connection.emit(packet())
                    runCurrent()
                }
                assertTrue(entered.isCompleted)
                val closing = async { active.close() }
                runCurrent()
                advanceTimeBy(5.seconds)
                runCurrent()

                assertTrue(closing.isCompleted, "The bounded handler must let close finish")
                assertSame(SubscriptionCloseResult.CLEANUP_FAILED, closing.await())
                assertTrue(handlerRan.get())
                assertTrue(monitorAccessible.get(), "Cancellation handlers must not hold the subscription monitor")
                assertTrue(active.diagnostics.value.consumerDrainTimedOut)
                assertSame(SubscriptionTerminalReason.Closed, (active.state.value as SubscriptionState.Terminal).reason)
            } finally {
                releaseSkip.complete(Unit)
                manager.closeAndJoin()
            }
        }
    }

    @Test
    fun `unconfined open caller resumes outside subscription manager and generation monitors`() = runTest {
        MonitorProbe().use { probe ->
            val connection = RecordingSubscriptionConnection()
            val generationMonitor = Any()
            val guardedConnection = object : SubscriptionConnection by connection {
                override fun <T> commitIfLive(block: () -> T): T = synchronized(generationMonitor) { block() }
            }
            val manager = manager(guardedConnection)
            try {
                val opening = async(Dispatchers.Unconfined) {
                    val result = manager.open(SubscriptionChannelId(1L), SubscriptionEventConsumer {})
                    val active = (result as SubscriptionOpenResult.Opened).subscription
                    assertTrue(active.state.value is SubscriptionState.Playable)
                    probe.read {
                        active.grantedTimeshiftPeriod
                        manager.startAdmission()
                        synchronized(generationMonitor) { Unit }
                    }
                }
                runCurrent()
                connection.emit(started(stream()))
                runCurrent()
                assertTrue(opening.await(), "Open must resume callers outside every commit monitor")
            } finally {
                manager.closeAndJoin()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `unconfined seek caller resumes outside the subscription monitor`(invalidated: Boolean) = runTest {
        MonitorProbe().use { probe ->
            val connection = RecordingSubscriptionConnection()
            val manager = manager(connection)
            try {
                val active = open(manager, connection) {}
                val seeking = async(Dispatchers.Unconfined) {
                    val result = active.seek(SubscriptionSeekTarget.Absolute(30.seconds))
                    if (invalidated) assertTrue(active.state.value is SubscriptionState.Terminal)
                    result to probe.read { active.grantedTimeshiftPeriod }
                }
                runCurrent()
                connection.emit(skipped(if (invalidated) SkipOutcome.UNKNOWN else SkipOutcome.ACCEPTED))
                runCurrent()
                val (result, accessible) = seeking.await()
                if (invalidated) assertTrue(result is SubscriptionSeekResult.Invalidated)
                else assertSame(SubscriptionSeekResult.Accepted, result)
                assertTrue(accessible, "Seek outcomes must resume callers outside the monitor")
            } finally {
                manager.closeAndJoin()
            }
        }
    }

    @Test
    fun `unconfined state observer resumes outside the subscription monitor`() = runTest {
        MonitorProbe().use { probe ->
            val connection = RecordingSubscriptionConnection()
            val manager = manager(connection)
            try {
                val active = open(manager, connection) {}
                val reads = ArrayList<Boolean>()
                val observation = launch(Dispatchers.Unconfined) {
                    active.state.drop(1).take(3).collect {
                        reads += probe.read { active.grantedTimeshiftPeriod }
                    }
                }
                connection.emit(SubscriptionEvent.Stopped(SubscriptionCondition.NO_DETAIL))
                runCurrent()
                connection.emit(started(stream()))
                runCurrent()
                active.close()
                observation.join()
                assertEquals(listOf(true, true, true), reads, "State publication must not hold the monitor")
            } finally {
                manager.closeAndJoin()
            }
        }
    }

    private fun TestScope.manager(connection: SubscriptionConnection): SubscriptionManager =
        createSubscriptionManager(connection, StandardTestDispatcher(testScheduler)).also { it.startAdmission() }

    private suspend fun TestScope.open(
        manager: SubscriptionManager,
        connection: RecordingSubscriptionConnection,
        onEvent: suspend (SubscriptionEvent) -> Unit,
    ): ActiveSubscription {
        connection.subscribeAction = { successfulConfirmation(timeshiftPeriodSeconds = 300L) }
        val opening = async {
            manager.open(SubscriptionChannelId(1L), SubscriptionEventConsumer { onEvent(it) }, 300.seconds)
        }
        runCurrent()
        connection.emit(started(stream()))
        runCurrent()
        return (opening.await() as SubscriptionOpenResult.Opened).subscription
    }
}

/** Pauses one foreign notification's dispatch while keeping all coroutine delays on virtual time. */
@OptIn(InternalCoroutinesApi::class)
private class PausedNotificationDispatcher(private val delegate: TestDispatcher) : CoroutineDispatcher(), Delay by delegate {
    val pauseNextDispatch = AtomicBoolean()
    val intercepted = CountDownLatch(1)
    val allowPosting = CountDownLatch(1)
    val posted = CountDownLatch(1)
    val allowReturn = CountDownLatch(1)
    val timedOut = AtomicBoolean()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (pauseNextDispatch.compareAndSet(true, false)) {
            intercepted.countDown()
            if (!allowPosting.await(5, TimeUnit.SECONDS)) timedOut.set(true)
            delegate.dispatch(context, block)
            posted.countDown()
            if (!allowReturn.await(5, TimeUnit.SECONDS)) timedOut.set(true)
        } else {
            delegate.dispatch(context, block)
        }
    }
}

/** A bounded real-thread probe: a regressed monitor hold fails an assertion instead of hanging the test. */
private class MonitorProbe : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()

    fun read(block: () -> Unit): Boolean = try {
        executor.submit(block).get(2, TimeUnit.SECONDS)
        true
    } catch (_: TimeoutException) {
        false
    }

    override fun close() {
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "The monitor probe must finish")
    }
}
