package at.bernhardberger.tvheadend.sdk.playback

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

internal class PlaybackBuildMarkerTest {
    @Test
    fun `playback production classes are available to Jupiter tests`() {
        assertSame(PlaybackBuildMarker, PlaybackBuildMarker)
    }
}
