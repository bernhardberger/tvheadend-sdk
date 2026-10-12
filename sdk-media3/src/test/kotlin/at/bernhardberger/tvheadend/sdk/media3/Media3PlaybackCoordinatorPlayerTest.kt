@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.PlaybackException
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrProgressPolicy
import at.bernhardberger.tvheadend.sdk.core.DvrProgressResult
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.RecordingFile
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionState
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTerminalReason
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

internal class Media3PlaybackCoordinatorPlayerTest {
    private var previousBoundsChecks: Boolean? = null

    @org.junit.jupiter.api.BeforeEach
    fun rememberMedia3BoundsChecks() {
        previousBoundsChecks = media3LegacyBoundsChecksOverride()
    }

    @org.junit.jupiter.api.AfterEach
    fun restoreMedia3BoundsChecks() {
        androidx.media3.common.util.ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(previousBoundsChecks)
    }

    @Test
    fun `retired pending item cannot disable audio when recovery detachment fails`() = runTest {
        var timer: (() -> Unit)? = null
        val access = FakeCoordinatorPlaybackAccess(
            looperInitiallyCurrent = true,
            recoveryScheduler = RecoveryScheduler { _, action ->
                timer = action
                ScheduledRecoveryTask { }
            },
        )
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
            RecordingAdmission.Completed(null)
        }
        val token = PlaybackTargetToken()
        player.installLive(PlayerOperationTicket(), token, TestCoordinatorLiveTarget(), timeshiftControls = controls(token))
        requireNotNull(access.recoveryBuffering).invoke()
        val fire = requireNotNull(timer)
        access.failRecoveryClose = true // Model an uncooperative recovery helper with a surviving timer.
        access.failNextStop = true
        access.failRemove = true
        assertFalse(player.stop(PlayerOperationTicket()).playerAvailable)
        assertTrue(player.playerCleanupPending.value)
        assertFalse(token.isActive())
        access.operations.clear()

        fire()

