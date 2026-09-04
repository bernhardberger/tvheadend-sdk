@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.testing.FakePlaybackApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package at.bernhardberger.tvheadend.sdk.media3

import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.Channel as TvheadendChannel
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackExit
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrProgressPolicy
import at.bernhardberger.tvheadend.sdk.core.DvrProgressResult
import at.bernhardberger.tvheadend.sdk.core.DvrRecordingFile
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSnapshot
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.StreamProfileId
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.LiveFrontendState
import at.bernhardberger.tvheadend.sdk.playback.LiveSubscriptionDiagnostics
import at.bernhardberger.tvheadend.sdk.playback.LiveSubscriptionSource
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFile
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionCondition
import at.bernhardberger.tvheadend.sdk.playback.ActiveSubscription
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionDiagnostics
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionIssue
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekInvalidation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionState
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTerminalReason
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTermination
import at.bernhardberger.tvheadend.sdk.testing.FakeSessionCall
import at.bernhardberger.tvheadend.sdk.testing.FakeTvheadendSession
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class TvheadendPlaybackCoordinatorTest {
    @Test
    fun `public playback outcomes expose stable non-exhaustive classifications`() {
        val timeshiftCategories = mapOf(
            TimeshiftCommandResult.ACCEPTED to emptySet(),
            TimeshiftCommandResult.REJECTED to emptySet(),
            TimeshiftCommandResult.UNAVAILABLE to setOf(PlaybackOutcomeCategory.TRANSIENT),
            TimeshiftCommandResult.ALREADY_PENDING to setOf(PlaybackOutcomeCategory.TRANSIENT),
            TimeshiftCommandResult.NOT_ACKNOWLEDGED to setOf(PlaybackOutcomeCategory.TRANSIENT),
            TimeshiftCommandResult.ACKNOWLEDGEMENT_TIMEOUT to setOf(
                PlaybackOutcomeCategory.TERMINAL,
                PlaybackOutcomeCategory.UNCERTAIN,
            ),
            TimeshiftCommandResult.PENDING_QUEUE_OVERFLOW to setOf(
                PlaybackOutcomeCategory.TERMINAL,
                PlaybackOutcomeCategory.UNCERTAIN,
            ),
            TimeshiftCommandResult.UNCERTAIN_REQUEST_OUTCOME to setOf(
                PlaybackOutcomeCategory.TERMINAL,
                PlaybackOutcomeCategory.UNCERTAIN,
            ),
            TimeshiftCommandResult.UNRECOGNIZED_ACKNOWLEDGEMENT to setOf(
                PlaybackOutcomeCategory.TERMINAL,
                PlaybackOutcomeCategory.UNCERTAIN,
            ),
            TimeshiftCommandResult.RESUMED_SEGMENT_UNANCHORABLE to
                setOf(PlaybackOutcomeCategory.TERMINAL),
            TimeshiftCommandResult.SUBSCRIPTION_ENDED to setOf(
                PlaybackOutcomeCategory.TERMINAL,
                PlaybackOutcomeCategory.UNCERTAIN,
            ),
            TimeshiftCommandResult.SERVER_REJECTED to emptySet(),
            TimeshiftCommandResult.ACCESS_DENIED to
                setOf(PlaybackOutcomeCategory.CONFIGURATION_OR_ACCESS),
            TimeshiftCommandResult.CONNECTION_LIMIT to setOf(PlaybackOutcomeCategory.TRANSIENT),
            TimeshiftCommandResult.TIMEOUT to setOf(
                PlaybackOutcomeCategory.TRANSIENT,
                PlaybackOutcomeCategory.UNCERTAIN,
            ),
            TimeshiftCommandResult.TRANSPORT_UNAVAILABLE to
                setOf(PlaybackOutcomeCategory.TRANSIENT),
            TimeshiftCommandResult.NOT_SUPPORTED to setOf(PlaybackOutcomeCategory.UNSUPPORTED),
            TimeshiftCommandResult.NOT_RUNNING to setOf(PlaybackOutcomeCategory.TRANSIENT),
            TimeshiftCommandResult.SHUT_DOWN to setOf(PlaybackOutcomeCategory.TERMINAL),
        )
        val timeshiftDispositions = mapOf(
            TimeshiftCommandResult.ACCEPTED to TimeshiftCommandDisposition.ACCEPTED,
            TimeshiftCommandResult.REJECTED to TimeshiftCommandDisposition.NOT_ACCEPTED,
            TimeshiftCommandResult.UNAVAILABLE to TimeshiftCommandDisposition.NOT_ACCEPTED,
            TimeshiftCommandResult.ALREADY_PENDING to TimeshiftCommandDisposition.NOT_ACCEPTED,
            TimeshiftCommandResult.NOT_ACKNOWLEDGED to TimeshiftCommandDisposition.NOT_ACCEPTED,
            TimeshiftCommandResult.ACKNOWLEDGEMENT_TIMEOUT to
                TimeshiftCommandDisposition.UNCONFIRMED,
            TimeshiftCommandResult.PENDING_QUEUE_OVERFLOW to
                TimeshiftCommandDisposition.UNCONFIRMED,
            TimeshiftCommandResult.UNCERTAIN_REQUEST_OUTCOME to
                TimeshiftCommandDisposition.UNCONFIRMED,
            TimeshiftCommandResult.UNRECOGNIZED_ACKNOWLEDGEMENT to
                TimeshiftCommandDisposition.UNCONFIRMED,
            TimeshiftCommandResult.RESUMED_SEGMENT_UNANCHORABLE to
                TimeshiftCommandDisposition.ACCEPTED,
            TimeshiftCommandResult.SUBSCRIPTION_ENDED to TimeshiftCommandDisposition.UNCONFIRMED,
            TimeshiftCommandResult.SERVER_REJECTED to TimeshiftCommandDisposition.NOT_ACCEPTED,
            TimeshiftCommandResult.ACCESS_DENIED to TimeshiftCommandDisposition.NOT_ACCEPTED,
            TimeshiftCommandResult.CONNECTION_LIMIT to TimeshiftCommandDisposition.NOT_ACCEPTED,
            TimeshiftCommandResult.TIMEOUT to TimeshiftCommandDisposition.UNCONFIRMED,
            TimeshiftCommandResult.TRANSPORT_UNAVAILABLE to
                TimeshiftCommandDisposition.NOT_ACCEPTED,
            TimeshiftCommandResult.NOT_SUPPORTED to TimeshiftCommandDisposition.NOT_ACCEPTED,
            TimeshiftCommandResult.NOT_RUNNING to TimeshiftCommandDisposition.NOT_ACCEPTED,
            TimeshiftCommandResult.SHUT_DOWN to TimeshiftCommandDisposition.NOT_ACCEPTED,
        )
        val publicTimeshiftValues = TimeshiftCommandResult::class.java.fields
            .filter { field -> field.type == TimeshiftCommandResult::class.java }
            .mapTo(mutableSetOf()) { field -> field.get(null) as TimeshiftCommandResult }
        assertEquals(publicTimeshiftValues, timeshiftCategories.keys)
        assertEquals(publicTimeshiftValues, timeshiftDispositions.keys)
        timeshiftCategories.forEach { (result, categories) ->
            assertEquals(categories, result.categories)
            assertEquals(timeshiftDispositions.getValue(result), result.disposition)
            assertEquals(
                result.disposition == TimeshiftCommandDisposition.ACCEPTED,
                result.isAccepted,
            )
            assertEquals(PlaybackOutcomeCategory.TRANSIENT in categories, result.isTransient)
            assertEquals(PlaybackOutcomeCategory.TERMINAL in categories, result.isTerminal)
            assertEquals(PlaybackOutcomeCategory.UNSUPPORTED in categories, result.isUnsupported)
            assertEquals(
                PlaybackOutcomeCategory.CONFIGURATION_OR_ACCESS in categories,
                result.isConfigurationOrAccessRelated,
            )
            assertEquals(PlaybackOutcomeCategory.UNCERTAIN in categories, result.isOutcomeUncertain)
        }
        assertEquals("ACKNOWLEDGEMENT_TIMEOUT", TimeshiftCommandResult.ACKNOWLEDGEMENT_TIMEOUT.toString())
        assertNotEquals(TimeshiftCommandResult.REJECTED, TimeshiftCommandResult.SERVER_REJECTED)

        val targetCategories = mapOf(
            PlaybackTargetResult.STARTED to emptySet(),
            PlaybackTargetResult.NOT_RUNNING to setOf(PlaybackOutcomeCategory.TRANSIENT),
            PlaybackTargetResult.SHUT_DOWN to setOf(PlaybackOutcomeCategory.TERMINAL),
            PlaybackTargetResult.NOT_READY to setOf(PlaybackOutcomeCategory.TRANSIENT),
            PlaybackTargetResult.RECORDING_PROGRESS_UNSUPPORTED to
                setOf(PlaybackOutcomeCategory.UNSUPPORTED),
            PlaybackTargetResult.TARGET_UNAVAILABLE to emptySet(),
            PlaybackTargetResult.GROWING_RECORDING_RESUME_UNSUPPORTED to
                setOf(PlaybackOutcomeCategory.UNSUPPORTED),
            PlaybackTargetResult.GROWING_RECORDING_DEFERRED to setOf(
                PlaybackOutcomeCategory.TRANSIENT,
                PlaybackOutcomeCategory.UNSUPPORTED,
            ),
            PlaybackTargetResult.PLAYER_UNAVAILABLE to setOf(PlaybackOutcomeCategory.TRANSIENT),
        )
        val publicTargetValues = PlaybackTargetResult::class.java.fields
            .filter { field -> field.type == PlaybackTargetResult::class.java }
            .mapTo(mutableSetOf()) { field -> field.get(null) as PlaybackTargetResult }
        assertEquals(publicTargetValues, targetCategories.keys)
        targetCategories.forEach { (result, categories) ->
            assertEquals(categories, result.categories)
            assertEquals(result === PlaybackTargetResult.STARTED, result.isStarted)
            assertEquals(PlaybackOutcomeCategory.TRANSIENT in categories, result.isTransient)
            assertEquals(PlaybackOutcomeCategory.TERMINAL in categories, result.isTerminal)
            assertEquals(PlaybackOutcomeCategory.UNSUPPORTED in categories, result.isUnsupported)
            assertEquals(false, result.isConfigurationOrAccessRelated)
            assertEquals(false, result.isOutcomeUncertain)
        }
        assertEquals(
            "GROWING_RECORDING_RESUME_UNSUPPORTED",
            PlaybackTargetResult.GROWING_RECORDING_RESUME_UNSUPPORTED.toString(),
        )
        assertNotEquals(
            PlaybackTargetResult.NOT_RUNNING,
            PlaybackTargetResult.PLAYER_UNAVAILABLE,
        )
    }

    @Test
    fun `active TS requires explicit start over and unsupported containers remain deferred`() {
        val lease = MutableGrowingLease()
        val target = TestCoordinatorRecordingTarget(
            startedGrowing = true,
            admissionState = CoordinatorRecordingAdmission.GrowingStartOverOnly(
                RecordingProgressCapability.SUPPORTED,
            ),
            growingResult = RecordingFileResult.Ok(lease),
        )

        val startOver = admitRecordingTarget(
            target = target,
            start = RecordingPlaybackStart.START_OVER,
        )
        assertTrue(startOver is RecordingAdmission.Growing)
        assertSame(lease, (startOver as RecordingAdmission.Growing).lease)
        assertSame(
            RecordingAdmission.GrowingResumeUnsupported,
            admitRecordingTarget(
                target = target,
                start = RecordingPlaybackStart.RESUME,
            ),
        )

        target.admissionState = CoordinatorRecordingAdmission.GrowingDeferred
        assertSame(
            RecordingAdmission.GrowingRecordingDeferred,
            admitRecordingTarget(
                target = target,
                start = RecordingPlaybackStart.START_OVER,
            ),
        )

        target.admissionState = CoordinatorRecordingAdmission.TargetUnavailable
        assertSame(
            RecordingAdmission.TargetUnavailable,
            admitRecordingTarget(
                target = target,
                start = RecordingPlaybackStart.START_OVER,
            ),
        )

        target.admissionState = CoordinatorRecordingAdmission.GrowingStartOverOnly(
            RecordingProgressCapability.SUPPORTED,
        )
        target.growingResult = RecordingFileResult.Failed(RecordingFileFailure.CONNECTION_CHANGED)
        assertSame(
            RecordingAdmission.NotReady,
            admitRecordingTarget(
                target = target,
                start = RecordingPlaybackStart.START_OVER,
            ),
        )
        target.growingResult = RecordingFileResult.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        assertSame(
            RecordingAdmission.TargetUnavailable,
            admitRecordingTarget(
                target = target,
                start = RecordingPlaybackStart.START_OVER,
            ),
        )

        target.admissionState = CoordinatorRecordingAdmission.Completed(
            resumePosition = null,
            progressCapability = RecordingProgressCapability.SUPPORTED,
        )
        assertEquals(
            RecordingAdmission.Completed(resumePosition = null),
            admitRecordingTarget(
                target = target,
                start = RecordingPlaybackStart.START_OVER,
            ),
        )
    }

    @Test
    fun `growing lease binding precedes classification and stale bindings fail closed`() {
        val lease = MutableGrowingLease()
        val target = TestCoordinatorRecordingTarget(
            startedGrowing = true,
            admissionState = CoordinatorRecordingAdmission.GrowingStartOverOnly(
                RecordingProgressCapability.SUPPORTED,
            ),
            growingResult = RecordingFileResult.Ok(lease),
        )
        target.bindGrowingAction = {
            target.admissionState = CoordinatorRecordingAdmission.GrowingDeferred
            RecordingFileResult.Ok(lease)
        }
        val reclassified = admitRecordingTarget(
            target = target,
            start = RecordingPlaybackStart.START_OVER,
        )
        assertSame(RecordingAdmission.GrowingRecordingDeferred, reclassified)

        target.admissionState = CoordinatorRecordingAdmission.GrowingStartOverOnly(
            RecordingProgressCapability.SUPPORTED,
        )
        target.bindGrowingAction = { RecordingFileResult.Ok(lease) }
        lease.current = false
        assertSame(
            RecordingAdmission.TargetUnavailable,
            admitRecordingTarget(
                target = target,
                start = RecordingPlaybackStart.START_OVER,
            ),
        )
    }

    @Test
    fun `completed recordings remain playable without progress support`() {
        listOf(
            RecordingProgressCapability.UNKNOWN,
            RecordingProgressCapability.UNSUPPORTED,
        ).forEach { capability ->
            val target = TestCoordinatorRecordingTarget(
                admissionState = CoordinatorRecordingAdmission.Completed(
                    resumePosition = 30.seconds,
                    progressCapability = capability,
                ),
            )

            assertEquals(
                RecordingAdmission.Completed(
                    resumePosition = null,
                    progressReportingSupported = false,
                ),
                admitRecordingTarget(target, RecordingPlaybackStart.RESUME),
            )
        }
    }

    @Test
    fun `one-call live workflow preserves binding and coordinator result domains`() = runTest {
        val staleFixture = CoordinatorFixture()
        val staleSession = liveSession()
        val staleProof = staleSession.captureCurrentSession()
        staleSession.replaceGeneration(currentLiveObservation())
        assertSame(
            LivePlaybackTargetResult.ObservationExpired,
            staleFixture.coordinator.setLiveTarget(staleSession, staleProof, ChannelId(1)),
        )
        assertEquals(listOf(FakeSessionCall.BIND_LIVE_PLAYBACK), staleSession.calls)
        assertTrue(staleFixture.player.operations.isEmpty())

        val unavailableFixture = CoordinatorFixture()
        val unavailableSession = liveSession().apply {
            scriptLivePlaybackFailure(at.bernhardberger.tvheadend.sdk.core.PlaybackBindingResult.TargetUnavailable)
        }
        assertSame(
            LivePlaybackTargetResult.TargetUnavailable,
            unavailableFixture.coordinator.setLiveTarget(
                unavailableSession,
                unavailableSession.captureCurrentSession(),
                ChannelId(1),
            ),
        )
        assertEquals(listOf(FakeSessionCall.BIND_LIVE_PLAYBACK), unavailableSession.calls)
        assertTrue(unavailableFixture.player.operations.isEmpty())

        val notRunningFixture = CoordinatorFixture()
        val lifecycleSession = liveSession()
        val current = lifecycleSession.captureCurrentSession()
        val notRunning = notRunningFixture.coordinator.setLiveTarget(
            lifecycleSession,
            current,
            ChannelId(1),
        ) as LivePlaybackTargetResult.Bound
        assertSame(PlaybackTargetResult.NOT_RUNNING, notRunning.result)
        assertTrue(notRunningFixture.player.operations.isEmpty())

        val owner = launch(start = CoroutineStart.UNDISPATCHED) { notRunningFixture.coordinator.run() }
        assertSame(PlaybackShutdownResult.DRAINED, notRunningFixture.coordinator.shutdown(1.seconds))
        owner.join()
        val shutDown = notRunningFixture.coordinator.setLiveTarget(
            lifecycleSession,
            current,
            ChannelId(1),
        ) as LivePlaybackTargetResult.Bound
        assertSame(PlaybackTargetResult.SHUT_DOWN, shutDown.result)
        assertEquals(
            listOf(FakeSessionCall.BIND_LIVE_PLAYBACK, FakeSessionCall.BIND_LIVE_PLAYBACK),
            lifecycleSession.calls,
        )

        mapOf(
            PlaybackPlayerInstallStatus.STARTED to PlaybackTargetResult.STARTED,
            PlaybackPlayerInstallStatus.NOT_READY to PlaybackTargetResult.NOT_READY,
            PlaybackPlayerInstallStatus.TARGET_UNAVAILABLE to PlaybackTargetResult.TARGET_UNAVAILABLE,
            PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE to PlaybackTargetResult.PLAYER_UNAVAILABLE,
        ).forEach { (status, expected) ->
            val fixture = CoordinatorFixture()
            fixture.player.liveInstallStatus = status
            val session = liveSession()
            val lifetime = fixture.coordinator.launchIn(this)

            val result = fixture.coordinator.setLiveTarget(
                session,
                session.captureCurrentSession(),
                ChannelId(1),
            ) as LivePlaybackTargetResult.Bound

            assertSame(expected, result.result)
            assertEquals(listOf(FakeSessionCall.BIND_LIVE_PLAYBACK), session.calls)
            assertEquals(status == PlaybackPlayerInstallStatus.STARTED, fixture.player.hasActiveTarget)
            lifetime.shutdown(1.seconds)
        }
    }

    @Test
    fun `one-call live workflow rejects pre-cancelled binding and immediate lifecycle paths`() = runTest {
        suspend fun verify(session: FakeTvheadendSession) {
            val operation = async(start = CoroutineStart.UNDISPATCHED) {
                coroutineContext.cancel(CancellationException("scripted pre-cancellation"))
                CoordinatorFixture().coordinator.setLiveTarget(
                    session,
                    session.captureCurrentSession(),
                    ChannelId(1),
                )
            }

            assertTrue(runCatching { operation.await() }.exceptionOrNull() is CancellationException)
            assertTrue(session.calls.isEmpty())
        }

        verify(liveSession())
        verify(liveSession().apply {
            scriptLivePlaybackFailure(
                at.bernhardberger.tvheadend.sdk.core.PlaybackBindingResult.TargetUnavailable,
            )
        })
    }

    @Test
    fun `one-call live workflow preserves replacement and cancellation semantics`() = runTest {
        val replacementFixture = CoordinatorFixture()
        val replacementOwner = launch(start = CoroutineStart.UNDISPATCHED) {
            replacementFixture.coordinator.run()
        }
        assertSame(
            PlaybackTargetResult.STARTED,
            replacementFixture.coordinator.setLiveTarget(ChannelId(7)),
        )
        val healthyToken = replacementFixture.player.requireActiveToken()
        replacementFixture.player.liveInstallStatus = PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE
        val replacementSession = liveSession()

        val failed = replacementFixture.coordinator.setLiveTarget(
            replacementSession,
            replacementSession.captureCurrentSession(),
            ChannelId(1),
        ) as LivePlaybackTargetResult.Bound

        assertSame(PlaybackTargetResult.PLAYER_UNAVAILABLE, failed.result)
        assertSame(healthyToken, replacementFixture.player.requireActiveToken())
        assertEquals(listOf("live:7"), replacementFixture.player.operations)
        assertEquals(listOf(FakeSessionCall.BIND_LIVE_PLAYBACK), replacementSession.calls)
        replacementFixture.coordinator.shutdown(1.seconds)
        replacementOwner.join()

        val cancellationFixture = CoordinatorFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        cancellationFixture.player.liveInstallEntered = entered
        cancellationFixture.player.liveInstallRelease = release
        val cancellationOwner = launch(start = CoroutineStart.UNDISPATCHED) {
            cancellationFixture.coordinator.run()
        }
        val cancellationSession = liveSession()
        val operation = async {
            cancellationFixture.coordinator.setLiveTarget(
                cancellationSession,
                cancellationSession.captureCurrentSession(),
                ChannelId(1),
            )
        }
        entered.await()

        operation.cancel(CancellationException("scripted workflow cancellation"))
        release.complete(Unit)
        assertTrue(runCatching { operation.await() }.exceptionOrNull() is CancellationException)
        assertEquals(listOf(FakeSessionCall.BIND_LIVE_PLAYBACK), cancellationSession.calls)
        cancellationFixture.coordinator.shutdown(1.seconds)
        cancellationOwner.join()
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
    fun `lifetime launches immediately and repeated shutdown and join remain terminal`() = runTest {
        val fixture = CoordinatorFixture()
        val lifetime = fixture.coordinator.launchIn(this)

        assertSame(
            PlaybackTargetResult.STARTED,
            fixture.coordinator.setLiveTarget(ChannelId(1)),
        )
        assertSame(PlaybackShutdownResult.DRAINED, lifetime.shutdown(1.seconds))
        assertSame(PlaybackShutdownResult.ALREADY_SHUT_DOWN, lifetime.shutdown(1.seconds))
        lifetime.join()
        lifetime.join()

        assertEquals(1, fixture.player.abandonCalls)
        assertFalse(fixture.player.released)
        assertTrue(
            runCatching { fixture.coordinator.launchIn(this) }.exceptionOrNull() is
                IllegalStateException,
        )
    }

    @Test
    fun `lifetime exposes no public JVM constructor`() {
        assertTrue(PlaybackCoordinatorLifetime::class.java.isSealed)
        assertTrue(PlaybackCoordinatorLifetime::class.java.constructors.isEmpty())
    }

    @Test
    fun `structured block stops collector children before returning shutdown evidence`() = runTest {
        val fixture = CoordinatorFixture()
        var collectorStopped = false

        val result = fixture.coordinator.withLifetime(1.seconds) { coordinator ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    collectorStopped = true
                }
            }
            assertSame(
                PlaybackTargetResult.STARTED,
                coordinator.setLiveTarget(ChannelId(1)),
            )
        }

        assertSame(PlaybackShutdownResult.DRAINED, result)
        assertTrue(collectorStopped)
        assertEquals(1, fixture.player.abandonCalls)
        assertFalse(fixture.player.released)
        assertSame(LivePlaybackObservation.NoTarget, fixture.coordinator.livePlaybackObservation.value)
    }

    @Test
    fun `structured block failure is rethrown after collector and coordinator cleanup`() = runTest {
        val fixture = CoordinatorFixture()
        val failure = IOException("scripted block failure")
        var collectorStopped = false

        val caught = runCatching {
            fixture.coordinator.withLifetime(1.seconds) {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        collectorStopped = true
                    }
                }
                throw failure
            }
        }.exceptionOrNull()

        assertTrue(caught === failure || caught?.cause === failure)
        assertTrue(collectorStopped)
        assertEquals(1, fixture.player.abandonCalls)
        assertFalse(fixture.player.released)
        assertSame(
            PlaybackTargetResult.SHUT_DOWN,
            fixture.coordinator.setLiveTarget(ChannelId(2)),
        )
    }

    @Test
    fun `structured caller cancellation remains cancellation after terminal cleanup`() = runTest {
        val fixture = CoordinatorFixture()
        val entered = CompletableDeferred<Unit>()
        val cancellation = CancellationException("scripted caller cancellation")
        val caller = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.coordinator.withLifetime(1.seconds) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()

        caller.cancel(cancellation)
        val caught = runCatching { caller.await() }.exceptionOrNull()

        assertTrue(caught is CancellationException)
        assertEquals(cancellation.message, caught?.message)
        assertEquals(1, fixture.player.abandonCalls)
        assertFalse(fixture.player.released)
        assertSame(
            PlaybackShutdownResult.ALREADY_SHUT_DOWN,
            fixture.coordinator.shutdown(1.seconds),
        )
    }

    @Test
    fun `shutdown caller cancellation cancels and joins the actor before propagating`() = runTest {
        val fixture = CoordinatorFixture()
        val installEntered = CompletableDeferred<Unit>()
        fixture.player.liveInstallEntered = installEntered
        fixture.player.liveInstallRelease = CompletableDeferred()
        val lifetime = fixture.coordinator.launchIn(this)
        val install = async { fixture.coordinator.setLiveTarget(ChannelId(1)) }
        installEntered.await()
        val cancellation = CancellationException("scripted shutdown caller cancellation")
        val shutdown = async { lifetime.shutdown(1.seconds) }
        runCurrent()

        shutdown.cancel(cancellation)
        val caught = runCatching { shutdown.await() }.exceptionOrNull()

        assertTrue(caught is CancellationException)
        assertEquals(cancellation.message, caught?.message)
        assertSame(PlaybackTargetResult.SHUT_DOWN, install.await())
        assertEquals(1, fixture.player.abandonCalls)
        assertFalse(fixture.player.released)
        assertSame(
            PlaybackShutdownResult.ALREADY_SHUT_DOWN,
            lifetime.shutdown(1.seconds),
        )
    }

    @Test
    fun `failed actor is retained by every lifetime join`() = runTest {
        val fixture = CoordinatorFixture()
        val failure = IOException("scripted install failure")
        fixture.player.installFailure = failure

        supervisorScope {
            val lifetime = fixture.coordinator.launchIn(this)
            assertSame(
                PlaybackTargetResult.SHUT_DOWN,
                fixture.coordinator.setLiveTarget(ChannelId(1)),
            )
            assertSame(
                PlaybackShutdownResult.ALREADY_SHUT_DOWN,
                lifetime.shutdown(1.seconds),
            )
            repeat(2) {
                val caught = runCatching { lifetime.join() }.exceptionOrNull()
                assertTrue(caught === failure || caught?.cause === failure)
            }
        }
        assertEquals(1, fixture.player.abandonCalls)
        assertFalse(fixture.player.released)
    }

    @Test
    fun `live target options carry profile and timeshift while defaults remain empty`() =
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
    fun `live diagnostics publish ordered units and clear on generation termination and close`() =
        runTest {
            val fixture = CoordinatorFixture()
            assertEquals(null, fixture.coordinator.liveDiagnostics.value)
            val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
            fixture.coordinator.setLiveTarget(ChannelId(1))
            val firstAttachment = fixture.player.requireTimeshiftControls()

            fixture.player.emitTimeshift(sourceStarted("Service A"))
            fixture.player.emitTimeshift(
                SubscriptionEvent.Signal(
                    relativeSnr = 65_535L,
                    absoluteSnr = 12_500L,
                    relativeSignal = 32_768L,
                    absoluteSignal = -70_250L,
                    bitErrorRate = 4L,
                    uncorrectedBlockCount = 5L,
                    frontendStatusReported = true,
                    frontendState = LiveFrontendState.create(true, true, true),
                ),
            )
            fixture.player.emitTimeshift(SubscriptionEvent.Queue(6L, 7L, 8L, 9L, 10L, 11L))
            assertEquals(null, fixture.coordinator.liveDiagnostics.value)

            fixture.player.attachTimeshift(FakeTimeshiftSubscription(60.seconds))
            with(requireNotNull(fixture.coordinator.liveDiagnostics.value)) {
                assertEquals("Service A", source?.serviceName)
                assertEquals(100.0, frontend?.relativeSnrPercent)
                assertEquals(12.5, frontend?.absoluteSnrDecibels)
                assertEquals(32_768.0 * 100.0 / 65_535.0, frontend?.relativeSignalPercent)
                assertEquals(-70.25, frontend?.absoluteSignalDbm)
                assertEquals(4L, frontend?.bitErrorRateRaw)
                assertEquals(5L, frontend?.uncorrectedBlockCount)
                assertTrue(requireNotNull(frontend?.state).locked)
                assertEquals(6L, queue?.packetCount)
                assertEquals(7L, queue?.byteCount)
                assertEquals(8.microseconds, queue?.mediaSpan)
                assertEquals(9L, queue?.droppedBFrameCount)
                assertEquals(10L, queue?.droppedPFrameCount)
                assertEquals(11L, queue?.droppedIFrameCount)
            }

            fixture.player.replaceTimeshiftPeriod(FakeTimeshiftSubscription(60.seconds))
            assertEquals(null, fixture.coordinator.liveDiagnostics.value)
            firstAttachment.accept(SubscriptionEvent.Queue(99L, 99L, 99L, 99L, 99L, 99L))
            assertEquals(null, fixture.coordinator.liveDiagnostics.value)

            fixture.player.emitTimeshift(sourceStarted("Service B"))
            assertEquals("Service B", fixture.coordinator.liveDiagnostics.value?.source?.serviceName)
            fixture.player.emitTimeshift(
                SubscriptionEvent.Terminated(SubscriptionTermination.GENERATION_LOST),
            )
            assertEquals(null, fixture.coordinator.liveDiagnostics.value)

            val closingSubscription = FakeTimeshiftSubscription(60.seconds)
            fixture.player.replaceTimeshiftPeriod(closingSubscription)
            fixture.player.emitTimeshift(SubscriptionEvent.Queue(1L, 2L, 3L, 4L, 5L, 6L))
            assertEquals(1L, fixture.coordinator.liveDiagnostics.value?.queue?.packetCount)
            closingSubscription.mutableState.value = SubscriptionState.Terminal(
                SubscriptionTerminalReason.ConsumerFailed,
            )
            fixture.player.requireTimeshiftControls().terminal(closingSubscription)
            assertEquals(null, fixture.coordinator.liveDiagnostics.value)

            fixture.coordinator.shutdown(1.seconds)
            owner.join()
        }

    @Test
    fun `live diagnostics are fenced by failed and successful target replacement stop and shutdown`() =
        runTest {
            val fixture = CoordinatorFixture()
            val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
            fixture.coordinator.setLiveTarget(ChannelId(1))
            fixture.player.attachTimeshift(FakeTimeshiftSubscription(60.seconds))
            val staleAttachment = fixture.player.requireTimeshiftControls()
            fixture.player.emitTimeshift(SubscriptionEvent.Queue(1L, 10L, 100L, 0L, 0L, 0L))
            val original = requireNotNull(fixture.coordinator.liveDiagnostics.value)

            fixture.player.liveInstallStatus = PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE
            fixture.player.simulateFailedReplacementPeriodTurnover = true
            assertSame(
                PlaybackTargetResult.PLAYER_UNAVAILABLE,
                fixture.coordinator.setLiveTarget(ChannelId(2)),
            )
            assertSame(original, fixture.coordinator.liveDiagnostics.value)

            fixture.player.liveInstallStatus = PlaybackPlayerInstallStatus.STARTED
            assertSame(PlaybackTargetResult.STARTED, fixture.coordinator.setLiveTarget(ChannelId(3)))
            assertEquals(null, fixture.coordinator.liveDiagnostics.value)
            staleAttachment.accept(SubscriptionEvent.Queue(2L, 20L, 200L, 0L, 0L, 0L))
            assertEquals(null, fixture.coordinator.liveDiagnostics.value)

            fixture.player.attachTimeshift(FakeTimeshiftSubscription(60.seconds))
            fixture.player.emitTimeshift(SubscriptionEvent.Queue(3L, 30L, 300L, 0L, 0L, 0L))
            fixture.coordinator.setRecordingTarget(DvrEntryId(7))
            assertEquals(null, fixture.coordinator.liveDiagnostics.value)

            fixture.coordinator.setLiveTarget(ChannelId(4))
            fixture.player.attachTimeshift(FakeTimeshiftSubscription(60.seconds))
            fixture.player.emitTimeshift(SubscriptionEvent.Queue(4L, 40L, 400L, 0L, 0L, 0L))
            fixture.coordinator.stop()
            assertEquals(null, fixture.coordinator.liveDiagnostics.value)

            fixture.coordinator.setLiveTarget(ChannelId(5))
            fixture.player.attachTimeshift(FakeTimeshiftSubscription(60.seconds))
            fixture.player.emitTimeshift(SubscriptionEvent.Queue(5L, 50L, 500L, 0L, 0L, 0L))
            fixture.coordinator.shutdown(1.seconds)
            owner.join()
            assertEquals(null, fixture.coordinator.liveDiagnostics.value)
        }

    @Test
    fun `aggregate live observation updates coherently and resets behind the target fence`() = runTest {
        val fixture = CoordinatorFixture()
        assertSame(LivePlaybackObservation.NoTarget, fixture.coordinator.livePlaybackObservation.value)
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        assertSame(PlaybackTargetResult.STARTED, fixture.coordinator.setLiveTarget(ChannelId(1)))

        var observation = fixture.coordinator.livePlaybackObservation.value as LivePlaybackObservation.Active
        assertSame(LiveTimeshiftState.Unavailable, observation.timeshiftState)
        assertNull(observation.subscriptionIssue)
        assertNull(observation.diagnostics)

        fixture.player.attachTimeshift(FakeTimeshiftSubscription(60.seconds))
        fixture.player.emitTimeshift(
            SubscriptionEvent.Status(
                SubscriptionCondition.ERROR_REPORTED,
                SubscriptionIssue.BAD_SIGNAL,
            ),
        )
        observation = fixture.coordinator.livePlaybackObservation.value as LivePlaybackObservation.Active
        assertSame(SubscriptionIssue.BAD_SIGNAL, observation.subscriptionIssue)
        assertEquals(fixture.coordinator.timeshiftState.value, observation.timeshiftState)

        fixture.player.emitTimeshift(SubscriptionEvent.Queue(3L, 30L, 300L, 0L, 0L, 0L))
        observation = fixture.coordinator.livePlaybackObservation.value as LivePlaybackObservation.Active
        assertEquals(3L, observation.diagnostics?.queue?.packetCount)
        assertSame(SubscriptionIssue.BAD_SIGNAL, observation.subscriptionIssue)

        fixture.player.emitTimeshift(
            SubscriptionEvent.Timeshift(0L, 10_000_000L, 0L, 30_000_000L, 100),
        )
        observation = fixture.coordinator.livePlaybackObservation.value as LivePlaybackObservation.Active
        assertTrue(observation.timeshiftState is LiveTimeshiftState.Available)
        assertSame(fixture.coordinator.subscriptionIssue.value, observation.subscriptionIssue)
        assertSame(fixture.coordinator.liveDiagnostics.value, observation.diagnostics)
        val original = observation
        val staleAttachment = fixture.player.requireTimeshiftControls()

        fixture.player.liveInstallStatus = PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE
        assertSame(
            PlaybackTargetResult.PLAYER_UNAVAILABLE,
            fixture.coordinator.setLiveTarget(ChannelId(2)),
        )
        assertEquals(original, fixture.coordinator.livePlaybackObservation.value)

        fixture.player.liveInstallStatus = PlaybackPlayerInstallStatus.STARTED
        assertSame(PlaybackTargetResult.STARTED, fixture.coordinator.setLiveTarget(ChannelId(3)))
        val replacement = fixture.coordinator.livePlaybackObservation.value
        assertEquals(
            LivePlaybackObservation.Active(LiveTimeshiftState.Unavailable, null, null),
            replacement,
        )
        staleAttachment.accept(
            SubscriptionEvent.Status(
                SubscriptionCondition.ERROR_REPORTED,
                SubscriptionIssue.SCRAMBLED,
            ),
        )
        staleAttachment.accept(SubscriptionEvent.Queue(99L, 99L, 99L, 0L, 0L, 0L))
        assertEquals(replacement, fixture.coordinator.livePlaybackObservation.value)

        assertSame(PlaybackTargetResult.STARTED, fixture.coordinator.setRecordingTarget(DvrEntryId(7)))
        assertSame(LivePlaybackObservation.NoTarget, fixture.coordinator.livePlaybackObservation.value)
        assertSame(PlaybackTargetResult.STARTED, fixture.coordinator.setLiveTarget(ChannelId(4)))
        assertSame(PlaybackStopResult.STOPPED, fixture.coordinator.stop())
        assertSame(LivePlaybackObservation.NoTarget, fixture.coordinator.livePlaybackObservation.value)
        assertSame(PlaybackTargetResult.STARTED, fixture.coordinator.setLiveTarget(ChannelId(5)))
        assertSame(PlaybackShutdownResult.DRAINED, fixture.coordinator.shutdown(1.seconds))
        owner.join()
        assertSame(LivePlaybackObservation.NoTarget, fixture.coordinator.livePlaybackObservation.value)
    }

    @Test
    fun `replacement rollback keeps newer diagnostics observations`() {
        var publishedDiagnostics: LiveSubscriptionDiagnostics? = null
        val bridge = LiveTimeshiftControlBridge(
            token = PlaybackTargetToken(),
            publish = {},
            publishIssue = {},
            publishDiagnostics = { publishedDiagnostics = it },
        )
        bridge.newAttachment().apply {
            bind(FakeTimeshiftSubscription(60.seconds))
            accept(SubscriptionEvent.Queue(1L, 10L, 100L, 0L, 0L, 0L))
        }
        val replacement = bridge.beginObservationReplacement()
        bridge.newAttachment().apply {
            bind(FakeTimeshiftSubscription(60.seconds))
            accept(SubscriptionEvent.Queue(2L, 20L, 200L, 0L, 0L, 0L))
        }

        bridge.rollbackObservationReplacement(replacement)

        assertEquals(2L, publishedDiagnostics?.queue?.packetCount)
    }

    @Test
    fun `failed replacement suppresses period turnover and publishes bound timeshift state`() {
        val publishedStates = mutableListOf<LiveTimeshiftState>()
        val bridge = LiveTimeshiftControlBridge(
            token = PlaybackTargetToken(),
            publish = publishedStates::add,
            publishIssue = {},
        )
        val original = bridge.newAttachment()
        original.bind(FakeTimeshiftSubscription(60.seconds))
        original.accept(SubscriptionEvent.Timeshift(0L, 10_000_000L, 0L, 30_000_000L, 100))
        val originalState = publishedStates.last()
        val replacement = bridge.beginObservationReplacement()
        val publicationCount = publishedStates.size

        original.detach()
        val restored = bridge.newAttachment()
        restored.bind(FakeTimeshiftSubscription(60.seconds))
        assertEquals(publicationCount, publishedStates.size)

        bridge.rollbackObservationReplacement(replacement)
        val reboundState = publishedStates.last() as LiveTimeshiftState.Available
        assertEquals(60.seconds, reboundState.grantedPeriod)
        assertNull(reboundState.bufferedDuration)
        assertNotEquals(originalState, reboundState)
        restored.accept(
            SubscriptionEvent.Status(
                SubscriptionCondition.NO_DETAIL,
                issue = null,
            ),
        )
        assertEquals(reboundState, publishedStates.last())

        restored.accept(SubscriptionEvent.Timeshift(0L, 20_000_000L, 10_000_000L, 30_000_000L, 100))
        assertEquals(20.seconds, (publishedStates.last() as LiveTimeshiftState.Available).bufferedDuration)
    }

    @Test
    fun `failed replacement restores timeshift state until a new period binds`() {
        val publishedStates = mutableListOf<LiveTimeshiftState>()
        val bridge = LiveTimeshiftControlBridge(
            token = PlaybackTargetToken(),
            publish = publishedStates::add,
            publishIssue = {},
        )
        val original = bridge.newAttachment()
        original.bind(FakeTimeshiftSubscription(60.seconds))
        original.accept(SubscriptionEvent.Timeshift(0L, 10_000_000L, 0L, 30_000_000L, 100))
        val originalState = publishedStates.last()
        val replacement = bridge.beginObservationReplacement()
        val publicationCount = publishedStates.size

        original.detach()
        assertEquals(publicationCount, publishedStates.size)

        bridge.rollbackObservationReplacement(replacement)
        assertEquals(originalState, publishedStates.last())
    }

    @Test
    fun `retired bridge safely settles claimed observation replacement`() {
        val committed = LiveTimeshiftControlBridge(PlaybackTargetToken(), {})
        val commitReplacement = committed.beginObservationReplacement()
        committed.retire()
        committed.commitObservationReplacement(commitReplacement)

        val rolledBack = LiveTimeshiftControlBridge(PlaybackTargetToken(), {})
        val rollbackReplacement = rolledBack.beginObservationReplacement()
        rolledBack.retire()
        rolledBack.rollbackObservationReplacement(rollbackReplacement)
    }

    @Test
    fun `terminal subscription at bind cannot replay pre-bind diagnostics`() {
        var publishedDiagnostics: LiveSubscriptionDiagnostics? = null
        val bridge = LiveTimeshiftControlBridge(
            token = PlaybackTargetToken(),
            publish = {},
            publishIssue = {},
            publishDiagnostics = { publishedDiagnostics = it },
        )
        val original = bridge.newAttachment()
        original.bind(FakeTimeshiftSubscription(60.seconds))
        original.accept(SubscriptionEvent.Queue(1L, 10L, 100L, 0L, 0L, 0L))
        val replacement = bridge.beginObservationReplacement()
        original.detach()
        val restored = bridge.newAttachment()
        restored.accept(SubscriptionEvent.Queue(2L, 20L, 200L, 0L, 0L, 0L))
        bridge.rollbackObservationReplacement(replacement)
        assertEquals(1L, publishedDiagnostics?.queue?.packetCount)

        val terminalSubscription = FakeTimeshiftSubscription(60.seconds)
        terminalSubscription.mutableState.value = SubscriptionState.Terminal(
            SubscriptionTerminalReason.TransportClosed,
        )
        restored.bind(terminalSubscription)

        assertEquals(null, publishedDiagnostics)
    }

    @Test
    fun `newer pre-bind termination fences diagnostics from the active predecessor`() {
        var publishedDiagnostics: LiveSubscriptionDiagnostics? = null
        val bridge = LiveTimeshiftControlBridge(
            token = PlaybackTargetToken(),
            publish = {},
            publishIssue = {},
            publishDiagnostics = { publishedDiagnostics = it },
        )
        val predecessor = bridge.newAttachment()
        predecessor.bind(FakeTimeshiftSubscription(60.seconds))
        predecessor.accept(SubscriptionEvent.Queue(1L, 10L, 100L, 0L, 0L, 0L))
        assertEquals(1L, publishedDiagnostics?.queue?.packetCount)

        bridge.newAttachment().accept(
            SubscriptionEvent.Terminated(SubscriptionTermination.GENERATION_LOST),
        )
        assertEquals(null, publishedDiagnostics)

        predecessor.accept(SubscriptionEvent.Queue(2L, 20L, 200L, 0L, 0L, 0L))
        assertEquals(null, publishedDiagnostics)
    }

    @Test
    fun `newer pre-bind start fences diagnostics from the active predecessor`() {
        var publishedDiagnostics: LiveSubscriptionDiagnostics? = null
        val bridge = LiveTimeshiftControlBridge(
            token = PlaybackTargetToken(),
            publish = {},
            publishIssue = {},
            publishDiagnostics = { publishedDiagnostics = it },
        )
        val predecessor = bridge.newAttachment()
        predecessor.bind(FakeTimeshiftSubscription(60.seconds))
        predecessor.accept(SubscriptionEvent.Queue(1L, 10L, 100L, 0L, 0L, 0L))
        assertEquals(1L, publishedDiagnostics?.queue?.packetCount)

        val successor = bridge.newAttachment()
        successor.accept(
            SubscriptionEvent.Started(
                streams = null,
                codecMetadata = null,
                condition = SubscriptionCondition.NO_DETAIL,
            ),
        )
        assertEquals(null, publishedDiagnostics)

        predecessor.accept(SubscriptionEvent.Queue(2L, 20L, 200L, 0L, 0L, 0L))
        assertEquals(null, publishedDiagnostics)
        successor.accept(SubscriptionEvent.Queue(3L, 30L, 300L, 0L, 0L, 0L))
        successor.bind(FakeTimeshiftSubscription(60.seconds))
        assertEquals(3L, publishedDiagnostics?.queue?.packetCount)
    }

    @Test
    fun `replacement rollback restores diagnostics fenced by a newer pre-bind start`() {
        var publishedDiagnostics: LiveSubscriptionDiagnostics? = null
        val bridge = LiveTimeshiftControlBridge(
            token = PlaybackTargetToken(),
            publish = {},
            publishIssue = {},
            publishDiagnostics = { publishedDiagnostics = it },
        )
        val predecessor = bridge.newAttachment()
        predecessor.bind(FakeTimeshiftSubscription(60.seconds))
        predecessor.accept(SubscriptionEvent.Queue(1L, 10L, 100L, 0L, 0L, 0L))
        val replacement = bridge.beginObservationReplacement()
        bridge.newAttachment().accept(
            SubscriptionEvent.Started(
                streams = null,
                codecMetadata = null,
                condition = SubscriptionCondition.NO_DETAIL,
            ),
        )

        bridge.rollbackObservationReplacement(replacement)
        assertEquals(1L, publishedDiagnostics?.queue?.packetCount)
        predecessor.accept(SubscriptionEvent.Queue(2L, 20L, 200L, 0L, 0L, 0L))
        assertEquals(2L, publishedDiagnostics?.queue?.packetCount)
    }

    @Test
    fun `replacement rollback cannot hide a terminal behind newer attachments`() {
        var publishedDiagnostics: LiveSubscriptionDiagnostics? = null
        val bridge = LiveTimeshiftControlBridge(
            token = PlaybackTargetToken(),
            publish = {},
            publishIssue = {},
            publishDiagnostics = { publishedDiagnostics = it },
        )
        val predecessor = bridge.newAttachment()
        predecessor.bind(FakeTimeshiftSubscription(60.seconds))
        predecessor.accept(SubscriptionEvent.Queue(1L, 10L, 100L, 0L, 0L, 0L))
        val replacement = bridge.beginObservationReplacement()
        predecessor.detach()
        bridge.newAttachment().apply {
            accept(
                SubscriptionEvent.Started(
                    streams = null,
                    codecMetadata = null,
                    condition = SubscriptionCondition.NO_DETAIL,
                ),
            )
            accept(SubscriptionEvent.Terminated(SubscriptionTermination.GENERATION_LOST))
        }
        bridge.newAttachment()
        val newest = bridge.newAttachment()

        bridge.rollbackObservationReplacement(replacement)
        assertEquals(null, publishedDiagnostics)
        newest.bind(FakeTimeshiftSubscription(60.seconds))
        assertEquals(null, publishedDiagnostics)
        newest.accept(SubscriptionEvent.Queue(3L, 30L, 300L, 0L, 0L, 0L))
        assertEquals(3L, publishedDiagnostics?.queue?.packetCount)
    }

    @Test
    fun `replacement rollback ignores a stale lower sequence terminal callback`() {
        var publishedDiagnostics: LiveSubscriptionDiagnostics? = null
        val bridge = LiveTimeshiftControlBridge(
            token = PlaybackTargetToken(),
            publish = {},
            publishIssue = {},
            publishDiagnostics = { publishedDiagnostics = it },
        )
        val stale = bridge.newAttachment()
        bridge.newAttachment().accept(
            SubscriptionEvent.Stopped(
                SubscriptionCondition.ERROR_REPORTED,
                SubscriptionIssue.BAD_SIGNAL,
            ),
        )
        val predecessor = bridge.newAttachment()
        predecessor.bind(FakeTimeshiftSubscription(60.seconds))
        predecessor.accept(SubscriptionEvent.Queue(2L, 20L, 200L, 0L, 0L, 0L))
        val replacement = bridge.beginObservationReplacement()
        predecessor.detach()
        stale.accept(SubscriptionEvent.Terminated(SubscriptionTermination.GENERATION_LOST))

        bridge.rollbackObservationReplacement(replacement)

        assertEquals(2L, publishedDiagnostics?.queue?.packetCount)
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
            val replacement = bridge.beginObservationReplacement()
            original.detach()
            val restored = bridge.newAttachment()
            restored.accept(
                SubscriptionEvent.Status(
                    SubscriptionCondition.ERROR_REPORTED,
                    observedIssue,
                ),
            )
            restored.bind(FakeTimeshiftSubscription(60.seconds))

            bridge.rollbackObservationReplacement(replacement)

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
            val replacement = bridge.beginObservationReplacement()
            original.detach()
            val restored = bridge.newAttachment()
            bridge.rollbackObservationReplacement(replacement)
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
            val replacement = bridge.beginObservationReplacement()
            original.detach()
            bridge.newAttachment().accept(event)

            bridge.rollbackObservationReplacement(replacement)

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
        val replacement = bridge.beginObservationReplacement()
        subscription.mutableState.value = SubscriptionState.Terminal(
            SubscriptionTerminalReason.ConsumerFailed,
        )

        original.terminal(subscription)
        bridge.rollbackObservationReplacement(replacement)

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
                command: suspend (TestCoordinatorHarness) -> TimeshiftCommandResult,
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
        fixture.admitGrowing()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.player.snapshot = snapshot(position = 0, duration = 100)
        assertEquals(
            PlaybackTargetResult.STARTED,
            fixture.coordinator.setRecordingTarget(DvrEntryId(7), RecordingPlaybackStart.START_OVER),
        )

        fixture.environment.publishDvrState(currentDvr(
            growingEntry(state = DvrEntryState.COMPLETED),
        ))
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
        fixture.admitGrowing()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.player.snapshot = snapshot(position = 0, duration = 100)
        fixture.coordinator.setRecordingTarget(DvrEntryId(7), RecordingPlaybackStart.START_OVER)

        fixture.environment.publishDvrState(currentDvr(
            growingEntry(state = DvrEntryState.COMPLETED),
        ))
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
        fixture.admitGrowing()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.player.snapshot = snapshot(position = 0, duration = 100)
        fixture.coordinator.setRecordingTarget(DvrEntryId(7), RecordingPlaybackStart.START_OVER)
        fixture.environment.publishDvrState(currentDvr(
            growingEntry(state = DvrEntryState.COMPLETED),
        ))

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
        fixture.admitGrowing()
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
        fixture.environment.publishDvrState(currentDvr(
            growingEntry(state = DvrEntryState.COMPLETED),
        ))

        assertProgress(fixture.environment.calls.single(), 7, 100, watched = false)
        fixture.coordinator.shutdown(1.seconds)
        owner.join()
    }

    @Test
    fun `growing identity change invalidates queued and terminal progress`() = runTest {
        val fixture = CoordinatorFixture()
        fixture.admitGrowing()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.player.snapshot = snapshot(position = 0, duration = 100)
        fixture.coordinator.setRecordingTarget(DvrEntryId(7), RecordingPlaybackStart.START_OVER)

        val blocker = fixture.environment.blockNextReport()
        fixture.events.publish(pause(fixture, 10))
        runCurrent()
        fixture.events.publish(pause(fixture, 20))
        runCurrent()
        fixture.environment.publishDvrState(currentDvr(
            growingEntry(fileId = 2, filePath = "/replacement.ts"),
        ))
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
        fixture.environment.publishSessionState(SessionState.Disconnected)
        runCurrent()
        assertTrue(fixture.player.hasActiveTarget)

        blocker.complete(Unit)
        runCurrent()
        assertEquals(1, fixture.environment.calls.size)

        fixture.environment.publishSessionState(readyState())
        runCurrent()
        assertEquals(1, fixture.environment.calls.size)

        fixture.events.publish(pause(fixture, 30))
        runCurrent()
        assertEquals(1, fixture.environment.calls.size)

        fixture.coordinator.shutdown(1.seconds)
        owner.join()
    }

    @Test
    fun `same generation recording replacement cannot rearm a retired progress epoch`() = runTest {
        val fixture = CoordinatorFixture()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.player.snapshot = snapshot(position = 0, duration = 100)
        fixture.coordinator.setRecordingTarget(DvrEntryId(7))

        fixture.events.publish(pause(fixture, 10))
        runCurrent()
        assertEquals(1, fixture.environment.calls.size)

        val target = requireNotNull(fixture.environment.activeTarget)
        target.admissionState = CoordinatorRecordingAdmission.TargetUnavailable
        fixture.events.publish(pause(fixture, 20))
        runCurrent()
        assertEquals(1, fixture.environment.calls.size)

        target.admissionState = completedCoordinatorAdmission()
        fixture.events.publish(pause(fixture, 30))
        runCurrent()
        assertEquals(1, fixture.environment.calls.size)

        fixture.coordinator.shutdown(1.seconds)
        owner.join()
    }

    @Test
    fun `growing continuity loss never restarts progress after reconnect`() = runTest {
        val fixture = CoordinatorFixture()
        fixture.admitGrowing()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.run() }
        fixture.player.snapshot = snapshot(position = 0, duration = 100)
        fixture.coordinator.setRecordingTarget(DvrEntryId(7), RecordingPlaybackStart.START_OVER)

        fixture.events.publish(pause(fixture, 10))
        runCurrent()
        assertEquals(1, fixture.environment.calls.size)

        fixture.environment.publishSessionState(SessionState.Disconnected)
        fixture.growingLease.current = false
        runCurrent()
        fixture.environment.publishSessionState(readyState())
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

