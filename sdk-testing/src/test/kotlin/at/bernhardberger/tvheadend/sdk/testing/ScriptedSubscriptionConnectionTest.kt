@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.playback.LiveSubscriptionPriority
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionCondition
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTermination
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class ScriptedSubscriptionConnectionTest {
    @Test
    fun `subscribe observations change only after cancellation and collector checks`() = runTest {
        val profileUuid = "0123456789abcdef0123456789abcdef"
        val connection = ScriptedSubscriptionConnection()
        val id = SubscriptionId(0L)
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            connection.subscribe(
                id,
                SubscriptionChannelId(4L),
                SubscriptionOptions(profileUuid, 120.seconds),
            )
        }
        cancelled.join()
        assertTrue(cancelled.isCancelled)
        assertNull(connection.requestedStreamProfileUuid)
        assertEquals(0, connection.subscribeCount)

        val failure = try {
            connection.subscribe(
                id,
                SubscriptionChannelId(4L),
                SubscriptionOptions(profileUuid, 120.seconds),
            )
            null
        } catch (exception: IllegalStateException) {
            exception
        }
        assertEquals("Collector must register before subscribe", failure?.message)
        assertNull(connection.requestedStreamProfileUuid)
        assertEquals(0, connection.subscribeCount)

        val collected = async { connection.events(id).toList() }
        connection.awaitCollectionRegistered()
        connection.subscribe(
            id,
            SubscriptionChannelId(4L),
            SubscriptionOptions(profileUuid, 120.seconds),
        )
        connection.subscribe(id, SubscriptionChannelId(4L), 60.seconds)
        assertNull(connection.requestedStreamProfileUuid)
        assertEquals(60L, connection.requestedTimeshiftSeconds)
        assertEquals(2, connection.subscribeCount)
        connection.unsubscribe(id)
        collected.await()
    }

    @Test
    fun `scripted connection preserves registration command event and completion order`() = runTest {
        val connection = ScriptedSubscriptionConnection()
        val id = SubscriptionId(0L)
        val collected = async { connection.events(id).toList() }

        connection.awaitCollectionRegistered()
        val subscribe = connection.subscribe(
            id,
            SubscriptionChannelId(4L),
            SubscriptionOptions(
                "0123456789abcdef0123456789abcdef",
                120.seconds,
                LiveSubscriptionPriority.YIELD,
            ),
        )
        val status = SubscriptionEvent.Status(SubscriptionCondition.STATUS_REPORTED)
        connection.emit(status)
        val skip = connection.skip(id, SubscriptionSeekTarget.Absolute(30.seconds))
        val speed = connection.speed(id, 0)
        connection.scriptPriority(SubscriptionOperationResult.Failed(TvheadendFailure.NOT_SUPPORTED))
        val priority = connection.changePriority(id, LiveSubscriptionPriority.NORMAL)
        val unsubscribe = connection.unsubscribe(id)

        assertTrue(subscribe is SubscriptionOperationResult.Ok)
        assertTrue(skip is SubscriptionOperationResult.Ok)
        assertTrue(speed is SubscriptionOperationResult.Ok)
        assertEquals(SubscriptionOperationResult.Failed(TvheadendFailure.NOT_SUPPORTED), priority)
        assertTrue(unsubscribe is SubscriptionOperationResult.Ok)
        assertEquals(listOf(status), collected.await())
        assertEquals(LiveSubscriptionPriority.YIELD, connection.requestedPriority)
        assertEquals(listOf(LiveSubscriptionPriority.NORMAL), connection.priorityChanges)
        assertEquals(
            listOf(
                ScriptedSubscriptionCall.COLLECTION_REGISTERED,
                ScriptedSubscriptionCall.SUBSCRIBE,
                ScriptedSubscriptionCall.SKIP,
                ScriptedSubscriptionCall.SPEED,
                ScriptedSubscriptionCall.PRIORITY,
                ScriptedSubscriptionCall.UNSUBSCRIBE,
            ),
            connection.calls,
        )
        assertEquals(1, connection.subscribeCount)
        assertEquals(1, connection.unsubscribeCount)
        assertEquals(120L, connection.requestedTimeshiftSeconds)
        assertEquals(
            "0123456789abcdef0123456789abcdef",
            connection.requestedStreamProfileUuid,
        )
        assertEquals(
            30.seconds,
            (connection.seekTargets.single() as SubscriptionSeekTarget.Absolute).position,
        )
        assertEquals(listOf(0), connection.speeds)
        assertTrue(connection.toString().contains("<redacted>"))
    }

    @Test
    fun `consumed identifiers and call snapshots remain immutable for the generation`() = runTest {
        val connection = ScriptedSubscriptionConnection()
        val id = SubscriptionId(9L)
        val first = async { connection.events(id).toList() }
        connection.awaitCollectionRegistered()
        connection.unsubscribe(id)
        first.await()

        val failure = try {
            connection.events(id).toList()
            null
        } catch (exception: IllegalStateException) {
            exception
        }
        assertTrue(failure != null)
        assertEquals("Subscription stream was already consumed", failure?.message)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (connection.calls as MutableList<ScriptedSubscriptionCall>).add(
                ScriptedSubscriptionCall.LIVE_COMMIT,
            )
        }
    }

    @Test
    fun `opaque registrations route concurrent streams independently`() = runTest {
        val connection = ScriptedSubscriptionConnection()
        val firstId = SubscriptionId(1L)
        val secondId = SubscriptionId(2L)
        val first = async { connection.events(firstId).toList() }
        val firstRegistration = connection.awaitCollectionRegistered()
        val second = async { connection.events(secondId).toList() }
        val secondRegistration = connection.awaitCollectionRegistered()
        val firstStatus = SubscriptionEvent.Status(SubscriptionCondition.STATUS_REPORTED)
        val secondStatus = SubscriptionEvent.Status(SubscriptionCondition.ERROR_REPORTED)

        connection.emit(firstRegistration, firstStatus)
        connection.emit(secondRegistration, secondStatus)
        connection.unsubscribe(firstId)
        connection.unsubscribe(secondId)

        assertEquals(listOf(firstStatus), first.await())
        assertEquals(listOf(secondStatus), second.await())
        assertEquals(
            "ScriptedSubscriptionRegistration(<redacted>)",
            firstRegistration.toString(),
        )
        assertEquals(
            "ScriptedSubscriptionRegistration(<redacted>)",
            secondRegistration.toString(),
        )
    }

    @Test
    fun `registrations are connection bound and generation loss terminates streams`() = runTest {
        val firstConnection = ScriptedSubscriptionConnection()
        val secondConnection = ScriptedSubscriptionConnection()
        val first = async { firstConnection.events(SubscriptionId(0L)).toList() }
        val firstRegistration = firstConnection.awaitCollectionRegistered()
        val second = async { secondConnection.events(SubscriptionId(0L)).toList() }
        secondConnection.awaitCollectionRegistered()

        val failure = try {
            secondConnection.emit(
                firstRegistration,
                SubscriptionEvent.Status(SubscriptionCondition.STATUS_REPORTED),
            )
            null
        } catch (exception: IllegalStateException) {
            exception
        }
        assertEquals("Subscription registration belongs to another connection", failure?.message)

        firstConnection.loseGeneration()
        secondConnection.loseGeneration()
        assertTrue(first.await().single() is SubscriptionEvent.Terminated)
        assertTrue(second.await().single() is SubscriptionEvent.Terminated)
    }

    @Test
    fun `scripting completed streams is a no-op`() = runTest {
        val connection = ScriptedSubscriptionConnection()
        val id = SubscriptionId(0L)
        val collected = async { connection.events(id).toList() }
        val registration = connection.awaitCollectionRegistered()
        val status = SubscriptionEvent.Status(SubscriptionCondition.STATUS_REPORTED)

        assertTrue(connection.unsubscribe(id) is SubscriptionOperationResult.Ok)
        connection.emit(registration, status)
        connection.emit(status)
        connection.loseGeneration()
        assertEquals(emptyList<SubscriptionEvent>(), collected.await())

        connection.emit(registration, status)
        connection.emit(status)
    }

    @Test
    fun `lost generation fences commands and terminates late collectors`() = runTest {
        val connection = ScriptedSubscriptionConnection()
        val id = SubscriptionId(0L)
        val collected = async { connection.events(id).toList() }
        connection.awaitCollectionRegistered()

        connection.loseGeneration()
        connection.setLive(true)
        val unavailable = SubscriptionOperationResult.Failed(TvheadendFailure.TRANSPORT_UNAVAILABLE)
        assertEquals(unavailable, connection.subscribe(id, SubscriptionChannelId(4L), 60.seconds))
        assertEquals(unavailable, connection.skip(id, SubscriptionSeekTarget.Absolute(30.seconds)))
        assertEquals(unavailable, connection.speed(id, 0))
        assertEquals(unavailable, connection.changePriority(id, LiveSubscriptionPriority.YIELD))
        assertEquals(unavailable, connection.unsubscribe(id))
        assertNull(connection.commitIfLive { Unit })
        val terminal = collected.await().single() as SubscriptionEvent.Terminated
        assertEquals(SubscriptionTermination.GENERATION_LOST, terminal.reason)

        listOf(id, SubscriptionId(1L)).forEach { lateId ->
            val late = connection.events(lateId).toList().single() as SubscriptionEvent.Terminated
            assertEquals(SubscriptionTermination.GENERATION_LOST, late.reason)
        }
        assertNull(connection.requestedTimeshiftSeconds)
        assertTrue(connection.seekTargets.isEmpty())
        assertTrue(connection.speeds.isEmpty())
        assertTrue(connection.priorityChanges.isEmpty())
        assertEquals(
            listOf(
                ScriptedSubscriptionCall.COLLECTION_REGISTERED,
                ScriptedSubscriptionCall.SUBSCRIBE,
                ScriptedSubscriptionCall.SKIP,
                ScriptedSubscriptionCall.SPEED,
                ScriptedSubscriptionCall.PRIORITY,
                ScriptedSubscriptionCall.UNSUBSCRIBE,
                ScriptedSubscriptionCall.LIVE_COMMIT,
            ),
            connection.calls,
        )
    }

    @Test
    fun `generation loss terminates every inline-resumed collector`() = runTest {
        val connection = ScriptedSubscriptionConnection()
        val unconfined = UnconfinedTestDispatcher(testScheduler)
        val collected = listOf(SubscriptionId(1L), SubscriptionId(2L)).map { id ->
            async(unconfined) { connection.events(id).toList() }.also { connection.awaitCollectionRegistered() }
        }

        connection.loseGeneration()

        collected.forEach { stream ->
            val terminal = stream.await().single() as SubscriptionEvent.Terminated
            assertEquals(SubscriptionTermination.GENERATION_LOST, terminal.reason)
        }
    }

    @Test
    fun `broadcast reaches every collector when one completes inline`() = runTest {
        val connection = ScriptedSubscriptionConnection()
        val unconfined = UnconfinedTestDispatcher(testScheduler)
        val collected = listOf(SubscriptionId(1L), SubscriptionId(2L)).map { id ->
            async(unconfined) { connection.events(id).take(1).toList() }.also { connection.awaitCollectionRegistered() }
        }
        val status = SubscriptionEvent.Status(SubscriptionCondition.STATUS_REPORTED)

        connection.emit(status)

        collected.forEach { stream -> assertEquals(listOf(status), stream.await()) }
    }

    @Test
    fun `binary fixture is defensive bounded and redacted`() {
        val source = byteArrayOf(1, 2, 3)
        val binary = SubscriptionBinaryFixture(source)
        source.fill(9)
        val destination = byteArrayOf(0, 0, 0)

        assertEquals(2, binary.copyInto(destination, 1))
        assertArrayEquals(byteArrayOf(0, 1, 2), destination)
        assertEquals(1, binary.copyCount)
        assertEquals(3, binary.size)
        assertTrue(binary.toString().contains("<redacted>"))
    }
}