        assertTrue(access.operations.isEmpty(), "A retired current item must not mutate audio or tracks")
        assertFalse(requireNotNull(access.recoveryTargetCurrent).invoke())
        access.failRemove = false
        player.abandon()
    }

    @Test
    fun `recovery closes its state machine even when listener detachment throws`() {
        var listener: Player.Listener? = null
        var timer: (() -> Unit)? = null
        var cancellations = 0
        val reasons = mutableListOf<PlaybackRecoveryReason>()
        val player = java.lang.reflect.Proxy.newProxyInstance(
            Player::class.java.classLoader, arrayOf(Player::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getApplicationLooper" -> null // Local Android stubs also return null for myLooper().
                "getPlayWhenReady" -> true
                "getCurrentTracks" -> androidx.media3.common.Tracks.EMPTY
                "addListener" -> { listener = arguments!![0] as Player.Listener; null }
                "removeListener" -> throw IllegalStateException("scripted listener removal failure")
                else -> error("Unexpected recovery player access")
            }
        } as Player
        val recovery = TvheadendPlaybackRecovery(
            player, PlaybackRecoveryPolicy(), reasons::add,
            scheduler = RecoveryScheduler { _, action ->
                timer = action
                ScheduledRecoveryTask { cancellations += 1 }
            },
        )
        recovery.beginPlaybackTarget()
        requireNotNull(listener).onPlaybackStateChanged(Player.STATE_BUFFERING)
        val fire = requireNotNull(timer)

        assertThrows(IllegalStateException::class.java) { recovery.close() }
        assertEquals(1, cancellations)
        fire()
        requireNotNull(listener).onPlaybackStateChanged(Player.STATE_ENDED)
        assertTrue(reasons.isEmpty())
    }

    @Test
    fun `terminal cleanup fence rejects a claimed operation completing after cancelled owner abandonment`() = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource)
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        access.prepareAction = {
            // The install is claimed on the looper, but its actor continuation can already be cancelled.
            owner.cancel()
            access.looperQueue.rejectPosts = true
            runCurrent() // Terminal abandon cannot dispatch onto the quitting looper.
            assertTrue(owner.isCompleted)
            assertFalse(coordinator.playerCleanupPending.value)
            access.looperQueue.rejectPosts = false
            access.failNextStop = true
            access.failRemove = true
            throw IOException("scripted late preparation failure")
        }
        val install = async { coordinator.setLiveTarget(TestCoordinatorLiveTarget()) }
        runCurrent()
        access.looperQueue.runAll() // Continue the claimed operation after terminal abandonment.
        runCurrent()
        install.await()
        owner.join()

        assertEquals(listOf("live"), access.playlistKinds)
        assertTrue("remove-live" in access.operations, "The late rollback must reach failed item removal")
        assertFalse(coordinator.playerCleanupPending.value)
    }

    @Test
    fun `failed stop with successful removal clears pending on retry without stopping again`() = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
            RecordingAdmission.Completed(null)
        }
        val token = PlaybackTargetToken()
        player.installLive(PlayerOperationTicket(), token, TestCoordinatorLiveTarget(), timeshiftControls = controls(token))
        access.failNextStop = true
        assertFalse(player.stop(PlayerOperationTicket()).playerAvailable)
        assertTrue(player.playerCleanupPending.value)
        assertTrue(access.playlistKinds.isEmpty())
        access.operations.clear()

        assertTrue(player.stop(PlayerOperationTicket()).playerAvailable)
        assertFalse(player.playerCleanupPending.value)
        assertTrue(access.operations.isEmpty(), "An already removed item does not require another stop")
    }

    @Test
    fun `pending current item cleanup stops and removes only it while retaining a queued application item`() = runTest {
        val faults = CleanupFaults()
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true, cleanupFaults = faults)
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
            RecordingAdmission.Completed(null)
        }
        val token = PlaybackTargetToken()
        player.installLive(PlayerOperationTicket(), token, TestCoordinatorLiveTarget(), timeshiftControls = controls(token))
        access.addApplicationItem(moveToIt = false)
        access.snapshot = PlaybackPlayerSnapshot(37.seconds, 100.seconds, Player.STATE_READY, failed = false)
        faults.enabled = false
        access.operations.clear()

        assertTrue(player.stop(PlayerOperationTicket()).playerAvailable)
        assertEquals(listOf("stop", "remove-live"), access.operations)
        assertEquals(listOf("application"), access.playlistKinds)
        assertEquals(Duration.ZERO, access.snapshot.position)
        assertEquals(Player.STATE_IDLE, access.snapshot.playbackState)
        assertFalse(player.playerCleanupPending.value)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `run cancellation retries pending cleanup once then abandons it`(cleared: Boolean) = runTest {
        val faults = CleanupFaults()
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true, cleanupFaults = faults)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource)
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        assertSame(PlaybackTargetResult.PLAYER_UNAVAILABLE, coordinator.setLiveTarget(TestCoordinatorLiveTarget()))
        assertTrue(coordinator.playerCleanupPending.value)
        faults.enabled = !cleared
        access.operations.clear()

        owner.cancel()
        owner.join()

        assertEquals(listOf("stop", "remove-live"), access.operations)
        assertEquals(if (cleared) emptyList() else listOf("live"), access.playlistKinds)
        assertFalse(coordinator.playerCleanupPending.value)
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `pending cleanup after failed install is retried by stop or the next target`(
        recording: Boolean,
        directInstall: Boolean,
    ) = runTest {
        val faults = CleanupFaults()
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true, cleanupFaults = faults)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource)
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        val target = TestCoordinatorRecordingTarget()
        try {
            val result = if (recording) coordinator.setRecordingTarget(target, RecordingPlaybackStart.START_OVER)
            else coordinator.setLiveTarget(TestCoordinatorLiveTarget())
            assertSame(PlaybackTargetResult.PLAYER_UNAVAILABLE, result)
            assertTrue(coordinator.playerCleanupPending.value)
            assertEquals(listOf(if (recording) "recording" else "live"), access.playlistKinds)

            access.snapshot = PlaybackPlayerSnapshot(100.seconds, 100.seconds, Player.STATE_ENDED, failed = false)
            access.deliver { it.onPlaybackStateChanged(Player.STATE_ENDED) }
            runCurrent()
            assertTrue(target.reports.isEmpty(), "Retired items cannot report progress or watched state")
            faults.enabled = false
            access.operations.clear()
            if (!directInstall) {
                assertSame(PlaybackStopResult.ALREADY_STOPPED, coordinator.stop())
                assertEquals(emptyList<String>(), access.playlistKinds)
                assertFalse(coordinator.playerCleanupPending.value)
            }
            assertSame(PlaybackTargetResult.STARTED, coordinator.setLiveTarget(TestCoordinatorLiveTarget()))
            assertEquals(listOf("stop", if (recording) "remove-recording" else "remove-live"), access.operations.take(2))
            assertEquals(1, access.operations.count { it.startsWith("remove-") })
            assertEquals(listOf("live"), access.playlistKinds)
            assertFalse(coordinator.playerCleanupPending.value)
            assertTrue(target.reports.isEmpty())
        } finally {
            faults.enabled = false
            coordinator.shutdown(1.seconds)
            owner.join()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `pending cleanup preserves a current application item and its play intent`(replaced: Boolean) = runTest {
        val faults = CleanupFaults()
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true, cleanupFaults = faults)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource)
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        try {
            assertSame(PlaybackTargetResult.PLAYER_UNAVAILABLE, coordinator.setLiveTarget(TestCoordinatorLiveTarget()))
            assertTrue(coordinator.playerCleanupPending.value)
            if (replaced) access.replaceWithApplicationItem() else access.addApplicationItem(moveToIt = true)
            val item = access.currentItem
            val playing = PlaybackPlayerSnapshot(37.seconds, 100.seconds, Player.STATE_READY, failed = false)
            access.snapshot = playing
            access.playWhenReady = true
            faults.enabled = false
            access.operations.clear()

            assertSame(PlaybackStopResult.ALREADY_STOPPED, coordinator.stop())
            assertEquals(listOf("application"), access.playlistKinds)
            assertSame(item, access.currentItem)
            assertEquals(playing, access.snapshot)
            assertTrue(access.playWhenReady)
            assertFalse("stop" in access.operations)
            assertEquals(if (replaced) emptyList() else listOf("remove-live"), access.operations)
            assertFalse(coordinator.playerCleanupPending.value)
        } finally {
            faults.enabled = false
            coordinator.shutdown(1.seconds)
            owner.join()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `pending cleanup blocks source creation and stop reports persistent failure`(recording: Boolean) = runTest {
        val faults = CleanupFaults()
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true, cleanupFaults = faults)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource)
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        try {
            assertSame(PlaybackTargetResult.PLAYER_UNAVAILABLE, coordinator.setLiveTarget(TestCoordinatorLiveTarget()))
            val item = access.currentItem
            access.operations.clear()
            val result = if (recording) coordinator.setRecordingTarget(TestCoordinatorRecordingTarget(), RecordingPlaybackStart.START_OVER)
            else coordinator.setLiveTarget(TestCoordinatorLiveTarget())
            assertSame(PlaybackTargetResult.PLAYER_UNAVAILABLE, result)
            assertEquals(listOf("stop", "remove-live"), access.operations)
            assertTrue(coordinator.stop().outcome == PlaybackStopOutcome.PLAYER_UNAVAILABLE)
            assertEquals(listOf("stop", "remove-live", "stop", "remove-live"), access.operations)
            assertSame(item, access.currentItem)
            assertTrue(coordinator.playerCleanupPending.value)
        } finally {
            faults.enabled = false
            coordinator.shutdown(1.seconds)
            owner.join()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `shutdown retries pending cleanup once then forgets any remainder`(cleared: Boolean) = runTest {
        val faults = CleanupFaults()
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true, cleanupFaults = faults)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource)
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        assertSame(PlaybackTargetResult.PLAYER_UNAVAILABLE, coordinator.setLiveTarget(TestCoordinatorLiveTarget()))
        assertTrue(coordinator.playerCleanupPending.value)
        faults.enabled = !cleared
        access.operations.clear()

        assertSame(
            if (cleared) PlaybackShutdownResult.DRAINED else PlaybackShutdownResult.PLAYER_UNAVAILABLE,
            coordinator.shutdown(1.seconds),
        )
        assertFalse(coordinator.playerCleanupPending.value)
        owner.join()
        assertEquals(listOf("stop", "remove-live"), access.operations)
        assertEquals(if (cleared) emptyList() else listOf("live"), access.playlistKinds)
        assertFalse(coordinator.playerCleanupPending.value)
    }

    @ParameterizedTest
    @CsvSource("replaced,false", "mixed,false", "returned,false", "replaced,true", "mixed,true", "returned,true")
    fun `exhaustion after application departure leaves its player untouched`(departure: String, queuedFirst: Boolean) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val reasons = mutableListOf<PlaybackRecoveryReason>()
        val adapter = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(
            player = adapter,
            playerEvents = events,
            progressPolicy = DvrProgressPolicy(),
            onRecoveryRequired = reasons::add,
            timeSource = SystemPlaybackCoordinatorTimeSource,
        )
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        coordinator.setLiveTarget(PlaybackBindingTestFactory.currentLive())
        val harness = RestartSourceHarness(this, timeshiftSeconds = null, bridgeOverride = requireNotNull(access.liveControls))
        harness.start()
        val period = harness.createPeriod()
        harness.started(SubscriptionStreamType.MPEG2_AUDIO)
        // Hold the real adapter's admission until the app has departed, without blocking delivery.
        access.looperQueue.current = false
        if (queuedFirst) harness.fillLiveQueues(period)
        if (departure == "replaced") access.replaceWithApplicationItem() else access.addApplicationItem(moveToIt = true)
        if (departure == "returned") access.seekBackToCoordinatorItem()
        val item = access.currentItem
        val playlist = access.playlistKinds
        access.operations.clear()

        val posts = access.looperQueue.posts
        if (!queuedFirst) {
            harness.fillLiveQueues(period)
        }
        access.looperQueue.runAll()
        access.looperQueue.current = true
        runCurrent()

        if (!queuedFirst) {
            assertEquals(posts, access.looperQueue.posts, "Departure before overflow must refuse the hand-off")
        }
        assertSame(item, access.currentItem)
        assertEquals(playlist, access.playlistKinds)
        assertTrue(access.operations.isEmpty(), "Departed targets must not mutate the player on exhaustion")
        assertTrue(reasons.isEmpty())
        assertSame(LivePlaybackObservation.Departed, coordinator.livePlaybackObservation.value)
        val error = assertThrows(IOException::class.java) { period.maybeThrowPrepareError() }
        assertEquals("Live sample buffer limit reached", error.message)
        assertSame(SubscriptionTerminalReason.ConsumerFailed, (harness.subscription.state.value as SubscriptionState.Terminal).reason)
        assertEquals(1, harness.connection.unsubscribeCount)

        if (departure == "returned") access.transitionToApplicationItem(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        val applicationItem = access.currentItem
        assertTrue(coordinator.stop().outcome == PlaybackStopOutcome.RETIRED_AFTER_DEPARTURE)
        assertSame(applicationItem, access.currentItem)
        assertEquals(listOf("application"), access.playlistKinds)
        assertFalse("stop" in access.operations)
        assertSame(LivePlaybackObservation.NoTarget, coordinator.livePlaybackObservation.value)
        coordinator.shutdown(1.seconds)
        owner.join()
        harness.close()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `ordinary staged rollback cleanup failure settles observations and fails closed`(recording: Boolean) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
            RecordingAdmission.Completed(null)
        }
        val previous = PlaybackTargetToken()
        val bridge = controls(previous)
        player.installLive(PlayerOperationTicket(), previous, TestCoordinatorLiveTarget(), timeshiftControls = bridge)
        val staged = PlaybackTargetToken()
        access.prepareAction = {
            access.failNextRemoveListener = true
            throw IOException("scripted replacement preparation failure")
        }
        access.operations.clear()

        val result = if (recording) {
            player.installRecording(
                PlayerOperationTicket(), staged, TestCoordinatorRecordingTarget(), RecordingPlaybackStart.RESUME,
            )
        } else {
            player.installLive(PlayerOperationTicket(), staged, TestCoordinatorLiveTarget(), timeshiftControls = controls(staged))
        }

        assertSame(PlaybackPlayerInstallStatus.PLAYER_UNAVAILABLE, result.status)
        assertEquals("none", access.currentSourceKind)
        assertEquals(listOf("stop", if (recording) "remove-recording" else "remove-live"), access.operations.takeLast(2))
        assertFalse(previous.isActive())
        assertFalse(staged.isActive())
        assertNull(player.snapshot(previous))
        assertNull(player.snapshot(staged))
        // A stranded transaction rejects a new begin, even when its playback token has retired.
        bridge.commitObservationReplacement(bridge.beginObservationReplacement())
    }

    @ParameterizedTest
    @ValueSource(strings = ["stop", "exhaustion", "callback", "cleanup callback"])
    fun `foreign cancellation in player operations or recovery callbacks keeps the coordinator running`(failure: String) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val reasons = mutableListOf<PlaybackRecoveryReason>()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(
            player = player,
            playerEvents = events,
            progressPolicy = DvrProgressPolicy(),
            onRecoveryRequired = {
                reasons += it
                if (failure.endsWith("callback")) throw CancellationException("scripted callback failure")
            },
            timeSource = SystemPlaybackCoordinatorTimeSource,
        )
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        coordinator.setLiveTarget(PlaybackBindingTestFactory.currentLive())
        val attachment = requireNotNull(access.liveControls).newAttachment()
            .also { it.bind(FakeTimeshiftSubscription(null)) }
        if (failure != "callback") access.stopAction = { throw CancellationException("scripted player failure") }
        access.operations.clear()

        if (failure == "stop") {
            assertTrue(coordinator.stop().outcome == PlaybackStopOutcome.PLAYER_UNAVAILABLE)
            assertTrue(reasons.isEmpty())
        } else {
            assertTrue(attachment.bufferExhausted())
            runCurrent()
            assertEquals(listOf(if (failure == "callback") PlaybackRecoveryReason.LIVE_BUFFER_EXHAUSTED
                else PlaybackRecoveryReason.LIVE_BUFFER_EXHAUSTED_PLAYER_CLEANUP_FAILED), reasons)
        }

        assertTrue(owner.isActive)
        assertEquals(listOf("close-recovery", "remove-listener", "stop", "remove-live"), access.operations)
        assertEquals("none", access.currentSourceKind)
        assertFalse(attachment.leadsTarget())
        access.stopAction = null
        assertSame(PlaybackTargetResult.STARTED, coordinator.setLiveTarget(PlaybackBindingTestFactory.currentLive()))
        coordinator.shutdown(1.seconds)
        owner.join()
    }

    @ParameterizedTest
    @ValueSource(strings = ["false", "throw", "true", "no target"])
    fun `conditional stop uses the real adapter without mutating rejected targets`(condition: String) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
            RecordingAdmission.Completed(null)
        }
        val token = PlaybackTargetToken()
        if (condition != "no target") {
            player.installLive(
                PlayerOperationTicket(), token, TestCoordinatorLiveTarget(), timeshiftControls = controls(token),
            )
        }
        access.operations.clear()
        var predicates = 0

        val result = player.stop(PlayerOperationTicket()) {
            predicates += 1
            when (condition) {
                "throw" -> error("scripted predicate failure")
                "false" -> false
                else -> true
            }
        }

        assertEquals(condition == "true", result.retiredTarget)
        assertEquals(condition != "throw", result.playerAvailable)
        assertEquals(condition == "true", result.playerStoppedAndCleared)
        assertEquals(condition != "true", token.isActive())
        assertEquals(if (condition == "no target") 0 else 1, predicates)
        assertEquals(condition == "true", "stop" in access.operations)
        assertEquals(condition == "true", "remove-live" in access.operations)
        if (condition != "true") assertTrue(access.operations.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `exhaustion claim fences replacement between predicate and retirement`(afterClaim: Boolean) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
            RecordingAdmission.Completed(null)
        }
        val token = PlaybackTargetToken()
        val bridge = controls(token)
        player.installLive(PlayerOperationTicket(), token, TestCoordinatorLiveTarget(), timeshiftControls = bridge)
        val subscription = FakeTimeshiftSubscription(null)
        val exhausted = bridge.newAttachment().also { it.bind(subscription) }
        val successor = bridge.newAttachment()
        var snapshots = 0
        access.snapshotAction = {
            snapshots += 1
            // The sole snapshot now happens after the ownership claim, before retirement commits.
            exhausted.detach()
            successor.bind(subscription)
        }
        if (!afterClaim) {
            exhausted.detach()
            successor.bind(subscription)
        }

        val result = player.stop(PlayerOperationTicket()) { exhausted.claimExhaustionRetirement() }

        assertEquals(afterClaim, result.retiredTarget)
        assertEquals(!afterClaim, token.isActive())
        if (afterClaim) {
            assertEquals(1, snapshots)
            assertNull(successor.subscription, "retiring target must reject successor admission")
        } else {
            assertSame(subscription, successor.subscription)
            assertTrue(successor.leadsTarget())
            assertFalse("stop" in access.operations)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["snapshot", "predicate", "after claim", "post rejected", "post throws", "looper check"])
    fun `exhaustion evaluation failures cannot silently leave the target installed`(failure: String) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val reasons = mutableListOf<PlaybackRecoveryReason>()
        val adapter = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        // Inject predicate failures without replacing the production adapter's stop implementation.
        val player = object : PlaybackCoordinatorPlayer by adapter {
            override suspend fun stop(
                ticket: PlayerOperationTicket,
                abandonPendingCleanup: Boolean,
                retireOnlyIf: (() -> Boolean)?,
            ): PlaybackPlayerStopResult = adapter.stop(ticket, abandonPendingCleanup, retireOnlyIf?.let { predicate ->
                {
                    if (failure == "predicate") error("scripted evaluation failure")
                    val admitted = predicate()
                    if (failure == "after claim") error("scripted evaluation failure")
                    admitted
                }
            })
        }
        val coordinator = TvheadendPlaybackCoordinator(
            player = player,
            playerEvents = events,
            progressPolicy = DvrProgressPolicy(),
            onRecoveryRequired = reasons::add,
            timeSource = SystemPlaybackCoordinatorTimeSource,
        )
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        coordinator.setLiveTarget(PlaybackBindingTestFactory.currentLive())
        val attachment = requireNotNull(access.liveControls).newAttachment()
            .also { it.bind(FakeTimeshiftSubscription(null)) }
        var snapshots = 0
        if (failure == "snapshot") access.snapshotAction = {
            snapshots += 1
            error("scripted snapshot failure")
        }
        access.looperQueue.rejectPosts = failure == "post rejected"
        access.looperQueue.throwOnPost = failure == "post throws"
        access.looperQueue.throwOnIsCurrent = failure == "looper check"

        assertTrue(attachment.bufferExhausted())
        runCurrent()

        val expected = if (failure == "snapshot") {
            PlaybackRecoveryReason.LIVE_BUFFER_EXHAUSTED
        } else {
            PlaybackRecoveryReason.LIVE_BUFFER_EXHAUSTED_RETIREMENT_FAILED
        }
        assertEquals(listOf(expected), reasons)
        if (failure == "snapshot") {
            assertEquals(1, snapshots)
            assertEquals("none", access.currentSourceKind)
            assertTrue("stop" in access.operations)
            assertSame(LivePlaybackObservation.NoTarget, coordinator.livePlaybackObservation.value)
        } else {
            assertTrue(coordinator.livePlaybackObservation.value is LivePlaybackObservation.Active)
            assertEquals("live", access.currentSourceKind)
        }
        access.snapshotAction = null
        access.looperQueue.rejectPosts = false
        access.looperQueue.throwOnPost = false
        access.looperQueue.throwOnIsCurrent = false
        if (failure != "snapshot") {
            assertTrue(coordinator.stop().outcome == PlaybackStopOutcome.STOPPED)
            assertSame(LivePlaybackObservation.NoTarget, coordinator.livePlaybackObservation.value)
            assertEquals("none", access.currentSourceKind)
        }
        coordinator.shutdown(1.seconds)
        owner.join()
    }

    @ParameterizedTest
    @ValueSource(strings = ["stop", "remove", "remove retune", "listener", "recovery"])
    fun `exhaustion cleanup reports an app outcome independent of the period`(failure: String) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val reasons = mutableListOf<PlaybackRecoveryReason>()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(
            player = player,
            playerEvents = events,
            progressPolicy = DvrProgressPolicy(),
            onRecoveryRequired = reasons::add,
            timeSource = SystemPlaybackCoordinatorTimeSource,
        )
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        coordinator.setLiveTarget(PlaybackBindingTestFactory.currentLive())
        val attachment = requireNotNull(access.liveControls).newAttachment()
            .also { it.bind(FakeTimeshiftSubscription(null)) }
        when (failure) {
            "stop" -> access.failNextStop = true
            "remove", "remove retune" -> access.failRemove = true
            "listener" -> access.failNextRemoveListener = true
            "recovery" -> access.failRecoveryClose = true
        }
        assertTrue(attachment.bufferExhausted())
        runCurrent()

        val expected = if (failure == "stop" || failure.startsWith("remove")) {
            PlaybackRecoveryReason.LIVE_BUFFER_EXHAUSTED_PLAYER_CLEANUP_FAILED
        } else {
            PlaybackRecoveryReason.LIVE_BUFFER_EXHAUSTED
        }
        assertEquals(listOf(expected), reasons)
        assertTrue("stop" in access.operations)
        assertTrue("remove-live" in access.operations)
        assertSame(LivePlaybackObservation.NoTarget, coordinator.livePlaybackObservation.value)
        if (failure.startsWith("remove")) {
            assertEquals(listOf("live"), access.playlistKinds)
            assertTrue(coordinator.playerCleanupPending.value)
            access.failRemove = false
            access.operations.clear()
            if (failure == "remove retune") {
                assertSame(PlaybackTargetResult.STARTED, coordinator.setLiveTarget(TestCoordinatorLiveTarget()))
                assertEquals(listOf("live"), access.playlistKinds)
                assertEquals(listOf("stop", "remove-live", "create-live"), access.operations.take(3))
            } else {
                assertSame(PlaybackStopResult.ALREADY_STOPPED, coordinator.stop())
                assertEquals(emptyList<String>(), access.playlistKinds)
                assertEquals(listOf("stop", "remove-live"), access.operations)
            }
            assertFalse(coordinator.playerCleanupPending.value)
        }
        coordinator.shutdown(1.seconds)
        owner.join()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `failed removal ends delivery even when shutdown wins exhaustion admission`(shutdownFirst: Boolean) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val reasons = mutableListOf<PlaybackRecoveryReason>()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(player, events, DvrProgressPolicy(), reasons::add, SystemPlaybackCoordinatorTimeSource)
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        coordinator.setLiveTarget(PlaybackBindingTestFactory.currentLive())
        val harness = RestartSourceHarness(this, timeshiftSeconds = null, bridgeOverride = requireNotNull(access.liveControls))
        try {
            harness.start()
            val period = harness.createPeriod()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            access.failRemove = true
            access.looperQueue.current = false
            val shuttingDown = if (shutdownFirst) async { coordinator.shutdown(1.seconds) } else null
            runCurrent() // Shutdown waits on the player looper while the still-current period fills.
            harness.fillLiveQueues(period)
            assertEquals(1, harness.pendingConsumerCalls)
            access.looperQueue.runAll()
            access.looperQueue.current = true
            runCurrent()
            shuttingDown?.await()
            assertEquals(0, harness.pendingConsumerCalls)
            assertSame(SubscriptionTerminalReason.ConsumerFailed, (harness.subscription.state.value as SubscriptionState.Terminal).reason)
            assertEquals(1, harness.connection.unsubscribeCount)
            assertEquals("Live sample buffer limit reached", assertThrows(IOException::class.java) {
                period.maybeThrowPrepareError()
            }.message)
            assertEquals(listOf("live"), access.playlistKinds, "No application cleanup is needed to stop server delivery")
            assertEquals(if (shutdownFirst) emptyList() else listOf(PlaybackRecoveryReason.LIVE_BUFFER_EXHAUSTED_PLAYER_CLEANUP_FAILED), reasons)
        } finally {
            access.looperQueue.current = true
            access.failRemove = false
            coordinator.shutdown(1.seconds)
            owner.join()
            harness.close()
        }
    }

    @Test
    fun `recording ownership survives metadata-only item replacement but not a reused media id`() {
        val identity = RecordingMediaIdentity()
        val installed = tvheadendRecordingMediaItem(identity)
        val updated = installed.buildUpon()
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Updated title").build()).build()
        assertTrue(ownsCoordinatorMediaItem(installed, installed, identity))
        assertTrue(ownsCoordinatorMediaItem(installed, updated, identity))
        assertFalse(ownsCoordinatorMediaItem(installed, updated.buildUpon().setUri("data:,other").build(), identity))
        assertFalse(ownsCoordinatorMediaItem(installed, null, identity))
    }

    @Test
    fun `live ownership requires the installed instance rather than the reserved media id`() {
        val installed = MediaItem.Builder().setMediaId(LIVE_MEDIA_ID).build()
        assertTrue(ownsCoordinatorMediaItem(installed, installed, null))
        assertFalse(ownsCoordinatorMediaItem(installed, installed.buildUpon().build(), null))
        assertFalse(ownsCoordinatorMediaItem(installed, null, null))
    }

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

    @ParameterizedTest
    @CsvSource("false", "true")
    fun `retirement preserves the terminal finality captured before application replacement`(
        finalAtEnd: Boolean,
    ) = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> growingAdmission() }
        onLooper(access) {
            player.installRecording(
                PlayerOperationTicket(), PlaybackTargetToken(), TestCoordinatorRecordingTarget(),
                RecordingPlaybackStart.START_OVER,
            )
        }
        access.snapshot = access.snapshot.copy(
            position = 100.seconds, duration = 100.seconds, playbackState = Player.STATE_ENDED,
        )
        access.looperQueue.runOnLooper {
            if (finalAtEnd) access.growingFinalEndCallback?.invoke()
            access.deliver { it.onPlaybackStateChanged(Player.STATE_ENDED) }
            access.replaceWithApplicationItem()
            // Later proof must not change the finality attached to the captured terminal.
            access.growingFinalEndCallback?.invoke()
        }

        val retired = requireNotNull(onLooper(access) { player.stop(PlayerOperationTicket()) }.retiredRecording)

        assertEquals(100.seconds, retired.snapshot.position)
        assertSame(at.bernhardberger.tvheadend.sdk.core.DvrPlaybackExit.NATURAL_END, retired.exit)
        assertEquals(finalAtEnd, retired.growingFinalEndProven)
        assertNull(events.take())
    }

    @Test
    fun `growing resume installs a pending resume that snapshots carry until it applies`() =
        runTest(timeout = 30.seconds) {
            val access = FakeCoordinatorPlaybackAccess()
            val events = PlaybackPlayerEventAccumulator()
            val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ ->
                RecordingAdmission.Growing(CurrentGrowingLease, resumePosition = 8.seconds)
            }
            val token = PlaybackTargetToken()

            val result = async {
                player.installRecording(
                    PlayerOperationTicket(),
                    token,
                    TestCoordinatorRecordingTarget(),
                    RecordingPlaybackStart.RESUME,
                )
            }
            runCurrent()
            access.looperQueue.runAll()
            runCurrent()

            assertEquals(PlaybackPlayerInstallStatus.STARTED, result.await().status)
            assertEquals(
                listOf(
                    "create-growing",
                    "add-listener",
                    "set-growing",
                    "create-growing-resume",
                    "begin-resume:8",
                    "prepare",
                ),
                access.operations,
            )
            assertEquals(
                listOf(GROWING_RESUME_SETTLE_TIMEOUT_MILLIS),
                access.resumeTimeouts.scheduledDelays,
            )
            val pending = async { player.snapshot(token) }
            runCurrent()
            access.looperQueue.runAll()
            assertEquals(8.seconds, pending.await()?.pendingResumePosition)
            assertTrue(requireNotNull(pending.await()).precedesPendingResume)

            access.looperQueue.runOnLooper {
                access.applicationListener?.onPlayWhenReadyChanged(
                    false,
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                )
            }
            assertEquals(8.seconds, events.take()?.snapshot?.pendingResumePosition)

            access.deliverResumeMediaState(durationMillis = 12_000, isSeekable = true)
            assertEquals(listOf(8.seconds), access.resumeSeeks)
            val applied = async { player.snapshot(token) }
            runCurrent()
            access.looperQueue.runAll()
            assertNull(applied.await()?.pendingResumePosition)

            val stop = async { player.stop(PlayerOperationTicket()) }
            runCurrent()
            access.looperQueue.runAll()
            stop.await()
            assertTrue(access.operations.contains("close-resume"))
        }

    @Test
    fun `completed resume stays pending after the progress floor backstop and seeks once seekable`() =
        runTest(timeout = 30.seconds) {
            val access = FakeCoordinatorPlaybackAccess()
            val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
                RecordingAdmission.Completed(180.seconds)
            }
            val token = PlaybackTargetToken()
            val install = async {
                player.installRecording(
                    PlayerOperationTicket(),
                    token,
                    TestCoordinatorRecordingTarget(),
                    RecordingPlaybackStart.RESUME,
                )
            }
            runCurrent()
            access.looperQueue.runAll()
            install.await()
            assertEquals(
                listOf(COMPLETED_RESUME_PROGRESS_FLOOR_TIMEOUT_MILLIS),
                access.resumeTimeouts.scheduledDelays,
            )
            access.deliverResumeMediaState(durationMillis = 3_600_000, isSeekable = false)
            val floored = async { player.snapshot(token) }
            runCurrent()
            access.looperQueue.runAll()
            assertEquals(180.seconds, floored.await()?.pendingResumePosition)

            access.looperQueue.runOnLooper { access.resumeTimeouts.fireAll() }
            val afterBackstop = async { player.snapshot(token) }
            runCurrent()
            access.looperQueue.runAll()
            assertNull(afterBackstop.await()?.pendingResumePosition)
            assertNull(access.resumeState?.outcome)

            access.deliverResumeMediaState(durationMillis = 3_600_000, isSeekable = true)
            assertEquals(listOf(180.seconds), access.resumeSeeks)
            assertEquals(RecordingResumeOutcome.APPLIED, access.resumeState?.outcome)
        }

    @Test
    fun `stopping before a growing resume applies retires with the pending position`() =
        runTest(timeout = 30.seconds) {
            val access = FakeCoordinatorPlaybackAccess()
            val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
                RecordingAdmission.Growing(CurrentGrowingLease, resumePosition = 8.seconds)
            }
            val install = async {
                player.installRecording(
                    PlayerOperationTicket(),
                    PlaybackTargetToken(),
                    TestCoordinatorRecordingTarget(),
                    RecordingPlaybackStart.RESUME,
                )
            }
            runCurrent()
            access.looperQueue.runAll()
            install.await()

            val stop = async { player.stop(PlayerOperationTicket()) }
            runCurrent()
            access.looperQueue.runAll()

            val retired = requireNotNull(stop.await().retiredRecording)
            assertEquals(8.seconds, retired.snapshot.pendingResumePosition)
            assertEquals(RecordingResumeOutcome.CANCELLED, access.resumeState?.outcome)
            assertEquals(0, access.resumeTimeouts.active)
            assertEquals(emptyList<Duration>(), access.resumeSeeks)
        }

    @Test
    fun `growing start over installs no resume helper`() = runTest(timeout = 30.seconds) {
        val access = FakeCoordinatorPlaybackAccess()
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
            RecordingAdmission.Growing(CurrentGrowingLease, resumePosition = null)
        }
        val install = async {
            player.installRecording(
                PlayerOperationTicket(),
                PlaybackTargetToken(),
                TestCoordinatorRecordingTarget(),
                RecordingPlaybackStart.RESUME,
            )
        }
        runCurrent()
        access.looperQueue.runAll()

        assertEquals(PlaybackPlayerInstallStatus.STARTED, install.await().status)
        assertEquals(listOf("create-growing", "add-listener", "set-growing", "prepare"), access.operations)
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
                "remove-recording",
            ),
            access.operations,
        )
    }

    @ParameterizedTest
    @CsvSource("auto", "seek", "playlist", "repeat")
    fun `recording playlist transitions report natural completion only for auto advance`(
        transition: String,
    ) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(
            player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource,
        )
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        val target = TestCoordinatorRecordingTarget()
        try {
            assertSame(
                PlaybackTargetResult.STARTED,
                coordinator.setRecordingTarget(target, RecordingPlaybackStart.START_OVER),
            )
            access.snapshot = access.snapshot.copy(
                position = 97.seconds, duration = 100.seconds, playbackState = Player.STATE_READY,
            )
            access.addApplicationItem(moveToIt = false)
            when (transition) {
                "auto" -> access.transitionToApplicationItem(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                "seek" -> access.transitionToApplicationItem(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
                "playlist" -> access.transitionToApplicationItem(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                "repeat" -> {
                    access.snapshot = access.snapshot.copy(position = Duration.ZERO)
                    val item = access.currentItem
                    access.deliver { it.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) }
                }
            }
            runCurrent()
            assertEquals(if (transition == "auto") 1 else 0, target.reports.size)

            assertEquals(
                if (transition == "repeat") PlaybackStopResult.stopped(null)
                else PlaybackStopResult.retiredAfterDeparture(null),
                coordinator.stop(),
            )
            runCurrent()
            val report = target.reports.single().second
            assertEquals(
                when (transition) { "auto" -> 100.seconds; "repeat" -> Duration.ZERO; else -> 97.seconds },
                report.position,
            )
            assertEquals(transition == "auto", report.markWatched)
        } finally {
            coordinator.shutdown(1.seconds)
            owner.join()
        }
        assertEquals(1, target.reports.size)
    }

    @ParameterizedTest
    @CsvSource(
        "false,false,false", "false,false,true", "false,true,false", "false,true,true",
        "true,false,false", "true,false,true", "true,true,false", "true,true,true",
    )
    fun `auto departure completes from delivered events despite timeline batching or an application seek back`(
        seekBack: Boolean,
        timelineChanged: Boolean,
        shutdown: Boolean,
    ) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(
            player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource,
        )
        access.addApplicationListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (seekBack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) access.seekBackToCoordinatorItem()
            }
        })
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        val target = TestCoordinatorRecordingTarget()
        try {
            assertSame(
                PlaybackTargetResult.STARTED,
                coordinator.setRecordingTarget(target, RecordingPlaybackStart.START_OVER),
            )
            access.snapshot = access.snapshot.copy(
                position = 97.seconds, duration = 100.seconds, playbackState = Player.STATE_READY,
            )
            access.addApplicationItem(moveToIt = false)

            access.transitionToApplicationItem(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, timelineChanged)
            runCurrent()

            assertEquals(100.seconds, target.reports.single().second.position)
            assertTrue(target.reports.single().second.markWatched)
            access.snapshot = access.snapshot.copy(position = 4.seconds)
            access.deliver { it.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
            runCurrent()
            if (shutdown) coordinator.shutdown(1.seconds) else coordinator.stop()
            runCurrent()
        } finally {
            coordinator.shutdown(1.seconds)
            owner.join()
        }
        assertEquals(1, target.reports.size)
        assertEquals(100.seconds, target.reports.single().second.position)
    }

    @ParameterizedTest
    @CsvSource("seek,false", "remove,false", "auto,false", "seek,true", "remove,true", "auto,true")
    fun `departure captures the owned discontinuity position and rejects foreign old items`(
        departure: String,
        foreignOldItem: Boolean,
    ) = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        access.snapshot = access.snapshot.copy(position = 10.seconds, duration = null)
        val player = installedRecordingPlayer(access)
        access.looperQueue.runOnLooper {
            access.snapshot = access.snapshot.copy(position = 10.seconds)
            access.deliverTimelineChange()
        }
        access.addApplicationItem(moveToIt = false)
        val reason = when (departure) {
            "seek" -> Player.DISCONTINUITY_REASON_SEEK
            "remove" -> Player.DISCONTINUITY_REASON_REMOVE
            else -> Player.DISCONTINUITY_REASON_AUTO_TRANSITION
        }

        access.departWithPosition(reason, 60.seconds, foreignOldItem)
        val retired = requireNotNull(onLooper(access) { player.stop(PlayerOperationTicket()) }.retiredRecording)

        assertEquals(if (foreignOldItem) 10.seconds else 60.seconds, retired.snapshot.position)
        assertEquals(
            if (departure == "auto") at.bernhardberger.tvheadend.sdk.core.DvrPlaybackExit.NATURAL_END
            else at.bernhardberger.tvheadend.sdk.core.DvrPlaybackExit.ORDERLY,
            retired.exit,
        )
    }

    @ParameterizedTest
    @CsvSource(
        "false,true,seek", "true,true,seek", "false,false,seek", "true,false,seek",
        "false,true,replace", "true,true,replace", "false,false,replace", "true,false,replace",
        "false,true,auto", "true,true,auto", "false,false,auto", "true,false,auto",
    )
    fun `resume settlement updates the closing floor only before departure`(
        growing: Boolean,
        settleBeforeDeparture: Boolean,
        departure: String,
    ) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ ->
            if (growing) RecordingAdmission.Growing(CurrentGrowingLease, resumePosition = 80.seconds)
            else RecordingAdmission.Completed(80.seconds)
        }
        val coordinator = TvheadendPlaybackCoordinator(
            player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource,
        )
        val target = TestCoordinatorRecordingTarget()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        try {
            coordinator.setRecordingTarget(target, RecordingPlaybackStart.RESUME)
            access.addApplicationItem(moveToIt = false)
            access.looperQueue.runOnLooper {
                access.snapshot = access.snapshot.copy(position = 61.seconds)
                access.deliverTimelineChange()
                if (settleBeforeDeparture) access.resumeTimeouts.fireAll()
                when (departure) {
                    "seek" -> access.departWithPosition(Player.DISCONTINUITY_REASON_SEEK, 61.seconds)
                    "auto" -> access.transitionToApplicationItem(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                    else -> access.replaceWithApplicationItem()
                }
                // Settlement after departure must not release the captured floor, nor may close().
                if (!settleBeforeDeparture) access.resumeTimeouts.fireAll()
            }
            coordinator.stop()
            runCurrent()
            if (settleBeforeDeparture) assertEquals(61.seconds, target.reports.single().second.position)
            else assertTrue(target.reports.isEmpty())
        } finally {
            coordinator.shutdown(1.seconds)
            owner.join()
        }
    }

    @ParameterizedTest
    @CsvSource("false", "true")
    fun `application departure during live installation never prepares the application item`(previous: Boolean) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(
            player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource,
        )
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        try {
            if (previous) coordinator.setRecordingTarget(TestCoordinatorRecordingTarget(), RecordingPlaybackStart.START_OVER)
            access.operations.clear()
            access.setMediaSourceAction = { access.replaceWithApplicationItem() }

            val result = coordinator.setLiveTarget(PlaybackBindingTestFactory.currentLive())
            assertSame(PlaybackTargetResult.DEPARTED, result)
            assertSame(PlaybackTargetDisposition.DEPARTED, result.disposition)
            assertSame(LivePlaybackObservation.Departed, coordinator.livePlaybackObservation.value)
            assertEquals("application", access.currentSourceKind)
            assertFalse(access.operations.contains("prepare"))
            assertEquals(listOf("set-live"), access.operations.filter { it.startsWith("set-") })
            assertEquals(PlaybackStopResult.retiredAfterDeparture(null), coordinator.stop())
            assertSame(LivePlaybackObservation.NoTarget, coordinator.livePlaybackObservation.value)
            assertEquals("application", access.currentSourceKind)
        } finally {
            coordinator.shutdown(1.seconds)
            owner.join()
        }
    }

    @ParameterizedTest
    @CsvSource("none,false", "live,false", "recording,false", "none,true", "live,true", "recording,true")
    fun `application departure during recording installation never restores the previous target`(
        previous: String,
        clear: Boolean,
    ) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, start ->
            RecordingAdmission.Completed(if (start.start == RecordingPlaybackStart.RESUME) 80.seconds else null)
        }
        val coordinator = TvheadendPlaybackCoordinator(
            player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource,
        )
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        val target = TestCoordinatorRecordingTarget()
        try {
            when (previous) {
                "live" -> coordinator.setLiveTarget(TestCoordinatorLiveTarget())
                "recording" -> coordinator.setRecordingTarget(
                    TestCoordinatorRecordingTarget(), RecordingPlaybackStart.START_OVER,
                )
            }
            access.operations.clear()
            access.setMediaSourceAction = {
                access.setMediaSourceAction = null
                access.addListener(object : Player.Listener {
                    private var departed = false
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (departed) return
                        departed = true
                        if (clear) access.clearApplicationPlaylist() else access.replaceWithApplicationItem()
                    }
                })
            }

            assertSame(PlaybackTargetResult.DEPARTED, coordinator.setRecordingTarget(target, RecordingPlaybackStart.RESUME))
            assertEquals(if (clear) "none" else "application", access.currentSourceKind)
            assertEquals(listOf("set-recording"), access.operations.filter { it.startsWith("set-") })
            assertFalse(access.operations.contains("create-resume"))
            assertFalse(access.operations.contains("prepare"))
            assertEquals(PlaybackStopResult.retiredAfterDeparture(null), coordinator.stop())
            runCurrent()
            assertEquals(if (clear) "none" else "application", access.currentSourceKind)
            assertTrue(target.reports.isEmpty())
        } finally {
            coordinator.shutdown(1.seconds)
            owner.join()
        }
    }

    @Test
    fun `returned coordinator item is stopped without reclaiming reporting ownership`() = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(
            player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource,
        )
        val target = TestCoordinatorRecordingTarget()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        try {
            coordinator.setRecordingTarget(target, RecordingPlaybackStart.START_OVER)
            access.snapshot = access.snapshot.copy(position = 30.seconds)
            access.deliverTimelineChange()
            access.addApplicationItem(moveToIt = true)
            access.seekBackToCoordinatorItem()
            access.snapshot = access.snapshot.copy(position = 90.seconds)
            access.deliverTimelineChange()
            access.operations.clear()

            assertEquals(PlaybackStopResult.stopped(null), coordinator.stop())
            runCurrent()
            assertTrue(access.operations.contains("stop"))
            assertEquals(listOf("application"), access.playlistKinds)
            assertEquals(30.seconds, target.reports.single().second.position)
            assertFalse(target.reports.single().second.markWatched)
        } finally {
            coordinator.shutdown(1.seconds)
            owner.join()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `departed installation never emits active even when departure races activation`(duringInstall: Boolean) = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(
            player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource,
        )
        val observations = mutableListOf<LivePlaybackObservation>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.livePlaybackObservation.collect { observations += it }
        }
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        try {
            if (duringInstall) access.setMediaSourceAction = { access.replaceWithApplicationItem() }
            val installing = async { coordinator.setLiveTarget(PlaybackBindingTestFactory.currentLive()) }
            runCurrent()
            access.looperQueue.runAll()
            // The looper operation has finished but its actor continuation has not activated the target.
            access.looperQueue.current = true
            if (!duringInstall) access.replaceWithApplicationItem()
            runCurrent()
            assertSame(if (duringInstall) PlaybackTargetResult.DEPARTED else PlaybackTargetResult.STARTED, installing.await())
            assertEquals(listOf(LivePlaybackObservation.NoTarget, LivePlaybackObservation.Departed), observations)
            coordinator.stop()
            assertEquals(
                listOf(LivePlaybackObservation.NoTarget, LivePlaybackObservation.Departed, LivePlaybackObservation.NoTarget),
                observations,
            )
        } finally {
            coordinator.shutdown(1.seconds)
            owner.join()
        }
    }

    @Test
    fun `departed live observation survives subscription publication and return to the item`() = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(
            player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource,
        )
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        try {
            coordinator.setLiveTarget(PlaybackBindingTestFactory.currentLive())
            val controls = requireNotNull(access.liveControls)
            controls.newAttachment().bind(FakeTimeshiftSubscription(null))
            assertTrue(coordinator.livePlaybackObservation.value is LivePlaybackObservation.Active)

            access.addApplicationItem(moveToIt = true)
            assertSame(LivePlaybackObservation.Departed, coordinator.livePlaybackObservation.value)
            controls.publishCurrent()
            access.seekBackToCoordinatorItem()
            controls.publishCurrent()
            runCurrent()
            assertSame(LivePlaybackObservation.Departed, coordinator.livePlaybackObservation.value)

            access.transitionToApplicationItem(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            assertEquals(PlaybackStopResult.retiredAfterDeparture(null), coordinator.stop())
            assertSame(LivePlaybackObservation.NoTarget, coordinator.livePlaybackObservation.value)
        } finally {
            coordinator.shutdown(1.seconds)
            owner.join()
        }
    }

    @ParameterizedTest
    @CsvSource(
        "prepare,replace,false", "prepare,replace,true", "prepare,clear,false", "prepare,clear,true",
        "transition,replace,false", "transition,replace,true", "transition,clear,false", "transition,clear,true",
        "prepare,auto,false", "prepare,auto,true", "transition,auto,false", "transition,auto,true",
        "prepare,discontinuity,false", "prepare,discontinuity,true",
        "transition,discontinuity,false", "transition,discontinuity,true",
    )
    fun `resume progress floor survives departure before the first actor snapshot`(
        stage: String,
        departure: String,
        shutdown: Boolean,
    ) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(80.seconds) }
        val coordinator = TvheadendPlaybackCoordinator(
            player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource,
        )
        fun depart() {
            when (departure) {
                "replace" -> access.replaceWithApplicationItem()
                "clear" -> access.clearApplicationPlaylist()
                else -> {
                    access.addApplicationItem(moveToIt = false)
                    if (departure == "auto") access.transitionToApplicationItem(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                    else access.departWithPosition(Player.DISCONTINUITY_REASON_SEEK, 10.seconds)
                }
            }
        }
        if (stage == "prepare") {
            access.prepareAction = { depart() }
        } else {
            access.setMediaSourceAction = {
                // Registered after the coordinator, but before the source-install transition.
                access.addListener(object : Player.Listener {
                    var departed = false
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (!departed) {
                            departed = true
                            depart()
                        }
                    }
                })
            }
        }
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        val target = TestCoordinatorRecordingTarget()
        try {
            assertSame(PlaybackTargetResult.DEPARTED, coordinator.setRecordingTarget(target, RecordingPlaybackStart.RESUME))
            runCurrent()
            if (shutdown) coordinator.shutdown(1.seconds) else coordinator.stop()
            runCurrent()
            assertTrue(target.reports.isEmpty(), "Pending resume must not overwrite saved progress")
        } finally {
            coordinator.shutdown(1.seconds)
            owner.join()
        }
    }

    @ParameterizedTest
    @CsvSource("100,80", "20,0")
    fun `settled resume refreshes the closing snapshot before prepare can replace the item`(
        durationSeconds: Long,
        expectedPositionSeconds: Long,
    ) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        access.snapshot = access.snapshot.copy(duration = durationSeconds.seconds)
        access.prepareAction = { access.replaceWithApplicationItem() }
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(80.seconds) }
        val coordinator = TvheadendPlaybackCoordinator(
            player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource,
        )
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        val target = TestCoordinatorRecordingTarget()
        try {
            coordinator.setRecordingTarget(target, RecordingPlaybackStart.RESUME)
            coordinator.stop()
            runCurrent()
            assertEquals(expectedPositionSeconds.seconds, target.reports.single().second.position)
            assertFalse(target.reports.single().second.markWatched)
        } finally {
            coordinator.shutdown(1.seconds)
            owner.join()
        }
    }

    @ParameterizedTest
    @CsvSource("false", "true")
    fun `backward seek within the owned item supplies the new closing position`(pendingResume: Boolean) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ ->
            RecordingAdmission.Completed(if (pendingResume) 3_600.seconds else null)
        }
        val coordinator = TvheadendPlaybackCoordinator(
            player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource,
        )
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        val target = TestCoordinatorRecordingTarget()
        try {
            coordinator.setRecordingTarget(
                target, if (pendingResume) RecordingPlaybackStart.RESUME else RecordingPlaybackStart.START_OVER,
            )
            access.looperQueue.runOnLooper {
                access.snapshot = access.snapshot.copy(position = 3_000.seconds)
                access.deliverTimelineChange()
                access.seekWithinCoordinatorItem(600.seconds)
                access.replaceWithApplicationItem()
            }
            coordinator.stop()
            runCurrent()

            assertEquals(600.seconds, target.reports.single().second.position)
            assertFalse(target.reports.single().second.markWatched)
        } finally {
            coordinator.shutdown(1.seconds)
            owner.join()
        }
    }

    @Test
    fun `timeline only batches refresh owned snapshots but never reacquire a departed target`() = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val player = installedRecordingPlayer(access)
        access.addApplicationItem(moveToIt = false)
        access.looperQueue.runOnLooper {
            access.snapshot = access.snapshot.copy(position = 20.seconds)
            access.deliverTimelineChange()
            access.snapshot = access.snapshot.copy(position = 30.seconds)
            access.deliverTimelineChange()
            access.transitionToApplicationItem(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            access.seekBackToCoordinatorItem()
            access.snapshot = access.snapshot.copy(position = 50.seconds)
            access.deliverTimelineChange()
        }

        val retired = requireNotNull(onLooper(access) { player.stop(PlayerOperationTicket()) }.retiredRecording)

        assertEquals(30.seconds, retired.snapshot.position)
        assertFalse(retired.allowWatched)
    }

    @Test
    fun `retirement cannot report a recording that never had an owned snapshot`() = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        access.setMediaSourceAction = { access.replaceWithApplicationItem() }
        val player = installedRecordingPlayer(access, PlaybackPlayerInstallStatus.DEPARTED)

        val result = onLooper(access) { player.stop(PlayerOperationTicket()) }

        assertTrue(result.retiredTarget)
        assertNull(result.retiredRecording)
        assertEquals("application", access.currentSourceKind)
    }

    @Test
    fun `live auto advance loses ownership without a natural terminal`() = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val token = PlaybackTargetToken()
        onLooper(access) {
            player.installLive(
                PlayerOperationTicket(), token, TestCoordinatorLiveTarget(), SubscriptionOptions(), controls(token),
            )
        }
        access.addApplicationItem(moveToIt = false)
        access.transitionToApplicationItem(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertFalse(token.ownsPlayerItem())
        assertNull(events.take())
        assertNull(onLooper(access) { player.snapshot(token) })
        assertNull(onLooper(access) { player.stop(PlayerOperationTicket()) }.retiredRecording)
    }

    @ParameterizedTest
    @CsvSource(
        "false,false,false", "false,true,false", "true,false,false", "true,true,false",
        "false,false,true", "false,true,true", "true,false,true", "true,true,true",
    )
    fun `failed replacement preserves consumed recording resume position and subsequent reports`(
        replaceWithRecording: Boolean,
        failPrepare: Boolean,
        restoreFails: Boolean,
    ) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        access.snapshot = access.snapshot.copy(duration = 100.seconds, playbackState = Player.STATE_READY)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, start ->
            RecordingAdmission.Completed(if (start.start == RecordingPlaybackStart.RESUME) 10.seconds else null)
        }
        val coordinator = TvheadendPlaybackCoordinator(
            player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource,
        )
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        try {
            val target = TestCoordinatorRecordingTarget()
            assertSame(PlaybackTargetResult.STARTED, coordinator.setRecordingTarget(target, RecordingPlaybackStart.RESUME))
            assertEquals(listOf(10.seconds), access.resumeSeeks)
            assertEquals(10.seconds, access.snapshot.position)
            access.snapshot = access.snapshot.copy(position = 40.seconds)
            val previousListener = access.applicationListener
            if (failPrepare) {
                access.prepareAction = {
                    access.prepareAction = null
                    throw IOException("scripted replacement preparation failure")
                }
            } else {
                access.failNextSetMediaSource = true
            }
            if (restoreFails) {
                var installations = 0
                access.setMediaSourceAction = {
                    installations += 1
                    if (installations == 2) {
                        access.snapshot = access.snapshot.copy(
                            position = Duration.ZERO, duration = 1.seconds, playbackState = Player.STATE_ENDED,
                        )
                        throw IOException("scripted restoration failure")
                    }
                }
            }

            val result = if (replaceWithRecording) {
                coordinator.setRecordingTarget(TestCoordinatorRecordingTarget(), RecordingPlaybackStart.START_OVER)
            } else {
                coordinator.setLiveTarget(TestCoordinatorLiveTarget())
            }
            assertSame(PlaybackTargetResult.PLAYER_UNAVAILABLE, result)
            if (restoreFails) {
                runCurrent()
                assertNull(access.applicationListener)
                assertEquals("none", access.currentSourceKind)
                val finalReport = target.reports.single().second
                assertEquals(40.seconds, finalReport.position)
                assertFalse(finalReport.markWatched)
                return@runTest
            }
            assertSame(previousListener, access.applicationListener)
            assertEquals("recording", access.currentSourceKind)
            assertEquals(40.seconds, access.snapshot.position)
            assertEquals(listOf(10.seconds), access.resumeSeeks)
            assertTrue(target.reports.isEmpty(), "Rollback must retain the recording report target")

            access.applicationListener?.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            runCurrent()
            assertEquals(40.seconds, target.reports.single().second.position)
            coordinator.stop()
            runCurrent()
            assertEquals(listOf(40.seconds, 40.seconds), target.reports.map { it.second.position })
        } finally {
            coordinator.shutdown(1.seconds)
            owner.join()
        }
    }

    @Test
    fun `restoring an ended recording does not duplicate terminal progress`() = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(
            player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource,
        )
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        val target = TestCoordinatorRecordingTarget()
        try {
            coordinator.setRecordingTarget(target, RecordingPlaybackStart.START_OVER)
            access.snapshot = access.snapshot.copy(
                position = 100.seconds, duration = 100.seconds, playbackState = Player.STATE_ENDED,
            )
            access.applicationListener?.onPlaybackStateChanged(Player.STATE_ENDED)
            runCurrent()
            assertTrue(target.reports.single().second.markWatched)
            access.failNextSetMediaSource = true
            access.prepareAction = { access.applicationListener?.onPlaybackStateChanged(Player.STATE_ENDED) }

            assertSame(PlaybackTargetResult.PLAYER_UNAVAILABLE, coordinator.setLiveTarget(TestCoordinatorLiveTarget()))
            runCurrent()
            assertEquals(100.seconds, access.snapshot.position)
            assertEquals(1, target.reports.size)
        } finally {
            coordinator.shutdown(1.seconds)
            owner.join()
        }
        assertEquals(1, target.reports.size)
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
                "remove-recording",
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
        assertTrue(access.operations.takeLast(2) == listOf("stop", "remove-live"))
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
    fun `stop cleanup failure retires the live target and reports the player unavailable`() = runTest {
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

        val stop = async { player.stop(PlayerOperationTicket()) }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()

        val result = stop.await()
        assertFalse(result.playerAvailable)
        assertTrue(result.retiredTarget)
        assertFalse(liveToken.isActive())
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

    @ParameterizedTest
    @CsvSource("own,false", "own,true", "replace,false", "replace,true", "clear,false", "clear,true")
    fun `stop and shutdown clear and report only the coordinator's own recording item`(
        applicationAction: String,
        shutdown: Boolean,
    ) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(
            player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource,
        )
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        val target = TestCoordinatorRecordingTarget()
        try {
            assertSame(
                PlaybackTargetResult.STARTED,
                coordinator.setRecordingTarget(target, RecordingPlaybackStart.START_OVER),
            )
            access.snapshot = access.snapshot.copy(
                position = 30.seconds, duration = 100.seconds, playbackState = Player.STATE_READY,
            )
            access.deliverTimelineChange()
            val applicationReplaced = applicationAction != "own"
            if (applicationReplaced) {
                if (applicationAction == "clear") access.clearApplicationPlaylist() else access.replaceWithApplicationItem()
                access.snapshot = access.snapshot.copy(position = 100.seconds, playbackState = Player.STATE_ENDED)
                val listener = access.applicationListener
                listener?.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                listener?.onPlaybackStateChanged(Player.STATE_ENDED)
                listener?.onPlayerError(PlaybackException("safe", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
                runCurrent()
                assertTrue(target.reports.isEmpty(), "The application's item must not report recording progress")
            }
            access.operations.clear()

            if (shutdown) {
                assertSame(PlaybackShutdownResult.DRAINED, coordinator.shutdown(1.seconds))
            } else {
                assertEquals(
                    if (applicationReplaced) PlaybackStopResult.retiredAfterDeparture(null)
                    else PlaybackStopResult.stopped(null),
                    coordinator.stop(),
                )
            }
            runCurrent()

            if (applicationReplaced) {
                assertEquals(listOf("close-resume", "remove-listener"), access.operations)
                assertEquals(if (applicationAction == "clear") "none" else "application", access.currentSourceKind)
            } else {
                assertEquals(listOf("close-resume", "remove-listener", "stop", "remove-recording"), access.operations)
                assertEquals("none", access.currentSourceKind)
            }
            val finalReport = target.reports.single().second
            assertEquals(30.seconds, finalReport.position)
            assertFalse(finalReport.markWatched)
            assertSame(if (shutdown) PlaybackStopResult.SHUT_DOWN else PlaybackStopResult.ALREADY_STOPPED, coordinator.stop())
            runCurrent()
            assertEquals(1, target.reports.size)
        } finally {
            coordinator.shutdown(1.seconds)
            owner.join()
        }
    }

    @ParameterizedTest
    @CsvSource("false", "true")
    fun `a new target closes an application-replaced recording at its last owned position without restoring it`(
        installFails: Boolean,
    ) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(
            player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource,
        )
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        val target = TestCoordinatorRecordingTarget()
        try {
            assertSame(
                PlaybackTargetResult.STARTED,
                coordinator.setRecordingTarget(target, RecordingPlaybackStart.START_OVER),
            )
            access.snapshot = access.snapshot.copy(position = 25.seconds)
            access.deliverTimelineChange()
            access.replaceWithApplicationItem()
            access.snapshot = access.snapshot.copy(
                position = 100.seconds, duration = 100.seconds, playbackState = Player.STATE_ENDED,
            )
            access.operations.clear()
            access.failNextSetMediaSource = installFails

            val result = coordinator.setLiveTarget(TestCoordinatorLiveTarget())
            runCurrent()

            val replacedRetirement = listOf("close-resume", "remove-listener")
            val install = listOf("create-live", "add-listener", "create-recovery", "begin-recovery", "set-live")
            if (installFails) {
                assertSame(PlaybackTargetResult.PLAYER_UNAVAILABLE, result)
                assertEquals(
                    replacedRetirement + install + listOf("close-recovery", "remove-listener", "stop", "remove-live"),
                    access.operations,
                )
                assertSame(PlaybackStopResult.ALREADY_STOPPED, coordinator.stop())
            } else {
                assertSame(PlaybackTargetResult.STARTED, result)
                assertEquals(replacedRetirement + install + "prepare", access.operations)
                assertEquals(PlaybackStopResult.stopped(null), coordinator.stop())
            }
            runCurrent()
            assertEquals(25.seconds, target.reports.single().second.position)
            assertFalse(target.reports.single().second.markWatched)
        } finally {
            coordinator.shutdown(1.seconds)
            owner.join()
        }
    }

    @Test
    fun `an application-replaced live item fences snapshots and recovery and stop leaves it untouched`() = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ ->
            RecordingAdmission.Completed(null)
        }
        val token = PlaybackTargetToken()
        val live = async {
            player.installLive(
                PlayerOperationTicket(),
                token,
                TestCoordinatorLiveTarget(),
                timeshiftControls = controls(token),
            )
        }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()
        assertEquals(PlaybackPlayerInstallStatus.STARTED, live.await().status)
        val owned = async { player.snapshot(token) }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()
        assertTrue(owned.await() != null)
        access.looperQueue.runOnLooper { assertTrue(requireNotNull(access.recoveryTargetCurrent).invoke()) }

        access.replaceWithApplicationItem()
        access.looperQueue.runOnLooper {
            assertFalse(requireNotNull(access.recoveryTargetCurrent).invoke())
            access.recoveryCallback?.invoke(PlaybackRecoveryReason.LIVE_ENDED)
        }
        assertNull(events.take())
        val replaced = async { player.snapshot(token) }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()
        assertNull(replaced.await())

        access.operations.clear()
        val stop = async { player.stop(PlayerOperationTicket()) }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()
        val result = stop.await()
        assertTrue(result.playerAvailable)
        assertTrue(result.retiredTarget)
        assertFalse(token.isActive())
        assertEquals(listOf("close-recovery", "remove-listener"), access.operations)
        assertEquals("application", access.currentSourceKind)
    }

    @ParameterizedTest
    @CsvSource("replace,false", "add,false", "replace,true")
    fun `stop keeps the items an application installs from its own stop callback`(
        reaction: String,
        stopFails: Boolean,
    ) = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val player = installedRecordingPlayer(access)
        // An application listener reacting synchronously to the stop, as Media3 permits.
        access.stopAction = {
            if (reaction == "replace") access.replaceWithApplicationItem() else access.addApplicationItem(moveToIt = false)
        }
        access.failNextStop = stopFails
        access.operations.clear()

        val result = onLooper(access) { player.stop(PlayerOperationTicket()) }

        assertTrue(result.retiredTarget)
        assertEquals(!stopFails, result.playerAvailable)
        assertEquals(listOf("application"), access.playlistKinds)
        val ownRemoval = if (reaction == "add") listOf("remove-recording") else emptyList()
        assertEquals(listOf("close-resume", "remove-listener", "stop") + ownRemoval, access.operations)
    }

    @ParameterizedTest
    @CsvSource("false", "true")
    fun `retirement removes only the coordinator's item from a mixed playlist`(
        applicationItemCurrent: Boolean,
    ) = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val player = installedRecordingPlayer(access)
        access.addApplicationItem(moveToIt = applicationItemCurrent)
        access.operations.clear()

        val result = onLooper(access) { player.stop(PlayerOperationTicket()) }

        assertTrue(result.retiredTarget)
        assertSame(
            at.bernhardberger.tvheadend.sdk.core.DvrPlaybackExit.ORDERLY,
            requireNotNull(result.retiredRecording).exit,
        )
        assertEquals(listOf("application"), access.playlistKinds)
        val stop = if (applicationItemCurrent) emptyList() else listOf("stop")
        assertEquals(listOf("close-resume", "remove-listener") + stop + "remove-recording", access.operations)
    }

    @ParameterizedTest
    @CsvSource(
        "false,stop,false", "true,stop,false", "false,shutdown,false", "true,shutdown,false",
        "false,replace,false", "true,replace,false", "false,stop,true", "true,stop,true",
        "false,shutdown,true", "true,shutdown,true", "false,replace,true", "true,replace,true",
    )
    fun `a terminal the application answers by replacing the item still ends the recording`(
        error: Boolean,
        retirement: String,
        eventConsumedFirst: Boolean,
    ) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(
            player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource,
        )
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        val target = TestCoordinatorRecordingTarget()
        try {
            assertSame(
                PlaybackTargetResult.STARTED,
                coordinator.setRecordingTarget(target, RecordingPlaybackStart.START_OVER),
            )
            // Preparation publishes the duration through a timeline update; playback nears the end.
            access.snapshot = access.snapshot.copy(
                position = 97.seconds, duration = 100.seconds, playbackState = Player.STATE_READY,
            )
            access.looperQueue.runOnLooper {
                access.deliverTimelineChange()
            }
            // Registered before the coordinator, this listener sees the terminal first and moves on.
            access.addApplicationListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) playApplicationItem()
                    }

                    override fun onPlayerError(error: PlaybackException) = playApplicationItem()

                    fun playApplicationItem() {
                        access.replaceWithApplicationItem()
                        access.snapshot = PlaybackPlayerSnapshot(
                            Duration.ZERO, 5.seconds, Player.STATE_BUFFERING, failed = false,
                        )
                    }
                },
            )

            val retire = async(start = CoroutineStart.LAZY) {
                when (retirement) {
                    "stop" -> assertEquals(PlaybackStopResult.retiredAfterDeparture(null), coordinator.stop())
                    "shutdown" -> coordinator.shutdown(1.seconds)
                    "replace" -> assertSame(
                        PlaybackTargetResult.STARTED, coordinator.setLiveTarget(TestCoordinatorLiveTarget()),
                    )
                }
            }
            if (!eventConsumedFirst) {
                access.looperQueue.current = false
                retire.start()
                runCurrent()
                // The actor now awaits retirement on the looper and cannot drain player events.
                assertFalse(retire.isCompleted)
            }
            access.looperQueue.runOnLooper {
                if (error) {
                    access.deliver {
                        it.onPlayerError(PlaybackException("safe", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
                    }
                } else {
                    access.deliver { it.onPlaybackStateChanged(Player.STATE_ENDED) }
                }
            }
            if (eventConsumedFirst) {
                runCurrent()
                assertEquals(1, target.reports.size)
                retire.start()
                runCurrent()
            } else {
                assertTrue(target.reports.isEmpty())
            }
            access.operations.clear()
            access.looperQueue.current = true
            access.looperQueue.runAll()
            runCurrent()
            retire.await()

            val terminal = target.reports.single().second
            assertEquals(if (error) 97.seconds else 100.seconds, terminal.position)
            assertEquals(!error, terminal.markWatched)
            if (retirement != "replace" && !eventConsumedFirst) {
                assertEquals(listOf("close-resume", "remove-listener"), access.operations)
                assertEquals("application", access.currentSourceKind)
            }
        } finally {
            access.looperQueue.current = true
            val shutdown = async { coordinator.shutdown(1.seconds) }
            runCurrent()
            access.looperQueue.runAll()
            runCurrent()
            shutdown.await()
            owner.join()
        }
        assertEquals(1, target.reports.size)
    }

    @ParameterizedTest
    @CsvSource("false", "true")
    fun `an application replacement fences a published recovery before the coordinator consumes it`(
        replaced: Boolean,
    ) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val recoveries = mutableListOf<PlaybackRecoveryReason>()
        val coordinator = TvheadendPlaybackCoordinator(
            player, events, DvrProgressPolicy(), { recoveries += it }, SystemPlaybackCoordinatorTimeSource,
        )
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        try {
            assertSame(PlaybackTargetResult.STARTED, coordinator.setLiveTarget(TestCoordinatorLiveTarget()))

            access.recoveryCallback?.invoke(PlaybackRecoveryReason.LIVE_ENDED)
            if (replaced) access.replaceWithApplicationItem()
            runCurrent()

            assertEquals(if (replaced) emptyList() else listOf(PlaybackRecoveryReason.LIVE_ENDED), recoveries)
        } finally {
            coordinator.shutdown(1.seconds)
            owner.join()
        }
    }

    @Test
    fun `an application replacement fences a progress report still waiting for the report worker`() = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ -> RecordingAdmission.Completed(null) }
        val coordinator = TvheadendPlaybackCoordinator(
            player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource,
        )
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        val target = TestCoordinatorRecordingTarget()
        val upload = CompletableDeferred<Unit>()
        target.reportGate = { upload.await() }
        try {
            assertSame(
                PlaybackTargetResult.STARTED,
                coordinator.setRecordingTarget(target, RecordingPlaybackStart.START_OVER),
            )
            val listener = access.applicationListener
            access.snapshot = access.snapshot.copy(
                position = 20.seconds, duration = 100.seconds, playbackState = Player.STATE_READY,
            )
            listener?.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            runCurrent()
            assertEquals(listOf(20.seconds), target.reports.map { it.second.position })
            access.snapshot = access.snapshot.copy(position = 30.seconds)
            listener?.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            runCurrent()

            access.replaceWithApplicationItem()
            upload.complete(Unit)
            runCurrent()

            assertEquals(listOf(20.seconds), target.reports.map { it.second.position })
            assertEquals(PlaybackStopResult.retiredAfterDeparture(null), coordinator.stop())
            runCurrent()
            assertEquals(listOf(20.seconds, 30.seconds), target.reports.map { it.second.position })
            assertFalse(target.reports.last().second.markWatched)
        } finally {
            upload.complete(Unit)
            coordinator.shutdown(1.seconds)
            owner.join()
        }
    }

    @Test
    fun `pending coordinator resume cannot seek after the application replaces the item`() = runTest {
        val access = FakeCoordinatorPlaybackAccess()
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
            RecordingAdmission.Completed(80.seconds)
        }
        onLooper(access) {
            player.installRecording(
                PlayerOperationTicket(), PlaybackTargetToken(), TestCoordinatorRecordingTarget(),
                RecordingPlaybackStart.RESUME,
            )
        }
        assertTrue(access.resumeSeeks.isEmpty())

        access.replaceWithApplicationItem()
        access.deliverResumeMediaState(100_000, isSeekable = true)

        assertTrue(access.resumeSeeks.isEmpty())
        onLooper(access) { player.stop(PlayerOperationTicket()) }
    }

    private fun TestScope.installedRecordingPlayer(
        access: FakeCoordinatorPlaybackAccess,
        expectedStatus: PlaybackPlayerInstallStatus = PlaybackPlayerInstallStatus.STARTED,
    ): Media3PlaybackCoordinatorPlayer {
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator()) { _, _ ->
            RecordingAdmission.Completed(null)
        }
        val install = async {
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
        assertEquals(expectedStatus, install.getCompleted().status)
        return player
    }

    private fun <T> TestScope.onLooper(access: FakeCoordinatorPlaybackAccess, operation: suspend () -> T): T {
        val result = async { operation() }
        runCurrent()
        access.looperQueue.runAll()
        runCurrent()
        return result.getCompleted()
    }

    @Test
    fun `repeated recovery carries owed milliseconds and suppresses early closing progress`() = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events, ::admitRecordingTarget)
        val coordinator = TvheadendPlaybackCoordinator(player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource)
        val owner = coordinator.launchIn(this)
        val target = TestCoordinatorRecordingTarget()
        try {
            assertSame(PlaybackTargetResult.STARTED, coordinator.recoverRecordingTarget(target, 80_123.milliseconds))
            assertEquals(80_123.milliseconds, coordinator.pendingRecordingRecoveryPosition.value)
            val oldResume = requireNotNull(access.resumeState)
            val oldCallback = access.resumePendingCallbacks.last()
            val oldTimeout = access.resumeTimeouts.pending.last()
            assertSame(PlaybackTargetResult.STARTED, coordinator.recoverRecordingTarget(
                target, requireNotNull(coordinator.pendingRecordingRecoveryPosition.value),
            ))
            runCurrent()
            assertTrue(target.reports.isEmpty())
            access.looperQueue.runOnLooper {
                oldResume.onViewerSeek()
                oldTimeout.action()
                oldCallback(null)
            }
            assertEquals(80_123.milliseconds, coordinator.pendingRecordingRecoveryPosition.value)
            coordinator.stop()
            runCurrent()
            assertNull(coordinator.pendingRecordingRecoveryPosition.value)
            assertTrue(target.reports.isEmpty())
            assertTrue(access.resumeSeeks.isEmpty())
        } finally {
            owner.shutdown(1.seconds)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["seek", "departure", "stop", "shutdown", "cancel-owner", "live", "recording"])
    fun `pending recovery clears at every ownership boundary and late callbacks stay fenced`(boundary: String) = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events, ::admitRecordingTarget)
        val coordinator = TvheadendPlaybackCoordinator(player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource)
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        try {
            coordinator.recoverRecordingTarget(TestCoordinatorRecordingTarget(), 80.seconds)
            val oldCallback = access.resumePendingCallbacks.last()
            val oldResume = requireNotNull(access.resumeState)
            when (boundary) {
                "seek" -> access.looperQueue.runOnLooper { oldResume.onViewerSeek() }
                "departure" -> access.looperQueue.runOnLooper { access.replaceWithApplicationItem() }
                "stop" -> coordinator.stop()
                "shutdown" -> coordinator.shutdown(1.seconds)
                "cancel-owner" -> { owner.cancel(); owner.join() }
                "live" -> coordinator.setLiveTarget(TestCoordinatorLiveTarget())
                "recording" -> coordinator.setRecordingTarget(TestCoordinatorRecordingTarget())
            }
            assertNull(coordinator.pendingRecordingRecoveryPosition.value)
            access.looperQueue.runOnLooper {
                oldCallback(null)
                access.resumeTimeouts.fireAll()
            }
            assertNull(coordinator.pendingRecordingRecoveryPosition.value)
            assertTrue(access.resumeSeeks.isEmpty())
        } finally {
            coordinator.shutdown(1.seconds)
            owner.join()
        }
    }

    @Test
    fun `completed recovery observation outlives bounded progress hold then clears on exact seek`() = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator(), ::admitRecordingTarget)
        val token = PlaybackTargetToken()
        player.installRecording(PlayerOperationTicket(), token, TestCoordinatorRecordingTarget(), RecordingPlaybackStart.RESUME,
            RecordingRecoveryRequest(80_123.milliseconds))
        access.looperQueue.runOnLooper { access.resumeTimeouts.fireAll() }
        assertEquals(80_123.milliseconds, player.pendingRecordingRecoveryPosition.value)
        assertNull(player.snapshot(token)?.pendingResumePosition)
        access.deliverResumeMediaState(100_000, true)
        assertEquals(listOf(80_123.milliseconds), access.resumeSeeks)
        assertNull(player.pendingRecordingRecoveryPosition.value)
        player.abandon()
    }

    @Test
    fun `coordinator applies configured early fallback and late cutoff without a second bookmark seek`() = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events, ::admitRecordingTarget)
        val coordinator = TvheadendPlaybackCoordinator(player, events,
            DvrProgressPolicy(orderlyCompletionFraction = 0.8), {}, SystemPlaybackCoordinatorTimeSource)
        val owner = coordinator.launchIn(this)
        val target = TestCoordinatorRecordingTarget(admissionState = completedCoordinatorAdmission(30.seconds))
        try {
            coordinator.recoverRecordingTarget(target, 80.seconds, 100.seconds)
            assertNull(coordinator.pendingRecordingRecoveryPosition.value)
            access.deliverResumeMediaState(100_000, true)
            assertEquals(listOf(30.seconds), access.resumeSeeks)
            access.snapshot = access.snapshot.copy(duration = null, position = Duration.ZERO)
            coordinator.recoverRecordingTarget(target, 80.seconds)
            assertEquals(80.seconds, coordinator.pendingRecordingRecoveryPosition.value)
            access.deliverResumeMediaState(100_000, false)
            assertNull(coordinator.pendingRecordingRecoveryPosition.value)
            access.deliverResumeMediaState(100_000, true)
            assertEquals(listOf(30.seconds), access.resumeSeeks)
        } finally {
            owner.shutdown(1.seconds)
        }
    }

    @Test
    fun `growing explicit recovery publishes owed position until clamp or timeout`() = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val player = Media3PlaybackCoordinatorPlayer(access, PlaybackPlayerEventAccumulator(), ::admitRecordingTarget)
        val target = TestCoordinatorRecordingTarget(
            startedGrowing = true,
            admissionState = CoordinatorRecordingAdmission.Growing(at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability.SUPPORTED),
            growingResult = RecordingFileResult.Ok(CurrentGrowingLease),
        )
        repeat(2) { attempt ->
            player.installRecording(PlayerOperationTicket(), PlaybackTargetToken(), target, RecordingPlaybackStart.START_OVER,
                RecordingRecoveryRequest(95.seconds, 100.seconds))
            assertEquals(95.seconds, player.pendingRecordingRecoveryPosition.value)
            access.deliverResumeMediaState(3_000, true)
            assertEquals(95.seconds, player.pendingRecordingRecoveryPosition.value)
            if (attempt == 0) {
                access.deliverResumeMediaState(12_000, true)
                assertEquals(listOf(9.seconds), access.resumeSeeks)
            } else access.looperQueue.runOnLooper { access.resumeTimeouts.fireAll() }
            assertNull(player.pendingRecordingRecoveryPosition.value)
        }
        player.abandon()
    }

    @Test
    fun `cancelled recovery before looper claim leaves previous pending target untouched`() = runTest {
        val access = FakeCoordinatorPlaybackAccess(looperInitiallyCurrent = true)
        val events = PlaybackPlayerEventAccumulator()
        val player = Media3PlaybackCoordinatorPlayer(access, events, ::admitRecordingTarget)
        val coordinator = TvheadendPlaybackCoordinator(player, events, DvrProgressPolicy(), {}, SystemPlaybackCoordinatorTimeSource)
        val owner = coordinator.launchIn(this)
        try {
            coordinator.recoverRecordingTarget(TestCoordinatorRecordingTarget(), 80.seconds)
            access.looperQueue.current = false
            val cancelled = async { coordinator.recoverRecordingTarget(TestCoordinatorRecordingTarget(), 90.seconds) }
            runCurrent()
            cancelled.cancel()
            runCurrent()
            access.looperQueue.runAll()
            runCurrent()
            assertThrows(CancellationException::class.java) { cancelled.getCompleted() }
            assertEquals(80.seconds, coordinator.pendingRecordingRecoveryPosition.value)
        } finally {
            access.looperQueue.current = true
            access.looperQueue.runAll()
            owner.shutdown(1.seconds)
        }
        assertNull(coordinator.pendingRecordingRecoveryPosition.value)
    }
}

