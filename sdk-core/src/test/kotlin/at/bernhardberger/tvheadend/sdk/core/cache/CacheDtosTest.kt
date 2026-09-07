package at.bernhardberger.tvheadend.sdk.core.cache

import at.bernhardberger.tvheadend.sdk.core.Channel
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.ChannelService
import at.bernhardberger.tvheadend.sdk.core.ChannelTag
import at.bernhardberger.tvheadend.sdk.core.ChannelTagId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.EpgCoverage
import at.bernhardberger.tvheadend.sdk.core.EpgEpisode
import at.bernhardberger.tvheadend.sdk.core.EpgEpisodeId
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EpgRating
import at.bernhardberger.tvheadend.sdk.core.EpgSeriesLinkId
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.EventId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

internal class CacheDtosTest {
    @Test
    fun `channel catalog round trips through the DTO with every nullable field set and null`() {
        val serviceWithConditionalAccess = ChannelService(
            name = "Service One",
            type = "SDTV",
            content = 1L,
            conditionalAccessId = 7L,
            conditionalAccessName = "OSCam",
            providerName = "Provider",
        )
        val serviceWithoutConditionalAccess = ChannelService(
            name = "Service Two",
            type = "HDTV",
            content = 2L,
            conditionalAccessId = null,
            conditionalAccessName = null,
            providerName = null,
        )
        val populatedChannel = Channel.create(
            id = ChannelId(1L),
            name = "Channel One",
            uuid = "uuid-1",
            number = 1L,
            numberMinor = 0L,
            icon = "icon-1",
            currentEventId = EventId(100L),
            nextEventId = EventId(101L),
            services = listOf(serviceWithConditionalAccess, serviceWithoutConditionalAccess),
            tagIds = listOf(ChannelTagId(10L), ChannelTagId(20L)),
        )
        val emptyChannel = Channel.create(id = ChannelId(2L))
        val populatedTag = ChannelTag.create(
            id = ChannelTagId(10L),
            name = "Tag One",
            uuid = "tag-uuid-1",
            index = 1L,
            icon = "tag-icon",
            titledIcon = true,
            channelIds = listOf(ChannelId(1L)),
        )
        val emptyTag = ChannelTag.create(id = ChannelTagId(20L))
        val catalog = ChannelCatalog.create(
            channels = listOf(populatedChannel, emptyChannel),
            tags = listOf(populatedTag, emptyTag),
        )

        val restored = catalog.toDto().toModel()

        assertEquals(catalog, restored)
    }

    @Test
    fun `epg snapshot round trips through the DTO including an empty coverage`() {
        val populatedRating = EpgRating(
            age = 12L,
            label = null,
            icon = "rating-icon",
            authority = null,
            country = "AT",
            stars = null,
        )
        val populatedEpisode = EpgEpisode(
            id = EpgEpisodeId(5L),
            seriesLinkId = null,
            seasonNumber = 1L,
            seasonCount = null,
            episodeNumber = 3L,
            episodeCount = null,
            partNumber = 1L,
            partCount = null,
            onscreen = "S01E03",
        )
        val start = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val stop = Instant.fromEpochMilliseconds(1_700_003_600_000L)
        val populatedEvent = EpgEvent.create(
            id = EventId(1L),
            channelId = ChannelId(1L),
            start = start,
            stop = stop,
            title = "Title",
            subtitle = "Subtitle",
            summary = "Summary",
            description = "Description",
            genre = "Genre",
            categories = listOf("Category A", "Category B"),
            keywords = listOf("keyword"),
            seriesLinkUri = "series-uri",
            episodeUri = "episode-uri",
            contentType = 16L,
            rating = populatedRating,
            copyrightYear = 2024L,
            firstAired = start,
            isNew = true,
            episode = populatedEpisode,
            image = "image-uri",
            dvrEntryId = DvrEntryId(9L),
            nextEventId = EventId(2L),
        )
        val minimalEvent = EpgEvent.create(
            id = EventId(2L),
            start = start,
            stop = stop,
        )
        val populatedCoverage = EpgCoverage.create(
            channelId = ChannelId(1L),
            coveredFrom = start,
            coveredTo = stop,
            queriedTo = null,
        )
        val emptyCoverage = EpgCoverage.empty(
            channelId = ChannelId(2L),
            queriedTo = stop,
        )
        val snapshot = EpgSnapshot.create(
            events = listOf(populatedEvent, minimalEvent),
            coverages = listOf(populatedCoverage, emptyCoverage),
        )

        val restored = snapshot.toDto().toModel()

        assertEquals(snapshot, restored)
        assertTrue(restored.coverages[1].isEmpty)
    }
}
