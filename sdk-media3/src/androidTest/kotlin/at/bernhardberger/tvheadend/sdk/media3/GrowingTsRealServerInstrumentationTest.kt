@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrMutationResult
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSchedule
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.SessionCommandResult
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import at.bernhardberger.tvheadend.sdk.core.createTvheadendSession
import at.bernhardberger.tvheadend.sdk.playback.RecordingId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionState
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class GrowingTsRealServerInstrumentationTest {
    @Test(timeout = LIVE_TEST_TIMEOUT_MS)
    fun active_pass_through_mpeg2_and_h264_render_seek_grow_and_cleanup() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val privateProfile = File(instrumentation.targetContext.filesDir, PRIVATE_PROFILE_FILE_NAME)
        val profile = consumePrivateProfile(instrumentation.targetContext.filesDir)
        val session = createTvheadendSession()
        val ownedRecordings = ArrayList<OwnedLiveRecording>()
        var primaryFailure: Throwable? = null

        try {
            assertEquals(SessionCommandResult.STARTED, session.connect(profile))
            val ready = withTimeout(LIVE_CONNECTION_TIMEOUT_MS) {
                session.state.first { state -> state is SessionState.Ready || state is SessionState.Unavailable }
            }
            assertTrue("P7-F3 real-server session must become Ready", ready is SessionState.Ready)
            val targets = discoverLiveTargets(session)
            val configId = withTimeout(LIVE_DVR_CONFIG_TIMEOUT_MS) {
                session.dvrRepository.configurations.first { configurations -> configurations.isNotEmpty() }.first().id
            }

            val results = targets.map { target ->
                verifyGrowingTarget(
                    instrumentation = instrumentation,
                    session = session,
                    target = target,
                    configId = configId,
                    ownedRecordings = ownedRecordings,
                )
            }

            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("p7_f3_live_codecs", results.joinToString { result -> result.mimeType })
                    putLong("p7_f3_live_min_initial_horizon_ms", results.minOf(LiveResult::initialHorizonMs))
                    putLong("p7_f3_live_min_growth_ms", results.minOf(LiveResult::growthMs))
                    putBoolean("p7_f3_live_profile_consumed", !privateProfile.exists())
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
        ownedRecordings: MutableList<OwnedLiveRecording>,
    ): LiveResult {
        val marker = "sdk-p7f3-${target.role.lowercase()}-${UUID.randomUUID().toString().take(8)}"
        val startsAt = wholeSecondNow() + LIVE_RECORDING_START_DELAY
        val scheduleResult = session.dvrRepository.scheduleEntry(
            DvrScheduleRequest(
                schedule = DvrSchedule.ExplicitTime(
                    channelId = target.channelId,
                    start = startsAt,
                    stop = startsAt + LIVE_RECORDING_DURATION,
                ),
                configId = configId,
                title = marker,
            ),
        )
        val recordingId = scheduleResult.recordingIdOrThrow()
        val owned = OwnedLiveRecording(recordingId, marker)
        ownedRecordings += owned
        val recording = withTimeout(LIVE_RECORDING_START_TIMEOUT_MS) {
            session.dvrRepository.entry(recordingId).first { entry ->
                entry?.state == DvrEntryState.RECORDING
            }
        }
        requireNotNull(recording).requireOwnedBy(owned)
        owned.uuid = requireNotNull(recording.uuid) { "Disposable recording must expose a stable UUID" }
        delay(LIVE_INITIAL_CAPTURE_MS)
        requireCurrentOwnedRecording(session, owned)

        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val failure = AtomicReference<PlaybackException?>()
        val latestMap = AtomicReference<GrowingTsSeekMap?>()
        val videoMimeTypes = Collections.synchronizedSet(mutableSetOf<String>())
        lateinit var player: ExoPlayer
        var playerCreated = false

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
                                    group.getTrackFormat(index).sampleMimeType
                                        ?.takeIf { mimeType -> mimeType.startsWith("video/") }
                                        ?.let(videoMimeTypes::add)
                                }
                            }
                        }
                    },
                )
                player.setMediaSource(
                    createTvheadendGrowingRecordingMediaSource(
                        recordings = session.recordings,
                        recordingId = RecordingId(recordingId.value),
                        onSeekMap = { map -> if (map is GrowingTsSeekMap) latestMap.set(map) },
                    ),
                )
                player.prepare()
                player.setPlaybackSpeed(LIVE_PLAYBACK_SPEED)
                player.play()
            }

            val initial = awaitLivePlayer(instrumentation, player, failure) { snapshot ->
                snapshot.seekable && snapshot.renderedFrames > 0L && target.mimeType in videoMimeTypes &&
                    latestMap.get() != null
            }
            val initialMap = checkNotNull(latestMap.get())
            val initialHorizonMs = initialMap.indexedHorizonUs / 1_000L
            assertTrue("Initial live index horizon must be positive", initialHorizonMs > 0L)
            val grown = awaitLivePlayer(instrumentation, player, failure) { snapshot ->
                val horizon = latestMap.get()?.indexedHorizonUs ?: 0L
                horizon >= initialMap.indexedHorizonUs + REQUIRED_LIVE_MAP_GROWTH_US &&
                    snapshot.renderedFrames > initial.renderedFrames && snapshot.playbackState != Player.STATE_ENDED
            }
            val grownMap = checkNotNull(latestMap.get())
            val candidates = grownMap.points.filter { point -> point.position > 0L && point.timeUs > 0L }
            assertTrue("Live pass-through stream must expose nonzero seek points", candidates.size >= 3)
            val targetPoint = candidates[candidates.size / 3]
            val targetMs = targetPoint.timeUs / 1_000L
            awaitLivePlayer(instrumentation, player, failure) { snapshot ->
                snapshot.positionMs >= targetMs + MINIMUM_LIVE_BACKWARD_SEEK_MS
            }
            val framesBeforeSeek = livePlayerSnapshot(instrumentation, player).renderedFrames
            val horizonBeforeSeek = grownMap.indexedHorizonUs
            instrumentation.runOnMainSync { player.seekTo(targetMs) }
            val afterSeek = awaitLivePlayer(instrumentation, player, failure) { snapshot ->
                snapshot.renderedFrames > framesBeforeSeek &&
                    snapshot.positionMs >= targetMs - LIVE_SEEK_TOLERANCE_MS
            }
            awaitLivePlayer(instrumentation, player, failure) { snapshot ->
                val horizon = latestMap.get()?.indexedHorizonUs ?: 0L
                horizon >= horizonBeforeSeek + REQUIRED_LIVE_MAP_GROWTH_US &&
                    snapshot.renderedFrames > afterSeek.renderedFrames && snapshot.playbackState != Player.STATE_ENDED
            }
            assertNotEquals("Temporary live boundary ended playback", Player.STATE_ENDED, grown.playbackState)
            assertTrue("Expected video codec disappeared after growth", target.mimeType in videoMimeTypes)
            return LiveResult(
                mimeType = target.mimeType,
                initialHorizonMs = initialHorizonMs,
                growthMs = (checkNotNull(latestMap.get()).indexedHorizonUs - initialMap.indexedHorizonUs) / 1_000L,
            )
        } finally {
            if (playerCreated) instrumentation.runOnMainSync { player.release() }
            surface.release()
            texture.release()
        }
    }
}

