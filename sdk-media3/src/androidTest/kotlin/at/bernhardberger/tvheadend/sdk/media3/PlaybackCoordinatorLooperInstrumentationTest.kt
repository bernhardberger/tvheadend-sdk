@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.os.Looper
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.RecordingId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class PlaybackCoordinatorLooperInstrumentationTest {
    @Test
    fun everyTargetAndRetirementOperationRunsOnTheApplicationLooper() = runBlocking {
        val access = MainLooperCoordinatorPlaybackAccess()
        val player = Media3PlaybackCoordinatorPlayer(
            access = access,
            events = PlaybackPlayerEventAccumulator(),
            admitRecording = { _, _ -> error("Recording admission is not expected") },
        )

        val install = withContext(Dispatchers.Default) {
            val token = PlaybackTargetToken()
            player.installLive(
                PlayerOperationTicket(),
                token,
                SubscriptionChannelId(4),
                timeshiftControls = LiveTimeshiftControlBridge(token) {},
            )
        }
        val stop = withContext(Dispatchers.Default) {
            player.stop(PlayerOperationTicket())
        }

        assertEquals(PlaybackPlayerInstallStatus.STARTED, install.status)
        assertFalse(stop.cancelled)
        assertEquals(
            listOf(
                "create-live",
                "add-listener",
                "create-recovery",
                "begin-recovery",
                "set-source",
                "prepare",
                "close-recovery",
                "remove-listener",
                "stop",
                "clear",
            ),
            access.operations,
        )
        access.observedLoopers.forEach { observed ->
            assertSame(Looper.getMainLooper(), observed)
        }
    }
}

private class MainLooperCoordinatorPlaybackAccess : CoordinatorPlaybackAccess {
    override val looper: CoordinatorLooper = HandlerCoordinatorLooper(Looper.getMainLooper())
    val operations = mutableListOf<String>()
    val observedLoopers = mutableListOf<Looper?>()
    private var listener: Player.Listener? = null

    override fun requireApplicationLooper() {
        observedLoopers += Looper.myLooper()
        check(Looper.myLooper() === Looper.getMainLooper())
    }

    override fun snapshot(): PlaybackPlayerSnapshot {
        requireApplicationLooper()
        return PlaybackPlayerSnapshot(Duration.ZERO, null, Player.STATE_IDLE, failed = false)
    }

    override fun addListener(listener: Player.Listener) {
        requireApplicationLooper()
        operations += "add-listener"
        this.listener = listener
    }

    override fun removeListener(listener: Player.Listener) {
        requireApplicationLooper()
        operations += "remove-listener"
        if (this.listener === listener) this.listener = null
    }

    override fun createLiveSource(
        channelId: SubscriptionChannelId,
        options: SubscriptionOptions,
        timeshiftControls: LiveTimeshiftControlBridge,
    ): CoordinatorMediaSource {
        requireApplicationLooper()
        check(options.streamProfileUuid == null && options.timeshiftPeriod == Duration.ZERO)
        operations += "create-live"
        return MainLooperMediaSource
    }

    override fun createRecordingSource(recordingId: RecordingId): CoordinatorMediaSource =
        error("Recording source is not expected")

    override fun createGrowingRecordingSource(
        recordingId: RecordingId,
        lease: GrowingRecordingFileLease,
        onFinalEnd: () -> Unit,
    ): CoordinatorMediaSource = error("Growing recording source is not expected")

    override fun createRecovery(
        onRecoveryRequired: (PlaybackRecoveryReason) -> Unit,
    ): CoordinatorPlaybackRecovery {
        requireApplicationLooper()
        operations += "create-recovery"
        return object : CoordinatorPlaybackRecovery {
            override fun beginPlaybackTarget() {
                requireApplicationLooper()
                operations += "begin-recovery"
            }

            override fun close() {
                requireApplicationLooper()
                operations += "close-recovery"
            }
        }
    }

    override fun createResume(): CoordinatorRecordingResume = error("Recording resume is not expected")

    override fun setMediaSource(source: CoordinatorMediaSource) {
        requireApplicationLooper()
        operations += "set-source"
    }

    override fun prepare() {
        requireApplicationLooper()
        operations += "prepare"
    }

    override fun stop() {
        requireApplicationLooper()
        operations += "stop"
    }

    override fun clearMediaItems() {
        requireApplicationLooper()
        operations += "clear"
    }

    private data object MainLooperMediaSource : CoordinatorMediaSource
}
