@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrMutationResult
import at.bernhardberger.tvheadend.sdk.core.DvrRepository
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSchedule
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageRequestResult
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.core.ServerProfile
import at.bernhardberger.tvheadend.sdk.core.SessionCommandResult
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import at.bernhardberger.tvheadend.sdk.core.createTvheadendSession
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class SdkAcceptanceInstrumentationTest {
    @Test(timeout = STAGE_ONE_TIMEOUT_MS)
    fun stage_one_cold_reconnect_and_disposable_recording() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val stateFile = File(context.filesDir, ACCEPTANCE_STATE_FILE_NAME)
        assertFalse("A prior acceptance fixture must be cleaned before stage one", stateFile.exists())
        val profile = consumePrivateProfile(context.filesDir)
        val session = createTvheadendSession()
        val surface = launchPlaybackSurface(instrumentation)
        val render = RenderObservation()
        var player: ExoPlayer? = null
        var coordinator: TvheadendPlaybackCoordinator? = null
        var coordinatorOwner: Job? = null
        var fixtureState: AcceptanceState? = null
        var retainFixture = false
        var primaryFailure: Throwable? = null
        val startedAt = SystemClock.elapsedRealtime()
        val memoryBefore = memoryObservation()

        try {
            val synchronizingAt = async(start = CoroutineStart.UNDISPATCHED) {
                session.state.first { state -> state is SessionState.Synchronizing }
                elapsedSince(startedAt)
            }
            val firstRepositoryContentAt = async(start = CoroutineStart.UNDISPATCHED) {
                session.channelRepository.channels.first(List<*>::isNotEmpty)
                elapsedSince(startedAt)
            }
            val readyAt = async(start = CoroutineStart.UNDISPATCHED) {
                val ready = awaitReady(session)
                ready to elapsedSince(startedAt)
            }
            assertEquals(SessionCommandResult.STARTED, session.connect(profile))
            val coldSynchronizingMs = synchronizingAt.await()
            val firstRepositoryContentMs = firstRepositoryContentAt.await()
            val (coldReady, coldReadyMs) = readyAt.await()
            assertEquals(CapabilityAccess.ALLOWED, coldReady.capabilities.streaming)
            assertEquals(CapabilityAccess.ALLOWED, coldReady.capabilities.dvrWrite)
            awaitProgressSupport(session)

            val channels = session.channelRepository.channels.value
            assertTrue("Acceptance requires two live channels for target replacement", channels.size >= 2)
            val firstChannel = channels[0].id
            val replacementChannel = channels.first { channel -> channel.id != firstChannel }.id
            val initialEpg = session.epgRepository.state.value as? EpgRepositoryState.Current
            assertNotNull("Ready must expose a current EPG snapshot", initialEpg)
            val initialDvrCount = session.dvrRepository.entries.value.size
            val horizon = wholeSecondNow() + EPG_OBSERVATION_HORIZON
            val epgRequest = session.epgRepository.requestCoverage(firstChannel, horizon)
            assertTrue(
                "The public EPG coverage request must be admitted or already satisfied; result=$epgRequest",
                epgRequest == EpgCoverageRequestResult.ACCEPTED ||
                    epgRequest == EpgCoverageRequestResult.SATISFIED,
            )
            withTimeout(EPG_TIMEOUT_MS) {
                session.epgRepository.coverage(firstChannel).first { coverage ->
                    coverage?.knownTo?.let { knownTo -> knownTo >= horizon } == true
                }
            }
            val queriedCoverageCount =
                (session.epgRepository.state.value as EpgRepositoryState.Current)
                    .snapshot.coverages.count { coverage -> coverage.queriedTo != null }

            val activePlayer = createAcceptancePlayer(instrumentation, surface, render)
            player = activePlayer
            val activeCoordinator = createTvheadendPlaybackCoordinator(session, activePlayer)
            coordinator = activeCoordinator
            val activeCoordinatorOwner = launch(start = CoroutineStart.UNDISPATCHED) { activeCoordinator.run() }
            coordinatorOwner = activeCoordinatorOwner
            instrumentation.runOnMainSync { activePlayer.play() }
            assertEquals(PlaybackTargetResult.STARTED, activeCoordinator.setLiveTarget(firstChannel))
            val firstAdmissionMs = elapsedSince(startedAt)
            render.awaitPlayingVideo(instrumentation, activePlayer, "cold live playback")
            val firstRenderedFrameMs = elapsedSince(startedAt)

            val replacementBaseline = playerSnapshot(instrumentation, activePlayer)
            assertEquals(PlaybackTargetResult.STARTED, activeCoordinator.setLiveTarget(replacementChannel))
            render.awaitPlayingVideo(
                instrumentation = instrumentation,
                player = activePlayer,
                label = "live target replacement",
                frameBaseline = replacementBaseline.renderedVideoFrames,
            )
            assertEquals(PlaybackStopResult.STOPPED, activeCoordinator.stop())

            session.disconnect()
            assertEquals(SessionState.Disconnected, session.state.value)
            assertTrue(
                "Disconnect must retain the same-process channel catalog",
                session.channelRepository.state.value is ChannelRepositoryState.Stale,
            )
            val reconnectStartedAt = SystemClock.elapsedRealtime()
            val reconnectSynchronizing = async(start = CoroutineStart.UNDISPATCHED) {
                session.state.first { state -> state is SessionState.Synchronizing }
                elapsedSince(reconnectStartedAt)
            }
            assertEquals(SessionCommandResult.STARTED, session.connect(profile))
            val reconnectSynchronizingMs = reconnectSynchronizing.await()
            assertTrue(session.channelRepository.channels.value.any { channel -> channel.id == firstChannel })
            val reconnectBaseline = playerSnapshot(instrumentation, activePlayer)
            assertEquals(
                "A retained known channel must be admitted while synchronizing",
                PlaybackTargetResult.STARTED,
                activeCoordinator.setLiveTarget(firstChannel),
            )
            render.awaitPlayingVideo(
                instrumentation = instrumentation,
                player = activePlayer,
                label = "reconnect live playback during synchronization",
                frameBaseline = reconnectBaseline.renderedVideoFrames,
            )
            val warmReady = awaitReady(session)
            awaitProgressSupport(session)
            val warmReadyMs = elapsedSince(reconnectStartedAt)
            val memoryAfterReconnect = memoryObservation()
            assertEquals(PlaybackStopResult.STOPPED, activeCoordinator.stop())

            val marker = "sdk-p64-${UUID.randomUUID().toString().take(8)}"
            val recordingStart = wholeSecondNow() + RECORDING_START_DELAY
            val scheduleResult = session.dvrRepository.scheduleEntry(
                DvrScheduleRequest(
                    schedule = DvrSchedule.ExplicitTime(
                        channelId = firstChannel,
                        start = recordingStart,
                        stop = recordingStart + RECORDING_DURATION,
                    ),
                    configId = session.dvrRepository.configurations.value.firstOrNull()?.id,
                    title = marker,
                ),
            )
            val recordingId = when (scheduleResult) {
                is DvrMutationResult.Confirmed -> scheduleResult.value
                is DvrMutationResult.AcceptedButUnconfirmed -> {
                    val recoveryState = AcceptanceState(
                        recordingId = scheduleResult.value.value,
                        recordingUuid = null,
                        recordingMarker = marker,
                        stageOnePid = Process.myPid(),
                    )
                    fixtureState = recoveryState
                    writeAcceptanceState(context, recoveryState)
                    throw AssertionError("Disposable recording schedule was not stream-confirmed")
                }
                else -> throw AssertionError(
                    "Disposable recording schedule failed: ${scheduleResult.javaClass.simpleName}",
                )
            }
            val recoveryState = AcceptanceState(
                recordingId = recordingId.value,
                recordingUuid = null,
                recordingMarker = marker,
                stageOnePid = Process.myPid(),
            )
            fixtureState = recoveryState
            writeAcceptanceState(context, recoveryState)
            val scheduledEntry = requireNotNull(
                session.dvrRepository.entries.value.firstOrNull { entry -> entry.id == recordingId },
            )
            assertEquals(marker, scheduledEntry.title)
            val recordingUuid = requireNotNull(scheduledEntry.uuid) {
                "The disposable recording must have a stable UUID"
            }
            val ownedState = recoveryState.copy(recordingUuid = recordingUuid)
            fixtureState = ownedState
            writeAcceptanceState(context, ownedState)
            withTimeout(RECORDING_START_TIMEOUT_MS) {
                session.dvrRepository.entry(recordingId).first { entry ->
                    entry?.state == DvrEntryState.RECORDING
                }
            }
            delay(RECORDING_CAPTURE_MS)
            requireOwnedEntry(session.dvrRepository, ownedState)
            session.dvrRepository.stopEntry(recordingId).requireConfirmed()
            val completed = requireNotNull(
                withTimeout(RECORDING_COMPLETION_TIMEOUT_MS) {
                    session.dvrRepository.entry(recordingId).first { entry ->
                        entry?.state == DvrEntryState.COMPLETED
                    }
                },
            )
            completed.requireOwnedBy(ownedState)
            assertTrue(
                "The disposable completed recording must contain media bytes",
                completed.files.orEmpty().any { file -> file.sizeBytes?.let { size -> size > 0L } == true },
            )
            retainFixture = true

            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("p6_4_stage", "cold-reconnect-fixture-ready")
                    putString("p6_4_server_version", warmReady.capabilities.serverVersion ?: "unknown")
                    putLong("p6_4_cold_synchronizing_ms", coldSynchronizingMs)
                    putLong("p6_4_first_repository_content_ms", firstRepositoryContentMs)
                    putLong("p6_4_cold_ready_ms", coldReadyMs)
                    putLong("p6_4_first_live_admission_ms", firstAdmissionMs)
                    putLong("p6_4_first_rendered_frame_ms", firstRenderedFrameMs)
                    putLong("p6_4_reconnect_synchronizing_ms", reconnectSynchronizingMs)
                    putLong("p6_4_reconnect_ready_ms", warmReadyMs)
                    putInt("p6_4_initial_channel_count", channels.size)
                    putInt("p6_4_initial_tag_count", session.channelRepository.tags.value.size)
                    putInt("p6_4_initial_epg_event_count", requireNotNull(initialEpg).snapshot.events.size)
                    putInt("p6_4_initial_dvr_count", initialDvrCount)
                    putInt("p6_4_queried_coverage_count", queriedCoverageCount)
                    putString("p6_4_epg_request", epgRequest.name)
                    putMemory("p6_4_memory_before", memoryBefore)
                    putMemory("p6_4_memory_after_reconnect", memoryAfterReconnect)
                    putBoolean("p6_4_profile_consumed", true)
                },
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                runAcceptanceCleanup(
                    primaryFailure,
                    {
                        coordinator?.let { activeCoordinator ->
                            if (coordinatorOwner?.isActive == true) {
                                activeCoordinator.shutdown(COORDINATOR_DRAIN_TIMEOUT)
                            }
                        }
                    },
                    { withTimeout(COORDINATOR_JOIN_TIMEOUT_MS) { coordinatorOwner?.join() } },
                    {
                        if (!retainFixture) {
                            fixtureState?.let { state ->
                                if (cleanupRecording(session.dvrRepository, state) && stateFile.exists()) {
                                    check(stateFile.delete()) { "Acceptance process state could not be removed" }
                                }
                            }
                        }
                    },
                    { session.shutdown() },
                    {
                        player?.let { activePlayer ->
                            instrumentation.runOnMainSync { activePlayer.release() }
                        }
                    },
                    { surface.close(instrumentation) },
                )
            }
        }
    }

    @Test(timeout = STAGE_TWO_TIMEOUT_MS)
    fun stage_two_checkpoint_then_abrupt_process_loss(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val priorState = readAcceptanceState(context)
        assertNotEquals("Stage two must run in a fresh process", priorState.stageOnePid, Process.myPid())
        val profile = consumePrivateProfile(context.filesDir)
        val session = createTvheadendSession()
        val surface = launchPlaybackSurface(instrumentation)
        val render = RenderObservation()
        val player = createAcceptancePlayer(instrumentation, surface, render)
        val coordinator = createTvheadendPlaybackCoordinator(session, player)
        val coordinatorOwner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }

        try {
            assertEquals(SessionCommandResult.STARTED, session.connect(profile))
            awaitReady(session)
            awaitProgressSupport(session)
            val recordingId = DvrEntryId(priorState.recordingId)
            requireOwnedEntry(session.dvrRepository, priorState)
            assertEquals(
                PlaybackTargetResult.STARTED,
                coordinator.setRecordingTarget(recordingId, RecordingPlaybackStart.START_OVER),
            )
            instrumentation.runOnMainSync { player.play() }
            render.awaitPlayingVideo(instrumentation, player, "periodic checkpoint playback")
            withTimeout(PERIODIC_CHECKPOINT_TIMEOUT_MS) {
                while (playerSnapshot(instrumentation, player).positionMs < MINIMUM_CHECKPOINT_POSITION_MS) {
                    render.assertHealthy("periodic checkpoint playback")
                    delay(POLL_INTERVAL_MS)
                }
            }
            val checkpoint = withTimeout(PROGRESS_PUBLICATION_TIMEOUT_MS) {
                session.dvrRepository.entry(recordingId).first { entry ->
                    entry?.playPosition?.inWholeMilliseconds?.let { position -> position > 0L } == true
                }
            }
            val checkpointMs = requireNotNull(checkpoint).playPosition?.inWholeMilliseconds
            requireNotNull(checkpointMs)
            val laterPositionMs = awaitPlayerPosition(
                instrumentation = instrumentation,
                player = player,
                expectedMs = checkpointMs + UNREPORTED_PROGRESS_DELTA_MS,
                timeoutMs = LATER_POSITION_TIMEOUT_MS,
                render = render,
                label = "post-checkpoint playback",
            )
            writeAcceptanceState(
                context,
                priorState.copy(
                    stageTwoPid = Process.myPid(),
                    checkpointMs = checkpointMs,
                    laterPositionMs = laterPositionMs,
                ),
            )
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("p6_4_stage", "checkpoint-written-about-to-kill")
                    putLong("p6_4_server_checkpoint_ms", checkpointMs)
                    putLong("p6_4_unreported_player_position_ms", laterPositionMs)
                    putBoolean("p6_4_profile_consumed", true)
                },
            )
            Thread.sleep(STATUS_FLUSH_DELAY_MS)
            Process.killProcess(Process.myPid())
            throw AssertionError("The acceptance process did not terminate")
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runAcceptanceCleanup(
                    failure,
                    {
                        if (coordinatorOwner.isActive) coordinator.shutdown(COORDINATOR_DRAIN_TIMEOUT)
                    },
                    { withTimeout(COORDINATOR_JOIN_TIMEOUT_MS) { coordinatorOwner.join() } },
                    { session.shutdown() },
                    { instrumentation.runOnMainSync { player.release() } },
                    { surface.close(instrumentation) },
                )
            }
            throw failure
        }
    }

    @Test(timeout = STAGE_THREE_TIMEOUT_MS)
    fun stage_three_cross_process_resume_completion_and_cleanup() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val priorState = readAcceptanceState(context)
        val stageTwoPid = requireNotNull(priorState.stageTwoPid) { "Stage two process evidence is missing" }
        val checkpointMs = requireNotNull(priorState.checkpointMs) { "Stage two checkpoint is missing" }
        val laterPositionMs = requireNotNull(priorState.laterPositionMs) { "Stage two player position is missing" }
        assertNotEquals("Stage three must run in a fresh process", stageTwoPid, Process.myPid())
        val profile = consumePrivateProfile(context.filesDir)
        val session = createTvheadendSession()
        val surface = launchPlaybackSurface(instrumentation)
        val render = RenderObservation()
        var player: ExoPlayer? = null
        var coordinator: TvheadendPlaybackCoordinator? = null
        var coordinatorOwner: Job? = null
        val recordingId = DvrEntryId(priorState.recordingId)
        var fixtureRemoved = false
        var memoryAfterTeardown: MemoryObservation? = null
        var primaryFailure: Throwable? = null

        try {
            assertEquals(SessionCommandResult.STARTED, session.connect(profile))
            awaitReady(session)
            awaitProgressSupport(session)
            val serverEntry = requireOwnedEntry(session.dvrRepository, priorState)
            val serverPositionMs = requireNotNull(serverEntry.playPosition).inWholeMilliseconds
            assertTrue(
                "The new process must observe the last accepted server checkpoint",
                abs(serverPositionMs - checkpointMs) <= SERVER_POSITION_TOLERANCE_MS,
            )
            assertTrue(
                "Abrupt loss must not persist the later in-process position",
                serverPositionMs + SERVER_POSITION_TOLERANCE_MS < laterPositionMs,
            )

            val activePlayer = createAcceptancePlayer(instrumentation, surface, render)
            player = activePlayer
            val activeCoordinator = createTvheadendPlaybackCoordinator(session, activePlayer)
            coordinator = activeCoordinator
            val activeCoordinatorOwner = launch(start = CoroutineStart.UNDISPATCHED) { activeCoordinator.run() }
            coordinatorOwner = activeCoordinatorOwner
            assertEquals(
                PlaybackTargetResult.STARTED,
                activeCoordinator.setRecordingTarget(recordingId, RecordingPlaybackStart.RESUME),
            )
            instrumentation.runOnMainSync { activePlayer.play() }
            render.awaitPlayingVideo(instrumentation, activePlayer, "cross-process resume")
            withTimeout(RENDER_TIMEOUT_MS) {
                while (playerSnapshot(instrumentation, activePlayer).positionMs < serverPositionMs - RESUME_TOLERANCE_MS) {
                    render.assertHealthy("cross-process resume")
                    delay(POLL_INTERVAL_MS)
                }
            }

            val playCountBefore = serverEntry.playCount ?: 0L
            val pausePositionMs = awaitPlayerPosition(
                instrumentation = instrumentation,
                player = activePlayer,
                expectedMs = serverPositionMs + PAUSE_PROGRESS_DELTA_MS,
                timeoutMs = RENDER_TIMEOUT_MS,
                render = render,
                label = "explicit pause setup",
            )
            instrumentation.runOnMainSync { activePlayer.pause() }
            val pauseEntry = awaitServerPosition(session.dvrRepository, recordingId, pausePositionMs)
            assertEquals("Explicit pause must not mark watched", playCountBefore, pauseEntry.playCount ?: 0L)

            instrumentation.runOnMainSync { activePlayer.play() }
            delay(GENERIC_EXIT_PLAYBACK_MS)
            requireOwnedEntry(session.dvrRepository, priorState)
            assertEquals(PlaybackStopResult.STOPPED, activeCoordinator.stop())
            val genericExitEntry = withTimeout(PROGRESS_PUBLICATION_TIMEOUT_MS) {
                session.dvrRepository.entry(recordingId).first { entry ->
                    entry != null && (entry.playPosition?.inWholeMilliseconds ?: 0L) > pausePositionMs
                }
            }
            assertEquals(
                "An orderly exit below 95 percent must remain unwatched",
                playCountBefore,
                requireNotNull(genericExitEntry).playCount ?: 0L,
            )

            requireOwnedEntry(session.dvrRepository, priorState)
            assertEquals(
                PlaybackTargetResult.STARTED,
                activeCoordinator.setRecordingTarget(recordingId, RecordingPlaybackStart.START_OVER),
            )
            instrumentation.runOnMainSync { activePlayer.play() }
            render.awaitPlayingVideo(instrumentation, activePlayer, "natural completion setup")
            val naturalDurationMs = awaitKnownDuration(instrumentation, activePlayer)
            instrumentation.runOnMainSync {
                activePlayer.seekTo((naturalDurationMs - NATURAL_END_LEAD_MS).coerceAtLeast(0L))
            }
            awaitPlayerEnded(instrumentation, activePlayer, render, "natural completion")
            val naturalEntry = awaitExactPlayCount(session.dvrRepository, recordingId, playCountBefore + 1L)

            requireOwnedEntry(session.dvrRepository, priorState)
            assertEquals(
                PlaybackTargetResult.STARTED,
                activeCoordinator.setRecordingTarget(recordingId, RecordingPlaybackStart.START_OVER),
            )
            instrumentation.runOnMainSync { activePlayer.play() }
            render.awaitPlayingVideo(instrumentation, activePlayer, "orderly completion setup")
            val orderlyDurationMs = awaitKnownDuration(instrumentation, activePlayer)
            val orderlyPositionMs = (orderlyDurationMs * ORDERLY_COMPLETION_PERCENT / 100L)
                .coerceAtMost((orderlyDurationMs - 1L).coerceAtLeast(0L))
            instrumentation.runOnMainSync {
                activePlayer.seekTo(orderlyPositionMs)
                activePlayer.pause()
            }
            withTimeout(RENDER_TIMEOUT_MS) {
                while (playerSnapshot(instrumentation, activePlayer).positionMs < orderlyPositionMs) {
                    render.assertHealthy("orderly completion")
                    delay(POLL_INTERVAL_MS)
                }
            }
            val orderlySnapshot = playerSnapshot(instrumentation, activePlayer)
            assertFalse("Orderly completion must be paused before stop", orderlySnapshot.isPlaying)
            assertNotEquals(
                "Orderly completion must not reach natural end before stop",
                Player.STATE_ENDED,
                orderlySnapshot.playbackState,
            )
            val naturalPlayCount = naturalEntry.playCount ?: playCountBefore + 1L
            assertEquals(
                "Play count must remain unchanged until the orderly stop",
                naturalPlayCount,
                requireOwnedEntry(session.dvrRepository, priorState).playCount ?: 0L,
            )
            assertEquals(PlaybackStopResult.STOPPED, activeCoordinator.stop())
            val orderlyEntry = awaitExactPlayCount(
                session.dvrRepository,
                recordingId,
                naturalPlayCount + 1L,
            )

            assertEquals(PlaybackShutdownResult.DRAINED, activeCoordinator.shutdown(COORDINATOR_DRAIN_TIMEOUT))
            activeCoordinatorOwner.join()
            assertEquals(
                "No late progress event may duplicate the orderly completion",
                naturalPlayCount + 1L,
                requireOwnedEntry(session.dvrRepository, priorState).playCount ?: 0L,
            )
            session.dvrRepository.deleteEntry(recordingId).requireConfirmed()
            withTimeout(FIXTURE_CLEANUP_TIMEOUT_MS) {
                session.dvrRepository.state.first { repositoryState ->
                    repositoryState is DvrRepositoryState.Current &&
                        repositoryState.snapshot.entries.none { entry -> entry.id == recordingId }
                }
            }
            check(File(context.filesDir, ACCEPTANCE_STATE_FILE_NAME).delete()) {
                "Acceptance process state could not be removed"
            }
            fixtureRemoved = true

            session.shutdown()
            instrumentation.runOnMainSync { activePlayer.release() }
            surface.close(instrumentation)
            memoryAfterTeardown = memoryObservation()
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("p6_4_stage", "cross-process-completion-clean")
                    putLong("p6_4_saved_checkpoint_ms", checkpointMs)
                    putLong("p6_4_server_resume_ms", serverPositionMs)
                    putLong("p6_4_unreported_position_ms", laterPositionMs)
                    putLong("p6_4_pause_position_ms", pausePositionMs)
                    putLong("p6_4_natural_play_count", naturalEntry.playCount ?: -1L)
                    putLong("p6_4_orderly_play_count", orderlyEntry.playCount ?: -1L)
                    putMemory("p6_4_memory_after_teardown", requireNotNull(memoryAfterTeardown))
                    putBoolean("p6_4_fixture_removed", true)
                    putBoolean("p6_4_profile_consumed", true)
                },
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                runAcceptanceCleanup(
                    primaryFailure,
                    {
                        if (coordinatorOwner?.isActive == true) {
                            coordinator?.shutdown(COORDINATOR_DRAIN_TIMEOUT)
                        }
                    },
                    { withTimeout(COORDINATOR_JOIN_TIMEOUT_MS) { coordinatorOwner?.join() } },
                    {
                        if (!fixtureRemoved) {
                            val stateFile = File(context.filesDir, ACCEPTANCE_STATE_FILE_NAME)
                            if (cleanupRecording(session.dvrRepository, priorState) && stateFile.exists()) {
                                check(stateFile.delete()) { "Acceptance process state could not be removed" }
                            }
                        }
                    },
                    { session.shutdown() },
                    {
                        player?.let { activePlayer ->
                            instrumentation.runOnMainSync { activePlayer.release() }
                        }
                    },
                    { surface.close(instrumentation) },
                )
            }
        }
    }

    @Test(timeout = RECOVERY_TIMEOUT_MS)
    fun cleanup_owned_fixture_after_failed_acceptance() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val stateFile = File(context.filesDir, ACCEPTANCE_STATE_FILE_NAME)
        val priorState = readAcceptanceState(context)
        val profile = consumePrivateProfile(context.filesDir)
        val session = createTvheadendSession()

        try {
            assertEquals(SessionCommandResult.STARTED, session.connect(profile))
            awaitReady(session)
            awaitProgressSupport(session)
            val current = session.dvrRepository.state.value as? DvrRepositoryState.Current
                ?: throw AssertionError("DVR repository is not authoritative for fixture recovery")
            val candidate = current.snapshot.entries.firstOrNull { entry ->
                entry.id.value == priorState.recordingId
            }
            val recoveredState = if (candidate == null) {
                priorState
            } else {
                assertEquals("Acceptance recording marker changed", priorState.recordingMarker, candidate.title)
                val observedUuid = requireNotNull(candidate.uuid) {
                    "The recovery recording must have a stable UUID"
                }
                priorState.recordingUuid?.let { expectedUuid ->
                    assertEquals("Acceptance recording UUID changed", expectedUuid, observedUuid)
                }
                priorState.copy(recordingUuid = observedUuid).also { state ->
                    writeAcceptanceState(context, state)
                }
            }
            assertTrue(
                "Recovery requires authoritative deletion or absence",
                cleanupRecording(session.dvrRepository, recoveredState),
            )
            check(stateFile.delete()) { "Acceptance process state could not be removed" }
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("p6_4_stage", "failed-fixture-recovery-clean")
                    putBoolean("p6_4_fixture_removed", true)
                    putBoolean("p6_4_profile_consumed", true)
                },
            )
        } finally {
            withContext(NonCancellable) { session.shutdown() }
        }
    }
}