private fun sourceStarted(serviceName: String): SubscriptionEvent.Started =
    SubscriptionEvent.Started(
        streams = null,
        codecMetadata = null,
        condition = SubscriptionCondition.NO_DETAIL,
        issue = null,
        source = requireNotNull(
            LiveSubscriptionSource.create(
                adapterName = "Adapter",
                muxName = "Mux",
                networkName = "Network",
                providerName = "Provider",
                serviceName = serviceName,
            ),
        ),
    )

private class CoordinatorFixture {
    val environment = FakeCoordinatorEnvironment()
    val events = PlaybackPlayerEventAccumulator()
    val player = FakePlaybackCoordinatorPlayer()
    val growingLease = MutableGrowingLease()
    val time = ManualPlaybackCoordinatorTimeSource()
    private val delegate = TvheadendPlaybackCoordinator(
        player = player,
        playerEvents = events,
        progressPolicy = DvrProgressPolicy(),
        onRecoveryRequired = {},
        timeSource = time,
    )
    val coordinator = TestCoordinatorHarness(delegate) { recordingId ->
        NumberedRecordingTarget(
            id = recordingId,
            environment = environment,
            startedGrowing = player.recordingAdmission is RecordingAdmission.Growing,
            admissionState = if (player.recordingAdmission is RecordingAdmission.Growing) {
                CoordinatorRecordingAdmission.GrowingStartOverOnly(
                    RecordingProgressCapability.SUPPORTED,
                )
            } else {
                completedCoordinatorAdmission()
            },
            growingLease = growingLease.takeIf {
                player.recordingAdmission is RecordingAdmission.Growing
            },
        ).also { target -> environment.activeTarget = target }
    }

