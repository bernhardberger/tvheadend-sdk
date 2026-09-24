@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class TvheadendLoadControlTest {
    private val playerId = PlayerId("tvheadend-load-control-test")
    private val timeline = SinglePeriodTimeline(C.TIME_UNSET, false, true, false, null, MediaItem.EMPTY)
    private val mediaPeriodId = MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))

    @Test
    fun `recommended load control starts after the tuned playback buffers`() {
        val loadControl = preparedLoadControl()

        assertFalse(loadControl.shouldStartPlayback(parameters(bufferedMs = 1_999, rebuffering = false)))
        assertTrue(loadControl.shouldStartPlayback(parameters(bufferedMs = 2_000, rebuffering = false)))
        assertFalse(loadControl.shouldStartPlayback(parameters(bufferedMs = 1_499, rebuffering = true)))
        assertTrue(loadControl.shouldStartPlayback(parameters(bufferedMs = 1_500, rebuffering = true)))
    }

    @Test
    fun `recommended load control loads between the minimum and maximum buffer`() {
        val loadControl = preparedLoadControl()

        assertTrue(loadControl.shouldContinueLoading(parameters(bufferedMs = 14_999)))
        assertTrue(loadControl.shouldContinueLoading(parameters(bufferedMs = 29_999)))
        assertFalse(loadControl.shouldContinueLoading(parameters(bufferedMs = 30_000)))
        assertFalse(loadControl.shouldContinueLoading(parameters(bufferedMs = 15_000)))
        assertTrue(loadControl.shouldContinueLoading(parameters(bufferedMs = 14_999)))
    }

    private fun preparedLoadControl(): LoadControl =
        createTvheadendLoadControl().apply {
            onPrepared(playerId)
            onTracksSelected(parameters(bufferedMs = 0), TrackGroupArray.EMPTY, emptyArray())
        }

    private fun parameters(bufferedMs: Long, rebuffering: Boolean = false): LoadControl.Parameters =
        LoadControl.Parameters(
            playerId,
            timeline,
            mediaPeriodId,
            0L,
            bufferedMs * 1_000L,
            1f,
            true,
            rebuffering,
            C.TIME_UNSET,
            C.TIME_UNSET,
        )
}
