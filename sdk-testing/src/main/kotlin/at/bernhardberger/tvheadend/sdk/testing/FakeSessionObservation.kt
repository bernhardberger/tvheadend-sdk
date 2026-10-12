package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.MetadataRefreshTimes
import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
import kotlin.time.Instant

/** Mutable aggregate session observation source for application and SDK consumer tests. */
public class FakeSessionObservation(
    initialObservation: SessionObservation = SessionObservation.create(),
) {
    /** Creates a retained observation fixture with explicit freshness and immutable query times. */
    @ExperimentalTvheadendApi
    public constructor(
        channelRefreshTimes: MetadataRefreshTimes,
        epgRefreshTimes: MetadataRefreshTimes = MetadataRefreshTimes(),
        dvrRefreshTimes: MetadataRefreshTimes = MetadataRefreshTimes(),
        epgLastQueriedAt: Map<ChannelId, Instant> = emptyMap(),
        channelState: ChannelRepositoryState = ChannelRepositoryState.Empty,
        epgState: EpgRepositoryState = EpgRepositoryState.Empty,
        dvrState: DvrRepositoryState = DvrRepositoryState.Empty,
    ) : this(SessionObservation.create(
        channelRefreshTimes = channelRefreshTimes,
        epgRefreshTimes = epgRefreshTimes,
        dvrRefreshTimes = dvrRefreshTimes,
        epgLastQueriedAt = epgLastQueriedAt,
        channelState = channelState,
        epgState = epgState,
        dvrState = dvrState,
    ))

    private val mutableObservation = MutableStateFlow(initialObservation)

    /** The complete observation most recently published by this fake. */
    public val observation: StateFlow<SessionObservation> = mutableObservation.asStateFlow()

    /** Publishes one complete aggregate observation. */
    public fun publish(observation: SessionObservation) {
        mutableObservation.value = observation
    }

    /** Captures the exact capability carried by the fake's current aggregate publication. */
    public fun captureCurrentSession(): CurrentSessionObservation =
        checkNotNull(mutableObservation.value.currentSession) {
            "The fake session observation is not current"
        }

    /** Publishes one complete retired observation that carries no current-session capability. */
    public fun retire(observation: SessionObservation = SessionObservation.create()) {
        require(observation.currentSession == null) {
            "A retired session observation must not carry a current-session capability"
        }
        mutableObservation.value = observation
    }
}