private data class AcceptanceState(
    val recordingId: Long,
    val recordingUuid: String?,
    val recordingMarker: String,
    val stageOnePid: Int,
    val stageTwoPid: Int? = null,
    val checkpointMs: Long? = null,
    val laterPositionMs: Long? = null,
)

private data class MemoryObservation(
    val javaHeapBytes: Long,
    val nativeHeapBytes: Long,
    val totalPssKilobytes: Long,
)

private fun createAcceptancePlayer(
    instrumentation: Instrumentation,
    surface: PlaybackSurface,
    render: RenderObservation,
): ExoPlayer {
    lateinit var player: ExoPlayer
    instrumentation.runOnMainSync {
        player = ExoPlayer.Builder(
            instrumentation.targetContext,
            createTvheadendRenderersFactory(instrumentation.targetContext),
        ).build().apply {
            volume = 0f
            setVideoSurface(surface.surface)
            addListener(render.playerListener)
            addAnalyticsListener(render.analyticsListener)
        }
    }
    return player
}

private suspend fun awaitReady(session: TvheadendSession): SessionState.Ready {
    val state = withTimeout(CONNECTION_TIMEOUT_MS) {
        session.state.first { candidate ->
            candidate is SessionState.Ready || candidate is SessionState.Unavailable
        }
    }
    return state as? SessionState.Ready
        ?: throw AssertionError("Real-server session did not become ready")
}

