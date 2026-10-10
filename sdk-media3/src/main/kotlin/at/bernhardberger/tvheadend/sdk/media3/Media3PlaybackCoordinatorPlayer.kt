@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackExit
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal enum class PlaybackPlayerInstallStatus {
    STARTED,
    NOT_READY,
    RECORDING_PROGRESS_UNSUPPORTED,
    TARGET_UNAVAILABLE,
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
    /** False when only a historical owned position remains after application replacement. */
    val allowWatched: Boolean = true,
)

internal sealed interface RecordingAdmission {
    sealed interface Accepted : RecordingAdmission {
        val progressReportingSupported: Boolean
    }

    data class Completed(
        val resumePosition: Duration?,
        override val progressReportingSupported: Boolean = true,
    ) : Accepted

    class Growing(
        val lease: GrowingRecordingFileLease,
        override val progressReportingSupported: Boolean = true,
        val resumePosition: Duration? = null,
    ) : Accepted {
        override fun toString(): String = "RecordingAdmission.Growing(<redacted>)"
    }

    data object NotReady : RecordingAdmission

    data object ProgressUnsupported : RecordingAdmission

    data object TargetUnavailable : RecordingAdmission

    data object GrowingRecordingDeferred : RecordingAdmission
}

internal interface PlaybackCoordinatorPlayer {
    suspend fun installLive(
        ticket: PlayerOperationTicket,
        token: PlaybackTargetToken,
        target: CoordinatorLiveTarget,
        options: SubscriptionOptions = SubscriptionOptions(),
        timeshiftControls: LiveTimeshiftControlBridge,
    ): PlaybackPlayerInstallResult

    suspend fun installRecording(
        ticket: PlayerOperationTicket,
        token: PlaybackTargetToken,
        target: CoordinatorRecordingTarget,
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
    /** The pending saved position that progress reports must not undercut, or null. */
    val progressFloor: Duration?

    fun beginPlaybackTarget(position: Duration?)
}

internal interface CoordinatorPlaybackAccess {
    val looper: CoordinatorLooper

    fun requireApplicationLooper()

    fun snapshot(): PlaybackPlayerSnapshot

    fun addListener(listener: Player.Listener)

    fun removeListener(listener: Player.Listener)

    fun createLiveSource(
        target: CoordinatorLiveTarget,
        options: SubscriptionOptions,
        timeshiftControls: LiveTimeshiftControlBridge,
    ): CoordinatorMediaSource

    fun createRecordingSource(
        target: CoordinatorRecordingTarget,
        identity: RecordingMediaIdentity,
    ): CoordinatorMediaSource

    fun createGrowingRecordingSource(
        lease: GrowingRecordingFileLease,
        identity: RecordingMediaIdentity,
        onFinalEnd: () -> Unit,
    ): CoordinatorMediaSource

    /** Recovery reacts only while [isTargetCurrent] holds, so it never acts on an application's item. */
    fun createRecovery(
        isTargetCurrent: () -> Boolean,
        onRecoveryRequired: (PlaybackRecoveryReason) -> Unit,
    ): CoordinatorPlaybackRecovery

    fun createResume(
        identity: RecordingMediaIdentity,
        mode: RecordingResumeMode,
        onProgressFloorChanged: (Duration?) -> Unit,
        isTargetCurrent: () -> Boolean,
    ): CoordinatorRecordingResume

    /** Whether the player's current item is the one [source] installed, not one the application set. */
    fun isCurrent(source: CoordinatorMediaSource): Boolean

    /** Matches a delivered event's item without consulting mutable player state. */
    fun ownsItem(source: CoordinatorMediaSource, item: MediaItem?): Boolean

    fun setMediaSource(source: CoordinatorMediaSource, startPosition: Duration? = null)

    fun prepare()

    fun stop()

