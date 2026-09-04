@file:OptIn(
    at.bernhardberger.tvheadend.sdk.core.TvheadendTestingApi::class,
    at.bernhardberger.tvheadend.sdk.testing.FakePlaybackApi::class,
)

package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.ArtworkFailure
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoadResult
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoader
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrMutationResult
import at.bernhardberger.tvheadend.sdk.core.DvrRepository
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageAcquisitionResult
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageBatchResult
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageBatchSettlement
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EpgRepository
import at.bernhardberger.tvheadend.sdk.core.EpgSearchRequest
import at.bernhardberger.tvheadend.sdk.core.EpgSearchResult
import at.bernhardberger.tvheadend.sdk.core.PlaybackBinding
import at.bernhardberger.tvheadend.sdk.core.PlaybackBindingResult
import at.bernhardberger.tvheadend.sdk.core.ServerProfile
import at.bernhardberger.tvheadend.sdk.core.SessionCommandResult
import at.bernhardberger.tvheadend.sdk.core.SessionGenerationTestAuthority
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.StreamProfile
import at.bernhardberger.tvheadend.sdk.core.StreamProfilesResult
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleId
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import at.bernhardberger.tvheadend.sdk.core.TvheadendTestResultFactory
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant
/** Marks fake playback bindings intended only for playback integration tests. */
@RequiresOptIn(
    message = "Fake playback bindings are test-only authority objects.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class FakePlaybackApi
/** Value-free invocation labels safe for assertions and diagnostics. */
public enum class FakeSessionCall {
    CONNECT,
    RETRY,
    DISCONNECT,
    SHUTDOWN,
    GET_STREAM_PROFILES,
    BIND_LIVE_PLAYBACK,
    BIND_RECORDING_PLAYBACK,
    EPG_SEARCH,
    EPG_ACQUIRE_COVERAGE,
    EPG_ACQUIRE_COVERAGE_BATCH,
    DVR_SCHEDULE_ENTRY,
    DVR_UPDATE_ENTRY,
    DVR_STOP_ENTRY,
    DVR_CANCEL_ENTRY,
    DVR_DELETE_ENTRY,
    DVR_CREATE_AUTOREC_RULE,
    DVR_UPDATE_AUTOREC_RULE,
    DVR_DELETE_AUTOREC_RULE,
    DVR_CREATE_TIMEREC_RULE,
    DVR_UPDATE_TIMEREC_RULE,
    DVR_DELETE_TIMEREC_RULE,
    LOAD_ARTWORK,
}
/**
 * JVM-only, generation-aware implementation of the complete application session boundary.
 * Supplied observations are republished under fake authority; capture this fake's current proof.
 */
public class FakeTvheadendSession(
    initialObservation: SessionObservation = SessionObservation.create(),
) : TvheadendSession {
    private val lock = Any()
    private val authority = SessionGenerationTestAuthority(initialObservation)
    private val mutableCalls = ArrayList<FakeSessionCall>()
    private var connectResult = SessionCommandResult.STARTED
    private var retryResult = SessionCommandResult.NO_ACTIVE_PROFILE
    private var streamProfilesScript: StreamProfilesScript = StreamProfilesScript.Result(
        StreamProfilesResult.NotReady,
    )
    private var liveBindingScript: PlaybackBindingScript = PlaybackBindingScript.Failure(
        PlaybackBindingResult.TargetUnavailable,
    )
    private var recordingBindingScript: PlaybackBindingScript = PlaybackBindingScript.Failure(
        PlaybackBindingResult.TargetUnavailable,
    )
    override val observation: StateFlow<SessionObservation> = authority.observation
    override val epgRepository: FakeEpgRepository = FakeEpgRepository(this)
    override val dvrRepository: FakeDvrRepository = FakeDvrRepository(this)
    override val artwork: FakeArtworkLoader = FakeArtworkLoader(this)
    /** Snapshot of invocation order across the session and its repositories. */
    public val calls: List<FakeSessionCall>
        get() = synchronized(lock) { mutableCalls.toImmutableList() }
    /** Publishes state while retaining current generation authority. */
    public fun publish(observation: SessionObservation) {
        authority.publish(observation)
    }
    /** Publishes ready state under replacement generation authority. */
    public fun replaceGeneration(observation: SessionObservation) {
        authority.replaceGeneration(observation)
    }
    /** Retires current proof with a complete non-current observation. */
    public fun retire(observation: SessionObservation = SessionObservation.create()) {
        require(observation.sessionState !is at.bernhardberger.tvheadend.sdk.core.SessionState.Ready) {
            "A retired session observation must not be ready"
        }
        authority.publish(observation)
    }
    /** Returns this fake's exact current proof. */
    public fun captureCurrentSession(): CurrentSessionObservation =
        checkNotNull(observation.value.currentSession) { "The fake session is not current" }
    /** Scripts the next and subsequent connect result. */
    public fun scriptConnect(result: SessionCommandResult) {
        synchronized(lock) { connectResult = result }
    }
    /** Scripts the next and subsequent retry result. */
    public fun scriptRetry(result: SessionCommandResult) {
        synchronized(lock) { retryResult = result }
    }
    /** Scripts failed stream-profile discovery. */
    public fun scriptStreamProfilesFailure(result: StreamProfilesResult) {
        require(result !is StreamProfilesResult.Available && result !== StreamProfilesResult.ObservationExpired) {
            "Scripted stream-profile failures require a current observation"
        }
        synchronized(lock) { streamProfilesScript = StreamProfilesScript.Result(result) }
    }
    /** Scripts successful discovery with invocation-proof provenance. */
    public fun scriptStreamProfiles(profiles: List<StreamProfile>) {
        synchronized(lock) {
            streamProfilesScript = StreamProfilesScript.Available(profiles.toImmutableList())
        }
    }
    /** Scripts a generation-bound live binding. */
    @FakePlaybackApi
    public fun scriptLivePlaybackSuccess() {
        synchronized(lock) { liveBindingScript = PlaybackBindingScript.Live }
    }
    /** Scripts a generation-bound completed-recording binding. */
    @FakePlaybackApi
    public fun scriptRecordingPlaybackSuccess() {
        synchronized(lock) {
            recordingBindingScript = PlaybackBindingScript.CompletedRecording
        }
    }
    /** Scripts a failed live binding result. */
    @FakePlaybackApi
    public fun scriptLivePlaybackFailure(result: PlaybackBindingResult<Nothing>) {
        require(result === PlaybackBindingResult.TargetUnavailable) {
            "Scripted binding failures require a current observation"
        }
        synchronized(lock) { liveBindingScript = PlaybackBindingScript.Failure(result) }
    }
    /** Scripts a failed recording binding result. */
    @FakePlaybackApi
    public fun scriptRecordingPlaybackFailure(result: PlaybackBindingResult<Nothing>) {
        require(result === PlaybackBindingResult.TargetUnavailable) {
            "Scripted binding failures require a current observation"
        }
        synchronized(lock) { recordingBindingScript = PlaybackBindingScript.Failure(result) }
    }
    override suspend fun getStreamProfiles(
        currentSession: CurrentSessionObservation,
    ): StreamProfilesResult = roundTrip(
        currentSession,
        FakeSessionCall.GET_STREAM_PROFILES,
        StreamProfilesResult.ObservationExpired,
    ) {
        when (val script = synchronized(lock) { streamProfilesScript }) {
            is StreamProfilesScript.Available ->
                StreamProfilesResult.Available.create(script.profiles, currentSession)
            is StreamProfilesScript.Result -> script.result
        }
    }
    override fun bindLivePlayback(
        currentSession: CurrentSessionObservation,
        channelId: ChannelId,
    ): PlaybackBindingResult<PlaybackBinding.Live> {
        record(FakeSessionCall.BIND_LIVE_PLAYBACK)
        if (!isCurrent(currentSession)) return PlaybackBindingResult.ObservationExpired
        return when (val script = synchronized(lock) { liveBindingScript }) {
            PlaybackBindingScript.Live ->
                TvheadendTestResultFactory.boundLivePlayback(this, currentSession, channelId)
            is PlaybackBindingScript.Failure -> script.result
            is PlaybackBindingScript.CompletedRecording,
            -> error("Invalid live playback script")
        }
    }
    override fun bindRecordingPlayback(
        currentSession: CurrentSessionObservation,
        recordingId: DvrEntryId,
    ): PlaybackBindingResult<PlaybackBinding.Recording> {
        record(FakeSessionCall.BIND_RECORDING_PLAYBACK)
        if (!isCurrent(currentSession)) return PlaybackBindingResult.ObservationExpired
        return when (val script = synchronized(lock) { recordingBindingScript }) {
            PlaybackBindingScript.CompletedRecording ->
                TvheadendTestResultFactory.boundCompletedRecordingPlayback(
                    this,
                    currentSession,
                    recordingId,
                )
            is PlaybackBindingScript.Failure -> script.result
            PlaybackBindingScript.Live -> error("Invalid recording playback script")
        }
    }
    override suspend fun connect(profile: ServerProfile): SessionCommandResult = command(
        FakeSessionCall.CONNECT,
    ) { synchronized(lock) { connectResult } }
    override suspend fun retry(): SessionCommandResult = command(FakeSessionCall.RETRY) {
        synchronized(lock) { retryResult }
    }
    override suspend fun disconnect() {
        command(FakeSessionCall.DISCONNECT) { Unit }
    }
    override suspend fun shutdown() {
        command(FakeSessionCall.SHUTDOWN) { Unit }
    }
    private fun record(call: FakeSessionCall) {
        synchronized(lock) { mutableCalls += call }
    }
    private suspend fun <T> command(call: FakeSessionCall, result: () -> T): T {
        currentCoroutineContext().ensureActive()
        record(call)
        val value = result()
        currentCoroutineContext().ensureActive()
        return value
    }
    internal suspend fun <T> roundTrip(
        currentSession: CurrentSessionObservation,
        call: FakeSessionCall,
        expired: T,
        result: () -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        record(call)
        if (!isCurrent(currentSession)) return expired
        val value = result()
        currentCoroutineContext().ensureActive()
        return if (isCurrent(currentSession)) value else expired
    }
}
/** Scriptable EPG boundary owned by [FakeTvheadendSession]. */
public class FakeEpgRepository internal constructor(
    private val session: FakeTvheadendSession,
) : EpgRepository {
    private val lock = Any()
    private var searchScript: EpgSearchScript = EpgSearchScript.Result(EpgSearchResult.NotSupported)
    private var coverageResult: EpgCoverageAcquisitionResult = EpgCoverageAcquisitionResult.Ineligible
    private var coverageBatchResult: EpgCoverageBatchResult? = null
    /** Scripts a failed search result. */
    public fun scriptSearchFailure(result: EpgSearchResult) {
        require(result !is EpgSearchResult.Available && result !== EpgSearchResult.ObservationExpired) {
            "Scripted search failures require a current observation"
        }
        synchronized(lock) { searchScript = EpgSearchScript.Result(result) }
    }

    /** Scripts successful search with provenance from the proof supplied to each invocation. */
    public fun scriptSearch(events: List<EpgEvent>) {
        synchronized(lock) { searchScript = EpgSearchScript.Available(events.toImmutableList()) }
    }
    /** Scripts the next and subsequent coverage result. */
    public fun scriptCoverage(result: EpgCoverageAcquisitionResult) {
        require(result !== EpgCoverageAcquisitionResult.ObservationExpired) {
            "Scripted coverage results require a current observation"
        }
        synchronized(lock) { coverageResult = result }
    }
    /** Scripts the next and subsequent batch coverage result. */
    public fun scriptCoverageBatch(result: EpgCoverageBatchResult) {
        synchronized(lock) { coverageBatchResult = result }
    }
    override suspend fun search(
        currentSession: CurrentSessionObservation,
        request: EpgSearchRequest,
    ): EpgSearchResult = session.roundTrip(
        currentSession,
        FakeSessionCall.EPG_SEARCH,
        EpgSearchResult.ObservationExpired,
    ) {
        when (val script = synchronized(lock) { searchScript }) {
            is EpgSearchScript.Available -> EpgSearchResult.Available.create(
                script.events,
                currentSession,
            )
            is EpgSearchScript.Result -> script.result
        }
    }
    override suspend fun acquireCoverage(
        currentSession: CurrentSessionObservation,
        channelId: ChannelId,
        through: Instant,
    ): EpgCoverageAcquisitionResult = session.roundTrip(
        currentSession,
        FakeSessionCall.EPG_ACQUIRE_COVERAGE,
        EpgCoverageAcquisitionResult.ObservationExpired,
    ) { synchronized(lock) { coverageResult } }
    override suspend fun acquireCoverageBatch(
        currentSession: CurrentSessionObservation,
        channelIds: List<ChannelId>,
        through: Instant,
    ): EpgCoverageBatchResult {
        val orderedChannelIds = channelIds.distinct()
        return session.roundTrip(
            currentSession,
            FakeSessionCall.EPG_ACQUIRE_COVERAGE_BATCH,
            EpgCoverageBatchResult.create(
                orderedChannelIds.map(EpgCoverageBatchSettlement::ObservationExpired),
            ),
        ) {
            synchronized(lock) {
                coverageBatchResult ?: EpgCoverageBatchResult.create(
                    orderedChannelIds.map { channelId -> coverageResult.toBatchSettlement(channelId) },
                )
            }
        }
    }
}

private fun EpgCoverageAcquisitionResult.toBatchSettlement(
    channelId: ChannelId,
): EpgCoverageBatchSettlement = when (this) {
    is EpgCoverageAcquisitionResult.CoveredWithData ->
        EpgCoverageBatchSettlement.CoveredWithData(channelId, observation)
    is EpgCoverageAcquisitionResult.CoveredEmpty ->
        EpgCoverageBatchSettlement.CoveredEmpty(channelId, observation)
    EpgCoverageAcquisitionResult.Ineligible -> EpgCoverageBatchSettlement.Rejected(channelId)
    EpgCoverageAcquisitionResult.ObservationExpired ->
        EpgCoverageBatchSettlement.ObservationExpired(channelId)
}

/** Scriptable DVR command boundary owned by [FakeTvheadendSession]. */
public class FakeDvrRepository internal constructor(
    private val session: FakeTvheadendSession,
) : DvrRepository {
    private val lock = Any()
    private val results = HashMap<FakeSessionCall, DvrMutationResult<*>>()
    /** Scripts the schedule-entry result. */
    public fun scriptScheduleEntry(result: DvrMutationResult<DvrEntryId>): Unit =
        script(FakeSessionCall.DVR_SCHEDULE_ENTRY, result)
    /** Scripts the update-entry result. */
    public fun scriptUpdateEntry(result: DvrMutationResult<Unit>): Unit =
        script(FakeSessionCall.DVR_UPDATE_ENTRY, result)
    /** Scripts the stop-entry result. */
    public fun scriptStopEntry(result: DvrMutationResult<Unit>): Unit = script(FakeSessionCall.DVR_STOP_ENTRY, result)
    /** Scripts the cancel-entry result. */
    public fun scriptCancelEntry(result: DvrMutationResult<Unit>): Unit =
        script(FakeSessionCall.DVR_CANCEL_ENTRY, result)
    /** Scripts the delete-entry result. */
    public fun scriptDeleteEntry(result: DvrMutationResult<Unit>): Unit =
        script(FakeSessionCall.DVR_DELETE_ENTRY, result)
    /** Scripts the create-autorec result. */
    public fun scriptCreateAutorecRule(result: DvrMutationResult<AutorecRuleId>): Unit =
        script(FakeSessionCall.DVR_CREATE_AUTOREC_RULE, result)
    /** Scripts the update-autorec result. */
    public fun scriptUpdateAutorecRule(result: DvrMutationResult<Unit>): Unit =
        script(FakeSessionCall.DVR_UPDATE_AUTOREC_RULE, result)
    /** Scripts the delete-autorec result. */
    public fun scriptDeleteAutorecRule(result: DvrMutationResult<Unit>): Unit =
        script(FakeSessionCall.DVR_DELETE_AUTOREC_RULE, result)
    /** Scripts the create-timerec result. */
    public fun scriptCreateTimerecRule(result: DvrMutationResult<TimerecRuleId>): Unit =
        script(FakeSessionCall.DVR_CREATE_TIMEREC_RULE, result)
    /** Scripts the update-timerec result. */
    public fun scriptUpdateTimerecRule(result: DvrMutationResult<Unit>): Unit =
        script(FakeSessionCall.DVR_UPDATE_TIMEREC_RULE, result)
    /** Scripts the delete-timerec result. */
    public fun scriptDeleteTimerecRule(result: DvrMutationResult<Unit>): Unit =
        script(FakeSessionCall.DVR_DELETE_TIMEREC_RULE, result)
    override suspend fun scheduleEntry(
        currentSession: CurrentSessionObservation,
        request: DvrScheduleRequest,
    ): DvrMutationResult<DvrEntryId> = result(currentSession, FakeSessionCall.DVR_SCHEDULE_ENTRY)
    override suspend fun updateEntry(
        currentSession: CurrentSessionObservation,
        id: DvrEntryId,
        update: DvrEntryUpdate,
    ): DvrMutationResult<Unit> = result(currentSession, FakeSessionCall.DVR_UPDATE_ENTRY)
    override suspend fun stopEntry(
        currentSession: CurrentSessionObservation,
        id: DvrEntryId,
    ): DvrMutationResult<Unit> = result(currentSession, FakeSessionCall.DVR_STOP_ENTRY)
    override suspend fun cancelEntry(
        currentSession: CurrentSessionObservation,
        id: DvrEntryId,
    ): DvrMutationResult<Unit> = result(currentSession, FakeSessionCall.DVR_CANCEL_ENTRY)
    override suspend fun deleteEntry(
        currentSession: CurrentSessionObservation,
        id: DvrEntryId,
    ): DvrMutationResult<Unit> = result(currentSession, FakeSessionCall.DVR_DELETE_ENTRY)
    override suspend fun createAutorecRule(
        currentSession: CurrentSessionObservation,
        request: AutorecRuleCreate,
    ): DvrMutationResult<AutorecRuleId> = result(
        currentSession,
        FakeSessionCall.DVR_CREATE_AUTOREC_RULE,
    )
    override suspend fun updateAutorecRule(
        currentSession: CurrentSessionObservation,
        id: AutorecRuleId,
        update: AutorecRuleUpdate,
    ): DvrMutationResult<Unit> = result(currentSession, FakeSessionCall.DVR_UPDATE_AUTOREC_RULE)
    override suspend fun deleteAutorecRule(
        currentSession: CurrentSessionObservation,
        id: AutorecRuleId,
    ): DvrMutationResult<Unit> = result(currentSession, FakeSessionCall.DVR_DELETE_AUTOREC_RULE)
    override suspend fun createTimerecRule(
        currentSession: CurrentSessionObservation,
        request: TimerecRuleCreate,
    ): DvrMutationResult<TimerecRuleId> = result(
        currentSession,
        FakeSessionCall.DVR_CREATE_TIMEREC_RULE,
    )
    override suspend fun updateTimerecRule(
        currentSession: CurrentSessionObservation,
        id: TimerecRuleId,
        update: TimerecRuleUpdate,
    ): DvrMutationResult<Unit> = result(currentSession, FakeSessionCall.DVR_UPDATE_TIMEREC_RULE)
    override suspend fun deleteTimerecRule(
        currentSession: CurrentSessionObservation,
        id: TimerecRuleId,
    ): DvrMutationResult<Unit> = result(currentSession, FakeSessionCall.DVR_DELETE_TIMEREC_RULE)
    private fun script(call: FakeSessionCall, result: DvrMutationResult<*>) {
        require(result !== DvrMutationResult.ObservationExpired) {
            "Scripted DVR results require a current observation"
        }
        synchronized(lock) { results[call] = result }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> result(
        currentSession: CurrentSessionObservation,
        call: FakeSessionCall,
    ): DvrMutationResult<T> = session.roundTrip(
        currentSession,
        call,
        DvrMutationResult.ObservationExpired,
    ) {
        synchronized(lock) {
            (results[call] ?: DvrMutationResult.NotReady) as DvrMutationResult<T>
        }
    }
}

/** Scriptable artwork boundary owned by [FakeTvheadendSession]. */
public class FakeArtworkLoader internal constructor(
    private val session: FakeTvheadendSession,
) : ArtworkLoader {
    private val lock = Any()
    private var result: ArtworkLoadResult = ArtworkLoadResult.Unavailable(ArtworkFailure.NOT_SUPPORTED)
    /** Scripts the next and subsequent artwork result. */
    public fun scriptLoad(result: ArtworkLoadResult) {
        require((result as? ArtworkLoadResult.Unavailable)?.failure != ArtworkFailure.OBSERVATION_EXPIRED) {
            "Scripted artwork results require a current observation"
        }
        synchronized(lock) { this.result = result }
    }

    override suspend fun loadArtwork(
        currentSession: CurrentSessionObservation,
        artworkId: ArtworkId,
    ): ArtworkLoadResult = session.roundTrip(
        currentSession,
        FakeSessionCall.LOAD_ARTWORK,
        ArtworkLoadResult.Unavailable(ArtworkFailure.OBSERVATION_EXPIRED),
    ) { synchronized(lock) { result } }
}

private sealed interface StreamProfilesScript {
    class Available(val profiles: List<StreamProfile>) : StreamProfilesScript
    class Result(val result: StreamProfilesResult) : StreamProfilesScript
}

private sealed interface EpgSearchScript {
    class Available(val events: List<EpgEvent>) : EpgSearchScript
    class Result(val result: EpgSearchResult) : EpgSearchScript
}

private sealed interface PlaybackBindingScript {
    data object Live : PlaybackBindingScript
    data object CompletedRecording : PlaybackBindingScript
    class Failure(val result: PlaybackBindingResult<Nothing>) : PlaybackBindingScript
}

private fun <T> Collection<T>.toImmutableList(): List<T> =
    Collections.unmodifiableList(ArrayList(this))
