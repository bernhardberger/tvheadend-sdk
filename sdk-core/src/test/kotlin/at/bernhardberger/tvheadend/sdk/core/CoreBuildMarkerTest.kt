package at.bernhardberger.tvheadend.sdk.core

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

internal class CoreBuildMarkerTest {
    @Test
    fun `core production classes are available to Jupiter tests`() {
        assertSame(CoreBuildMarker, CoreBuildMarker)
    }
}
