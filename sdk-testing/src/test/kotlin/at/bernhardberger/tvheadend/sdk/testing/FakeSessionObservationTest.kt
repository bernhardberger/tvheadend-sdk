@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.MetadataRefreshTimes
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import kotlin.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals

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
    fun `freshness fixture copies query times and defaults remain unknown`() {
        val now = Instant.fromEpochSeconds(123)
        val times = MetadataRefreshTimes(now, now)
        val queries = mutableMapOf(ChannelId(1) to now)
        val fake = FakeSessionObservation(times, times, times, queries)
        queries.clear()
        val observed = fake.observation.value
        assertEquals(times, observed.channelRefreshTimes)
        assertEquals(times, observed.epgRefreshTimes)
        assertEquals(times, observed.dvrRefreshTimes)
        assertEquals(mapOf(ChannelId(1) to now), observed.epgLastQueriedAt)
        assertThrows(UnsupportedOperationException::class.java) {
            (observed.epgLastQueriedAt as MutableMap).clear()
        }
        assertEquals(MetadataRefreshTimes(), FakeSessionObservation().observation.value.epgRefreshTimes)
        assertEquals(SessionObservation.create(), SessionObservation.create(channelRefreshTimes = MetadataRefreshTimes()))
    }

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
        assertSame(current.currentSession, fake.captureCurrentSession())
        assertThrows(IllegalArgumentException::class.java) { fake.retire(current) }

        fake.retire(retired)
        assertSame(retired, fake.observation.value)
        assertThrows(IllegalStateException::class.java) { fake.captureCurrentSession() }
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
