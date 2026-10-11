package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayAutorecRule
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrEntry
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayTimerecRule
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.metadata.DvrReducer
import java.time.DayOfWeek
import java.time.LocalTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

internal class DvrFieldsTest {
    @Test
    fun `known open values round trip and unknown values use documented fallbacks`() {
        // TVHeadend src/dvr/dvr.h priority, autorec-record and broadcast enums.
        val priorities = listOf(
            DvrPriority.IMPORTANT, DvrPriority.HIGH, DvrPriority.NORMAL, DvrPriority.LOW,
            DvrPriority.UNIMPORTANT, DvrPriority.NOT_SET, DvrPriority.DEFAULT,
        )
        val duplicates = listOf(
            DuplicateDetection.ALL, DuplicateDetection.DIFFERENT_EPISODE_NUMBER,
            DuplicateDetection.DIFFERENT_SUBTITLE, DuplicateDetection.DIFFERENT_DESCRIPTION,
            DuplicateDetection.ONCE_PER_WEEK, DuplicateDetection.ONCE_PER_DAY,
            DuplicateDetection.LOCAL_DIFFERENT_EPISODE_NUMBER, DuplicateDetection.LOCAL_DIFFERENT_TITLE,
            DuplicateDetection.LOCAL_DIFFERENT_SUBTITLE, DuplicateDetection.LOCAL_DIFFERENT_DESCRIPTION,
            DuplicateDetection.LOCAL_ONCE_PER_WEEK, DuplicateDetection.LOCAL_ONCE_PER_DAY,
            DuplicateDetection.ONCE_PER_MONTH, DuplicateDetection.LOCAL_ONCE_PER_MONTH,
            DuplicateDetection.UNIQUE, DuplicateDetection.DVR_PROFILE,
        )
        val broadcasts = listOf(
            BroadcastType.ALL, BroadcastType.NEW_OR_UNKNOWN, BroadcastType.REPEAT, BroadcastType.NEW,
        )
        priorities.forEachIndexed { wire, value ->
            assertSame(value, DvrPriority.fromWire(wire.toLong()))
            assertEquals(wire.toLong(), value.wireValue)
        }
        duplicates.forEachIndexed { wire, value ->
            assertSame(value, DuplicateDetection.fromWire(wire.toLong()))
            assertEquals(wire.toLong(), value.wireValue)
        }
        broadcasts.forEachIndexed { wire, value ->
            assertSame(value, BroadcastType.fromWire(wire.toLong()))
            assertEquals(wire.toLong(), value.wireValue)
        }
        for (wire in listOf(-1L, 999L, Long.MAX_VALUE)) {
            assertSame(DvrPriority.UNKNOWN, DvrPriority.fromWire(wire))
            assertSame(DuplicateDetection.UNKNOWN, DuplicateDetection.fromWire(wire))
            assertSame(BroadcastType.UNKNOWN, BroadcastType.fromWire(wire))
        }
        assertNull(DvrPriority.UNKNOWN.wireValue)
        assertNull(DuplicateDetection.UNKNOWN.wireValue)
        assertNull(BroadcastType.UNKNOWN.wireValue)
        assertEquals("UNKNOWN", DvrPriority.UNKNOWN.toString())
        assertEquals("UNKNOWN", DuplicateDetection.UNKNOWN.toString())
        assertEquals("UNKNOWN", BroadcastType.UNKNOWN.toString())
        for (values in listOf(priorities, duplicates, broadcasts)) {
            assertEquals(values.size, values.toSet().size)
            assertEquals(values.size, values.map { it.toString() }.toSet().size)
        }
    }

