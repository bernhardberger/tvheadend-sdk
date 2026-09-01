package at.bernhardberger.tvheadend.sdk.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
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
    fun `search models validate filters copy results and redact private text`() {
        val request = EpgSearchRequest.create(
            query = "private-query",
            fullText = true,
            channelId = ChannelId(1),
            tagId = ChannelTagId(2),
            contentType = 3,
            language = "de",
            minimumDuration = 4.seconds,
            maximumDuration = 5.seconds,
        )
        val source = mutableListOf(event(id = 6, start = 10, stop = 20))
        val available = EpgSearchResult.Available.create(source)
        source.clear()
        val javaRequest = EpgSearchRequest.createFromSeconds(
            query = "private-java-query",
            fullText = true,
            channelId = 7,
            tagId = 8,
            contentType = 0xff,
            language = "eng",
            minimumDurationSeconds = 9,
            maximumDurationSeconds = Int.MAX_VALUE.toLong(),
        )

        assertEquals("private-query", request.query)
        assertTrue(request.fullText)
        assertEquals(ChannelId(1), request.channelId)
        assertEquals(ChannelTagId(2), request.tagId)
        assertEquals(3L, request.contentType)
        assertEquals("de", request.language)
        assertEquals(4.seconds, request.minimumDuration)
        assertEquals(5.seconds, request.maximumDuration)
        assertEquals(ChannelId(7), javaRequest.channelId)
        assertEquals(ChannelTagId(8), javaRequest.tagId)
        assertEquals(0xffL, javaRequest.contentType)
        assertEquals(9.seconds, javaRequest.minimumDuration)
        assertEquals(Int.MAX_VALUE.toLong().seconds, javaRequest.maximumDuration)
        assertEquals(listOf(6L), available.events.map { it.id.value })
        assertThrows(UnsupportedOperationException::class.java) {
            (available.events as MutableList<EpgEvent>).clear()
        }
        assertFalse(
            listOf(request, javaRequest, available).joinToString().contains("private"),
            "EPG search rendering exposed query or result metadata",
        )

        assertEquals("", EpgSearchRequest.create("").query)
        assertEquals(" ", EpgSearchRequest.create(" ").query)
        assertThrows(IllegalArgumentException::class.java) {
            EpgSearchRequest.create("query", contentType = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EpgSearchRequest.create("query", contentType = 0x100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EpgSearchRequest.create("query", minimumDuration = 1.nanoseconds)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EpgSearchRequest.create(
                "query",
                maximumDuration = (Int.MAX_VALUE.toLong() + 1).seconds,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EpgSearchRequest.create(
                query = "query",
                minimumDuration = 2.seconds,
                maximumDuration = 1.seconds,
            )
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
