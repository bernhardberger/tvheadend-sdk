@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
)

package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.session.GenerationBoundGrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.core.session.PlaybackRecordingLookup
import at.bernhardberger.tvheadend.sdk.core.session.SessionChildren
import at.bernhardberger.tvheadend.sdk.core.session.SessionMetadata
import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.RecordingFile
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import kotlin.time.Duration

/** Opaque, observation-bound authority for one playback target. */
public sealed class PlaybackBinding protected constructor() {
    /** One channel in the exact session generation that created this binding. */
    public class Live internal constructor(
        private val current: () -> Boolean,
        private val openTarget: suspend (
            SubscriptionEventConsumer,
            SubscriptionOptions,
        ) -> SubscriptionOpenResult,
    ) : PlaybackBinding() {
        /** Whether this exact target remains current for its originating observation. */
        @SubscriptionInfrastructureApi
        public val isCurrent: Boolean
            get() = current()

        /** Opens this exact target without accepting a channel or generation selector. */
        @SubscriptionInfrastructureApi
        public suspend fun open(
            consumer: SubscriptionEventConsumer,
            options: SubscriptionOptions,
        ): SubscriptionOpenResult = openTarget(consumer, options)

        override fun toString(): String = "PlaybackBinding.Live(<redacted>)"
    }

    /** One recording in the exact session generation that created this binding. */
    public class Recording internal constructor(
        /** Retains how the target was classified when the binding was created. */
        @SubscriptionInfrastructureApi
        public val startedGrowing: Boolean,
        private val observeAdmission: () -> RecordingPlaybackAdmission,
        private val openTarget: suspend () -> RecordingFileResult<RecordingFile>,
        private val bindGrowingTarget: () -> RecordingFileResult<GrowingRecordingFileLease>,
        private val reportTargetProgress: suspend (
            GrowingRecordingFileLease?,
            DvrPlaybackProgress,
        ) -> DvrProgressResult,
        private val loadTargetCutpoints: suspend () -> DvrCutpointsResult,
        private val observeDeclaredVideoFrameDuration: () -> Duration? = { null },
    ) : PlaybackBinding() {
        /** Current side-effect-free admission state of this exact recording target. */
        public val admission: RecordingPlaybackAdmission
            get() = observeAdmission()

        /**
         * Positive video frame duration declared in the current observation of this target, or null.
         *
         * It is read from the last recording file, which is the file Tvheadend opens for playback,
         * and its first video stream in recorded order; null without such a stream or a positive
         * declared duration, or once the target is no longer current.
         */
        @SubscriptionInfrastructureApi
        @ExperimentalTvheadendApi
        public val declaredVideoFrameDuration: Duration?
            get() = observeDeclaredVideoFrameDuration()

        /** Retrieves cutpoints for this exact recording target. */
        public suspend fun cutpoints(): DvrCutpointsResult = loadTargetCutpoints()

        /** Opens this exact completed recording without accepting an identity selector. */
        @SubscriptionInfrastructureApi
        public suspend fun openRecording(): RecordingFileResult<RecordingFile> = openTarget()

        /** Binds growing-file continuity to this exact recording target. */
        @SubscriptionInfrastructureApi
        public fun bindGrowingRecording(): RecordingFileResult<GrowingRecordingFileLease> =
            bindGrowingTarget()

        /** Reports progress for this exact target and optional growing continuity lease. */
        @SubscriptionInfrastructureApi
        public suspend fun reportProgress(
            growingLease: GrowingRecordingFileLease?,
            progress: DvrPlaybackProgress,
        ): DvrProgressResult = reportTargetProgress(growingLease, progress)

        override fun toString(): String = "PlaybackBinding.Recording(<redacted>)"
    }
}

/** Typed result of binding one playback target to a current session observation. */
public sealed interface PlaybackBindingResult<out T : PlaybackBinding> {
    /** The target was present in the exact originating observation. */
    public class Bound<out T : PlaybackBinding> internal constructor(
        public val binding: T,
    ) : PlaybackBindingResult<T> {
        override fun toString(): String = "PlaybackBindingResult.Bound(<redacted>)"
    }

    /** The supplied current-session observation is no longer authoritative. */
    public data object ObservationExpired : PlaybackBindingResult<Nothing>

    /** The selected target was not uniquely present in the originating observation. */
    public data object TargetUnavailable : PlaybackBindingResult<Nothing>
}

/** Current side-effect-free admission state of one observation-bound recording target. */
public sealed interface RecordingPlaybackAdmission {
    /** A completed file may play; resume/reporting are enabled only when progress is supported. */
    public class Completed internal constructor(
        public val resumePosition: Duration?,
        public val progressCapability: RecordingProgressCapability,
    ) : RecordingPlaybackAdmission {
        override fun toString(): String = "RecordingPlaybackAdmission.Completed(<redacted>)"
    }