    @Test
    fun `unknown settings are rejected by every accepting request`() {
        assertThrows(IllegalArgumentException::class.java) { DvrEntryUpdate(priority = DvrPriority.UNKNOWN) }
        assertThrows(IllegalArgumentException::class.java) { AutorecRuleCreate("x", priority = DvrPriority.UNKNOWN) }
        assertThrows(IllegalArgumentException::class.java) { AutorecRuleUpdate(priority = DvrPriority.UNKNOWN) }
        assertThrows(IllegalArgumentException::class.java) { TimerecRuleCreate("x", priority = DvrPriority.UNKNOWN) }
        assertThrows(IllegalArgumentException::class.java) { TimerecRuleUpdate(priority = DvrPriority.UNKNOWN) }
        assertThrows(IllegalArgumentException::class.java) {
            AutorecRuleCreate("x", duplicateDetection = DuplicateDetection.UNKNOWN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutorecRuleUpdate(duplicateDetection = DuplicateDetection.UNKNOWN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutorecRuleCreate("x", broadcastType = BroadcastType.UNKNOWN)
        }
        assertThrows(IllegalArgumentException::class.java) { AutorecRuleUpdate(broadcastType = BroadcastType.UNKNOWN) }
    }

    @Test
    fun `wire weekdays are Monday first and unknown mask bits are ignored`() {
        DayOfWeek.values().forEachIndexed { bit, day ->
            val wire = 1L shl bit
            assertEquals(setOf(day), wire.toDvrDays())
            assertEquals(wire, setOf(day).toDvrDayMask())
        }
        assertEquals(emptySet<DayOfWeek>(), 128L.toDvrDays())
        assertEquals(0L, emptySet<DayOfWeek>().toDvrDayMask())
        assertEquals(DayOfWeek.values().toSet(), 127L.toDvrDays())
        assertEquals(65L, setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY).toDvrDayMask())
    }

    @Test
    fun `minute boundaries preserve midnight last minute and distinct any-time sentinel`() {
        for ((wire, time) in listOf(0 to LocalTime.MIDNIGHT, 1439 to LocalTime.of(23, 59))) {
            assertEquals(time, wire.toDvrLocalTime())
            assertEquals(wire, time.toDvrMinute())
            assertEquals(AutorecTime.At(time), wire.toAutorecTime())
            assertEquals(wire, AutorecTime.At(time).toDvrMinute())
        }
        assertSame(AutorecTime.AnyTime, 1440.toAutorecTime())
        assertEquals(1440, AutorecTime.AnyTime.toDvrMinute())
        assertNull(1440.toDvrLocalTime())
        for (wire in listOf(-1, 1441, Int.MAX_VALUE)) {
            assertNull(wire.toDvrLocalTime())
            assertNull(wire.toAutorecTime())
        }
        assertThrows(IllegalArgumentException::class.java) { AutorecTime.At(LocalTime.of(0, 0, 1)) }
        assertThrows(IllegalArgumentException::class.java) { AutorecTime.At(LocalTime.of(0, 0, 0, 1)) }
        assertThrows(IllegalArgumentException::class.java) {
            TimerecRule.create(TimerecRuleId("time"), startMinutesSinceMidnight = LocalTime.of(0, 0, 1))
        }
    }

    @Test
    fun `padding permits signed whole minutes and rejects precision loss at all request boundaries`() {
        for (wire in listOf(-7L, 0L, 1439L, 1440L)) {
            assertEquals(wire.minutes, wire.toDvrExtraDuration())
            assertEquals(wire, wire.toDvrExtraDuration()!!.inWholeMinutes)
        }
        assertNull(Long.MAX_VALUE.toDvrExtraDuration())
        assertNull(Long.MIN_VALUE.toDvrExtraDuration())
        for (padding in listOf(1.seconds, (-1).nanoseconds, Duration.INFINITE, -Duration.INFINITE)) {
            assertThrows(IllegalArgumentException::class.java) { DvrEntryUpdate(startExtraMinutes = padding) }
            assertThrows(IllegalArgumentException::class.java) { DvrEntryUpdate(stopExtraMinutes = padding) }
            assertThrows(IllegalArgumentException::class.java) { AutorecRuleCreate("x", startExtraMinutes = padding) }
            assertThrows(IllegalArgumentException::class.java) { AutorecRuleCreate("x", stopExtraMinutes = padding) }
            assertThrows(IllegalArgumentException::class.java) { AutorecRuleUpdate(startExtraMinutes = padding) }
            assertThrows(IllegalArgumentException::class.java) { AutorecRuleUpdate(stopExtraMinutes = padding) }
            assertThrows(IllegalArgumentException::class.java) {
                DvrEntry.create(DvrEntryId(1), startExtraMinutes = padding)
            }
            assertThrows(IllegalArgumentException::class.java) {
                AutorecRule.create(AutorecRuleId("rule"), stopExtraMinutes = padding)
            }
        }
    }

    @Test
    fun `snapshot weekday sets are defensive copies`() {
        val days = mutableSetOf(DayOfWeek.MONDAY)
        val autorec = AutorecRule.create(AutorecRuleId("rule"), daysOfWeekMask = days)
        val timerec = TimerecRule.create(TimerecRuleId("time"), daysOfWeekMask = days)
        days.clear()
        assertEquals(setOf(DayOfWeek.MONDAY), autorec.daysOfWeekMask)
        assertEquals(setOf(DayOfWeek.MONDAY), timerec.daysOfWeekMask)
        for (retained in listOf(autorec.daysOfWeekMask, timerec.daysOfWeekMask)) {
            assertThrows(UnsupportedOperationException::class.java) { (retained as MutableSet<DayOfWeek>).clear() }
        }
    }

    @Test
    fun `reducer projects wire scalars artwork booleans and fallback values into typed models`() {
        val generation = GatewayGeneration()
        val reducer = DvrReducer()
        reducer.accept(MetadataEvent.DvrEntryAdded(generation, GatewayDvrEntry(
            id = DvrEntryId(1), priority = 999, duplicate = 1, image = "imagecache/7",
            fanartImage = "/imagecache/8", startExtraMinutes = -7, stopExtraMinutes = 8,
        )))
        reducer.accept(MetadataEvent.AutorecRuleAdded(generation, GatewayAutorecRule(
            id = AutorecRuleId("rule"), priority = 999, duplicateDetection = 999, broadcastType = 999,
            startMinutesSinceMidnight = 1440, startWindowEndMinutesSinceMidnight = 1439, daysOfWeekMask = 65,
        )))
        reducer.accept(MetadataEvent.TimerecRuleAdded(generation, GatewayTimerecRule(
            id = TimerecRuleId("time"), startMinutesSinceMidnight = 0, stopMinutesSinceMidnight = 1439,
        )))
        val snapshot = reducer.snapshot()
        val entry = snapshot.entries.single()
        assertSame(DvrPriority.UNKNOWN, entry.priority)
        assertEquals(true, entry.duplicate)
        assertEquals(ArtworkId(7), entry.image)
        assertEquals(ArtworkId(8), entry.fanartImage)
        assertEquals((-7).minutes, entry.startExtraMinutes)
        assertEquals(8.minutes, entry.stopExtraMinutes)
        val autorec = snapshot.autorecRules.single()
        assertSame(DuplicateDetection.UNKNOWN, autorec.duplicateDetection)
        assertSame(BroadcastType.UNKNOWN, autorec.broadcastType)
        assertSame(AutorecTime.AnyTime, autorec.startMinutesSinceMidnight)
        assertEquals(AutorecTime.At(LocalTime.of(23, 59)), autorec.startWindowEndMinutesSinceMidnight)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), autorec.daysOfWeekMask)
        assertEquals(LocalTime.MIDNIGHT, snapshot.timerecRules.single().startMinutesSinceMidnight)
        assertEquals(LocalTime.of(23, 59), snapshot.timerecRules.single().stopMinutesSinceMidnight)
        reducer.accept(MetadataEvent.DvrEntryAdded(generation, GatewayDvrEntry(
            id = DvrEntryId(1), duplicate = 0, image = "https://private.invalid/image",
        )))
        assertEquals(false, reducer.snapshot().entries.single().duplicate)
        assertNull(reducer.snapshot().entries.single().image)
    }
}
