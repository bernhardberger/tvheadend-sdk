@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackExit
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileOpener
import at.bernhardberger.tvheadend.sdk.playback.RecordingId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpener
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal enum class PlaybackPlayerInstallStatus {
    STARTED,
    NOT_READY,
    RECORDING_PROGRESS_UNSUPPORTED,
    TARGET_UNAVAILABLE,
    GROWING_RECORDING_RESUME_UNSUPPORTED,
    GROWING_RECORDING_DEFERRED,
    PLAYER_UNAVAILABLE,
    CANCELLED,
}

internal data class PlaybackPlayerInstallResult(
    val status: PlaybackPlayerInstallStatus,
    val retiredTarget: Boolean = false,
    val retiredRecording: RetiredRecordingTarget? = null,
    val installedRecording: RecordingAdmission.Accepted? = null,
)

internal data class PlaybackPlayerStopResult(
    val cancelled: Boolean = false,
    val playerAvailable: Boolean = true,
    val retiredTarget: Boolean = false,
    val retiredRecording: RetiredRecordingTarget? = null,
)

internal data class RetiredRecordingTarget(
    val token: PlaybackTargetToken,
    val snapshot: PlaybackPlayerSnapshot,
    val exit: DvrPlaybackExit,
    val growingFinalEndProven: Boolean,
)

internal sealed interface RecordingAdmission {
    sealed interface Accepted : RecordingAdmission

    data class Completed(
        val resumePosition: Duration?,
    ) : Accepted

    class Growing(
        val fence: GrowingRecordingFence,
        val lease: GrowingRecordingFileLease,
    ) : Accepted {
        override fun toString(): String = "RecordingAdmission.Growing(<redacted>)"
    }

    data object NotReady : RecordingAdmission

    data object ProgressUnsupported : RecordingAdmission

    data object TargetUnavailable : RecordingAdmission

    data object GrowingResumeUnsupported : RecordingAdmission

    data object GrowingRecordingDeferred : RecordingAdmission
}

internal interface PlaybackCoordinatorPlayer {
    suspend fun installLive(
        ticket: PlayerOperationTicket,
        token: PlaybackTargetToken,
        channelId: SubscriptionChannelId,
        options: SubscriptionOptions = SubscriptionOptions(),
    ): PlaybackPlayerInstallResult

    suspend fun installRecording(
        ticket: PlayerOperationTicket,
        token: PlaybackTargetToken,
        recordingId: RecordingId,
        start: RecordingPlaybackStart,
    ): PlaybackPlayerInstallResult

    suspend fun stop(ticket: PlayerOperationTicket): PlaybackPlayerStopResult

    suspend fun snapshot(token: PlaybackTargetToken): PlaybackPlayerSnapshot?

    suspend fun abandon()
}

internal interface CoordinatorMediaSource

internal interface CoordinatorPlaybackRecovery : AutoCloseable {
    fun beginPlaybackTarget()
}

internal interface CoordinatorRecordingResume : AutoCloseable {
    fun beginPlaybackTarget(recordingId: RecordingId, position: Duration?)
}

internal interface CoordinatorPlaybackAccess {
    val looper: CoordinatorLooper

    fun requireApplicationLooper()

    fun snapshot(): PlaybackPlayerSnapshot

    fun addListener(listener: Player.Listener)

    fun removeListener(listener: Player.Listener)

    fun createLiveSource(
        channelId: SubscriptionChannelId,
        options: SubscriptionOptions,
    ): CoordinatorMediaSource

    fun createRecordingSource(recordingId: RecordingId): CoordinatorMediaSource

    fun createGrowingRecordingSource(
        recordingId: RecordingId,
        lease: GrowingRecordingFileLease,
        onFinalEnd: () -> Unit,
    ): CoordinatorMediaSource

    fun createRecovery(onRecoveryRequired: (PlaybackRecoveryReason) -> Unit): CoordinatorPlaybackRecovery

    fun createResume(): CoordinatorRecordingResume

    fun setMediaSource(source: CoordinatorMediaSource)

    fun prepare()

    fun stop()

    fun clearMediaItems()
}

