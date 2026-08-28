@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.graphics.SurfaceTexture
import android.media.MediaCodecInfo.CodecProfileLevel
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrConfigurationsState
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrMutationResult
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSchedule
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.ServerProfile
import at.bernhardberger.tvheadend.sdk.core.SessionCommandResult
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import at.bernhardberger.tvheadend.sdk.core.createTvheadendSession
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionState
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class GrowingTsRealServerInstrumentationTest {
    @Test(timeout = LIVE_TEST_TIMEOUT_MS)
    fun active_pass_through_mpeg2_and_h264_render_seek_grow_and_cleanup(): Unit =
        GrowingTsRealServerVerifier.run(
            evidencePrefix = "p7_f3_live",
            fixturePrefix = "sdk-p7f3",
            readyLabel = "P7-F3",
            discoverTargets = ::discoverLiveTargets,
        )
}

@RunWith(AndroidJUnit4::class)
internal class GrowingTsHevcRealServerInstrumentationTest {
    @Test(timeout = LIVE_TEST_TIMEOUT_MS)
    fun active_channel_900_hevc_main10_render_seek_grow_and_cleanup(): Unit =
        GrowingTsRealServerVerifier.run(
            evidencePrefix = "p7_c2_hevc",
            fixturePrefix = "sdk-p7c2",
            readyLabel = "P7-C2",
            discoverTargets = { session -> listOf(discoverHevcTarget(session)) },
        )
}

@RunWith(AndroidJUnit4::class)
internal class GrowingTsAcceptanceInstrumentationTest {
    @Test(timeout = ACCEPTANCE_TEST_TIMEOUT_MS)
    fun active_mpeg2_and_h264_complete_cancel_and_fail_closed(): Unit =
        GrowingTsRealServerVerifier.run(
            evidencePrefix = "p7_f5_acceptance",
            fixturePrefix = "sdk-p7f5",
            readyLabel = "P7-F5",
            discoverTargets = ::discoverLiveTargets,
            acceptance = true,
        )
}

private object GrowingTsRealServerVerifier {
    fun run(
        evidencePrefix: String,
        fixturePrefix: String,
        readyLabel: String,
        discoverTargets: suspend (TvheadendSession) -> List<LiveTarget>,
        acceptance: Boolean = false,
    ): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val privateProfile = File(instrumentation.targetContext.filesDir, PRIVATE_PROFILE_FILE_NAME)
        if (acceptance) {
            assertTrue("P7-F5 requires a provisioned one-use private profile", privateProfile.isFile)
        }
        val profile = consumePrivateProfile(instrumentation.targetContext.filesDir)
        val session = createTvheadendSession()
        val ownedRecordings = ArrayList<OwnedLiveRecording>()
        var primaryFailure: Throwable? = null