    /** Removes only the playlist items [source] installed, leaving every application item in place. */
    fun removeMediaItems(source: CoordinatorMediaSource)
}

internal class Media3PlaybackCoordinatorPlayer(
    private val access: CoordinatorPlaybackAccess,
    private val events: PlaybackPlayerEventAccumulator,
    private val admitRecording: (CoordinatorRecordingTarget, RecordingPlaybackStart) -> RecordingAdmission,
) : PlaybackCoordinatorPlayer {
    private val executor = PlayerLooperExecutor(access.looper)
    private var active: InstalledPlayerTarget? = null

    override suspend fun installLive(
        ticket: PlayerOperationTicket,
        token: PlaybackTargetToken,
        target: CoordinatorLiveTarget,
        options: SubscriptionOptions,
        timeshiftControls: LiveTimeshiftControlBridge,
    ): PlaybackPlayerInstallResult = when (
        val operation = executor.execute(ticket) {
            installAfterApplicationReplacementOnLooper {
                installLiveOnLooper(token, target, options, timeshiftControls)
            }
        }
    ) {
        is LooperOperationResult.Success -> operation.value
        LooperOperationResult.Cancelled -> PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.CANCELLED)
        LooperOperationResult.Unavailable ->
            PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE)
    }

    override suspend fun installRecording(
        ticket: PlayerOperationTicket,
        token: PlaybackTargetToken,
        target: CoordinatorRecordingTarget,
        start: RecordingPlaybackStart,
    ): PlaybackPlayerInstallResult = when (
        val operation = executor.execute(ticket) {
            installAfterApplicationReplacementOnLooper {
                installRecordingOnLooper(token, target, start)
            }
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
                ownedTarget(token)?.takeIf { token.isActive() }?.let(::ownedSnapshot)
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
        target: CoordinatorLiveTarget,
        options: SubscriptionOptions,
        timeshiftControls: LiveTimeshiftControlBridge,
    ): PlaybackPlayerInstallResult {
        access.requireApplicationLooper()
        if (!target.isCurrent) {
            return PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.NOT_READY)
        }
        val source = try {
            access.createLiveSource(target, options, timeshiftControls)
        } catch (_: Exception) {
            return PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE)
        }
        val previous = active
        val previousSnapshot = snapshotForReplacement(previous)
            ?: return PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE)
        val observationReplacement = (previous as? InstalledPlayerTarget.Live)
            ?.timeshiftControls
            ?.beginObservationReplacement()
        val listener = targetListener(token, recording = false)
        val installed = InstalledPlayerTarget.Live(token, listener, source, timeshiftControls)
        active = installed
        return try {
            installed.listenerAttached = true
            access.addListener(listener)
            installed.recovery = access.createRecovery(
                isTargetCurrent = { describesTarget(installed) },
            ) { reason -> publishRecovery(token, reason) }
            installed.recovery?.beginPlaybackTarget()
            installed.sourceInstalled = true
            access.setMediaSource(source)
            if (describesTarget(installed)) access.prepare() else loseOwnership(installed)
            val retirement = retireReplacedOnLooper(previous, previousSnapshot)
            if (!retirement.playerAvailable) {
                observationReplacement?.let {
                    previous.timeshiftControls.commitObservationReplacement(it)
                }
                retireActiveOnLooper()
                return retirement.failedInstall()
            }
            observationReplacement?.let {
                previous.timeshiftControls.commitObservationReplacement(it)
            }
            PlaybackPlayerInstallResult(
                status = PlaybackPlayerInstallStatus.STARTED,
                retiredTarget = retirement.retiredTarget,
                retiredRecording = retirement.retiredRecording,
            )
        } catch (_: Exception) {
            rollbackReplacementOnLooper(installed, previous, previousSnapshot, observationReplacement)
        }
    }

    private fun installRecordingOnLooper(
        token: PlaybackTargetToken,
        target: CoordinatorRecordingTarget,
        start: RecordingPlaybackStart,
    ): PlaybackPlayerInstallResult {
        access.requireApplicationLooper()
        val admission = admitRecording(target, start)
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
            RecordingAdmission.GrowingRecordingDeferred ->
                return PlaybackPlayerInstallResult(
                    PlaybackPlayerInstallStatus.GROWING_RECORDING_DEFERRED,
                )
        }
        val finality = (accepted as? RecordingAdmission.Growing)?.let { GrowingFinalitySignal() }
        val mediaIdentity = RecordingMediaIdentity()
        val source = try {
            when (accepted) {
                is RecordingAdmission.Completed ->
                    access.createRecordingSource(target, mediaIdentity)
                is RecordingAdmission.Growing -> access.createGrowingRecordingSource(
                    lease = accepted.lease,
                    identity = mediaIdentity,
                    onFinalEnd = checkNotNull(finality)::prove,
                )
            }
        } catch (_: Exception) {
            return PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE)
        }
        val previous = active
        val previousSnapshot = snapshotForReplacement(previous)
            ?: return PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE)
        val observationReplacement = (previous as? InstalledPlayerTarget.Live)
            ?.timeshiftControls
            ?.beginObservationReplacement()
        val listener = targetListener(token, recording = true)
        val resumePosition = when (accepted) {
            is RecordingAdmission.Completed -> accepted.resumePosition
            is RecordingAdmission.Growing -> accepted.resumePosition
        }
        val installed = InstalledPlayerTarget.Recording(token, listener, source, finality, resumePosition)
        active = installed
        return try {
            installed.listenerAttached = true
            access.addListener(listener)
            installed.sourceInstalled = true
            access.setMediaSource(source)
            if (describesTarget(installed)) {
                // Growing start-over keeps its install sequence without a resume helper.
                if (accepted is RecordingAdmission.Completed || resumePosition != null) {
                    val mode = if (accepted is RecordingAdmission.Completed) RecordingResumeMode.COMPLETED
                    else RecordingResumeMode.GROWING
                    installed.resume = access.createResume(
                        mediaIdentity, mode, onProgressFloorChanged = { updateResumeFloor(installed, it) },
                    ) { describesTarget(installed) }
                    installed.resume?.beginPlaybackTarget(resumePosition)
                }
            }
            // Resume may have applied or been abandoned synchronously. Refresh only while owned;
            // a snapshot captured before departure must retain its original resume protection.
            if (describesTarget(installed)) {
                ownedSnapshot(installed)
                access.prepare()
            } else {
                // Installing succeeded, but an application listener already departed. Do not
                // prepare its item or roll a previous source back over its playlist change.
                loseOwnership(installed)
            }
            val retirement = retireReplacedOnLooper(previous, previousSnapshot)
            if (!retirement.playerAvailable) {
                observationReplacement?.let {
                    previous.timeshiftControls.commitObservationReplacement(it)
                }
                retireActiveOnLooper()
                return retirement.failedInstall()
            }
            observationReplacement?.let {
                previous.timeshiftControls.commitObservationReplacement(it)
            }
            PlaybackPlayerInstallResult(
                status = PlaybackPlayerInstallStatus.STARTED,
                retiredTarget = retirement.retiredTarget,
                retiredRecording = retirement.retiredRecording,
                installedRecording = accepted,
            )
        } catch (_: Exception) {
            rollbackReplacementOnLooper(installed, previous, previousSnapshot, observationReplacement)
        }
    }

    private fun targetListener(
        token: PlaybackTargetToken,
        recording: Boolean,
    ): Player.Listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            access.requireApplicationLooper()
            // A transition later in this batch must see the departing item's ownership/snapshot.
            // Timeline-only observations settle in onEvents instead.
        }

        override fun onEvents(player: Player, events: Player.Events) {
            access.requireApplicationLooper()
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) &&
                !events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            ) {
                observeOwnership(token)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            access.requireApplicationLooper()
            val installed = active?.takeIf { it.token === token && it.ownership != ItemOwnership.LOST } ?: return
            if (access.ownsItem(installed.source, mediaItem)) {
                installed.ownership = ItemOwnership.OWNED
                if (playsInstalledSource(installed)) ownedSnapshot(installed)
            } else if (installed.ownership == ItemOwnership.OWNED) {
                if (recording && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    installed.lastOwnedSnapshot?.let { snapshot ->
                        publishNaturalEnd(installed, snapshot.atNaturalEnd())
                    }
                }
                // Getters may already describe a later seek back initiated by an earlier listener.
                // The delivered departure still ends our ownership, which can never be reacquired.
                loseOwnership(installed)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            access.requireApplicationLooper()
            if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                reason != Player.DISCONTINUITY_REASON_SEEK && reason != Player.DISCONTINUITY_REASON_REMOVE
            ) return
            val installed = active?.takeIf { it.token === token && it.ownership == ItemOwnership.OWNED } ?: return
            if (!access.ownsItem(installed.source, oldPosition.mediaItem)) return
            val staysOwned = access.ownsItem(installed.source, newPosition.mediaItem)
            val position = if (staysOwned) newPosition else oldPosition
            installed.lastOwnedSnapshot = installed.lastOwnedSnapshot?.let { snapshot ->
                snapshot.copy(
                    position = position.positionMs.milliseconds,
                    // The resume listener cancels pending resume on this same in-item seek.
                    pendingResumePosition = if (staysOwned && reason == Player.DISCONTINUITY_REASON_SEEK) null
                    else snapshot.pendingResumePosition,
                )
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            access.requireApplicationLooper()
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                val (_, snapshot) = eventTarget(token) ?: return
                events.publish(PlaybackPlayerEvent(token = token, snapshot = snapshot, paused = true))
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            access.requireApplicationLooper()
            if (recording && playbackState == Player.STATE_ENDED) {
                publishNaturalEnd(token)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            access.requireApplicationLooper()
            if (recording) {
                val (_, snapshot) = eventTarget(token) { last -> last.copy(failed = true) } ?: return
                events.publish(
                    PlaybackPlayerEvent(
                        token = token,
                        snapshot = snapshot,
                        terminalExit = DvrPlaybackExit.ERROR,
                    ),
                )
            }
        }
    }

    /** Both playlist auto-advance and ENDED settle the recording before ownership is fenced. */
    private fun publishNaturalEnd(token: PlaybackTargetToken) {
        val (installed, snapshot) = eventTarget(token) { it.atNaturalEnd() } ?: return
        publishNaturalEnd(installed, snapshot)
    }

    private fun PlaybackPlayerSnapshot.atNaturalEnd(): PlaybackPlayerSnapshot =
        copy(position = duration ?: position, playbackState = Player.STATE_ENDED)

    private fun publishNaturalEnd(installed: InstalledPlayerTarget, snapshot: PlaybackPlayerSnapshot) {
        events.publish(
            PlaybackPlayerEvent(
                token = installed.token,
                snapshot = snapshot,
                terminalExit = DvrPlaybackExit.NATURAL_END,
                growingFinalEndProven = (installed as? InstalledPlayerTarget.Recording)?.finality?.isProven() == true,
            ),
        )
    }

    /**
     * The active target for [token] while the player's current state describes it.
     *
     * Player state belongs to this coordinator only while the target's own item is current and the
     * application never replaced it: afterwards position, end and errors describe the application's
     * media instead, even if the application later returns to the target's item.
     */
    private fun ownedTarget(token: PlaybackTargetToken): InstalledPlayerTarget? =
        active?.takeIf { it.token === token && describesTarget(it) }

    private fun describesTarget(installed: InstalledPlayerTarget): Boolean =
        installed.ownership != ItemOwnership.LOST && playsInstalledSource(installed)

    private fun playsInstalledSource(installed: InstalledPlayerTarget): Boolean =
        installed.sourceInstalled && access.isCurrent(installed.source)

    /**
     * Attributes a player callback for [token] in event order.
     *
     * Media3 delivers each event to every listener before the events an earlier listener caused by
     * changing the playlist, so a callback may arrive after an application listener already
     * replaced the item but before this target observed that transition. Such an event still
     * belongs to the target and uses its last snapshot taken while its item was current, adjusted
     * by [settle] to the state the event reports.
     */
    private inline fun eventTarget(
        token: PlaybackTargetToken,
        settle: (PlaybackPlayerSnapshot) -> PlaybackPlayerSnapshot = { it },
    ): Pair<InstalledPlayerTarget, PlaybackPlayerSnapshot>? {
        val installed = active?.takeIf { it.token === token && it.ownership == ItemOwnership.OWNED }
            ?: return null
        val snapshot = if (playsInstalledSource(installed)) {
            ownedSnapshot(installed)
        } else {
            installed.lastOwnedSnapshot?.let(settle) ?: return null
        }
        return installed to snapshot
    }

    /** A snapshot of [installed]'s current item, kept as the last one that described it. */
    private fun ownedSnapshot(installed: InstalledPlayerTarget): PlaybackPlayerSnapshot =
        snapshotOf(installed).also { installed.lastOwnedSnapshot = it }

    /** Settlement refreshes cached protection only while owned, never during departure or teardown. */
    private fun updateResumeFloor(installed: InstalledPlayerTarget.Recording, floor: Duration?) {
        access.requireApplicationLooper()
        if (installed.token.isActive() && describesTarget(installed)) {
            installed.lastOwnedSnapshot = installed.lastOwnedSnapshot?.copy(pendingResumePosition = floor)
        }
    }

    /**
     * Follows the player's current item in event order from timeline and item-transition callbacks.
     *
     * The first observation of the target's own item claims it. Once the application replaces or
     * clears that item, the target loses ownership for good: its pending pause and recovery events
     * and its pending progress reports are fenced at once, without waiting for a coordinator command.
     */
    private fun observeOwnership(token: PlaybackTargetToken) {
        val installed = active?.takeIf { it.token === token } ?: return
        when {
            installed.ownership == ItemOwnership.LOST -> Unit
            playsInstalledSource(installed) -> {
                installed.ownership = ItemOwnership.OWNED
                ownedSnapshot(installed)
            }
            installed.ownership == ItemOwnership.OWNED -> {
                loseOwnership(installed)
            }
        }
    }

    private fun loseOwnership(installed: InstalledPlayerTarget) {
        installed.ownership = ItemOwnership.LOST
        installed.token.releasePlayerItem()
        events.fenceItemReplacement(installed.token)
    }

    /**
     * Runs [install] after retiring the active target if the application has replaced its item.
     *
     * The application's item is never treated as the replaced target: it is neither snapshotted
     * for progress nor restored by a failed installation.
     */
    private inline fun installAfterApplicationReplacementOnLooper(
        install: () -> PlaybackPlayerInstallResult,
    ): PlaybackPlayerInstallResult {
        val replaced = retireApplicationReplacedOnLooper() ?: return install()
        if (!replaced.playerAvailable) return replaced.failedInstall()
        return install().copy(retiredTarget = true, retiredRecording = replaced.retiredRecording)
    }

    /**
     * Retires the active target once the application replaced its item, leaving the application's
     * items in place and removing only the target's own item if it lingers in the playlist.
     */
    private fun retireApplicationReplacedOnLooper(): PlayerRetirement? {
        access.requireApplicationLooper()
        val installed = active ?: return null
        val owned = try {
            describesTarget(installed)
        } catch (_: Exception) {
            return PlayerRetirement(playerAvailable = false)
        }
        if (owned) return null
        active = null
        return retireInstalledOnLooper(installed, snapshot = null, clearPlayer = true)
    }

    private fun publishRecovery(token: PlaybackTargetToken, reason: PlaybackRecoveryReason) {
        access.requireApplicationLooper()
        if (ownedTarget(token) == null) return
        events.publish(
            PlaybackPlayerEvent(
                token = token,
                snapshot = access.snapshot(),
                recoveryReason = reason,
            ),
        )
    }

    private fun snapshotOf(installed: InstalledPlayerTarget?): PlaybackPlayerSnapshot {
        val snapshot = access.snapshot()
        val pending = (installed as? InstalledPlayerTarget.Recording)?.progressFloor
            ?: return snapshot
        return snapshot.copy(pendingResumePosition = pending)
    }

    private fun snapshotForReplacement(installed: InstalledPlayerTarget?): PlaybackPlayerSnapshot? {
        if (installed == null) {
            return PlaybackPlayerSnapshot(Duration.ZERO, null, Player.STATE_IDLE, failed = false)
        }
        return try {
            snapshotOf(installed)
        } catch (_: Exception) {
            null
        }
    }

    private fun retireReplacedOnLooper(
        installed: InstalledPlayerTarget?,
        snapshot: PlaybackPlayerSnapshot,
    ): PlayerRetirement = installed?.let {
        retireInstalledOnLooper(it, snapshot, clearPlayer = false)
    } ?: PlayerRetirement()

    private fun rollbackReplacementOnLooper(
        installed: InstalledPlayerTarget,
        previous: InstalledPlayerTarget?,
        previousSnapshot: PlaybackPlayerSnapshot,
        observationReplacement: LiveTimeshiftControlBridge.ObservationReplacement?,
    ): PlaybackPlayerInstallResult {
        if (active !== installed) {
            commitObservationReplacement(previous, observationReplacement)
            return PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE)
        }
        val restorablePrevious = previous?.takeIf { it.token.isActive() }
        active = restorablePrevious
        val stagedRetirement = retireInstalledOnLooper(
            installed = installed,
            snapshot = PlaybackPlayerSnapshot(Duration.ZERO, null, Player.STATE_IDLE, failed = true),
            clearPlayer = restorablePrevious == null,
            alsoInstalled = previous,
        )
        if (!stagedRetirement.playerAvailable) {
            commitObservationReplacement(previous, observationReplacement)
            val retirement = retireActiveOnLooper(previousSnapshot.copy(failed = true), alsoInstalled = installed)
            return retirement.failedInstall()
        }
        if (previous == null) {
            return PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE)
        }
        if (restorablePrevious == null) {
            commitObservationReplacement(previous, observationReplacement)
            return stagedRetirement.failedInstall()
        }
        if (!installed.sourceInstalled) {
            rollbackObservationReplacement(restorablePrevious, observationReplacement)
            return PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE)
        }
        return try {
            val restored = restorablePrevious.token.runIfActive {
                access.setMediaSource(
                    restorablePrevious.source,
                    previousSnapshot.position.takeIf { restorablePrevious is InstalledPlayerTarget.Recording },
                )
                if (!restorablePrevious.token.isActive()) return@runIfActive
                access.prepare()
            }
            if (!restored) {
                commitObservationReplacement(previous, observationReplacement)
                return retireActiveOnLooper(previousSnapshot.copy(failed = true), alsoInstalled = installed)
                    .failedInstall()
            }
            rollbackObservationReplacement(restorablePrevious, observationReplacement)
            PlaybackPlayerInstallResult(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE)
        } catch (_: Exception) {
            commitObservationReplacement(previous, observationReplacement)
            retireActiveOnLooper(previousSnapshot.copy(failed = true), alsoInstalled = installed).failedInstall()
        }
    }

    private fun commitObservationReplacement(
        previous: InstalledPlayerTarget?,
        replacement: LiveTimeshiftControlBridge.ObservationReplacement?,
    ) {
        if (replacement != null) {
            (previous as InstalledPlayerTarget.Live).timeshiftControls.commitObservationReplacement(replacement)
        }
    }

    private fun rollbackObservationReplacement(
        previous: InstalledPlayerTarget,
        replacement: LiveTimeshiftControlBridge.ObservationReplacement?,
    ) {
        if (replacement != null) {
            (previous as InstalledPlayerTarget.Live).timeshiftControls.rollbackObservationReplacement(replacement)
        }
    }

    private fun retireActiveOnLooper(
        snapshotOverride: PlaybackPlayerSnapshot? = null,
        alsoInstalled: InstalledPlayerTarget? = null,
    ): PlayerRetirement {
        access.requireApplicationLooper()
        val installed = active ?: return PlayerRetirement()
        var available = true
        // A failed restoration leaves the player's current snapshot attributable to neither source.
        // Otherwise the player describes this target only while its own item is still current.
        val snapshot = snapshotOverride ?: try {
            if (describesTarget(installed)) snapshotOf(installed) else null
        } catch (_: Exception) {
            available = false
            PlaybackPlayerSnapshot(Duration.ZERO, null, Player.STATE_IDLE, failed = true)
        }
        active = null
        return retireInstalledOnLooper(
            installed = installed,
            snapshot = snapshot,
            clearPlayer = true,
            available = available,
            alsoInstalled = alsoInstalled,
        )
    }

    /**
     * Retires [installed] and, with [clearPlayer], removes this coordinator's items from the player:
     * [installed]'s, and after a failed replacement [alsoInstalled]'s.
     *
     * A null [snapshot] means the player no longer describes the target. A captured terminal
     * transfers atomically from the event accumulator; otherwise its last owned snapshot supplies
     * an orderly closing position only, never evidence that the recording was watched.
     */
    private fun retireInstalledOnLooper(
        installed: InstalledPlayerTarget,
        snapshot: PlaybackPlayerSnapshot?,
        clearPlayer: Boolean,
        available: Boolean = true,
        alsoInstalled: InstalledPlayerTarget? = null,
    ): PlayerRetirement {
        var playerAvailable = available
        installed.token.retire()
        val terminal = events.retire(installed.token)?.takeIf { it.terminalExit != null }

        when (installed) {
            is InstalledPlayerTarget.Live -> installed.recovery?.let { recovery ->
                try {
                    recovery.close()
                } catch (_: Exception) {
                    playerAvailable = false
                }
            }
            is InstalledPlayerTarget.Recording -> installed.resume?.let { resume ->
                try {
                    resume.close()
                } catch (_: Exception) {
                    playerAvailable = false
                }
            }
        }
        if (installed.listenerAttached) {
            try {
                access.removeListener(installed.listener)
            } catch (_: Exception) {
                playerAvailable = false
            }
        }
        if (clearPlayer && !removeOwnItems(listOfNotNull(installed, alsoInstalled))) playerAvailable = false
        val retirementSnapshot = terminal?.snapshot ?: snapshot ?: installed.lastOwnedSnapshot
        val retiredRecording = if (installed is InstalledPlayerTarget.Recording && retirementSnapshot != null) {
            RetiredRecordingTarget(
                token = installed.token,
                snapshot = retirementSnapshot,
                exit = terminal?.terminalExit ?: when {
                    snapshot == null -> DvrPlaybackExit.ORDERLY
                    retirementSnapshot.failed -> DvrPlaybackExit.ERROR
                    retirementSnapshot.playbackState == Player.STATE_ENDED -> DvrPlaybackExit.NATURAL_END
                    else -> DvrPlaybackExit.ORDERLY
                },
                growingFinalEndProven = terminal?.growingFinalEndProven ?: (installed.finality?.isProven() == true),
                allowWatched = terminal != null || snapshot != null,
            )
        } else {
            null
        }
        return PlayerRetirement(
            playerAvailable = playerAvailable,
            retiredTarget = true,
            retiredRecording = retiredRecording,
        )
    }

    /**
     * Stops the player only while one of [owners]' items is current, then removes those items alone.
     *
     * Removal locates the items again after stopping, and even after a failed stop: an application
     * listener may react to the stop by setting or adding its own items, which must survive.
     * Returns false if the player failed.
     */
    private fun removeOwnItems(owners: List<InstalledPlayerTarget>): Boolean {
        val installedOwners = owners.filter { it.sourceInstalled }
        if (installedOwners.isEmpty()) return true
        var available = true
        val current = try {
            installedOwners.any(::playsInstalledSource)
        } catch (_: Exception) {
            available = false
            false
        }
        if (current) {
            try {
                access.stop()
            } catch (_: Exception) {
                available = false
            }
        }
        installedOwners.forEach { owner ->
            try {
                access.removeMediaItems(owner.source)
            } catch (_: Exception) {
                available = false
            }
        }
        return available
    }

    /** Event-ordered ownership of the player's current item; see [observeOwnership]. */
    private enum class ItemOwnership {
        /** Installed, but the player has not yet reported the target's item as current. */
        PENDING,
        OWNED,

        /** The application replaced or cleared the target's item; final for this target. */
        LOST,
    }

    private sealed class InstalledPlayerTarget(
        val token: PlaybackTargetToken,
        val listener: Player.Listener,
        val source: CoordinatorMediaSource,
    ) {
        var listenerAttached: Boolean = false
        var sourceInstalled: Boolean = false
        var ownership: ItemOwnership = ItemOwnership.PENDING
        var lastOwnedSnapshot: PlaybackPlayerSnapshot? = null

        class Live(
            token: PlaybackTargetToken,
            listener: Player.Listener,
            source: CoordinatorMediaSource,
            val timeshiftControls: LiveTimeshiftControlBridge,
        ) : InstalledPlayerTarget(token, listener, source) {
            var recovery: CoordinatorPlaybackRecovery? = null
        }

        class Recording(
            token: PlaybackTargetToken,
            listener: Player.Listener,
            source: CoordinatorMediaSource,
            val finality: GrowingFinalitySignal?,
            private val initialResumePosition: Duration?,
        ) : InstalledPlayerTarget(token, listener, source) {
            var resume: CoordinatorRecordingResume? = null

            // Source installation publishes callbacks before the resume helper can be constructed.
            // Seed their snapshots from admission so cached/terminal snapshots survive helper close.
            val progressFloor: Duration?
                get() = if (resume == null) initialResumePosition?.takeIf { it > Duration.ZERO }
                else resume?.progressFloor
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
    private val recoveryPolicy: PlaybackRecoveryPolicy,
    private val onUnsupportedStream: (SubscriptionStreamType) -> Unit,
) : CoordinatorPlaybackAccess {
    override val looper: CoordinatorLooper = HandlerCoordinatorLooper(player.applicationLooper)

    override fun requireApplicationLooper() {
        check(looper.isCurrent()) { "Playback coordination must run on the player's application looper" }
    }

    override fun snapshot(): PlaybackPlayerSnapshot {
        requireApplicationLooper()
        val before = currentPeriodUid()
        val position = player.currentPosition.takeIf { it >= 0L }?.milliseconds ?: Duration.ZERO
        val duration = player.duration
            .takeIf { it != C.TIME_UNSET && it > 0L }
            ?.milliseconds
        return PlaybackPlayerSnapshot(
            position = position,
            duration = duration,
            playbackState = player.playbackState,
            failed = player.playerError != null,
            periodUid = before?.takeIf { it == currentPeriodUid() },
            positionResolutionUs = 1_000L,
            videoSelected = player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected },
        )
    }

    private fun currentPeriodUid(): Any? {
        val timeline = player.currentTimeline
        val index = player.currentPeriodIndex
        if (index !in 0 until timeline.periodCount) return null
        val uid = timeline.getUidOfPeriod(index)
        return if (timeline is androidx.media3.exoplayer.AbstractConcatenatedTimeline) {
            androidx.media3.exoplayer.AbstractConcatenatedTimeline.getChildPeriodUidFromConcatenatedUid(uid)
        } else uid
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
        target: CoordinatorLiveTarget,
        options: SubscriptionOptions,
        timeshiftControls: LiveTimeshiftControlBridge,
    ): CoordinatorMediaSource =
        Media3CoordinatorMediaSource(
            createTvheadendLiveMediaSource(
                target,
                options,
                timeshiftControls,
                onUnsupportedStream,
            ),
        )

    override fun createRecordingSource(
        target: CoordinatorRecordingTarget,
        identity: RecordingMediaIdentity,
    ): CoordinatorMediaSource =
        Media3CoordinatorMediaSource(
            createTvheadendRecordingMediaSource(target, identity),
            identity,
        )

    override fun createGrowingRecordingSource(
        lease: GrowingRecordingFileLease,
        identity: RecordingMediaIdentity,
        onFinalEnd: () -> Unit,
    ): CoordinatorMediaSource = Media3CoordinatorMediaSource(
        createTvheadendGrowingRecordingMediaSource(
            lease = lease,
            identity = identity,
            onFinalEnd = onFinalEnd,
        ),
        identity,
    )

    override fun createRecovery(
        isTargetCurrent: () -> Boolean,
        onRecoveryRequired: (PlaybackRecoveryReason) -> Unit,
    ): CoordinatorPlaybackRecovery {
        val recovery = TvheadendPlaybackRecovery(player, recoveryPolicy, onRecoveryRequired, isTargetCurrent)
        return object : CoordinatorPlaybackRecovery {
            override fun beginPlaybackTarget() {
                recovery.beginPlaybackTarget()
            }

            override fun close() {
                recovery.close()
            }
        }
    }

    override fun createResume(
        identity: RecordingMediaIdentity,
        mode: RecordingResumeMode,
        onProgressFloorChanged: (Duration?) -> Unit,
        isTargetCurrent: () -> Boolean,
    ): CoordinatorRecordingResume {
        val resume = createTvheadendRecordingResume(player, identity, mode, isTargetCurrent, onProgressFloorChanged)
        return object : CoordinatorRecordingResume {
            override val progressFloor: Duration?
                get() = resume.progressFloor

            override fun beginPlaybackTarget(position: Duration?) {
                resume.beginPlaybackTarget(position)
            }

            override fun close() {
                resume.close()
            }
        }
    }

    override fun isCurrent(source: CoordinatorMediaSource): Boolean {
        requireApplicationLooper()
        return (source as Media3CoordinatorMediaSource).owns(player.currentMediaItem)
    }

    override fun ownsItem(source: CoordinatorMediaSource, item: MediaItem?): Boolean =
        (source as Media3CoordinatorMediaSource).owns(item)

    override fun removeMediaItems(source: CoordinatorMediaSource) {
        requireApplicationLooper()
        val owner = source as Media3CoordinatorMediaSource
        // Each removal notifies listeners synchronously, and they may edit the playlist, so every
        // pass locates the item afresh. Bounded so an application re-adding an item cannot loop.
        repeat(player.mediaItemCount) {
            val index = (player.mediaItemCount - 1 downTo 0).firstOrNull { index ->
                owner.owns(player.getMediaItemAt(index))
            } ?: return
            player.removeMediaItem(index)
        }
    }

    override fun setMediaSource(source: CoordinatorMediaSource, startPosition: Duration?) {
        requireApplicationLooper()
        val mediaSource = (source as Media3CoordinatorMediaSource).source
        if (startPosition == null) {
            player.setMediaSource(mediaSource)
        } else {
            player.setMediaSource(mediaSource, startPosition.inWholeMilliseconds)
        }
    }

    override fun prepare() {
        requireApplicationLooper()
        player.prepare()
    }

    override fun stop() {
        requireApplicationLooper()
        player.stop()
    }

    /**
     * One coordinator-installed source and the rule that recognizes its playlist item.
     *
     * A recording item is recognized by its per-install opaque URI, never its media id: Media3
     * updates a progressive source in place when the application replaces only its metadata, which
     * yields a new item instance that keeps the URI while the same source keeps playing. A live
     * source never accepts such an update, so every application change installs a different item
     * instance, while the live source keeps its own instance across stream restarts. Live items
     * all share one reserved media id, so they are recognized by instance.
     */
    private class Media3CoordinatorMediaSource(
        val source: MediaSource,
        private val recordingIdentity: RecordingMediaIdentity? = null,
    ) : CoordinatorMediaSource {
        private val installedItem: MediaItem = source.mediaItem

        fun owns(item: MediaItem?): Boolean =
            ownsCoordinatorMediaItem(installedItem, item, recordingIdentity)
    }
}

/** Shared playlist/current-item identity policy, independent of the player's mutable timeline. */
internal fun ownsCoordinatorMediaItem(
    installed: MediaItem,
    current: MediaItem?,
    recordingIdentity: RecordingMediaIdentity?,
): Boolean = recordingIdentity?.matches(current) ?: (current != null && current === installed)
