package at.bernhardberger.tvheadend.sdk.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.time.Instant

internal class ContentGenreTest {
    @Test
    fun `every subgenre belongs to the category of its high nibble`() {
        ContentSubgenre.entries.forEach { subgenre ->
            assertEquals(subgenre.category.code, subgenre.code ushr 4, subgenre.name)
        }
    }

    @Test
    fun `every subgenre round trips through its content type`() {
        assertEquals(71, ContentSubgenre.entries.size)
        ContentSubgenre.entries.forEach { subgenre ->
            val genre = ContentGenre.fromContentType(subgenre.code.toLong())
            assertEquals(subgenre.category, genre?.category, subgenre.name)
            assertEquals(subgenre, genre?.subgenre, subgenre.name)
        }
    }

    @Test
    fun `general bytes decode to the category without a subgenre except original language`() {
        ContentCategory.entries.forEach { category ->
            val genre = ContentGenre.fromContentType((category.code shl 4).toLong())
            assertEquals(category, genre?.category, category.name)
            val expectedSubgenre = if (category == ContentCategory.SPECIAL_CHARACTERISTICS) {
                ContentSubgenre.ORIGINAL_LANGUAGE
            } else {
                null
            }
            assertEquals(expectedSubgenre, genre?.subgenre, category.name)
        }
    }

    @Test
    fun `unassigned and user defined level two values keep only the category`() {
        val expected = mapOf(
            0x19L to ContentCategory.MOVIE_DRAMA,
            0x1FL to ContentCategory.MOVIE_DRAMA,
            0x4CL to ContentCategory.SPORTS,
            0x7CL to ContentCategory.ARTS_CULTURE,
            0xA8L to ContentCategory.LEISURE_HOBBIES,
            0xB6L to ContentCategory.SPECIAL_CHARACTERISTICS,
            0xBFL to ContentCategory.SPECIAL_CHARACTERISTICS,
            0xF5L to ContentCategory.USER_DEFINED,
            0xFFL to ContentCategory.USER_DEFINED,
        )

        expected.forEach { (contentType, category) ->
            val genre = ContentGenre.fromContentType(contentType)
            assertEquals(category, genre?.category, "contentType=$contentType")
            assertNull(genre?.subgenre, "contentType=$contentType")
        }
    }

    @Test
    fun `absent out of range undefined and reserved content types do not decode`() {
        listOf(null, -1L, 0x00L, 0x0FL, 0xC0L, 0xD3L, 0xE5L, 0xEFL, 0x100L, 0x1_0043L).forEach { contentType ->
            assertNull(ContentGenre.fromContentType(contentType), "contentType=$contentType")
        }
    }

    @Test
    fun `genres compare by category and subgenre`() {
        assertEquals(ContentGenre.fromContentType(0x43), ContentGenre.fromContentType(0x43))
        assertEquals(ContentGenre.fromContentType(0x43).hashCode(), ContentGenre.fromContentType(0x43).hashCode())
        assertEquals(
            "ContentGenre(category=SPORTS, subgenre=FOOTBALL_SOCCER)",
            ContentGenre.fromContentType(0x43).toString(),
        )
        assertNotEquals(ContentGenre.fromContentType(0x40), ContentGenre.fromContentType(0x43))
    }

    @Test
    fun `EPG events and DVR entries expose the decoded genre and keep the raw content type`() {
        val event = EpgEvent.create(
            id = EventId(1),
            start = Instant.fromEpochSeconds(10),
            stop = Instant.fromEpochSeconds(20),
            contentType = 0x43,
        )
        val entry = DvrEntry.create(id = DvrEntryId(1), contentType = 0x10)

        assertEquals(ContentCategory.SPORTS, event.contentGenre?.category)
        assertEquals(ContentSubgenre.FOOTBALL_SOCCER, event.contentGenre?.subgenre)
        assertEquals(0x43L, event.contentType)
        assertEquals(ContentCategory.MOVIE_DRAMA, entry.contentGenre?.category)
        assertNull(entry.contentGenre?.subgenre)
        assertEquals(0x10L, entry.contentType)
        assertNull(EpgEvent.create(EventId(2), start = event.start, stop = event.stop).contentGenre)
        assertNull(DvrEntry.create(id = DvrEntryId(2)).contentGenre)
    }
}