        try {
            assertEquals(SessionCommandResult.STARTED, session.connect(profile))
            val ready = withTimeout(LIVE_CONNECTION_TIMEOUT_MS) {
                session.observation.first { observation ->
                    observation.sessionState is SessionState.Ready ||
                        observation.sessionState is SessionState.Unavailable
                }
            }.sessionState
            assertTrue("$readyLabel real-server session must become Ready", ready is SessionState.Ready)
            val recoveredFixtureCount = cleanupStaleOwnedRecordings(session, fixturePrefix)
            val targets = discoverTargets(session)
            val configId = withTimeout(LIVE_DVR_CONFIG_TIMEOUT_MS) {
                session.observation.first { observation ->
                    (observation.dvrConfigurationsState as? DvrConfigurationsState.Current)
                        ?.configurations?.isNotEmpty() == true
                }
            }.let { observation ->
                (observation.dvrConfigurationsState as DvrConfigurationsState.Current)
                    .configurations.first().id
            }

            val results = targets.map { target ->
                verifyGrowingTarget(
                    instrumentation = instrumentation,
                    session = session,
                    target = target,
                    configId = configId,
                    fixturePrefix = fixturePrefix,
                    ownedRecordings = ownedRecordings,
                    recordingDuration = if (acceptance) {
                        ACCEPTANCE_RECORDING_DURATION
                    } else {
                        LIVE_RECORDING_DURATION
                    },
                    terminalMode = if (acceptance) {
                        LiveTerminalMode.COMPLETE
                    } else {
                        LiveTerminalMode.GROWTH_ONLY
                    },
                    reconnectSession = { reconnectReadySession(session, profile, readyLabel) },
                )
            }
            val disruption = if (acceptance) {
                verifyGrowingTarget(
                    instrumentation = instrumentation,
                    session = session,
                    target = targets.single { target -> target.mimeType == MimeTypes.VIDEO_H264 },
                    configId = configId,
                    fixturePrefix = fixturePrefix,
                    ownedRecordings = ownedRecordings,
                    recordingDuration = LIVE_RECORDING_DURATION,
                    terminalMode = LiveTerminalMode.CANCEL_AND_REPLACE_GENERATION,
                    reconnectSession = { reconnectReadySession(session, profile, readyLabel) },
                ).disruption
            } else {
                null
            }
            if (acceptance) cleanupOwnedRecordings(session, ownedRecordings)

            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("${evidencePrefix}_codecs", results.joinToString { result -> result.mimeType })
                    putLong(
                        "${evidencePrefix}_min_initial_horizon_ms",
                        results.minOf(LiveResult::initialHorizonMs),
                    )
                    putLong("${evidencePrefix}_min_growth_ms", results.minOf(LiveResult::growthMs))
                    putLong("${evidencePrefix}_min_seek_target_ms", results.minOf(LiveResult::seekTargetMs))
                    putLong(
                        "${evidencePrefix}_min_post_seek_frames",
                        results.minOf(LiveResult::postSeekRenderedFrames),
                    )
                    putBoolean("${evidencePrefix}_identity_preserved", results.all(LiveResult::identityPreserved))
                    putInt("${evidencePrefix}_recovered_fixtures", recoveredFixtureCount)
                    putBoolean("${evidencePrefix}_profile_consumed", !privateProfile.exists())
                    if (acceptance) {
                        val completions = results.map { result -> checkNotNull(result.completion) }
                        putLong(
                            "${evidencePrefix}_min_temporary_eof_wait_ms",
                            results.minOf { result -> checkNotNull(result.temporaryEofWaitMs) },
                        )
                        putLong(
                            "${evidencePrefix}_min_progress_position_ms",
                            completions.minOf(LiveCompletion::progressPositionMs),
                        )
                        putBoolean(
                            "${evidencePrefix}_completion_handoff",
                            completions.all(LiveCompletion::completionObserved),
                        )
                        putBoolean(
                            "${evidencePrefix}_watched_once",
                            completions.all { completion -> completion.watchedIncrement == 1L },
                        )
                        putBoolean(
                            "${evidencePrefix}_owner_cancelled",
                            checkNotNull(disruption).ownerCancelled,
                        )
                        putBoolean("${evidencePrefix}_generation_rejected", disruption.generationRejected)
                        putBoolean("${evidencePrefix}_fixtures_removed", ownedRecordings.isEmpty())
                    }
                },
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                val cleanupFailures = ArrayList<Throwable>()
                ownedRecordings.asReversed().forEach { owned ->
                    try {
                        cleanupOwnedRecording(session, owned)
                    } catch (failure: Throwable) {
                        cleanupFailures += failure
                    }
                }
                try {
                    session.shutdown()
                } catch (failure: Throwable) {
                    cleanupFailures += failure
                }
                if (privateProfile.exists()) {
                    cleanupFailures += AssertionError("One-use private profile was not consumed")
                }
                if (primaryFailure != null) {
                    cleanupFailures.forEach(checkNotNull(primaryFailure)::addSuppressed)
                } else if (cleanupFailures.isNotEmpty()) {
                    val first = cleanupFailures.first()
                    cleanupFailures.drop(1).forEach(first::addSuppressed)
                    throw first
                }
            }
        }
    }

    private suspend fun verifyGrowingTarget(
        instrumentation: android.app.Instrumentation,
        session: TvheadendSession,
        target: LiveTarget,
        configId: at.bernhardberger.tvheadend.sdk.core.DvrConfigId,
        fixturePrefix: String,
        ownedRecordings: MutableList<OwnedLiveRecording>,
        recordingDuration: kotlin.time.Duration,
        terminalMode: LiveTerminalMode,
        reconnectSession: suspend () -> Unit,
    ): LiveResult = coroutineScope {
        val markerRole = target.role.lowercase()
        check(markerRole in LIVE_FIXTURE_MARKER_ROLES) { "Unsupported disposable recording role" }
        val marker = "$fixturePrefix-$markerRole-${UUID.randomUUID().toString().take(8)}"
        val startsAt = wholeSecondNow() + LIVE_RECORDING_START_DELAY
        val scheduleResult = session.dvrRepository.scheduleEntry(
            requireNotNull(session.observation.value.currentSession),
            DvrScheduleRequest(
                schedule = DvrSchedule.ExplicitTime(
                    channelId = target.channelId,
                    start = startsAt,
                    stop = startsAt + recordingDuration,
                ),
                configId = configId,
                title = marker,
            ),
        )
        val recordingId = scheduleResult.recordingIdOrThrow()
        val owned = OwnedLiveRecording(recordingId, marker)
        ownedRecordings += owned
        val recording = withTimeout(LIVE_RECORDING_START_TIMEOUT_MS) {
            session.observation.first { observation ->
                observation.dvrEntry(recordingId)?.state == DvrEntryState.RECORDING
            }.dvrEntry(recordingId)
        }
        requireNotNull(recording).requireOwnedBy(owned)
        owned.uuid = requireNotNull(recording.uuid) { "Disposable recording must expose a stable UUID" }
        val playCountAtStart = recording.playCount ?: 0L
        delay(LIVE_INITIAL_CAPTURE_MS)
        requireCurrentOwnedRecording(session, owned)

        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val failure = AtomicReference<PlaybackException?>()
        val selectedVideoFormat = AtomicReference<Format?>()
        lateinit var player: ExoPlayer
        lateinit var coordinator: TvheadendPlaybackCoordinator
        var coordinatorOwner: Job? = null
        var playerCreated = false
        var primaryFailure: Throwable? = null

        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(
                    instrumentation.targetContext,
                    createTvheadendRenderersFactory(instrumentation.targetContext),
                ).setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(500, 30_000, 100, 100)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .setBackBuffer(0, false)
                        .build(),
                ).build()
                playerCreated = true
                player.volume = 0f
                player.setVideoSurface(surface)
                player.addListener(
                    object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            failure.compareAndSet(null, error)
                        }

                        override fun onTracksChanged(tracks: Tracks) {
                            tracks.groups.forEach { group ->
                                repeat(group.length) { index ->
                                    val format = group.getTrackFormat(index)
                                    if (
                                        format.sampleMimeType == target.mimeType &&
                                        group.isTrackSupported(index) &&
                                        group.isTrackSelected(index)
                                    ) {
                                        selectedVideoFormat.set(format)
                                    }
                                }
                            }
                        }
                    },
                )
            }
            coordinator = createTvheadendPlaybackCoordinator(session, player)
            coordinatorOwner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
            assertEquals(
                PlaybackTargetResult.STARTED,
                coordinator.setRecordingTarget(
                    recordingId = recordingId,
                    start = RecordingPlaybackStart.START_OVER,
                )
            )
            instrumentation.runOnMainSync {
                player.setPlaybackSpeed(LIVE_PLAYBACK_SPEED)
                player.play()
            }

            val initial = awaitLivePlayer(instrumentation, player, failure) { snapshot ->
                snapshot.seekable && snapshot.durationMs > 0L && snapshot.renderedFrames > 0L &&
                    selectedVideoFormat.get() != null
            }
            val initialHorizonMs = initial.durationMs
            assertTrue("Initial live index horizon must be positive", initialHorizonMs > 0L)
            if (target.requiresHevcMain10) assertHevcMain10(checkNotNull(selectedVideoFormat.get()))
            val growth = awaitRequiredGrowth(
                instrumentation = instrumentation,
                player = player,
                failure = failure,
                initial = initial,
                requireTemporaryEof = terminalMode != LiveTerminalMode.GROWTH_ONLY,
            )
            val grown = growth.snapshot
            val targetMs = (grown.durationMs / 3L).coerceAtLeast(MINIMUM_LIVE_BACKWARD_SEEK_MS)
            val beforeSeek = awaitLivePlayer(instrumentation, player, failure) { snapshot ->
                snapshot.positionMs >= targetMs + MINIMUM_LIVE_BACKWARD_SEEK_MS
            }
            val horizonBeforeSeekMs = grown.durationMs
            instrumentation.runOnMainSync { player.seekTo(targetMs) }
            val seekLanding = awaitLivePlayer(instrumentation, player, failure) { snapshot ->
                snapshot.positionMs <= beforeSeek.positionMs - MINIMUM_LIVE_BACKWARD_SEEK_MS &&
                    snapshot.positionMs in
                    (targetMs - LIVE_SEEK_TOLERANCE_MS)..(targetMs + LIVE_SEEK_TOLERANCE_MS)
            }
            val afterSeek = awaitLivePlayer(instrumentation, player, failure) { snapshot ->
                snapshot.renderedFrames > seekLanding.renderedFrames && snapshot.playbackState != Player.STATE_ENDED
            }
            val later = awaitLivePlayer(instrumentation, player, failure) { snapshot ->
                snapshot.durationMs >= horizonBeforeSeekMs + REQUIRED_LIVE_MAP_GROWTH_MS &&
                    snapshot.renderedFrames > afterSeek.renderedFrames && snapshot.playbackState != Player.STATE_ENDED
            }
            assertNotEquals("Temporary live boundary ended playback", Player.STATE_ENDED, later.playbackState)
            assertTrue("Expected selected video codec disappeared after growth", selectedVideoFormat.get() != null)
            val identityPreserved = requireCurrentOwnedRecording(session, owned).uuid == owned.uuid
            val completion = if (terminalMode == LiveTerminalMode.COMPLETE) {
                val observed = awaitLiveCompletion(
                    instrumentation = instrumentation,
                    player = player,
                    failure = failure,
                    session = session,
                    owned = owned,
                    playCountBefore = playCountAtStart,
                )
                val owner = checkNotNull(coordinatorOwner)
                assertEquals(
                    PlaybackShutdownResult.DRAINED,
                    coordinator.shutdown(LIVE_COORDINATOR_DRAIN_TIMEOUT),
                )
                withTimeout(LIVE_COORDINATOR_JOIN_TIMEOUT_MS) { owner.join() }
                coordinatorOwner = null
                failure.get()?.let { error ->
                    throw AssertionError("Orderly completion surfaced a playback failure", error)
                }
                delay(LIVE_WATCHED_STABILITY_MS)
                val stableIncrement =
                    (requireCurrentOwnedRecording(session, owned).playCount ?: 0L) - playCountAtStart
                assertEquals(
                    "Growing completion must remain watched exactly once after coordinator shutdown",
                    1L,
                    stableIncrement,
                )
                observed.copy(watchedIncrement = stableIncrement)
            } else {
                null
            }
            val disruption = if (terminalMode == LiveTerminalMode.CANCEL_AND_REPLACE_GENERATION) {
                val playCountBefore = playCountAtStart
                assertEquals(
                    "Active recording became watched before coordinator cancellation",
                    playCountBefore,
                    requireCurrentOwnedRecording(session, owned).playCount ?: 0L,
                )
                checkNotNull(coordinatorOwner).cancelAndJoin()
                coordinatorOwner = null
                failure.get()?.let { error ->
                    throw AssertionError("Coordinator cancellation surfaced a playback failure", error)
                }
                val cancelled = livePlayerSnapshot(instrumentation, player)
                assertEquals("Owner cancellation must clear the active source", 0, cancelled.mediaItemCount)
                assertEquals(
                    "Owner cancellation must not mark a growing recording watched",
                    playCountBefore,
                    requireCurrentOwnedRecording(session, owned).playCount ?: 0L,
                )
                delay(LIVE_CANCELLATION_STABILITY_MS)
                val cancellationStable = livePlayerSnapshot(instrumentation, player)
                assertEquals(
                    "The cancelled source was restored without a new target",
                    0,
                    cancellationStable.mediaItemCount,
                )
                assertTrue(
                    "Rendered-frame count increased after coordinator owner cancellation",
                    cancellationStable.renderedFrames <= cancelled.renderedFrames,
                )
                assertEquals(
                    "Owner cancellation published a delayed watched update",
                    playCountBefore,
                    requireCurrentOwnedRecording(session, owned).playCount ?: 0L,
                )

                selectedVideoFormat.set(null)
                coordinator = createTvheadendPlaybackCoordinator(session, player)
                coordinatorOwner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
                assertEquals(
                    PlaybackTargetResult.STARTED,
                    coordinator.setRecordingTarget(recordingId, RecordingPlaybackStart.START_OVER),
                )
                instrumentation.runOnMainSync {
                    player.setPlaybackSpeed(LIVE_PLAYBACK_SPEED)
                    player.play()
                }
                awaitLivePlayer(instrumentation, player, failure) { snapshot ->
                    snapshot.renderedFrames > cancelled.renderedFrames && selectedVideoFormat.get() != null
                }

                session.disconnect()
                val generationFailure = awaitPlaybackFailure(failure)
                assertTrue(
                    "Generation replacement must fail through the typed recording path",
                    generationFailure.hasRecordingFailure(RecordingFileFailure.CONNECTION_CHANGED),
                )
                val failed = livePlayerSnapshot(instrumentation, player)
                reconnectSession()
                delay(LIVE_GENERATION_STABILITY_MS)
                assertEquals(
                    "The invalidated growing source resumed after reconnect",
                    failed.renderedFrames,
                    livePlayerSnapshot(instrumentation, player).renderedFrames,
                )
                LiveDisruption(ownerCancelled = true, generationRejected = true)
            } else {
                null
            }
            return@coroutineScope LiveResult(
                mimeType = target.mimeType,
                initialHorizonMs = initialHorizonMs,
                growthMs = later.durationMs - initialHorizonMs,
                seekTargetMs = targetMs,
                postSeekRenderedFrames = later.renderedFrames - seekLanding.renderedFrames,
                identityPreserved = identityPreserved,
                temporaryEofWaitMs = growth.temporaryEofWaitMs,
                completion = completion,
                disruption = disruption,
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                val cleanupFailures = ArrayList<Throwable>()
                coordinatorOwner?.let { owner ->
                    if (owner.isActive) {
                        try {
                            assertEquals(
                                PlaybackShutdownResult.DRAINED,
                                coordinator.shutdown(LIVE_COORDINATOR_DRAIN_TIMEOUT),
                            )
                        } catch (failure: Throwable) {
                            cleanupFailures += failure
                        }
                    }
                    try {
                        withTimeout(LIVE_COORDINATOR_JOIN_TIMEOUT_MS) { owner.join() }
                    } catch (failure: Throwable) {
                        cleanupFailures += failure
                        owner.cancel()
                        try {
                            withTimeout(LIVE_COORDINATOR_JOIN_TIMEOUT_MS) { owner.join() }
                        } catch (cancellationFailure: Throwable) {
                            cleanupFailures += cancellationFailure
                        }
                    }
                }
                if (playerCreated) {
                    try {
                        instrumentation.runOnMainSync { player.release() }
                    } catch (failure: Throwable) {
                        cleanupFailures += failure
                    }
                }
                try {
                    surface.release()
                } catch (failure: Throwable) {
                    cleanupFailures += failure
                }
                try {
                    texture.release()
                } catch (failure: Throwable) {
                    cleanupFailures += failure
                }
                if (primaryFailure != null) {
                    cleanupFailures.forEach(checkNotNull(primaryFailure)::addSuppressed)
                } else if (cleanupFailures.isNotEmpty()) {
                    val first = cleanupFailures.first()
                    cleanupFailures.drop(1).forEach(first::addSuppressed)
                    throw first
                }
            }
        }
    }
}