private suspend fun awaitProgressSupport(session: TvheadendSession) {
    val capability = withTimeout(PROGRESS_CAPABILITY_TIMEOUT_MS) {
        session.recordingProgressCapability.first { value -> value != RecordingProgressCapability.UNKNOWN }
    }
    assertEquals(
        "P6-4 requires the complete semantic HTSP v27+ recording-progress contract",
        RecordingProgressCapability.SUPPORTED,
        capability,
    )
}

private fun <T> DvrMutationResult<T>.requireConfirmed(): T = when (this) {
    is DvrMutationResult.Confirmed -> value
    is DvrMutationResult.AcceptedButUnconfirmed ->
        throw AssertionError("DVR mutation was accepted but not stream-confirmed")
    else -> throw AssertionError("DVR mutation failed: ${javaClass.simpleName}")
}

private suspend fun runAcceptanceCleanup(
    primaryFailure: Throwable?,
    vararg steps: suspend () -> Unit,
) {
    var cleanupFailure: Throwable? = null
    steps.forEach { step ->
        try {
            step()
        } catch (failure: Throwable) {
            cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
        }
    }
    cleanupFailure?.let { failure ->
        if (primaryFailure == null) throw failure
        primaryFailure.addSuppressed(failure)
    }
}

private suspend fun cleanupRecording(dvr: DvrRepository, state: AcceptanceState): Boolean {
    if (dvr.state.value !is DvrRepositoryState.Current) return false
    val id = DvrEntryId(state.recordingId)
    val existing = dvr.entries.value.firstOrNull { entry -> entry.id == id } ?: return true
    if (state.recordingUuid == null) return false
    existing.requireOwnedBy(state)
    if (existing.state == DvrEntryState.RECORDING) {
        dvr.stopEntry(id).requireConfirmed()
        requireOwnedEntry(dvr, state)
    }
    dvr.deleteEntry(id).requireConfirmed()
    withTimeout(FIXTURE_CLEANUP_TIMEOUT_MS) {
        dvr.state.first { repositoryState ->
            repositoryState is DvrRepositoryState.Current &&
                repositoryState.snapshot.entries.none { entry -> entry.id == id }
        }
    }
    return true
}

