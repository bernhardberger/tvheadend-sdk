@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpener
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTracks
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow

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
    /** Atomic observations carrying proofs authored by this authority. */
    public val observation: StateFlow<SessionObservation> = store.observation
    init {
        publish(initialObservation)
    }
    /** Publishes state in the current generation, creating one when ready state first appears. */
    public fun publish(observation: SessionObservation) {
        synchronized(lock) {
            val publishedGeneration = if (observation.sessionState is SessionState.Ready) {
                generation ?: Any().also { generation = it }
            } else {
                generation = null
                null
            }
            store.publishObservation(observation, publishedGeneration)
        }
    }
    /** Publishes ready state under a new generation and retires every prior proof. */
    public fun replaceGeneration(observation: SessionObservation) {
        require(observation.sessionState is SessionState.Ready) {
            "A replacement generation requires a ready session observation"
        }
        synchronized(lock) {
            val replacement = Any()
            generation = replacement
            store.publishObservation(observation, replacement)
        }
    }
}
/** Validated factories for otherwise opaque playback bindings used by session fakes. */
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
    /** Creates a completed-recording binding tied to [currentSession]. */
    @JvmStatic
    public fun boundCompletedRecordingPlayback(
        session: TvheadendSession,
        currentSession: CurrentSessionObservation,
        recordingId: DvrEntryId,
    ): PlaybackBindingResult<PlaybackBinding.Recording> = boundRecordingPlayback(
        session,
        currentSession,
        recordingId,
    )
    private fun boundRecordingPlayback(
        session: TvheadendSession,
        currentSession: CurrentSessionObservation,
        recordingId: DvrEntryId,
    ): PlaybackBindingResult<PlaybackBinding.Recording> {
        val initial = currentObservation(session, currentSession)
            ?: return PlaybackBindingResult.ObservationExpired
        val target = initial.dvrEntry(recordingId)
            ?.takeIf { entry -> entry.state == DvrEntryState.COMPLETED }
            ?: return PlaybackBindingResult.TargetUnavailable
        fun admission(): RecordingPlaybackAdmission {
            val observation = currentObservation(session, currentSession)
                ?: return RecordingPlaybackAdmission.ObservationExpired
            if (observation.dvrEntry(recordingId) !== target) {
                return RecordingPlaybackAdmission.TargetUnavailable
            }
            val capability = observation.recordingProgressCapability
            return RecordingPlaybackAdmission.Completed(
                target.playPosition?.takeIf { it.isPositive() && capability == RecordingProgressCapability.SUPPORTED },
                capability,
            )
        }
        fun unavailableFile() = RecordingFileResult.Failed(
            if (admission() == RecordingPlaybackAdmission.ObservationExpired) {
                RecordingFileFailure.CONNECTION_CHANGED
            } else {
                RecordingFileFailure.FILE_UNAVAILABLE
            },
        )
        return PlaybackBindingResult.Bound(
            PlaybackBinding.Recording(
                startedGrowing = false,
                observeAdmission = ::admission,
                openTarget = { cancellationAware(::unavailableFile) },
                bindGrowingTarget = { unavailableFile() },
                reportTargetProgress = { _, _ ->
                    cancellationAware {
                        when (val state = admission()) {
                            is RecordingPlaybackAdmission.Completed ->
                                if (state.progressCapability == RecordingProgressCapability.UNSUPPORTED) {
                                    DvrProgressResult.NotSupported
                                } else {
                                    DvrProgressResult.NotReady
                                }
                            RecordingPlaybackAdmission.ObservationExpired ->
                                DvrProgressResult.ObservationExpired
                            else -> DvrProgressResult.NotReady
                        }
                    }
                },
                loadTargetCutpoints = {
                    cancellationAware {
                        if (admission() == RecordingPlaybackAdmission.ObservationExpired) {
                            DvrCutpointsResult.ObservationExpired
                        } else {
                            DvrCutpointsResult.NotReady
                        }
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
