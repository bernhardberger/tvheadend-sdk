package at.bernhardberger.tvheadend.sdk.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

internal class EpgRepositoryTest {
    @Test
    fun `models validate wire domains timing and immutable collections`() {
        val categories = mutableListOf("private-category")
        val keywords = mutableListOf("private-keyword")
        val event = EpgEvent.create(
            id = EventId(0xffff_ffffL),
            channelId = ChannelId(1),
            start = instant(10),
            stop = instant(20),
            title = "private-title",
            categories = categories,
            keywords = keywords,
            contentType = 2,
            rating = EpgRating(3, "private-rating", "private-icon", "private-authority", "GB", 4),
            copyrightYear = 2026,
            episode = EpgEpisode(
                id = EpgEpisodeId(5),
                seriesLinkId = EpgSeriesLinkId(6),
                seasonNumber = 1,
                seasonCount = 2,
                episodeNumber = 3,
                episodeCount = 4,
                partNumber = 5,
                partCount = 6,
                onscreen = "private-onscreen",
            ),
            dvrEntryId = DvrEntryId(7),
        )
        categories.clear()
        keywords.clear()

        assertEquals(listOf("private-category"), event.categories)
        assertEquals(listOf("private-keyword"), event.keywords)
        assertThrows(UnsupportedOperationException::class.java) {
            (event.categories as MutableList<String>).clear()
        }
        assertThrows(IllegalArgumentException::class.java) { EpgEpisodeId(-1) }
        assertThrows(IllegalArgumentException::class.java) { EpgSeriesLinkId(0x1_0000_0000L) }
        assertThrows(IllegalArgumentException::class.java) { DvrEntryId(-1) }
        assertThrows(IllegalArgumentException::class.java) {
            EpgRating(age = -1, label = null, icon = null, authority = null, country = null, stars = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EpgEpisode(null, null, null, null, null, null, null, 0x1_0000_0000L, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EpgEvent.create(EventId(1), start = instant(20), stop = instant(19))
        }
        assertFalse(
            listOf(event, event.rating, event.episode).joinToString().contains("private"),
            "EPG model rendering exposed programme metadata",
        )
    }

    @Test
    fun `coverage separates empty actual queried and known horizons`() {
        val empty = EpgCoverage.empty(ChannelId(1))
        assertTrue(empty.isEmpty)
        assertTrue(empty.coveredFrom > empty.coveredTo)
        assertEquals(null, empty.knownTo)

        val knownEmpty = EpgCoverage.empty(ChannelId(1), queriedTo = instant(30))
        assertTrue(knownEmpty.isEmpty)
        assertEquals(instant(30), knownEmpty.knownTo)

        val actualAhead = EpgCoverage.create(
            channelId = ChannelId(1),
            coveredFrom = instant(10),
            coveredTo = instant(40),
            queriedTo = instant(30),
        )
        assertFalse(actualAhead.isEmpty)
        assertEquals(instant(40), actualAhead.knownTo)

        val queryAhead = EpgCoverage.create(
            channelId = ChannelId(1),
            coveredFrom = instant(10),
            coveredTo = instant(20),
            queriedTo = instant(30),
        )
        assertEquals(instant(30), queryAhead.knownTo)
        assertThrows(IllegalArgumentException::class.java) {
            EpgCoverage.create(ChannelId(1), instant(20), instant(10))
        }
    }

    @Test
    fun `all projections derive from one freshness state`() = runTest {
        val repository = TestEpgRepository()
        val first = event(1, 10, 20, channelId = 7)
        val second = event(2, 20, 30, channelId = 8)
        val coverage = EpgCoverage.create(ChannelId(7), first.start, first.stop)
        val snapshot = EpgSnapshot.create(listOf(first, second), listOf(coverage))

        assertEquals(EpgRepositoryState.Empty, repository.state.value)
        assertEquals(emptyList<EpgEvent>(), repository.events.value)
        assertEquals(null, repository.event(EventId(1)).first())

        repository.set(EpgRepositoryState.Synchronizing(snapshot))
        assertSame(snapshot.events, repository.events.value)
        assertSame(first, repository.event(EventId(1)).first())
        assertEquals(listOf(first), repository.events(ChannelId(7)).first())
        assertSame(coverage, repository.coverage(ChannelId(7)).first())
        assertEquals(null, repository.coverage(ChannelId(9)).first())

        repository.set(EpgRepositoryState.Current(EpgSnapshot.create()))
        assertEquals(emptyList<EpgEvent>(), repository.events.value)
        assertEquals(null, repository.event(EventId(1)).first())
    }

    private class TestEpgRepository : StateBackedEpgRepository() {
        private val mutableState = MutableStateFlow<EpgRepositoryState>(EpgRepositoryState.Empty)
        override val state: StateFlow<EpgRepositoryState> = mutableState.asStateFlow()

        fun set(state: EpgRepositoryState) {
            mutableState.value = state
        }
    }

    private fun event(id: Long, start: Long, stop: Long, channelId: Long? = null): EpgEvent =
        EpgEvent.create(
            id = EventId(id),
            channelId = channelId?.let(::ChannelId),
            start = instant(start),
            stop = instant(stop),
        )

    private fun instant(seconds: Long): Instant = Instant.fromEpochSeconds(seconds)
}
