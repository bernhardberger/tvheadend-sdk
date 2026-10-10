package at.bernhardberger.tvheadend.sdk.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
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
        for (minute in listOf(null, 0, 1439)) {
            TimerecRuleCreate("Recording", startMinutesSinceMidnight = minute, stopMinutesSinceMidnight = minute)
            TimerecRuleUpdate(startMinutesSinceMidnight = minute, stopMinutesSinceMidnight = minute)
        }
        for (minute in listOf(-1, 1440)) {
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
        assertEquals(1440, AutorecRuleCreate("Recording", startMinutesSinceMidnight = 1440).startMinutesSinceMidnight)
        assertEquals(1440, AutorecRuleUpdate(startWindowEndMinutesSinceMidnight = 1440).startWindowEndMinutesSinceMidnight)
    }
}
