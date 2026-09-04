package at.bernhardberger.tvheadend.sdk.core

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class SessionObservationTest {
    @Test
    fun `channel display projection covers every repository state`() {
        val retained = catalog()
        val cases = listOf(
            Triple(ChannelRepositoryState.Empty, null, RetainedMetadataAuthority.ABSENT),
            Triple(
                ChannelRepositoryState.Synchronizing(null),
                null,
                RetainedMetadataAuthority.SYNCHRONIZING_WITHOUT_RETAINED_DATA,
            ),
            Triple(
                ChannelRepositoryState.Synchronizing(retained),
                retained,
                RetainedMetadataAuthority.SYNCHRONIZING_WITH_RETAINED_DATA,
            ),
            Triple(ChannelRepositoryState.Current(retained), retained, RetainedMetadataAuthority.CURRENT),
            Triple(ChannelRepositoryState.Stale(retained), retained, RetainedMetadataAuthority.STALE),
        )

        cases.forEach { (state, expectedCatalog, expectedAuthority) ->
            assertSame(expectedCatalog, state.channelCatalogForDisplay)
            assertEquals(expectedAuthority, state.channelCatalogAuthority)
            val observation = SessionObservation.create(channelState = state)
            assertSame(expectedCatalog, observation.channelCatalogForDisplay)
            assertEquals(expectedAuthority, observation.channelCatalogAuthority)
        }
    }

    @Test
    fun `EPG display projection covers every repository state`() {
        val retained = EpgSnapshot.create()
        val cases = listOf(
            Triple(EpgRepositoryState.Empty, null, RetainedMetadataAuthority.ABSENT),
            Triple(
                EpgRepositoryState.Synchronizing(null),
                null,
                RetainedMetadataAuthority.SYNCHRONIZING_WITHOUT_RETAINED_DATA,
            ),
            Triple(
                EpgRepositoryState.Synchronizing(retained),
                retained,
                RetainedMetadataAuthority.SYNCHRONIZING_WITH_RETAINED_DATA,
            ),
            Triple(EpgRepositoryState.Current(retained), retained, RetainedMetadataAuthority.CURRENT),
            Triple(EpgRepositoryState.Stale(retained), retained, RetainedMetadataAuthority.STALE),
        )

        cases.forEach { (state, expectedSnapshot, expectedAuthority) ->
            assertSame(expectedSnapshot, state.epgSnapshotForDisplay)
            assertEquals(expectedAuthority, state.epgSnapshotAuthority)
            val observation = SessionObservation.create(epgState = state)
            assertSame(expectedSnapshot, observation.epgSnapshotForDisplay)
            assertEquals(expectedAuthority, observation.epgSnapshotAuthority)
        }
    }

    @Test
    fun `DVR display projection covers every repository state`() {
        val retained = DvrSnapshot.create()
        val cases = listOf(
            Triple(DvrRepositoryState.Empty, null, RetainedMetadataAuthority.ABSENT),
            Triple(
                DvrRepositoryState.Synchronizing(null),
                null,
                RetainedMetadataAuthority.SYNCHRONIZING_WITHOUT_RETAINED_DATA,
            ),
            Triple(
                DvrRepositoryState.Synchronizing(retained),
                retained,
                RetainedMetadataAuthority.SYNCHRONIZING_WITH_RETAINED_DATA,
            ),
            Triple(DvrRepositoryState.Current(retained), retained, RetainedMetadataAuthority.CURRENT),
            Triple(DvrRepositoryState.Stale(retained), retained, RetainedMetadataAuthority.STALE),
        )

        cases.forEach { (state, expectedSnapshot, expectedAuthority) ->
            assertSame(expectedSnapshot, state.dvrSnapshotForDisplay)
            assertEquals(expectedAuthority, state.dvrSnapshotAuthority)
            val observation = SessionObservation.create(dvrState = state)
            assertSame(expectedSnapshot, observation.dvrSnapshotForDisplay)
            assertEquals(expectedAuthority, observation.dvrSnapshotAuthority)
        }
    }

    @Test
    fun `repository authority stays distinct from current session proof`() {
        val source = observation()
        val withoutProof = SessionObservation.create(
            sessionState = SessionState.Disconnected,
            channelState = source.channelState,
            epgState = source.epgState,
            dvrState = source.dvrState,
        )

        assertNull(withoutProof.currentSession)
        assertEquals(RetainedMetadataAuthority.CURRENT, withoutProof.channelCatalogAuthority)
        assertEquals(RetainedMetadataAuthority.CURRENT, withoutProof.epgSnapshotAuthority)
        assertEquals(RetainedMetadataAuthority.CURRENT, withoutProof.dvrSnapshotAuthority)
    }

    @Test
    fun `point selectors use retained display projections`() {
        val source = observation()
        val retained = SessionObservation.create(
            channelState = ChannelRepositoryState.Synchronizing(source.channelCatalogForDisplay),
            epgState = EpgRepositoryState.Stale(requireNotNull(source.epgSnapshotForDisplay)),
            dvrState = DvrRepositoryState.Synchronizing(source.dvrSnapshotForDisplay),
        )

        assertSame(source.channel(ChannelId(1)), retained.channel(ChannelId(1)))
        assertSame(source.event(EventId(10)), retained.event(EventId(10)))
        assertSame(source.dvrEntry(DvrEntryId(100)), retained.dvrEntry(DvrEntryId(100)))
        assertSame(source.dvrEntryForEvent(EventId(10)), retained.dvrEntryForEvent(EventId(10)))
        assertSame(source.epgEventForDvrEntry(DvrEntryId(100)), retained.epgEventForDvrEntry(DvrEntryId(100)))
    }

    @Test
    fun `current capability requires ready channel EPG and DVR state`() {
        val current = observation()
        assertTrue(current.currentSession != null)
        assertFalse(current.toString().contains("private"))
        assertFalse(current.currentSession.toString().contains("private"))
        assertTrue(
            CurrentSessionObservation::class.java.declaredConstructors.none {
                Modifier.isPublic(it.modifiers) && !it.isSynthetic
            },
        )
        assertTrue(
            SessionGenerationIdentity::class.java.declaredConstructors.none {
                Modifier.isPublic(it.modifiers) && !it.isSynthetic
            },
        )
        assertNotEquals(
            current.currentSession,
            observation().currentSession,
            "Current proofs must retain identity equality",
        )

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
        assertEquals("SessionGenerationIdentity(<redacted>)", firstCurrent.generationIdentity.toString())

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

        store.publishMetadata(
            channelState = source.channelState,
            epgState = source.epgState,
            dvrState = source.dvrState,
            configurationsState = source.dvrConfigurationsState,
            diskSpaceState = source.dvrDiskSpaceState,
        )
        val republishedCurrent = requireNotNull(store.observation.value.currentSession)
        assertNotSame(firstCurrent, republishedCurrent)
        assertSame(firstCurrent.generationIdentity, republishedCurrent.generationIdentity)

        store.publishSessionState(source.sessionState, RecordingProgressCapability.SUPPORTED, Any())
        val directlyReplaced = requireNotNull(store.observation.value.currentSession)
        assertNotSame(republishedCurrent, directlyReplaced)
        assertNotSame(republishedCurrent.generationIdentity, directlyReplaced.generationIdentity)

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
        val replacementCurrent = requireNotNull(store.observation.value.currentSession)
        assertNotSame(firstCurrent, replacementCurrent)
        assertNotSame(firstCurrent.generationIdentity, replacementCurrent.generationIdentity)
    }

    @Test
    fun `current inspection and replacement wait preserve proof identity and cancellation`() = runTest {
        val store = SessionObservationStore()
        val source = observation()
        val session = sessionFor(store)
        val generation = Any()
        val initialWait = async(start = CoroutineStart.UNDISPATCHED) {
            session.awaitCurrentSession()
        }

        assertFalse(initialWait.isCompleted)
        store.publishMetadata(
            channelState = source.channelState,
            epgState = source.epgState,
            dvrState = source.dvrState,
            configurationsState = source.dvrConfigurationsState,
            diskSpaceState = source.dvrDiskSpaceState,
        )
        store.publishSessionState(source.sessionState, RecordingProgressCapability.UNKNOWN, generation)
        val first = initialWait.await()
        assertTrue(session.isCurrent(first))

        val replacementWait = async(start = CoroutineStart.UNDISPATCHED) {
            session.awaitCurrentSession(replaced = first)
        }
        store.publishMetadata(
            channelState = ChannelRepositoryState.Stale(
                (source.channelState as ChannelRepositoryState.Current).catalog,
            ),
            epgState = source.epgState,
            dvrState = source.dvrState,
            configurationsState = source.dvrConfigurationsState,
            diskSpaceState = source.dvrDiskSpaceState,
        )
        runCurrent()
        assertFalse(replacementWait.isCompleted)
        assertFalse(session.isCurrent(first))
        store.publishMetadata(
            channelState = source.channelState,
            epgState = source.epgState,
            dvrState = source.dvrState,
            configurationsState = source.dvrConfigurationsState,
            diskSpaceState = source.dvrDiskSpaceState,
        )
        val replacement = replacementWait.await()
        assertNotSame(first, replacement)
        assertSame(first.generationIdentity, replacement.generationIdentity)
        assertTrue(session.isCurrent(replacement))

        val cancellation = CancellationException("fixed wait cancellation")
        val cancelledWait = async(start = CoroutineStart.UNDISPATCHED) {
            session.awaitCurrentSession(replaced = replacement)
        }
        cancelledWait.cancel(cancellation)
        var caught: CancellationException? = null
        try {
            cancelledWait.await()
        } catch (failure: CancellationException) {
            caught = failure
        }
        val propagated = requireNotNull(caught)
        assertTrue(propagated === cancellation || propagated.cause === cancellation)
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

@Suppress("UNCHECKED_CAST")
private fun sessionFor(store: SessionObservationStore): TvheadendSession = Proxy.newProxyInstance(
    TvheadendSession::class.java.classLoader,
    arrayOf(TvheadendSession::class.java),
) { proxy, method, arguments ->
    when {
        method.name == "getObservation" -> store.observation
        method.isDefault -> InvocationHandler.invokeDefault(proxy, method, *(arguments ?: emptyArray()))
        else -> error("Unexpected session call")
    }
} as TvheadendSession
