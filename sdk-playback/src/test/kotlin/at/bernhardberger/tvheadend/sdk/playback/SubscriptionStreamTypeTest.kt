package at.bernhardberger.tvheadend.sdk.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SubscriptionStreamTypeTest {
    @Test
    fun `every stream type has a distinct safe label`() {
        val cases = listOf(
            SubscriptionStreamType.MPEG2_VIDEO to "MPEG2_VIDEO",
            SubscriptionStreamType.H264 to "H264",
            SubscriptionStreamType.H265 to "H265",
            SubscriptionStreamType.AAC to "AAC",
            SubscriptionStreamType.AC3 to "AC3",
            SubscriptionStreamType.EAC3 to "EAC3",
            SubscriptionStreamType.MPEG2_AUDIO to "MPEG2_AUDIO",
            SubscriptionStreamType.DVB_SUBTITLE to "DVB_SUBTITLE",
            SubscriptionStreamType.TEXT_SUBTITLE to "TEXT_SUBTITLE",
            SubscriptionStreamType.TELETEXT to "TELETEXT",
            SubscriptionStreamType.UNKNOWN to "UNKNOWN",
        )
        assertEquals(cases.size, cases.map { it.first }.toSet().size)
        assertEquals(cases.size, cases.map { it.second }.toSet().size)
        cases.forEach { (value, label) -> assertEquals(label, value.toString()) }
    }
}