private data class LiveTarget(
    val role: String,
    val channelId: ChannelId,
    val mimeType: String,
)

private data class LiveResult(
    val mimeType: String,
    val initialHorizonMs: Long,
    val growthMs: Long,
)

private data class LivePlayerSnapshot(
    val positionMs: Long,
    val renderedFrames: Long,
    val playbackState: Int,
    val seekable: Boolean,
)

private class OwnedLiveRecording(
    val id: DvrEntryId,
    val marker: String,
    var uuid: String? = null,
)

private suspend fun discoverLiveTargets(session: TvheadendSession): List<LiveTarget> {
    val found = LinkedHashMap<SubscriptionStreamType, LiveTarget>()
    val desired = setOf(SubscriptionStreamType.MPEG2_VIDEO, SubscriptionStreamType.H264)
    for (channel in session.channelRepository.channels.value.take(MAXIMUM_CODEC_PROBE_CHANNELS)) {
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

private suspend fun cleanupOwnedRecording(session: TvheadendSession, owned: OwnedLiveRecording) {
    val current = withTimeout(LIVE_CLEANUP_CURRENT_TIMEOUT_MS) {
        session.dvrRepository.state.first { state -> state is DvrRepositoryState.Current }
    } as DvrRepositoryState.Current
    val existing = current.snapshot.entries.firstOrNull { entry -> entry.id == owned.id } ?: return
    existing.requireOwnedBy(owned)
    if (existing.state == DvrEntryState.RECORDING) {
        session.dvrRepository.stopEntry(owned.id).requireConfirmedMutation()
        withTimeout(LIVE_RECORDING_STOP_TIMEOUT_MS) {
            session.dvrRepository.entry(owned.id).first { entry -> entry?.state != DvrEntryState.RECORDING }
        }
    }
    requireCurrentOwnedRecording(session, owned)
    session.dvrRepository.deleteEntry(owned.id).requireConfirmedMutation()
    withTimeout(LIVE_RECORDING_DELETE_TIMEOUT_MS) {
        session.dvrRepository.state.first { state ->
            state is DvrRepositoryState.Current && state.snapshot.entries.none { entry -> entry.id == owned.id }
        }
    }
}

private fun requireCurrentOwnedRecording(session: TvheadendSession, owned: OwnedLiveRecording): DvrEntry {
    val current = session.dvrRepository.state.value as? DvrRepositoryState.Current
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

private fun awaitLivePlayer(
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
        Thread.sleep(LIVE_POLL_INTERVAL_MS)
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
                renderedFrames = player.videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: 0L,
                playbackState = player.playbackState,
                seekable = player.isCurrentMediaItemSeekable,
            ),
        )
    }
    return snapshot.get()
}

