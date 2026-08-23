@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import at.bernhardberger.tvheadend.sdk.playback.RecordingId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class RecordingResumeStateMachineTest {
    @Test
    fun `MP4 and MKV resume waits for a known seekable timeline and seeks exactly once`() {
        val seeks = mutableListOf<Long>()
        val resume = RecordingResumeStateMachine(seeks::add)
        val recording = RecordingId(7)

        resume.beginPlaybackTarget(recording, positionMillis = 180_000)
        resume.onMediaState(recording, durationMillis = null, isSeekable = false)
        assertEquals(emptyList<Long>(), seeks)

        resume.onMediaState(recording, durationMillis = 3_600_000, isSeekable = false)
        assertEquals(emptyList<Long>(), seeks)

        resume.onMediaState(recording, durationMillis = 3_600_000, isSeekable = true)
        resume.onMediaState(recording, durationMillis = 3_600_000, isSeekable = true)

        assertEquals(listOf(180_000L), seeks)
    }

    @Test
    fun `a different recording cannot consume the pending resume`() {
        val seeks = mutableListOf<Long>()
        val resume = RecordingResumeStateMachine(seeks::add)

        resume.beginPlaybackTarget(RecordingId(7), positionMillis = 180_000)
        resume.onMediaState(RecordingId(8), durationMillis = 3_600_000, isSeekable = true)
        assertEquals(emptyList<Long>(), seeks)

        resume.onMediaState(RecordingId(7), durationMillis = 3_600_000, isSeekable = true)
        assertEquals(listOf(180_000L), seeks)
    }

    @Test
    fun `a new target replaces stale resume work`() {
        val seeks = mutableListOf<Long>()
        val resume = RecordingResumeStateMachine(seeks::add)

        resume.beginPlaybackTarget(RecordingId(7), positionMillis = 180_000)
        resume.beginPlaybackTarget(RecordingId(8), positionMillis = 240_000)
        resume.onMediaState(RecordingId(7), durationMillis = 3_600_000, isSeekable = true)
        resume.onMediaState(RecordingId(8), durationMillis = 3_600_000, isSeekable = true)

        assertEquals(listOf(240_000L), seeks)
    }

    @Test
    fun `start over and a position outside known media do not seek`() {
        val seeks = mutableListOf<Long>()
        val resume = RecordingResumeStateMachine(seeks::add)
        val recording = RecordingId(7)

        resume.beginPlaybackTarget(recording, positionMillis = null)
        resume.onMediaState(recording, durationMillis = 3_600_000, isSeekable = true)
        resume.beginPlaybackTarget(recording, positionMillis = 0)
        resume.onMediaState(recording, durationMillis = 3_600_000, isSeekable = true)
        resume.beginPlaybackTarget(recording, positionMillis = 3_600_000)
        resume.onMediaState(recording, durationMillis = 3_600_000, isSeekable = true)

        assertEquals(emptyList<Long>(), seeks)
    }

    @Test
    fun `close clears pending resume and rejects reuse`() {
        val seeks = mutableListOf<Long>()
        val resume = RecordingResumeStateMachine(seeks::add)
        val recording = RecordingId(7)

        resume.beginPlaybackTarget(recording, positionMillis = 180_000)
        resume.close()
        resume.onMediaState(recording, durationMillis = 3_600_000, isSeekable = true)

        assertEquals(emptyList<Long>(), seeks)
        assertThrows(IllegalStateException::class.java) {
            resume.beginPlaybackTarget(recording, positionMillis = 180_000)
        }
    }
}
