@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.testing.FakePlaybackApi::class,
)

package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.ArtworkContent
import at.bernhardberger.tvheadend.sdk.core.ArtworkFailure
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoadResult
import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.Channel
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrEntryUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrMutationResult
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSchedule
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.DvrSnapshot
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageAcquisitionResult
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
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal class FakeTvheadendSessionTest {
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
        assertSame(StreamProfilesResult.ObservationExpired, second.getStreamProfiles(foreign))
        assertSame(
            EpgSearchResult.ObservationExpired,
            second.epgRepository.search(foreign, EpgSearchRequest.create("news")),
        )
        val expiredCoverage = second.epgRepository.acquireCoverageBatch(
            foreign,
            listOf(ChannelId(2), ChannelId(2), ChannelId(3)),
            Instant.fromEpochSeconds(1),
        )
        assertEquals(listOf(2L, 3L), expiredCoverage.settlements.map { it.channelId.value })
        assertTrue(expiredCoverage.settlements.all { it is EpgCoverageBatchSettlement.ObservationExpired })
        assertSame(
            DvrMutationResult.ObservationExpired,
            second.dvrRepository.scheduleEntry(foreign, scheduleRequest()),
        )
        val artwork = second.artwork.loadArtwork(foreign, ArtworkId(1)) as ArtworkLoadResult.Unavailable
        assertEquals(ArtworkFailure.OBSERVATION_EXPIRED, artwork.failure)
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
        val dvrFailure = DvrMutationResult.ConnectionLimit
        fake.scriptStreamProfilesFailure(StreamProfilesResult.NotSupported)
        fake.epgRepository.scriptSearchFailure(EpgSearchResult.NotSupported)
        fake.epgRepository.scriptCoverage(EpgCoverageAcquisitionResult.Ineligible)
        fake.artwork.scriptLoad(ArtworkLoadResult.Unavailable(ArtworkFailure.NOT_SUPPORTED))
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
        assertSame(StreamProfilesResult.NotSupported, fake.getStreamProfiles(current))
        assertSame(PlaybackBindingResult.TargetUnavailable, fake.bindLivePlayback(current, ChannelId(1)))
        assertSame(PlaybackBindingResult.TargetUnavailable, fake.bindRecordingPlayback(current, DvrEntryId(1)))
        assertSame(EpgSearchResult.NotSupported, fake.epgRepository.search(current, EpgSearchRequest.create("x")))
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
    fun `retirement and failure scripts cannot counterfeit expiration`() = runTest {
        val fake = FakeTvheadendSession(currentObservation())
        val current = fake.captureCurrentSession()
        assertThrows(IllegalArgumentException::class.java) { fake.retire(currentObservation()) }
        assertThrows(IllegalArgumentException::class.java) {
            fake.scriptStreamProfilesFailure(StreamProfilesResult.ObservationExpired)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fake.epgRepository.scriptSearchFailure(EpgSearchResult.ObservationExpired)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fake.dvrRepository.scriptStopEntry(DvrMutationResult.ObservationExpired)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fake.artwork.scriptLoad(ArtworkLoadResult.Unavailable(ArtworkFailure.OBSERVATION_EXPIRED))
        }
        assertThrows(IllegalArgumentException::class.java) {
            fake.scriptLivePlaybackFailure(PlaybackBindingResult.ObservationExpired)
        }
        fake.retire()
        assertFalse(fake.isCurrent(current))
        assertSame(StreamProfilesResult.ObservationExpired, fake.getStreamProfiles(current))
    }
}
private fun scheduleRequest(): DvrScheduleRequest =
    DvrScheduleRequest(DvrSchedule.Programme(EventId(1)))

private fun currentObservation(includeTargets: Boolean = true): SessionObservation = SessionObservation.create(
    sessionState = SessionState.Ready(
        ServerCapabilities.create(CapabilityAccess.ALLOWED, CapabilityAccess.ALLOWED),
    ),
    channelState = ChannelRepositoryState.Current(
        ChannelCatalog.create(if (includeTargets) listOf(Channel.create(ChannelId(1))) else emptyList()),
    ),
    epgState = EpgRepositoryState.Current(EpgSnapshot.create()),
    dvrState = DvrRepositoryState.Current(
        DvrSnapshot.create(
            if (includeTargets) {
                listOf(DvrEntry.create(DvrEntryId(1), state = DvrEntryState.COMPLETED))
            } else {
                emptyList()
            },
        ),
    ),
)

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