private const val PRIVATE_PROFILE_FILE_NAME: String = "p4-5-real-server.json"
private val LIVE_RECORDING_START_DELAY = 2.seconds
private val LIVE_RECORDING_DURATION = 90.seconds
private const val LIVE_INITIAL_CAPTURE_MS: Long = 12_000L
private const val REQUIRED_LIVE_MAP_GROWTH_US: Long = 5_000_000L
private const val MINIMUM_LIVE_BACKWARD_SEEK_MS: Long = 1_000L
private const val LIVE_SEEK_TOLERANCE_MS: Long = 2_000L
private const val LIVE_PLAYBACK_SPEED: Float = 4f
private const val LIVE_POLL_INTERVAL_MS: Long = 100L
private const val LIVE_PLAYER_TIMEOUT_MS: Long = 60_000L
private const val LIVE_CONNECTION_TIMEOUT_MS: Long = 45_000L
private const val LIVE_DVR_CONFIG_TIMEOUT_MS: Long = 30_000L
private const val LIVE_RECORDING_START_TIMEOUT_MS: Long = 30_000L
private const val LIVE_RECORDING_STOP_TIMEOUT_MS: Long = 30_000L
private const val LIVE_RECORDING_DELETE_TIMEOUT_MS: Long = 30_000L
private const val LIVE_CLEANUP_CURRENT_TIMEOUT_MS: Long = 10_000L
private const val MAXIMUM_CODEC_PROBE_CHANNELS: Int = 32
private const val CODEC_PROBE_TIMEOUT_MS: Long = 10_000L
private const val LIVE_TEST_TIMEOUT_MS: Long = 420_000L
