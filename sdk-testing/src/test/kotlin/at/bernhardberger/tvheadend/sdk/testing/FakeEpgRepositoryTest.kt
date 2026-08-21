package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EpgCoverage
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.EventId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import kotlin.time.Instant

internal class FakeEpgRepositoryTest {
    @Test
    fun `fake scripts only complete public repository states`() = runTest {
        val channelId = ChannelId(7)
        val event = EpgEvent.create(
            id = EventId(8),
            channelId = channelId,
            start = Instant.fromEpochSeconds(10),
            stop = Instant.fromEpochSeconds(20),
        )
        val coverage = EpgCoverage.create(channelId, event.start, event.stop)
        val snapshot = EpgSnapshot.create(listOf(event), listOf(coverage))
        val repository = FakeEpgRepository(EpgRepositoryState.Synchronizing(snapshot))

        assertSame(snapshot.events, repository.events.value)
        assertSame(event, repository.event(EventId(8)).first())
        assertEquals(listOf(event), repository.events(channelId).first())
        assertSame(coverage, repository.coverage(channelId).first())

        repository.setState(EpgRepositoryState.Current(EpgSnapshot.create()))

        assertEquals(emptyList<EpgEvent>(), repository.events.value)
        assertEquals(null, repository.event(EventId(8)).first())
        assertEquals(null, repository.coverage(channelId).first())
        assertEquals(EpgRepositoryState.Current(EpgSnapshot.create()), repository.state.value)
    }
}
