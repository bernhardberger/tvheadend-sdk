@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package at.bernhardberger.tvheadend.sdk.media3

import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackExit
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrProgressPolicy
import at.bernhardberger.tvheadend.sdk.core.DvrRecordingFile
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSnapshot
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.StreamProfileId
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.RecordingId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class TvheadendPlaybackCoordinatorTest {
    @Test
    fun `active TS requires explicit start over and unsupported containers remain deferred`() {
        val dvrState = kotlinx.coroutines.flow.MutableStateFlow<DvrRepositoryState>(
            currentDvr(growingEntry()),
        )
        val lease = MutableGrowingLease()

        val startOver = admitRecordingTarget(
            sessionState = readyState(),
            progressCapability = RecordingProgressCapability.SUPPORTED,
            dvrState = dvrState,
            recordingId = RecordingId(7),
            start = RecordingPlaybackStart.START_OVER,
            progressPolicy = DvrProgressPolicy(),
            bindGrowingRecording = { RecordingFileResult.Ok(lease) },
        )
        assertTrue(startOver is RecordingAdmission.Growing)
        assertSame(lease, (startOver as RecordingAdmission.Growing).lease)
        assertSame(
            RecordingAdmission.GrowingResumeUnsupported,
            admitRecordingTarget(
                sessionState = readyState(),
                progressCapability = RecordingProgressCapability.SUPPORTED,
                dvrState = dvrState,
                recordingId = RecordingId(7),
                start = RecordingPlaybackStart.RESUME,
                progressPolicy = DvrProgressPolicy(),
                bindGrowingRecording = { error("Resume refusal must not bind a growing file") },
            ),
        )

        dvrState.value = currentDvr(growingEntry(filePath = "/recording.mkv"))
        assertSame(
            RecordingAdmission.GrowingRecordingDeferred,
            admitRecordingTarget(
                sessionState = readyState(),
                progressCapability = RecordingProgressCapability.SUPPORTED,
                dvrState = dvrState,
                recordingId = RecordingId(7),
                start = RecordingPlaybackStart.START_OVER,
                progressPolicy = DvrProgressPolicy(),
                bindGrowingRecording = { RecordingFileResult.Ok(lease) },
            ),
        )

        dvrState.value = currentDvr(growingEntry(extraFile = true))
        assertSame(
            RecordingAdmission.TargetUnavailable,
            admitRecordingTarget(
                sessionState = readyState(),
                progressCapability = RecordingProgressCapability.SUPPORTED,
                dvrState = dvrState,
                recordingId = RecordingId(7),
                start = RecordingPlaybackStart.START_OVER,
                progressPolicy = DvrProgressPolicy(),
                bindGrowingRecording = { RecordingFileResult.Ok(lease) },
            ),
        )

        dvrState.value = currentDvr(growingEntry())
        assertSame(
            RecordingAdmission.NotReady,
            admitRecordingTarget(
                sessionState = readyState(),
                progressCapability = RecordingProgressCapability.SUPPORTED,
                dvrState = dvrState,
                recordingId = RecordingId(7),
                start = RecordingPlaybackStart.START_OVER,
                progressPolicy = DvrProgressPolicy(),
                bindGrowingRecording = {
                    RecordingFileResult.Failed(RecordingFileFailure.CONNECTION_CHANGED)
                },
            ),
        )
        assertSame(
            RecordingAdmission.TargetUnavailable,
            admitRecordingTarget(
                sessionState = readyState(),
                progressCapability = RecordingProgressCapability.SUPPORTED,
                dvrState = dvrState,
                recordingId = RecordingId(7),
                start = RecordingPlaybackStart.START_OVER,
                progressPolicy = DvrProgressPolicy(),
                bindGrowingRecording = {
                    RecordingFileResult.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
                },
            ),
        )

        dvrState.value = currentDvr(growingEntry(state = DvrEntryState.COMPLETED))
        assertEquals(
            RecordingAdmission.Completed(resumePosition = null),
            admitRecordingTarget(
                sessionState = readyState(),
                progressCapability = RecordingProgressCapability.SUPPORTED,
                dvrState = dvrState,
                recordingId = RecordingId(7),
                start = RecordingPlaybackStart.START_OVER,
                progressPolicy = DvrProgressPolicy(),
                bindGrowingRecording = {
                    RecordingFileResult.Failed(RecordingFileFailure.NOT_SUPPORTED)
                },
            ),
        )
    }

    @Test
    fun `growing lease binding precedes classification and stale bindings fail closed`() {
        val dvrState = kotlinx.coroutines.flow.MutableStateFlow<DvrRepositoryState>(
            currentDvr(growingEntry()),
        )
        val lease = MutableGrowingLease()
        val reclassified = admitRecordingTarget(
            sessionState = readyState(),
            progressCapability = RecordingProgressCapability.SUPPORTED,
            dvrState = dvrState,
            recordingId = RecordingId(7),
            start = RecordingPlaybackStart.START_OVER,
            progressPolicy = DvrProgressPolicy(),
            bindGrowingRecording = {
                dvrState.value = currentDvr(growingEntry(filePath = "/replacement.mkv"))
                RecordingFileResult.Ok(lease)
            },
        )
        assertSame(RecordingAdmission.GrowingRecordingDeferred, reclassified)

        dvrState.value = currentDvr(growingEntry())
        lease.current = false
        assertSame(
            RecordingAdmission.TargetUnavailable,
            admitRecordingTarget(
                sessionState = readyState(),
                progressCapability = RecordingProgressCapability.SUPPORTED,
                dvrState = dvrState,
                recordingId = RecordingId(7),
                start = RecordingPlaybackStart.START_OVER,
                progressPolicy = DvrProgressPolicy(),
                bindGrowingRecording = { RecordingFileResult.Ok(lease) },
            ),
        )
    }

    @Test
    fun `growing fence rejects size and completion regression permanently`() {
        val sizeStates = kotlinx.coroutines.flow.MutableStateFlow<DvrRepositoryState>(
            currentDvr(growingEntry(fileSizeBytes = 1_000)),
        )
        val sizeFence = checkNotNull(
            GrowingRecordingFence.create(growingEntry(fileSizeBytes = 1_000), sizeStates),
        )
        sizeStates.value = currentDvr(growingEntry(fileSizeBytes = 2_000))
        assertSame(GrowingRecordingObservation.RECORDING, sizeFence.observe())
        sizeStates.value = currentDvr(growingEntry(fileSizeBytes = 1_500))
        assertSame(GrowingRecordingObservation.INVALID, sizeFence.observe())
        sizeStates.value = currentDvr(growingEntry(fileSizeBytes = 3_000))
        assertSame(GrowingRecordingObservation.INVALID, sizeFence.observe())

        val completionStates = kotlinx.coroutines.flow.MutableStateFlow<DvrRepositoryState>(
            currentDvr(growingEntry()),
        )
        val completionFence = checkNotNull(
            GrowingRecordingFence.create(growingEntry(), completionStates),
        )
        completionStates.value = currentDvr(growingEntry(state = DvrEntryState.COMPLETED))
        assertSame(GrowingRecordingObservation.COMPLETED, completionFence.observe())
        completionStates.value = currentDvr(growingEntry(fileSizeBytes = 2_000))
        assertSame(GrowingRecordingObservation.INVALID, completionFence.observe())

        val disappearanceStates = kotlinx.coroutines.flow.MutableStateFlow<DvrRepositoryState>(
            currentDvr(growingEntry()),
        )
        val disappearanceFence = checkNotNull(
            GrowingRecordingFence.create(growingEntry(), disappearanceStates),
        )
        disappearanceStates.value = currentDvr()
        assertSame(GrowingRecordingObservation.INVALID, disappearanceFence.observe())
    }

    @Test
    fun `one caller-owned run boundary preserves application player lifetime`() = runTest {
        val fixture = CoordinatorFixture()
        assertEquals(
            PlaybackTargetResult.NOT_RUNNING,
            fixture.coordinator.setLiveTarget(ChannelId(1)),
        )

        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        assertEquals(
            PlaybackTargetResult.STARTED,
            fixture.coordinator.setLiveTarget(ChannelId(1)),
        )
        assertEquals(PlaybackStopResult.STOPPED, fixture.coordinator.stop())
        assertEquals(PlaybackStopResult.ALREADY_STOPPED, fixture.coordinator.stop())
        assertEquals(
            PlaybackShutdownResult.DRAINED,
            fixture.coordinator.shutdown(1.seconds),
        )
        owner.join()

        assertEquals(1, fixture.player.abandonCalls)
        assertFalse(fixture.player.released)
        assertEquals(
            PlaybackTargetResult.SHUT_DOWN,
            fixture.coordinator.setLiveTarget(ChannelId(2)),
        )
        val secondRunFailure = runCatching { fixture.coordinator.run() }.exceptionOrNull()
        assertTrue(secondRunFailure is IllegalStateException)
    }

    @Test
    fun `live target options carry profile and timeshift while legacy overload keeps defaults`() =
        runTest {
            val fixture = CoordinatorFixture()
            val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
            val profileId = StreamProfileId("0123456789abcdef0123456789abcdef")

            assertEquals(
                PlaybackTargetResult.STARTED,
                fixture.coordinator.setLiveTarget(
                    ChannelId(4),
                    LivePlaybackOptions(profileId, 600.seconds),
                ),
            )
            assertEquals(profileId.value, fixture.player.liveOptions?.streamProfileUuid)
            assertEquals(600.seconds, fixture.player.liveOptions?.timeshiftPeriod)

            assertEquals(PlaybackTargetResult.STARTED, fixture.coordinator.setLiveTarget(ChannelId(5)))
            assertEquals(null, fixture.player.liveOptions?.streamProfileUuid)
            assertEquals(Duration.ZERO, fixture.player.liveOptions?.timeshiftPeriod)

            fixture.coordinator.shutdown(1.seconds)
            owner.join()
        }

    @Test
    fun `concurrent target intents are processed in admission order`() = runTest {
        val fixture = CoordinatorFixture()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }

        val live = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.coordinator.setLiveTarget(ChannelId(4))
        }
        val recording = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.coordinator.setRecordingTarget(DvrEntryId(8), RecordingPlaybackStart.START_OVER)
        }
        runCurrent()

        assertEquals(PlaybackTargetResult.STARTED, live.await())
        assertEquals(PlaybackTargetResult.STARTED, recording.await())
        assertEquals(
            listOf("live:4", "recording:8:START_OVER"),
            fixture.player.operations,
        )

        fixture.coordinator.shutdown(1.seconds)
        owner.join()
    }

    @Test
    fun `recording cadence pause and natural end use the sole progress tracker`() = runTest {
        val fixture = CoordinatorFixture()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.player.snapshot = snapshot(position = 0, duration = 100)
        assertEquals(
            PlaybackTargetResult.STARTED,
            fixture.coordinator.setRecordingTarget(DvrEntryId(7)),
        )

        fixture.player.snapshot = snapshot(position = 20, duration = 100)
        fixture.time.tick(30.seconds)
        runCurrent()
        assertProgress(fixture.environment.calls[0], 7, 20, watched = false)
        assertSame(null, fixture.environment.calls[0].growingLease)

        fixture.events.publish(
            PlaybackPlayerEvent(
                token = fixture.player.requireActiveToken(),
                snapshot = snapshot(position = 25, duration = 100),
                paused = true,
            ),
        )
        runCurrent()
        assertProgress(fixture.environment.calls[1], 7, 25, watched = false)

        fixture.events.publish(
            PlaybackPlayerEvent(
                token = fixture.player.requireActiveToken(),
                snapshot = snapshot(position = 100, duration = 100),
                terminalExit = DvrPlaybackExit.NATURAL_END,
            ),
        )
        runCurrent()
        assertProgress(fixture.environment.calls[2], 7, 100, watched = true)

        fixture.events.publish(
            PlaybackPlayerEvent(
                token = fixture.player.requireActiveToken(),
                snapshot = snapshot(position = 100, duration = 100, failed = true),
                terminalExit = DvrPlaybackExit.ERROR,
            ),
        )
        runCurrent()
        assertEquals(3, fixture.environment.calls.size)

        fixture.coordinator.shutdown(1.seconds)
        owner.join()
    }

    @Test
    fun `growing natural end marks watched only after fresh completion and final EOF`() = runTest {
        val fixture = CoordinatorFixture()
        val recording = growingEntry()
        fixture.admitGrowing(recording)
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.player.snapshot = snapshot(position = 0, duration = 100)
        assertEquals(
            PlaybackTargetResult.STARTED,
            fixture.coordinator.setRecordingTarget(DvrEntryId(7), RecordingPlaybackStart.START_OVER),
        )

        fixture.environment.dvrState.value = currentDvr(
            growingEntry(state = DvrEntryState.COMPLETED),
        )
        fixture.events.publish(
            PlaybackPlayerEvent(
                token = fixture.player.requireActiveToken(),
                snapshot = snapshot(position = 100, duration = 100),
                terminalExit = DvrPlaybackExit.NATURAL_END,
                growingFinalEndProven = true,
            ),
        )
        runCurrent()

        assertProgress(fixture.environment.calls.single(), 7, 100, watched = true)
        assertSame(fixture.growingLease, fixture.environment.calls.single().growingLease)
        fixture.coordinator.shutdown(1.seconds)
        owner.join()
    }

    @Test
    fun `growing indexed horizon never marks an orderly replacement watched`() = runTest {
        val fixture = CoordinatorFixture()
        fixture.admitGrowing(growingEntry())
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.player.snapshot = snapshot(position = 0, duration = 100)
        fixture.coordinator.setRecordingTarget(DvrEntryId(7), RecordingPlaybackStart.START_OVER)

        fixture.environment.dvrState.value = currentDvr(
            growingEntry(state = DvrEntryState.COMPLETED),
        )
        fixture.player.snapshot = snapshot(position = 95, duration = 100)
        fixture.coordinator.setLiveTarget(ChannelId(1))
        runCurrent()

        assertProgress(fixture.environment.calls.single(), 7, 95, watched = false)
        fixture.coordinator.shutdown(1.seconds)
        owner.join()
    }

    @Test
    fun `growing natural end without final EOF proof remains unwatched`() = runTest {
        val fixture = CoordinatorFixture()
        fixture.admitGrowing(growingEntry())
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.player.snapshot = snapshot(position = 0, duration = 100)
        fixture.coordinator.setRecordingTarget(DvrEntryId(7), RecordingPlaybackStart.START_OVER)
        fixture.environment.dvrState.value = currentDvr(
            growingEntry(state = DvrEntryState.COMPLETED),
        )

        fixture.events.publish(
            PlaybackPlayerEvent(
                token = fixture.player.requireActiveToken(),
                snapshot = snapshot(position = 100, duration = 100),
                terminalExit = DvrPlaybackExit.NATURAL_END,
            ),
        )
        runCurrent()

        assertProgress(fixture.environment.calls.single(), 7, 100, watched = false)
        fixture.coordinator.shutdown(1.seconds)
        owner.join()
    }

    @Test
    fun `growing final EOF before current completion remains unwatched`() = runTest {
        val fixture = CoordinatorFixture()
        fixture.admitGrowing(growingEntry())
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.player.snapshot = snapshot(position = 0, duration = 100)
        fixture.coordinator.setRecordingTarget(DvrEntryId(7), RecordingPlaybackStart.START_OVER)

        fixture.events.publish(
            PlaybackPlayerEvent(
                token = fixture.player.requireActiveToken(),
                snapshot = snapshot(position = 100, duration = 100),
                terminalExit = DvrPlaybackExit.NATURAL_END,
                growingFinalEndProven = true,
            ),
        )
        runCurrent()
        fixture.environment.dvrState.value = currentDvr(
            growingEntry(state = DvrEntryState.COMPLETED),
        )

        assertProgress(fixture.environment.calls.single(), 7, 100, watched = false)
        fixture.coordinator.shutdown(1.seconds)
        owner.join()
    }

    @Test
    fun `growing identity change invalidates queued and terminal progress`() = runTest {
        val fixture = CoordinatorFixture()
        fixture.admitGrowing(growingEntry())
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.player.snapshot = snapshot(position = 0, duration = 100)
        fixture.coordinator.setRecordingTarget(DvrEntryId(7), RecordingPlaybackStart.START_OVER)

        val blocker = fixture.environment.blockNextReport()
        fixture.events.publish(pause(fixture, 10))
        runCurrent()
        fixture.events.publish(pause(fixture, 20))
        runCurrent()
        fixture.environment.dvrState.value = currentDvr(
            growingEntry(fileId = 2, filePath = "/replacement.ts"),
        )
        blocker.complete(Unit)
        runCurrent()
        fixture.events.publish(
            PlaybackPlayerEvent(
                token = fixture.player.requireActiveToken(),
                snapshot = snapshot(position = 20, duration = 100, failed = true),
                terminalExit = DvrPlaybackExit.ERROR,
            ),
        )
        runCurrent()

        assertEquals(1, fixture.environment.calls.size)
        assertProgress(fixture.environment.calls.single(), 7, 10, watched = false)
        fixture.coordinator.shutdown(1.seconds)
        owner.join()
    }

    @Test
    fun `orderly replacement uses actual duration and errors never mark watched`() = runTest {
        val fixture = CoordinatorFixture()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }

        fixture.player.snapshot = snapshot(position = 95, duration = 100)
        fixture.coordinator.setRecordingTarget(DvrEntryId(1))
        fixture.coordinator.setLiveTarget(ChannelId(1))
        runCurrent()
        assertProgress(fixture.environment.calls[0], 1, 95, watched = true)

        fixture.player.snapshot = snapshot(position = 100, duration = 100, failed = true)
        fixture.coordinator.setRecordingTarget(DvrEntryId(2))
        fixture.player.retirementExit = DvrPlaybackExit.ERROR
        fixture.coordinator.setLiveTarget(ChannelId(2))
        runCurrent()
        assertProgress(fixture.environment.calls[1], 2, 100, watched = false)

        fixture.player.snapshot = snapshot(position = 100, duration = null)
        fixture.player.retirementExit = DvrPlaybackExit.ORDERLY
        fixture.coordinator.setRecordingTarget(DvrEntryId(3))
        fixture.coordinator.setLiveTarget(ChannelId(3))
        runCurrent()
        assertProgress(fixture.environment.calls[2], 3, 100, watched = false)

        fixture.coordinator.shutdown(1.seconds)
        owner.join()
    }

    @Test
    fun `blocked report does not block replacement and shutdown drain is bounded`() = runTest {
        val fixture = CoordinatorFixture()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.player.snapshot = snapshot(position = 0, duration = 100)
        fixture.coordinator.setRecordingTarget(DvrEntryId(7))

        val blocker = fixture.environment.blockNextReport()
        fixture.events.publish(
            PlaybackPlayerEvent(
                token = fixture.player.requireActiveToken(),
                snapshot = snapshot(position = 10, duration = 100),
                paused = true,
            ),
        )
        runCurrent()
        assertEquals(1, fixture.environment.calls.size)

        fixture.player.snapshot = snapshot(position = 80, duration = 100)
        assertEquals(
            PlaybackTargetResult.STARTED,
            withTimeout(1.seconds) { fixture.coordinator.setLiveTarget(ChannelId(2)) },
        )
        assertEquals(
            PlaybackShutdownResult.TIMED_OUT,
            fixture.coordinator.shutdown(Duration.ZERO),
        )
        owner.join()

        assertFalse(blocker.isCompleted)
        assertEquals(1, fixture.environment.cancelledReports)
        assertFalse(fixture.player.released)
    }

    @Test
    fun `latest backward seek wins while one report is in flight`() = runTest {
        val fixture = CoordinatorFixture()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.player.snapshot = snapshot(position = 0, duration = 100)
        fixture.coordinator.setRecordingTarget(DvrEntryId(7))

        val blocker = fixture.environment.blockNextReport()
        fixture.events.publish(pause(fixture, 10))
        runCurrent()
        fixture.events.publish(pause(fixture, 20))
        fixture.events.publish(pause(fixture, 5))
        runCurrent()
        assertEquals(1, fixture.environment.calls.size)

        blocker.complete(Unit)
        runCurrent()
        assertEquals(2, fixture.environment.calls.size)
        assertProgress(fixture.environment.calls[0], 7, 10, watched = false)
        assertProgress(fixture.environment.calls[1], 7, 5, watched = false)

        fixture.coordinator.shutdown(1.seconds)
        owner.join()
    }

    @Test
    fun `generation loss discards pending progress without stopping playback or replay`() = runTest {
        val fixture = CoordinatorFixture()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.player.snapshot = snapshot(position = 0, duration = 100)
        fixture.coordinator.setRecordingTarget(DvrEntryId(7))

        val blocker = fixture.environment.blockNextReport()
        fixture.events.publish(pause(fixture, 10))
        runCurrent()
        fixture.events.publish(pause(fixture, 20))
        runCurrent()
        fixture.environment.sessionState.value = SessionState.Disconnected
        runCurrent()
        assertTrue(fixture.player.hasActiveTarget)

        blocker.complete(Unit)
        runCurrent()
        assertEquals(1, fixture.environment.calls.size)

        fixture.environment.sessionState.value = readyState()
        fixture.environment.progressCapability.value = RecordingProgressCapability.SUPPORTED
        runCurrent()
        assertEquals(1, fixture.environment.calls.size)

        fixture.events.publish(pause(fixture, 30))
        runCurrent()
        assertEquals(2, fixture.environment.calls.size)
        assertProgress(fixture.environment.calls[1], 7, 30, watched = false)

        fixture.coordinator.shutdown(1.seconds)
        owner.join()
    }

    @Test
    fun `growing continuity loss never restarts progress after reconnect`() = runTest {
        val fixture = CoordinatorFixture()
        fixture.admitGrowing(growingEntry())
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.player.snapshot = snapshot(position = 0, duration = 100)
        fixture.coordinator.setRecordingTarget(DvrEntryId(7), RecordingPlaybackStart.START_OVER)

        fixture.events.publish(pause(fixture, 10))
        runCurrent()
        assertEquals(1, fixture.environment.calls.size)

        fixture.environment.sessionState.value = SessionState.Disconnected
        fixture.growingLease.current = false
        runCurrent()
        fixture.environment.sessionState.value = readyState()
        runCurrent()
        fixture.events.publish(pause(fixture, 30))
        fixture.events.publish(
            PlaybackPlayerEvent(
                token = fixture.player.requireActiveToken(),
                snapshot = snapshot(position = 40, duration = 100, failed = true),
                terminalExit = DvrPlaybackExit.ERROR,
            ),
        )
        runCurrent()

        assertEquals(1, fixture.environment.calls.size)
        assertTrue(fixture.player.hasActiveTarget)
        fixture.coordinator.shutdown(1.seconds)
        owner.join()
    }

    @Test
    fun `indeterminate watched report is not retried and does not stop playback`() = runTest {
        val fixture = CoordinatorFixture()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.player.snapshot = snapshot(position = 0, duration = 100)
        fixture.coordinator.setRecordingTarget(DvrEntryId(7))
        fixture.environment.failNextReports = 1

        fixture.events.publish(
            PlaybackPlayerEvent(
                token = fixture.player.requireActiveToken(),
                snapshot = snapshot(position = 100, duration = 100),
                terminalExit = DvrPlaybackExit.NATURAL_END,
            ),
        )
        runCurrent()
        runCurrent()

        assertEquals(1, fixture.environment.calls.size)
        assertProgress(fixture.environment.calls.single(), 7, 100, watched = true)
        assertEquals(
            PlaybackTargetResult.STARTED,
            fixture.coordinator.setLiveTarget(ChannelId(2)),
        )

        fixture.coordinator.shutdown(1.seconds)
        owner.join()
    }

    @Test
    fun `owner cancellation abandons media and cancels progress without a terminal replay`() = runTest {
        val fixture = CoordinatorFixture()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.player.snapshot = snapshot(position = 0, duration = 100)
        fixture.coordinator.setRecordingTarget(DvrEntryId(7))
        val blocker = fixture.environment.blockNextReport()
        fixture.events.publish(pause(fixture, 10))
        runCurrent()

        owner.cancelAndJoin()

        assertFalse(blocker.isCompleted)
        assertEquals(1, fixture.environment.cancelledReports)
        assertEquals(1, fixture.environment.calls.size)
        assertEquals(1, fixture.player.abandonCalls)
        assertFalse(fixture.player.hasActiveTarget)
        assertFalse(fixture.player.released)
        assertEquals(
            PlaybackTargetResult.SHUT_DOWN,
            fixture.coordinator.setLiveTarget(ChannelId(2)),
        )
    }

    @Test
    fun `unexpected command failure completes the current intent before owner teardown`() = runTest {
        val fixture = CoordinatorFixture()
        fixture.player.installFailure = IOException("scripted install failure")
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { fixture.coordinator.run() }.exceptionOrNull()
        }

        assertEquals(
            PlaybackTargetResult.SHUT_DOWN,
            fixture.coordinator.setLiveTarget(ChannelId(2)),
        )
        assertTrue(owner.await() is IOException)
        assertEquals(1, fixture.player.abandonCalls)
        assertFalse(fixture.player.released)
    }

    private fun pause(fixture: CoordinatorFixture, position: Long): PlaybackPlayerEvent =
        PlaybackPlayerEvent(
            token = fixture.player.requireActiveToken(),
            snapshot = snapshot(position = position, duration = 100),
            paused = true,
        )

    private fun assertProgress(
        call: CapturedProgress,
        recordingId: Long,
        position: Long,
        watched: Boolean,
    ) {
        assertEquals(recordingId, call.id.value)
        assertEquals(position.seconds, call.progress.position)
        assertEquals(watched, call.progress.markWatched)
    }
}