private data class LiveTarget(
    val role: String,
    val channelId: ChannelId,
    val mimeType: String,
    val requiresHevcMain10: Boolean = false,
)

private enum class LiveTerminalMode {
    GROWTH_ONLY,
    COMPLETE,
    CANCEL_AND_REPLACE_GENERATION,
}

private data class LiveResult(
    val mimeType: String,
    val initialHorizonMs: Long,
    val growthMs: Long,
    val seekTargetMs: Long,
    val postSeekRenderedFrames: Long,
    val identityPreserved: Boolean,
    val temporaryEofWaitMs: Long?,
    val completion: LiveCompletion?,
    val disruption: LiveDisruption?,
)

private data class LiveGrowth(
    val snapshot: LivePlayerSnapshot,
    val temporaryEofWaitMs: Long?,
)

private data class LiveCompletion(
    val progressPositionMs: Long,
    val completionObserved: Boolean,
    val watchedIncrement: Long,
)

private data class LiveDisruption(
    val ownerCancelled: Boolean,
    val generationRejected: Boolean,
)

private data class LivePlayerSnapshot(
    val positionMs: Long,
    val durationMs: Long,
    val renderedFrames: Long,
    val playbackState: Int,
    val seekable: Boolean,
    val mediaItemCount: Int,
)

