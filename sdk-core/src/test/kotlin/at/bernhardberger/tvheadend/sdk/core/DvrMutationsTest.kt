package at.bernhardberger.tvheadend.sdk.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalTime
import kotlin.time.Instant

internal class DvrMutationsTest {
    @Test
    fun `explicit time requires title while programme scheduling permits omission`() {
        val schedule = DvrSchedule.ExplicitTime(ChannelId(1), Instant.fromEpochSeconds(0), Instant.fromEpochSeconds(1))
        for (title in listOf(null, "")) {
            assertThrows(IllegalArgumentException::class.java) { DvrScheduleRequest(schedule, title = title) }
            DvrScheduleRequest(DvrSchedule.Programme(EventId(1)), title = title)
        }
        DvrScheduleRequest(schedule, title = " ")
        DvrScheduleRequest(schedule, title = "Recording")
    }

    @Test
    fun `timerec minutes stop at 1439 but autorec retains the 1440 sentinel`() {
        for (minute in listOf(null, LocalTime.MIDNIGHT, LocalTime.of(23, 59))) {
            TimerecRuleCreate("Recording", startMinutesSinceMidnight = minute, stopMinutesSinceMidnight = minute)
            TimerecRuleUpdate(startMinutesSinceMidnight = minute, stopMinutesSinceMidnight = minute)
        }
        for (minute in listOf(LocalTime.of(0, 0, 1), LocalTime.of(23, 59, 0, 1))) {
            assertThrows(IllegalArgumentException::class.java) {
                TimerecRuleCreate("Recording", startMinutesSinceMidnight = minute)
            }
            assertThrows(IllegalArgumentException::class.java) {
                TimerecRuleCreate("Recording", stopMinutesSinceMidnight = minute)
            }
            assertThrows(IllegalArgumentException::class.java) {
                TimerecRuleUpdate(startMinutesSinceMidnight = minute)
            }
            assertThrows(IllegalArgumentException::class.java) {
                TimerecRuleUpdate(stopMinutesSinceMidnight = minute)
            }
        }
        assertEquals(
            AutorecTime.AnyTime,
            AutorecRuleCreate("Recording", startMinutesSinceMidnight = AutorecTime.AnyTime).startMinutesSinceMidnight,
        )
        assertEquals(
            AutorecTime.AnyTime,
            AutorecRuleUpdate(startWindowEndMinutesSinceMidnight = AutorecTime.AnyTime)
                .startWindowEndMinutesSinceMidnight,
        )
    }
}