    /**
     * An active single-file MPEG-TS target may play while Tvheadend is still appending to it.
     *
     * [resumePosition] is the positive saved server position and is present only when [progressCapability] is
     * supported, exactly as for [Completed]. The Media3 coordinator plays this target only with
     * supported progress; it starts over without [resumePosition] or for an explicit start-over
     * request, and otherwise seeks near [resumePosition] once the growing timeline is seekable.
     */
    @ExperimentalTvheadendApi
    public class Growing internal constructor(
        public val resumePosition: Duration?,
        public val progressCapability: RecordingProgressCapability,
    ) : RecordingPlaybackAdmission {
        override fun toString(): String =
            "RecordingPlaybackAdmission.Growing(<redacted>)"
    }

    /** The active recording is outside the supported single-file MPEG-TS path. */
    public data object GrowingDeferred : RecordingPlaybackAdmission

    /** The recording is not currently playable. */
    public data object TargetUnavailable : RecordingPlaybackAdmission

    /** The observation that created the binding is no longer current. */
    public data object ObservationExpired : RecordingPlaybackAdmission
}

internal class SessionPlaybackBindingFactory(
    private val metadata: SessionMetadata,
    private val children: SessionChildren,
) {
    internal fun bindLive(
        currentSession: CurrentSessionObservation,
        channelId: ChannelId,
    ): PlaybackBindingResult<PlaybackBinding.Live> {
        val generation = metadata.resolveGeneration(currentSession)
            ?: return PlaybackBindingResult.ObservationExpired
        val observation = metadata.currentObservation(generation, currentSession)
            ?: return PlaybackBindingResult.ObservationExpired
        if (observation.channel(channelId) == null) return PlaybackBindingResult.TargetUnavailable

        return PlaybackBindingResult.Bound(
            PlaybackBinding.Live(
                current = {
                    metadata.currentObservation(generation, currentSession)?.channel(channelId) != null
                },
                openTarget = { consumer, options ->
                    if (
                        metadata.currentObservation(generation, currentSession)?.channel(channelId) == null
                    ) {
                        SubscriptionOpenResult.NotReady
                    } else {
                        children.open(
                            generation,
                            SubscriptionChannelId(channelId.value),
                            consumer,
                            options,
                        )
                    }
                },
            ),
        )
    }

    internal fun bindRecording(
        currentSession: CurrentSessionObservation,
        recordingId: DvrEntryId,
    ): PlaybackBindingResult<PlaybackBinding.Recording> {
        val generation = metadata.resolveGeneration(currentSession)
            ?: return PlaybackBindingResult.ObservationExpired
        val initial = when (
            val lookup = metadata.bindPlaybackRecording(generation, currentSession, recordingId)
        ) {
            PlaybackRecordingLookup.ObservationExpired ->
                return PlaybackBindingResult.ObservationExpired
            PlaybackRecordingLookup.TargetUnavailable ->
                return PlaybackBindingResult.TargetUnavailable
            is PlaybackRecordingLookup.Current -> lookup
        }
        val entry = initial.entry
        val target = initial.target
        val startedGrowing = entry.state == DvrEntryState.RECORDING

        fun admission(): RecordingPlaybackAdmission = when (
            val lookup = metadata.currentPlaybackRecording(target)
        ) {
            PlaybackRecordingLookup.ObservationExpired ->
                RecordingPlaybackAdmission.ObservationExpired
            PlaybackRecordingLookup.TargetUnavailable ->
                RecordingPlaybackAdmission.TargetUnavailable
            is PlaybackRecordingLookup.Current -> recordingAdmission(
                lookup.observation,
                lookup.entry,
            )
        }

        return PlaybackBindingResult.Bound(
            PlaybackBinding.Recording(
                startedGrowing = startedGrowing,
                observeAdmission = ::admission,
                openTarget = {
                    when (admission()) {
                        is RecordingPlaybackAdmission.Completed ->
                            children.openRecording(target)
                        RecordingPlaybackAdmission.ObservationExpired -> changedRecording()
                        is RecordingPlaybackAdmission.Growing,
                        RecordingPlaybackAdmission.GrowingDeferred,
                        RecordingPlaybackAdmission.TargetUnavailable,
                        -> unavailableRecording()
                    }
                },
                bindGrowingTarget = {
                    when (admission()) {
                        is RecordingPlaybackAdmission.Growing ->
                            children.bindGrowingRecording(target)
                        is RecordingPlaybackAdmission.Completed -> if (startedGrowing) {
                            children.bindGrowingRecording(target)
                        } else {
                            unavailableGrowingRecording()
                        }
                        RecordingPlaybackAdmission.ObservationExpired -> changedGrowingRecording()
                        RecordingPlaybackAdmission.GrowingDeferred,
                        RecordingPlaybackAdmission.TargetUnavailable,
                        -> unavailableGrowingRecording()
                    }
                },
                reportTargetProgress = report@ { lease, progress ->
                    if (
                        lease != null &&
                        (!startedGrowing || !lease.isBoundTo(generation, recordingId))
                    ) {
                        return@report DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)
                    }
                    when (val current = admission()) {
                        is RecordingPlaybackAdmission.Completed -> when (current.progressCapability) {
                            RecordingProgressCapability.UNKNOWN ->
                                DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)
                            RecordingProgressCapability.UNSUPPORTED ->
                                DvrProgressResult.Failed(TvheadendFailure.NOT_SUPPORTED, mayHaveApplied = false)
                            RecordingProgressCapability.SUPPORTED -> if (lease == null) {
                                metadata.reportProgress(target, progress)
                            } else {
                                metadata.reportProgress(lease, progress)
                            }
                        }
                        is RecordingPlaybackAdmission.Growing ->
                            when (current.progressCapability) {
                                RecordingProgressCapability.UNKNOWN ->
                                    DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)
                                RecordingProgressCapability.UNSUPPORTED ->
                                    DvrProgressResult.Failed(TvheadendFailure.NOT_SUPPORTED, mayHaveApplied = false)
                                RecordingProgressCapability.SUPPORTED -> lease?.let {
                                    metadata.reportProgress(it, progress)
                                } ?: DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)
                            }
                        RecordingPlaybackAdmission.ObservationExpired ->
                            DvrProgressResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, mayHaveApplied = false)
                        RecordingPlaybackAdmission.GrowingDeferred,
                        RecordingPlaybackAdmission.TargetUnavailable,
                        -> DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)
                    }
                },
                observeDeclaredVideoFrameDuration = {
                    (metadata.currentPlaybackRecording(target) as? PlaybackRecordingLookup.Current)
                        ?.entry
                        ?.declaredVideoFrameDuration()
                },
                loadTargetCutpoints = {
                    when (admission()) {
                        is RecordingPlaybackAdmission.Completed,
                        is RecordingPlaybackAdmission.Growing,
                        -> metadata.getCutpoints(target)
                        RecordingPlaybackAdmission.ObservationExpired ->
                            DvrCutpointsResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED)
                        RecordingPlaybackAdmission.GrowingDeferred,
                        RecordingPlaybackAdmission.TargetUnavailable,
                        -> DvrCutpointsResult.Failed(TvheadendFailure.NOT_READY)
                    }
                },
            ),
        )
    }
}

