@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class ChannelMediaKindTest {
    @Test
    fun `service content is exact and independent of descriptive labels`() {
        val cases = listOf(
            0L to ChannelMediaKind.OTHER,
            1L to ChannelMediaKind.TV,
            2L to ChannelMediaKind.RADIO,
            3L to ChannelMediaKind.UNKNOWN,
            0xffff_ffffL to ChannelMediaKind.UNKNOWN,
        )
        for ((content, expected) in cases) {
            assertEquals(expected, service(content).mediaKind)
        }
        assertEquals(listOf("OTHER", "TV", "RADIO", "UNKNOWN", "UNKNOWN"), cases.map { it.second.toString() })
    }

    @Test
    fun `channel classification distinguishes missing and mixed evidence`() {
        val cases = listOf(
            null to ChannelMediaKind.UNKNOWN,
            emptyList<Long>() to ChannelMediaKind.UNKNOWN,
            listOf(0L, 0L) to ChannelMediaKind.OTHER,
            listOf(1L) to ChannelMediaKind.TV,
            listOf(2L) to ChannelMediaKind.RADIO,
            listOf(3L) to ChannelMediaKind.UNKNOWN,
            listOf(2L, 1L) to ChannelMediaKind.TV,
            listOf(1L, 2L) to ChannelMediaKind.TV,
            listOf(0L, 3L) to ChannelMediaKind.UNKNOWN,
            listOf(2L, 3L, 0L) to ChannelMediaKind.RADIO,
            listOf(3L, 1L, 0L) to ChannelMediaKind.TV,
        )
        for ((contents, expected) in cases) {
            val channel = Channel.create(ChannelId(1), name = "Radio TV", services = contents?.map(::service))
            assertEquals(expected, channel.mediaKind)
        }
    }

    private fun service(content: Long): ChannelService = ChannelService(
        name = "Radio TV",
        type = "UHD Radio",
        content = content,
        conditionalAccessId = null,
        conditionalAccessName = null,
        providerName = null,
    )
}