internal class Media3PlaybackCoordinatorPlayer(
    private val access: CoordinatorPlaybackAccess,
    private val events: PlaybackPlayerEventAccumulator,
    private val admitRecording: (RecordingId, RecordingPlaybackStart) -> RecordingAdmission,
) : PlaybackCoordinatorPlayer {
    private val executor = PlayerLooperExecutor(access.looper)
    private var active: InstalledPlayerTarget? = null

    override suspend fun installLive(
        ticket: PlayerOperationTicket,
        token: PlaybackTargetToken,
        channelId: SubscriptionChannelId,
        options: SubscriptionOptions,
    ): PlaybackPlayerInstallResult = when (
        val operation = executor.execute(ticket) { installLiveOnLooper(token, channelId, options) }
    ) {
        is LooperOperationResult.Success -> operation.value
        LooperOperationResult.Cancelled -> PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.CANCELLED)
        LooperOperationResult.Unavailable ->
            PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE)
    }

    override suspend fun installRecording(
        ticket: PlayerOperationTicket,
        token: PlaybackTargetToken,
        recordingId: RecordingId,
        start: RecordingPlaybackStart,
    ): PlaybackPlayerInstallResult = when (
        val operation = executor.execute(ticket) {
            installRecordingOnLooper(token, recordingId, start)
        }
    ) {
        is LooperOperationResult.Success -> operation.value
        LooperOperationResult.Cancelled -> PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.CANCELLED)
        LooperOperationResult.Unavailable ->
            PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE)
    }

    override suspend fun stop(ticket: PlayerOperationTicket): PlaybackPlayerStopResult = when (
        val operation = executor.execute(ticket) {
            val retirement = retireActiveOnLooper()
            PlaybackPlayerStopResult(
                playerAvailable = retirement.playerAvailable,
                retiredTarget = retirement.retiredTarget,
                retiredRecording = retirement.retiredRecording,
            )
        }
    ) {
        is LooperOperationResult.Success -> operation.value
        LooperOperationResult.Cancelled -> PlaybackPlayerStopResult(cancelled = true)
        LooperOperationResult.Unavailable -> PlaybackPlayerStopResult(playerAvailable = false)
    }

    override suspend fun snapshot(token: PlaybackTargetToken): PlaybackPlayerSnapshot? {
        val ticket = PlayerOperationTicket()
        return when (
            val operation = executor.execute(ticket) {
                access.requireApplicationLooper()
                access.snapshot().takeIf { active?.token === token && token.isActive() }
            }
        ) {
            is LooperOperationResult.Success -> operation.value
            LooperOperationResult.Cancelled,
            LooperOperationResult.Unavailable,
            -> null
        }
    }

    override suspend fun abandon() {
        executor.execute(PlayerOperationTicket()) { retireActiveOnLooper() }
    }

    private fun installLiveOnLooper(
        token: PlaybackTargetToken,
        channelId: SubscriptionChannelId,
        options: SubscriptionOptions,
    ): PlaybackPlayerInstallResult {
        access.requireApplicationLooper()
        val source = try {
            access.createLiveSource(channelId, options)
        } catch (_: Exception) {
            return PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE)
        }
        val retirement = retireActiveOnLooper()
        if (!retirement.playerAvailable) return retirement.failedInstall()

        val listener = targetListener(token, recording = false)
        val installed = InstalledPlayerTarget.Live(token, listener)
        active = installed
        return try {
            installed.listenerAttached = true
            access.addListener(listener)
            installed.recovery = access.createRecovery { reason -> publishRecovery(token, reason) }
            installed.recovery?.beginPlaybackTarget()
            installed.sourceInstalled = true
            access.setMediaSource(source)
            access.prepare()
            PlaybackPlayerInstallResult(
                status = PlaybackPlayerInstallStatus.STARTED,
                retiredTarget = retirement.retiredTarget,
                retiredRecording = retirement.retiredRecording,
            )
        } catch (_: Exception) {
            rollbackInstalledOnLooper(installed)
            retirement.failedInstall()
        }
    }

    private fun installRecordingOnLooper(
        token: PlaybackTargetToken,
        recordingId: RecordingId,
        start: RecordingPlaybackStart,
    ): PlaybackPlayerInstallResult {
        access.requireApplicationLooper()
        val admission = admitRecording(recordingId, start)
        val accepted = when (admission) {
            is RecordingAdmission.Accepted -> admission
            RecordingAdmission.NotReady ->
                return PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.NOT_READY)
            RecordingAdmission.ProgressUnsupported ->
                return PlaybackPlayerInstallResult(
                    PlaybackPlayerInstallStatus.RECORDING_PROGRESS_UNSUPPORTED,
                )
            RecordingAdmission.TargetUnavailable ->
                return PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.TARGET_UNAVAILABLE)
            RecordingAdmission.GrowingResumeUnsupported ->
                return PlaybackPlayerInstallResult(
                    PlaybackPlayerInstallStatus.GROWING_RECORDING_RESUME_UNSUPPORTED,
                )
            RecordingAdmission.GrowingRecordingDeferred ->
                return PlaybackPlayerInstallResult(
                    PlaybackPlayerInstallStatus.GROWING_RECORDING_DEFERRED,
                )
        }
        val finality = (accepted as? RecordingAdmission.Growing)?.let { GrowingFinalitySignal() }
        val source = try {
            when (accepted) {
                is RecordingAdmission.Completed -> access.createRecordingSource(recordingId)
                is RecordingAdmission.Growing -> access.createGrowingRecordingSource(
                    recordingId = recordingId,
                    lease = accepted.lease,
                    onFinalEnd = checkNotNull(finality)::prove,
                )
            }
        } catch (_: Exception) {
            return PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE)
        }
        val retirement = retireActiveOnLooper()
        if (!retirement.playerAvailable) return retirement.failedInstall()

        val listener = targetListener(token, recording = true)
        val installed = InstalledPlayerTarget.Recording(token, listener, finality)
        active = installed
        return try {
            installed.listenerAttached = true
            access.addListener(listener)
            installed.sourceInstalled = true
            access.setMediaSource(source)
            if (accepted is RecordingAdmission.Completed) {
                installed.resume = access.createResume()
                installed.resume?.beginPlaybackTarget(recordingId, accepted.resumePosition)
            }
            access.prepare()
            PlaybackPlayerInstallResult(
                status = PlaybackPlayerInstallStatus.STARTED,
                retiredTarget = retirement.retiredTarget,
                retiredRecording = retirement.retiredRecording,
                installedRecording = accepted,
            )
        } catch (_: Exception) {
            rollbackInstalledOnLooper(installed)
            retirement.failedInstall()
        }
    }

    private fun targetListener(
        token: PlaybackTargetToken,
        recording: Boolean,
    ): Player.Listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            access.requireApplicationLooper()
            if (
                !playWhenReady &&
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST &&
                active?.token === token
            ) {
                events.publish(
                    PlaybackPlayerEvent(
                        token = token,
                        snapshot = access.snapshot(),
                        paused = true,
                    ),
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            access.requireApplicationLooper()
            if (recording && playbackState == Player.STATE_ENDED && active?.token === token) {
                val installed = active as? InstalledPlayerTarget.Recording
                events.publish(
                    PlaybackPlayerEvent(
                        token = token,
                        snapshot = access.snapshot(),
                        terminalExit = DvrPlaybackExit.NATURAL_END,
                        growingFinalEndProven = installed?.finality?.isProven() == true,
                    ),
                )
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            access.requireApplicationLooper()
            if (recording && active?.token === token) {
                events.publish(
                    PlaybackPlayerEvent(
                        token = token,
                        snapshot = access.snapshot(),
                        terminalExit = DvrPlaybackExit.ERROR,
                    ),
                )
            }
        }
    }

    private fun publishRecovery(token: PlaybackTargetToken, reason: PlaybackRecoveryReason) {
        access.requireApplicationLooper()
        if (active?.token !== token) return
        events.publish(
            PlaybackPlayerEvent(
                token = token,
                snapshot = access.snapshot(),
                recoveryReason = reason,
            ),
        )
    }

    private fun rollbackInstalledOnLooper(installed: InstalledPlayerTarget) {
        if (active !== installed) return
        retireActiveOnLooper()
    }

    private fun retireActiveOnLooper(): PlayerRetirement {
        access.requireApplicationLooper()
        val installed = active ?: return PlayerRetirement()
        var available = true
        val snapshot = try {
            access.snapshot()
        } catch (_: Exception) {
            available = false
            PlaybackPlayerSnapshot(Duration.ZERO, null, Player.STATE_IDLE, failed = true)
        }
        installed.token.retire()
        active = null
        events.retire(installed.token)

        when (installed) {
            is InstalledPlayerTarget.Live -> installed.recovery?.let { recovery ->
                try {
                    recovery.close()
                } catch (_: Exception) {
                    available = false
                }
            }
            is InstalledPlayerTarget.Recording -> installed.resume?.let { resume ->
                try {
                    resume.close()
                } catch (_: Exception) {
                    available = false
                }
            }
        }
        if (installed.listenerAttached) {
            try {
                access.removeListener(installed.listener)
            } catch (_: Exception) {
                available = false
            }
        }
        if (installed.sourceInstalled) {
            try {
                access.stop()
            } catch (_: Exception) {
                available = false
            }
            try {
                access.clearMediaItems()
            } catch (_: Exception) {
                available = false
            }
        }
        val retiredRecording = (installed as? InstalledPlayerTarget.Recording)?.let {
            RetiredRecordingTarget(
                token = installed.token,
                snapshot = snapshot,
                exit = when {
                    snapshot.failed -> DvrPlaybackExit.ERROR
                    snapshot.playbackState == Player.STATE_ENDED -> DvrPlaybackExit.NATURAL_END
                    else -> DvrPlaybackExit.ORDERLY
                },
                growingFinalEndProven = installed.finality?.isProven() == true,
            )
        }
        return PlayerRetirement(
            playerAvailable = available,
            retiredTarget = true,
            retiredRecording = retiredRecording,
        )
    }

    private sealed class InstalledPlayerTarget(
        val token: PlaybackTargetToken,
        val listener: Player.Listener,
    ) {
        var listenerAttached: Boolean = false
        var sourceInstalled: Boolean = false

        class Live(
            token: PlaybackTargetToken,
            listener: Player.Listener,
        ) : InstalledPlayerTarget(token, listener) {
            var recovery: CoordinatorPlaybackRecovery? = null
        }

        class Recording(
            token: PlaybackTargetToken,
            listener: Player.Listener,
            val finality: GrowingFinalitySignal?,
        ) : InstalledPlayerTarget(token, listener) {
            var resume: CoordinatorRecordingResume? = null
        }
    }

    private data class PlayerRetirement(
        val playerAvailable: Boolean = true,
        val retiredTarget: Boolean = false,
        val retiredRecording: RetiredRecordingTarget? = null,
    ) {
        fun failedInstall(): PlaybackPlayerInstallResult = PlaybackPlayerInstallResult(
            status = PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE,
            retiredTarget = retiredTarget,
            retiredRecording = retiredRecording,
        )
    }
}

