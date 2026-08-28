package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSnapshot
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionState
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class FakeSessionObservationTest {
    @Test
    fun `publishes and retires complete aggregate observations`() {
        val fake = FakeSessionObservation()
        val current = currentObservation()
        val retired = SessionObservation.create(
            sessionState = SessionState.Disconnected,
            channelState = ChannelRepositoryState.Stale(ChannelCatalog.create()),
            epgState = EpgRepositoryState.Stale(EpgSnapshot.create()),
            dvrState = DvrRepositoryState.Stale(DvrSnapshot.create()),
        )

        fake.publish(current)
        assertSame(current, fake.observation.value)
        assertThrows(IllegalArgumentException::class.java) { fake.retire(current) }

        fake.retire(retired)
        assertSame(retired, fake.observation.value)
    }

    private fun currentObservation(): SessionObservation = SessionObservation.create(
        sessionState = SessionState.Ready(
            ServerCapabilities.create(CapabilityAccess.ALLOWED, CapabilityAccess.UNKNOWN),
        ),
        channelState = ChannelRepositoryState.Current(ChannelCatalog.create()),
        epgState = EpgRepositoryState.Current(EpgSnapshot.create()),
        dvrState = DvrRepositoryState.Current(DvrSnapshot.create()),
    )
}