private class CoordinatorFixture {
    val environment = FakeCoordinatorEnvironment()
    val events = PlaybackPlayerEventAccumulator()
    val player = FakePlaybackCoordinatorPlayer()
    val growingLease = MutableGrowingLease()
    val time = ManualPlaybackCoordinatorTimeSource()
    val coordinator = TvheadendPlaybackCoordinator(
        environment = environment,
        player = player,
        playerEvents = events,
        progressPolicy = DvrProgressPolicy(),
        onRecoveryRequired = {},
        timeSource = time,
    )

    fun admitGrowing(entry: DvrEntry) {
        environment.dvrState.value = currentDvr(entry)
        growingLease.current = true
        player.recordingAdmission = RecordingAdmission.Growing(
            checkNotNull(GrowingRecordingFence.create(entry, environment.dvrState)),
            growingLease,
        )
    }
}

private data class CapturedProgress(
    val id: DvrEntryId,
    val growingLease: GrowingRecordingFileLease?,
    val progress: DvrPlaybackProgress,
)

private class FakeCoordinatorEnvironment : PlaybackCoordinatorEnvironment {
    override val sessionState = kotlinx.coroutines.flow.MutableStateFlow<SessionState>(readyState())
    override val progressCapability =
        kotlinx.coroutines.flow.MutableStateFlow(RecordingProgressCapability.SUPPORTED)
    override val dvrState = kotlinx.coroutines.flow.MutableStateFlow<DvrRepositoryState>(
        currentDvr(),
    )
    val calls = mutableListOf<CapturedProgress>()
    var cancelledReports = 0
    var failNextReports = 0
    private val blockers = ArrayDeque<CompletableDeferred<Unit>>()

