@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class RecordingResumeStateMachineTest {
    @Test
    fun `MP4 and MKV resume waits for a known seekable timeline and seeks exactly once`() {
        val seeks = mutableListOf<Long>()
        val resume = RecordingResumeStateMachine(seeks::add)
        resume.beginPlaybackTarget(positionMillis = 180_000)
        resume.onMediaState(targetMatches = true, durationMillis = null, isSeekable = false)
        assertEquals(emptyList<Long>(), seeks)

        resume.onMediaState(targetMatches = true, durationMillis = 3_600_000, isSeekable = false)
        assertEquals(emptyList<Long>(), seeks)

        resume.onMediaState(targetMatches = true, durationMillis = 3_600_000, isSeekable = true)
        resume.onMediaState(targetMatches = true, durationMillis = 3_600_000, isSeekable = true)

        assertEquals(listOf(180_000L), seeks)
    }

    @Test
    fun `a different installed target cannot consume the pending resume`() {
        val seeks = mutableListOf<Long>()
        val resume = RecordingResumeStateMachine(seeks::add)

        resume.beginPlaybackTarget(positionMillis = 180_000)
        resume.onMediaState(targetMatches = false, durationMillis = 3_600_000, isSeekable = true)
        assertEquals(emptyList<Long>(), seeks)

        resume.onMediaState(targetMatches = true, durationMillis = 3_600_000, isSeekable = true)
        assertEquals(listOf(180_000L), seeks)
    }

    @Test
    fun `a new target replaces stale resume work`() {
        val seeks = mutableListOf<Long>()
        val resume = RecordingResumeStateMachine(seeks::add)

        resume.beginPlaybackTarget(positionMillis = 180_000)
        resume.beginPlaybackTarget(positionMillis = 240_000)
        resume.onMediaState(targetMatches = false, durationMillis = 3_600_000, isSeekable = true)
        resume.onMediaState(targetMatches = true, durationMillis = 3_600_000, isSeekable = true)

        assertEquals(listOf(240_000L), seeks)
    }

    @Test
    fun `start over and a position outside known media do not seek`() {
        val seeks = mutableListOf<Long>()
        val resume = RecordingResumeStateMachine(seeks::add)
        resume.beginPlaybackTarget(positionMillis = null)
        resume.onMediaState(targetMatches = true, durationMillis = 3_600_000, isSeekable = true)
        resume.beginPlaybackTarget(positionMillis = 0)
        resume.onMediaState(targetMatches = true, durationMillis = 3_600_000, isSeekable = true)
        resume.beginPlaybackTarget(positionMillis = 3_600_000)
        resume.onMediaState(targetMatches = true, durationMillis = 3_600_000, isSeekable = true)

        assertEquals(emptyList<Long>(), seeks)
    }

    @Test
    fun `close clears pending resume and rejects reuse`() {
        val seeks = mutableListOf<Long>()
        val resume = RecordingResumeStateMachine(seeks::add)
        resume.beginPlaybackTarget(positionMillis = 180_000)
        resume.close()
        resume.onMediaState(targetMatches = true, durationMillis = 3_600_000, isSeekable = true)

        assertEquals(emptyList<Long>(), seeks)
        assertThrows(IllegalStateException::class.java) {
            resume.beginPlaybackTarget(positionMillis = 180_000)
        }
    }
}
