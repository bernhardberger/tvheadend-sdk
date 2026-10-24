@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

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
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.metadata.EpgQueryAcceptance
import at.bernhardberger.tvheadend.sdk.core.metadata.EpgQueryFence
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
    fun `ordinary query batches publish once with every accepted result`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..6L)
        val publications = mutableListOf<EpgRepositoryState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            metadata.observation.collect { observation ->
                if (publications.lastOrNull() != observation.epgState) publications += observation.epgState
            }
        }
        val worker = EpgWorker(generation, metadata, MutableClock(instant(0)), queryEpg = { _, channelId, _ ->
            GatewayResult.Ok(listOf(queryEvent(channelId.value, channelId.value, 10, 20)))
        })
        val job = backgroundScope.launch { worker.run() }

        advanceTimeBy(1_500.milliseconds)
        runCurrent()
        assertEquals(1, publications.size - 1, "One EPG publication per completed ordinary batch")
        val first = (publications.last() as EpgRepositoryState.Current).snapshot
        assertEquals((1L..6L).toList(), first.events.map { it.id.value })
        assertEquals(6, first.coverages.count { it.queriedTo == instant(0) + 4.hours })

        advanceTimeBy(500.milliseconds)
        runCurrent()
        assertEquals(1, publications.size - 1)
        job.cancelAndJoin()
    }

    @Test
    fun `priority query publishes and settles before the rest of its batch`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..2L)
        val worker = EpgWorker(generation, metadata, MutableClock(instant(0)), queryEpg = { _, channelId, _ ->
            if (channelId == ChannelId(1)) GatewayResult.Ok(emptyList()) else awaitCancellation()
        })
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val acquisition = backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(1), instant(0) + 8.hours)
        }
        runCurrent()
        val job = backgroundScope.launch { worker.run() }
        runCurrent()

        assertEquals(0L, testScheduler.currentTime)
        assertTrue(acquisition.isCompleted)
        val covered = acquisition.await() as EpgCoverageAcquisitionResult.CoveredEmpty
        assertSame(metadata.observation.value, covered.observation)
        assertEquals(instant(0) + 8.hours, covered.observation.coverage(ChannelId(1))!!.queriedTo)
        job.cancelAndJoin()
    }

    @ParameterizedTest
    @ValueSource(strings = ["in-flight", "during-apply", "after-apply"])
    fun `waiter joining ordinary query settles without waiting for its batch`(timing: String) = runTest {
        val generation = GatewayGeneration()
        val delegate = synchronizedMetadata(generation, 1L..2L)
        var onApply: () -> Unit = {}
        val metadata = object : SessionMetadata by delegate {
            override fun applySuccessfulEpgQuery(
                generation: GatewayGeneration,
                query: EpgQueryFence,
                queriedTo: Instant,
                events: List<GatewayEpgQueryEvent>,
                deferPublication: Boolean,
            ): EpgQueryAcceptance {
                onApply()
                return delegate.applySuccessfulEpgQuery(generation, query, queriedTo, events, deferPublication)
            }
        }
        val release = CompletableDeferred<Unit>()
        val worker = EpgWorker(generation, metadata, MutableClock(instant(0)), queryEpg = { _, channelId, _ ->
            if (channelId == ChannelId(1)) {
                release.await()
                GatewayResult.Ok(emptyList())
            } else {
                awaitCancellation()
            }
        })
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        lateinit var acquisition: Deferred<EpgCoverageAcquisitionResult>
        val acquire = {
            acquisition = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
                worker.acquireCoverage(currentSession, ChannelId(1), instant(0) + 4.hours)
            }
        }
        val job = backgroundScope.launch { worker.run() }
        advanceTimeBy(500.milliseconds)
        runCurrent()
        when (timing) {
            "in-flight" -> acquire()
            "during-apply" -> onApply = acquire
        }
        release.complete(Unit)
        runCurrent()
        if (timing == "after-apply") acquire()
        runCurrent()

        assertTrue(acquisition.isCompleted, "Coverage must not wait for an unrelated unfinished query")
        assertTrue(acquisition.await() is EpgCoverageAcquisitionResult.CoveredEmpty)
        assertEquals(500L, testScheduler.currentTime)
        job.cancelAndJoin()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `worker cancellation flushes accepted results only for its bound generation`(replaceGeneration: Boolean) = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..2L)
        val worker = EpgWorker(generation, metadata, MutableClock(instant(0)), queryEpg = { _, channelId, _ ->
            if (channelId == ChannelId(1)) {
                GatewayResult.Ok(listOf(queryEvent(1, 1, 10, 20)))
            } else {
                awaitCancellation()
            }
        })
        val job = backgroundScope.launch { worker.run() }
        advanceTimeBy(500.milliseconds)
        runCurrent()
        assertTrue(metadata.observation.value.epgSnapshotForDisplay!!.events.isEmpty())

        val replacement = GatewayGeneration()
        if (replaceGeneration) {
            metadata.resetWorkingStateRetainingPublishedSnapshot()
            metadata.bindGeneration(replacement)
            metadata.acceptMetadata(MetadataEvent.ChannelAdded(replacement, channel(2)))
            metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(replacement))
            metadata.acceptMetadataDeferringEpg(MetadataEvent.EventAdded(replacement, event(2, 2, 10, 20)))
        }
        val beforeCancellation = metadata.observation.value
        job.cancelAndJoin()

        if (replaceGeneration) {
            assertSame(beforeCancellation, metadata.observation.value)
            metadata.flushDeferredMetadata(replacement)
            assertEquals(listOf(EventId(2)), metadata.observation.value.epgSnapshotForDisplay!!.events.map { it.id })
        } else {
            assertEquals(listOf(EventId(1)), metadata.observation.value.epgSnapshotForDisplay!!.events.map { it.id })
            assertEquals(instant(0) + 4.hours, metadata.observation.value.coverage(ChannelId(1))!!.queriedTo)
        }
    }

    @Test
    fun `waiter joining an in-progress flush settles before the unfinished batch`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..2L)
        val worker = EpgWorker(generation, metadata, MutableClock(instant(0)), queryEpg = { _, channelId, _ ->
            if (channelId == ChannelId(1)) GatewayResult.Ok(emptyList()) else awaitCancellation()
        })
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val job = backgroundScope.launch { worker.run() }
        advanceTimeBy(500.milliseconds)
        runCurrent()
        var acquisition: Deferred<EpgCoverageAcquisitionResult>? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            metadata.publishedSnapshots.collect { published ->
                if (published?.epgSnapshot?.coverages?.any { it.queriedTo != null } == true && acquisition == null) {
                    acquisition = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        worker.acquireCoverage(currentSession, ChannelId(1), instant(0) + 4.hours)
                    }
                }
            }
        }

        metadata.flushDeferredMetadata(generation)
        runCurrent()

        val enrolled = requireNotNull(acquisition)
        assertTrue(enrolled.isCompleted, "A waiter joining an in-progress flush must settle before the batch completes")
        assertTrue(enrolled.await() is EpgCoverageAcquisitionResult.CoveredEmpty)
        job.cancelAndJoin()
    }

    @Test
    fun `non EPG publication does not expose deferred query receipt times`() = runTest {
        val generation = GatewayGeneration()
        val clock = MutableClock(instant(0))
        val metadata = synchronizedMetadata(generation, 1L..2L, clock = clock)
        val before = metadata.observation.value
        val worker = EpgWorker(generation, metadata, clock, queryEpg = { _, channelId, _ ->
            if (channelId == ChannelId(1)) {
                clock.now = instant(1)
                GatewayResult.Ok(emptyList())
            } else {
                awaitCancellation()
            }
        })
        val job = backgroundScope.launch { worker.run() }
        advanceTimeBy(500.milliseconds)
        runCurrent()

        metadata.applyDvrDiskSpace(generation, GatewayResult.Timeout)
        assertEquals(before.epgState, metadata.observation.value.epgState)
        assertEquals(before.epgRefreshTimes, metadata.observation.value.epgRefreshTimes)
        assertEquals(before.epgLastQueriedAt, metadata.observation.value.epgLastQueriedAt,
            "Query receipt times must remain pending until their EPG publication")

        job.cancelAndJoin()
        assertEquals(instant(1), metadata.observation.value.epgRefreshTimes.lastUpdatedAt)
        assertEquals(mapOf(ChannelId(1) to instant(1)), metadata.observation.value.epgLastQueriedAt)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `publication preserves query deferred by an inline collector`(epgOnly: Boolean) = runTest {
        val generation = GatewayGeneration()
        val clock = MutableClock(instant(0))
        val metadata = synchronizedMetadata(generation, 1L..2L, clock = clock)
        var acceptedDuringPublication = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            metadata.publishedSnapshots.collect { published ->
                if (published?.epgLastQueriedAt?.containsKey(ChannelId(1)) == true && !acceptedDuringPublication) {
                    acceptedDuringPublication = true
                    clock.now = instant(2)
                    val query = requireNotNull(metadata.beginEpgQuery(generation, ChannelId(2)))
                    assertEquals(EpgQueryAcceptance.APPLIED, metadata.applySuccessfulEpgQuery(
                        generation, query, instant(0) + 4.hours, listOf(queryEvent(2, 2, 10, 20)),
                        deferPublication = true,
                    ))
                }
            }
        }
        clock.now = instant(1)
        val query = requireNotNull(metadata.beginEpgQuery(generation, ChannelId(1)))
        assertEquals(EpgQueryAcceptance.APPLIED, metadata.applySuccessfulEpgQuery(
            generation, query, instant(0) + 4.hours, listOf(queryEvent(1, 1, 10, 20)),
            deferPublication = !epgOnly,
        ))
        if (!epgOnly) metadata.flushDeferredMetadata(generation)
        assertTrue(acceptedDuringPublication)
        assertEquals(listOf(EventId(1)), metadata.observation.value.epgSnapshotForDisplay!!.events.map { it.id })
        assertEquals(mapOf(ChannelId(1) to instant(1)), metadata.observation.value.epgLastQueriedAt)

        metadata.flushDeferredMetadata(generation)

        assertEquals(listOf(EventId(1), EventId(2)), metadata.observation.value.epgSnapshotForDisplay!!.events.map { it.id },
            "Explicit flush must publish the query deferred during publication")
        assertEquals(mapOf(ChannelId(1) to instant(1), ChannelId(2) to instant(2)), metadata.observation.value.epgLastQueriedAt)
        assertEquals(instant(2), metadata.observation.value.epgRefreshTimes.lastUpdatedAt)
    }

    @Test
    fun `deferred query receipt times publish with the completed batch`() = runTest {
        val generation = GatewayGeneration()
        val clock = MutableClock(instant(0))
        val metadata = synchronizedMetadata(generation, 1L..2L, clock = clock)
        val before = metadata.observation.value
        val worker = EpgWorker(generation, metadata, clock, queryEpg = { _, channelId, _ ->
            clock.now = instant(channelId.value)
            GatewayResult.Ok(emptyList())
        })
        val job = backgroundScope.launch { worker.run() }
        advanceTimeBy(250.milliseconds)
        runCurrent()
        assertEquals(before.epgRefreshTimes, metadata.observation.value.epgRefreshTimes)
        assertTrue(metadata.observation.value.epgLastQueriedAt.isEmpty())

        advanceTimeBy(250.milliseconds)
        runCurrent()
        assertEquals(instant(2), metadata.observation.value.epgRefreshTimes.lastUpdatedAt)
        assertEquals(before.epgRefreshTimes.lastSynchronizedAt, metadata.observation.value.epgRefreshTimes.lastSynchronizedAt)
        assertEquals(mapOf(ChannelId(1) to instant(1), ChannelId(2) to instant(2)), metadata.observation.value.epgLastQueriedAt)
        job.cancelAndJoin()
    }

    @Test
    fun `completed sync suppresses ordinary queries without changing public acquisition`() = runTest {
        val generation = GatewayGeneration()
        val now = instant(0)
        val metadata = synchronizedMetadata(generation, 1L..2L, syncThrough = now + 24.hours)
        val requests = mutableListOf<ChannelId>()
        val worker = EpgWorker(generation, metadata, MutableClock(now), queryEpg = { _, channelId, _ ->
            requests += channelId
            GatewayResult.Ok(emptyList())
        })
        val job = backgroundScope.launch { worker.run() }
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(requests.isEmpty())
        val coverage = metadata.currentEpgSnapshot(generation)!!.coverages.first()
        assertNull(coverage.queriedTo)
        assertNull(coverage.knownTo)

        val proof = requireNotNull(metadata.observation.value.currentSession)
        val acquisition = backgroundScope.async { worker.acquireCoverage(proof, ChannelId(1), now + 8.hours) }
        runCurrent()
        assertTrue(acquisition.await() is EpgCoverageAcquisitionResult.CoveredEmpty)
        assertEquals(listOf(ChannelId(1)), requests)

        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(3)))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(listOf(ChannelId(1), ChannelId(3)), requests)
        job.cancelAndJoin()
    }

    @Test
    fun `ordinary queries extend beyond the fixed sync boundary as time passes`() = runTest {
        val generation = GatewayGeneration()
        val now = instant(0)
        val clock = MutableClock(now)
        val metadata = synchronizedMetadata(generation, 1L..1L, syncThrough = now + 24.hours)
        val targets = mutableListOf<Instant>()
        val worker = EpgWorker(generation, metadata, clock, queryEpg = { _, _, target ->
            targets += target
            GatewayResult.Ok(emptyList())
        })
        val job = backgroundScope.launch { worker.run() }
        runCurrent()
        assertTrue(targets.isEmpty())
        clock.now = now + 5.hours
        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(listOf(now + 28.hours), targets)
        job.cancelAndJoin()
    }

    @Test
    fun `sync scheduling proof retires on channel deletion reset and generation rebind`() = runTest {
        val generation = GatewayGeneration()
        val now = instant(0)
        val metadata = synchronizedMetadata(generation, 1L..2L, syncThrough = now + 24.hours)
        assertEquals(setOf(ChannelId(1), ChannelId(2)), metadata.initialEpgCoverage(generation).keys)
        metadata.acceptMetadata(MetadataEvent.ChannelDeleted(generation, ChannelId(1)))
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(1)))
        assertEquals(setOf(ChannelId(2)), metadata.initialEpgCoverage(generation).keys)
        metadata.resetWorkingStateRetainingPublishedSnapshot()
        assertTrue(metadata.initialEpgCoverage(generation).isEmpty())
        val replacement = GatewayGeneration()
        metadata.bindGeneration(replacement)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(replacement, channel(2)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation, now + 24.hours))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(replacement))
        assertTrue(metadata.initialEpgCoverage(replacement).isEmpty())
        assertEquals(
            listOf(EpgQueryPlan(ChannelId(2), now + 4.hours)),
            selectEpgQueries(
                metadata.currentEpgSnapshot(replacement)!!, now, EpgWorkerSettings(),
                initialEpgCoverage = metadata.initialEpgCoverage(replacement),
            ),
        )
    }

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
    fun `explicit coverage request skips the ordinary top-up cooldown`() = runTest {
        val generation = GatewayGeneration()
        val policy = EpgCoveragePolicy.create(7.days)
        val metadata = synchronizedMetadata(generation, 1L..1L, policy)
        val targets = mutableListOf<Instant>()
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            settings = EpgWorkerSettings(coveragePolicy = policy),
            queryEpg = { _, _, target ->
                targets += target
                GatewayResult.Ok(emptyList())
            },
        )
        val job = backgroundScope.launch { worker.run() }
        runCurrent()
        advanceTimeBy(250.milliseconds)
        runCurrent()
        assertEquals(listOf(instant(0) + 4.hours), targets)

        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val through = instant(0) + 3.days
        val acquisition = backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(1), through)
        }
        runCurrent()

        assertEquals(listOf(instant(0) + 4.hours, through), targets)
        assertTrue(acquisition.await() is EpgCoverageAcquisitionResult.CoveredEmpty)
        job.cancelAndJoin()
    }

    @Test
    fun `programme admitted past an eviction cut never proves the evicted window`() = runTest {
        val generation = GatewayGeneration()
        val policy = EpgCoveragePolicy.create(7.days, maximumRetainedEvents = 3)
        val hour = 1.hours.inWholeSeconds
        val cut = 30 * hour
        val metadata = synchronizedMetadata(generation, 1L..2L, policy,
            clock = SchedulerClock { testScheduler.currentTime }) {
            acceptMetadata(MetadataEvent.EventAdded(generation, event(1, 1, 0, hour)))
        }
        val fence = requireNotNull(metadata.beginEpgQuery(generation, ChannelId(2)))
        assertEquals(EpgQueryAcceptance.APPLIED, metadata.applySuccessfulEpgQuery(
            generation, fence, instant(cut + 2 * hour),
            listOf(queryEvent(2, 2, cut, cut + hour), queryEvent(3, 2, cut + hour, cut + 2 * hour)),
        ))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, event(4, 1, hour, 2 * hour)))
        metadata.acceptMetadata(MetadataEvent.EventDeleted(generation, EventId(4)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, event(5, 2, cut + 2 * hour, cut + 3 * hour)))

        // Channel 2's far tail went for the near addition; the later programme is shown but
        // proves nothing, and neither does the evicted start itself.
        var snapshot = requireNotNull(metadata.currentEpgSnapshot(generation))
        assertEquals(listOf(1L, 5L), snapshot.events.map { it.id.value })
        assertEquals(instant(cut - 1), snapshot.coverages.first { it.channelId == ChannelId(2) }.knownTo)

        val queries = mutableListOf<Pair<ChannelId, Instant>>()
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            settings = EpgWorkerSettings(coveragePolicy = policy, requestSpacing = 1.milliseconds),
            queryEpg = { _, channelId, target ->
                queries += channelId to target
                GatewayResult.Ok(
                    if (channelId == ChannelId(2)) listOf(queryEvent(2, 2, cut, cut + hour)) else emptyList(),
                )
            },
        )
        assertTrue(metadata.bindEpgCoverageRequester(generation, worker))
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val job = backgroundScope.launch { worker.run() }
        runCurrent()

        val boundary = backgroundScope.async {
            metadata.epgRepository.acquireCoverage(currentSession, ChannelId(2), instant(cut))
        }
        runCurrent()
        advanceTimeBy(2.milliseconds)
        runCurrent()

        assertTrue(boundary.await() is EpgCoverageAcquisitionResult.CoveredWithData)
        assertEquals(ChannelId(2) to instant(cut), queries.last())
        snapshot = requireNotNull(metadata.currentEpgSnapshot(generation))
        assertEquals(listOf(1L, 2L, 5L), snapshot.events.map { it.id.value }.sorted())
        // The query reached only the evicted start, so later evicted programmes stay unproven.
        assertEquals(instant(cut), snapshot.coverages.first { it.channelId == ChannelId(2) }.knownTo)
        job.cancelAndJoin()
    }

    @Test
    fun `failed priority target waits for cooldown while a further jump skips it`() = runTest {
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
                if (targets.size <= 2) GatewayResult.Timeout else GatewayResult.Ok(emptyList())
            },
        )
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val first = backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(1), instant(0) + 8.hours)
        }
        runCurrent()
        val job = backgroundScope.launch { worker.run() }
        runCurrent()
        assertEquals(listOf(instant(0) + 8.hours), targets)

        val repeated = backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(1), instant(0) + 8.hours)
        }
        runCurrent()
        advanceTimeBy(999.milliseconds)
        runCurrent()
        assertEquals(1, targets.size, "A failed target must not be retried before the cooldown ends")

        val jump = backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(1), instant(0) + 12.hours)
        }
        runCurrent()
        assertEquals(listOf(instant(0) + 8.hours, instant(0) + 12.hours), targets)
        advanceTimeBy(999.milliseconds)
        runCurrent()
        assertEquals(2, targets.size, "A failed jump must wait for the cooldown its own query started")
        advanceTimeBy(1.milliseconds)
        runCurrent()

        assertEquals(listOf(instant(0) + 8.hours, instant(0) + 12.hours, instant(0) + 12.hours), targets)
        assertTrue(first.await() is EpgCoverageAcquisitionResult.CoveredEmpty)
        assertTrue(repeated.await() is EpgCoverageAcquisitionResult.CoveredEmpty)
        assertTrue(jump.await() is EpgCoverageAcquisitionResult.CoveredEmpty)
        job.cancelAndJoin()
    }

    @Test
    fun `staggered priority attempt survives the expiry of its previous cooldown`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..2L)
        val through = instant(0) + 23.hours
        val starts = mutableListOf<Pair<ChannelId, Long>>()
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            settings = EpgWorkerSettings(
                requestSpacing = 100.milliseconds,
                channelCooldown = 1.seconds,
            ),
            queryEpg = { _, channelId, target ->
                starts += channelId to testScheduler.currentTime
                if (channelId == ChannelId(1) && target == through) GatewayResult.Timeout else GatewayResult.Ok(emptyList())
            },
        )
        val job = backgroundScope.launch { worker.run() }
        runCurrent()
        advanceTimeBy(950.milliseconds)
        runCurrent()
        assertEquals(listOf(ChannelId(1) to 0L, ChannelId(2) to 100L), starts)

        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val second = backgroundScope.async { worker.acquireCoverage(currentSession, ChannelId(2), through) }
        val first = backgroundScope.async { worker.acquireCoverage(currentSession, ChannelId(1), through) }
        runCurrent()
        // Channel 1's earlier cooldown ends at 1000 ms, between selection and its 1050 ms dispatch.
        advanceTimeBy(1_099.milliseconds)
        runCurrent()
        assertTrue(second.await() is EpgCoverageAcquisitionResult.CoveredEmpty)
        assertEquals(
            listOf(1050L),
            starts.drop(2).filter { (channelId, _) -> channelId == ChannelId(1) }.map { (_, time) -> time },
            "A failed target must wait for the cooldown its own query started",
        )
        advanceTimeBy(1.milliseconds)
        runCurrent()

        assertEquals(
            listOf(1050L, 2050L),
            starts.drop(2).filter { (channelId, _) -> channelId == ChannelId(1) }.map { (_, time) -> time },
        )
        assertFalse(first.isCompleted)
        job.cancelAndJoin()
    }

    @Test
    fun `priority for an in flight channel dispatches after that query without waiting for cooldown`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..1L)
        val release = CompletableDeferred<Unit>()
        val targets = mutableListOf<Instant>()
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            settings = EpgWorkerSettings(
                requestSpacing = 1.milliseconds,
                channelCooldown = 1.hours,
            ),
            queryEpg = { _, _, target ->
                targets += target
                if (targets.size == 1) release.await()
                GatewayResult.Ok(emptyList())
            },
        )
        val job = backgroundScope.launch { worker.run() }
        runCurrent()
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val acquisition = backgroundScope.async {
            worker.acquireCoverage(currentSession, ChannelId(1), instant(0) + 12.hours)
        }
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(listOf(instant(0) + 4.hours), targets, "One query per channel may be in flight")

        release.complete(Unit)
        runCurrent()

        assertEquals(listOf(instant(0) + 4.hours, instant(0) + 12.hours), targets)
        assertTrue(acquisition.await() is EpgCoverageAcquisitionResult.CoveredEmpty)
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
    fun `capacity exhaustion settles repository single and batch waiters atomically and retries after cooldown`() = runTest {
        val generation = GatewayGeneration()
        val policy = EpgCoveragePolicy.create(24.hours, maximumRetainedEvents = 2)
        // Every retained programme is upcoming and protected, so nothing can be evicted.
        val metadata = synchronizedMetadata(generation, 1L..2L, policy,
            clock = SchedulerClock { testScheduler.currentTime }) {
            acceptMetadata(MetadataEvent.EventAdded(generation, event(1, 1, 10, 20)))
            acceptMetadata(MetadataEvent.EventAdded(generation, event(2, 1, 20, 30)))
        }
        val before = requireNotNull(metadata.currentEpgSnapshot(generation))
        val release = CompletableDeferred<Unit>()
        val starts = mutableListOf<ChannelId>()
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            settings = EpgWorkerSettings(coveragePolicy = policy, requestSpacing = 1.milliseconds),
            queryEpg = { _, channelId, _ ->
                starts += channelId
                if (channelId == ChannelId(1)) {
                    release.await()
                    GatewayResult.Ok(
                        listOf(
                            queryEvent(1, 1, 10, 40, title = "replacement"),
                            queryEvent(3, 1, 40, 50),
                        ),
                    )
                } else {
                    GatewayResult.Ok(emptyList())
                }
            },
        )
        assertTrue(metadata.bindEpgCoverageRequester(generation, worker))
        val repository = metadata.epgRepository
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val target = instant(0) + 8.hours
        val single = backgroundScope.async {
            repository.acquireCoverage(currentSession, ChannelId(1), target)
        }
        val batch = backgroundScope.async {
            repository.acquireCoverageBatch(currentSession, listOf(ChannelId(1), ChannelId(2)), target)
        }
        val cancelled = backgroundScope.async {
            repository.acquireCoverage(currentSession, ChannelId(1), target)
        }
        runCurrent()
        val job = backgroundScope.launch { worker.run() }
        runCurrent()
        cancelled.cancelAndJoin()
        release.complete(Unit)
        advanceTimeBy(2.milliseconds)
        runCurrent()

        assertTrue(single.isCompleted, "Capacity rejection must settle without waiting for cooldown")
        assertTrue(batch.isCompleted, "A rejected channel must not hold the batch open")
        assertSame(EpgCoverageAcquisitionResult.CapacityExhausted, single.await())
        val settlements = batch.await().settlements
        assertEquals(listOf(ChannelId(1), ChannelId(2)), settlements.map { it.channelId })
        assertTrue(settlements[0] is EpgCoverageBatchSettlement.CapacityExhausted)
        assertEquals("EpgCoverageBatchSettlement.CapacityExhausted(<redacted>)", settlements[0].toString())
        assertTrue(settlements[1] is EpgCoverageBatchSettlement.CoveredEmpty)
        assertTrue(cancelled.isCancelled)
        val rejected = requireNotNull(metadata.currentEpgSnapshot(generation))
        assertEquals(before.events, rejected.events)
        assertEquals(before.coverages.first(), rejected.coverages.first())
        assertEquals(target, rejected.coverages.last().queriedTo)
        assertEquals(listOf(ChannelId(1), ChannelId(2)), starts)

        metadata.acceptMetadata(MetadataEvent.EventDeleted(generation, EventId(2)))
        val retry = backgroundScope.async {
            repository.acquireCoverage(currentSession, ChannelId(1), target)
        }
        runCurrent()
        advanceTimeBy(10.minutes - 3.milliseconds)
        runCurrent()
        assertFalse(retry.isCompleted)
        assertEquals(1, starts.count { it == ChannelId(1) })
        advanceTimeBy(1.milliseconds)
        runCurrent()

        assertTrue(retry.isCompleted)
        assertTrue(retry.await() is EpgCoverageAcquisitionResult.CoveredWithData)
        assertEquals(2, starts.count { it == ChannelId(1) })
        val accepted = requireNotNull(metadata.currentEpgSnapshot(generation))
        assertEquals(listOf(EventId(1), EventId(3)), accepted.events.map { it.id })
        assertEquals("replacement", accepted.events.first().title)
        assertEquals(target, accepted.coverages.first().queriedTo)
        job.cancelAndJoin()
    }

    @Test
    fun `stale query authority at full capacity keeps repository acquisition pending until retry`() = runTest {
        val generation = GatewayGeneration()
        val policy = EpgCoveragePolicy.create(24.hours, maximumRetainedEvents = 1)
        val metadata = synchronizedMetadata(generation, 1L..1L, policy,
            clock = SchedulerClock { testScheduler.currentTime }) {
            acceptMetadata(MetadataEvent.EventAdded(generation, event(1, 1, 10, 20)))
        }
        val before = requireNotNull(metadata.currentEpgSnapshot(generation))
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            settings = EpgWorkerSettings(coveragePolicy = policy, requestSpacing = 1.milliseconds),
            queryEpg = { _, _, _ ->
                attempts += 1
                if (attempts == 1) {
                    release.await()
                    GatewayResult.Ok(listOf(queryEvent(3, 1, 30, 40)))
                } else {
                    GatewayResult.Ok(emptyList())
                }
            },
        )
        assertTrue(metadata.bindEpgCoverageRequester(generation, worker))
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val acquisition = backgroundScope.async {
            metadata.epgRepository.acquireCoverage(currentSession, ChannelId(1), instant(0) + 8.hours)
        }
        runCurrent()
        val job = backgroundScope.launch { worker.run() }
        runCurrent()
        assertEquals(1, attempts)
        // An async event dropped at capacity invalidates the older query's authority.
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, event(2, 1, 20, 30)))
        release.complete(Unit)
        advanceTimeBy(1.milliseconds)
        runCurrent()

        assertFalse(acquisition.isCompleted)
        assertEquals(before, metadata.currentEpgSnapshot(generation))
        advanceTimeBy(10.minutes - 1.milliseconds)
        runCurrent()

        assertTrue(acquisition.isCompleted)
        assertTrue(acquisition.await() is EpgCoverageAcquisitionResult.CoveredWithData)
        assertEquals(2, attempts)
        job.cancelAndJoin()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `capacity rejection preserves repository waiter expiry on retirement`(retireAfterReduction: Boolean) = runTest {
        val generation = GatewayGeneration()
        val policy = EpgCoveragePolicy.create(24.hours, maximumRetainedEvents = 1)
        val delegate = synchronizedMetadata(generation, 1L..1L, policy) {
            acceptMetadata(MetadataEvent.EventAdded(generation, event(1, 1, 10, 20)))
        }
        val metadata = object : SessionMetadata by delegate {
            override fun applySuccessfulEpgQuery(
                generation: GatewayGeneration,
                query: EpgQueryFence,
                queriedTo: Instant,
                events: List<GatewayEpgQueryEvent>,
                deferPublication: Boolean,
            ): EpgQueryAcceptance {
                if (!retireAfterReduction) delegate.resetWorkingStateRetainingPublishedSnapshot()
                return delegate.applySuccessfulEpgQuery(generation, query, queriedTo, events, deferPublication).also {
                    if (retireAfterReduction) delegate.resetWorkingStateRetainingPublishedSnapshot()
                }
            }
        }
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            settings = EpgWorkerSettings(coveragePolicy = policy),
            queryEpg = { _, _, _ -> GatewayResult.Ok(listOf(queryEvent(2, 1, 20, 30))) },
        )
        assertTrue(metadata.bindEpgCoverageRequester(generation, worker))
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val acquisition = backgroundScope.async {
            metadata.epgRepository.acquireCoverage(currentSession, ChannelId(1), instant(0) + 8.hours)
        }
        runCurrent()
        val job = backgroundScope.launch { worker.run() }
        runCurrent()

        assertTrue(acquisition.isCompleted)
        assertSame(EpgCoverageAcquisitionResult.ObservationExpired, acquisition.await())
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
        advanceTimeBy(250.milliseconds)
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
                GatewayEpgEvent(EventId(1), ChannelId(1), instant(10), instant(20), title = "new"),
            ),
        )
        release.complete(Unit)
        advanceTimeBy(250.milliseconds)
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

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `unsupported and malformed queries become ineligible and are not retried`(malformed: Boolean) = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..1L)
        var attempts = 0
        val worker = EpgWorker(
            generation = generation,
            metadata = metadata,
            clock = SchedulerClock { testScheduler.currentTime },
            queryEpg = { _, _, _ ->
                attempts += 1
                if (malformed) GatewayResult.MalformedReply else GatewayResult.NotSupported
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

    @ParameterizedTest
    @ValueSource(ints = [24, 168])
    fun `advancing edge is admitted before another worker pass`(hours: Int) = runTest {
        val generation = GatewayGeneration()
        val clock = MutableClock(instant(0))
        val policy = EpgCoveragePolicy.create(hours.hours)
        val metadata = synchronizedMetadata(generation, 1L..1L, policy, clock.now + hours.hours)
        val job = backgroundScope.launch {
            EpgWorker(generation, metadata, clock, EpgWorkerSettings(coveragePolicy = policy),
                queryEpg = { _, _, _ -> GatewayResult.Timeout }).run()
        }
        runCurrent()
        clock.now += 1.minutes
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation,
            timedEvent(1, 1, clock.now + hours.hours, clock.now + hours.hours + 1.hours)))
        assertEquals(listOf(EventId(1)), metadata.observation.value.epgSnapshotForDisplay!!.events.map { it.id })
        val expiredStop = clock.now - 6.hours - 30.seconds
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation,
            timedEvent(2, 1, expiredStop - 1.hours, expiredStop)))
        metadata.applySuccessfulEpgQuery(generation, requireNotNull(metadata.beginEpgQuery(generation, ChannelId(1))),
            clock.now + hours.hours, listOf(
                GatewayEpgQueryEvent(EventId(3), ChannelId(1), expiredStop - 1.hours, expiredStop),
            ))
        assertEquals(listOf(EventId(1)), metadata.observation.value.epgSnapshotForDisplay!!.events.map { it.id })
        job.cancelAndJoin()
    }

    @ParameterizedTest
    @ValueSource(ints = [-3600, 3600])
    fun `both clock windows survive admission and worker pruning`(skew: Int) = runTest {
        val generation = GatewayGeneration()
        val clock = MutableClock(instant(0))
        val serverNow = instant(skew.toLong())
        val metadata = synchronizedMetadata(generation, 1L..1L, syncThrough = instant(0) + 24.hours,
            estimatedServerTime = { serverNow })
        val job = backgroundScope.launch {
            EpgWorker(generation, metadata, clock, queryEpg = { _, _, _ -> GatewayResult.Timeout }).run()
        }
        runCurrent()
        val past = minOf(clock.now, serverNow) - 6.hours
        val future = maxOf(clock.now, serverNow) + 24.hours
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, timedEvent(1, 1, past - 1.hours, past)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, timedEvent(2, 1, future, future + 1.hours)))
        assertEquals(2, metadata.observation.value.epgSnapshotForDisplay!!.events.size)
        runCurrent()
        assertEquals(2, metadata.observation.value.epgSnapshotForDisplay!!.events.size)
        job.cancelAndJoin()
    }

    @ParameterizedTest
    @ValueSource(ints = [-3600, 3600])
    fun `fallback to estimated time preserves retained edges`(skew: Int) = runTest {
        val generation = GatewayGeneration()
        val clock = MutableClock(instant(0))
        var estimate: Instant? = null
        val metadata = synchronizedMetadata(generation, 1L..1L, syncThrough = clock.now + 24.hours,
            estimatedServerTime = { estimate }) {
            acceptMetadata(MetadataEvent.EventAdded(generation, timedEvent(1, 1, clock.now - 7.hours, clock.now - 6.hours)))
            acceptMetadata(MetadataEvent.EventAdded(generation, timedEvent(2, 1, clock.now + 24.hours, clock.now + 25.hours)))
        }
        val job = backgroundScope.launch {
            EpgWorker(generation, metadata, clock, queryEpg = { _, _, _ -> GatewayResult.Timeout }).run()
        }
        runCurrent()
        val before = metadata.observation.value.epgSnapshotForDisplay!!.events
        estimate = instant(skew.toLong())
        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(before, metadata.observation.value.epgSnapshotForDisplay!!.events)
        // The newly available estimate expands, rather than replaces, the device window.
        val edge = if (skew < 0) estimate - 6.hours else estimate + 24.hours
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, timedEvent(3, 1, edge, edge)))
        runCurrent()
        assertEquals(3, metadata.observation.value.epgSnapshotForDisplay!!.events.size)
        job.cancelAndJoin()
    }

    @Test
    fun `filtered async traffic does not stamp publish or wake the worker`() = runTest {
        val generation = GatewayGeneration()
        val metadata = synchronizedMetadata(generation, 1L..1L, syncThrough = instant(0) + 24.hours)
        val job = backgroundScope.launch {
            EpgWorker(generation, metadata, MutableClock(instant(0)),
                queryEpg = { _, _, _ -> error("Unexpected query") }).run()
        }
        runCurrent()
        val before = metadata.observation.value
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, event(1, 1, -30_000, -25_000)))
        assertSame(before, metadata.observation.value)
        job.cancelAndJoin()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `post sync capacity refusal revokes scheduling proof`(unattributed: Boolean) = runTest {
        val generation = GatewayGeneration()
        val policy = EpgCoveragePolicy.create(24.hours, maximumRetainedEvents = 1)
        val metadata = synchronizedMetadata(generation, 1L..2L, policy, instant(0) + 24.hours,
            clock = MutableClock(instant(0))) {
            acceptMetadata(MetadataEvent.EventAdded(generation, event(1, 1, 0, 100)))
        }
        val refused = GatewayEpgEvent(EventId(2), if (unattributed) null else ChannelId(2), instant(0), instant(100))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, refused))
        metadata.acceptMetadata(MetadataEvent.EventDeleted(generation, EventId(1)))
        val queried = mutableListOf<ChannelId>()
        val job = backgroundScope.launch {
            EpgWorker(generation, metadata, MutableClock(instant(0)), EpgWorkerSettings(coveragePolicy = policy),
                queryEpg = { _, channel, _ -> queried += channel; GatewayResult.Timeout }).run()
        }
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(if (unattributed) listOf(ChannelId(1), ChannelId(2)) else listOf(ChannelId(2)), queried)
        job.cancelAndJoin()
    }

    @Test
    fun `lagging estimate retains a server slide edge through pruning`() = runTest {
        val generation = GatewayGeneration()
        val serverTick = instant(1)
        val estimate = serverTick - 500.milliseconds
        val clock = MutableClock(estimate)
        val metadata = synchronizedMetadata(generation, 1L..1L, syncThrough = estimate + 24.hours,
            estimatedServerTime = { estimate })
        val job = backgroundScope.launch {
            EpgWorker(generation, metadata, clock, queryEpg = { _, _, _ -> GatewayResult.Timeout }).run()
        }
        runCurrent()
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation,
            timedEvent(1, 1, serverTick + 24.hours, serverTick + 25.hours)))
        assertEquals(1, metadata.observation.value.epgSnapshotForDisplay!!.events.size)
        runCurrent()
        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(1, metadata.observation.value.epgSnapshotForDisplay!!.events.size)
        job.cancelAndJoin()
    }

    @Test
    fun `capacity refused sync channel warms up after capacity frees`() = runTest {
        val generation = GatewayGeneration()
        val policy = EpgCoveragePolicy.create(24.hours, maximumRetainedEvents = 1)
        val metadata = synchronizedMetadata(generation, 1L..2L, policy, instant(0) + 24.hours,
            clock = MutableClock(instant(0))) {
            acceptMetadata(MetadataEvent.EventAdded(generation, event(1, 1, 0, 100)))
            acceptMetadata(MetadataEvent.EventAdded(generation, event(2, 2, 0, 100)))
        }
        metadata.acceptMetadata(MetadataEvent.EventDeleted(generation, EventId(1)))
        val queried = mutableListOf<ChannelId>()
        val job = backgroundScope.launch {
            EpgWorker(generation, metadata, MutableClock(instant(0)), EpgWorkerSettings(coveragePolicy = policy),
                queryEpg = { _, channel, _ -> queried += channel; GatewayResult.Timeout }).run()
        }
        runCurrent()
        assertEquals(listOf(ChannelId(2)), queried)
        job.cancelAndJoin()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `out of window async events do not oscillate published counts`(update: Boolean) = runTest {
        val generation = GatewayGeneration()
        val now = instant(0)
        val metadata = synchronizedMetadata(generation, 1L..1L) {
            acceptMetadata(MetadataEvent.EventAdded(generation, event(1, 1, 0, 3_600)))
        }
        val counts = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            metadata.observation.collect { counts += it.epgSnapshotForDisplay!!.events.size }
        }
        val worker = EpgWorker(generation, metadata, MutableClock(now), queryEpg = { _, _, _ ->
            GatewayResult.Timeout
        })
        val job = backgroundScope.launch { worker.run() }
        runCurrent()
        repeat(2) {
            val outside = timedEvent(2, 1, now - 7.hours, now - 6.hours - 1.seconds)
            metadata.acceptMetadata(
                if (update) MetadataEvent.EventUpdated(generation, outside)
                else MetadataEvent.EventAdded(generation, outside),
            )
            runCurrent()
            advanceTimeBy(1.seconds)
            runCurrent()
        }
        assertEquals(1, counts.last())
        assertTrue(counts.all { it == 1 }, "Published counts: $counts")
        job.cancelAndJoin()
    }

    @Test
    fun `out of window query events do not oscillate published counts`() = runTest {
        val generation = GatewayGeneration()
        val now = instant(0)
        val metadata = synchronizedMetadata(generation, 1L..1L) {
            acceptMetadata(MetadataEvent.EventAdded(generation, event(1, 1, 0, 3_600)))
        }
        val counts = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            metadata.observation.collect { counts += it.epgSnapshotForDisplay!!.events.size }
        }
        // A server clock behind the device can return a programme outside local past retention.
        val worker = EpgWorker(generation, metadata, MutableClock(now), queryEpg = { _, _, _ ->
            GatewayResult.Ok(listOf(queryEvent(2, 1, -25_200, -21_601)))
        })
        val job = backgroundScope.launch { worker.run() }
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()
        advanceTimeBy(10.minutes)
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(1, counts.last())
        assertTrue(counts.all { it == 1 }, "Published counts: $counts")
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
            acceptMetadata(
                MetadataEvent.EventAdded(
                    generation,
                    timedEvent(4, 1, now + 7.days + 61.seconds, now + 7.days + 1.hours),
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
        assertEquals(listOf(1L, 2L, 3L), snapshot.events.map { it.id.value })
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
    coveragePolicy: EpgCoveragePolicy = EpgCoveragePolicy.create(),
    syncThrough: Instant? = null,
    estimatedServerTime: (GatewayGeneration) -> Instant? = { null },
    clock: Clock = Clock.System,
    beforeFence: PhaseOneSessionMetadata.() -> Unit = {},
): PhaseOneSessionMetadata = PhaseOneSessionMetadata(
    epgCoveragePolicy = coveragePolicy, estimatedServerTime = estimatedServerTime, clock = clock,
).apply {
    bindGeneration(generation)
    channelIds.forEach { id ->
        acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id)))
    }
    beforeFence()
    acceptMetadata(MetadataEvent.InitialSyncCompleted(generation, syncThrough))
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
