@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.testing.FakePlaybackApi::class,
)

package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.ArtworkContent
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoadResult
import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.Channel
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.CacheStatistics
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrChange
import at.bernhardberger.tvheadend.sdk.core.DvrChangeKind
import at.bernhardberger.tvheadend.sdk.core.DvrChangeOrigin
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrEntryUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrMutationResult
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrProgressResult
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSchedule
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.DvrSnapshot
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgCoverage
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageAcquisitionResult
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageBatchResult
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageBatchSettlement
import at.bernhardberger.tvheadend.sdk.core.EpgSearchRequest
import at.bernhardberger.tvheadend.sdk.core.EpgSearchResult
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.PlaybackBindingResult
import at.bernhardberger.tvheadend.sdk.core.RecordingPlaybackAdmission
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.ServerProfile
import at.bernhardberger.tvheadend.sdk.core.SessionCommandResult
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.StreamProfile
import at.bernhardberger.tvheadend.sdk.core.StreamProfileId
import at.bernhardberger.tvheadend.sdk.core.StreamProfilesResult
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleId
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleUpdate
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpener
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStream
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTracks
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionCondition
import at.bernhardberger.tvheadend.sdk.playback.StreamIndex
import at.bernhardberger.tvheadend.sdk.playback.createSubscriptionManager
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal class FakeTvheadendSessionTest {
    @Test
    fun `watch and reset retirement after capture preserve application uncertainty`() = runTest {
        val replies = listOf(
            DvrProgressResult.Accepted to true,
            DvrProgressResult.Failed(TvheadendFailure.SERVER_REJECTED, false) to false,
            DvrProgressResult.Failed(TvheadendFailure.TIMEOUT, true) to true,
        )
        for (reset in listOf(false, true)) {
            for ((reply, mayHaveApplied) in replies) {
                val fake = FakeTvheadendSession(currentObservation())
                val proof = fake.captureCurrentSession()
                val repository = fake.dvrRepository
                repository.scriptUpdateWatchState(reply)
                repository.scriptResetResume(reply)
                fake.afterResult = {
                    assertEquals(1, repository.watchStateRequests.size + repository.resetResumeRequests.size)
                    fake.retire()
                }
                val result = if (reset) {
                    repository.resetResume(proof, DvrEntryId(1))
                } else {
                    repository.updateWatchState(proof, DvrEntryId(1), true)
                }
                assertEquals(DvrProgressResult.Failed(TvheadendFailure.CONNECTION_CHANGED, mayHaveApplied), result)
                assertEquals(if (reset) 0 else 1, repository.watchStateRequests.size)
                assertEquals(if (reset) 1 else 0, repository.resetResumeRequests.size)
            }
        }
    }

    @Test
    fun `resume resets script outcomes capture immutable redacted requests and fence expired calls`() = runTest {
        val fake = FakeTvheadendSession(currentObservation())
        val observation = fake.observation.value
        val proof = checkNotNull(observation.currentSession)
        val repository = fake.dvrRepository
        repository.scriptResetResume(DvrProgressResult.Accepted)
        assertSame(DvrProgressResult.Accepted, repository.resetResume(proof, DvrEntryId(987654)))
        val captured = repository.resetResumeRequests
        assertEquals(listOf(FakeDvrRepository.ResetResumeRequest(DvrEntryId(987654))), captured)
        assertEquals("ResetResumeRequest(id=<redacted>)", captured.single().toString())
        val refusal = DvrProgressResult.Failed(TvheadendFailure.SERVER_REJECTED, false)
        repository.scriptResetResume(refusal)
        assertEquals(refusal, repository.resetResume(proof, DvrEntryId(2)))
        assertEquals(2, repository.resetResumeRequests.size)
        assertEquals(1, captured.size)
        assertThrows(UnsupportedOperationException::class.java) { (captured as MutableList).clear() }
        assertSame(observation, fake.observation.value)
        assertThrows(IllegalArgumentException::class.java) {
            repository.scriptResetResume(DvrProgressResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, false))
        }
        fake.replaceGeneration(currentObservation())
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, false),
            repository.resetResume(proof, DvrEntryId(3)))
        assertEquals(2, repository.resetResumeRequests.size)
        assertEquals(List(3) { FakeSessionCall.DVR_RESET_RESUME }, fake.calls)
    }

    @Test
    fun `reset fake checks retirement at admission and propagates cancellation`() = runTest {
        val fake = FakeTvheadendSession(currentObservation())
        val proof = checkNotNull(fake.observation.value.currentSession)
        fake.dvrRepository.scriptResetResume(DvrProgressResult.Accepted)
        fake.afterAdmission = { fake.retire() }
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, false),
            fake.dvrRepository.resetResume(proof, DvrEntryId(1)))
        assertTrue(fake.dvrRepository.resetResumeRequests.isEmpty())
        val current = FakeTvheadendSession(currentObservation())
        val cancellation = CancellationException("cancelled")
        current.afterAdmission = { throw cancellation }
        var caught: CancellationException? = null
        try {
            current.dvrRepository.resetResume(checkNotNull(current.observation.value.currentSession), DvrEntryId(1))
        } catch (failure: CancellationException) {
            caught = failure
        }
        assertSame(cancellation, caught)
        assertTrue(current.dvrRepository.resetResumeRequests.isEmpty())
    }

    @Test
    fun `watch actions script outcomes capture safe immutable requests and expire old proofs`() = runTest {
        val fake = FakeTvheadendSession(currentObservation())
        val observation = fake.observation.value
        val proof = checkNotNull(observation.currentSession)
        val repository = fake.dvrRepository
        repository.scriptUpdateWatchState(DvrProgressResult.Accepted)
        assertSame(DvrProgressResult.Accepted, repository.updateWatchState(proof, DvrEntryId(987654), true))
        val captured = repository.watchStateRequests
        assertEquals(listOf(FakeDvrRepository.WatchStateRequest(DvrEntryId(987654), true)), captured)
        assertEquals("WatchStateRequest(id=<redacted>, watched=true)", captured.single().toString())
        val refusal = DvrProgressResult.Failed(TvheadendFailure.SERVER_REJECTED, false)
        repository.scriptUpdateWatchState(refusal)
        assertEquals(refusal, repository.updateWatchState(proof, DvrEntryId(2), false))
        assertEquals(listOf(true, false), repository.watchStateRequests.map { it.watched })
        assertEquals(1, captured.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (captured as MutableList).clear()
        }
        assertSame(observation, fake.observation.value)
        assertThrows(IllegalArgumentException::class.java) {
            repository.scriptUpdateWatchState(DvrProgressResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, false))
        }
        fake.replaceGeneration(currentObservation())
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, false),
            repository.updateWatchState(proof, DvrEntryId(3), true))
        assertEquals(2, repository.watchStateRequests.size)
        assertEquals(List(3) { FakeSessionCall.DVR_UPDATE_WATCH_STATE }, fake.calls)
    }

    @Test
    fun `watch fake checks retirement at admission and propagates cancellation`() = runTest {
        val fake = FakeTvheadendSession(currentObservation())
        val proof = checkNotNull(fake.observation.value.currentSession)
        fake.dvrRepository.scriptUpdateWatchState(DvrProgressResult.Accepted)
        fake.afterAdmission = { fake.retire() }
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, false),
            fake.dvrRepository.updateWatchState(proof, DvrEntryId(1), false))
        assertTrue(fake.dvrRepository.watchStateRequests.isEmpty())

        val current = FakeTvheadendSession(currentObservation())
        val cancellation = CancellationException("cancelled")
        current.afterAdmission = { throw cancellation }
        var caught: CancellationException? = null
        try {
            current.dvrRepository.updateWatchState(checkNotNull(current.observation.value.currentSession),
                DvrEntryId(1), true)
        } catch (failure: CancellationException) {
            caught = failure
        }
        assertSame(cancellation, caught)
        assertTrue(current.dvrRepository.watchStateRequests.isEmpty())
    }

    @Test
    fun `fake DVR changes are hot non replaying and independent from snapshots`() = runTest {
        val fake = FakeTvheadendSession(currentObservation())
        val observation = fake.observation.value
        val change = DvrChange.create(
            DvrChangeKind.REMOVED, null, null, DvrChangeOrigin.External,
            observation.currentSession!!.generationIdentity,
        )
        fake.dvrRepository.emitChange(change)
        val collected = mutableListOf<DvrChange>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            fake.dvrRepository.changes.collect { collected += it }
        }
        assertTrue(collected.isEmpty())
        fake.dvrRepository.emitChange(change)
        kotlinx.coroutines.yield()
        assertEquals(listOf(change), collected)
        assertSame(observation, fake.observation.value)
        assertEquals("DvrChange(<redacted>)", change.toString())
        collector.cancel()
    }

    @Test
    fun `media opener transfers ownership or closes on replacement and cancellation`() = runTest {
        for (boundary in listOf("transfer", "replace", "cancel")) {
            val fake = FakeTvheadendSession(currentObservation())
            val connection = ScriptedSubscriptionConnection()
            val manager = createSubscriptionManager(connection, StandardTestDispatcher(testScheduler)).apply { startAdmission() }
            val delivered = mutableListOf<SubscriptionEvent>()
            val tracks = mutableListOf<SubscriptionTracks>()
            lateinit var callback: SubscriptionEventConsumer
            fake.scriptLivePlaybackSuccess(SubscriptionOpener { id, consumer, period ->
                assertEquals(1L, id.value)
                assertEquals(120.seconds, period)
                callback = consumer
                manager.open(id, consumer, period).also {
                    if (boundary == "replace") fake.replaceGeneration(currentObservation())
                    if (boundary == "cancel") currentCoroutineContext().cancel()
                }
            })
            val binding = (fake.bindLivePlayback(fake.captureCurrentSession(), ChannelId(1)) as PlaybackBindingResult.Bound).binding
            val opened = async { binding.open(object : SubscriptionEventConsumer {
                override suspend fun accept(event: SubscriptionEvent) { delivered += event }
                override fun tracksReady(value: SubscriptionTracks) { tracks += value }
            }, SubscriptionOptions(timeshiftPeriod = 120.seconds)) }
            val registration = connection.awaitCollectionRegistered()
            connection.emit(registration, SubscriptionEvent.Started(listOf(SubscriptionStream(
                StreamIndex(1), SubscriptionStreamType.H264, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
            )), null))
            try {
                if (boundary == "cancel") {
                    var cancelled = false
                    try { opened.await() } catch (_: CancellationException) { cancelled = true }
                    assertTrue(cancelled)
                } else if (boundary == "replace") {
                    assertSame(SubscriptionOpenResult.NotReady, opened.await())
                } else {
                    val active = (opened.await() as SubscriptionOpenResult.Opened).subscription
                    assertEquals(0, connection.unsubscribeCount)
                    val before = delivered.size
                    callback.accept(SubscriptionEvent.Speed(0))
                    assertEquals(before + 1, delivered.size)
                    fake.retire()
                    callback.accept(SubscriptionEvent.Speed(100))
                    assertEquals(before + 1, delivered.size)
                    assertEquals(1, tracks.size)
                    callback.tracksReady(tracks.single())
                    assertEquals(1, tracks.size)
                    assertSame(SubscriptionOpenResult.NotReady, binding.open(SubscriptionEventConsumer {}, SubscriptionOptions()))
                    active.close()
                }
                assertEquals(1, connection.unsubscribeCount)
            } finally {
                manager.closeAndJoin()
            }
        }
    }

    @Test
    fun `scripts cached artwork bound to the observed profile`() = runTest {
        val a = ServerProfile("a.invalid")
        val b = ServerProfile("b.invalid")
        val id = ArtworkId(1)
        val fake = FakeTvheadendSession()
        val bytes = ArtworkLoadResult.Available(ArtworkContent.create(byteArrayOf(7)))
        fake.artwork.scriptCached(a, bytes)
        org.junit.jupiter.api.Assertions.assertNull(
            fake.artwork.cachedArtwork(fake.observation.value, id),
            "An observation without profile data resolves nothing",
        )

        fake.publish(SessionObservation.create(), a)
        val observedA = fake.observation.value
        val first = requireNotNull(fake.artwork.cachedArtwork(observedA, id))
        assertEquals(first, fake.artwork.cachedArtwork(observedA, id))
        assertSame(bytes, fake.artwork.loadCachedArtwork(first))
        fake.publish(SessionObservation.create(sessionState = SessionState.Connecting))
        assertNotNull(fake.artwork.cachedArtwork(fake.observation.value, id), "Publishing keeps the profile")

        fake.artwork.scriptCached(b, bytes)
        fake.publish(SessionObservation.create(), b)
        val observedB = fake.observation.value
        org.junit.jupiter.api.Assertions.assertNotEquals(observedA, observedB, "Equal data, different profiles")
        org.junit.jupiter.api.Assertions.assertNull(
            fake.artwork.cachedArtwork(observedA, id),
            "A's artwork id never maps to B's bytes",
        )
        val second = requireNotNull(fake.artwork.cachedArtwork(observedB, id))
        org.junit.jupiter.api.Assertions.assertNotEquals(first.cacheKey, second.cacheKey)
        assertEquals(
            TvheadendFailure.OBSERVATION_EXPIRED,
            (fake.artwork.loadCachedArtwork(first) as ArtworkLoadResult.Unavailable).failure,
        )
        assertSame(bytes, fake.artwork.loadCachedArtwork(second))

        fake.artwork.scriptCached(a, bytes)
        assertEquals(first, fake.artwork.cachedArtwork(observedA, id), "A observations resolve once A is active")
        assertSame(bytes, fake.artwork.loadCachedArtwork(first), "Equal profile identity reads again")
        assertEquals(
            TvheadendFailure.OBSERVATION_EXPIRED,
            (fake.artwork.loadCachedArtwork(second) as ArtworkLoadResult.Unavailable).failure,
        )

        fake.artwork.scriptCached(null)
        org.junit.jupiter.api.Assertions.assertNull(fake.artwork.cachedArtwork(observedA, id))
    }

    @Test
    fun `replacement generations can carry a profile`() {
        val profile = ServerProfile("a.invalid")
        val fake = FakeTvheadendSession()
        fake.artwork.scriptCached(profile)
        fake.replaceGeneration(currentObservation(), profile)
        val first = fake.captureCurrentSession()
        assertNotNull(fake.artwork.cachedArtwork(fake.observation.value, ArtworkId(1)))
        fake.replaceGeneration(currentObservation(), null)
        org.junit.jupiter.api.Assertions.assertNotSame(first, fake.captureCurrentSession())
        org.junit.jupiter.api.Assertions.assertNull(fake.artwork.cachedArtwork(fake.observation.value, ArtworkId(1)))
    }

    @Test
    fun `scripts successful session repository and binding results and records calls`() = runTest {
        val supplied = currentObservation()
        val fake = FakeTvheadendSession(supplied)
        assertFalse(fake.isCurrent(requireNotNull(supplied.currentSession)))
        val current = fake.captureCurrentSession()
        val profile = StreamProfile(StreamProfileId("00000000000000000000000000000000"), "pass", "")
        fake.scriptConnect(SessionCommandResult.NO_CHANGE)
        fake.scriptRetry(SessionCommandResult.STARTED)
        fake.scriptStreamProfiles(listOf(profile))
        fake.epgRepository.scriptSearch(emptyList())
        fake.dvrRepository.scriptScheduleEntry(DvrMutationResult.Confirmed(DvrEntryId(9)))
        fake.artwork.scriptLoad(ArtworkLoadResult.Available(ArtworkContent.create(byteArrayOf(1))))
        fake.scriptLivePlaybackSuccess()
        fake.scriptRecordingPlaybackSuccess()
        assertCancellationPropagates { fake.retry() }
        assertEquals(SessionCommandResult.NO_CHANGE, fake.connect(ServerProfile("test.invalid")))
        assertEquals(SessionCommandResult.STARTED, fake.retry())
        val profiles = fake.getStreamProfiles(current) as StreamProfilesResult.Available
        assertEquals(listOf(profile), profiles.profiles)
        assertSame(current, profiles.originatingSession)
        val search = fake.epgRepository.search(current, EpgSearchRequest.create("news"))
            as EpgSearchResult.Available
        assertSame(current, search.originatingSession)
        assertEquals(
            DvrEntryId(9),
            (fake.dvrRepository.scheduleEntry(current, scheduleRequest()) as DvrMutationResult.Confirmed).value,
        )
        assertTrue(fake.artwork.loadArtwork(current, ArtworkId(1)) is ArtworkLoadResult.Available)
        assertTrue(fake.bindLivePlayback(current, ChannelId(1)) is PlaybackBindingResult.Bound)
        val recording = fake.bindRecordingPlayback(current, DvrEntryId(1))
            as PlaybackBindingResult.Bound
        assertTrue(recording.binding.admission is RecordingPlaybackAdmission.Completed)
        fake.disconnect()
        fake.shutdown()
        assertEquals(
            listOf(
                FakeSessionCall.CONNECT,
                FakeSessionCall.RETRY,
                FakeSessionCall.GET_STREAM_PROFILES,
                FakeSessionCall.EPG_SEARCH,
                FakeSessionCall.DVR_SCHEDULE_ENTRY,
                FakeSessionCall.LOAD_ARTWORK,
                FakeSessionCall.BIND_LIVE_PLAYBACK,
                FakeSessionCall.BIND_RECORDING_PLAYBACK,
                FakeSessionCall.DISCONNECT,
                FakeSessionCall.SHUTDOWN,
            ),
            fake.calls,
        )
    }
    @Test
    fun `rejects foreign proof across every generation-bound surface`() = runTest {
        val first = FakeTvheadendSession(currentObservation())
        val second = FakeTvheadendSession(currentObservation())
        val foreign = first.captureCurrentSession()
        assertEquals(
            StreamProfilesResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED),
            second.getStreamProfiles(foreign),
        )
        assertEquals(
            EpgSearchResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED),
            second.epgRepository.search(foreign, EpgSearchRequest.create("news")),
        )
        val expiredCoverage = second.epgRepository.acquireCoverageBatch(
            foreign,
            listOf(ChannelId(2), ChannelId(2), ChannelId(3)),
            Instant.fromEpochSeconds(1),
        )
        assertEquals(listOf(2L, 3L), expiredCoverage.settlements.map { it.channelId.value })
        assertTrue(expiredCoverage.settlements.all { it is EpgCoverageBatchSettlement.ObservationExpired })
        assertEquals(
            DvrMutationResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, mayHaveApplied = false),
            second.dvrRepository.scheduleEntry(foreign, scheduleRequest()),
        )
        val artwork = second.artwork.loadArtwork(foreign, ArtworkId(1)) as ArtworkLoadResult.Unavailable
        assertEquals(TvheadendFailure.OBSERVATION_EXPIRED, artwork.failure)
        assertSame(
            PlaybackBindingResult.ObservationExpired,
            second.bindLivePlayback(foreign, ChannelId(1)),
        )
        assertSame(
            PlaybackBindingResult.ObservationExpired,
            second.bindRecordingPlayback(foreign, DvrEntryId(1)),
        )
    }
    @Test
    fun `replacement retires previously returned playback bindings`() {
        val fake = FakeTvheadendSession(currentObservation())
        val first = fake.captureCurrentSession()
        fake.scriptLivePlaybackSuccess()
        fake.scriptRecordingPlaybackSuccess()
        val live = (fake.bindLivePlayback(first, ChannelId(1)) as PlaybackBindingResult.Bound).binding
        val recording = (
            fake.bindRecordingPlayback(first, DvrEntryId(1)) as PlaybackBindingResult.Bound
        ).binding
        fake.replaceGeneration(currentObservation())
        assertFalse(live.isCurrent)
        assertSame(RecordingPlaybackAdmission.ObservationExpired, recording.admission)
    }

    @Test
    fun `scripts every failure and DVR command without provenance bypass`() = runTest {
        val fake = FakeTvheadendSession(currentObservation())
        val current = fake.captureCurrentSession()
        val dvrFailure = DvrMutationResult.Failed(TvheadendFailure.SERVER_REJECTED, false, "Access denied")
        fake.scriptStreamProfilesFailure(StreamProfilesResult.Failed(TvheadendFailure.NOT_SUPPORTED))
        fake.epgRepository.scriptSearchFailure(EpgSearchResult.Failed(TvheadendFailure.NOT_SUPPORTED))
        fake.epgRepository.scriptCoverage(EpgCoverageAcquisitionResult.Ineligible)
        fake.artwork.scriptLoad(ArtworkLoadResult.Unavailable(TvheadendFailure.NOT_SUPPORTED))
        fake.scriptLivePlaybackFailure(PlaybackBindingResult.TargetUnavailable)
        fake.scriptRecordingPlaybackFailure(PlaybackBindingResult.TargetUnavailable)
        fake.dvrRepository.apply {
            scriptScheduleEntry(dvrFailure)
            scriptUpdateEntry(dvrFailure)
            scriptStopEntry(dvrFailure)
            scriptCancelEntry(dvrFailure)
            scriptDeleteEntry(dvrFailure)
            scriptCreateAutorecRule(dvrFailure)
            scriptUpdateAutorecRule(dvrFailure)
            scriptDeleteAutorecRule(dvrFailure)
            scriptCreateTimerecRule(dvrFailure)
            scriptUpdateTimerecRule(dvrFailure)
            scriptDeleteTimerecRule(dvrFailure)
        }
        assertEquals(StreamProfilesResult.Failed(TvheadendFailure.NOT_SUPPORTED), fake.getStreamProfiles(current))
        assertSame(PlaybackBindingResult.TargetUnavailable, fake.bindLivePlayback(current, ChannelId(1)))
        assertSame(PlaybackBindingResult.TargetUnavailable, fake.bindRecordingPlayback(current, DvrEntryId(1)))
        assertEquals(
            EpgSearchResult.Failed(TvheadendFailure.NOT_SUPPORTED),
            fake.epgRepository.search(current, EpgSearchRequest.create("x")),
        )
        assertSame(
            EpgCoverageAcquisitionResult.Ineligible,
            fake.epgRepository.acquireCoverage(current, ChannelId(1), Instant.fromEpochSeconds(1)),
        )
        val batch = fake.epgRepository.acquireCoverageBatch(
            current,
            listOf(ChannelId(1), ChannelId(1), ChannelId(2)),
            Instant.fromEpochSeconds(1),
        )
        assertEquals(listOf(1L, 2L), batch.settlements.map { it.channelId.value })
        assertTrue(batch.settlements.all { it is EpgCoverageBatchSettlement.Rejected })
        val results: List<DvrMutationResult<*>> = listOf(
            fake.dvrRepository.scheduleEntry(current, scheduleRequest()),
            fake.dvrRepository.updateEntry(current, DvrEntryId(1), DvrEntryUpdate()),
            fake.dvrRepository.stopEntry(current, DvrEntryId(1)),
            fake.dvrRepository.cancelEntry(current, DvrEntryId(1)),
            fake.dvrRepository.deleteEntry(current, DvrEntryId(1)),
            fake.dvrRepository.createAutorecRule(current, AutorecRuleCreate("x")),
            fake.dvrRepository.updateAutorecRule(current, AutorecRuleId("x"), AutorecRuleUpdate()),
            fake.dvrRepository.deleteAutorecRule(current, AutorecRuleId("x")),
            fake.dvrRepository.createTimerecRule(current, TimerecRuleCreate("x")),
            fake.dvrRepository.updateTimerecRule(current, TimerecRuleId("x"), TimerecRuleUpdate()),
            fake.dvrRepository.deleteTimerecRule(current, TimerecRuleId("x")),
        )
        results.forEach { result -> assertSame(dvrFailure, result) }
        assertTrue(fake.artwork.loadArtwork(current, ArtworkId(1)) is ArtworkLoadResult.Unavailable)
        fake.cache.scriptStatistics(CacheStatistics(metadataBytes = 10, artworkBytes = 20, artworkEntryCount = 2))
        fake.cache.clear()
        assertEquals(CacheStatistics.EMPTY, fake.cache.statistics.value)
        val watchFailure = DvrProgressResult.Failed(TvheadendFailure.CONNECTION_LIMIT, false)
        fake.dvrRepository.scriptUpdateWatchState(watchFailure)
        assertSame(watchFailure, fake.dvrRepository.updateWatchState(current, DvrEntryId(1), true))
        fake.dvrRepository.scriptResetResume(watchFailure)
        assertSame(watchFailure, fake.dvrRepository.resetResume(current, DvrEntryId(1)))
        assertEquals(FakeSessionCall.entries.drop(4), fake.calls)
    }

    @Test
    fun `successful bindings require targets track removal and propagate cancellation`() = runTest {
        val fake = FakeTvheadendSession(currentObservation())
        val current = fake.captureCurrentSession()
        fake.scriptLivePlaybackSuccess()
        fake.scriptRecordingPlaybackSuccess()
        val live = (fake.bindLivePlayback(current, ChannelId(1)) as PlaybackBindingResult.Bound).binding
        val recording = (
            fake.bindRecordingPlayback(current, DvrEntryId(1)) as PlaybackBindingResult.Bound
        ).binding
        assertCancellationPropagates {
            live.open(SubscriptionEventConsumer {}, SubscriptionOptions())
        }
        assertCancellationPropagates { recording.openRecording() }
        assertCancellationPropagates {
            recording.reportProgress(null, DvrPlaybackProgress.checkpoint(1.seconds))
        }
        assertCancellationPropagates { recording.cutpoints() }
        fake.publish(currentObservation(includeTargets = false))
        assertSame(current, fake.captureCurrentSession())
        assertFalse(live.isCurrent)
        assertSame(RecordingPlaybackAdmission.TargetUnavailable, recording.admission)
        val unavailable = FakeTvheadendSession(currentObservation(includeTargets = false)).apply {
            scriptLivePlaybackSuccess()
            scriptRecordingPlaybackSuccess()
        }
        val unavailableProof = unavailable.captureCurrentSession()
        assertSame(PlaybackBindingResult.TargetUnavailable, unavailable.bindLivePlayback(unavailableProof, ChannelId(1)))
        assertSame(
            PlaybackBindingResult.TargetUnavailable,
            unavailable.bindRecordingPlayback(unavailableProof, DvrEntryId(1)),
        )
    }

    @Test
    @OptIn(at.bernhardberger.tvheadend.sdk.core.TvheadendTestingApi::class)
    fun `completed recording binding declares its played file's video frame duration while current`() {
        val observation = currentObservation().let { base ->
            SessionObservation.create(
                sessionState = base.sessionState,
                channelState = base.channelState,
                epgState = base.epgState,
                dvrState = DvrRepositoryState.Current(DvrSnapshot.create(listOf(DvrEntry.create(
                    DvrEntryId(1),
                    state = DvrEntryState.COMPLETED,
                    files = listOf(at.bernhardberger.tvheadend.sdk.core.DvrRecordingFile.create(
                        fileId = 1,
                        streams = listOf(
                            at.bernhardberger.tvheadend.sdk.core.DvrRecordingStream(
                                at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType.AAC,
                                frameDuration = 21.milliseconds,
                            ),
                            at.bernhardberger.tvheadend.sdk.core.DvrRecordingStream(
                                at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType.H264,
                                frameDuration = 40.milliseconds,
                            ),
                        ),
                    )),
                )))),
            )
        }
        val fake = FakeTvheadendSession(observation)
        val binding = (at.bernhardberger.tvheadend.sdk.core.TvheadendTestResultFactory.boundCompletedRecordingPlayback(
            fake, fake.captureCurrentSession(), DvrEntryId(1),
        ) as PlaybackBindingResult.Bound).binding

        assertEquals(40.milliseconds, binding.declaredVideoFrameDuration)
        fake.replaceGeneration(currentObservation())
        assertNull(binding.declaredVideoFrameDuration)
    }

    @Test
    @OptIn(at.bernhardberger.tvheadend.sdk.core.TvheadendTestingApi::class)
    fun `scripted cutpoints retain authority across suspended responses`() = runTest {
        for (retirement in listOf("none", "generation", "removal", "cancel")) {
            val fake = FakeTvheadendSession(currentObservation())
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val points = at.bernhardberger.tvheadend.sdk.core.DvrCutpointsResult.Available.create(listOf(
                at.bernhardberger.tvheadend.sdk.core.DvrCutpoint(
                    kotlin.time.Duration.ZERO, 10.seconds,
                    at.bernhardberger.tvheadend.sdk.core.DvrCutpointAction.SCENE_MARKER,
                ),
            ))
            val binding = (at.bernhardberger.tvheadend.sdk.core.TvheadendTestResultFactory.boundCompletedRecordingPlayback(
                fake, fake.captureCurrentSession(), DvrEntryId(1),
                cutpoints = { entered.complete(Unit); release.await(); points },
            ) as PlaybackBindingResult.Bound).binding
            val response = async { binding.cutpoints() }
            entered.await()
            when (retirement) {
                "generation" -> fake.replaceGeneration(currentObservation())
                "removal" -> fake.publish(currentObservation(includeTargets = false))
                "cancel" -> response.cancel()
            }
            release.complete(Unit)
            when (retirement) {
                "none" -> assertSame(points, response.await(), retirement)
                "generation" -> assertEquals(
                    at.bernhardberger.tvheadend.sdk.core.DvrCutpointsResult.Failed(
                        TvheadendFailure.OBSERVATION_EXPIRED,
                    ),
                    response.await(),
                    retirement,
                )
                "removal" -> assertEquals(
                    at.bernhardberger.tvheadend.sdk.core.DvrCutpointsResult.Failed(TvheadendFailure.NOT_READY),
                    response.await(),
                    retirement,
                )
                "cancel" -> { response.join(); assertTrue(response.isCancelled) }
            }
        }
    }

    @Test
    @OptIn(at.bernhardberger.tvheadend.sdk.core.TvheadendTestingApi::class)
    fun `cutpoints reject cancellation after a noncancellable script returns`() = runTest {
        val fake = FakeTvheadendSession(currentObservation())
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var scriptReturned = false
        var cancellationCaught = false
        var resultReturned = false
        val binding = (at.bernhardberger.tvheadend.sdk.core.TvheadendTestResultFactory.boundCompletedRecordingPlayback(
            fake, fake.captureCurrentSession(), DvrEntryId(1),
            cutpoints = {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
                scriptReturned = true
                at.bernhardberger.tvheadend.sdk.core.DvrCutpointsResult.Failed(TvheadendFailure.NOT_READY)
            },
        ) as PlaybackBindingResult.Bound).binding
        val response = launch {
            try {
                binding.cutpoints()
                resultReturned = true
            } catch (failure: CancellationException) {
                cancellationCaught = true
                throw failure
            }
        }
        entered.await()
        response.cancel()
        release.complete(Unit)
        response.join()
        assertTrue(scriptReturned)
        assertTrue(cancellationCaught)
        assertFalse(resultReturned)
    }

    @Test
    @OptIn(at.bernhardberger.tvheadend.sdk.core.TvheadendTestingApi::class)
    fun `cutpoint admission rejects stale targets before invoking scripts`() = runTest {
        for (retirement in listOf("generation", "removal", "replacement")) {
            val fake = FakeTvheadendSession(currentObservation())
            var calls = 0
            val current = fake.captureCurrentSession()
            val defaultBinding = (at.bernhardberger.tvheadend.sdk.core.TvheadendTestResultFactory.boundCompletedRecordingPlayback(
                fake, current, DvrEntryId(1),
            ) as PlaybackBindingResult.Bound).binding
            assertEquals(
                at.bernhardberger.tvheadend.sdk.core.DvrCutpointsResult.Failed(TvheadendFailure.NOT_READY),
                defaultBinding.cutpoints(),
            )
            val binding = (at.bernhardberger.tvheadend.sdk.core.TvheadendTestResultFactory.boundCompletedRecordingPlayback(
                fake, current, DvrEntryId(1),
                cutpoints = {
                    calls++
                    at.bernhardberger.tvheadend.sdk.core.DvrCutpointsResult.Failed(TvheadendFailure.NOT_READY)
                },
            ) as PlaybackBindingResult.Bound).binding
            when (retirement) {
                "generation" -> fake.replaceGeneration(currentObservation())
                "removal" -> fake.publish(currentObservation(includeTargets = false))
                "replacement" -> fake.publish(currentObservation(recordingTitle = "replacement"))
            }
            val expected = if (retirement == "generation") {
                at.bernhardberger.tvheadend.sdk.core.DvrCutpointsResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED)
            } else {
                at.bernhardberger.tvheadend.sdk.core.DvrCutpointsResult.Failed(TvheadendFailure.NOT_READY)
            }
            assertEquals(expected, binding.cutpoints(), retirement)
            assertEquals(0, calls, retirement)
        }
    }

    @Test
    @OptIn(at.bernhardberger.tvheadend.sdk.core.TvheadendTestingApi::class)
    fun `cutpoint scripts cannot counterfeit observation expiry`() = runTest {
        val fake = FakeTvheadendSession(currentObservation())
        val binding = (at.bernhardberger.tvheadend.sdk.core.TvheadendTestResultFactory.boundCompletedRecordingPlayback(
            fake, fake.captureCurrentSession(), DvrEntryId(1),
            cutpoints = {
                at.bernhardberger.tvheadend.sdk.core.DvrCutpointsResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED)
            },
        ) as PlaybackBindingResult.Bound).binding
        var rejected = false
        try { binding.cutpoints() } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test
    fun `coverage scripts must carry the admitting proof`() = runTest {
        val fake = FakeTvheadendSession(currentObservation())
        val current = fake.captureCurrentSession()
        val through = Instant.fromEpochSeconds(1)
        val admitted = fake.observation.value
        fake.epgRepository.scriptCoverage(EpgCoverageAcquisitionResult.CoveredEmpty(admitted))

        val covered = fake.epgRepository.acquireCoverage(current, ChannelId(1), through)
        assertSame(admitted, (covered as EpgCoverageAcquisitionResult.CoveredEmpty).observation)
        val batch = fake.epgRepository.acquireCoverageBatch(current, listOf(ChannelId(1)), through)
        assertSame(
            admitted,
            (batch.settlements.single() as EpgCoverageBatchSettlement.CoveredEmpty).observation,
        )

        fake.epgRepository.scriptCoverage(EpgCoverageAcquisitionResult.CoveredWithData(currentObservation()))
        assertRejected { fake.epgRepository.acquireCoverage(current, ChannelId(1), through) }
        assertRejected { fake.epgRepository.acquireCoverageBatch(current, listOf(ChannelId(1)), through) }

        fake.epgRepository.scriptCoverage(EpgCoverageAcquisitionResult.CoveredEmpty(admitted))
        fake.replaceGeneration(currentObservation())
        val replacement = fake.captureCurrentSession()
        assertRejected { fake.epgRepository.acquireCoverage(replacement, ChannelId(1), through) }
    }

    @Test
    fun `scripted coverage batches must match requested channels and proof`() = runTest {
        val fake = FakeTvheadendSession(currentObservation())
        val current = fake.captureCurrentSession()
        val through = Instant.fromEpochSeconds(1)
        val scripted = EpgCoverageBatchResult.create(
            listOf(
                EpgCoverageBatchSettlement.CoveredEmpty(ChannelId(2), fake.observation.value),
                EpgCoverageBatchSettlement.Rejected(ChannelId(1)),
            ),
        )
        fake.epgRepository.scriptCoverageBatch(scripted)

        assertSame(
            scripted,
            fake.epgRepository.acquireCoverageBatch(
                current,
                listOf(ChannelId(2), ChannelId(1), ChannelId(2)),
                through,
            ),
        )
        assertRejected {
            fake.epgRepository.acquireCoverageBatch(current, listOf(ChannelId(1), ChannelId(2)), through)
        }
        assertRejected { fake.epgRepository.acquireCoverageBatch(current, listOf(ChannelId(2)), through) }
        fake.replaceGeneration(currentObservation())
        assertRejected {
            fake.epgRepository.acquireCoverageBatch(
                fake.captureCurrentSession(),
                listOf(ChannelId(2), ChannelId(1)),
                through,
            )
        }
    }

    @Test
    fun `coverage-absent channels settle like production regardless of scripts`() = runTest {
        val fake = FakeTvheadendSession(currentObservation())
        val current = fake.captureCurrentSession()
        val through = Instant.fromEpochSeconds(1)
        fake.epgRepository.scriptCoverage(EpgCoverageAcquisitionResult.CoveredEmpty(fake.observation.value))

        assertSame(
            EpgCoverageAcquisitionResult.Ineligible,
            fake.epgRepository.acquireCoverage(current, ChannelId(3), through),
        )
        val batch = fake.epgRepository.acquireCoverageBatch(current, listOf(ChannelId(3), ChannelId(1)), through)
        assertTrue(batch.settlements[0] is EpgCoverageBatchSettlement.TargetAbsent)
        assertTrue(batch.settlements[1] is EpgCoverageBatchSettlement.CoveredEmpty)

        fake.epgRepository.scriptCoverageBatch(
            EpgCoverageBatchResult.create(listOf(EpgCoverageBatchSettlement.Rejected(ChannelId(3)))),
        )
        assertRejected { fake.epgRepository.acquireCoverageBatch(current, listOf(ChannelId(3)), through) }
        fake.epgRepository.scriptCoverageBatch(
            EpgCoverageBatchResult.create(listOf(EpgCoverageBatchSettlement.TargetAbsent(ChannelId(1)))),
        )
        assertRejected { fake.epgRepository.acquireCoverageBatch(current, listOf(ChannelId(1)), through) }
        val absent = EpgCoverageBatchResult.create(listOf(EpgCoverageBatchSettlement.TargetAbsent(ChannelId(3))))
        fake.epgRepository.scriptCoverageBatch(absent)
        assertSame(absent, fake.epgRepository.acquireCoverageBatch(current, listOf(ChannelId(3)), through))
    }

    @Test
    fun `coverage settles as expired when its generation is retired or replaced after admission`() = runTest {
        val through = Instant.fromEpochSeconds(1)
        val races = listOf<(FakeTvheadendSession) -> Unit>(
            { fake -> fake.retire() },
            { fake -> fake.replaceGeneration(currentObservation(includeTargets = false)) },
        )
        fun racingFake(race: (FakeTvheadendSession) -> Unit) = FakeTvheadendSession(currentObservation()).apply {
            epgRepository.scriptCoverage(EpgCoverageAcquisitionResult.CoveredEmpty(observation.value))
            epgRepository.scriptCoverageBatch(
                EpgCoverageBatchResult.create(
                    listOf(EpgCoverageBatchSettlement.CoveredEmpty(ChannelId(1), observation.value)),
                ),
            )
            afterAdmission = { race(this) }
        }
        races.forEach { race ->
            val single = racingFake(race)
            assertSame(
                EpgCoverageAcquisitionResult.ObservationExpired,
                single.epgRepository.acquireCoverage(single.captureCurrentSession(), ChannelId(1), through),
            )
            val batch = racingFake(race)
            val settlement = batch.epgRepository
                .acquireCoverageBatch(batch.captureCurrentSession(), listOf(ChannelId(1)), through)
                .settlements.single()
            assertTrue(settlement is EpgCoverageBatchSettlement.ObservationExpired)
            assertEquals(ChannelId(1), settlement.channelId)
        }
    }

    @Test
    fun `retirement and failure scripts cannot counterfeit expiration`() = runTest {
        val fake = FakeTvheadendSession(currentObservation())
        val current = fake.captureCurrentSession()
        assertThrows(IllegalArgumentException::class.java) { fake.retire(currentObservation()) }
        assertThrows(IllegalArgumentException::class.java) {
            fake.scriptStreamProfilesFailure(StreamProfilesResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED))
        }
        assertThrows(IllegalArgumentException::class.java) {
            fake.epgRepository.scriptSearchFailure(EpgSearchResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED))
        }
        for (mayHaveApplied in listOf(false, true)) {
            assertThrows(IllegalArgumentException::class.java) {
                fake.dvrRepository.scriptStopEntry(
                    DvrMutationResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, mayHaveApplied),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            fake.artwork.scriptLoad(ArtworkLoadResult.Unavailable(TvheadendFailure.OBSERVATION_EXPIRED))
        }
        assertThrows(IllegalArgumentException::class.java) {
            fake.scriptLivePlaybackFailure(PlaybackBindingResult.ObservationExpired)
        }
        fake.retire()
        assertFalse(fake.isCurrent(current))
        assertEquals(StreamProfilesResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED), fake.getStreamProfiles(current))
    }
}
private fun scheduleRequest(): DvrScheduleRequest =
    DvrScheduleRequest(DvrSchedule.Programme(EventId(1)))