private class OwnedLiveRecording(
    val id: DvrEntryId,
    val marker: String,
    var uuid: String? = null,
)

private fun currentChannels(session: TvheadendSession) =
    (session.observation.value.channelState as ChannelRepositoryState.Current).catalog.channels

private suspend fun discoverLiveTargets(session: TvheadendSession): List<LiveTarget> {
    val found = LinkedHashMap<SubscriptionStreamType, LiveTarget>()
    val desired = setOf(SubscriptionStreamType.MPEG2_VIDEO, SubscriptionStreamType.H264)
    for (channel in currentChannels(session).take(MAXIMUM_CODEC_PROBE_CHANNELS)) {
        val result = withTimeoutOrNull(CODEC_PROBE_TIMEOUT_MS) {
            session.subscriptions.open(
                SubscriptionChannelId(channel.id.value),
                SubscriptionEventConsumer { },
            )
        } ?: continue
        if (result is SubscriptionOpenResult.Opened) {
            try {
                val playable = result.subscription.state.value as? SubscriptionState.Playable
                playable?.tracks?.streams?.map { stream -> stream.type }
                    ?.filterTo(mutableSetOf()) { type -> type in desired }
                    ?.forEach { type ->
                        found.putIfAbsent(
                            type,
                            when (type) {
                                SubscriptionStreamType.MPEG2_VIDEO ->
                                    LiveTarget("mpeg2", channel.id, MimeTypes.VIDEO_MPEG2)
                                SubscriptionStreamType.H264 ->
                                    LiveTarget("h264", channel.id, MimeTypes.VIDEO_H264)
                                else -> error("Unexpected codec probe type")
                            },
                        )
                    }
            } finally {
                withContext(NonCancellable) { result.subscription.close() }
            }
        }
        if (found.keys.containsAll(desired)) break
    }
    assertTrue("Bounded channel probes must find MPEG-2 and H.264", found.keys.containsAll(desired))
    return listOf(
        checkNotNull(found[SubscriptionStreamType.MPEG2_VIDEO]),
        checkNotNull(found[SubscriptionStreamType.H264]),
    )
}