    override fun admitRecording(
        recordingId: RecordingId,
        start: RecordingPlaybackStart,
    ): RecordingAdmission = RecordingAdmission.Completed(null)

    override suspend fun reportProgress(
        recordingId: DvrEntryId,
        growingLease: GrowingRecordingFileLease?,
        progress: DvrPlaybackProgress,
    ) {
        calls += CapturedProgress(recordingId, growingLease, progress)
        if (failNextReports > 0) {
            failNextReports -= 1
            throw IOException("scripted progress failure")
        }
        val blocker = blockers.removeFirstOrNull() ?: return
        try {
            blocker.await()
        } catch (cancellation: CancellationException) {
            cancelledReports += 1
            throw cancellation
        }
    }

    fun blockNextReport(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also(blockers::addLast)
}

private class FakePlaybackCoordinatorPlayer : PlaybackCoordinatorPlayer {
    private var active: FakeTarget? = null
    var snapshot: PlaybackPlayerSnapshot = snapshot(position = 0, duration = null)
    var retirementExit: DvrPlaybackExit = DvrPlaybackExit.ORDERLY
    var growingFinalEndProven = false
    var recordingAdmission: RecordingAdmission.Accepted = RecordingAdmission.Completed(null)
    var installFailure: Exception? = null
    var liveOptions: SubscriptionOptions? = null
    var abandonCalls = 0
    var released = false
    val operations = mutableListOf<String>()