private fun requireOwnedEntry(dvr: DvrRepository, state: AcceptanceState): DvrEntry {
    val current = dvr.state.value as? DvrRepositoryState.Current
        ?: throw AssertionError("DVR repository is not authoritative for fixture ownership")
    return requireNotNull(current.snapshot.entries.firstOrNull { entry -> entry.id.value == state.recordingId }) {
        "The owned acceptance recording is absent"
    }.requireOwnedBy(state)
}

private fun DvrEntry.requireOwnedBy(state: AcceptanceState): DvrEntry {
    assertEquals(
        "Acceptance recording UUID changed",
        requireNotNull(state.recordingUuid) { "Acceptance recording UUID is missing" },
        uuid,
    )
    assertEquals("Acceptance recording marker changed", state.recordingMarker, title)
    return this
}

private suspend fun awaitServerPosition(
    dvr: DvrRepository,
    id: DvrEntryId,
    expectedMs: Long,
): DvrEntry = requireNotNull(
    withTimeout(PROGRESS_PUBLICATION_TIMEOUT_MS) {
        dvr.entry(id).first { entry ->
            entry?.playPosition?.inWholeMilliseconds?.let { position ->
                position >= expectedMs - SERVER_POSITION_TOLERANCE_MS
            } == true
        }
    },
)

