@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrEntry
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrRecordingFile
import at.bernhardberger.tvheadend.sdk.core.metadata.ReducedDvrEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class DvrEntryFolderTest {
    @Test
    fun `application folder is defensively copied and unmodifiable`() {
        val supplied = mutableListOf("archive", "series")
        val entry = DvrEntry.create(DvrEntryId(1), folder = supplied)
        supplied.clear()

        assertEquals(listOf("archive", "series"), entry.folder)
        assertThrows(UnsupportedOperationException::class.java) {
            (entry.folder as MutableList<String>).add("extra")
        }
        val root = DvrEntry.create(DvrEntryId(1), folder = emptyList())
        assertEquals(emptyList<String>(), root.folder)
        assertThrows(UnsupportedOperationException::class.java) {
            (root.folder as MutableList<String>).add("extra")
        }
        assertNull(DvrEntry.create(DvrEntryId(1)).folder)
    }

    @Test
    fun `invalid application folder components fail with a fixed redacted message`() {
        listOf("", ".", "..").forEach { invalid ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                DvrEntry.create(DvrEntryId(1), folder = listOf("private-folder-marker", invalid))
            }
            assertEquals("DvrEntry folder contains invalid components", failure.message)
            assertFalse(failure.message.orEmpty().contains("private-folder-marker"))
        }
    }

    @Test
    fun `server file paths take precedence over an application folder including unknown derivations`() {
        listOf("/server-folder/one.ts", "/one.ts", "/server-folder/../one.ts").forEach { path ->
            val serverEntry = entry(listOf(path))
            val applicationEntry = DvrEntry.create(
                DvrEntryId(1), files = serverEntry.files, folder = listOf("application-folder"),
            )
            assertEquals(serverEntry.folder, applicationEntry.folder)
        }
    }

    @Test
    fun `application folder applies to files without server paths`() {
        val files = listOf(DvrRecordingFile.create(fileId = 1), DvrRecordingFile.create(fileId = 2))
        val entry = DvrEntry.create(DvrEntryId(1), files = files, folder = listOf("archive", "series"))

        assertEquals(listOf("archive", "series"), entry.folder)
        assertNull(DvrEntry.create(DvrEntryId(1), files = files).folder)
        assertNull(entry(listOf(null, null)).folder)
    }

    @Test
    fun `application folder participates in value equality without appearing in diagnostics`() {
        val entry = DvrEntry.create(DvrEntryId(1), folder = listOf("private-folder-marker"))
        val equal = DvrEntry.create(DvrEntryId(1), folder = mutableListOf("private-folder-marker"))

        assertEquals(entry, equal)
        assertEquals(entry.hashCode(), equal.hashCode())
        assertNotEquals(entry, DvrEntry.create(DvrEntryId(1), folder = listOf("other-folder")))
        assertNotEquals(entry, DvrEntry.create(DvrEntryId(1)))
        assertNotEquals(DvrEntry.create(DvrEntryId(1), folder = emptyList()), DvrEntry.create(DvrEntryId(1)))
        assertEquals("DvrEntry(<redacted>)", entry.toString())
        assertFalse(listOf(entry).toString().contains("private-folder-marker"))
    }

    @Test
    fun `single file supplies directory components instead of the entry path`() {
        val entry = entry(listOf("/archive/series/episode.ts"), path = "/other/recording.ts")

        assertEquals(listOf("archive", "series"), entry.folder)
    }

    @Test
    fun `multiple files use their longest common component prefix`() {
        val entry = entry(listOf("/archive/series/a/one.ts", "/archive/series/b/two.ts", "/archive/series/three.ts"))

        assertEquals(listOf("archive", "series"), entry.folder)
    }

    @Test
    fun `files without a common component prefix resolve to the storage root`() {
        assertEquals(emptyList<String>(), entry(listOf("/series/one.ts", "/series-extra/two.ts")).folder)
    }

    @Test
    fun `missing or empty files fall back to the entry directory`() {
        assertEquals(listOf("archive", "series"), entry(path = "/archive/series/recording.ts").folder)
        assertEquals(listOf("archive"), entry(emptyList(), path = "/archive/recording.ts").folder)
    }

    @Test
    fun `missing paths remain unknown rather than falling back past an unknown file`() {
        assertNull(entry().folder)
        assertNull(entry(emptyList()).folder)
        assertNull(entry(listOf("/archive/one.ts", null), path = "/archive/fallback.ts").folder)
    }

    @Test
    fun `dot components invalidate every selected path including its final component`() {
        listOf("/archive/./one.ts", "/archive/../one.ts", "/archive/.", "/archive/..").forEach { path ->
            assertNull(entry(listOf(path)).folder)
            assertNull(entry(path = path).folder)
            assertNull(entry(listOf("/one.ts", "/other/two.ts", path)).folder)
        }
    }

    @Test
    fun `leading and repeated slashes do not create empty components`() {
        assertEquals(listOf("archive", "series"), entry(listOf("///archive//series///one.ts")).folder)
        assertEquals(listOf("archive"), entry(path = "archive//one.ts").folder)
    }

    @Test
    fun `root files have an empty folder`() {
        assertEquals(emptyList<String>(), entry(listOf("/recording.ts")).folder)
        assertEquals(emptyList<String>(), entry(path = "recording.ts").folder)
    }

    @Test
    fun `folder lists are unmodifiable including the storage root`() {
        listOf("/archive/recording.ts", "/recording.ts").forEach { path ->
            val folder = requireNotNull(entry(listOf(path)).folder)
            assertThrows(UnsupportedOperationException::class.java) {
                (folder as MutableList<String>).add("extra")
            }
        }
    }

    @Test
    fun `rendering keeps folder metadata redacted`() {
        val entry = entry(listOf("/marker-folder/recording.ts"))

        assertEquals(listOf("marker-folder"), entry.folder)
        assertEquals("DvrEntry(<redacted>)", entry.toString())
        assertFalse(listOf(entry, entry.files).toString().contains("marker-folder"))
    }

    private fun entry(filePaths: List<String?>? = null, path: String? = null): DvrEntry = requireNotNull(
        ReducedDvrEntry.fromAdd(
            GatewayDvrEntry(
                id = DvrEntryId(1),
                files = filePaths?.map { GatewayDvrRecordingFile(1, it, null, null, null) },
                path = path,
            ),
        )?.toPublicOrNull(),
    )
}
