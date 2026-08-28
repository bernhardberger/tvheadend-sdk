@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackRecoveryStateMachineTest {
    @Test
    fun `policy defaults to two six second recovery stages`() {
        val policy = PlaybackRecoveryPolicy()

        assertEquals(6_000L, policy.initialBufferingDurationMillis)
        assertEquals(6_000L, policy.postAudioDisableDurationMillis)
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackRecoveryPolicy(initialBufferingDurationMillis = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackRecoveryPolicy(postAudioDisableDurationMillis = -1L)
        }
    }

    @Test
    fun `initial timer survives audio arrival then second timeout escalates once`() {
        val harness = RecoveryHarness()
        harness.selectedAudio = false
        harness.begin()

        harness.playback(Player.STATE_BUFFERING)
        assertEquals(listOf(6_000L), harness.scheduler.activeDelays())
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
    fun `ready after audio disable cancels escalation`() {
        val harness = RecoveryHarness()
        harness.begin()
        harness.playback(Player.STATE_BUFFERING)
        harness.scheduler.runNextActive()

        harness.playback(Player.STATE_READY)
        harness.scheduler.runAllIncludingCancelled()

        assertTrue(harness.audioDisabled)
        assertTrue(harness.reasons.isEmpty())
    }

    @Test
    fun `buffering again after policy disables audio resumes second stage`() {
        val harness = RecoveryHarness()
        harness.begin()
        harness.playback(Player.STATE_BUFFERING)
        harness.scheduler.runNextActive()
        harness.playback(Player.STATE_READY)

        harness.playback(Player.STATE_BUFFERING)
        harness.scheduler.runNextActive()

        assertEquals(listOf(PlaybackRecoveryReason.AUDIO_RECOVERY_EXHAUSTED), harness.reasons)
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
    val machine = PlaybackRecoveryStateMachine(
        policy = PlaybackRecoveryPolicy(),
        scheduler = scheduler,
        hasSelectedAudio = { selectedAudio },
        setAudioDisabled = { disabled ->
            audioDisabled = disabled
            audioDisabledChanges += disabled
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
