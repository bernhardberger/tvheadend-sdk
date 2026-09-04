package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation
import at.bernhardberger.tvheadend.sdk.core.EpgCoverage
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageAcquisitionResult
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageBatchSettlement
import at.bernhardberger.tvheadend.sdk.core.EpgCoveragePolicy
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionState
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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
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
        assertEquals(24.hours, settings.coveragePolicy.futureHorizon)
        assertEquals(4.hours, settings.queryChunk)
        assertEquals(10.minutes, settings.channelCooldown)
        assertEquals(250.milliseconds, settings.requestSpacing)
        assertEquals(6, settings.batchSize)
        assertEquals(6.hours, settings.retainPast)
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
        val configured = capped.copy(coveragePolicy = EpgCoveragePolicy.create(7.days))
        assertEquals(
            now + 29.hours,
            epgQueryTarget(EpgCoverage.empty(channelId, now + 19.hours), now, configured),
        )
        assertThrows(IllegalArgumentException::class.java) {
            settings.copy(batchSize = 0)
        }
    }

    @Test
    fun `seven day policy admits its exact horizon and rejects a later target`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..1L)
        val now = instant(1_000)
        val targets = mutableListOf<Instant>()
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = MutableClock(now),
            settings = EpgWorkerSettings(
                coveragePolicy = EpgCoveragePolicy.create(7.days),
                requestSpacing = 1.milliseconds,
            ),
            queryEpg = { _, _, target ->
                targets += target
                GatewayResult.Ok(emptyList())
            },
        )
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val acquisition = backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(1), now + 7.days)
        }
        runCurrent()

        assertFalse(acquisition.isCompleted)
        assertSame(
            EpgCoverageAcquisitionResult.Ineligible,
            worker.acquireCoverage(currentSession, ChannelId(1), now + 7.days + 1.seconds),
        )
        val job = backgroundScope.launch { worker.run() }
        runCurrent()

        assertEquals(listOf(now + 7.days), targets)
        assertTrue(acquisition.await() is EpgCoverageAcquisitionResult.CoveredEmpty)
        job.cancelAndJoin()
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
    fun `coverage acquisition distinguishes settled pending ineligible and expired`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..2L)
        val now = instant(1_000)
        val queriedTo = now + 4.hours
        val query = requireNotNull(metadata.beginEpgQuery(generation, ChannelId(1)))
        metadata.applySuccessfulEpgQuery(
            generation = generation,
            query = query,
            queriedTo = queriedTo,
            events = emptyList(),
        )
        val dataQuery = requireNotNull(metadata.beginEpgQuery(generation, ChannelId(2)))
        metadata.applySuccessfulEpgQuery(
            generation = generation,
            query = dataQuery,
            queriedTo = queriedTo,
            events = listOf(queryEvent(id = 20, channelId = 2, start = 1_100, stop = 1_200)),
        )
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = MutableClock(now),
            queryEpg = { _, _, _ -> GatewayResult.Timeout },
        )
        val currentSession = requireNotNull(metadata.observation.value.currentSession)

        val settled = worker.acquireCoverage(currentSession, ChannelId(1), queriedTo)
        assertTrue(settled is EpgCoverageAcquisitionResult.CoveredEmpty)
        assertSame(
            metadata.observation.value,
            (settled as EpgCoverageAcquisitionResult.CoveredEmpty).observation,
        )
        val coveredWithData = worker.acquireCoverage(currentSession, ChannelId(2), queriedTo)
        assertTrue(coveredWithData is EpgCoverageAcquisitionResult.CoveredWithData)
        assertSame(
            metadata.observation.value,
            (coveredWithData as EpgCoverageAcquisitionResult.CoveredWithData).observation,
        )
        val pending = backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(1), queriedTo + 1.hours)
        }
        runCurrent()
        assertFalse(pending.isCompleted)
        assertSame(
            EpgCoverageAcquisitionResult.Ineligible,
            worker.acquireCoverage(currentSession, ChannelId(3), queriedTo + 1.hours),
        )
        assertTrue(
            worker.acquireCoverage(currentSession, ChannelId(1), now) is
                EpgCoverageAcquisitionResult.CoveredEmpty,
        )
        assertSame(
            EpgCoverageAcquisitionResult.Ineligible,
            worker.acquireCoverage(currentSession, ChannelId(1), now + 24.hours + 1.seconds),
        )
        worker.stopAcceptingPriorities()
        assertSame(EpgCoverageAcquisitionResult.ObservationExpired, pending.await())
        assertSame(
            EpgCoverageAcquisitionResult.ObservationExpired,
            worker.acquireCoverage(currentSession, ChannelId(1), queriedTo + 2.hours),
        )

        val staleWorker = EpgWorker(
            generation = GatewayGeneration(),
            metadata = metadata,
            clock = MutableClock(now),
            queryEpg = { _, _, _ -> GatewayResult.Timeout },
        )
        assertSame(
            EpgCoverageAcquisitionResult.ObservationExpired,
            staleWorker.acquireCoverage(currentSession, ChannelId(1), queriedTo + 2.hours),
        )
    }

    @Test
    fun `caller cancellation removes only its waiter and priority target`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..1L)
        val now = instant(1_000)
        val requestedTargets = mutableListOf<Instant>()
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = MutableClock(now),
            settings = EpgWorkerSettings(
                requestSpacing = 1.milliseconds,
                channelCooldown = 1.hours,
            ),
            queryEpg = { _, _, target ->
                requestedTargets += target
                GatewayResult.Ok(emptyList())
            },
        )
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val retainedTarget = now + 6.hours
        val cancelled = backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(1), now + 8.hours)
        }
        val retained = backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(1), retainedTarget)
        }
        runCurrent()

        cancelled.cancelAndJoin()
        val job = backgroundScope.launch { worker.run() }
        runCurrent()

        assertTrue(cancelled.isCancelled)
        assertEquals(retainedTarget, requestedTargets.first())
        assertTrue(retained.await() is EpgCoverageAcquisitionResult.CoveredEmpty)
        job.cancelAndJoin()
    }

    @Test
    fun `batch deduplicates in order and returns mixed authoritative settlements`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..3L)
        val now = instant(0)
        val target = now + 8.hours
        val coveredQuery = requireNotNull(metadata.beginEpgQuery(generation, ChannelId(1)))
        metadata.applySuccessfulEpgQuery(
            generation = generation,
            query = coveredQuery,
            queriedTo = target,
            events = listOf(queryEvent(id = 10, channelId = 1, start = 1, stop = 2)),
        )
        val authoritativeObservation = metadata.observation.value
        val thirdStarted = CompletableDeferred<Unit>()
        val releaseThird = CompletableDeferred<Unit>()
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            settings = EpgWorkerSettings(requestSpacing = 1.milliseconds, batchSize = 3),
            queryEpg = { _, channelId, _ ->
                when (channelId) {
                    ChannelId(2) -> GatewayResult.NotSupported
                    ChannelId(3) -> {
                        thirdStarted.complete(Unit)
                        releaseThird.await()
                        GatewayResult.Timeout
                    }
                    else -> GatewayResult.Timeout
                }
            },
        )
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val batch = backgroundScope.async {
            worker.acquireCoverageBatch(
                currentSession,
                listOf(ChannelId(1), ChannelId(9), ChannelId(2), ChannelId(1), ChannelId(3)),
                target,
            )
        }
        runCurrent()
        val job = backgroundScope.launch { worker.run() }
        runCurrent()
        advanceTimeBy(1.milliseconds)
        thirdStarted.await()

        metadata.resetWorkingStateRetainingPublishedSnapshot()
        runCurrent()
        releaseThird.complete(Unit)
        val settlements = batch.await().settlements

        assertEquals(listOf(1L, 9L, 2L, 3L), settlements.map { it.channelId.value })
        assertTrue(settlements[0] is EpgCoverageBatchSettlement.CoveredWithData)
        assertTrue(settlements[1] is EpgCoverageBatchSettlement.TargetAbsent)
        assertTrue(settlements[2] is EpgCoverageBatchSettlement.Rejected)
        assertTrue(settlements[3] is EpgCoverageBatchSettlement.ObservationExpired)
        assertSame(
            authoritativeObservation,
            (settlements[0] as EpgCoverageBatchSettlement.CoveredWithData).observation,
        )
        job.cancelAndJoin()
    }

    @Test
    fun `batch cancellation keeps a shared singular waiter and narrows its target`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..1L)
        val now = instant(0)
        val targets = mutableListOf<Instant>()
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            settings = EpgWorkerSettings(requestSpacing = 1.milliseconds),
            queryEpg = { _, _, target ->
                targets += target
                GatewayResult.Ok(emptyList())
            },
        )
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val retainedTarget = now + 6.hours
        val retained = backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(1), retainedTarget)
        }
        val cancelled = backgroundScope.async {
            worker.acquireCoverageBatch(currentSession, listOf(ChannelId(1)), now + 8.hours)
        }
        runCurrent()

        cancelled.cancelAndJoin()
        val job = backgroundScope.launch { worker.run() }
        runCurrent()

        assertTrue(cancelled.isCancelled)
        assertEquals(listOf(retainedTarget), targets)
        assertTrue(retained.await() is EpgCoverageAcquisitionResult.CoveredEmpty)
        job.cancelAndJoin()
    }

    @Test
    fun `batch priorities coalesce by channel while ordinary work keeps a slot`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..3L)
        val release = CompletableDeferred<Unit>()
        val starts = mutableListOf<ChannelId>()
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            settings = EpgWorkerSettings(
                requestSpacing = 1.milliseconds,
                channelCooldown = 1.hours,
                batchSize = 2,
            ),
            queryEpg = { _, channelId, _ ->
                starts += channelId
                release.await()
                GatewayResult.Timeout
            },
        )
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val batch = backgroundScope.async {
            worker.acquireCoverageBatch(
                currentSession,
                listOf(ChannelId(3), ChannelId(3), ChannelId(2)),
                instant(0) + 8.hours,
            )
        }
        val coalesced = backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(3), instant(0) + 10.hours)
        }
        runCurrent()
        val job = backgroundScope.launch { worker.run() }
        runCurrent()
        advanceTimeBy(1.milliseconds)
        runCurrent()

        assertEquals(listOf(ChannelId(3), ChannelId(1)), starts)
        assertEquals(1, starts.count { it == ChannelId(3) })
        batch.cancelAndJoin()
        coalesced.cancelAndJoin()
        release.complete(Unit)
        job.cancelAndJoin()
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
        val currentSession = requireNotNull(metadata.observation.value.currentSession)

        backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(3), instant(0) + 8.hours)
        }
        backgroundScope.async {
            worker.acquireCoverage(
                currentSession,
                ChannelId(3),
                instant(0) + 12.hours + 999.milliseconds,
            )
        }
        backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(2), instant(0) + 10.hours)
        }
        runCurrent()
        val job = backgroundScope.launch { worker.run() }

        runCurrent()
        assertEquals(ChannelId(3), starts.single().channelId)
        assertEquals(instant(0) + 12.hours, starts.single().target)
        backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(3), instant(0) + 11.hours)
        }
        runCurrent()
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
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(3), instant(0) + 8.hours)
        }
        backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(2), instant(0) + 8.hours)
        }
        runCurrent()
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
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(1), instant(0) + 8.hours)
        }
        runCurrent()
        val job = backgroundScope.launch { worker.run() }
        runCurrent()
        assertEquals(listOf(instant(0) + 8.hours), targets)

        backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(1), instant(0) + 12.hours)
        }
        runCurrent()
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
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val covered = backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(1), target)
        }
        val ineligible = backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(2), target)
        }
        runCurrent()
        val job = backgroundScope.launch { worker.run() }

        runCurrent()
        advanceTimeBy(250.milliseconds)
        runCurrent()

        val snapshot = (metadata.observation.value.epgState as EpgRepositoryState.Current).snapshot
        assertEquals(target, snapshot.coverages.single { it.channelId == ChannelId(1) }.queriedTo)
        assertTrue(covered.await() is EpgCoverageAcquisitionResult.CoveredEmpty)
        assertSame(EpgCoverageAcquisitionResult.Ineligible, ineligible.await())
        assertTrue(
            worker.acquireCoverage(currentSession, ChannelId(1), target) is
                EpgCoverageAcquisitionResult.CoveredEmpty,
        )
        assertSame(
            EpgCoverageAcquisitionResult.Ineligible,
            worker.acquireCoverage(currentSession, ChannelId(2), target),
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
    fun `denial and racing latch consumption settle expired after metadata retirement`() = runTest {
        val generation = GatewayGeneration()
        val delegate = synchronizedMetadata(generation, 1L..1L)
        val metadata = TransitioningSessionMetadata(delegate)
        val queryStarted = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<GatewayResult<List<GatewayEpgQueryEvent>>>()
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            queryEpg = { _, _, _ ->
                queryStarted.complete(Unit)
                releaseQuery.await()
            },
        )
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val currentObservation = metadata.observation.value
        val acquisition = backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(1), instant(0) + 8.hours)
        }
        runCurrent()
        val job = backgroundScope.launch { worker.run() }
        queryStarted.await()

        delegate.resetWorkingStateRetainingPublishedSnapshot()
        releaseQuery.complete(GatewayResult.NotSupported)
        runCurrent()

        assertSame(EpgCoverageAcquisitionResult.ObservationExpired, acquisition.await())
        metadata.nextCurrentObservation = currentObservation
        assertSame(
            EpgCoverageAcquisitionResult.ObservationExpired,
            worker.acquireCoverage(currentSession, ChannelId(1), instant(0) + 8.hours),
        )
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
        val metadata = synchronizedMetadata(generation, 1L..1L)
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
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
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val acquisition = backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(1), instant(0) + 8.hours)
        }
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(2, attempts)
        assertTrue(acquisition.await() is EpgCoverageAcquisitionResult.CoveredEmpty)
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
    fun `seven day policy controls the future eviction boundary`() = runTest {
        val generation = GatewayGeneration()
        val now = instant(0)
        val metadata = synchronizedMetadata(generation, 1L..1L) {
            acceptMetadata(
                MetadataEvent.EventAdded(
                    generation,
                    timedEvent(1, 1, now + 6.days, now + 6.days + 1.hours),
                ),
            )
            acceptMetadata(
                MetadataEvent.EventAdded(
                    generation,
                    timedEvent(2, 1, now + 7.days, now + 7.days + 1.hours),
                ),
            )
            acceptMetadata(
                MetadataEvent.EventAdded(
                    generation,
                    timedEvent(3, 1, now + 7.days + 1.seconds, now + 7.days + 1.hours),
                ),
            )
        }
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = MutableClock(now),
            settings = EpgWorkerSettings(
                coveragePolicy = EpgCoveragePolicy.create(7.days),
            ),
            queryEpg = { _, _, _ -> GatewayResult.Timeout },
        )
        val job = backgroundScope.launch { worker.run() }

        runCurrent()

        val snapshot = (metadata.observation.value.epgState as EpgRepositoryState.Current).snapshot
        assertEquals(listOf(1L, 2L), snapshot.events.map { it.id.value })
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
    publishSessionState(
        state = SessionState.Ready(
            ServerCapabilities.create(CapabilityAccess.UNKNOWN, CapabilityAccess.UNKNOWN),
        ),
        progressCapability = RecordingProgressCapability.UNKNOWN,
        generation = generation,
    )
}

private class TransitioningSessionMetadata(
    private val delegate: SessionMetadata,
) : SessionMetadata by delegate {
    internal var nextCurrentObservation: SessionObservation? = null

    override fun currentObservation(
        generation: GatewayGeneration,
        currentSession: CurrentSessionObservation,
    ): SessionObservation? = nextCurrentObservation?.also {
        nextCurrentObservation = null
    } ?: delegate.currentObservation(generation, currentSession)
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
