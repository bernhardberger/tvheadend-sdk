package at.bernhardberger.tvheadend.sdk.core

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

internal class SessionPolicyTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `EPG equality and hash code include both configuration values`() {
        val first = EpgCoveragePolicy.create(2.days, 123)
        val equal = EpgCoveragePolicy.create(48.hours, 123)
        assertEquals(first, equal)
        assertEquals(equal, first)
        assertEquals(first.hashCode(), equal.hashCode())
        assertEquals(1, setOf(first, equal).size)
        assertNotEquals(first, EpgCoveragePolicy.create(3.days, 123))
        assertNotEquals(first, EpgCoveragePolicy.create(2.days, 124))
        assertNotEquals(first, null)
        assertFalse(first.equals("policy"))
    }

    @Test
    fun `cache equality normalizes absolute roots and includes every budget`() {
        val absolute = File(root, "not-created").absoluteFile
        val relative = absolute.relativeTo(File("").absoluteFile)
        val first = MetadataCachePolicy.create(absolute)
        val equal = MetadataCachePolicy.create(File(relative, "unused/.."))
        assertEquals(first, equal)
        assertEquals(equal, first)
        assertEquals(first.hashCode(), equal.hashCode())
        assertEquals(1, setOf(first, equal).size)
        assertEquals(absolute, equal.root)
        assertFalse(absolute.exists())
        assertNotEquals(first, MetadataCachePolicy.create(root))
        assertNotEquals(first, MetadataCachePolicy.create(absolute, metadataRetention = 8.days))
        assertNotEquals(first, MetadataCachePolicy.create(absolute, artworkRetention = 31.days))
        assertNotEquals(first, MetadataCachePolicy.create(absolute, artworkMaxBytes = 1))
        assertNotEquals(first, null)
        assertFalse(first.equals("policy"))
        assertFalse(first.toString().contains(absolute.path))
    }

    @Test
    fun `cache equality does not resolve symbolic links`() {
        val target = Files.createDirectory(root.toPath().resolve("target"))
        val alias = Files.createSymbolicLink(root.toPath().resolve("alias"), target)
        assertNotEquals(MetadataCachePolicy.create(target.toFile()), MetadataCachePolicy.create(alias.toFile()))
    }
}