private suspend fun discoverHevcTarget(session: TvheadendSession): LiveTarget {
    val channel = currentChannels(session).singleOrNull { candidate ->
        candidate.number == HEVC_CHANNEL_NUMBER
    } ?: throw AssertionError("The configured HEVC verification channel is unavailable")
    val opened = withTimeout(CODEC_PROBE_TIMEOUT_MS) {
        session.subscriptions.open(
            SubscriptionChannelId(channel.id.value),
            SubscriptionEventConsumer { },
        )
    } as? SubscriptionOpenResult.Opened
        ?: throw AssertionError("The configured HEVC verification channel could not be opened")
    try {
        val playable = opened.subscription.state.value as? SubscriptionState.Playable
            ?: throw AssertionError("The configured HEVC verification channel is not playable")
        assertTrue(
            "Channel 900 must expose HEVC before creating the disposable recording",
            playable.tracks.streams.any { stream -> stream.type == SubscriptionStreamType.H265 },
        )
        return LiveTarget(
            role = "hevc",
            channelId = channel.id,
            mimeType = MimeTypes.VIDEO_H265,
            requiresHevcMain10 = true,
        )
    } finally {
        withContext(NonCancellable) { opened.subscription.close() }
    }
}

private fun assertHevcMain10(format: Format) {
    val profileAndLevel = MediaCodecUtil.getHevcBaseLayerCodecProfileAndLevel(format)
    assertTrue(
        "The HEVC track profile must be supportable by MediaCodec",
        profileAndLevel?.isSupportableByMediaCodec == true,
    )
    val profile = profileAndLevel?.profile
    assertTrue("The HEVC track must expose a maintained Main 10 codec profile", profile in HEVC_MAIN_10_PROFILES)
    assertEquals("The HEVC source width changed", HEVC_SOURCE_WIDTH, format.width)
    assertEquals("The HEVC source height changed", HEVC_SOURCE_HEIGHT, format.height)
}

private suspend fun reconnectReadySession(
    session: TvheadendSession,
    profile: ServerProfile,
    readyLabel: String,
) {
    assertEquals(SessionCommandResult.STARTED, session.connect(profile))
    val ready = withTimeout(LIVE_CONNECTION_TIMEOUT_MS) {
        session.observation.first { observation ->
            observation.sessionState is SessionState.Ready ||
                observation.sessionState is SessionState.Unavailable
        }
    }.sessionState
    assertTrue("$readyLabel replacement generation must become Ready", ready is SessionState.Ready)
}