private class CleanupFaults(var enabled: Boolean = true)

private class FakeCoordinatorPlaybackAccess(
    private val failSetMediaSource: Boolean = false,
    looperInitiallyCurrent: Boolean = false,
    private val cleanupFaults: CleanupFaults? = null,
    private val recoveryScheduler: RecoveryScheduler? = null,
) : CoordinatorPlaybackAccess {
    val looperQueue = TestCoordinatorLooper(looperInitiallyCurrent)
    override val looper: CoordinatorLooper = looperQueue
    val operations = mutableListOf<String>()
    var snapshot = PlaybackPlayerSnapshot(Duration.ZERO, null, Player.STATE_IDLE, failed = false)
    var playWhenReady = false
    private val listeners = mutableListOf<Player.Listener>()
    val applicationListener: Player.Listener?
        get() = listeners.lastOrNull()
    private val queuedEvents = ArrayDeque<(Player.Listener) -> Unit>()
    private var flushingEvents = false

    /** The playlist: coordinator sources, or names of items the application set itself. */
    private val playlist = mutableListOf<Any>()
    private var currentIndex = 0
    var recoveryCallback: ((PlaybackRecoveryReason) -> Unit)? = null
    var recoveryBuffering: (() -> Unit)? = null
    var recoveryTargetCurrent: (() -> Boolean)? = null
    var growingFinalEndCallback: (() -> Unit)? = null
    var growingLease: GrowingRecordingFileLease? = null
    var liveOptions: SubscriptionOptions? = null
    var liveControls: LiveTimeshiftControlBridge? = null
    var snapshotAction: (() -> Unit)? = null
    var failRemove = false
    val currentSourceKind: String
        get() = kindOf(playlist.getOrNull(currentIndex))
    val playlistKinds: List<String>
        get() = playlist.map(::kindOf)
    var failNextSetMediaSource = failSetMediaSource
    var failNextStop = false
    var stopAction: (() -> Unit)? = null
    var failRecoveryClose = false
    var failNextRemoveListener = false
    var prepareAction: (() -> Unit)? = null
    var removeListenerAction: (() -> Unit)? = null
    var setMediaSourceAction: ((String) -> Unit)? = null
    val resumeSeeks = mutableListOf<Duration>()

    override fun requireApplicationLooper() {
        check(looperQueue.isCurrent())
    }

    override fun snapshot(): PlaybackPlayerSnapshot {
        requireApplicationLooper()
        snapshotAction?.invoke()
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
        liveControls = timeshiftControls
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
        isTargetCurrent: () -> Boolean,
        onRecoveryRequired: (PlaybackRecoveryReason) -> Unit,
    ): CoordinatorPlaybackRecovery {
        requireApplicationLooper()
        operations += "create-recovery"
        recoveryCallback = onRecoveryRequired
        recoveryTargetCurrent = isTargetCurrent
        val recovery = recoveryScheduler?.let { scheduler ->
            PlaybackRecoveryStateMachine(
                PlaybackRecoveryPolicy(), scheduler, hasSelectedAudio = { true }, isAudioDisabled = { false },
                setAudioDisabled = { operations += "audio-disabled:$it" },
                onRecoveryRequired = onRecoveryRequired, isTargetCurrent = isTargetCurrent,
            )
        }
        recoveryBuffering = { recovery?.onPlaybackStateChanged(Player.STATE_BUFFERING) }
        return object : CoordinatorPlaybackRecovery {
            override fun beginPlaybackTarget() {
                requireApplicationLooper()
                operations += "begin-recovery"
                recovery?.beginPlaybackTarget()
            }

            override fun close() {
                requireApplicationLooper()
                operations += "close-recovery"
                if (failRecoveryClose) throw IOException("scripted recovery close failure")
                recovery?.close()
            }
        }
    }

    val resumeTimeouts = FakeResumeTimeouts()
    var resumeState: RecordingResumeStateMachine? = null
    val resumePendingCallbacks = mutableListOf<(Duration?) -> Unit>()
    private var resumeTargetCurrent: () -> Boolean = { false }

    /** Delivers a media state to the installed resume helper as the player listener would. */
    fun deliverResumeMediaState(durationMillis: Long?, isSeekable: Boolean) {
        looperQueue.runOnLooper { resumeState?.onMediaState(resumeTargetCurrent(), durationMillis, isSeekable) }
    }

    override fun createResume(
        identity: RecordingMediaIdentity,
        mode: RecordingResumeMode,
        onProgressFloorChanged: (Duration?) -> Unit,
        onPendingPositionChanged: (Duration?) -> Unit,
        isTargetCurrent: () -> Boolean,
    ): CoordinatorRecordingResume {
        requireApplicationLooper()
        operations += if (mode == RecordingResumeMode.GROWING) "create-growing-resume" else "create-resume"
        resumePendingCallbacks += onPendingPositionChanged
        val resume = RecordingResumeStateMachine(
            seekTo = { positionMillis ->
                resumeSeeks += positionMillis.milliseconds
                snapshot = snapshot.copy(position = positionMillis.milliseconds)
            },
            scheduleTimeout = resumeTimeouts::schedule,
            onProgressFloorChanged = { onProgressFloorChanged(it?.milliseconds) },
            onPendingPositionChanged = { onPendingPositionChanged(it?.milliseconds) },
        )
        resumeState = resume
        resumeTargetCurrent = isTargetCurrent
        return object : CoordinatorRecordingResume {
            override val progressFloor: Duration?
                get() = resume.progressFloorMillis?.milliseconds

            override fun beginPlaybackTarget(position: Duration?, lateCompletionFraction: Double?) {
                requireApplicationLooper()
                check(isTargetCurrent()) { "Recording resume target must already be installed on the player" }
                operations += "begin-resume:${position?.inWholeSeconds ?: 0}"
                resume.beginPlaybackTarget(position?.inWholeMilliseconds, mode, lateCompletionFraction)
                resume.onMediaState(isTargetCurrent(), snapshot.duration?.inWholeMilliseconds, isSeekable = true)
            }

            override fun close() {
                requireApplicationLooper()
                operations += "close-resume"
                resume.close()
            }
        }
    }

    override fun isCurrent(source: CoordinatorMediaSource): Boolean {
        requireApplicationLooper()
        return playlist.getOrNull(currentIndex) === source
    }

    override fun ownsItem(source: CoordinatorMediaSource, item: MediaItem?): Boolean =
        (source as FakeCoordinatorMediaSource).item === item

    val currentItem: MediaItem?
        get() = itemOf(playlist.getOrNull(currentIndex))

    private val applicationItem = MediaItem.Builder().build()

    private fun itemOf(entry: Any?): MediaItem? = when (entry) {
        is FakeCoordinatorMediaSource -> entry.item
        null -> null
        else -> applicationItem
    }

    /**
     * Delivers [event] to every listener as Media3's listener set does: an event raised while
     * another is being delivered, such as a listener changing the playlist, queues behind it.
     */
    fun deliver(event: (Player.Listener) -> Unit) {
        queuedEvents += event
        flushEvents()
    }

    private val batchFlags = mutableSetOf<Int>()
    // onEvents receives a Player, but the coordinator must not read it to attribute delivered events.
    private val eventPlayer: Player = java.lang.reflect.Proxy.newProxyInstance(
        Player::class.java.classLoader, arrayOf(Player::class.java),
    ) { _, _, _ -> error("Unexpected event player access") } as Player

    fun deliverBatch(flags: IntArray, vararg callbacks: (Player.Listener) -> Unit) {
        batchFlags.addAll(flags.toList())
        queuedEvents.addAll(callbacks)
        flushEvents()
    }

    fun deliverTimelineChange() = deliverBatch(
        intArrayOf(Player.EVENT_TIMELINE_CHANGED),
        { it.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) },
    )

    private fun flushEvents() {
        if (flushingEvents) return
        flushingEvents = true
        try {
            while (queuedEvents.isNotEmpty()) {
                val next = queuedEvents.removeFirst()
                listeners.toList().forEach { listener -> if (listener in listeners) next(listener) }
            }
            if (batchFlags.isNotEmpty()) {
                val flags = androidx.media3.common.FlagSet.Builder().addAll(*batchFlags.toIntArray()).build()
                batchFlags.clear()
                listeners.toList().forEach { it.onEvents(eventPlayer, Player.Events(flags)) }
            }
        } finally {
            flushingEvents = false
        }
    }

    /** Registers an application listener ahead of every coordinator listener. */
    fun addApplicationListener(listener: Player.Listener) {
        listeners.add(0, listener)
    }

    /** Replaces the whole playlist with an application item, as `setMediaItem` would. */
    fun replaceWithApplicationItem() = looperQueue.runOnLooper {
        playlist.clear()
        playlist += "application"
        currentIndex = 0
        notifyPlaylistChanged()
    }

    fun clearApplicationPlaylist() = looperQueue.runOnLooper {
        playlist.clear()
        currentIndex = 0
        notifyPlaylistChanged()
    }

    fun transitionToApplicationItem(reason: Int, timelineChanged: Boolean = false) = looperQueue.runOnLooper {
        currentIndex = playlist.lastIndex
        snapshot = snapshot.copy(position = Duration.ZERO, duration = 5.seconds, playbackState = Player.STATE_READY)
        val item = currentItem
        if (timelineChanged) {
            deliverBatch(
                intArrayOf(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION),
                { it.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) },
                { it.onMediaItemTransition(item, reason) },
            )
        } else {
            deliverBatch(intArrayOf(Player.EVENT_MEDIA_ITEM_TRANSITION), { it.onMediaItemTransition(item, reason) })
        }
    }

    fun seekBackToCoordinatorItem() = looperQueue.runOnLooper {
        currentIndex = 0
        snapshot = snapshot.copy(position = 3.seconds, duration = 100.seconds)
        val item = currentItem
        deliverBatch(
            intArrayOf(Player.EVENT_MEDIA_ITEM_TRANSITION),
            { it.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) },
        )
    }

    fun seekWithinCoordinatorItem(position: Duration) = looperQueue.runOnLooper {
        val item = currentItem
        val old = positionInfo(item, currentIndex, snapshot.position)
        val next = positionInfo(item, currentIndex, position)
        snapshot = snapshot.copy(position = position)
        deliverBatch(
            intArrayOf(Player.EVENT_POSITION_DISCONTINUITY),
            { it.onPositionDiscontinuity(old, next, Player.DISCONTINUITY_REASON_SEEK) },
        )
    }

    fun departWithPosition(reason: Int, position: Duration, foreignOldItem: Boolean = false) = looperQueue.runOnLooper {
        val old = positionInfo(if (foreignOldItem) applicationItem else currentItem, currentIndex, position)
        if (reason == Player.DISCONTINUITY_REASON_REMOVE) playlist.removeAt(currentIndex)
        currentIndex = playlist.lastIndex
        val next = positionInfo(currentItem, currentIndex, Duration.ZERO)
        snapshot = snapshot.copy(position = Duration.ZERO, duration = 5.seconds)
        val item = currentItem
        val transitionReason = when (reason) {
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
            Player.DISCONTINUITY_REASON_REMOVE -> Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            else -> Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
        }
        deliverBatch(
            intArrayOf(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_MEDIA_ITEM_TRANSITION),
            { it.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) },
            { it.onPositionDiscontinuity(old, next, reason) },
            { it.onMediaItemTransition(item, transitionReason) },
        )
    }

    private fun positionInfo(item: MediaItem?, index: Int, position: Duration) = Player.PositionInfo(
        null, index, item, null, 0, position.inWholeMilliseconds, position.inWholeMilliseconds, -1, -1,
    )

    /** Adds an application item after the current one and optionally moves playback to it. */
    fun addApplicationItem(moveToIt: Boolean) = looperQueue.runOnLooper {
        playlist += "application"
        if (moveToIt) currentIndex = playlist.lastIndex
        notifyPlaylistChanged()
    }

    override fun setMediaSource(source: CoordinatorMediaSource, startPosition: Duration?) {
        requireApplicationLooper()
        playlist.clear()
        playlist += source
        currentIndex = 0
        val kind = kindOf(source)
        snapshot = snapshot.copy(position = startPosition ?: Duration.ZERO)
        operations += "set-$kind"
        setMediaSourceAction?.invoke(kind)
        if (failNextSetMediaSource) {
            failNextSetMediaSource = false
            throw IOException("scripted")
        }
        notifyPlaylistChanged()
    }

    override fun prepare() {
        requireApplicationLooper()
        operations += "prepare"
        if (cleanupFaults?.enabled == true) throw IOException("scripted preparation failure")
        prepareAction?.invoke()
    }

    override fun stop() {
        requireApplicationLooper()
        operations += "stop"
        if (cleanupFaults?.enabled == true) throw IOException("scripted stop failure")
        stopAction?.invoke()
        if (failNextStop) {
            failNextStop = false
            throw IOException("scripted stop failure")
        }
        snapshot = snapshot.copy(position = Duration.ZERO, playbackState = Player.STATE_IDLE)
    }

    override fun removeMediaItems(source: CoordinatorMediaSource) {
        requireApplicationLooper()
        val index = playlist.indexOfFirst { it === source }.takeIf { it >= 0 } ?: return
        operations += "remove-${kindOf(source)}"
        if (failRemove || cleanupFaults?.enabled == true) throw IOException("scripted removal failure")
        playlist.removeAt(index)
        if (index < currentIndex || currentIndex > playlist.lastIndex) currentIndex = maxOf(0, currentIndex - 1)
        notifyPlaylistChanged()
    }

    private fun notifyPlaylistChanged() {
        val item = currentItem
        deliverBatch(
            intArrayOf(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION),
            { it.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) },
            { it.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) },
        )
    }

    private fun kindOf(entry: Any?): String = when (entry) {
        null -> "none"
        is FakeCoordinatorMediaSource -> entry.kind
        else -> entry as String
    }

    private class FakeCoordinatorMediaSource(val kind: String) : CoordinatorMediaSource {
        val item = MediaItem.Builder().build()
    }
}

private class TestCoordinatorLooper(initiallyCurrent: Boolean = false) : CoordinatorLooper {
    private val queue = ArrayDeque<Runnable>()
    var current = initiallyCurrent
    var posts: Int = 0
        private set
    var rejectPosts = false
    var throwOnPost = false
    var throwOnIsCurrent = false

    override fun post(runnable: Runnable): Boolean {
        posts += 1
        if (throwOnPost) error("scripted dispatch failure")
        if (rejectPosts) return false
        queue += runnable
        return true
    }

    override fun remove(runnable: Runnable) {
        queue.remove(runnable)
    }

    override fun isCurrent(): Boolean {
        if (throwOnIsCurrent) error("scripted looper check failure")
        return current && !rejectPosts && !throwOnPost
    }

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
        RecordingFileResult.Failed(TvheadendFailure.NOT_SUPPORTED)
}

private fun controls(token: PlaybackTargetToken): LiveTimeshiftControlBridge =
    LiveTimeshiftControlBridge(token) {}
