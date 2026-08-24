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
    fun `resume offers every positive completed position without scheduled duration heuristics`() {
        val policy = DvrProgressPolicy()
        assertSame(
            DvrResumeOffer.StartOver,
            policy.resumeOffer(entry(DvrEntryState.RECORDING, 1.seconds)),
        )
        assertSame(
            DvrResumeOffer.StartOver,
            policy.resumeOffer(entry(DvrEntryState.COMPLETED, null)),
        )
        assertSame(
            DvrResumeOffer.StartOver,
            policy.resumeOffer(entry(DvrEntryState.COMPLETED, Duration.ZERO)),
        )
        val oneSecond = policy.resumeOffer(entry(DvrEntryState.COMPLETED, 1.seconds))
        assertEquals(1.seconds, (oneSecond as DvrResumeOffer.Resume).position)
        assertFalse(oneSecond.toString().contains("1"))
        assertEquals(
            DvrResumeOffer.Resume(200.minutes),
            policy.resumeOffer(entry(DvrEntryState.COMPLETED, 200.minutes, 1.minutes)),
        )
    }

    @Test
    fun `tracker uses one elapsed cadence and any positive movement`() {
        val tracker = DvrProgressPolicy().tracker()
        val origin = Instant.fromEpochSeconds(0)
        assertNull(tracker.onElapsed(origin, 0.seconds))
        assertNull(tracker.onElapsed(Instant.fromEpochSeconds(29), 20.seconds))
        assertEquals(
            DvrPlaybackProgress.checkpoint(1.seconds),
            tracker.onElapsed(Instant.fromEpochSeconds(30), 1.seconds),
        )
        assertNull(tracker.onElapsed(Instant.fromEpochSeconds(60), 1.seconds))
        assertNull(tracker.onElapsed(Instant.fromEpochSeconds(90), 500.milliseconds))
        assertEquals(
            DvrPlaybackProgress.checkpoint(0.seconds),
            tracker.onElapsed(Instant.fromEpochSeconds(120), 600.milliseconds),
        )
    }

    @Test
    fun `pause and terminal observations are never suppressed and reset cadence`() {
        val tracker = DvrProgressPolicy().tracker()
        tracker.onElapsed(Instant.fromEpochSeconds(0), 0.seconds)
        assertEquals(
            DvrPlaybackProgress.checkpoint(5.seconds),
            tracker.onPause(Instant.fromEpochSeconds(5), 5_900.milliseconds),
        )
        assertNull(tracker.onElapsed(Instant.fromEpochSeconds(34), 20.seconds))
        assertEquals(
            DvrPlaybackProgress(6.seconds, markWatched = true),
            tracker.onTerminal(
                position = 6.seconds,
                duration = null,
                state = DvrEntryState.COMPLETED,
                exit = DvrPlaybackExit.NATURAL_END,
            ),
        )
        assertNull(tracker.onElapsed(Instant.fromEpochSeconds(35), 30.seconds))
    }

    @Test
    fun `terminal completion uses actual proportional and explicit exit semantics`() {
        val policy = DvrProgressPolicy()
        assertFalse(terminal(policy, 56.seconds, 60.seconds).markWatched)
        assertTrue(terminal(policy, 57.seconds, 60.seconds).markWatched)
        assertFalse(terminal(policy, 94.minutes, 100.minutes).markWatched)
        assertTrue(terminal(policy, 95.minutes, 100.minutes).markWatched)
        assertFalse(terminal(policy, 1.minutes, 6.minutes).markWatched)
        assertFalse(terminal(policy, 95.seconds, null).markWatched)
        assertFalse(terminal(policy, 95.seconds, Duration.ZERO).markWatched)

        val fractional = terminal(policy, 9_500.milliseconds, 10.seconds)
        assertEquals(9.seconds, fractional.position)
        assertTrue(fractional.markWatched)
        assertTrue(
            policy.terminalProgress(
                1.seconds,
                null,
                DvrEntryState.COMPLETED,
                DvrPlaybackExit.NATURAL_END,
            ).markWatched,
        )
        assertFalse(
            policy.terminalProgress(
                100.seconds,
                100.seconds,
                DvrEntryState.COMPLETED,
                DvrPlaybackExit.ERROR,
            ).markWatched,
        )
        assertFalse(
            policy.terminalProgress(
                100.seconds,
                100.seconds,
                DvrEntryState.RECORDING,
                DvrPlaybackExit.NATURAL_END,
            ).markWatched,
        )
        assertFalse(
            policy.terminalProgress(
                100.seconds,
                100.seconds,
                state = null,
                exit = DvrPlaybackExit.NATURAL_END,
            ).markWatched,
        )
    }

    @Test
    fun `policy and tracker reject invalid observations`() {
        assertThrows(IllegalArgumentException::class.java) {
            DvrProgressPolicy(checkpointInterval = Duration.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DvrProgressPolicy(orderlyCompletionFraction = Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DvrProgressPolicy().tracker().onElapsed(Instant.fromEpochSeconds(0), (-1).seconds)
        }
    }

    private fun entry(
        state: DvrEntryState,
        position: Duration?,
        scheduledDuration: Duration? = null,
    ): DvrEntry = DvrEntry.create(
        id = DvrEntryId(1),
        start = scheduledDuration?.let { Instant.fromEpochSeconds(0) },
        stop = scheduledDuration?.let { Instant.fromEpochSeconds(it.inWholeSeconds) },
        playPosition = position,
        state = state,
    )

    private fun terminal(
        policy: DvrProgressPolicy,
        position: Duration,
        duration: Duration?,
    ): DvrPlaybackProgress = policy.terminalProgress(
        position = position,
        duration = duration,
        state = DvrEntryState.COMPLETED,
        exit = DvrPlaybackExit.ORDERLY,
    )
}