private suspend fun awaitExactPlayCount(
    dvr: DvrRepository,
    id: DvrEntryId,
    expected: Long,
): DvrEntry {
    val entry = requireNotNull(withTimeout(PROGRESS_PUBLICATION_TIMEOUT_MS) {
        dvr.entry(id).first { entry -> (entry?.playCount ?: 0L) >= expected }
    })
    assertEquals("Play count changed by more than one policy event", expected, entry.playCount ?: 0L)
    return entry
}

private suspend fun awaitPlayerPosition(
    instrumentation: Instrumentation,
    player: ExoPlayer,
    expectedMs: Long,
    timeoutMs: Long,
    render: RenderObservation,
    label: String,
): Long = withTimeout(timeoutMs) {
    var position = playerSnapshot(instrumentation, player).positionMs
    while (position < expectedMs) {
        render.assertHealthy(label)
        delay(POLL_INTERVAL_MS)
        position = playerSnapshot(instrumentation, player).positionMs
    }
    position
}

private suspend fun awaitKnownDuration(instrumentation: Instrumentation, player: ExoPlayer): Long =
    withTimeout(RENDER_TIMEOUT_MS) {
        var duration = playerSnapshot(instrumentation, player).durationMs
        while (duration <= 0L || duration == androidx.media3.common.C.TIME_UNSET) {
            delay(POLL_INTERVAL_MS)
            duration = playerSnapshot(instrumentation, player).durationMs
        }
        duration
    }