    fun admitGrowing() {
        growingLease.current = true
        player.recordingAdmission = RecordingAdmission.Growing(growingLease)
    }
}

private class TestCoordinatorHarness(
    private val delegate: TvheadendPlaybackCoordinator,
    private val recordingTarget: (Long) -> NumberedRecordingTarget,
) {
    val timeshiftState = delegate.timeshiftState
    val subscriptionIssue = delegate.subscriptionIssue
    val liveDiagnostics = delegate.liveDiagnostics
    val livePlaybackObservation = delegate.livePlaybackObservation

    suspend fun run() = delegate.run()

    fun launchIn(scope: CoroutineScope): PlaybackCoordinatorLifetime = delegate.launchIn(scope)

    suspend fun withLifetime(
        timeout: Duration,
        block: suspend CoroutineScope.(TestCoordinatorHarness) -> Unit,
    ): PlaybackShutdownResult = delegate.withLifetime(timeout) {
        block(this@TestCoordinatorHarness)
    }

    suspend fun setLiveTarget(
        channelId: ChannelId,
        options: LivePlaybackOptions = LivePlaybackOptions(),
    ): PlaybackTargetResult = delegate.setLiveTarget(NumberedLiveTarget(channelId.value), options)

    suspend fun setLiveTarget(
        session: FakeTvheadendSession,
        currentSession: at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation,
        channelId: ChannelId,
        options: LivePlaybackOptions = LivePlaybackOptions(),
    ): LivePlaybackTargetResult = delegate.setLiveTarget(
        session,
        currentSession,
        channelId,
        options,
    )

    suspend fun setRecordingTarget(
        recordingId: DvrEntryId,
        start: RecordingPlaybackStart = RecordingPlaybackStart.RESUME,
    ): PlaybackTargetResult = delegate.setRecordingTarget(recordingTarget(recordingId.value), start)

    suspend fun seekTimeshift(offset: Duration): TimeshiftCommandResult =
        delegate.seekTimeshift(offset)

    suspend fun returnToLive(): TimeshiftCommandResult = delegate.returnToLive()

    suspend fun pauseTimeshift(): TimeshiftCommandResult = delegate.pauseTimeshift()

    suspend fun resumeTimeshift(): TimeshiftCommandResult = delegate.resumeTimeshift()

    suspend fun stop(): PlaybackStopResult = delegate.stop()

    suspend fun shutdown(timeout: Duration): PlaybackShutdownResult = delegate.shutdown(timeout)
}