    val hasActiveTarget: Boolean
        get() = active != null

    override suspend fun installLive(
        ticket: PlayerOperationTicket,
        token: PlaybackTargetToken,
        channelId: SubscriptionChannelId,
        options: SubscriptionOptions,
    ): PlaybackPlayerInstallResult {
        installFailure?.let { failure -> throw failure }
        if (!ticket.claim()) return PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.CANCELLED)
        val retirement = retire()
        active = FakeTarget.Live(token)
        liveOptions = options
        operations += "live:${channelId.value}"
        ticket.complete()
        return PlaybackPlayerInstallResult(
            PlaybackPlayerInstallStatus.STARTED,
            retirement.first,
            retirement.second,
        )
    }

    override suspend fun installRecording(
        ticket: PlayerOperationTicket,
        token: PlaybackTargetToken,
        recordingId: RecordingId,
        start: RecordingPlaybackStart,
    ): PlaybackPlayerInstallResult {
        if (!ticket.claim()) return PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.CANCELLED)
        val retirement = retire()
        active = FakeTarget.Recording(token)
        operations += "recording:${recordingId.value}:$start"
        ticket.complete()
        return PlaybackPlayerInstallResult(
            status = PlaybackPlayerInstallStatus.STARTED,
            retiredTarget = retirement.first,
            retiredRecording = retirement.second,
            installedRecording = recordingAdmission,
        )
    }

    override suspend fun stop(ticket: PlayerOperationTicket): PlaybackPlayerStopResult {
        if (!ticket.claim()) return PlaybackPlayerStopResult(cancelled = true)
        val retirement = retire()
        operations += "stop"
        ticket.complete()
        return PlaybackPlayerStopResult(
            retiredTarget = retirement.first,
            retiredRecording = retirement.second,
        )
    }

    override suspend fun snapshot(token: PlaybackTargetToken): PlaybackPlayerSnapshot? =
        snapshot.takeIf { active?.token === token && token.isActive() }

    override suspend fun abandon() {
        abandonCalls += 1
        retire()
    }

    fun requireActiveToken(): PlaybackTargetToken = checkNotNull(active).token

    private fun retire(): Pair<Boolean, RetiredRecordingTarget?> {
        val previous = active ?: return false to null
        previous.token.retire()
        active = null
        return true to if (previous is FakeTarget.Recording) {
            RetiredRecordingTarget(
                previous.token,
                snapshot,
                retirementExit,
                growingFinalEndProven,
            )
        } else {
            null
        }
    }

    private sealed class FakeTarget(open val token: PlaybackTargetToken) {
        data class Live(override val token: PlaybackTargetToken) : FakeTarget(token)
        data class Recording(override val token: PlaybackTargetToken) : FakeTarget(token)
    }
}