private suspend fun awaitPlayerEnded(
    instrumentation: Instrumentation,
    player: ExoPlayer,
    render: RenderObservation,
    label: String,
) {
    withTimeout(RENDER_TIMEOUT_MS) {
        while (playerSnapshot(instrumentation, player).playbackState != Player.STATE_ENDED) {
            render.assertHealthy(label)
            delay(POLL_INTERVAL_MS)
        }
    }
}

private fun writeAcceptanceState(context: Context, state: AcceptanceState) {
    val root = JSONObject()
        .put("recording_id", state.recordingId)
        .put("recording_marker", state.recordingMarker)
        .put("stage_one_pid", state.stageOnePid)
    state.recordingUuid?.let { root.put("recording_uuid", it) }
    state.stageTwoPid?.let { root.put("stage_two_pid", it) }
    state.checkpointMs?.let { root.put("checkpoint_ms", it) }
    state.laterPositionMs?.let { root.put("later_position_ms", it) }
    context.openFileOutput(ACCEPTANCE_STATE_FILE_NAME, Context.MODE_PRIVATE).use { output ->
        output.write(root.toString().toByteArray(Charsets.UTF_8))
        output.fd.sync()
    }
}

private fun readAcceptanceState(context: Context): AcceptanceState {
    val file = File(context.filesDir, ACCEPTANCE_STATE_FILE_NAME)
    check(file.isFile) { "Acceptance process state is missing" }
    return try {
        JSONObject(file.readText()).let { root ->
            AcceptanceState(
                recordingId = root.getLong("recording_id"),
                recordingUuid = root.optString("recording_uuid").takeIf(String::isNotEmpty),
                recordingMarker = root.getString("recording_marker"),
                stageOnePid = root.getInt("stage_one_pid"),
                stageTwoPid = root.optInt("stage_two_pid").takeIf { root.has("stage_two_pid") },
                checkpointMs = root.optLong("checkpoint_ms").takeIf { root.has("checkpoint_ms") },
                laterPositionMs = root.optLong("later_position_ms").takeIf { root.has("later_position_ms") },
            )
        }
    } catch (_: Exception) {
        throw AssertionError("Acceptance process state is invalid")
    }
}

