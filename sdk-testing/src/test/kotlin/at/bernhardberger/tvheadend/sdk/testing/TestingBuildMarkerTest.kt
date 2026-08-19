package at.bernhardberger.tvheadend.sdk.testing

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

internal class TestingBuildMarkerTest {
    @Test
    fun `testing production classes are available to Jupiter tests`() {
        assertSame(TestingBuildMarker, TestingBuildMarker)
    }
}