private suspend fun awaitRequiredGrowth(
    instrumentation: android.app.Instrumentation,
    player: ExoPlayer,
    failure: AtomicReference<PlaybackException?>,
    initial: LivePlayerSnapshot,
    requireTemporaryEof: Boolean,
): LiveGrowth {
    if (!requireTemporaryEof) {
        return LiveGrowth(
            snapshot = awaitLivePlayer(instrumentation, player, failure) { snapshot ->
                snapshot.durationMs >= initial.durationMs + REQUIRED_LIVE_MAP_GROWTH_MS &&
                    snapshot.renderedFrames > initial.renderedFrames &&
                    snapshot.playbackState != Player.STATE_ENDED
            },
            temporaryEofWaitMs = null,
        )
    }

    val boundary = awaitLivePlayer(instrumentation, player, failure) { snapshot ->
        snapshot.playbackState == Player.STATE_BUFFERING &&
            snapshot.durationMs >= initial.durationMs &&
            snapshot.positionMs >= snapshot.durationMs - TEMPORARY_BOUNDARY_TOLERANCE_MS &&
            snapshot.renderedFrames > initial.renderedFrames
    }
    val waitStartedAt = SystemClock.elapsedRealtime()
    val grown = withTimeout(LIVE_TEMPORARY_EOF_WAIT_LIMIT_MS) {
        awaitLivePlayer(instrumentation, player, failure) { snapshot ->
            snapshot.durationMs >= boundary.durationMs + REQUIRED_LIVE_MAP_GROWTH_MS &&
                snapshot.renderedFrames > boundary.renderedFrames &&
                snapshot.playbackState != Player.STATE_ENDED
        }
    }
    val waitedMs = SystemClock.elapsedRealtime() - waitStartedAt
    assertTrue("Temporary EOF wait exceeded the transport deadline", waitedMs <= LIVE_TEMPORARY_EOF_WAIT_LIMIT_MS)
    return LiveGrowth(grown, waitedMs)
}

private suspend fun awaitLiveCompletion(
    instrumentation: android.app.Instrumentation,
    player: ExoPlayer,
    failure: AtomicReference<PlaybackException?>,
    session: TvheadendSession,
    owned: OwnedLiveRecording,
    playCountBefore: Long,
): LiveCompletion {
    val progress = requireNotNull(withTimeout(LIVE_PROGRESS_PUBLICATION_TIMEOUT_MS) {
        session.observation.first { observation ->
            val entry = observation.dvrEntry(owned.id)
            entry?.title == owned.marker &&
                entry.uuid == owned.uuid &&
                (entry.playPosition?.inWholeMilliseconds ?: 0L) > 0L &&
                (entry.playCount ?: 0L) == playCountBefore
        }.dvrEntry(owned.id)
    }).requireOwnedBy(owned)
    val progressPositionMs = requireNotNull(progress.playPosition).inWholeMilliseconds

    assertEquals(
        "Disposable recording must still be active before completion",
        DvrEntryState.RECORDING,
        requireCurrentOwnedRecording(session, owned).state,
    )
    session.dvrRepository.stopEntry(
        requireNotNull(session.observation.value.currentSession),
        owned.id,
    ).requireConfirmedMutation()
    requireNotNull(withTimeout(LIVE_RECORDING_COMPLETION_TIMEOUT_MS) {
        session.observation.first { observation ->
            val entry = observation.dvrEntry(owned.id)
            entry?.title == owned.marker && entry.uuid == owned.uuid && entry.state == DvrEntryState.COMPLETED
        }.dvrEntry(owned.id)
    }).requireOwnedBy(owned)
    awaitLivePlayer(instrumentation, player, failure) { snapshot ->
        snapshot.playbackState == Player.STATE_ENDED
    }
    val watched = requireNotNull(withTimeout(LIVE_PROGRESS_PUBLICATION_TIMEOUT_MS) {
        session.observation.first { observation ->
            val entry = observation.dvrEntry(owned.id)
            entry?.title == owned.marker &&
                entry.uuid == owned.uuid &&
                (entry.playCount ?: 0L) >= playCountBefore + 1L
        }.dvrEntry(owned.id)
    }).requireOwnedBy(owned)
    val watchedIncrement = (watched.playCount ?: 0L) - playCountBefore
    assertEquals("Orderly growing completion must mark watched exactly once", 1L, watchedIncrement)
    return LiveCompletion(
        progressPositionMs = progressPositionMs,
        completionObserved = true,
        watchedIncrement = watchedIncrement,
    )
}

private suspend fun awaitPlaybackFailure(
    failure: AtomicReference<PlaybackException?>,
): PlaybackException = withTimeout(LIVE_GENERATION_FAILURE_TIMEOUT_MS) {
    while (true) {
        failure.get()?.let { return@withTimeout it }
        delay(LIVE_POLL_INTERVAL_MS)
    }
    error("unreachable")
}

private fun Throwable.hasRecordingFailure(expected: RecordingFileFailure): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is TvheadendRecordingException && current.failure == expected) return true
        current = current.cause
    }
    return false
}