private class ManualPlaybackCoordinatorTimeSource : PlaybackCoordinatorTimeSource {
    private var instant = Instant.fromEpochSeconds(0)
    private val requested = Channel<Duration>(Channel.UNLIMITED)
    private val releases = Channel<Unit>(Channel.UNLIMITED)

    override fun now(): Instant = instant

    override suspend fun wait(duration: Duration) {
        requested.send(duration)
        releases.receive()
    }

    suspend fun tick(duration: Duration) {
        assertEquals(duration, requested.receive())
        instant += duration
        releases.send(Unit)
    }
}

private fun snapshot(
    position: Long,
    duration: Long?,
    failed: Boolean = false,
): PlaybackPlayerSnapshot = PlaybackPlayerSnapshot(
    position = position.seconds,
    duration = duration?.seconds,
    playbackState = if (position == duration) androidx.media3.common.Player.STATE_ENDED else
        androidx.media3.common.Player.STATE_READY,
    failed = failed,
)

private fun readyState(): SessionState.Ready = SessionState.Ready(
    ServerCapabilities.create(
        streaming = CapabilityAccess.ALLOWED,
        dvrWrite = CapabilityAccess.ALLOWED,
    ),
)

private fun currentDvr(vararg entries: DvrEntry): DvrRepositoryState.Current =
    DvrRepositoryState.Current(DvrSnapshot.create(entries.toList()))