internal class ExoPlayerCoordinatorPlaybackAccess(
    private val player: ExoPlayer,
    private val subscriptions: SubscriptionOpener,
    private val recordings: RecordingFileOpener,
    private val recoveryPolicy: PlaybackRecoveryPolicy,
    private val onUnsupportedStream: (SubscriptionStreamType) -> Unit,
) : CoordinatorPlaybackAccess {
    override val looper: CoordinatorLooper = HandlerCoordinatorLooper(player.applicationLooper)

    override fun requireApplicationLooper() {
        check(looper.isCurrent()) { "Playback coordination must run on the player's application looper" }
    }

    override fun snapshot(): PlaybackPlayerSnapshot {
        requireApplicationLooper()
        val position = player.currentPosition.takeIf { it >= 0L }?.milliseconds ?: Duration.ZERO
        val duration = player.duration
            .takeIf { it != C.TIME_UNSET && it > 0L }
            ?.milliseconds
        return PlaybackPlayerSnapshot(
            position = position,
            duration = duration,
            playbackState = player.playbackState,
            failed = player.playerError != null,
        )
    }

    override fun addListener(listener: Player.Listener) {
        requireApplicationLooper()
        player.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        requireApplicationLooper()
        player.removeListener(listener)
    }

    override fun createLiveSource(
        channelId: SubscriptionChannelId,
        options: SubscriptionOptions,
    ): CoordinatorMediaSource =
        Media3CoordinatorMediaSource(
            createTvheadendLiveMediaSource(
                subscriptions,
                channelId,
                options,
                onUnsupportedStream,
            ),
        )

    override fun createRecordingSource(recordingId: RecordingId): CoordinatorMediaSource =
        Media3CoordinatorMediaSource(
            createTvheadendRecordingMediaSource(recordings, recordingId),
        )

    override fun createGrowingRecordingSource(
        recordingId: RecordingId,
        lease: GrowingRecordingFileLease,
        onFinalEnd: () -> Unit,
    ): CoordinatorMediaSource = Media3CoordinatorMediaSource(
        createTvheadendGrowingRecordingMediaSource(
            lease = lease,
            recordingId = recordingId,
            onFinalEnd = onFinalEnd,
        ),
    )

    override fun createRecovery(
        onRecoveryRequired: (PlaybackRecoveryReason) -> Unit,
    ): CoordinatorPlaybackRecovery {
        val recovery = createTvheadendPlaybackRecovery(player, recoveryPolicy, onRecoveryRequired)
        return object : CoordinatorPlaybackRecovery {
            override fun beginPlaybackTarget() {
                recovery.beginPlaybackTarget()
            }

            override fun close() {
                recovery.close()
            }
        }
    }

    override fun createResume(): CoordinatorRecordingResume {
        val resume = createTvheadendRecordingResume(player)
        return object : CoordinatorRecordingResume {
            override fun beginPlaybackTarget(recordingId: RecordingId, position: Duration?) {
                resume.beginPlaybackTarget(recordingId, position)
            }

            override fun close() {
                resume.close()
            }
        }
    }

    override fun setMediaSource(source: CoordinatorMediaSource) {
        requireApplicationLooper()
        player.setMediaSource((source as Media3CoordinatorMediaSource).source)
    }

    override fun prepare() {
        requireApplicationLooper()
        player.prepare()
    }

    override fun stop() {
        requireApplicationLooper()
        player.stop()
    }

    override fun clearMediaItems() {
        requireApplicationLooper()
        player.clearMediaItems()
    }

    private data class Media3CoordinatorMediaSource(val source: MediaSource) : CoordinatorMediaSource
}