private suspend fun cleanupOwnedRecordings(
    session: TvheadendSession,
    ownedRecordings: MutableList<OwnedLiveRecording>,
) {
    val markers = ownedRecordings.mapTo(mutableSetOf(), OwnedLiveRecording::marker)
    while (ownedRecordings.isNotEmpty()) {
        cleanupOwnedRecording(session, ownedRecordings.last())
        ownedRecordings.removeAt(ownedRecordings.lastIndex)
    }
    val current = withTimeout(LIVE_CLEANUP_CURRENT_TIMEOUT_MS) {
        session.observation.first { observation ->
            observation.dvrState is DvrRepositoryState.Current
        }
    }.dvrState as DvrRepositoryState.Current
    assertTrue(
        "Package-owned recordings remained after cleanup",
        current.snapshot.entries.none { entry -> entry.title?.let(markers::contains) == true },
    )
}

private suspend fun cleanupOwnedRecording(session: TvheadendSession, owned: OwnedLiveRecording) {
    val current = withTimeout(LIVE_CLEANUP_CURRENT_TIMEOUT_MS) {
        session.observation.first { observation ->
            observation.dvrState is DvrRepositoryState.Current
        }
    }.dvrState as DvrRepositoryState.Current
    val existing = current.snapshot.entries.firstOrNull { entry -> entry.id == owned.id } ?: return
    if (owned.uuid == null) {
        throw AssertionError("Refusing disposable recording cleanup before UUID capture")
    }
    existing.requireOwnedBy(owned)
    if (existing.state == DvrEntryState.RECORDING) {
        session.dvrRepository.stopEntry(
            requireNotNull(session.observation.value.currentSession),
            owned.id,
        ).requireConfirmedMutation()
        withTimeout(LIVE_RECORDING_STOP_TIMEOUT_MS) {
            session.observation.first { observation ->
                observation.dvrEntry(owned.id)?.state != DvrEntryState.RECORDING
            }
        }
    }
    requireCurrentOwnedRecording(session, owned)
    session.dvrRepository.deleteEntry(
        requireNotNull(session.observation.value.currentSession),
        owned.id,
    ).requireConfirmedMutation()
    withTimeout(LIVE_RECORDING_DELETE_TIMEOUT_MS) {
        session.observation.first { observation ->
            observation.dvrState is DvrRepositoryState.Current &&
                observation.dvrEntry(owned.id) == null
        }
    }
}

private suspend fun cleanupStaleOwnedRecordings(session: TvheadendSession, fixturePrefix: String): Int {
    val current = withTimeout(LIVE_CLEANUP_CURRENT_TIMEOUT_MS) {
        session.observation.first { observation ->
            observation.dvrState is DvrRepositoryState.Current
        }
    }.dvrState as DvrRepositoryState.Current
    val stale = current.snapshot.entries.filter { entry ->
        entry.title?.isOwnedLiveFixtureMarker(fixturePrefix) == true
    }
    assertTrue("Refusing an unbounded stale-fixture cleanup", stale.size <= MAXIMUM_STALE_FIXTURES)
    stale.forEach { entry ->
        val marker = requireNotNull(entry.title)
        val uuid = requireNotNull(entry.uuid) { "Stale disposable recording must expose a stable UUID" }
        cleanupOwnedRecording(
            session,
            OwnedLiveRecording(entry.id, marker, uuid),
        )
    }
    return stale.size
}

private fun String.isOwnedLiveFixtureMarker(fixturePrefix: String): Boolean {
    val prefix = "$fixturePrefix-"
    if (!startsWith(prefix)) return false
    val suffix = removePrefix(prefix)
    val separator = suffix.lastIndexOf('-')
    if (separator <= 0) return false
    val role = suffix.substring(0, separator)
    val token = suffix.substring(separator + 1)
    return role in LIVE_FIXTURE_MARKER_ROLES &&
        token.length == LIVE_FIXTURE_MARKER_TOKEN_LENGTH &&
        token.all { character -> character in '0'..'9' || character in 'a'..'f' }
}

private fun requireCurrentOwnedRecording(session: TvheadendSession, owned: OwnedLiveRecording): DvrEntry {
    val current = session.observation.value.dvrState as? DvrRepositoryState.Current
        ?: throw AssertionError("DVR repository is not current during fixture verification")
    return requireNotNull(current.snapshot.entries.firstOrNull { entry -> entry.id == owned.id }) {
        "Owned disposable recording is absent"
    }.requireOwnedBy(owned)
}

private fun DvrEntry.requireOwnedBy(owned: OwnedLiveRecording): DvrEntry {
    assertTrue("Disposable recording marker changed", owned.marker == title)
    owned.uuid?.let { expected -> assertTrue("Disposable recording UUID changed", expected == uuid) }
    return this
}

private fun DvrMutationResult<DvrEntryId>.recordingIdOrThrow(): DvrEntryId = when (this) {
    is DvrMutationResult.Confirmed -> value
    is DvrMutationResult.AcceptedButUnconfirmed -> value
    else -> throw AssertionError("Disposable recording schedule was rejected: ${javaClass.simpleName}")
}

