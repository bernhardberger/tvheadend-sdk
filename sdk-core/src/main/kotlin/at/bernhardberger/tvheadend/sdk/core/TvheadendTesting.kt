@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
)

package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.core.cache.CacheNamespace
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpener
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTracks
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Instant

/** Marks narrowly scoped construction APIs intended only for fake-backed tests. */
@RequiresOptIn(
    message = "This API constructs test-only session authority and playback results.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class TvheadendTestingApi
/** Test-only publisher backed by the same generation-authority store as production sessions. */
@TvheadendTestingApi
public class SessionGenerationTestAuthority(
    initialObservation: SessionObservation = SessionObservation.create(),
) {
    private val lock = Any()
    private val store = SessionObservationStore()
    private var generation: Any? = null
    private var profileIdentity: CacheNamespace? = null
    /** Atomic observations carrying proofs authored by this authority. */
    public val observation: StateFlow<SessionObservation> = store.observation
    init {
        publish(initialObservation)
    }
    /** Publishes state in the current generation, creating one when ready state first appears. */
    public fun publish(observation: SessionObservation) {
        synchronized(lock) { publishLocked(observation, profileIdentity) }
    }
    /**
     * Publishes state like [publish], atomically stamped as data of [profile], or of no profile
     * when null. Later publications keep that profile until another one is selected.
     */
    @ExperimentalTvheadendApi
    public fun publish(observation: SessionObservation, profile: ServerProfile?) {
        synchronized(lock) { publishLocked(observation, profile?.cacheNamespace()) }
    }
    /** Publishes ready state under a new generation and retires every prior proof. */
    public fun replaceGeneration(observation: SessionObservation) {
        synchronized(lock) { replaceGenerationLocked(observation, profileIdentity) }
    }
    /** Publishes like [replaceGeneration], atomically stamped as data of [profile] (see [publish]). */
    @ExperimentalTvheadendApi
    public fun replaceGeneration(observation: SessionObservation, profile: ServerProfile?) {
        synchronized(lock) { replaceGenerationLocked(observation, profile?.cacheNamespace()) }
    }
    private fun publishLocked(observation: SessionObservation, identity: CacheNamespace?) {
        val publishedGeneration = if (observation.sessionState is SessionState.Ready) {
            generation ?: Any().also { generation = it }
        } else {
            generation = null
            null
        }
        profileIdentity = identity
        store.publishObservation(observation, publishedGeneration, identity)
    }
    private fun replaceGenerationLocked(observation: SessionObservation, identity: CacheNamespace?) {
        require(observation.sessionState is SessionState.Ready) {
            "A replacement generation requires a ready session observation"
        }
        val replacement = Any()
        generation = replacement
        profileIdentity = identity
        store.publishObservation(observation, replacement, identity)
    }
}
/** Test-only factories for otherwise opaque playback bindings and admission values used by session fakes. */
@TvheadendTestingApi
public object TvheadendTestResultFactory {
    /** Creates an authority-only binding whose subscription opens return NotReady. */
    @JvmStatic
    public fun boundLivePlayback(
        session: TvheadendSession,
        currentSession: CurrentSessionObservation,
        channelId: ChannelId,
    ): PlaybackBindingResult<PlaybackBinding.Live> = boundLivePlayback(
        session, currentSession, channelId, SubscriptionOpener { _, _, _ -> SubscriptionOpenResult.NotReady },
    )

    /**
     * Creates a live binding tied to [currentSession] while [session] retains that proof.
     * [opener] supplies real offline media; the test owns its manager and teardown.
     */
    @JvmStatic
    public fun boundLivePlayback(
        session: TvheadendSession,
        currentSession: CurrentSessionObservation,
        channelId: ChannelId,
        opener: SubscriptionOpener,
    ): PlaybackBindingResult<PlaybackBinding.Live> {
        val initial = currentObservation(session, currentSession)
            ?: return PlaybackBindingResult.ObservationExpired
        if (initial.channel(channelId) == null) return PlaybackBindingResult.TargetUnavailable
        fun current(): Boolean = currentObservation(session, currentSession)?.channel(channelId) != null
        return PlaybackBindingResult.Bound(
            PlaybackBinding.Live(
                current = ::current,
                openTarget = open@ { consumer, options ->
                    currentCoroutineContext().ensureActive()
                    if (!current()) return@open SubscriptionOpenResult.NotReady
                    val result = opener.open(SubscriptionChannelId(channelId.value), object : SubscriptionEventConsumer {
                        override suspend fun accept(event: SubscriptionEvent) {
                            if (current()) consumer.accept(event)
                        }
                        override fun tracksReady(tracks: SubscriptionTracks) {
                            if (current()) consumer.tracksReady(tracks)
                        }
                    }, options)
                    var transferred = false
                    try {
                        currentCoroutineContext().ensureActive()
                        if (current()) {
                            transferred = true
                            result
                        } else {
                            SubscriptionOpenResult.NotReady
                        }
                    } finally {
                        if (!transferred && result is SubscriptionOpenResult.Opened) {
                            withContext(NonCancellable) { result.subscription.close() }
                        }
                    }
                },
            ),
        )
    }
    /**
     * Creates a completed-recording binding tied to [currentSession].
     * Like the session binding, it admits completed entries and failed or missed entries that list a
     * recording file whose data size is not known to be zero; other targets are unavailable.
     * [cutpoints] supplies test-owned responses while the same recording remains available.
     * The binding checks cancellation and authority before and after the suspending response.
     * Scripts must not return [TvheadendFailure.OBSERVATION_EXPIRED]; expiry belongs to the binding.
     * Omitting [cutpoints] retains the default [TvheadendFailure.NOT_READY] response.
     */
    @JvmStatic
    public fun boundCompletedRecordingPlayback(
        session: TvheadendSession,
        currentSession: CurrentSessionObservation,
        recordingId: DvrEntryId,
        cutpoints: suspend () -> DvrCutpointsResult = { DvrCutpointsResult.Failed(TvheadendFailure.NOT_READY) },
    ): PlaybackBindingResult<PlaybackBinding.Recording> = boundRecordingPlayback(
        session,
        currentSession,
        recordingId,
        cutpoints,
    )

    /** Creates a completed-recording admission with scripted resume and progress capability. */
    @JvmStatic
    public fun completedRecordingAdmission(
        resumePosition: Duration?,
        progressCapability: RecordingProgressCapability,
    ): RecordingPlaybackAdmission.Completed = RecordingPlaybackAdmission.Completed(resumePosition, progressCapability)

    /** Creates a growing-recording admission with scripted resume and progress capability. */
    @ExperimentalTvheadendApi
    @JvmStatic
    public fun growingRecordingAdmission(
        resumePosition: Duration?,
        progressCapability: RecordingProgressCapability,
    ): RecordingPlaybackAdmission.Growing = RecordingPlaybackAdmission.Growing(resumePosition, progressCapability)

    /**
     * Creates a recording file whose private server path is [serverPath] (blank is allowed), so fake
     * sessions can build entries that [SessionObservation.recordingPlaybackAdmission] admits like real
     * ones. The path is write-only: it has no getter and never appears in [DvrRecordingFile.toString].
     * As documented for [DvrEntry.folder], a path-bearing file does feed that display-only folder.
     */
    @JvmStatic
    public fun recordingFile(
        fileId: Long? = null,
        serverPath: String,
        start: Instant? = null,
        stop: Instant? = null,
        sizeBytes: Long? = null,
    ): DvrRecordingFile = DvrRecordingFile(fileId, serverPath, start, stop, sizeBytes)

    /**
     * Creates a cached-artwork reference for [profile] and [artworkId] so fake [ArtworkLoader]s can
     * return it from [ArtworkLoader.cachedArtwork]. Its cache key matches the real session's key for
     * the same profile and artwork.
     */
    @ExperimentalTvheadendApi
    @JvmStatic
    public fun cachedArtwork(profile: ServerProfile, artworkId: ArtworkId): CachedArtworkReference =
        CachedArtworkReference.create(profile.cacheNamespace(), artworkId)

    /**
     * Creates the cached-artwork reference a real session would derive from [observation]'s
     * profile, ignoring which profile is active, or null when [observation] carries no profile
     * data. Fake [ArtworkLoader]s compare it with [cachedArtwork] for their active profile.
     */
    @ExperimentalTvheadendApi
    @JvmStatic
    public fun cachedArtwork(observation: SessionObservation, artworkId: ArtworkId): CachedArtworkReference? =
        observation.profileIdentity?.let { namespace -> CachedArtworkReference.create(namespace, artworkId) }

    private fun boundRecordingPlayback(
        session: TvheadendSession,
        currentSession: CurrentSessionObservation,
        recordingId: DvrEntryId,
        cutpoints: suspend () -> DvrCutpointsResult,
    ): PlaybackBindingResult<PlaybackBinding.Recording> {
        val initial = currentObservation(session, currentSession)
            ?: return PlaybackBindingResult.ObservationExpired
        val target = initial.dvrEntry(recordingId)
            ?.takeIf(DvrEntry::isPlayableEndedRecording)
            ?: return PlaybackBindingResult.TargetUnavailable
        fun admission(): RecordingPlaybackAdmission {
            val observation = currentObservation(session, currentSession)
                ?: return RecordingPlaybackAdmission.ObservationExpired
            if (observation.dvrEntry(recordingId) !== target) {
                return RecordingPlaybackAdmission.TargetUnavailable
            }
            return observation.recordingPlaybackAdmission(target)
        }
        fun unavailableFile() = RecordingFileResult.Failed(
            if (admission() == RecordingPlaybackAdmission.ObservationExpired) {
                TvheadendFailure.CONNECTION_CHANGED
            } else {
                TvheadendFailure.FILE_UNAVAILABLE
            },
        )
        return PlaybackBindingResult.Bound(
            PlaybackBinding.Recording(
                startedGrowing = false,
                observeAdmission = ::admission,
                openTarget = { cancellationAware(::unavailableFile) },
                bindGrowingTarget = { unavailableFile() },
                observeDeclaredVideoFrameDuration = {
                    currentObservation(session, currentSession)
                        ?.dvrEntry(recordingId)
                        ?.takeIf { it === target }
                        ?.declaredVideoFrameDuration()
                },
                reportTargetProgress = { _, _ ->
                    cancellationAware {
                        when (val state = admission()) {
                            is RecordingPlaybackAdmission.Completed ->
                                if (state.progressCapability == RecordingProgressCapability.UNSUPPORTED) {
                                    DvrProgressResult.Failed(TvheadendFailure.NOT_SUPPORTED, mayHaveApplied = false)
                                } else {
                                    DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)
                                }
                            RecordingPlaybackAdmission.ObservationExpired ->
                                DvrProgressResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, mayHaveApplied = false)
                            else -> DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)
                        }
                    }
                },
                loadTargetCutpoints = cutpoints@{
                    currentCoroutineContext().ensureActive()
                    when (admission()) {
                        RecordingPlaybackAdmission.ObservationExpired ->
                            return@cutpoints DvrCutpointsResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED)
                        is RecordingPlaybackAdmission.Completed -> Unit
                        else -> return@cutpoints DvrCutpointsResult.Failed(TvheadendFailure.NOT_READY)
                    }
                    val result = cutpoints()
                    currentCoroutineContext().ensureActive()
                    when (admission()) {
                        RecordingPlaybackAdmission.ObservationExpired ->
                            DvrCutpointsResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED)
                        is RecordingPlaybackAdmission.Completed -> {
                            require(result != DvrCutpointsResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED)) {
                                "Scripted cutpoint responses cannot counterfeit observation expiry"
                            }
                            result
                        }
                        else -> DvrCutpointsResult.Failed(TvheadendFailure.NOT_READY)
                    }
                },
            ),
        )
    }

    private fun currentObservation(
        session: TvheadendSession,
        currentSession: CurrentSessionObservation,
    ): SessionObservation? = session.observation.value.takeIf { observation ->
        observation.currentSession === currentSession && session.isCurrent(currentSession)
    }

    private suspend fun <T> cancellationAware(result: () -> T): T {
        currentCoroutineContext().ensureActive()
        val value = result()
        currentCoroutineContext().ensureActive()
        return value
    }
}