private fun currentObservation(includeTargets: Boolean = true, recordingTitle: String? = null): SessionObservation = SessionObservation.create(
    sessionState = SessionState.Ready(
        ServerCapabilities.create(CapabilityAccess.ALLOWED, CapabilityAccess.ALLOWED),
    ),
    channelState = ChannelRepositoryState.Current(
        ChannelCatalog.create(if (includeTargets) listOf(Channel.create(ChannelId(1))) else emptyList()),
    ),
    epgState = EpgRepositoryState.Current(
        EpgSnapshot.create(
            coverages = if (includeTargets) listOf(EpgCoverage.empty(ChannelId(1)), EpgCoverage.empty(ChannelId(2))) else emptyList(),
        ),
    ),
    dvrState = DvrRepositoryState.Current(
        DvrSnapshot.create(
            if (includeTargets) {
                listOf(DvrEntry.create(DvrEntryId(1), state = DvrEntryState.COMPLETED, title = recordingTitle))
            } else {
                emptyList()
            },
        ),
    ),
)

private suspend fun assertRejected(block: suspend () -> Unit) {
    var rejected = false
    try {
        block()
    } catch (_: IllegalArgumentException) {
        rejected = true
    }
    assertTrue(rejected)
}

private suspend fun assertCancellationPropagates(block: suspend () -> Unit): Unit = coroutineScope {
    val cancellation = CancellationException("fixed fake cancellation")
    var caught: CancellationException? = null
    val call = launch(start = CoroutineStart.UNDISPATCHED) {
        cancel(cancellation)
        try {
            block()
        } catch (failure: CancellationException) {
            caught = failure
            throw failure
        }
    }
    call.join()
    check(caught === cancellation || caught?.cause === cancellation)
}
