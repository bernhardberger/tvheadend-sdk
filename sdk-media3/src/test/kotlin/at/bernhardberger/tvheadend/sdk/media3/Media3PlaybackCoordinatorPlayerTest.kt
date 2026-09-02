@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrProgressPolicy
import at.bernhardberger.tvheadend.sdk.core.DvrProgressResult
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.RecordingFile
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
    fun `public live target completes when coordinator owns application looper`() = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ ->
            RecordingAdmission.Completed(Duration.ZERO)
        }
        val coordinator = TvheadendPlaybackCoordinator(
            player = player,
            playerEvents = events,
            progressPolicy = DvrProgressPolicy(),
            onRecoveryRequired = {},
            timeSource = SystemPlaybackCoordinatorTimeSource,
        )
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }

        val result = async {
            coordinator.setLiveTarget(PlaybackBindingTestFactory.currentLive())
        }
        runCurrent()

        val completedWithoutPosting = result.isCompleted
        val postsBeforeDrain = access.looperQueue.posts
        if (!completedWithoutPosting) {
            access.looperQueue.runAll()
            runCurrent()
        }
        assertEquals(PlaybackTargetResult.STARTED, result.await())
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

        owner.cancel()
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()
        owner.join()

        assertTrue(completedWithoutPosting, "public live-target command did not complete inline")
        assertEquals(0, postsBeforeDrain)
    }

    @Test
    fun `current live recovery reaches application and replacement fences stale callback`() = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val reasons = mutableListOf<PlaybackRecoveryReason>()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ ->
            RecordingAdmission.Completed(Duration.ZERO)
        }
        val coordinator = TvheadendPlaybackCoordinator(
            player = player,
            playerEvents = events,
            progressPolicy = DvrProgressPolicy(),
            onRecoveryRequired = reasons::add,
            timeSource = SystemPlaybackCoordinatorTimeSource,
        )
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }

        assertEquals(
            PlaybackTargetResult.STARTED,
            coordinator.setLiveTarget(PlaybackBindingTestFactory.currentLive()),
        )
        val staleRecovery = requireNotNull(access.recoveryCallback)
        access.looperQueue.runOnLooper {
            staleRecovery(PlaybackRecoveryReason.AUDIO_RECOVERY_EXHAUSTED)
        }
        runCurrent()
        assertEquals(listOf(PlaybackRecoveryReason.AUDIO_RECOVERY_EXHAUSTED), reasons)

        assertEquals(
            PlaybackTargetResult.STARTED,
            coordinator.setLiveTarget(PlaybackBindingTestFactory.currentLive()),
        )
        access.looperQueue.runOnLooper {
            staleRecovery(PlaybackRecoveryReason.AUDIO_RECOVERY_EXHAUSTED)
        }
        runCurrent()
        assertEquals(listOf(PlaybackRecoveryReason.AUDIO_RECOVERY_EXHAUSTED), reasons)

        coordinator.shutdown(1.seconds)
        owner.join()
    }

    @Test
    fun `live and recording transitions use exact helper order and never release player`() = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ ->
            access.operations += "admit-recording"
            RecordingAdmission.Completed(12.seconds)
        }
        val liveOptions = SubscriptionOptions(
            streamProfileUuid = "0123456789abcdef0123456789abcdef",
            timeshiftPeriod = 600.seconds,
        )

        val live = async {
            val token = PlaybackTargetToken()
            player.installLive(
                PlayerOperationTicket(),
                token,
                TestCoordinatorLiveTarget(),
                liveOptions,
                controls(token),
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()
        assertEquals(PlaybackPlayerInstallStatus.STARTED, live.await().status)
        assertSame(liveOptions, access.liveOptions)
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
                TestCoordinatorRecordingTarget(),
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
                "add-listener",
                "set-recording",
                "create-resume",
                "begin-resume:12",
                "prepare",
                "close-recovery",
                "remove-listener",
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
                TestCoordinatorRecordingTarget(),
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
            player.installLive(
                PlayerOperationTicket(),
                liveToken,
                TestCoordinatorLiveTarget(),
                timeshiftControls = controls(liveToken),
            )
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
                TestCoordinatorRecordingTarget(),
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
            player.installLive(
                PlayerOperationTicket(),
                liveToken,
                TestCoordinatorLiveTarget(),
                timeshiftControls = controls(liveToken),
            )
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
                TestCoordinatorRecordingTarget(),
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
                TestCoordinatorRecordingTarget(),
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
                TestCoordinatorRecordingTarget(),
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
                TestCoordinatorRecordingTarget(),
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
    fun `failed replacement restores the previous source and target`() = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
            RecordingAdmission.Completed(null)
        }
        val liveToken = PlaybackTargetToken()
        val live = async {
            player.installLive(
                PlayerOperationTicket(),
                liveToken,
                TestCoordinatorLiveTarget(),
                timeshiftControls = controls(liveToken),
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()
        assertEquals(PlaybackPlayerInstallStatus.STARTED, live.await().status)
        access.operations.clear()
        access.failNextSetMediaSource = true

        val recordingToken = PlaybackTargetToken()
        val recording = async {
            player.installRecording(
                PlayerOperationTicket(),
                recordingToken,
                TestCoordinatorRecordingTarget(),
                RecordingPlaybackStart.START_OVER,
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()

        val result = recording.await()
        assertEquals(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE, result.status)
        assertFalse(result.retiredTarget)
        assertTrue(liveToken.isActive())
        assertFalse(recordingToken.isActive())
        assertEquals("live", access.currentSourceKind)
        assertEquals(
            listOf(
                "create-recording",
                "add-listener",
                "set-recording",
                "remove-listener",
                "set-live",
                "prepare",
            ),
            access.operations,
        )
    }

    @Test
    fun `failed replacement never reinstalls an owner-retired target`() = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
            RecordingAdmission.Completed(null)
        }
        val liveToken = PlaybackTargetToken()
        val liveControls = controls(liveToken)
        val live = async {
            player.installLive(
                PlayerOperationTicket(),
                liveToken,
                TestCoordinatorLiveTarget(),
                timeshiftControls = liveControls,
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()
        assertEquals(PlaybackPlayerInstallStatus.STARTED, live.await().status)
        access.operations.clear()
        access.prepareAction = {
            access.prepareAction = null
            throw IOException("scripted replacement failure")
        }
        access.removeListenerAction = {
            access.removeListenerAction = null
            liveToken.retire()
            liveControls.retire()
        }

        val replacementToken = PlaybackTargetToken()
        val replacement = async {
            player.installRecording(
                PlayerOperationTicket(),
                replacementToken,
                TestCoordinatorRecordingTarget(),
                RecordingPlaybackStart.START_OVER,
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()

        val result = replacement.await()
        assertEquals(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE, result.status)
        assertFalse(liveToken.isActive())
        assertFalse(replacementToken.isActive())
        assertEquals("none", access.currentSourceKind)
        assertEquals(
            listOf(
                "create-recording",
                "add-listener",
                "set-recording",
                "create-resume",
                "begin-resume:0",
                "prepare",
                "close-resume",
                "remove-listener",
                "close-recovery",
                "remove-listener",
                "stop",
                "clear",
            ),
            access.operations,
        )
    }

    @Test
    fun `reentrant retirement during source restore skips prepare and clears player`() = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
            RecordingAdmission.Completed(null)
        }
        val liveToken = PlaybackTargetToken()
        val liveControls = controls(liveToken)
        val live = async {
            player.installLive(
                PlayerOperationTicket(),
                liveToken,
                TestCoordinatorLiveTarget(),
                timeshiftControls = liveControls,
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()
        assertEquals(PlaybackPlayerInstallStatus.STARTED, live.await().status)
        access.operations.clear()
        access.prepareAction = {
            access.prepareAction = null
            throw IOException("scripted replacement failure")
        }
        access.setMediaSourceAction = { sourceKind ->
            if (sourceKind == "live") {
                liveToken.retire()
                liveControls.retire()
            }
        }

        val replacementToken = PlaybackTargetToken()
        val replacement = async {
            player.installRecording(
                PlayerOperationTicket(),
                replacementToken,
                TestCoordinatorRecordingTarget(),
                RecordingPlaybackStart.START_OVER,
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()

        assertEquals(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE, replacement.await().status)
        assertFalse(liveToken.isActive())
        assertFalse(replacementToken.isActive())
        assertEquals("none", access.currentSourceKind)
        assertEquals(1, access.operations.count { it == "prepare" })
        assertEquals(1, access.operations.count { it == "set-live" })
        assertTrue(access.operations.takeLast(2) == listOf("stop", "clear"))
    }

    @Test
    fun `successful replacement cleanup failure retires both targets`() = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
            RecordingAdmission.Completed(null)
        }
        val liveToken = PlaybackTargetToken()
        val live = async {
            player.installLive(
                PlayerOperationTicket(),
                liveToken,
                TestCoordinatorLiveTarget(),
                timeshiftControls = controls(liveToken),
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()
        live.await()
        access.failRecoveryClose = true

        val recordingToken = PlaybackTargetToken()
        val recording = async {
            player.installRecording(
                PlayerOperationTicket(),
                recordingToken,
                TestCoordinatorRecordingTarget(),
                RecordingPlaybackStart.START_OVER,
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()

        val result = recording.await()
        assertEquals(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE, result.status)
        assertTrue(result.retiredTarget)
        assertFalse(liveToken.isActive())
        assertFalse(recordingToken.isActive())
        assertEquals("none", access.currentSourceKind)
    }

    @Test
    fun `rollback cleanup failure retires the previous target`() = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
            RecordingAdmission.Completed(null)
        }
        val liveToken = PlaybackTargetToken()
        val live = async {
            player.installLive(
                PlayerOperationTicket(),
                liveToken,
                TestCoordinatorLiveTarget(),
                timeshiftControls = controls(liveToken),
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()
        live.await()
        access.failNextSetMediaSource = true
        access.failNextRemoveListener = true

        val recordingToken = PlaybackTargetToken()
        val recording = async {
            player.installRecording(
                PlayerOperationTicket(),
                recordingToken,
                TestCoordinatorRecordingTarget(),
                RecordingPlaybackStart.START_OVER,
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()

        val result = recording.await()
        assertEquals(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE, result.status)
        assertTrue(result.retiredTarget)
        assertFalse(liveToken.isActive())
        assertFalse(recordingToken.isActive())
        assertEquals("none", access.currentSourceKind)
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
                TestCoordinatorRecordingTarget(),
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
            player.installLive(
                PlayerOperationTicket(),
                liveToken,
                TestCoordinatorLiveTarget(),
                timeshiftControls = controls(liveToken),
            )
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
                TestCoordinatorRecordingTarget(),
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
    looperInitiallyCurrent: Boolean = false,
) : CoordinatorPlaybackAccess {
    val looperQueue = TestCoordinatorLooper(looperInitiallyCurrent)
    override val looper: CoordinatorLooper = looperQueue
    val operations = mutableListOf<String>()
    var snapshot = PlaybackPlayerSnapshot(Duration.ZERO, null, Player.STATE_IDLE, failed = false)
    private val listeners = mutableListOf<Player.Listener>()
    val applicationListener: Player.Listener?
        get() = listeners.lastOrNull()
    var recoveryCallback: ((PlaybackRecoveryReason) -> Unit)? = null
    var growingFinalEndCallback: (() -> Unit)? = null
    var growingLease: GrowingRecordingFileLease? = null
    var liveOptions: SubscriptionOptions? = null
    private var sourceKind = "none"
    val currentSourceKind: String
        get() = sourceKind
    var failNextSetMediaSource = failSetMediaSource
    var failRecoveryClose = false
    var failNextRemoveListener = false
    var prepareAction: (() -> Unit)? = null
    var removeListenerAction: (() -> Unit)? = null
    var setMediaSourceAction: ((String) -> Unit)? = null

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
        listeners += listener
    }

    override fun removeListener(listener: Player.Listener) {
        requireApplicationLooper()
        operations += "remove-listener"
        removeListenerAction?.invoke()
        if (failNextRemoveListener) {
            failNextRemoveListener = false
            throw IOException("scripted listener removal failure")
        }
        listeners.remove(listener)
    }

    override fun createLiveSource(
        target: CoordinatorLiveTarget,
        options: SubscriptionOptions,
        timeshiftControls: LiveTimeshiftControlBridge,
    ): CoordinatorMediaSource {
        requireApplicationLooper()
        operations += "create-live"
        liveOptions = options
        return FakeCoordinatorMediaSource("live")
    }

    override fun createRecordingSource(
        target: CoordinatorRecordingTarget,
        identity: RecordingMediaIdentity,
    ): CoordinatorMediaSource {
        requireApplicationLooper()
        operations += "create-recording"
        return FakeCoordinatorMediaSource("recording")
    }

    override fun createGrowingRecordingSource(
        lease: GrowingRecordingFileLease,
        identity: RecordingMediaIdentity,
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
                if (failRecoveryClose) throw IOException("scripted recovery close failure")
            }
        }
    }

    override fun createResume(identity: RecordingMediaIdentity): CoordinatorRecordingResume {
        requireApplicationLooper()
        operations += "create-resume"
        return object : CoordinatorRecordingResume {
            override fun beginPlaybackTarget(position: Duration?) {
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
        setMediaSourceAction?.invoke(sourceKind)
        if (failNextSetMediaSource) {
            failNextSetMediaSource = false
            throw IOException("scripted")
        }
    }

    override fun prepare() {
        requireApplicationLooper()
        operations += "prepare"
        prepareAction?.invoke()
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

private class TestCoordinatorLooper(initiallyCurrent: Boolean = false) : CoordinatorLooper {
    private val queue = ArrayDeque<Runnable>()
    private var current = initiallyCurrent
    var posts: Int = 0
        private set

    override fun post(runnable: Runnable): Boolean {
        posts += 1
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
        val wasCurrent = current
        current = true
        try {
            block()
        } finally {
            current = wasCurrent
        }
    }
}

private fun growingAdmission(): RecordingAdmission.Growing {
    return RecordingAdmission.Growing(CurrentGrowingLease)
}

private data object CurrentGrowingLease : GrowingRecordingFileLease {
    override val isCurrent: Boolean = true

    override suspend fun open(position: Long): RecordingFileResult<GrowingRecordingFileReader> =
        RecordingFileResult.Failed(RecordingFileFailure.NOT_SUPPORTED)
}

private fun controls(token: PlaybackTargetToken): LiveTimeshiftControlBridge =
    LiveTimeshiftControlBridge(token) {}
