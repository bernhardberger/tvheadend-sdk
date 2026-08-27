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
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionCondition
import at.bernhardberger.tvheadend.sdk.playback.ActiveSubscription
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionDiagnostics
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionIssue
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekInvalidation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionState
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTerminalReason
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTermination
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
    fun `timeshift state and commands use ordered subscription authority without player controls`() =
        runTest {
            val fixture = CoordinatorFixture()
            assertSame(LiveTimeshiftState.Unavailable, fixture.coordinator.timeshiftState.value)
            assertSame(TimeshiftCommandResult.NOT_RUNNING, fixture.coordinator.seekTimeshift((-10).seconds))
            val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
            fixture.coordinator.setLiveTarget(
                ChannelId(4),
                LivePlaybackOptions(timeshiftPeriod = 120.seconds),
            )
            val subscription = FakeTimeshiftSubscription(120.seconds)
            fixture.player.attachTimeshift(subscription)

            var available = fixture.coordinator.timeshiftState.value as LiveTimeshiftState.Available
            assertEquals(120.seconds, available.grantedPeriod)
            assertEquals(null, available.bufferedDuration)
            assertEquals(null, available.positionBehindLive)
            assertEquals(null, available.serverPaused)

            fixture.player.emitTimeshift(
                SubscriptionEvent.Timeshift(
                    full = 0,
                    shift = 40_000_000,
                    start = 10_000_000,
                    end = 100_000_000,
                    speed = null,
                ),
            )
            fixture.player.emitTimeshift(SubscriptionEvent.Speed(0))
            available = fixture.coordinator.timeshiftState.value as LiveTimeshiftState.Available
            assertEquals(90.seconds, available.bufferedDuration)
            assertEquals(40.seconds, available.positionBehindLive)
            assertEquals(true, available.serverPaused)

            assertSame(TimeshiftCommandResult.ACCEPTED, fixture.coordinator.seekTimeshift((-10).seconds))
            assertSame(TimeshiftCommandResult.ACCEPTED, fixture.coordinator.seekTimeshift(5.seconds))
            assertSame(TimeshiftCommandResult.ACCEPTED, fixture.coordinator.seekTimeshift(Duration.ZERO))
            assertSame(TimeshiftCommandResult.ACCEPTED, fixture.coordinator.returnToLive())
            assertSame(TimeshiftCommandResult.ACCEPTED, fixture.coordinator.pauseTimeshift())
            assertSame(TimeshiftCommandResult.ACCEPTED, fixture.coordinator.resumeTimeshift())
            assertEquals(
                listOf((-10).seconds, 5.seconds, Duration.ZERO),
                subscription.seekTargets.filterIsInstance<SubscriptionSeekTarget.Relative>()
                    .map { it.offset },
            )
            assertSame(SubscriptionSeekTarget.Live, subscription.seekTargets.last())
            assertEquals(listOf(0, 100), subscription.speeds)
            assertEquals(listOf("live:4"), fixture.player.operations)

            assertSame(PlaybackStopResult.STOPPED, fixture.coordinator.stop())
            assertSame(LiveTimeshiftState.Unavailable, fixture.coordinator.timeshiftState.value)
            assertSame(TimeshiftCommandResult.UNAVAILABLE, fixture.coordinator.resumeTimeshift())
            fixture.coordinator.shutdown(1.seconds)
            owner.join()
        }

    @Test
    fun `subscription issue follows ordered period retry and remains separate from termination`() =
        runTest {
            val fixture = CoordinatorFixture()
            assertEquals(null, fixture.coordinator.subscriptionIssue.value)
            val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
            fixture.coordinator.setLiveTarget(ChannelId(4))
            val firstAttachment = fixture.player.requireTimeshiftControls()
            fixture.player.emitTimeshift(
                SubscriptionEvent.Started(
                    streams = null,
                    codecMetadata = null,
                    condition = SubscriptionCondition.ERROR_REPORTED,
                    issue = SubscriptionIssue.BAD_SIGNAL,
                ),
            )
            assertEquals(null, fixture.coordinator.subscriptionIssue.value)
            fixture.player.attachTimeshift(FakeTimeshiftSubscription(60.seconds))
            assertSame(SubscriptionIssue.BAD_SIGNAL, fixture.coordinator.subscriptionIssue.value)

            fixture.player.emitTimeshift(
                SubscriptionEvent.Status(
                    SubscriptionCondition.NO_DETAIL,
                    null,
                ),
            )
            assertEquals(null, fixture.coordinator.subscriptionIssue.value)
            fixture.player.emitTimeshift(
                SubscriptionEvent.Stopped(
                    SubscriptionCondition.ERROR_REPORTED,
                    SubscriptionIssue.NO_DISK_SPACE,
                ),
            )
            assertSame(SubscriptionIssue.NO_DISK_SPACE, fixture.coordinator.subscriptionIssue.value)
            assertSame(LiveTimeshiftState.Unavailable, fixture.coordinator.timeshiftState.value)

            fixture.player.replaceTimeshiftPeriod(FakeTimeshiftSubscription(60.seconds))
            assertEquals(null, fixture.coordinator.subscriptionIssue.value)
            firstAttachment.accept(
                SubscriptionEvent.Status(
                    SubscriptionCondition.ERROR_REPORTED,
                    SubscriptionIssue.SCRAMBLED,
                ),
            )
            assertEquals(null, fixture.coordinator.subscriptionIssue.value)

            fixture.player.emitTimeshift(
                SubscriptionEvent.Status(
                    SubscriptionCondition.ERROR_REPORTED,
                    SubscriptionIssue.USER_ACCESS,
                ),
            )
            assertSame(SubscriptionIssue.USER_ACCESS, fixture.coordinator.subscriptionIssue.value)
            val secondAttachment = fixture.player.requireTimeshiftControls()
            val terminalSubscription = FakeTimeshiftSubscription(60.seconds)
            fixture.player.replaceTimeshiftPeriod(terminalSubscription)
            fixture.player.emitTimeshift(
                SubscriptionEvent.Status(
                    SubscriptionCondition.ERROR_REPORTED,
                    SubscriptionIssue.USER_ACCESS,
                ),
            )
            terminalSubscription.mutableState.value = SubscriptionState.Terminal(
                SubscriptionTerminalReason.ConsumerFailed,
            )
            fixture.player.requireTimeshiftControls().terminal(terminalSubscription)
            assertEquals(null, fixture.coordinator.subscriptionIssue.value)
            secondAttachment.accept(
                SubscriptionEvent.Status(
                    SubscriptionCondition.ERROR_REPORTED,
                    SubscriptionIssue.SCRAMBLED,
                ),
            )
            assertEquals(null, fixture.coordinator.subscriptionIssue.value)

            fixture.player.replaceTimeshiftPeriod(FakeTimeshiftSubscription(60.seconds))
            fixture.player.emitTimeshift(
                SubscriptionEvent.Status(
                    SubscriptionCondition.ERROR_REPORTED,
                    SubscriptionIssue.USER_ACCESS,
                ),
            )
            fixture.player.emitTimeshift(
                SubscriptionEvent.Terminated(SubscriptionTermination.GENERATION_LOST),
            )
            assertEquals(null, fixture.coordinator.subscriptionIssue.value)

            fixture.coordinator.shutdown(1.seconds)
            owner.join()
        }

    @Test
    fun `subscription issue is fenced by target replacement stop and shutdown`() = runTest {
        val fixture = CoordinatorFixture()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.coordinator.setLiveTarget(ChannelId(1))
        fixture.player.attachTimeshift(FakeTimeshiftSubscription(60.seconds))
        val staleAttachment = fixture.player.requireTimeshiftControls()
        fixture.player.emitTimeshift(
            SubscriptionEvent.Status(
                SubscriptionCondition.ERROR_REPORTED,
                SubscriptionIssue.SCRAMBLED,
            ),
        )

        fixture.player.liveInstallStatus = PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE
        fixture.player.simulateFailedReplacementPeriodTurnover = true
        assertSame(
            PlaybackTargetResult.PLAYER_UNAVAILABLE,
            fixture.coordinator.setLiveTarget(ChannelId(2)),
        )
        assertSame(SubscriptionIssue.SCRAMBLED, fixture.coordinator.subscriptionIssue.value)

        fixture.player.liveInstallStatus = PlaybackPlayerInstallStatus.STARTED
        assertSame(PlaybackTargetResult.STARTED, fixture.coordinator.setLiveTarget(ChannelId(3)))
        assertEquals(null, fixture.coordinator.subscriptionIssue.value)
        staleAttachment.accept(
            SubscriptionEvent.Status(
                SubscriptionCondition.ERROR_REPORTED,
                SubscriptionIssue.BAD_SIGNAL,
            ),
        )
        assertEquals(null, fixture.coordinator.subscriptionIssue.value)

        fixture.player.attachTimeshift(FakeTimeshiftSubscription(60.seconds))
        fixture.player.emitTimeshift(
            SubscriptionEvent.Status(
                SubscriptionCondition.ERROR_REPORTED,
                SubscriptionIssue.USER_LIMIT,
            ),
        )
        fixture.coordinator.setRecordingTarget(DvrEntryId(7))
        assertEquals(null, fixture.coordinator.subscriptionIssue.value)

        fixture.coordinator.setLiveTarget(ChannelId(4))
        fixture.player.attachTimeshift(FakeTimeshiftSubscription(60.seconds))
        fixture.player.emitTimeshift(
            SubscriptionEvent.Status(
                SubscriptionCondition.ERROR_REPORTED,
                SubscriptionIssue.WEAK_STREAM,
            ),
        )
        fixture.coordinator.stop()
        assertEquals(null, fixture.coordinator.subscriptionIssue.value)

        fixture.coordinator.setLiveTarget(ChannelId(5))
        fixture.player.attachTimeshift(FakeTimeshiftSubscription(60.seconds))
        fixture.player.emitTimeshift(
            SubscriptionEvent.Status(
                SubscriptionCondition.ERROR_REPORTED,
                SubscriptionIssue.NO_FREE_ADAPTER,
            ),
        )
        fixture.coordinator.shutdown(1.seconds)
        owner.join()
        assertEquals(null, fixture.coordinator.subscriptionIssue.value)
    }

    @Test
    fun `replacement rollback keeps newer status observations`() {
        listOf(
            null,
            SubscriptionIssue.BAD_SIGNAL,
        ).forEach { observedIssue ->
            var publishedIssue: SubscriptionIssue? = null
            val token = PlaybackTargetToken()
            val bridge = LiveTimeshiftControlBridge(token, {}, { publishedIssue = it })
            val original = bridge.newAttachment()
            original.bind(FakeTimeshiftSubscription(60.seconds))
            original.accept(
                SubscriptionEvent.Status(
                    SubscriptionCondition.ERROR_REPORTED,
                    SubscriptionIssue.SCRAMBLED,
                ),
            )
            val replacement = bridge.beginIssueReplacement()
            original.detach()
            val restored = bridge.newAttachment()
            restored.accept(
                SubscriptionEvent.Status(
                    SubscriptionCondition.ERROR_REPORTED,
                    observedIssue,
                ),
            )
            restored.bind(FakeTimeshiftSubscription(60.seconds))

            bridge.rollbackIssueReplacement(replacement)

            assertEquals(observedIssue, publishedIssue)
        }
    }

    @Test
    fun `pre-bind replacement termination consumes the restored issue`() {
        fun rollbackBeforeBind(): Triple<
            LiveTimeshiftControlBridge,
            LiveTimeshiftControlBridge.Attachment,
            () -> SubscriptionIssue?,
            > {
            var publishedIssue: SubscriptionIssue? = null
            val token = PlaybackTargetToken()
            val bridge = LiveTimeshiftControlBridge(token, {}, { publishedIssue = it })
            val original = bridge.newAttachment()
            original.bind(FakeTimeshiftSubscription(60.seconds))
            original.accept(
                SubscriptionEvent.Status(
                    SubscriptionCondition.ERROR_REPORTED,
                    SubscriptionIssue.SCRAMBLED,
                ),
            )
            val replacement = bridge.beginIssueReplacement()
            original.detach()
            val restored = bridge.newAttachment()
            bridge.rollbackIssueReplacement(replacement)
            assertSame(SubscriptionIssue.SCRAMBLED, publishedIssue)
            return Triple(bridge, restored) { publishedIssue }
        }

        rollbackBeforeBind().let { (_, attachment, publishedIssue) ->
            attachment.accept(SubscriptionEvent.Terminated(SubscriptionTermination.GENERATION_LOST))
            assertEquals(null, publishedIssue())
        }
        rollbackBeforeBind().let { (_, attachment, publishedIssue) ->
            attachment.accept(
                SubscriptionEvent.Stopped(
                    SubscriptionCondition.ERROR_REPORTED,
                    SubscriptionIssue.BAD_SIGNAL,
                ),
            )
            assertSame(SubscriptionIssue.BAD_SIGNAL, publishedIssue())
        }
        rollbackBeforeBind().let { (_, attachment, publishedIssue) ->
            val terminalSubscription = FakeTimeshiftSubscription(60.seconds)
            terminalSubscription.mutableState.value = SubscriptionState.Terminal(
                SubscriptionTerminalReason.TransportClosed,
            )
            attachment.bind(terminalSubscription)
            assertEquals(null, publishedIssue())
        }
    }

    @Test
    fun `pre-bind terminal event during replacement supersedes the snapshot`() {
        listOf(
            SubscriptionEvent.Terminated(SubscriptionTermination.GENERATION_LOST) to null,
            SubscriptionEvent.Stopped(
                SubscriptionCondition.ERROR_REPORTED,
                SubscriptionIssue.BAD_SIGNAL,
            ) to SubscriptionIssue.BAD_SIGNAL,
        ).forEach { (event, expectedIssue) ->
            var publishedIssue: SubscriptionIssue? = null
            val token = PlaybackTargetToken()
            val bridge = LiveTimeshiftControlBridge(token, {}, { publishedIssue = it })
            val original = bridge.newAttachment()
            original.bind(FakeTimeshiftSubscription(60.seconds))
            original.accept(
                SubscriptionEvent.Status(
                    SubscriptionCondition.ERROR_REPORTED,
                    SubscriptionIssue.SCRAMBLED,
                ),
            )
            val replacement = bridge.beginIssueReplacement()
            original.detach()
            bridge.newAttachment().accept(event)

            bridge.rollbackIssueReplacement(replacement)

            assertEquals(expectedIssue, publishedIssue)
        }
    }

    @Test
    fun `active predecessor payload-free terminal supersedes replacement snapshot`() {
        var publishedIssue: SubscriptionIssue? = null
        val token = PlaybackTargetToken()
        val bridge = LiveTimeshiftControlBridge(token, {}, { publishedIssue = it })
        val subscription = FakeTimeshiftSubscription(60.seconds)
        val original = bridge.newAttachment()
        original.bind(subscription)
        original.accept(
            SubscriptionEvent.Status(
                SubscriptionCondition.ERROR_REPORTED,
                SubscriptionIssue.SCRAMBLED,
            ),
        )
        val replacement = bridge.beginIssueReplacement()
        subscription.mutableState.value = SubscriptionState.Terminal(
            SubscriptionTerminalReason.ConsumerFailed,
        )

        original.terminal(subscription)
        bridge.rollbackIssueReplacement(replacement)

        assertEquals(null, publishedIssue)
    }

    @Test
    fun `timeshift status rejects invalid bounds and caps position at the observed buffer`() = runTest {
        val fixture = CoordinatorFixture()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.coordinator.setLiveTarget(ChannelId(4))
        fixture.player.emitTimeshift(
            SubscriptionEvent.Timeshift(0, 10_000_000, 0, 30_000_000, null),
        )
        fixture.player.attachTimeshift(FakeTimeshiftSubscription(60.seconds))
        var available = fixture.coordinator.timeshiftState.value as LiveTimeshiftState.Available
        assertEquals(30.seconds, available.bufferedDuration)
        assertEquals(10.seconds, available.positionBehindLive)

        fixture.player.emitTimeshift(
            SubscriptionEvent.Timeshift(0, 90_000_000, 20_000_000, 10_000_000, 50),
        )
        available = fixture.coordinator.timeshiftState.value as LiveTimeshiftState.Available
        assertEquals(null, available.bufferedDuration)
        assertEquals(null, available.positionBehindLive)
        assertEquals(null, available.serverPaused)

        fixture.player.emitTimeshift(
            SubscriptionEvent.Timeshift(0, 90_000_000, 10_000_000, 30_000_000, null),
        )
        available = fixture.coordinator.timeshiftState.value as LiveTimeshiftState.Available
        assertEquals(20.seconds, available.bufferedDuration)
        assertEquals(20.seconds, available.positionBehindLive)

        fixture.player.emitTimeshift(
            SubscriptionEvent.Timeshift(0, -1, 10_000_000, 30_000_000, null),
        )
        available = fixture.coordinator.timeshiftState.value as LiveTimeshiftState.Available
        assertEquals(null, available.positionBehindLive)
        fixture.coordinator.shutdown(1.seconds)
        owner.join()
    }

    @Test
    fun `failed replacement preserves timeshift while successful replacement fences stale callbacks`() =
        runTest {
            val fixture = CoordinatorFixture()
            val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
            fixture.coordinator.setLiveTarget(ChannelId(1))
            fixture.player.attachTimeshift(FakeTimeshiftSubscription(60.seconds))
            fixture.player.emitTimeshift(
                SubscriptionEvent.Timeshift(0, 10_000_000, 0, 30_000_000, 100),
            )
            val originalState = fixture.coordinator.timeshiftState.value
            val staleBridge = fixture.player.requireTimeshiftControls()

            fixture.player.liveInstallStatus = PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE
            assertSame(
                PlaybackTargetResult.PLAYER_UNAVAILABLE,
                fixture.coordinator.setLiveTarget(ChannelId(2)),
            )
            assertEquals(originalState, fixture.coordinator.timeshiftState.value)

            fixture.player.liveInstallStatus = PlaybackPlayerInstallStatus.STARTED
            assertSame(PlaybackTargetResult.STARTED, fixture.coordinator.setLiveTarget(ChannelId(3)))
            assertSame(LiveTimeshiftState.Unavailable, fixture.coordinator.timeshiftState.value)
            fixture.player.attachTimeshift(FakeTimeshiftSubscription(90.seconds))
            val replacementState = fixture.coordinator.timeshiftState.value
            staleBridge.accept(SubscriptionEvent.Speed(0))
            assertEquals(replacementState, fixture.coordinator.timeshiftState.value)

            fixture.coordinator.shutdown(1.seconds)
            owner.join()
            assertSame(LiveTimeshiftState.Unavailable, fixture.coordinator.timeshiftState.value)
            assertSame(TimeshiftCommandResult.SHUT_DOWN, fixture.coordinator.pauseTimeshift())
        }

    @Test
    fun `timeshift commands map operation and seek gate failures without transport detail`() = runTest {
        val fixture = CoordinatorFixture()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.coordinator.setLiveTarget(ChannelId(1))
        val subscription = FakeTimeshiftSubscription(60.seconds)
        fixture.player.attachTimeshift(subscription)

        val operationFailures = listOf(
            SubscriptionOperationResult.ServerRejected to TimeshiftCommandResult.SERVER_REJECTED,
            SubscriptionOperationResult.AccessDenied to TimeshiftCommandResult.ACCESS_DENIED,
            SubscriptionOperationResult.ConnectionLimit to TimeshiftCommandResult.CONNECTION_LIMIT,
            SubscriptionOperationResult.Timeout to TimeshiftCommandResult.TIMEOUT,
            SubscriptionOperationResult.TransportUnavailable to
                TimeshiftCommandResult.TRANSPORT_UNAVAILABLE,
            SubscriptionOperationResult.NotSupported to TimeshiftCommandResult.NOT_SUPPORTED,
        )
        operationFailures.forEach { (source, expected) ->
            subscription.speedAction = { source }
            assertSame(expected, fixture.coordinator.pauseTimeshift())
        }

        val seekResults = listOf(
            SubscriptionSeekResult.Rejected to TimeshiftCommandResult.REJECTED,
            SubscriptionSeekResult.NotSeekable to TimeshiftCommandResult.UNAVAILABLE,
            SubscriptionSeekResult.AlreadyPending to TimeshiftCommandResult.ALREADY_PENDING,
            SubscriptionSeekResult.NotAcknowledged to TimeshiftCommandResult.NOT_ACKNOWLEDGED,
            SubscriptionSeekResult.SubscriptionEnded to TimeshiftCommandResult.SUBSCRIPTION_ENDED,
            SubscriptionSeekResult.Invalidated(
                SubscriptionSeekInvalidation.ACKNOWLEDGEMENT_TIMEOUT,
            ) to TimeshiftCommandResult.ACKNOWLEDGEMENT_TIMEOUT,
            SubscriptionSeekResult.Invalidated(
                SubscriptionSeekInvalidation.PENDING_QUEUE_OVERFLOW,
            ) to TimeshiftCommandResult.PENDING_QUEUE_OVERFLOW,
            SubscriptionSeekResult.Invalidated(
                SubscriptionSeekInvalidation.UNCERTAIN_REQUEST_OUTCOME,
            ) to TimeshiftCommandResult.UNCERTAIN_REQUEST_OUTCOME,
            SubscriptionSeekResult.Invalidated(
                SubscriptionSeekInvalidation.UNRECOGNIZED_ACKNOWLEDGEMENT,
            ) to TimeshiftCommandResult.UNRECOGNIZED_ACKNOWLEDGEMENT,
            SubscriptionSeekResult.Invalidated(
                SubscriptionSeekInvalidation.RESUMED_SEGMENT_UNANCHORABLE,
            ) to TimeshiftCommandResult.RESUMED_SEGMENT_UNANCHORABLE,
        )
        seekResults.forEach { (source, expected) ->
            val currentSubscription = FakeTimeshiftSubscription(60.seconds)
            currentSubscription.seekAction = { source }
            fixture.player.replaceTimeshiftPeriod(currentSubscription)
            assertSame(expected, fixture.coordinator.seekTimeshift((-1).seconds))
        }

        fixture.coordinator.shutdown(1.seconds)
        owner.join()
    }

    @Test
    fun `claimed seek survives caller cancellation but queued command performs no work`() = runTest {
        val fixture = CoordinatorFixture()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.coordinator.setLiveTarget(ChannelId(1))
        val subscription = FakeTimeshiftSubscription(60.seconds)
        fixture.player.attachTimeshift(subscription)
        val seekRelease = CompletableDeferred<SubscriptionSeekResult>()
        subscription.seekAction = { seekRelease.await() }

        val claimed = async { fixture.coordinator.seekTimeshift((-10).seconds) }
        runCurrent()
        assertEquals(
            (-10).seconds,
            (subscription.seekTargets.single() as SubscriptionSeekTarget.Relative).offset,
        )
        claimed.cancel()
        assertTrue(claimed.isCancelled)
        seekRelease.complete(SubscriptionSeekResult.Accepted)
        runCurrent()

        val installEntered = CompletableDeferred<Unit>()
        val installRelease = CompletableDeferred<Unit>()
        fixture.player.liveInstallEntered = installEntered
        fixture.player.liveInstallRelease = installRelease
        val replacement = async { fixture.coordinator.setLiveTarget(ChannelId(2)) }
        installEntered.await()
        val queued = async { fixture.coordinator.pauseTimeshift() }
        runCurrent()
        queued.cancel()
        installRelease.complete(Unit)
        assertSame(PlaybackTargetResult.STARTED, replacement.await())
        assertTrue(queued.isCancelled)
        assertTrue(subscription.speeds.isEmpty())

        owner.cancelAndJoin()
        assertSame(LiveTimeshiftState.Unavailable, fixture.coordinator.timeshiftState.value)
    }

    @Test
    fun `operation cancellation reaches the command caller and tears down the actor owner`() =
        runTest {
            suspend fun verify(
                configure: (FakeTimeshiftSubscription, CancellationException) -> Unit,
                command: suspend (TvheadendPlaybackCoordinator) -> TimeshiftCommandResult,
            ) {
                val fixture = CoordinatorFixture()
                val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
                fixture.coordinator.setLiveTarget(ChannelId(1))
                val subscription = FakeTimeshiftSubscription(60.seconds)
                fixture.player.attachTimeshift(subscription)
                val cancellation = CancellationException("scripted operation cancellation")
                configure(subscription, cancellation)

                val caught = try {
                    command(fixture.coordinator)
                    null
                } catch (failure: CancellationException) {
                    failure
                }
                assertEquals(cancellation.message, caught?.message)
                assertTrue(caught === cancellation || caught?.cause === cancellation)
                owner.join()
                assertTrue(owner.isCancelled)
                assertSame(LiveTimeshiftState.Unavailable, fixture.coordinator.timeshiftState.value)
            }

            verify(
                configure = { subscription, cancellation ->
                    subscription.seekAction = { throw cancellation }
                },
                command = { coordinator -> coordinator.seekTimeshift((-1).seconds) },
            )
            verify(
                configure = { subscription, cancellation ->
                    subscription.speedAction = { throw cancellation }
                },
                command = { coordinator -> coordinator.pauseTimeshift() },
            )
        }

    @Test
    fun `period attachments supersede stale periods and terminal state detaches controls`() = runTest {
        val token = PlaybackTargetToken()
        val published = mutableListOf<LiveTimeshiftState>()
        val bridge = LiveTimeshiftControlBridge(token, published::add)
        val first = bridge.newAttachment()
        val firstSubscription = FakeTimeshiftSubscription(60.seconds)
        first.bind(firstSubscription)
        first.accept(SubscriptionEvent.Timeshift(0, 5_000_000, 0, 20_000_000, 100))
        val second = bridge.newAttachment()
        val secondSubscription = FakeTimeshiftSubscription(90.seconds)
        second.accept(SubscriptionEvent.Timeshift(0, 7_000_000, 0, 30_000_000, 0))
        second.bind(secondSubscription)

        var available = published.last() as LiveTimeshiftState.Available
        assertEquals(90.seconds, available.grantedPeriod)
        assertEquals(30.seconds, available.bufferedDuration)
        assertEquals(true, available.serverPaused)
        first.accept(SubscriptionEvent.Speed(0))
        first.detach()
        assertEquals(available, published.last())
        assertEquals(0, firstSubscription.closeCount)

        secondSubscription.mutableState.value = SubscriptionState.Terminal(
            SubscriptionTerminalReason.ConsumerFailed,
        )
        second.terminal(secondSubscription)
        assertSame(LiveTimeshiftState.Unavailable, published.last())
        assertSame(null, bridge.setSpeed(100))
        assertEquals(0, secondSubscription.closeCount)

        val third = bridge.newAttachment()
        val thirdSubscription = FakeTimeshiftSubscription(120.seconds)
        third.bind(thirdSubscription)
        available = published.last() as LiveTimeshiftState.Available
        assertEquals(120.seconds, available.grantedPeriod)
        thirdSubscription.seekAction = {
            SubscriptionSeekResult.Invalidated(
                SubscriptionSeekInvalidation.ACKNOWLEDGEMENT_TIMEOUT,
            )
        }
        assertTrue(bridge.seek(SubscriptionSeekTarget.Live) is SubscriptionSeekResult.Invalidated)
        assertSame(LiveTimeshiftState.Unavailable, published.last())
        second.accept(SubscriptionEvent.Speed(100))
        second.detach()
        assertSame(LiveTimeshiftState.Unavailable, published.last())
        third.detach()
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
    var liveInstallStatus: PlaybackPlayerInstallStatus = PlaybackPlayerInstallStatus.STARTED
    var simulateFailedReplacementPeriodTurnover = false
    var liveInstallEntered: CompletableDeferred<Unit>? = null
    var liveInstallRelease: CompletableDeferred<Unit>? = null
    private var timeshiftControls: LiveTimeshiftControlBridge? = null
    private var timeshiftAttachment: LiveTimeshiftControlBridge.Attachment? = null
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
        timeshiftControls: LiveTimeshiftControlBridge,
    ): PlaybackPlayerInstallResult {
        installFailure?.let { failure -> throw failure }
        if (!ticket.claim()) return PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.CANCELLED)
        liveInstallEntered?.complete(Unit)
        liveInstallRelease?.await()
        liveInstallEntered = null
        liveInstallRelease = null
        if (liveInstallStatus != PlaybackPlayerInstallStatus.STARTED) {
            if (simulateFailedReplacementPeriodTurnover) {
                val currentControls = checkNotNull(this.timeshiftControls)
                val replacement = currentControls.beginIssueReplacement()
                checkNotNull(timeshiftAttachment).detach()
                val restoredSubscription = FakeTimeshiftSubscription(60.seconds)
                timeshiftAttachment = currentControls.newAttachment()
                checkNotNull(timeshiftAttachment).bind(restoredSubscription)
                currentControls.rollbackIssueReplacement(replacement)
                simulateFailedReplacementPeriodTurnover = false
            }
            ticket.complete()
            return PlaybackPlayerInstallResult(liveInstallStatus)
        }
        val retirement = retire()
        active = FakeTarget.Live(token)
        this.timeshiftControls = timeshiftControls
        timeshiftAttachment = timeshiftControls.newAttachment()
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

    fun requireTimeshiftControls(): LiveTimeshiftControlBridge.Attachment =
        checkNotNull(timeshiftAttachment)

    fun attachTimeshift(subscription: ActiveSubscription) {
        requireTimeshiftControls().bind(subscription)
    }

    fun replaceTimeshiftPeriod(subscription: ActiveSubscription) {
        timeshiftAttachment = checkNotNull(timeshiftControls).newAttachment()
        attachTimeshift(subscription)
    }

    fun emitTimeshift(event: SubscriptionEvent) {
        requireTimeshiftControls().accept(event)
    }

    private fun retire(): Pair<Boolean, RetiredRecordingTarget?> {
        val previous = active ?: return false to null
        previous.token.retire()
        active = null
        if (previous is FakeTarget.Live) timeshiftControls = null
        if (previous is FakeTarget.Live) timeshiftAttachment = null
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

private class FakeTimeshiftSubscription(
    override val grantedTimeshiftPeriod: Duration?,
) : ActiveSubscription {
    val seekTargets = mutableListOf<SubscriptionSeekTarget>()
    val speeds = mutableListOf<Int>()
    var seekAction: suspend (SubscriptionSeekTarget) -> SubscriptionSeekResult = {
        SubscriptionSeekResult.Accepted
    }
    var speedAction: suspend (Int) -> SubscriptionOperationResult<Unit> = {
        SubscriptionOperationResult.Ok(Unit)
    }
    val mutableState = kotlinx.coroutines.flow.MutableStateFlow<SubscriptionState>(
        SubscriptionState.Starting,
    )
    var closeCount = 0

    override val state: kotlinx.coroutines.flow.StateFlow<SubscriptionState> = mutableState
    override val diagnostics: kotlinx.coroutines.flow.StateFlow<SubscriptionDiagnostics>
        get() = error("Timeshift diagnostics are not used by this coordinator fixture")

    override suspend fun seek(target: SubscriptionSeekTarget): SubscriptionSeekResult {
        seekTargets += target
        return seekAction(target)
    }

    override suspend fun setSpeed(speed: Int): SubscriptionOperationResult<Unit> {
        speeds += speed
        return speedAction(speed)
    }

    override suspend fun close(): at.bernhardberger.tvheadend.sdk.playback.SubscriptionCloseResult {
        closeCount += 1
        return at.bernhardberger.tvheadend.sdk.playback.SubscriptionCloseResult.CLOSED
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
