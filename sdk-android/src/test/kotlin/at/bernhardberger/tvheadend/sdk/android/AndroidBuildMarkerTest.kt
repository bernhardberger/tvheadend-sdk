package at.bernhardberger.tvheadend.sdk.android

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

internal class AndroidBuildMarkerTest {
    @Test
    fun `Android production classes are available to local Jupiter tests`() {
        assertSame(AndroidBuildMarker, AndroidBuildMarker)
    }
}
