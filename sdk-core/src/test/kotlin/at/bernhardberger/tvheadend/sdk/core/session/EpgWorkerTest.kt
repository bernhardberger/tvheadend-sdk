package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EpgCoverage
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
internal class EpgWorkerTest {
    @Test
    fun `defaults and target policy use known horizon for warmup and steady advancement`() {
        val settings = EpgWorkerSettings()
        val now = instant(1_000)
        val channelId = ChannelId(1)

        assertEquals(4.hours, settings.warmupHorizon)
        assertEquals(20.hours, settings.steadyMinimum)
        assertEquals(24.hours, settings.steadyMaximum)
        assertEquals(4.hours, settings.queryChunk)
        assertEquals(10.minutes, settings.channelCooldown)
        assertEquals(250.milliseconds, settings.requestSpacing)
        assertEquals(6, settings.batchSize)
        assertEquals(6.hours, settings.retainPast)
        assertEquals(24.hours, settings.retainFuture)
        assertEquals(now + 4.hours, epgQueryTarget(EpgCoverage.empty(channelId), now, settings))
        assertEquals(
            now + 8.hours,
            epgQueryTarget(EpgCoverage.empty(channelId, now + 4.hours), now, settings),
        )
        assertEquals(
            now + 14.hours,
            epgQueryTarget(EpgCoverage.empty(channelId, now + 10.hours), now, settings),
        )
        assertNull(epgQueryTarget(EpgCoverage.empty(channelId, now + 20.hours), now, settings))
        assertTrue(
            isEpgWarm(
                EpgSnapshot.create(coverages = listOf(EpgCoverage.empty(channelId, now + 4.hours))),
                now,
                settings,
            ),
        )
        assertFalse(
            isEpgWarm(
                EpgSnapshot.create(coverages = listOf(EpgCoverage.empty(channelId, now + 4.hours - 1.milliseconds))),
                now,
                settings,
            ),
        )

        val capped = settings.copy(queryChunk = 10.hours)
        assertEquals(
            now + 24.hours,
            epgQueryTarget(EpgCoverage.empty(channelId, now + 19.hours), now, capped),
        )
        assertThrows(IllegalArgumentException::class.java) {
            settings.copy(batchSize = 0)
        }
    }

    @Test
    fun `planning sorts actual coverage excludes busy channels and limits the batch`() {
        val now = instant(1_000)
        val settings = EpgWorkerSettings()
        val coverages = (1L..8L).map { id ->
            EpgCoverage.create(
                channelId = ChannelId(id),
                coveredFrom = now,
                coveredTo = now + (9L - id).hours,
            )
        }

        val plans = selectEpgQueries(
            snapshot = EpgSnapshot.create(coverages = coverages),
            now = now,
            settings = settings,
            excludedChannelIds = setOf(ChannelId(8)),
        )

        assertEquals(listOf(7L, 6L, 5L, 4L, 3L, 2L), plans.map { it.channelId.value })
    }

    @Test
    fun `worker staggers six in flight requests and starts the next batch afterward`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..8L)
        val release = CompletableDeferred<Unit>()
        val starts = mutableListOf<Pair<Long, Long>>()
        var active = 0
        var maximumActive = 0
        val worker = EpgWorker(
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            queryEpg = { _, channelId, _ ->
                starts += channelId.value to testScheduler.currentTime
                active += 1
                maximumActive = maxOf(maximumActive, active)
                try {
                    release.await()
                    GatewayResult.Ok(emptyList())
                } finally {
                    active -= 1
                }
            },
        )
        val job = backgroundScope.launch { worker.run(generation) }

        runCurrent()
        advanceTimeBy(1_250.milliseconds)
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), starts.map { it.first })
        assertEquals(listOf(0L, 250L, 500L, 750L, 1_000L, 1_250L), starts.map { it.second })
        assertEquals(6, maximumActive)

        release.complete(Unit)
        runCurrent()
        advanceTimeBy(750.milliseconds)
        runCurrent()

        assertEquals((1L..8L).toList(), starts.map { it.first })
        assertEquals(listOf(1_500L, 1_750L), starts.takeLast(2).map { it.second })
        assertTrue(worker.isWarm)
        job.cancelAndJoin()
    }

    @Test
    fun `failed request retries at cooldown without terminating the worker`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..1L)
        var attempts = 0
        val worker = EpgWorker(
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            queryEpg = { _, _, _ ->
                attempts += 1
                if (attempts == 1) error("private failure")
                GatewayResult.Ok(emptyList())
            },
        )
        val job = backgroundScope.launch { worker.run(generation) }

        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(599_999.milliseconds)
        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(2, attempts)
        advanceTimeBy(250.milliseconds)
        runCurrent()
        assertTrue(worker.isWarm)
        job.cancelAndJoin()
    }

    @Test
    fun `request cancellation terminates the worker instead of retrying`() = runTest {
        val generation = GatewayGeneration()
        val cancellation = CancellationException("fixed cancellation")
        val worker = EpgWorker(
            metadata = synchronizedMetadata(generation, 1L..1L),
            clock = SchedulerClock { testScheduler.currentTime },
            queryEpg = { _, _, _ -> throw cancellation },
        )
        var caught: CancellationException? = null

        val job = backgroundScope.launch {
            try {
                worker.run(generation)
            } catch (failure: CancellationException) {
                caught = failure
            }
        }
        runCurrent()
        job.join()

        assertEquals(cancellation.message, caught?.message)
        assertFalse(worker.isWarm)
    }

    @Test
    fun `startup retention keeps boundary overlap and removes drained events`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..1L) {
            acceptMetadata(MetadataEvent.EventAdded(generation, event(1, 1, -21_601, -21_600)))
            acceptMetadata(MetadataEvent.EventAdded(generation, event(2, 1, -21_602, -21_601)))
        }
        val worker = EpgWorker(
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            queryEpg = { _, _, _ -> GatewayResult.Timeout },
        )
        val job = backgroundScope.launch { worker.run(generation) }

        runCurrent()

        val snapshot = (metadata.epgRepository.state.value as EpgRepositoryState.Current).snapshot
        assertEquals(listOf(1L), snapshot.events.map { it.id.value })
        job.cancelAndJoin()
    }
}

private class SchedulerClock(
    private val currentTimeMillis: () -> Long,
) : Clock {
    override fun now(): Instant = instant(0) + currentTimeMillis().milliseconds
}

private fun synchronizedMetadata(
    generation: GatewayGeneration,
    channelIds: LongRange,
    beforeFence: PhaseOneSessionMetadata.() -> Unit = {},
): PhaseOneSessionMetadata = PhaseOneSessionMetadata().apply {
    bindGeneration(generation)
    channelIds.forEach { id ->
        acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id)))
    }
    beforeFence()
    acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
}

private fun channel(id: Long): GatewayChannelMetadata = GatewayChannelMetadata(
    id = ChannelId(id),
    name = null,
    uuid = null,
    number = null,
    numberMinor = null,
    icon = null,
    currentEventId = null,
    nextEventId = null,
    services = null,
    tagIds = null,
)

private fun event(id: Long, channelId: Long, start: Long, stop: Long): GatewayEpgEvent =
    GatewayEpgEvent(
        id = EventId(id),
        channelId = ChannelId(channelId),
        start = instant(start),
        stop = instant(stop),
    )

private fun instant(seconds: Long): Instant = Instant.fromEpochSeconds(seconds)
