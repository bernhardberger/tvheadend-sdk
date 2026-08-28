package at.bernhardberger.tvheadend.sdk.core

import kotlin.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class SessionObservationTest {
    @Test
    fun `current capability requires ready channel EPG and DVR state`() {
        val current = observation()
        assertTrue(current.currentSession != null)
        assertFalse(current.toString().contains("private"))
        assertFalse(current.currentSession.toString().contains("private"))

        val synchronizing = SessionObservation.create(
            sessionState = current.sessionState,
            channelState = ChannelRepositoryState.Synchronizing(catalog()),
            epgState = current.epgState,
            dvrState = current.dvrState,
        )
        assertNull(synchronizing.currentSession)
    }

    @Test
    fun `channel selectors preserve catalog order and exact identity`() {
        val observation = observation()

        assertSame(observation.channel(ChannelId(2)), observation.channels(setOf(ChannelId(2))).single())
        assertEquals(
            listOf(ChannelId(1), ChannelId(2)),
            observation.channels(setOf(ChannelId(2), ChannelId(1))).map(Channel::id),
        )
        assertNull(observation.channel(ChannelId(99)))
        assertThrows(UnsupportedOperationException::class.java) {
            (observation.channels(setOf(ChannelId(1))) as MutableList<Channel>).clear()
        }
    }

    @Test
    fun `event at and next event use exact half open boundaries`() {
        val observation = observation()
        val channelId = ChannelId(1)

        assertNull(observation.eventAt(channelId, instant(9)))
        assertEquals(EventId(10), observation.nextEvent(channelId, instant(9))?.id)
        assertEquals(EventId(10), observation.eventAt(channelId, instant(10))?.id)
        assertEquals(EventId(11), observation.nextEvent(channelId, instant(10))?.id)
        assertEquals(EventId(11), observation.eventAt(channelId, instant(20))?.id)
        assertEquals(EventId(12), observation.nextEvent(channelId, instant(20))?.id)
        assertNull(observation.eventAt(channelId, instant(40)))
        assertNull(observation.nextEvent(channelId, instant(40)))
        assertEquals(instant(30), observation.coverage(channelId)?.coveredTo)
    }

    @Test
    fun `EPG and DVR relationship selectors stay within one observation`() {
        val observation = observation()

        assertEquals(DvrEntryId(100), observation.dvrEntryForEvent(EventId(10))?.id)
        assertEquals(EventId(10), observation.epgEventForDvrEntry(DvrEntryId(100))?.id)
        assertEquals(DvrEntryId(101), observation.dvrEntryForEvent(EventId(11))?.id)
        assertEquals(EventId(11), observation.epgEventForDvrEntry(DvrEntryId(101))?.id)
        assertNull(observation.dvrEntryForEvent(EventId(12)))
    }

    @Test
    fun `store collectors receive complete current and retired observations`() = runTest {
        val store = SessionObservationStore()
        val source = observation()
        val firstGeneration = Any()
        val observed = mutableListOf<SessionObservation>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.observation.take(6).toList(observed)
        }

        store.publishMetadata(
            channelState = source.channelState,
            epgState = source.epgState,
            dvrState = source.dvrState,
            configurationsState = source.dvrConfigurationsState,
            diskSpaceState = source.dvrDiskSpaceState,
        )
        runCurrent()
        store.publishSessionState(source.sessionState, RecordingProgressCapability.UNKNOWN, firstGeneration)
        runCurrent()
        val firstCurrent = requireNotNull(store.observation.value.currentSession)

        store.publishSessionState(source.sessionState, RecordingProgressCapability.SUPPORTED, firstGeneration)
        runCurrent()
        store.publishMetadata(
            channelState = source.channelState,
            epgState = source.epgState,
            dvrState = source.dvrState,
            configurationsState = DvrConfigurationsState.Current.create(emptyList()),
            diskSpaceState = source.dvrDiskSpaceState,
        )
        runCurrent()
        store.publishMetadata(
            channelState = ChannelRepositoryState.Stale(
                (source.channelState as ChannelRepositoryState.Current).catalog,
            ),
            epgState = EpgRepositoryState.Stale(
                (source.epgState as EpgRepositoryState.Current).snapshot,
            ),
            dvrState = DvrRepositoryState.Stale(
                (source.dvrState as DvrRepositoryState.Current).snapshot,
            ),
            configurationsState = DvrConfigurationsState.Stale.create(emptyList()),
            diskSpaceState = source.dvrDiskSpaceState,
        )
        runCurrent()
        collector.join()

        assertEquals(6, observed.size)
        assertNull(observed[1].currentSession)
        assertSame(firstCurrent, observed[2].currentSession)
        assertSame(firstCurrent, observed[3].currentSession)
        assertSame(firstCurrent, observed[4].currentSession)
        val retired = observed[5]
        assertNull(retired.currentSession)
        assertTrue(retired.channelState is ChannelRepositoryState.Stale)
        assertTrue(retired.epgState is EpgRepositoryState.Stale)
        assertTrue(retired.dvrState is DvrRepositoryState.Stale)

        store.publishSessionState(
            SessionState.Unavailable(SessionFailure.TransportUnavailable),
            RecordingProgressCapability.UNKNOWN,
            generation = null,
        )
        store.publishMetadata(
            channelState = source.channelState,
            epgState = source.epgState,
            dvrState = source.dvrState,
            configurationsState = source.dvrConfigurationsState,
            diskSpaceState = source.dvrDiskSpaceState,
        )
        assertNull(store.observation.value.currentSession)
        store.publishSessionState(source.sessionState, RecordingProgressCapability.SUPPORTED, Any())
        assertNotSame(firstCurrent, store.observation.value.currentSession)
    }

    private fun observation(): SessionObservation = SessionObservation.create(
        sessionState = SessionState.Ready(
            ServerCapabilities.create(CapabilityAccess.ALLOWED, CapabilityAccess.ALLOWED),
        ),
        channelState = ChannelRepositoryState.Current(catalog()),
        epgState = EpgRepositoryState.Current(
            EpgSnapshot.create(
                events = listOf(
                    event(10, 10, 20, next = 11, dvrEntry = 100),
                    event(11, 20, 30, next = 12),
                    event(12, 30, 40),
                ),
                coverages = listOf(EpgCoverage.create(ChannelId(1), instant(10), instant(30))),
            ),
        ),
        dvrState = DvrRepositoryState.Current(
            DvrSnapshot.create(
                entries = listOf(
                    DvrEntry.create(DvrEntryId(100), eventId = EventId(99)),
                    DvrEntry.create(DvrEntryId(101), eventId = EventId(11)),
                ),
            ),
        ),
    )

    private fun catalog(): ChannelCatalog = ChannelCatalog.create(
        channels = listOf(
            Channel.create(ChannelId(1), name = "private-one"),
            Channel.create(ChannelId(2), name = "private-two"),
        ),
    )

    private fun event(
        id: Long,
        start: Long,
        stop: Long,
        next: Long? = null,
        dvrEntry: Long? = null,
    ): EpgEvent = EpgEvent.create(
        id = EventId(id),
        channelId = ChannelId(1),
        start = instant(start),
        stop = instant(stop),
        nextEventId = next?.let(::EventId),
        dvrEntryId = dvrEntry?.let(::DvrEntryId),
    )

    private fun instant(seconds: Long): Instant = Instant.fromEpochSeconds(seconds)
}
