package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EpgCoverage
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageRequestResult
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgUpdate
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
internal class EpgWorkerTest {
    @Test
    fun `defaults and target policy require polling proof before steady advancement`() {
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
        val asyncOnlyCoverage = EpgCoverage.create(
            channelId = channelId,
            coveredFrom = now,
            coveredTo = now + 24.hours,
        )
        assertEquals(now + 4.hours, epgQueryTarget(asyncOnlyCoverage, now, settings))
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
    fun `coverage request distinguishes satisfied accepted ineligible and generation lost`() {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..1L)
        val now = instant(1_000)
        val queriedTo = now + 4.hours
        val query = requireNotNull(metadata.beginEpgQuery(generation, ChannelId(1)))
        metadata.applySuccessfulEpgQuery(
            generation = generation,
            query = query,
            queriedTo = queriedTo,
            events = emptyList(),
        )
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = MutableClock(now),
            queryEpg = { _, _, _ -> GatewayResult.Timeout },
        )

        assertEquals(
            EpgCoverageRequestResult.SATISFIED,
            worker.requestCoverage(ChannelId(1), queriedTo),
        )
        assertEquals(
            EpgCoverageRequestResult.ACCEPTED,
            worker.requestCoverage(ChannelId(1), queriedTo + 1.hours),
        )
        assertEquals(
            EpgCoverageRequestResult.INELIGIBLE,
            worker.requestCoverage(ChannelId(2), queriedTo + 1.hours),
        )
        assertEquals(
            EpgCoverageRequestResult.SATISFIED,
            worker.requestCoverage(ChannelId(1), now),
        )
        assertEquals(
            EpgCoverageRequestResult.INELIGIBLE,
            worker.requestCoverage(ChannelId(1), now + 24.hours + 1.seconds),
        )
        worker.stopAcceptingPriorities()
        assertEquals(
            EpgCoverageRequestResult.GENERATION_LOST,
            worker.requestCoverage(ChannelId(1), queriedTo + 2.hours),
        )

