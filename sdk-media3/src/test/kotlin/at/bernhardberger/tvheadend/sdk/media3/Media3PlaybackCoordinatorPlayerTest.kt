@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrRecordingFile
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSnapshot
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.RecordingId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class Media3PlaybackCoordinatorPlayerTest {
    @Test
    fun `live and recording transitions use exact helper order and never release player`() = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ ->
            access.operations += "admit-recording"
            RecordingAdmission.Completed(12.seconds)
        }

        val live = async {
            player.installLive(
                PlayerOperationTicket(),
                PlaybackTargetToken(),
                SubscriptionChannelId(3),
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()
        assertEquals(PlaybackPlayerInstallStatus.STARTED, live.await().status)
        assertEquals(
            listOf(
                "create-live",
                "add-listener",
                "create-recovery",
                "begin-recovery",
                "set-live",
                "prepare",
            ),
            access.operations,
        )

        access.operations.clear()
        val recording = async {
            player.installRecording(
                PlayerOperationTicket(),
                PlaybackTargetToken(),
                RecordingId(9),
                RecordingPlaybackStart.RESUME,
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()

        assertEquals(PlaybackPlayerInstallStatus.STARTED, recording.await().status)
        assertEquals(
            listOf(
                "admit-recording",
                "create-recording",
                "close-recovery",
                "remove-listener",
                "stop",
                "clear",
                "add-listener",
                "set-recording",
                "create-resume",
                "begin-resume:12",
                "prepare",
            ),
            access.operations,
        )
        assertFalse(access.operations.contains("release"))
    }

    @Test
    fun `growing recording uses its source without resume and forwards final EOF proof`() = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val events = PlaybackPlayerEventAccumulator()
        val admission = growingAdmission()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> admission }
        val token = PlaybackTargetToken()

        val result = async {
            player.installRecording(
                PlayerOperationTicket(),
                token,
                RecordingId(9),
                RecordingPlaybackStart.START_OVER,
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()

        assertEquals(PlaybackPlayerInstallStatus.STARTED, result.await().status)
        assertEquals(
            listOf("create-growing", "add-listener", "set-growing", "prepare"),
            access.operations,
        )
        assertSame(admission.lease, access.growingLease)
        access.growingFinalEndCallback?.invoke()
        access.snapshot = access.snapshot.copy(
            position = 100.seconds,
            duration = 100.seconds,
            playbackState = Player.STATE_ENDED,
        )
        access.looperQueue.runOnLooper {
            access.applicationListener?.onPlaybackStateChanged(Player.STATE_ENDED)
        }

        val terminal = events.take()
        assertSame(at.bernhardberger.tvheadend.sdk.core.DvrPlaybackExit.NATURAL_END, terminal?.terminalExit)
        assertTrue(requireNotNull(terminal).growingFinalEndProven)
    }

    @Test
    fun `growing resume refusal preserves the installed target`() = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        var admission: RecordingAdmission = RecordingAdmission.Completed(null)
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
            admission
        }
        val liveToken = PlaybackTargetToken()
        val live = async {
            player.installLive(PlayerOperationTicket(), liveToken, SubscriptionChannelId(3))
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()
        live.await()
        access.operations.clear()
        admission = RecordingAdmission.GrowingResumeUnsupported

        val recording = async {
            player.installRecording(
                PlayerOperationTicket(),
                PlaybackTargetToken(),
                RecordingId(9),
                RecordingPlaybackStart.RESUME,
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()

        assertEquals(
            PlaybackPlayerInstallStatus.GROWING_RECORDING_RESUME_UNSUPPORTED,
            recording.await().status,
        )
        assertTrue(access.operations.isEmpty())
        assertTrue(liveToken.isActive())
    }

    @Test
    fun `recording refusal happens before source creation and preserves current target`() = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
            access.operations += "admit-recording"
            RecordingAdmission.ProgressUnsupported
        }
        val liveToken = PlaybackTargetToken()
        val live = async {
            player.installLive(PlayerOperationTicket(), liveToken, SubscriptionChannelId(3))
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()
        live.await()
        access.operations.clear()

        val recording = async {
            player.installRecording(
                PlayerOperationTicket(),
                PlaybackTargetToken(),
                RecordingId(9),
                RecordingPlaybackStart.RESUME,
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()

        assertEquals(
            PlaybackPlayerInstallStatus.RECORDING_PROGRESS_UNSUPPORTED,
            recording.await().status,
        )
        assertEquals(listOf("admit-recording"), access.operations)
        assertTrue(liveToken.isActive())
    }

    @Test
    fun `checked admission failure completes without mutating the player`() = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
            throw IOException("scripted")
        }
        val result = async {
            player.installRecording(
                PlayerOperationTicket(),
                PlaybackTargetToken(),
                RecordingId(9),
                RecordingPlaybackStart.RESUME,
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()

        assertEquals(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE, result.await().status)
        assertEquals(emptyList<String>(), access.operations)
    }

    @Test
    fun `queued target cancellation performs no player or factory work`() = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
            error("Admission must not run")
        }
        val ticket = PlayerOperationTicket()
        val result = async {
            player.installRecording(
                ticket,
                PlaybackTargetToken(),
                RecordingId(9),
                RecordingPlaybackStart.RESUME,
            )
        }
        runCurrent()

        assertTrue(ticket.cancel())
        access.looperQueue.runAll()
        runCurrent()

        assertEquals(PlaybackPlayerInstallStatus.CANCELLED, result.await().status)
        assertEquals(emptyList<String>(), access.operations)
    }

    @Test
    fun `failed source installation rolls back listeners helpers and media`() = runTest {
        val access = FakeCoordinatorPlaybackAccess(failSetMediaSource = true)
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
            RecordingAdmission.Completed(null)
        }
        val token = PlaybackTargetToken()
        val result = async {
            player.installRecording(
                PlayerOperationTicket(),
                token,
                RecordingId(9),
                RecordingPlaybackStart.START_OVER,
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()

        assertEquals(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE, result.await().status)
        assertFalse(token.isActive())
        assertEquals(
            listOf(
                "create-recording",
                "add-listener",
                "set-recording",
                "remove-listener",
                "stop",
                "clear",
            ),
            access.operations,
        )
    }

    @Test
    fun `callbacks capture explicit pause and recording terminals but not buffering`() = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ ->
            RecordingAdmission.Completed(null)
        }
        val token = PlaybackTargetToken()
        val result = async {
            player.installRecording(
                PlayerOperationTicket(),
                token,
                RecordingId(9),
                RecordingPlaybackStart.START_OVER,
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()
        result.await()
        val listener = access.applicationListener

        access.looperQueue.runOnLooper { listener?.onPlaybackStateChanged(Player.STATE_BUFFERING) }
        assertNull(events.take())

        access.looperQueue.runOnLooper {
            listener?.onPlayWhenReadyChanged(
                false,
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            )
        }
        assertNull(events.take())

        access.snapshot = access.snapshot.copy(position = 20.seconds)
        access.looperQueue.runOnLooper {
            listener?.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        }
        val pause = events.take()
        assertTrue(requireNotNull(pause).paused)
        assertNull(pause.terminalExit)

        access.snapshot = access.snapshot.copy(
            position = 100.seconds,
            duration = 100.seconds,
            playbackState = Player.STATE_ENDED,
        )
        access.looperQueue.runOnLooper { listener?.onPlaybackStateChanged(Player.STATE_ENDED) }
        assertSame(at.bernhardberger.tvheadend.sdk.core.DvrPlaybackExit.NATURAL_END, events.take()?.terminalExit)

        access.snapshot = access.snapshot.copy(failed = true)
        access.looperQueue.runOnLooper {
            listener?.onPlayerError(PlaybackException("safe", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
        }
        assertSame(at.bernhardberger.tvheadend.sdk.core.DvrPlaybackExit.ERROR, events.take()?.terminalExit)
    }

    @Test
    fun `retirement fences stale player and recovery callbacks by token`() = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ ->
            RecordingAdmission.Completed(null)
        }
        val liveToken = PlaybackTargetToken()
        val live = async {
            player.installLive(PlayerOperationTicket(), liveToken, SubscriptionChannelId(3))
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()
        live.await()
        val staleListener = access.applicationListener
        val staleRecovery = access.recoveryCallback

        access.looperQueue.runOnLooper {
            staleRecovery?.invoke(PlaybackRecoveryReason.LIVE_ENDED)
        }
        assertSame(PlaybackRecoveryReason.LIVE_ENDED, events.take()?.recoveryReason)

        val recording = async {
            player.installRecording(
                PlayerOperationTicket(),
                PlaybackTargetToken(),
                RecordingId(9),
                RecordingPlaybackStart.START_OVER,
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()
        recording.await()

        access.looperQueue.runOnLooper {
            staleListener?.onPlayWhenReadyChanged(
                false,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            staleRecovery?.invoke(PlaybackRecoveryReason.LIVE_ENDED)
        }
        assertNull(events.take())
        assertFalse(liveToken.isActive())
    }
}

private class FakeCoordinatorPlaybackAccess(
    private val failSetMediaSource: Boolean = false,
) : CoordinatorPlaybackAccess {
    val looperQueue = TestCoordinatorLooper()
    override val looper: CoordinatorLooper = looperQueue
    val operations = mutableListOf<String>()
    var snapshot = PlaybackPlayerSnapshot(Duration.ZERO, null, Player.STATE_IDLE, failed = false)
    var applicationListener: Player.Listener? = null
    var recoveryCallback: ((PlaybackRecoveryReason) -> Unit)? = null
    var growingFinalEndCallback: (() -> Unit)? = null
    var growingLease: GrowingRecordingFileLease? = null
    private var sourceKind = "none"

    override fun requireApplicationLooper() {
        check(looperQueue.isCurrent())
    }

    override fun snapshot(): PlaybackPlayerSnapshot {
        requireApplicationLooper()
        return snapshot
    }

    override fun addListener(listener: Player.Listener) {
        requireApplicationLooper()
        operations += "add-listener"
        applicationListener = listener
    }

    override fun removeListener(listener: Player.Listener) {
        requireApplicationLooper()
        operations += "remove-listener"
        if (applicationListener === listener) applicationListener = null
    }

    override fun createLiveSource(channelId: SubscriptionChannelId): CoordinatorMediaSource {
        requireApplicationLooper()
        operations += "create-live"
        return FakeCoordinatorMediaSource("live")
    }

    override fun createRecordingSource(recordingId: RecordingId): CoordinatorMediaSource {
        requireApplicationLooper()
        operations += "create-recording"
        return FakeCoordinatorMediaSource("recording")
    }

    override fun createGrowingRecordingSource(
        recordingId: RecordingId,
        lease: GrowingRecordingFileLease,
        onFinalEnd: () -> Unit,
    ): CoordinatorMediaSource {
        requireApplicationLooper()
        operations += "create-growing"
        growingLease = lease
        growingFinalEndCallback = onFinalEnd
        return FakeCoordinatorMediaSource("growing")
    }

    override fun createRecovery(
        onRecoveryRequired: (PlaybackRecoveryReason) -> Unit,
    ): CoordinatorPlaybackRecovery {
        requireApplicationLooper()
        operations += "create-recovery"
        recoveryCallback = onRecoveryRequired
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

    override fun createResume(): CoordinatorRecordingResume {
        requireApplicationLooper()
        operations += "create-resume"
        return object : CoordinatorRecordingResume {
            override fun beginPlaybackTarget(recordingId: RecordingId, position: Duration?) {
                requireApplicationLooper()
                operations += "begin-resume:${position?.inWholeSeconds ?: 0}"
            }

            override fun close() {
                requireApplicationLooper()
                operations += "close-resume"
            }
        }
    }

    override fun setMediaSource(source: CoordinatorMediaSource) {
        requireApplicationLooper()
        sourceKind = (source as FakeCoordinatorMediaSource).kind
        operations += "set-$sourceKind"
        if (failSetMediaSource) throw IOException("scripted")
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
        sourceKind = "none"
    }

    private data class FakeCoordinatorMediaSource(val kind: String) : CoordinatorMediaSource
}

private class TestCoordinatorLooper : CoordinatorLooper {
    private val queue = ArrayDeque<Runnable>()
    private var current = false

    override fun post(runnable: Runnable): Boolean {
        queue += runnable
        return true
    }

    override fun remove(runnable: Runnable) {
        queue.remove(runnable)
    }

    override fun isCurrent(): Boolean = current

    fun runAll() {
        while (queue.isNotEmpty()) runOnLooper { queue.removeFirst().run() }
    }

    fun runOnLooper(block: () -> Unit) {
        current = true
        try {
            block()
        } finally {
            current = false
        }
    }
}

private fun growingAdmission(): RecordingAdmission.Growing {
    val entry = DvrEntry.create(
        id = DvrEntryId(9),
        uuid = "stable-entry",
        path = "/recording.ts",
        files = listOf(
            DvrRecordingFile(
                fileId = 1,
                path = "/recording.ts",
                start = Instant.fromEpochSeconds(1),
                stop = null,
                sizeBytes = 1_000,
            ),
        ),
        state = DvrEntryState.RECORDING,
        dataSizeBytes = 1_000,
    )
    val states = kotlinx.coroutines.flow.MutableStateFlow<DvrRepositoryState>(
        DvrRepositoryState.Current(DvrSnapshot.create(listOf(entry))),
    )
    return RecordingAdmission.Growing(
        checkNotNull(GrowingRecordingFence.create(entry, states)),
        CurrentGrowingLease,
    )
}

private data object CurrentGrowingLease : GrowingRecordingFileLease {
    override val isCurrent: Boolean = true

    override suspend fun open(position: Long): RecordingFileResult<GrowingRecordingFileReader> =
        RecordingFileResult.Failed(RecordingFileFailure.NOT_SUPPORTED)
}
