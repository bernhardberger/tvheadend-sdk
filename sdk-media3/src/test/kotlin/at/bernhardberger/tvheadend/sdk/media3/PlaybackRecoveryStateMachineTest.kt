@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackRecoveryStateMachineTest {
    @Test
    fun `paused seek never disables audio or requests retune and resume restores recovery`() {
        val harness = RecoveryHarness()
        harness.begin()
        harness.playback(Player.STATE_READY)
        harness.machine.onPlayWhenReadyChanged(false)
        harness.playback(Player.STATE_BUFFERING)
        harness.machine.onTracksChanged()

        assertTrue(harness.scheduler.activeDelays().isEmpty())
        harness.scheduler.runAllIncludingCancelled()
        assertFalse(harness.audioDisabled)
        assertTrue(harness.reasons.isEmpty())

        harness.machine.onPlayWhenReadyChanged(true)
        assertEquals(listOf(6_000L), harness.scheduler.activeDelays())
        harness.scheduler.runNextActive()
        harness.scheduler.runNextActive()
        assertEquals(listOf(PlaybackRecoveryReason.AUDIO_RECOVERY_EXHAUSTED), harness.reasons)
    }

    @Test
    fun `pause cancels an already armed buffering timeout including stale callbacks`() {
        val harness = RecoveryHarness()
        harness.begin()
        harness.playback(Player.STATE_BUFFERING)
        val stale = harness.scheduler.lastScheduled()
        harness.machine.onPlayWhenReadyChanged(false)
        stale.runEvenIfCancelled()
        harness.machine.onTracksChanged()
        assertTrue(harness.scheduler.activeDelays().isEmpty())
        assertFalse(harness.audioDisabled)
        assertTrue(harness.reasons.isEmpty())
    }

    @Test
    fun `policy defaults to two six second recovery stages`() {
        val policy = PlaybackRecoveryPolicy()

        assertEquals(6_000L, policy.initialBufferingDurationMillis)
        assertEquals(6_000L, policy.postAudioDisableDurationMillis)
        assertEquals(20_000L, policy.preparationDurationMillis)
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackRecoveryPolicy(initialBufferingDurationMillis = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackRecoveryPolicy(postAudioDisableDurationMillis = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackRecoveryPolicy(preparationDurationMillis = 0L)
        }
    }

    @Test
    fun `a target that never presented audio buffers on the preparation budget`() {
        val harness = RecoveryHarness()
        harness.selectedAudio = false
        harness.begin()

        harness.playback(Player.STATE_BUFFERING)

        assertEquals(listOf(20_000L), harness.scheduler.activeDelays())
        assertTrue(harness.reasons.isEmpty())
    }

    @Test
    fun `preparation budget does not restart while the target keeps buffering`() {
        val harness = RecoveryHarness()
        harness.selectedAudio = false
        harness.begin()

        harness.playback(Player.STATE_BUFFERING)
        val original = harness.scheduler.lastScheduled()
        harness.machine.onTracksChanged()
        harness.playback(Player.STATE_BUFFERING)

        // The original deadline must survive: rescheduling an equal delay would silently restore
        // the unbounded timer this stage exists to prevent.
        assertSame(original, harness.scheduler.lastScheduled())
        assertFalse(original.cancelled)
        assertEquals(1, harness.scheduler.scheduledCount())
    }

    @Test
    fun `audio selected while ready keeps the short budget for a later stall`() {
        val harness = RecoveryHarness()
        harness.selectedAudio = false
        harness.begin()
        harness.playback(Player.STATE_BUFFERING)

        harness.selectedAudio = true
        harness.playback(Player.STATE_READY)
        harness.selectedAudio = false
        harness.playback(Player.STATE_BUFFERING)

        assertEquals(listOf(6_000L), harness.scheduler.activeDelays())
    }

    @Test
    fun `initial timer survives audio arrival then second timeout escalates once`() {
        val harness = RecoveryHarness()
        harness.selectedAudio = false
        harness.begin()

        harness.playback(Player.STATE_BUFFERING)
        assertEquals(listOf(20_000L), harness.scheduler.activeDelays())
        assertFalse(harness.audioDisabled)

        harness.selectedAudio = true
        harness.machine.onTracksChanged()
        assertEquals(listOf(6_000L), harness.scheduler.activeDelays())

        harness.scheduler.runNextActive()
        assertTrue(harness.audioDisabled)
        assertEquals(listOf(6_000L), harness.scheduler.activeDelays())
        assertEquals(emptyList<PlaybackRecoveryReason>(), harness.reasons)

        harness.scheduler.runNextActive()
        assertEquals(listOf(PlaybackRecoveryReason.AUDIO_RECOVERY_EXHAUSTED), harness.reasons)
        harness.machine.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(1, harness.reasons.size)
    }

    @Test
    fun `ready and idle before initial timeout cancel no-audio recovery`() {
        val harness = RecoveryHarness()
        harness.selectedAudio = false
        harness.begin()
        harness.playback(Player.STATE_BUFFERING)

        harness.playback(Player.STATE_READY)
        harness.scheduler.runAllIncludingCancelled()

        harness.playback(Player.STATE_BUFFERING)
        harness.playback(Player.STATE_IDLE)
        harness.scheduler.runAllIncludingCancelled()

        assertFalse(harness.audioDisabled)
        assertTrue(harness.reasons.isEmpty())
    }

    @Test
    fun `audio disappearing before initial timeout still escalates once`() {
        val harness = RecoveryHarness()
        harness.begin()
        harness.playback(Player.STATE_BUFFERING)

        harness.selectedAudio = false
        harness.machine.onTracksChanged()
        assertEquals(listOf(6_000L), harness.scheduler.activeDelays())
        harness.scheduler.runNextActive()

        assertFalse(harness.audioDisabled)
        assertEquals(listOf(PlaybackRecoveryReason.AUDIO_RECOVERY_EXHAUSTED), harness.reasons)
    }

    @Test
    fun `buffering without selected audio escalates exactly once`() {
        val harness = RecoveryHarness()
        harness.selectedAudio = false
        harness.begin()
        harness.playback(Player.STATE_BUFFERING)
        val timeout = harness.scheduler.lastScheduled()

        timeout.runEvenIfCancelled()
        timeout.runEvenIfCancelled()
        harness.machine.onTracksChanged()
        harness.playback(Player.STATE_ENDED)

        assertFalse(harness.audioDisabled)
        assertEquals(listOf(PlaybackRecoveryReason.AUDIO_RECOVERY_EXHAUSTED), harness.reasons)
    }

    @Test
    fun `rebuffering after audio was presented keeps the short stuck budget`() {
        val harness = RecoveryHarness()
        harness.begin()
        harness.playback(Player.STATE_BUFFERING)
        harness.playback(Player.STATE_READY)

        harness.selectedAudio = false
        harness.playback(Player.STATE_BUFFERING)

        assertEquals(listOf(6_000L), harness.scheduler.activeDelays())
    }

    @Test
    fun `a new target returns to the preparation budget`() {
        val harness = RecoveryHarness()
        harness.begin()
        harness.playback(Player.STATE_BUFFERING)
        harness.playback(Player.STATE_READY)

        harness.selectedAudio = false
        harness.begin()
        harness.playback(Player.STATE_BUFFERING)

        assertEquals(listOf(20_000L), harness.scheduler.activeDelays())
    }

    @Test
    fun `ready after a timeshift stall restores audio and cancels stale escalation`() {
        val harness = RecoveryHarness()
        harness.begin()
        harness.playback(Player.STATE_READY)
        harness.playback(Player.STATE_BUFFERING)
        harness.scheduler.runNextActive()
        assertTrue(harness.audioDisabled)

        harness.playback(Player.STATE_READY)
        harness.scheduler.runAllIncludingCancelled()

        assertFalse(harness.audioDisabled)
        assertEquals(listOf(false, true, false), harness.audioDisabledChanges)
        assertTrue(harness.reasons.isEmpty())
    }

    @Test
    fun `buffering after audio restoration escalates without another disable cycle`() {
        val harness = RecoveryHarness()
        harness.begin()
        harness.playback(Player.STATE_BUFFERING)
        harness.scheduler.runNextActive()
        harness.playback(Player.STATE_READY)

        harness.playback(Player.STATE_BUFFERING)
        harness.machine.onTracksChanged()
        assertEquals(listOf(6_000L), harness.scheduler.activeDelays())
        harness.scheduler.runNextActive()
        harness.scheduler.runAllIncludingCancelled()

        assertFalse(harness.audioDisabled)
        assertEquals(listOf(false, true, false), harness.audioDisabledChanges)
        assertEquals(listOf(PlaybackRecoveryReason.AUDIO_RECOVERY_EXHAUSTED), harness.reasons)
    }

    @Test
    fun `ready while paused restores audio without arming recovery until resume`() {
        val harness = RecoveryHarness()
        harness.begin()
        harness.playback(Player.STATE_BUFFERING)
        harness.scheduler.runNextActive()
        harness.machine.onPlayWhenReadyChanged(false)

        harness.playback(Player.STATE_READY)
        harness.playback(Player.STATE_BUFFERING)
        harness.scheduler.runAllIncludingCancelled()

        assertFalse(harness.audioDisabled)
        assertTrue(harness.scheduler.activeDelays().isEmpty())
        assertTrue(harness.reasons.isEmpty())
        harness.machine.onPlayWhenReadyChanged(true)
        harness.scheduler.runNextActive()
        assertEquals(listOf(PlaybackRecoveryReason.AUDIO_RECOVERY_EXHAUSTED), harness.reasons)
    }

    @Test
    fun `idle after audio disable restores audio and cancels stale escalation`() {
        val harness = RecoveryHarness()
        harness.begin()
        harness.playback(Player.STATE_BUFFERING)
        harness.scheduler.runNextActive()

        harness.playback(Player.STATE_IDLE)
        harness.scheduler.runAllIncludingCancelled()

        assertFalse(harness.audioDisabled)
        assertEquals(listOf(false, true, false), harness.audioDisabledChanges)
        assertTrue(harness.reasons.isEmpty())
    }

    @Test
    fun `synchronous ready during audio disable leaves no timer or disabled audio`() {
        val harness = RecoveryHarness()
        harness.onAudioDisabledChanged = { disabled ->
            if (disabled) harness.playback(Player.STATE_READY)
        }
        harness.begin()
        harness.playback(Player.STATE_BUFFERING)

        harness.scheduler.runNextActive()

        assertFalse(harness.audioDisabled)
        assertEquals(listOf(false, true, false), harness.audioDisabledChanges)
        assertTrue(harness.scheduler.activeDelays().isEmpty())
        harness.scheduler.runAllIncludingCancelled()
        assertTrue(harness.reasons.isEmpty())
    }

    @Test
    fun `new target gets a fresh audio recovery attempt after the previous restoration`() {
        val harness = RecoveryHarness()
        harness.begin()
        harness.playback(Player.STATE_BUFFERING)
        harness.scheduler.runNextActive()
        val stale = harness.scheduler.lastScheduled()
        harness.playback(Player.STATE_READY)

        harness.begin()
        harness.playback(Player.STATE_BUFFERING)
        stale.runEvenIfCancelled()
        harness.scheduler.runNextActive()

        assertTrue(harness.audioDisabled)
        assertEquals(listOf(false, true, false, false, true), harness.audioDisabledChanges)
        assertTrue(harness.reasons.isEmpty())
    }

    @Test
    fun `new target re-enables audio and rejects stale timeout`() {
        val harness = RecoveryHarness()
        harness.selectedAudio = false
        harness.begin()
        harness.playback(Player.STATE_BUFFERING)
        val stale = harness.scheduler.lastScheduled()

        harness.begin()
        stale.runEvenIfCancelled()

        assertFalse(harness.audioDisabled)
        assertEquals(listOf(false, false), harness.audioDisabledChanges)
        assertTrue(harness.reasons.isEmpty())
    }

    @Test
    fun `live ended escalates immediately and only once`() {
        val harness = RecoveryHarness()
        harness.begin()

        harness.playback(Player.STATE_ENDED)
        harness.playback(Player.STATE_ENDED)

        assertEquals(listOf(PlaybackRecoveryReason.LIVE_ENDED), harness.reasons)
    }

    @Test
    fun `close cancels timers and ignores later player events`() {
        val harness = RecoveryHarness()
        harness.selectedAudio = false
        harness.begin()
        harness.playback(Player.STATE_BUFFERING)

        harness.machine.close()
        harness.scheduler.runAllIncludingCancelled()
        harness.playback(Player.STATE_ENDED)

        assertFalse(harness.audioDisabled)
        assertTrue(harness.reasons.isEmpty())
    }

    @Test
    fun `close restores audio when recovery disabled it`() {
        val harness = RecoveryHarness()
        harness.begin()
        harness.playback(Player.STATE_BUFFERING)
        harness.scheduler.runNextActive()

        harness.machine.close()

        assertFalse(harness.audioDisabled)
        assertEquals(listOf(false, true, false), harness.audioDisabledChanges)
        assertTrue(harness.reasons.isEmpty())
    }
}

private class RecoveryHarness {
    val scheduler = ManualRecoveryScheduler()
    val reasons = mutableListOf<PlaybackRecoveryReason>()
    val audioDisabledChanges = mutableListOf<Boolean>()
    var selectedAudio = true
    var audioDisabled = false
    var onAudioDisabledChanged: ((Boolean) -> Unit)? = null
    val machine = PlaybackRecoveryStateMachine(
        policy = PlaybackRecoveryPolicy(),
        scheduler = scheduler,
        hasSelectedAudio = { selectedAudio },
        setAudioDisabled = { disabled ->
            audioDisabled = disabled
            audioDisabledChanges += disabled
            onAudioDisabledChanged?.invoke(disabled)
        },
        onRecoveryRequired = reasons::add,
    )

    fun begin() {
        machine.beginPlaybackTarget()
    }

    fun playback(state: Int) {
        machine.onPlaybackStateChanged(state)
    }
}

private class ManualRecoveryScheduler : RecoveryScheduler {
    private val tasks = mutableListOf<ManualRecoveryTask>()

    override fun schedule(delayMillis: Long, action: () -> Unit): ScheduledRecoveryTask =
        ManualRecoveryTask(delayMillis, action).also(tasks::add)

    fun activeDelays(): List<Long> = tasks.filterNot(ManualRecoveryTask::cancelled).map { it.delayMillis }

    fun lastScheduled(): ManualRecoveryTask = tasks.last()

    fun scheduledCount(): Int = tasks.size

    fun runNextActive() {
        val task = tasks.firstOrNull { !it.cancelled } ?: error("No active task")
        tasks.remove(task)
        task.runEvenIfCancelled()
    }

    fun runAllIncludingCancelled() {
        while (tasks.isNotEmpty()) tasks.removeAt(0).runEvenIfCancelled()
    }
}

private class ManualRecoveryTask(
    val delayMillis: Long,
    private val action: () -> Unit,
) : ScheduledRecoveryTask {
    var cancelled = false
        private set

    override fun cancel() {
        cancelled = true
    }

    fun runEvenIfCancelled() {
        action()
    }
}