private fun memoryObservation(): MemoryObservation {
    val runtime = Runtime.getRuntime()
    return MemoryObservation(
        javaHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
        nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
        totalPssKilobytes = Debug.getPss(),
    )
}

private fun Bundle.putMemory(prefix: String, observation: MemoryObservation) {
    putLong("${prefix}_java_heap_bytes", observation.javaHeapBytes)
    putLong("${prefix}_native_heap_bytes", observation.nativeHeapBytes)
    putLong("${prefix}_total_pss_kb", observation.totalPssKilobytes)
}

private fun elapsedSince(startedAt: Long): Long = SystemClock.elapsedRealtime() - startedAt

private fun wholeSecondNow(): Instant = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)

private const val ACCEPTANCE_STATE_FILE_NAME = "p6-4-acceptance-state.json"
private const val CONNECTION_TIMEOUT_MS = 120_000L
private const val PROGRESS_CAPABILITY_TIMEOUT_MS = 30_000L
private const val EPG_TIMEOUT_MS = 120_000L
private const val RECORDING_START_TIMEOUT_MS = 60_000L
private const val RECORDING_COMPLETION_TIMEOUT_MS = 240_000L
private const val RECORDING_CAPTURE_MS = 75_000L
private const val PERIODIC_CHECKPOINT_TIMEOUT_MS = 90_000L
private const val PROGRESS_PUBLICATION_TIMEOUT_MS = 60_000L
private const val LATER_POSITION_TIMEOUT_MS = 30_000L
private const val FIXTURE_CLEANUP_TIMEOUT_MS = 60_000L
private const val COORDINATOR_JOIN_TIMEOUT_MS = 15_000L
private const val MINIMUM_CHECKPOINT_POSITION_MS = 35_000L
private const val UNREPORTED_PROGRESS_DELTA_MS = 10_000L
private const val SERVER_POSITION_TOLERANCE_MS = 2_000L
private const val RESUME_TOLERANCE_MS = 2_000L
private const val PAUSE_PROGRESS_DELTA_MS = 5_000L
private const val GENERIC_EXIT_PLAYBACK_MS = 3_000L
private const val NATURAL_END_LEAD_MS = 5_000L
private const val ORDERLY_COMPLETION_PERCENT = 96L
private const val STATUS_FLUSH_DELAY_MS = 1_000L
private const val STAGE_ONE_TIMEOUT_MS = 10 * 60 * 1_000L
private const val STAGE_TWO_TIMEOUT_MS = 5 * 60 * 1_000L
private const val STAGE_THREE_TIMEOUT_MS = 8 * 60 * 1_000L
private const val RECOVERY_TIMEOUT_MS = 3 * 60 * 1_000L
private val EPG_OBSERVATION_HORIZON = 6.hours
private val RECORDING_START_DELAY = 5.seconds
private val RECORDING_DURATION = 3.minutes
private val COORDINATOR_DRAIN_TIMEOUT = 5.seconds