        val staleWorker = EpgWorker(
            generation = GatewayGeneration(),
            metadata = metadata,
            clock = MutableClock(now),
            queryEpg = { _, _, _ -> GatewayResult.Timeout },
        )
        assertEquals(
            EpgCoverageRequestResult.GENERATION_LOST,
            staleWorker.requestCoverage(ChannelId(1), queriedTo + 2.hours),
        )
    }

    @Test
    fun `priority requests promote and deduplicate while reserving ordinary batch work`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..3L)
        val release = CompletableDeferred<Unit>()
        val starts = mutableListOf<EpgQueryPlan>()
        val settings = EpgWorkerSettings(
            requestSpacing = 1.milliseconds,
            channelCooldown = 1.hours,
            batchSize = 2,
        )
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            settings = settings,
            queryEpg = { _, channelId, target ->
                starts += EpgQueryPlan(channelId, target)
                release.await()
                GatewayResult.Timeout
            },
        )

        assertEquals(
            EpgCoverageRequestResult.ACCEPTED,
            worker.requestCoverage(ChannelId(3), instant(0) + 8.hours),
        )
        assertEquals(
            EpgCoverageRequestResult.ACCEPTED,
            worker.requestCoverage(ChannelId(3), instant(0) + 12.hours + 999.milliseconds),
        )
        assertEquals(
            EpgCoverageRequestResult.ACCEPTED,
            worker.requestCoverage(ChannelId(2), instant(0) + 10.hours),
        )
        val job = backgroundScope.launch { worker.run() }

        runCurrent()
        assertEquals(ChannelId(3), starts.single().channelId)
        assertEquals(instant(0) + 12.hours, starts.single().target)
        assertEquals(
            EpgCoverageRequestResult.ACCEPTED,
            worker.requestCoverage(ChannelId(3), instant(0) + 11.hours),
        )
        advanceTimeBy(1.milliseconds)
        runCurrent()

        assertEquals(listOf(3L, 1L), starts.map { plan -> plan.channelId.value })
        assertEquals(1, starts.count { plan -> plan.channelId == ChannelId(3) })
        release.complete(Unit)
        job.cancelAndJoin()
    }

    @Test
    fun `single slot batches alternate priority and ordinary work`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..3L)
        val starts = mutableListOf<ChannelId>()
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            settings = EpgWorkerSettings(
                requestSpacing = 1.milliseconds,
                channelCooldown = 1.hours,
                batchSize = 1,
            ),
            queryEpg = { _, channelId, _ ->
                starts += channelId
                GatewayResult.Timeout
            },
        )
        assertEquals(
            EpgCoverageRequestResult.ACCEPTED,
            worker.requestCoverage(ChannelId(3), instant(0) + 8.hours),
        )
        assertEquals(
            EpgCoverageRequestResult.ACCEPTED,
            worker.requestCoverage(ChannelId(2), instant(0) + 8.hours),
        )
        val job = backgroundScope.launch { worker.run() }

        runCurrent()
        advanceTimeBy(1.milliseconds)
        runCurrent()
        advanceTimeBy(1.milliseconds)
        runCurrent()

        assertEquals(listOf(ChannelId(3), ChannelId(1), ChannelId(2)), starts)
        job.cancelAndJoin()
    }

    @Test
    fun `cooling priority retains one promoted hint until the channel is eligible`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..1L)
        val targets = mutableListOf<Instant>()
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            settings = EpgWorkerSettings(
                requestSpacing = 1.milliseconds,
                channelCooldown = 1.seconds,
            ),
            queryEpg = { _, _, target ->
                targets += target
                if (targets.size == 1) GatewayResult.Timeout else GatewayResult.Ok(emptyList())
            },
        )
        assertEquals(
            EpgCoverageRequestResult.ACCEPTED,
            worker.requestCoverage(ChannelId(1), instant(0) + 8.hours),
        )
        val job = backgroundScope.launch { worker.run() }
        runCurrent()
        assertEquals(listOf(instant(0) + 8.hours), targets)

        assertEquals(
            EpgCoverageRequestResult.ACCEPTED,
            worker.requestCoverage(ChannelId(1), instant(0) + 12.hours),
        )
        advanceTimeBy(999.milliseconds)
        runCurrent()
        assertEquals(1, targets.size)
        advanceTimeBy(1.milliseconds)
        runCurrent()

        assertEquals(listOf(instant(0) + 8.hours, instant(0) + 12.hours), targets)
        job.cancelAndJoin()
    }

    @Test
    fun `successful empty priority query advances coverage and denial becomes ineligible`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..2L)
        val target = instant(0) + 12.hours
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            queryEpg = { _, channelId, _ ->
                if (channelId == ChannelId(1)) GatewayResult.Ok(emptyList())
                else GatewayResult.NotSupported
            },
        )
        assertEquals(
            EpgCoverageRequestResult.ACCEPTED,
            worker.requestCoverage(ChannelId(1), target),
        )
        assertEquals(
            EpgCoverageRequestResult.ACCEPTED,
            worker.requestCoverage(ChannelId(2), target),
        )
        val job = backgroundScope.launch { worker.run() }

        runCurrent()
        advanceTimeBy(250.milliseconds)
        runCurrent()

        val snapshot = (metadata.observation.value.epgState as EpgRepositoryState.Current).snapshot
        assertEquals(target, snapshot.coverages.single { it.channelId == ChannelId(1) }.queriedTo)
        assertEquals(
            EpgCoverageRequestResult.SATISFIED,
            worker.requestCoverage(ChannelId(1), target),
        )
        assertEquals(
            EpgCoverageRequestResult.INELIGIBLE,
            worker.requestCoverage(ChannelId(2), target),
        )
        job.cancelAndJoin()
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
            generation = generation,
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
        val job = backgroundScope.launch { worker.run() }

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
        job.cancelAndJoin()
    }

    @Test
    fun `async delete after query dispatch prevents stale query resurrection`() = runTest {
        val generation = GatewayGeneration()
        val dispatched = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val metadata = synchronizedMetadata(generation, 1L..1L) {
            acceptMetadata(MetadataEvent.EventAdded(generation, event(1, 1, 10, 20)))
        }
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            queryEpg = { _, _, _ ->
                dispatched.complete(Unit)
                release.await()
                GatewayResult.Ok(listOf(queryEvent(1, 1, 10, 20, title = "stale")))
            },
        )
        val job = backgroundScope.launch { worker.run() }

        runCurrent()
        assertTrue(dispatched.isCompleted)
        metadata.acceptMetadata(MetadataEvent.EventDeleted(generation, EventId(1)))
        release.complete(Unit)
        runCurrent()

        val snapshot = (metadata.observation.value.epgState as EpgRepositoryState.Current).snapshot
        assertEquals(emptyList<Any>(), snapshot.events)
        assertTrue(snapshot.coverages.single().queriedTo != null)
        job.cancelAndJoin()
    }

    @Test
    fun `async update after query dispatch remains authoritative over stale query fields`() = runTest {
        val generation = GatewayGeneration()
        val dispatched = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val metadata = synchronizedMetadata(generation, 1L..1L) {
            acceptMetadata(
                MetadataEvent.EventAdded(
                    generation,
                    timedEvent(1, 1, instant(10), instant(20), title = "old"),
                ),
            )
        }
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            queryEpg = { _, _, _ ->
                dispatched.complete(Unit)
                release.await()
                GatewayResult.Ok(listOf(queryEvent(1, 1, 10, 20, title = "old")))
            },
        )
        val job = backgroundScope.launch { worker.run() }

        runCurrent()
        assertTrue(dispatched.isCompleted)
        metadata.acceptMetadata(
            MetadataEvent.EventUpdated(
                generation,
                GatewayEpgUpdate(id = EventId(1), title = "new"),
            ),
        )
        release.complete(Unit)
        runCurrent()

        val snapshot = (metadata.observation.value.epgState as EpgRepositoryState.Current).snapshot
        assertEquals("new", snapshot.events.single().title)
        assertTrue(snapshot.coverages.single().queriedTo != null)
        job.cancelAndJoin()
    }

    @Test
    fun `failed request retries at cooldown without terminating the worker`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..1L)
        var attempts = 0
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            queryEpg = { _, _, _ ->
                attempts += 1
                if (attempts == 1) error("private failure")
                GatewayResult.Ok(emptyList())
            },
        )
        val job = backgroundScope.launch { worker.run() }

        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(250.milliseconds)
        runCurrent()
        advanceTimeBy(599_749.milliseconds)
        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(2, attempts)
        job.cancelAndJoin()
    }

    @Test
    fun `persistent timeout still retries after cooldown`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..1L)
        var attempts = 0
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            queryEpg = { _, _, _ ->
                attempts += 1
                GatewayResult.Timeout
            },
        )
        val job = backgroundScope.launch { worker.run() }

        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(250.milliseconds)
        runCurrent()
        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(2, attempts)
        job.cancelAndJoin()
    }

    @Test
    fun `unsupported queries become ineligible and are not retried`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..1L)
        var attempts = 0
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            queryEpg = { _, _, _ ->
                attempts += 1
                GatewayResult.NotSupported
            },
        )
        val job = backgroundScope.launch { worker.run() }

        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(250.milliseconds)
        runCurrent()
        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(1, attempts)
        job.cancelAndJoin()
    }

    @Test
    fun `independent request cancellation remains a channel local failure`() = runTest {
        val generation = GatewayGeneration()
        val cancellation = CancellationException("fixed cancellation")
        var attempts = 0
        val worker = EpgWorker(
            generation = generation,
            metadata = synchronizedMetadata(generation, 1L..1L),
            clock = SchedulerClock { testScheduler.currentTime },
            settings = EpgWorkerSettings(
                requestSpacing = 1.milliseconds,
                channelCooldown = 1.seconds,
            ),
            queryEpg = { _, _, _ ->
                attempts += 1
                if (attempts == 1) throw cancellation
                GatewayResult.Ok(emptyList())
            },
        )
        val job = backgroundScope.launch { worker.run() }
        runCurrent()
        assertEquals(1, attempts)
        assertTrue(job.isActive)
        assertEquals(
            EpgCoverageRequestResult.ACCEPTED,
            worker.requestCoverage(ChannelId(1), instant(0) + 8.hours),
        )
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(2, attempts)
        assertTrue(job.isActive)
        job.cancelAndJoin()
    }

    @Test
    fun `startup retention keeps boundary overlap and removes drained events`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..1L) {
            acceptMetadata(MetadataEvent.EventAdded(generation, event(1, 1, -21_601, -21_600)))
            acceptMetadata(MetadataEvent.EventAdded(generation, event(2, 1, -21_602, -21_601)))
        }
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            queryEpg = { _, _, _ -> GatewayResult.Timeout },
        )
        val job = backgroundScope.launch { worker.run() }

        runCurrent()

        val snapshot = (metadata.observation.value.epgState as EpgRepositoryState.Current).snapshot
        assertEquals(listOf(1L), snapshot.events.map { it.id.value })
        job.cancelAndJoin()
    }

    @Test
    fun `clock jump drains actual coverage without clearing queried horizon`() = runTest {
        val generation = GatewayGeneration()
        val origin = Instant.fromEpochSeconds(1_000_000)
        val clock = MutableClock(origin)
        val metadata = synchronizedMetadata(generation, 1L..2L) {
            acceptMetadata(
                MetadataEvent.EventAdded(
                    generation,
                    timedEvent(1, 1, origin - 1.hours, origin - 1.seconds),
                ),
            )
            acceptMetadata(
                MetadataEvent.EventAdded(
                    generation,
                    timedEvent(2, 1, origin + 2.hours, origin + 3.hours),
                ),
            )
            acceptMetadata(
                MetadataEvent.EventAdded(
                    generation,
                    timedEvent(3, 2, origin - 2.hours, origin - 1.hours),
                ),
            )
        }
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = clock,
            queryEpg = { _, _, _ -> GatewayResult.Ok(emptyList()) },
        )
        val job = backgroundScope.launch { worker.run() }

        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()
        val warmed = (metadata.observation.value.epgState as EpgRepositoryState.Current).snapshot
        assertEquals(setOf(1L, 2L, 3L), warmed.events.map { it.id.value }.toSet())
        val queried = warmed.coverages.associate { coverage ->
            coverage.channelId to coverage.queriedTo
        }
        assertTrue(queried.values.all { horizon -> horizon != null })

        clock.now = origin + 7.hours
        advanceTimeBy(10.minutes)
        runCurrent()

        val drained = (metadata.observation.value.epgState as EpgRepositoryState.Current).snapshot
        assertEquals(listOf(2L), drained.events.map { it.id.value })
        val retained = drained.coverages.single { coverage -> coverage.channelId == ChannelId(1) }
        val emptied = drained.coverages.single { coverage -> coverage.channelId == ChannelId(2) }
        assertFalse(retained.isEmpty)
        assertTrue(emptied.isEmpty)
        assertTrue(retained.queriedTo != null && retained.queriedTo >= queried.getValue(ChannelId(1))!!)
        assertTrue(emptied.queriedTo != null && emptied.queriedTo >= queried.getValue(ChannelId(2))!!)
        assertEquals(emptied.queriedTo, emptied.knownTo)
        job.cancelAndJoin()
    }
}

private class SchedulerClock(
    private val currentTimeMillis: () -> Long,
) : Clock {
    override fun now(): Instant = instant(0) + currentTimeMillis().milliseconds
}

private class MutableClock(
    var now: Instant,
) : Clock {
    override fun now(): Instant = now
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
    timedEvent(id, channelId, instant(start), instant(stop))

private fun timedEvent(
    id: Long,
    channelId: Long,
    start: Instant,
    stop: Instant,
    title: String? = null,
): GatewayEpgEvent = GatewayEpgEvent(
    id = EventId(id),
    channelId = ChannelId(channelId),
    start = start,
    stop = stop,
    title = title,
)

private fun queryEvent(
    id: Long,
    channelId: Long,
    start: Long,
    stop: Long,
    title: String? = null,
): GatewayEpgQueryEvent = GatewayEpgQueryEvent(
    id = EventId(id),
    channelId = ChannelId(channelId),
    start = instant(start),
    stop = instant(stop),
    title = title,
)

private fun instant(seconds: Long): Instant = Instant.fromEpochSeconds(seconds)