private class NumberedLiveTarget(
    val id: Long,
) : CoordinatorLiveTarget {
    override val isCurrent: Boolean = true

    override suspend fun open(
        consumer: SubscriptionEventConsumer,
        options: SubscriptionOptions,
    ): SubscriptionOpenResult = SubscriptionOpenResult.NotReady
}

private class NumberedRecordingTarget(
    val id: Long,
    private val environment: FakeCoordinatorEnvironment,
    override val startedGrowing: Boolean,
    var admissionState: CoordinatorRecordingAdmission,
    val growingLease: MutableGrowingLease?,
) : CoordinatorRecordingTarget {
    override val admission: CoordinatorRecordingAdmission
        get() = admissionState

    override suspend fun openRecording(): RecordingFileResult<RecordingFile> =
        RecordingFileResult.Failed(RecordingFileFailure.NOT_SUPPORTED)

    override fun bindGrowingRecording(): RecordingFileResult<GrowingRecordingFileLease> =
        growingLease?.let { lease -> RecordingFileResult.Ok(lease) }
            ?: RecordingFileResult.Failed(RecordingFileFailure.FILE_UNAVAILABLE)

    override suspend fun reportProgress(
        growingLease: GrowingRecordingFileLease?,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult = environment.reportProgress(id, growingLease, progress)
}

private data class CapturedProgress(
    val id: DvrEntryId,
    val growingLease: GrowingRecordingFileLease?,
    val progress: DvrPlaybackProgress,
)

private class FakeCoordinatorEnvironment {
    var activeTarget: NumberedRecordingTarget? = null
    val calls = mutableListOf<CapturedProgress>()
    var cancelledReports = 0
    var failNextReports = 0
    private val blockers = ArrayDeque<CompletableDeferred<Unit>>()

    suspend fun reportProgress(
        recordingId: Long,
        growingLease: GrowingRecordingFileLease?,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult {
        calls += CapturedProgress(DvrEntryId(recordingId), growingLease, progress)
        if (failNextReports > 0) {
            failNextReports -= 1
            throw IOException("scripted progress failure")
        }
        val blocker = blockers.removeFirstOrNull() ?: return DvrProgressResult.Accepted
        try {
            blocker.await()
        } catch (cancellation: CancellationException) {
            cancelledReports += 1
            throw cancellation
        }
        return DvrProgressResult.Accepted
    }

    fun blockNextReport(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also(blockers::addLast)

    fun publishSessionState(sessionState: SessionState) {
        val target = activeTarget ?: return
        target.admissionState = if (sessionState is SessionState.Ready) {
            if (target.startedGrowing) {
                CoordinatorRecordingAdmission.GrowingStartOverOnly(
                    RecordingProgressCapability.SUPPORTED,
                )
            } else {
                completedCoordinatorAdmission()
            }
        } else {
            CoordinatorRecordingAdmission.ObservationExpired
        }
    }

    fun publishDvrState(dvrState: DvrRepositoryState) {
        val target = activeTarget ?: return
        val entry = (dvrState as? DvrRepositoryState.Current)
            ?.snapshot
            ?.entries
            ?.singleOrNull { candidate -> candidate.id.value == target.id }
        target.admissionState = when (entry?.state) {
            DvrEntryState.COMPLETED -> completedCoordinatorAdmission()
            DvrEntryState.RECORDING -> {
                val file = entry.files?.singleOrNull()
                if (file?.fileId == 1L && file.path == "/recording.ts") {
                    CoordinatorRecordingAdmission.GrowingStartOverOnly(
                        RecordingProgressCapability.SUPPORTED,
                    )
                } else {
                    target.growingLease?.current = false
                    CoordinatorRecordingAdmission.TargetUnavailable
                }
            }
            else -> CoordinatorRecordingAdmission.TargetUnavailable
        }
    }
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
        target: CoordinatorLiveTarget,
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
                val replacement = currentControls.beginObservationReplacement()
                checkNotNull(timeshiftAttachment).detach()
                val restoredSubscription = FakeTimeshiftSubscription(60.seconds)
                timeshiftAttachment = currentControls.newAttachment()
                checkNotNull(timeshiftAttachment).bind(restoredSubscription)
                currentControls.rollbackObservationReplacement(replacement)
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
        operations += (target as? NumberedLiveTarget)?.let { numbered -> "live:${numbered.id}" }
            ?: "live:bound"
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
        target: CoordinatorRecordingTarget,
        start: RecordingPlaybackStart,
    ): PlaybackPlayerInstallResult {
        if (!ticket.claim()) return PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.CANCELLED)
        val retirement = retire()
        active = FakeTarget.Recording(token)
        operations += "recording:${(target as NumberedRecordingTarget).id}:$start"
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

internal class FakeTimeshiftSubscription(
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

private fun liveSession(): FakeTvheadendSession = FakeTvheadendSession(currentLiveObservation()).apply {
    scriptLivePlaybackSuccess()
}

private fun currentLiveObservation(): SessionObservation = SessionObservation.create(
    sessionState = readyState(),
    channelState = ChannelRepositoryState.Current(
        ChannelCatalog.create(listOf(TvheadendChannel.create(ChannelId(1)))),
    ),
    epgState = EpgRepositoryState.Current(EpgSnapshot.create()),
    dvrState = DvrRepositoryState.Current(DvrSnapshot.create()),
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
