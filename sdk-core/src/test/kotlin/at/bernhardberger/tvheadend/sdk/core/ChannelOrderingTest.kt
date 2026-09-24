package at.bernhardberger.tvheadend.sdk.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.Collator
import java.util.Locale
import kotlin.random.Random

internal class ChannelOrderingTest {
    private val german = channelComparator(Collator.getInstance(Locale.GERMAN))

    @Test
    fun `only positive major numbers are user visible`() {
        assertTrue(channel(1, number = 1).hasChannelNumber)
        assertFalse(channel(2, number = 0).hasChannelNumber)
        assertFalse(channel(3, number = 0, numberMinor = 5).hasChannelNumber)
        assertFalse(channel(4).hasChannelNumber)
    }

    @Test
    fun `numbered channels precede unnumbered ones including number zero`() {
        val ordered = listOf(
            channel(9, name = "Arte", number = 0),
            channel(8, name = "Zdf", number = 12),
            channel(7, name = "Bayern"),
            channel(6, name = "Ard", number = 1),
        ).sortedWith(german)

        assertEquals(listOf(6L, 8L, 9L, 7L), ordered.ids())
    }

    @Test
    fun `numbered channels order by major then absent minor first then name`() {
        val ordered = listOf(
            channel(1, name = "A", number = 5, numberMinor = 2),
            channel(2, name = "B", number = 5, numberMinor = 1),
            channel(3, name = "C", number = 5),
            channel(4, name = "D", number = 4, numberMinor = 9),
            channel(5, name = "E", number = 10),
            channel(6, name = "A", number = 5),
        ).sortedWith(german)

        assertEquals(listOf(4L, 6L, 3L, 2L, 1L, 5L), ordered.ids())
    }

    @Test
    fun `unnumbered channels ignore minor numbers and order names with the locale collator`() {
        val ordered = listOf(
            channel(1, name = "Zeit", number = 0, numberMinor = 1),
            channel(2, name = "Öffentlich", number = 0),
            channel(3, name = "arte"),
            channel(4, name = "Ostsee", numberMinor = 3),
            channel(5, name = "Bayern"),
        ).sortedWith(german)

        assertEquals(listOf("arte", "Bayern", "Öffentlich", "Ostsee", "Zeit"), ordered.map { it.name })
    }

    @Test
    fun `missing and blank names sort after named channels`() {
        val ordered = listOf(
            channel(1, name = " ", number = 0),
            channel(2),
            channel(3, name = "Zeit"),
            channel(4, name = "", number = 3),
            channel(5, name = "Ard", number = 3),
        ).sortedWith(german)

        assertEquals(listOf(5L, 4L, 3L, 1L, 2L), ordered.ids())
    }

    @Test
    fun `identifier breaks remaining ties`() {
        val ordered = listOf(
            channel(30, name = "Same", number = 1),
            channel(10, name = "Same", number = 1),
            channel(20, name = "Same", number = 1),
            channel(3, name = "Same"),
            channel(1, name = "Same", number = 0),
        ).sortedWith(german)

        assertEquals(listOf(10L, 20L, 30L, 1L, 3L), ordered.ids())
    }

    @Test
    fun `order is total and deterministic under shuffles`() {
        val channels = listOf(
            channel(1, name = "Ard", number = 1),
            channel(2, name = "Zdf", number = 2),
            channel(3, name = "Zdf", number = 2, numberMinor = 1),
            channel(4, name = "arte", number = 0),
            channel(5, name = "Arte"),
            channel(6, name = "Äpfel"),
            channel(7),
            channel(8, name = ""),
            channel(9, name = "Ard", number = 1),
        )
        val expected = channels.sortedWith(german)

        repeat(50) { seed ->
            assertEquals(expected, channels.shuffled(Random(seed)).sortedWith(german))
        }
        channels.forEach { left ->
            channels.forEach { right ->
                if (left !== right) assertTrue(german.compare(left, right) != 0)
            }
        }
    }

    @Test
    fun `default comparator applies the same rules`() {
        val ordered = listOf(
            channel(2, name = "B", number = 0),
            channel(1, name = "A", number = 7),
        ).sortedWith(channelComparator())

        assertEquals(listOf(1L, 2L), ordered.ids())
    }

    private fun channel(
        id: Long,
        name: String? = null,
        number: Long? = null,
        numberMinor: Long? = null,
    ): Channel = Channel.create(ChannelId(id), name = name, number = number, numberMinor = numberMinor)

    private fun List<Channel>.ids(): List<Long> = map { it.id.value }
}