private fun DvrMutationResult<*>.requireConfirmedMutation() {
    if (this !is DvrMutationResult.Confirmed) {
        throw AssertionError("Disposable recording cleanup was not confirmed: ${javaClass.simpleName}")
    }
}

private fun wholeSecondNow(): kotlin.time.Instant = Clock.System.now().let { now ->
    kotlin.time.Instant.fromEpochSeconds(now.epochSeconds)
}

private suspend fun awaitLivePlayer(
    instrumentation: android.app.Instrumentation,
    player: ExoPlayer,
    failure: AtomicReference<PlaybackException?>,
    predicate: (LivePlayerSnapshot) -> Boolean,
): LivePlayerSnapshot {
    val deadline = SystemClock.elapsedRealtime() + LIVE_PLAYER_TIMEOUT_MS
    while (SystemClock.elapsedRealtime() < deadline) {
        failure.get()?.let { error -> throw AssertionError("Growing pass-through playback failed", error) }
        val snapshot = livePlayerSnapshot(instrumentation, player)
        if (predicate(snapshot)) return snapshot
        delay(LIVE_POLL_INTERVAL_MS)
    }
    throw AssertionError("Growing pass-through playback did not reach the required state")
}

private fun livePlayerSnapshot(
    instrumentation: android.app.Instrumentation,
    player: ExoPlayer,
): LivePlayerSnapshot {
    val snapshot = AtomicReference<LivePlayerSnapshot>()
    instrumentation.runOnMainSync {
        snapshot.set(
            LivePlayerSnapshot(
                positionMs = player.currentPosition,
                durationMs = player.duration,
                renderedFrames = player.videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: 0L,
                playbackState = player.playbackState,
                seekable = player.isCurrentMediaItemSeekable,
                mediaItemCount = player.mediaItemCount,
            ),
        )
    }
    return snapshot.get()
}

private const val PRIVATE_PROFILE_FILE_NAME: String = "p4-5-real-server.json"
private val LIVE_RECORDING_START_DELAY = 2.seconds
private val LIVE_RECORDING_DURATION = 90.seconds
private val ACCEPTANCE_RECORDING_DURATION = 75.seconds
private val LIVE_COORDINATOR_DRAIN_TIMEOUT = 5.seconds
private const val LIVE_INITIAL_CAPTURE_MS: Long = 12_000L
private const val REQUIRED_LIVE_MAP_GROWTH_MS: Long = 5_000L
private const val TEMPORARY_BOUNDARY_TOLERANCE_MS: Long = 2_000L
private const val LIVE_TEMPORARY_EOF_WAIT_LIMIT_MS: Long = 30_000L
private const val MINIMUM_LIVE_BACKWARD_SEEK_MS: Long = 1_000L
private const val LIVE_SEEK_TOLERANCE_MS: Long = 2_000L
private const val LIVE_PLAYBACK_SPEED: Float = 4f
private const val LIVE_POLL_INTERVAL_MS: Long = 100L
private const val LIVE_PLAYER_TIMEOUT_MS: Long = 60_000L
private const val LIVE_CONNECTION_TIMEOUT_MS: Long = 45_000L
private const val LIVE_DVR_CONFIG_TIMEOUT_MS: Long = 30_000L
private const val LIVE_RECORDING_START_TIMEOUT_MS: Long = 30_000L
private const val LIVE_RECORDING_COMPLETION_TIMEOUT_MS: Long = 120_000L
private const val LIVE_PROGRESS_PUBLICATION_TIMEOUT_MS: Long = 60_000L
private const val LIVE_RECORDING_STOP_TIMEOUT_MS: Long = 30_000L
private const val LIVE_RECORDING_DELETE_TIMEOUT_MS: Long = 30_000L
private const val LIVE_CLEANUP_CURRENT_TIMEOUT_MS: Long = 10_000L
private const val LIVE_COORDINATOR_JOIN_TIMEOUT_MS: Long = 10_000L
private const val LIVE_CANCELLATION_STABILITY_MS: Long = 1_000L
private const val LIVE_WATCHED_STABILITY_MS: Long = 1_000L
private const val LIVE_GENERATION_FAILURE_TIMEOUT_MS: Long = 60_000L
private const val LIVE_GENERATION_STABILITY_MS: Long = 2_000L
private const val MAXIMUM_CODEC_PROBE_CHANNELS: Int = 32
private const val MAXIMUM_STALE_FIXTURES: Int = 4
private const val LIVE_FIXTURE_MARKER_TOKEN_LENGTH: Int = 8
private const val CODEC_PROBE_TIMEOUT_MS: Long = 10_000L
private const val LIVE_TEST_TIMEOUT_MS: Long = 420_000L
private const val ACCEPTANCE_TEST_TIMEOUT_MS: Long = 600_000L
private const val HEVC_CHANNEL_NUMBER: Long = 900L
private const val HEVC_SOURCE_WIDTH: Int = 3_840
private const val HEVC_SOURCE_HEIGHT: Int = 2_160
private val LIVE_FIXTURE_MARKER_ROLES = setOf("mpeg2", "h264", "hevc")
private val HEVC_MAIN_10_PROFILES = setOf(
    CodecProfileLevel.HEVCProfileMain10,
    CodecProfileLevel.HEVCProfileMain10HDR10,
)
