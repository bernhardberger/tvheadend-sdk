@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrEntry
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.metadata.DvrReducer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Instant

internal class DvrEventRankingTest {
    @Test
    fun `other active entries preserve link and stop tie breaks within each state tier`() {
        val scheduledOlder = entry(1, DvrEntryState.SCHEDULED, stop = 20, owner = "test-account")
        val scheduledNewer = entry(2, DvrEntryState.SCHEDULED, stop = 30, owner = "test-account")
        val recordingOlder = entry(3, DvrEntryState.RECORDING, stop = 20, owner = "test-account")
        val recordingNewer = entry(4, DvrEntryState.RECORDING, stop = 30, owner = "test-account")
        val cases = listOf(
            recordingOlder.id to listOf(recordingOlder, recordingNewer, scheduledNewer, scheduledOlder),
            scheduledOlder.id to listOf(recordingNewer, recordingOlder, scheduledOlder, scheduledNewer),
            null to listOf(recordingNewer, recordingOlder, scheduledNewer, scheduledOlder),
        )
        val entries = listOf(scheduledOlder, scheduledNewer, recordingOlder, recordingNewer)
        for ((link, expected) in cases) {
            for (ordered in listOf(entries, entries.reversed())) {
                val observed = observation(ordered, link = link, username = "test-account")
                assertSame(expected.first(), observed.dvrEntryForEvent(EventId(10)))
                val result = requireNotNull(observed.dvrEntriesForEvent(EventId(10)))
                assertSame(expected.first(), result.selected)
                assertEquals(expected.drop(1), result.otherActive)
            }
        }
    }

    @Test
    fun `ranked result includes only remaining active matches in rank order`() {
        val own = entry(9, DvrEntryState.COMPLETED, owner = "test-account")
        val recording = entry(2, DvrEntryState.RECORDING)
        val recordingError = entry(3, DvrEntryState.RECORDING_ERROR)
        val scheduled = entry(1, DvrEntryState.SCHEDULED)
        val completed = entry(4, DvrEntryState.COMPLETED)
        val unlinked = DvrEntry.create(DvrEntryId(5), state = DvrEntryState.RECORDING)
        val entries = listOf(scheduled, recordingError, completed, own, recording, unlinked)
        for (username in listOf(null, "test-account")) {
            val observed = observation(entries, username = username)
            val ranked = requireNotNull(observed.dvrEntriesForEvent(EventId(10)))
            assertSame(observed.dvrEntryForEvent(EventId(10)), ranked.selected)
            assertEquals(
                if (username == null) listOf(recordingError, scheduled) else listOf(recording, recordingError, scheduled),
                ranked.otherActive,
            )
        }
    }

    @Test
    fun `ranked result is null for unknown duplicate or unmatched events and supports a single finished match`() {
        val duplicate = EpgEvent.create(
            EventId(10), start = Instant.fromEpochSeconds(10), stop = Instant.fromEpochSeconds(20),
        )
        val finished = entry(1, DvrEntryState.COMPLETED)
        val observations = listOf(
            SessionObservation.create(),
            observation(emptyList()),
            SessionObservation.create(
                epgState = EpgRepositoryState.Current(EpgSnapshot.create(listOf(duplicate, duplicate))),
                dvrState = DvrRepositoryState.Current(DvrSnapshot.create(listOf(finished))),
            ),
        )
        for (observed in observations) {
            assertNull(observed.dvrEntryForEvent(EventId(10)))
            assertNull(observed.dvrEntriesForEvent(EventId(10)))
        }
        val result = requireNotNull(observation(listOf(finished)).dvrEntriesForEvent(EventId(10)))
        assertSame(finished, result.selected)
        assertEquals(emptyList<DvrEntry>(), result.otherActive)
    }

    @Test
    fun `ranked values defensively copy active entries compare by value and redact rendering`() {
        val selected = entry(1, DvrEntryState.RECORDING, owner = "test-account")
        val other = entry(2, DvrEntryState.SCHEDULED, owner = "other-account")
        val source = mutableListOf(other)
        val value = DvrEventEntries.create(selected, source)
        source.clear()
        assertEquals(listOf(other), value.otherActive)
        assertThrows(UnsupportedOperationException::class.java) { (value.otherActive as MutableList<DvrEntry>).clear() }
        val equivalent = requireNotNull(observation(listOf(other, selected)).dvrEntriesForEvent(EventId(10)))
        assertEquals(value, equivalent)
        assertEquals(value.hashCode(), equivalent.hashCode())
        assertNotEquals(value, DvrEventEntries.create(selected, emptyList()))
        assertNotEquals(value, DvrEventEntries.create(other, listOf(selected)))
        assertEquals("DvrEventEntries(<redacted>)", value.toString())
    }

    @Test
    fun `record stop record selects the new recording over the linked completed entry`() {
        val completed = entry(1, DvrEntryState.COMPLETED)
        val recording = entry(2, DvrEntryState.RECORDING)
        assertSame(recording, observation(listOf(completed, recording), link = completed.id).dvrEntryForEvent(EventId(10)))
    }

    @Test
    fun `recording and recording error outrank scheduled and finished entries`() {
        val scheduled = entry(1, DvrEntryState.SCHEDULED)
        val finished = entry(2, DvrEntryState.COMPLETED)
        for (state in listOf(DvrEntryState.RECORDING, DvrEntryState.RECORDING_ERROR)) {
            val recording = entry(3, state)
            assertSame(recording, observation(listOf(scheduled, finished, recording), link = scheduled.id)
                .dvrEntryForEvent(EventId(10)))
        }
        assertSame(scheduled, observation(listOf(finished, scheduled), link = finished.id).dvrEntryForEvent(EventId(10)))
    }

