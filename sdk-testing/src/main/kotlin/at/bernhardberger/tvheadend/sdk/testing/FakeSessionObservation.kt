package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Mutable aggregate session observation source for application and SDK consumer tests. */
public class FakeSessionObservation(
    initialObservation: SessionObservation = SessionObservation.create(),
) {
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