private fun growingEntry(
    id: Long = 7,
    state: DvrEntryState = DvrEntryState.RECORDING,
    fileId: Long = 1,
    filePath: String = "/recording.ts",
    fileSizeBytes: Long? = 1_000,
    dataSizeBytes: Long? = fileSizeBytes,
    extraFile: Boolean = false,
): DvrEntry = DvrEntry.create(
    id = DvrEntryId(id),
    uuid = "stable-entry",
    path = filePath,
    files = buildList {
        add(
            DvrRecordingFile(
                fileId = fileId,
                path = filePath,
                start = Instant.fromEpochSeconds(1),
                stop = null,
                sizeBytes = fileSizeBytes,
            ),
        )
        if (extraFile) {
            add(
                DvrRecordingFile(
                    fileId = fileId + 1,
                    path = "/rollover.ts",
                    start = Instant.fromEpochSeconds(2),
                    stop = null,
                    sizeBytes = 1,
                ),
            )
        }
    },
    state = state,
    dataSizeBytes = dataSizeBytes,
)

private class MutableGrowingLease(
    var current: Boolean = true,
) : GrowingRecordingFileLease {
    override val isCurrent: Boolean
        get() = current

    override suspend fun open(position: Long): RecordingFileResult<GrowingRecordingFileReader> =
        RecordingFileResult.Failed(RecordingFileFailure.NOT_SUPPORTED)

    override fun toString(): String = "MutableGrowingLease(<redacted>)"
}