    @Test
    fun `own scheduled entry outranks other recording while anonymous and unknown owners receive no preference`() {
        val own = entry(1, DvrEntryState.SCHEDULED, owner = "test-account")
        val other = entry(2, DvrEntryState.RECORDING, owner = "other-account")
        val unknown = entry(3, DvrEntryState.SCHEDULED)
        assertSame(own, observation(listOf(other, own), username = "test-account").dvrEntryForEvent(EventId(10)))
        assertSame(other, observation(listOf(other, own)).dvrEntryForEvent(EventId(10)))
        assertSame(other, observation(listOf(unknown, other), username = "test-account").dvrEntryForEvent(EventId(10)))
        assertSame(other, observation(listOf(own, other), username = "TEST-ACCOUNT").dvrEntryForEvent(EventId(10)))
    }

    @Test
    fun `link then latest stop then lower ID resolve finished ties independent of input order`() {
        val older = entry(1, DvrEntryState.COMPLETED, stop = 20)
        val newer = entry(2, DvrEntryState.COMPLETED, stop = 30)
        val sameStop = entry(3, DvrEntryState.COMPLETED, stop = 30)
        val missingStop = entry(4, DvrEntryState.COMPLETED, stop = null)
        val entries = listOf(missingStop, sameStop, newer, older)
        for (ordered in listOf(entries, entries.reversed())) {
            assertSame(older, observation(ordered, link = older.id).dvrEntryForEvent(EventId(10)))
            assertSame(newer, observation(ordered).dvrEntryForEvent(EventId(10)))
        }
    }

    @Test
    fun `username is excluded from observation equality hashing and rendering`() {
        val entries = listOf(entry(1, DvrEntryState.SCHEDULED, owner = "test-account"))
        val anonymous = observation(entries)
        val authenticated = observation(entries, username = "test-account")
        assertEquals(anonymous, authenticated)
        assertEquals(anonymous.hashCode(), authenticated.hashCode())
        assertEquals("SessionObservation(<redacted>)", authenticated.toString())
        assertNull(authenticated.dvrEntryForEvent(EventId(99)))
    }

    @Test
    fun `active ties prefer event link then latest stop independent of input order`() {
        for (state in listOf(DvrEntryState.SCHEDULED, DvrEntryState.RECORDING)) {
            val older = entry(1, state, stop = 20, owner = "test-account")
            val newer = entry(2, state, stop = 30, owner = "test-account")
            for (entries in listOf(listOf(older, newer), listOf(newer, older))) {
                val linked = observation(entries, link = older.id, username = "test-account")
                val unlinked = observation(entries, username = "test-account")
                assertSame(older, linked.dvrEntryForEvent(EventId(10)))
                assertSame(newer, unlinked.dvrEntryForEvent(EventId(10)))
                val linkedResult = requireNotNull(linked.dvrEntriesForEvent(EventId(10)))
                assertSame(older, linkedResult.selected)
                assertEquals(listOf(newer), linkedResult.otherActive)
                val unlinkedResult = requireNotNull(unlinked.dvrEntriesForEvent(EventId(10)))
                assertSame(newer, unlinkedResult.selected)
                assertEquals(listOf(older), unlinkedResult.otherActive)
            }
        }
    }

    @Test
    fun `duplicate matched DVR ids are ambiguous even below the highest ranked entry`() {
        val recording = entry(1, DvrEntryState.RECORDING)
        val finished = entry(2, DvrEntryState.COMPLETED)
        val conflicting = entry(2, DvrEntryState.SCHEDULED)
        for (duplicate in listOf(finished, conflicting)) {
            val entries = listOf(recording, finished, duplicate)
            for (ordered in listOf(entries, entries.reversed())) {
                val observed = observation(ordered, link = recording.id)
                assertNull(observed.dvrEntry(DvrEntryId(2)))
                assertNull(observed.dvrEntryForEvent(EventId(10)))
                assertNull(observed.dvrEntriesForEvent(EventId(10)))
            }
        }
    }

    private fun entry(id: Long, state: DvrEntryState, stop: Long? = 20, owner: String? = null): DvrEntry {
        val reducer = DvrReducer()
        reducer.accept(MetadataEvent.DvrEntryAdded(GatewayGeneration(), GatewayDvrEntry(
            id = DvrEntryId(id), eventId = at.bernhardberger.tvheadend.sdk.core.gateway.EventId(10),
            state = state, stop = stop?.let(Instant::fromEpochSeconds), owner = owner,
        )))
        return reducer.snapshot().entries.single()
    }

    private fun observation(entries: List<DvrEntry>, link: DvrEntryId? = null, username: String? = null): SessionObservation {
        val store = SessionObservationStore()
        store.selectAccount(username)
        store.publishObservation(SessionObservation.create(
            epgState = EpgRepositoryState.Current(EpgSnapshot.create(listOf(EpgEvent.create(
                id = EventId(10), dvrEntryId = link,
                start = Instant.fromEpochSeconds(10), stop = Instant.fromEpochSeconds(20),
            )))),
            dvrState = DvrRepositoryState.Current(DvrSnapshot.create(entries)),
        ), generation = null)
        return store.observation.value
    }
}