private fun recordingAdmission(
    observation: SessionObservation,
    entry: DvrEntry,
): RecordingPlaybackAdmission {
    val resumePosition = entry.playPosition
        ?.takeIf { position ->
            observation.recordingProgressCapability == RecordingProgressCapability.SUPPORTED &&
                position.isPositive()
        }
    return when (entry.state) {
        DvrEntryState.COMPLETED -> RecordingPlaybackAdmission.Completed(
            resumePosition = resumePosition,
            progressCapability = observation.recordingProgressCapability,
        )
        DvrEntryState.RECORDING -> {
            val file = entry.files?.singleOrNull()
                ?: return RecordingPlaybackAdmission.TargetUnavailable
            val path = file.path ?: entry.path
                ?: return RecordingPlaybackAdmission.TargetUnavailable
            if (path.isBlank()) {
                RecordingPlaybackAdmission.TargetUnavailable
            } else if (path.endsWith(".ts", ignoreCase = true)) {
                RecordingPlaybackAdmission.Growing(
                    resumePosition = resumePosition,
                    progressCapability = observation.recordingProgressCapability,
                )
            } else {
                RecordingPlaybackAdmission.GrowingDeferred
            }
        }
        else -> RecordingPlaybackAdmission.TargetUnavailable
    }
}

/** Tvheadend opens the last file of an entry; its first declared video stream in recorded order. */
internal fun DvrEntry.declaredVideoFrameDuration(): Duration? =
    files?.lastOrNull()
        ?.streams
        ?.firstOrNull { it.type in DECLARED_FRAME_DURATION_VIDEO_TYPES }
        ?.frameDuration
        ?.takeIf { it.isPositive() && it.isFinite() }

// Keep in sync with DECLARED_FRAME_DURATION_VIDEO_TYPES in sdk-media3 LiveTimeshiftControls.kt.
private val DECLARED_FRAME_DURATION_VIDEO_TYPES = setOf(
    SubscriptionStreamType.MPEG2_VIDEO,
    SubscriptionStreamType.H264,
    SubscriptionStreamType.H265,
)

private fun GrowingRecordingFileLease.isBoundTo(
    generation: GatewayGeneration,
    recordingId: DvrEntryId,
): Boolean = (this as? GenerationBoundGrowingRecordingFileLease)?.let { bound ->
    bound.boundGeneration === generation && bound.boundRecordingId == recordingId
} == true

private fun changedRecording(): RecordingFileResult<RecordingFile> =
    RecordingFileResult.Failed(TvheadendFailure.CONNECTION_CHANGED)

private fun unavailableRecording(): RecordingFileResult<RecordingFile> =
    RecordingFileResult.Failed(TvheadendFailure.FILE_UNAVAILABLE)

private fun changedGrowingRecording(): RecordingFileResult<GrowingRecordingFileLease> =
    RecordingFileResult.Failed(TvheadendFailure.CONNECTION_CHANGED)

private fun unavailableGrowingRecording(): RecordingFileResult<GrowingRecordingFileLease> =
    RecordingFileResult.Failed(TvheadendFailure.FILE_UNAVAILABLE)
