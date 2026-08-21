package at.bernhardberger.tvheadend.sdk.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal class DvrProgressTest {
    @Test
    fun `progress values validate whole seconds and redact rendering`() {
        val progress = DvrPlaybackProgress.checkpoint(90.seconds)
        assertEquals(90.seconds, progress.position)
        assertFalse(progress.markWatched)
        assertEquals("DvrPlaybackProgress(<redacted>)", progress.toString())
        assertThrows(IllegalArgumentException::class.java) {
            DvrPlaybackProgress(position = 1500.milliseconds)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DvrPlaybackProgress(position = (-1).seconds)
        }
    }

    @Test
    fun `resume is offered only for completed recordings past the floor and not finished`() {
        val policy = DvrProgressPolicy()
        val duration = 200.minutes
        assertSame(
            DvrResumeOffer.StartOver,
            policy.resumeOffer(entry(DvrEntryState.RECORDING, 10.minutes, duration)),
        )
        assertSame(
            DvrResumeOffer.StartOver,
            policy.resumeOffer(entry(DvrEntryState.COMPLETED, 179.seconds, duration)),
        )
        val offered = policy.resumeOffer(entry(DvrEntryState.COMPLETED, 180.seconds, duration))
        assertEquals(DvrResumeOffer.Resume(180.seconds).toString(), offered.toString())
        assertEquals(180.seconds, (offered as DvrResumeOffer.Resume).position)
        assertFalse(offered.toString().contains("180"))
        assertSame(
            DvrResumeOffer.StartOver,
            policy.resumeOffer(entry(DvrEntryState.COMPLETED, 190.minutes, duration)),
        )
        assertSame(
            DvrResumeOffer.StartOver,
            policy.resumeOffer(entry(DvrEntryState.COMPLETED, 5.minutes, 10.minutes)),
        )
        assertEquals(
            180.seconds,
            (policy.resumeOffer(entry(DvrEntryState.COMPLETED, 180.seconds, 10.minutes))
                as DvrResumeOffer.Resume).position,
        )
        assertSame(
            DvrResumeOffer.StartOver,
            policy.resumeOffer(entry(DvrEntryState.COMPLETED, 180.seconds, 4.minutes)),
        )
        assertEquals(
            DvrResumeOffer.Resume(180.seconds),
            policy.resumeOffer(
                DvrEntry.create(
                    id = DvrEntryId(1),
                    state = DvrEntryState.COMPLETED,
                    playPosition = 180.seconds,
                ),
            ),
        )
    }

    @Test
    fun `tracker checkpoints on interval and minimum delta then debounces seeks`() {
        val tracker = DvrProgressPolicy().tracker()
        val origin = Instant.fromEpochSeconds(0)
        assertNull(tracker.onElapsed(origin, 0.seconds))
        assertNull(tracker.onElapsed(Instant.fromEpochSeconds(29), 29.seconds))
        assertNull(tracker.onElapsed(Instant.fromEpochSeconds(30), 9.seconds))
        assertEquals(
            DvrPlaybackProgress.checkpoint(10.seconds),
            tracker.onElapsed(Instant.fromEpochSeconds(30), 10.seconds),
        )

        tracker.onSeek(Instant.fromEpochSeconds(31))
        assertNull(tracker.onElapsed(Instant.fromEpochSeconds(32), 50.seconds))
        assertEquals(
            DvrPlaybackProgress.checkpoint(51.seconds),
            tracker.onElapsed(Instant.fromEpochSeconds(33), 51.seconds),
        )

        tracker.onSeek(Instant.fromEpochSeconds(34))
        tracker.onSeek(Instant.fromEpochSeconds(35))
        assertNull(tracker.onElapsed(Instant.fromEpochSeconds(36), 80.seconds))
        assertEquals(
            DvrPlaybackProgress.checkpoint(80.seconds),
            tracker.onElapsed(Instant.fromEpochSeconds(37), 80.seconds),
        )
    }

    @Test
    fun `close always reports and marks watched at the finished threshold`() {
        val policy = DvrProgressPolicy()
        assertEquals(
            DvrPlaybackProgress(189.minutes, markWatched = false),
            policy.closeProgress(189.minutes, 200.minutes),
        )
        assertEquals(
            DvrPlaybackProgress(190.minutes, markWatched = true),
            DvrPlaybackProgress.close(190.minutes, 200.minutes),
        )
        val tracker = policy.tracker()
        tracker.onElapsed(Instant.fromEpochSeconds(0), 0.seconds)
        assertEquals(
            DvrPlaybackProgress(12.seconds, markWatched = false),
            tracker.close(12.seconds, 40.minutes),
        )
        assertNull(tracker.onElapsed(Instant.fromEpochSeconds(30), 30.seconds))
    }

    private fun entry(
        state: DvrEntryState,
        position: Duration,
        duration: Duration,
    ): DvrEntry = DvrEntry.create(
        id = DvrEntryId(1),
        start = Instant.fromEpochSeconds(0),
        stop = Instant.fromEpochSeconds(duration.inWholeSeconds),
        playPosition = position,
        state = state,
    )
}
