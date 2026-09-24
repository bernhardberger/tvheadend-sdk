package at.bernhardberger.tvheadend.sdk.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class ArtworkIdTest {
    @Test
    fun `parse accepts only positive decimal image cache selectors`() {
        val cases = mapOf(
            "imagecache/73" to ArtworkId(73),
            "/imagecache/74" to ArtworkId(74),
            "imagecache/2147483647" to ArtworkId(Int.MAX_VALUE),
            null to null,
            "" to null,
            "imagecache/" to null,
            "imagecache/0" to null,
            "imagecache/-1" to null,
            "imagecache/+1" to null,
            "imagecache/1/2" to null,
            "imagecache/2147483648" to null,
            "//imagecache/1" to null,
            "https://private-host/imagecache/1" to null,
            "picon/x" to null,
        )

        cases.forEach { (selector, expected) ->
            assertEquals(expected, ArtworkId.parse(selector), "selector=$selector")
        }
    }
}
