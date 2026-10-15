package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal class DvrEntryFactoryTest {
    @Test
    fun `existing factory calls resolve without experimental opt in`() {
        val id = DvrEntryId(1)
        assertEquals(id, DvrEntry.create(id).id)
        assertEquals("fixture", DvrEntry.create(id, "fixture").uuid)
        assertEquals("programme", DvrEntry.create(id = id, title = "programme").title)
    }

    @Test
    @OptIn(ExperimentalTvheadendApi::class)
    fun `account factory preserves nullable labels and copies collections with redacted rendering`() {
        val id = DvrEntryId(1)
        for ((owner, creator) in listOf(null to null, "fixture-owner" to null, null to "fixture-creator",
            "fixture-owner" to "fixture-creator")) {
            val file = DvrRecordingFile.create(fileId = 1)
            val files = mutableListOf(file)
            val folder = mutableListOf("series")
            val entry = DvrEntry.create(id, files = files, folder = folder, owner = owner, creator = creator)
            files.clear()
            folder.clear()
            assertEquals(owner, entry.owner)
            assertEquals(creator, entry.creator)
            assertEquals(listOf(file), entry.files)
            assertEquals(listOf("series"), entry.folder)
            assertThrows(UnsupportedOperationException::class.java) { (entry.files as MutableList<DvrRecordingFile>).clear() }
            assertThrows(UnsupportedOperationException::class.java) { (entry.folder as MutableList<String>).clear() }
            assertEquals("DvrEntry(<redacted>)", entry.toString())
        }
        val stable = DvrEntry.create(id)
        assertNull(stable.owner)
        assertNull(stable.creator)
        assertEquals(stable, DvrEntry.create(id, owner = null, creator = null))
    }

    @Test
    @OptIn(ExperimentalTvheadendApi::class)
    fun `account factory retains timing wire domain padding and folder validation`() {
        val id = DvrEntryId(1)
        assertThrows(IllegalArgumentException::class.java) {
            DvrEntry.create(id, start = Instant.fromEpochSeconds(20), stop = Instant.fromEpochSeconds(10),
                owner = "fixture-owner", creator = "fixture-creator")
        }
        for (days in listOf(-1L, 0x1_0000_0000L)) {
            assertThrows(IllegalArgumentException::class.java) {
                DvrEntry.create(id, retentionDays = days, owner = null, creator = null)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            DvrEntry.create(id, startExtraMinutes = 1.seconds, owner = null, creator = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DvrEntry.create(id, folder = listOf(".."), owner = null, creator = null)
        }
    }
}
