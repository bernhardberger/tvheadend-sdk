@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.container.NalUnitUtil
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultAllocator
import at.bernhardberger.tvheadend.sdk.playback.ActiveSubscription
import at.bernhardberger.tvheadend.sdk.playback.MuxFrameType
import at.bernhardberger.tvheadend.sdk.playback.SkipOutcome
import at.bernhardberger.tvheadend.sdk.playback.StreamIndex
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionBinary
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionCondition
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionConfirmation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionIssue
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionState
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStream
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTerminalReason
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTracks
import at.bernhardberger.tvheadend.sdk.playback.createSubscriptionManager
import at.bernhardberger.tvheadend.sdk.testing.ScriptedSubscriptionConnection
import at.bernhardberger.tvheadend.sdk.testing.SubscriptionBinaryFixture
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.microseconds

internal class LiveStreamRestartTest {
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
    fun `bounds check restoration preserves earlier test configuration`() {
        val original = previousBoundsChecks
        try {
            for (previous in listOf(null, false, true)) {
                androidx.media3.common.util.ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(previous)
                rememberMedia3BoundsChecks()
                androidx.media3.common.util.ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
                restoreMedia3BoundsChecks()
                assertEquals(previous, media3LegacyBoundsChecksOverride())
            }
        } finally {
            previousBoundsChecks = original
            restoreMedia3BoundsChecks()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `untimed codec initialization survives until first timed access unit initially and after restart`(restart: Boolean) = runTest {
        val harness = RestartSourceHarness(this)
        try {
            harness.start()
            var period = harness.createPeriod()
            if (restart) {
                harness.started(SubscriptionStreamType.MPEG2_AUDIO)
                harness.audio(0)
                harness.flush()
                assertEquals(1, harness.preparations(period))
                harness.stop()
                harness.flush()
                period = harness.createPeriod()
            }
            harness.started(SubscriptionStreamType.H264)
            val (configuration, accessUnit) = h264FixtureParts()
            harness.packet(0, null, configuration)
            harness.flush()
            assertEquals(0, harness.preparations(period))
            repeat(3) { harness.packet(0, 9_000_000_000L + it * 40_000L, accessUnit) }
            harness.flush()
            assertEquals(1, harness.preparations(period))
            assertEquals(MimeTypes.VIDEO_H264, period.trackGroups[0].getFormat(0).sampleMimeType)
            assertEquals(720, period.trackGroups[0].getFormat(0).width)
            assertEquals(0L, harness.sampleTime(harness.select(period, 0)))
            assertEquals(1, harness.connection.subscribeCount)
            assertEquals(0, harness.connection.unsubscribeCount)
        } finally { harness.close() }
        assertEquals(0, harness.allocator.totalBytesAllocated)
        assertEquals(1, harness.connection.unsubscribeCount)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `pre origin packet prefix bounds events and bytes with existing typed failure`(bytes: Boolean) = runTest {
        val harness = RestartSourceHarness(this)
        try {
            harness.start()
            val period = harness.createPeriod()
            harness.started(SubscriptionStreamType.H264)
            val payload = if (bytes) ByteArray(1024 * 1024) else byteArrayOf()
            repeat(if (bytes) 4 else 256) { harness.packet(0, null, payload) }
            assertTrue(harness.subscription.state.value is SubscriptionState.Playable)
            assertEquals(0, harness.allocator.totalBytesAllocated)
            harness.packet(0, null, payload)
            harness.flush()
            val reason = (harness.subscription.state.value as SubscriptionState.Terminal).reason
            assertSame(at.bernhardberger.tvheadend.sdk.playback.SubscriptionTerminalReason.ConsumerFailed, reason)
            assertThrows(IOException::class.java) { period.maybeThrowPrepareError() }
            assertEquals(0, harness.preparations(period))
            assertEquals(1, harness.connection.unsubscribeCount)
        } finally { harness.close() }
        assertEquals(0, harness.allocator.totalBytesAllocated)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `release and interruption retire untimed prefix without copying into successor`(interrupt: Boolean) = runTest {
        val harness = RestartSourceHarness(this)
        try {
            harness.start()
            val old = harness.createPeriod()
            harness.started(SubscriptionStreamType.H264)
            val (configuration, accessUnit) = h264FixtureParts()
            val retained = PrefixCountingBinary(configuration)
            harness.emit(SubscriptionEvent.Packet(MuxFrameType.UNKNOWN, StreamIndex(0), null, null, 0, retained))
            assertEquals(0, retained.copies)
            if (interrupt) {
                harness.stop()
                harness.flush()
            } else {
                harness.source.releasePeriod(old)
            }
            val successor = harness.createPeriod()
            if (interrupt) harness.started(SubscriptionStreamType.H264)
            repeat(3) { harness.packet(0, 9_000_000_000L + it * 40_000L, accessUnit) }
            harness.flush()
            assertEquals(0, retained.copies)
            assertEquals(0, harness.preparations(old))
            assertEquals(0, harness.preparations(successor))
            // Only fresh segment configuration may make this successor prepare.
            harness.packet(0, null, configuration)
            repeat(3) { harness.packet(0, 9_000_120_000L + it * 40_000L, accessUnit) }
            harness.flush()
            assertEquals(1, harness.preparations(successor))
            assertEquals(0, retained.copies)
        } finally { harness.close() }
        assertEquals(0, harness.allocator.totalBytesAllocated)
    }

    @Test
    fun `continuing large clock uses one common audio video origin after restart`() = runTest {
        val harness = RestartSourceHarness(this)
        try {
            harness.start()
            harness.createPeriod()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio(0, 8_000_000_000)
            harness.stop()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO, SubscriptionStreamType.H264)
            harness.audio(0, 9_000_000_000)
            harness.packet(1, 9_000_020_000, fixture("channel-016-stream-01-packet-001.bin"))
            harness.packet(1, 9_000_060_000, fixture("channel-016-stream-01-packet-001.bin"))
            harness.flush()
            val period = harness.createPeriod()
            harness.flush()
            assertEquals(1, harness.preparations(period))
            assertEquals(0L, harness.sampleTime(harness.select(period, 0)))
            assertEquals(20_000L, harness.sampleTime(harness.select(period, 1)))
        } finally { harness.close() }
    }

    @Test
    fun `release during open closes a late returned handle exactly once without joining itself`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val subscription = FakeTimeshiftSubscription(120.seconds)
        val looper = QueuedCoordinatorLooper()
        val timelines = mutableListOf<Timeline>()
        val source = TvheadendLiveMediaSource(object : CoordinatorLiveTarget {
            override val isCurrent = true
            override suspend fun open(consumer: SubscriptionEventConsumer, options: SubscriptionOptions): SubscriptionOpenResult =
                withContext(NonCancellable) {
                    gate.await()
                    SubscriptionOpenResult.Opened(subscription)
                }
        }, SubscriptionOptions(), null, {}, StandardTestDispatcher(testScheduler), { looper })
        val caller = MediaSource.MediaSourceCaller { _, timeline -> timelines += timeline }
        source.prepareSource(caller, PlayerId.UNSET, BandwidthMeter.NO_OP)
        runCurrent()
        source.releaseSource(caller)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        looper.runAll()
        assertEquals(1, subscription.closeCount)
        assertEquals(1, timelines.size)
    }

    @Test
    fun `source reprepare fences retired consumer posts and late owner cleanup`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val oldHandle = FakeTimeshiftSubscription(120.seconds)
        val newHandle = FakeTimeshiftSubscription(120.seconds)
        val consumers = mutableListOf<SubscriptionEventConsumer>()
        val looper = QueuedCoordinatorLooper()
        val timelines = mutableListOf<Timeline>()
        val source = TvheadendLiveMediaSource(object : CoordinatorLiveTarget {
            override val isCurrent = true
            override suspend fun open(consumer: SubscriptionEventConsumer, options: SubscriptionOptions): SubscriptionOpenResult {
                consumers += consumer
                return if (consumers.size == 1) withContext(NonCancellable) {
                    gate.await()
                    SubscriptionOpenResult.Opened(oldHandle)
                } else SubscriptionOpenResult.Opened(newHandle)
            }
        }, SubscriptionOptions(), null, {}, StandardTestDispatcher(testScheduler), { looper })
        val caller = MediaSource.MediaSourceCaller { _, timeline -> timelines += timeline }
        source.prepareSource(caller, PlayerId.UNSET, BandwidthMeter.NO_OP)
        runCurrent()
        consumers.first().accept(SubscriptionEvent.Stopped(SubscriptionCondition.NO_DETAIL))
        source.releaseSource(caller)
        runCurrent()
        source.prepareSource(caller, PlayerId.UNSET, BandwidthMeter.NO_OP)
        runCurrent()
        consumers.first().accept(SubscriptionEvent.Stopped(SubscriptionCondition.NO_DETAIL))
        gate.complete(Unit)
        runCurrent()
        looper.runAll()
        assertEquals(2, timelines.size)
        assertEquals(1, oldHandle.closeCount)
        assertEquals(0, newHandle.closeCount)
        source.maybeThrowSourceInfoRefreshError()
        source.releaseSource(caller)
        runCurrent()
        assertEquals(1, newHandle.closeCount)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `unchanged and changed layouts use fresh periods and one transport`(changed: Boolean) = runTest {
        val harness = RestartSourceHarness(this)
        try {
            harness.start()
            val old = harness.createPeriod()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio(0, 9_000_000_000L)
            harness.flush()
            assertEquals(1, harness.preparations(old))
            val oldStream = harness.select(old, 0)
            val before = harness.timelines.last()
            harness.stop()
            assertEquals(C.RESULT_NOTHING_READ, oldStream.readData(FormatHolder(), buffer(), 0))
            assertFalse(old.isLoading)
            assertTrue(old.bufferedPositionUs != C.TIME_END_OF_SOURCE)
            harness.started(SubscriptionStreamType.AAC.takeIf { changed } ?: SubscriptionStreamType.MPEG2_AUDIO,
                *if (changed) arrayOf(SubscriptionStreamType.MPEG2_AUDIO) else emptyArray())
            if (changed) harness.aac(0, 9_001_000_000L) else harness.audio(0, 9_001_000_000L)
            if (changed) harness.audio(1, 9_001_020_000L)
            harness.flush()
            val after = harness.timelines.last()
            assertNotSame(before.getUidOfPeriod(0), after.getUidOfPeriod(0))
            assertSame(before.getWindow(0, Timeline.Window()).uid, after.getWindow(0, Timeline.Window()).uid)
            assertEquals(0L, after.getWindow(0, Timeline.Window(), 60_000_000).defaultPositionUs)
            assertEquals(C.INDEX_UNSET, after.getIndexOfPeriod(before.getUidOfPeriod(0)))
            val replacement = harness.createPeriod()
            harness.flush()
            assertEquals(1, harness.preparations(replacement))
            assertEquals(if (changed) 2 else 1, replacement.trackGroups.length)
            assertEquals(if (changed) MimeTypes.AUDIO_AAC else MimeTypes.AUDIO_MPEG_L2,
                replacement.trackGroups[0].getFormat(0).sampleMimeType)
            assertEquals(0L, harness.sampleTime(harness.select(replacement, 0)))
            if (changed) assertEquals(20_000L, harness.sampleTime(harness.select(replacement, 1)))
            harness.source.releasePeriod(old)
            harness.audio(if (changed) 1 else 0, 9_001_040_000L)
            harness.flush()
            assertEquals(1, harness.preparations(old))
            assertEquals(1, harness.preparations(replacement))
            assertEquals(1, harness.connection.subscribeCount)
            assertEquals(0, harness.connection.unsubscribeCount)
            assertTrue(harness.subscription.state.value is SubscriptionState.Playable)
        } finally { harness.close() }
        assertEquals(1, harness.connection.unsubscribeCount)
        assertEquals(0, harness.allocator.totalBytesAllocated)
    }

    @Test
    fun `stop during optional discovery fences old callback and waiting period prepares once`() = runTest {
        val harness = RestartSourceHarness(this)
        try {
            harness.start()
            val old = harness.createPeriod()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO, SubscriptionStreamType.AAC)
            harness.audio(0)
            harness.stop()
            harness.flush()
            val waiting = harness.createPeriod()
            advanceTimeBy(1.seconds)
            harness.flush()
            assertEquals(0, harness.preparations(old))
            assertEquals(0, harness.preparations(waiting))
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio(0)
            harness.flush()
            assertEquals(1, harness.preparations(waiting))
            assertEquals(0, harness.preparations(old))
        } finally { harness.close() }
    }

    @Test
    fun `stop before prepare and rapid starts coalesce timeline handoff without empty publication`() = runTest {
        val harness = RestartSourceHarness(this)
        try {
            harness.start()
            harness.stop()
            repeat(100) {
                harness.started(SubscriptionStreamType.MPEG2_AUDIO)
                harness.stop()
            }
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio(0)
            assertEquals(1, harness.timelines.size)
            harness.flush()
            assertEquals(2, harness.timelines.size)
            assertTrue(harness.timelines.all { it.periodCount == 1 && it.windowCount == 1 })
            val period = harness.createPeriod()
            harness.flush()
            assertEquals(1, harness.preparations(period))
            assertEquals(1, harness.connection.subscribeCount)
            assertEquals(0, harness.connection.unsubscribeCount)
        } finally { harness.close() }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `replacement handoff bounds bytes and events without awaiting period creation`(bytes: Boolean) = runTest {
        val harness = RestartSourceHarness(this)
        try {
            harness.start()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            if (bytes) {
                repeat(17) { harness.packet(0, 0, ByteArray(1024 * 1024)) }
            } else {
                repeat(2_049) { harness.emit(SubscriptionEvent.Status(SubscriptionCondition.NO_DETAIL)) }
            }
            runCurrent()
            assertTrue(harness.subscription.state.value is SubscriptionState.Terminal)
            assertEquals(1, harness.connection.unsubscribeCount)
            assertThrows(IOException::class.java) { harness.source.maybeThrowSourceInfoRefreshError() }
        } finally { harness.close() }
    }

    @Test
    fun `old content target and equal numeric player position cannot enter successor mapping`() = runTest {
        val harness = RestartSourceHarness(this)
        try {
            harness.start()
            harness.createPeriod()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio(0, 1_000_000)
            harness.audio(0, 2_000_000)
            harness.history(0, 3_000_000)
            harness.flush()
            val oldAttachment = checkNotNull(harness.bridge.mappingAttachment())
            val oldTimeline = (harness.state as LiveTimeshiftState.Available).timeline!!
            val target = oldTimeline.select(1.seconds)!!
            harness.stop()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio(0, 10_000_000)
            harness.audio(0, 11_000_000)
            harness.history(0, 20_000_000)
            harness.flush()
            harness.createPeriod()
            harness.releaseOldPeriods()
            harness.flush()
            val currentAttachment = checkNotNull(harness.bridge.mappingAttachment())
            val currentTimeline = (harness.state as LiveTimeshiftState.Available).timeline!!
            assertTrue(oldTimeline.describesSameSubscription(currentTimeline))
            assertFalse(oldTimeline.describesSameSegment(currentTimeline))
            assertSame(TimeshiftContentSeekResult.Replaced, harness.bridge.seekContent(target))
            assertTrue(harness.connection.seekTargets.isEmpty())
            assertSame(TimeshiftPlaybackPosition.Unavailable, harness.bridge.playbackPosition(oldAttachment, 0.microseconds))
            assertEquals(10.seconds, (harness.bridge.playbackPosition(currentAttachment, 0.microseconds)
                as TimeshiftPlaybackPosition.Estimate).target.position)
            assertNotSame(oldAttachment.periodUid, currentAttachment.periodUid)
        } finally { harness.close() }
    }

    @Test
    fun `interruption observations cannot authorize successor history or pause state`() = runTest {
        val harness = RestartSourceHarness(this)
        try {
            harness.start()
            harness.createPeriod()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio(0)
            harness.stop()
            harness.history(0, 20_000_000)
            harness.emit(SubscriptionEvent.Speed(0))
            repeat(2_049) { harness.emit(SubscriptionEvent.Status(SubscriptionCondition.ERROR_REPORTED)) }
            harness.flush()
            val waiting = harness.createPeriod()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio(0, 10_000_000)
            harness.releaseOldPeriods()
            harness.flush()
            assertEquals(1, harness.preparations(waiting))
            val available = harness.state as LiveTimeshiftState.Available
            assertEquals(null, available.timeline)
            assertEquals(null, available.serverPaused)
            harness.history(0, 30_000_000)
            harness.flush()
            assertEquals(30.seconds, (harness.state as LiveTimeshiftState.Available).timeline!!.end)
        } finally { harness.close() }
    }

    @Test
    fun `unbound attachment does not publish history with a substitute subscription identity`() {
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken()) {}
        val attachment = bridge.newAttachment()
        attachment.accept(SubscriptionEvent.Timeshift(0, 0, 0, 20_000_000, null))
        assertEquals(null, attachment.timeline())
        val subscription = FakeTimeshiftSubscription(120.seconds)
        attachment.bind(subscription)
        val bound = checkNotNull(attachment.timeline())
        assertSame(subscription, bound.subscriptionOwner)
        attachment.detach()
        assertEquals(null, attachment.timeline())
    }

    @Test
    fun `server stop reason and later status stay observable until the stream starts again`() = runTest {
        val harness = RestartSourceHarness(this)
        try {
            harness.start()
            harness.createPeriod()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio(0)
            harness.flush()
            harness.emit(
                SubscriptionEvent.Stopped(SubscriptionCondition.ERROR_REPORTED, SubscriptionIssue.SUBSCRIPTION_OVERRIDDEN),
            )
            harness.flush()
            assertSame(SubscriptionIssue.SUBSCRIPTION_OVERRIDDEN, harness.observedIssues.last())
            harness.emit(SubscriptionEvent.Status(SubscriptionCondition.ERROR_REPORTED, SubscriptionIssue.NO_FREE_ADAPTER))
            harness.flush()
            assertSame(SubscriptionIssue.NO_FREE_ADAPTER, harness.observedIssues.last())

            val waiting = harness.createPeriod()
            harness.audio(0, 2_000_000)
            harness.flush()
            assertEquals(0, harness.preparations(waiting))
            assertSame(SubscriptionIssue.NO_FREE_ADAPTER, harness.observedIssues.last())

            harness.emit(
                SubscriptionEvent.Started(
                    listOf(
                        SubscriptionStream(StreamIndex(0), SubscriptionStreamType.MPEG2_AUDIO, null, null, null, null, null,
                            null, null, null, null, null, null, null, null),
                    ),
                    null,
                ),
            )
            harness.audio(0, 10_000_000)
            harness.releaseOldPeriods()
            harness.flush()
            assertEquals(1, harness.preparations(waiting))
            assertEquals(
                listOf(
                    null,
                    SubscriptionIssue.SUBSCRIPTION_OVERRIDDEN,
                    SubscriptionIssue.NO_FREE_ADAPTER,
                    null,
                ),
                harness.observedIssues,
            )
        } finally { harness.close() }
    }

    @Test
    fun `restart replaces the held stop reason before a replacement period exists`() = runTest {
        val harness = RestartSourceHarness(this)
        try {
            harness.start()
            harness.createPeriod()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio(0)
            harness.flush()
            val overridden = SubscriptionEvent.Stopped(
                SubscriptionCondition.ERROR_REPORTED,
                SubscriptionIssue.SUBSCRIPTION_OVERRIDDEN,
            )
            harness.emit(overridden)
            harness.flush()
            assertSame(SubscriptionIssue.SUBSCRIPTION_OVERRIDDEN, harness.observedIssues.last())

            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.flush()
            assertEquals(null, harness.observedIssues.last())
            harness.emit(overridden)
            harness.emit(
                SubscriptionEvent.Started(
                    listOf(
                        SubscriptionStream(StreamIndex(0), SubscriptionStreamType.MPEG2_AUDIO, null, null, null, null, null,
                            null, null, null, null, null, null, null, null),
                    ),
                    null,
                ),
            )
            harness.flush()
            assertEquals(
                listOf(
                    null,
                    SubscriptionIssue.SUBSCRIPTION_OVERRIDDEN,
                    null,
                    SubscriptionIssue.SUBSCRIPTION_OVERRIDDEN,
                    null,
                ),
                harness.observedIssues,
            )
        } finally { harness.close() }
    }

    @Test
    fun `stop reason is released when the stopped subscription ends`() = runTest {
        val harness = RestartSourceHarness(this)
        try {
            harness.start()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.emit(
                SubscriptionEvent.Stopped(SubscriptionCondition.ERROR_REPORTED, SubscriptionIssue.SUBSCRIPTION_OVERRIDDEN),
            )
            harness.flush()
            assertSame(SubscriptionIssue.SUBSCRIPTION_OVERRIDDEN, harness.observedIssues.last())
            harness.connection.loseGeneration()
            harness.flush()
            assertEquals(null, harness.observedIssues.last())
        } finally { harness.close() }
    }

    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `first server stop publishes one consistent stopped observation`(priorIssue: Boolean, stopIssue: Boolean) = runTest {
        val harness = RestartSourceHarness(this)
        try {
            harness.start()
            harness.createPeriod()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio(0)
            harness.flush()
            if (priorIssue) {
                harness.emit(SubscriptionEvent.Status(SubscriptionCondition.ERROR_REPORTED, SubscriptionIssue.BAD_SIGNAL))
                harness.flush()
                assertSame(SubscriptionIssue.BAD_SIGNAL, harness.observations.last().subscriptionIssue)
            }
            assertTrue(harness.observations.last().timeshiftState is LiveTimeshiftState.Available)
            harness.observations.clear()

            val issue = SubscriptionIssue.SUBSCRIPTION_OVERRIDDEN.takeIf { stopIssue }
            val condition = if (stopIssue) SubscriptionCondition.ERROR_REPORTED else SubscriptionCondition.NO_DETAIL
            harness.emit(SubscriptionEvent.Stopped(condition, issue))
            harness.flush()

            assertEquals(
                listOf(LivePlaybackObservation.Active(LiveTimeshiftState.Unavailable, issue, null, serverStopped = true)),
                harness.observations,
            )
        } finally { harness.close() }
    }

    @Test
    fun `server stop is set cleared by restart and set again by a second stop`() = runTest {
        val harness = RestartSourceHarness(this)
        try {
            harness.start()
            harness.createPeriod()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio(0)
            harness.flush()
            assertFalse(harness.observations.last().serverStopped)

            harness.stop()
            harness.flush()
            assertTrue(harness.observations.last().serverStopped)

            val restarted = harness.createPeriod()
            harness.flush()
            assertTrue(harness.observations.last().serverStopped)
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio(0, 10_000_000)
            harness.releaseOldPeriods()
            harness.flush()
            assertEquals(1, harness.preparations(restarted))
            assertFalse(harness.observations.last().serverStopped)
            assertTrue(harness.observations.last().timeshiftState is LiveTimeshiftState.Available)

            harness.observations.clear()
            harness.stop()
            harness.flush()
            assertEquals(
                listOf(LivePlaybackObservation.Active(LiveTimeshiftState.Unavailable, null, null, serverStopped = true)),
                harness.observations,
            )
        } finally { harness.close() }
    }

    @Test
    fun `server stop is cleared when the source reconnects without a terminated event`() = runTest {
        val harness = RestartSourceHarness(this)
        try {
            harness.start()
            harness.createPeriod()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio(0)
            harness.flush()
            harness.stop()
            harness.flush()
            assertTrue(harness.observations.last().serverStopped)

            harness.reprepare()
            assertFalse(harness.observations.last().serverStopped)
            assertEquals(2, harness.connection.subscribeCount)

            val reconnected = harness.createPeriod()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio(0)
            harness.flush()
            assertEquals(1, harness.preparations(reconnected))
            assertFalse(harness.observations.last().serverStopped)
            assertTrue(harness.observations.last().timeshiftState is LiveTimeshiftState.Available)
        } finally { harness.close() }
    }

    @Test
    fun `server pause and timeshift seek never report a server stop`() = runTest {
        val harness = RestartSourceHarness(this)
        try {
            harness.start()
            harness.createPeriod()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio(0)
            harness.history(0, 20_000_000)
            harness.flush()

            val pausing = async { harness.bridge.setSpeed(0) }
            harness.flush()
            harness.emit(SubscriptionEvent.Speed(0))
            harness.flush()
            pausing.await()
            assertEquals(true, (harness.observations.last().timeshiftState as LiveTimeshiftState.Available).serverPaused)

            val seeking = async { harness.bridge.seek(SubscriptionSeekTarget.Absolute(5.seconds)) }
            harness.flush()
            harness.emit(SubscriptionEvent.Skipped(true, SkipOutcome.ACCEPTED, 5_000_000, null))
            harness.flush()
            assertTrue(seeking.await() is SubscriptionSeekResult.AcceptedAt)

            val resuming = async { harness.bridge.setSpeed(100) }
            harness.flush()
            harness.emit(SubscriptionEvent.Speed(100))
            harness.audio(0, 5_000_000)
            harness.flush()
            resuming.await()

            assertEquals(false, (harness.observations.last().timeshiftState as LiveTimeshiftState.Available).serverPaused)
            assertTrue(harness.observations.none { it.serverStopped })
        } finally { harness.close() }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `missing restart and generation loss terminate waiting period without resurrection`(generationLost: Boolean) = runTest {
        val harness = RestartSourceHarness(this)
        try {
            harness.start()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.stop()
            harness.flush()
            val waiting = harness.createPeriod()
            if (generationLost) harness.connection.loseGeneration() else advanceTimeBy(5.seconds)
            harness.flush()
            assertThrows(IOException::class.java) { waiting.maybeThrowPrepareError() }
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.flush()
            assertEquals(0, harness.preparations(waiting))
            assertEquals(1, harness.connection.unsubscribeCount)
        } finally { harness.close() }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `replay overflow closes its subscription without another consumer event`(rejectedAfterHandoff: Boolean) = runTest {
        val token = PlaybackTargetToken()
        var settle: ((Boolean) -> Unit)? = null
        val bridge = LiveTimeshiftControlBridge(token, onBufferExhausted = { _, decision ->
            settle = decision
            true
        }, publish = {}, publishIssue = {})
        val harness = RestartSourceHarness(this, timeshiftSeconds = null, bridgeOverride = bridge)
        try {
            harness.start()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.queueLiveOverflow()
            if (!rejectedAfterHandoff) token.releasePlayerItem()
            val calls = harness.consumerCalls
            val period = harness.createPeriod()
            harness.flush()
            if (rejectedAfterHandoff) {
                assertTrue(harness.subscription.state.value is SubscriptionState.Playable)
                requireNotNull(settle)(true)
                harness.flush()
            }
            assertEquals("Live sample buffer limit reached", assertThrows(IOException::class.java) {
                period.maybeThrowPrepareError()
            }.message)
            assertSame(SubscriptionTerminalReason.Closed, (harness.subscription.state.value as SubscriptionState.Terminal).reason)
            assertEquals(1, harness.connection.unsubscribeCount)
            assertEquals(calls, harness.consumerCalls, "Replay must close without another consumer call")
        } finally { harness.close() }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `late replay rejection cannot close a successor period or preparation`(reprepare: Boolean) = runTest {
        val decisions = mutableListOf<(Boolean) -> Unit>()
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken(), onBufferExhausted = { _, decision ->
            decisions += decision
            true
        }, publish = {}, publishIssue = {})
        val harness = RestartSourceHarness(this, timeshiftSeconds = null, bridgeOverride = bridge)
        try {
            harness.start()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.queueLiveOverflow()
            harness.createPeriod()
            harness.flush()
            assertEquals(1, decisions.size)
            // Queue the failed replay continuation, then replace its owner before it can resume.
            decisions.single()(true)
            if (reprepare) harness.reprepare(beforeWorkers = true)
            val successor = harness.createPeriod()
            if (reprepare) harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio(0)
            harness.flush()
            val closes = harness.connection.unsubscribeCount
            harness.flush()
            assertEquals(closes, harness.connection.unsubscribeCount)
            assertTrue(harness.subscription.state.value is SubscriptionState.Playable)
            successor.maybeThrowPrepareError()
            harness.source.maybeThrowSourceInfoRefreshError()
        } finally { harness.close() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["release", "reprepare", "teardown"])
    fun `unsettled handoff is released or cancelled by bounded teardown`(action: String) = runTest {
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken(), onBufferExhausted = { _, _ -> true }, publish = {}, publishIssue = {})
        val harness = RestartSourceHarness(this, timeshiftSeconds = null, bridgeOverride = bridge)
        try {
            harness.start()
            val period = harness.createPeriod()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.fillLiveQueues(period)
            assertEquals(1, harness.pendingConsumerCalls)
            val subscription = harness.subscription
            when (action) {
                "release" -> harness.source.releasePeriod(period)
                "reprepare" -> harness.reprepare()
                else -> {
                    val closing = async { harness.close(releaseSourceFirst = false) }
                    runCurrent()
                    assertFalse(closing.isCompleted)
                    advanceTimeBy(5.seconds)
                    runCurrent()
                    closing.await()
                }
            }
            harness.flush()
            assertEquals(0, harness.pendingConsumerCalls)
            assertEquals(action == "teardown", subscription.diagnostics.value.consumerDrainTimedOut)
            assertEquals(if (action == "teardown") 1 else 0, harness.cancelledConsumerCalls)
        } finally { harness.close() }
    }

    @Test
    fun `refused exhaustion handoff fails the original consumer`() = runTest {
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken(), onBufferExhausted = { _, _ -> false }, publish = {}, publishIssue = {})
        val harness = RestartSourceHarness(this, timeshiftSeconds = null, bridgeOverride = bridge)
        try {
            harness.start()
            val period = harness.createPeriod()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.fillLiveQueues(period)
            assertSame(SubscriptionTerminalReason.ConsumerFailed, (harness.subscription.state.value as SubscriptionState.Terminal).reason)
            assertEquals(0, harness.pendingConsumerCalls)
            assertEquals(1, harness.connection.unsubscribeCount)
        } finally { harness.close() }
    }

    @Test
    fun `live queues filled without timeshift wait for the coordinator and retirement closes the subscription`() = runTest {
        val handedOver = mutableListOf<LiveTimeshiftControlBridge.Attachment>()
        val harness = RestartSourceHarness(this, timeshiftSeconds = null) { handedOver += it }
        try {
            harness.start()
            val period = harness.createPeriod()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.fillLiveQueues(period)
            assertEquals(1, handedOver.size)
            // The paused player can still present what it already queued.
            harness.flush()
            assertEquals(1, harness.preparations(period))
            assertEquals(0L, harness.sampleTime(harness.select(period, 0)))
            assertTrue(harness.subscription.state.value is SubscriptionState.Playable)
            period.maybeThrowPrepareError()
            harness.source.maybeThrowSourceInfoRefreshError()
            // A paused player keeps its queued media; later delivery is dropped until retirement.
            assertTrue(harness.allocator.totalBytesAllocated > 0)
            val late = PrefixCountingBinary(fixture("mpeg-audio.bin"))
            harness.emit(SubscriptionEvent.Packet(MuxFrameType.I, StreamIndex(0), 1_000_000, 1_000_000, 40_000, late))
            assertEquals(0, late.copies)
            assertEquals(0, harness.connection.unsubscribeCount)
        } finally { harness.close() }
        // Retiring the target releases Media3's period and source, as the coordinator's stop does.
        assertEquals(1, harness.connection.unsubscribeCount)
        assertEquals(0, harness.allocator.totalBytesAllocated)
        assertEquals(1, handedOver.size)
    }

    @Test
    fun `live queues filled with timeshift granted still fail the subscription with an accurate reason`() = runTest {
        val handedOver = mutableListOf<LiveTimeshiftControlBridge.Attachment>()
        val harness = RestartSourceHarness(this) { handedOver += it }
        try {
            harness.start()
            val period = harness.createPeriod()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.fillLiveQueues(period)
            assertTrue(handedOver.isEmpty())
            val reason = (harness.subscription.state.value as SubscriptionState.Terminal).reason
            assertSame(at.bernhardberger.tvheadend.sdk.playback.SubscriptionTerminalReason.ConsumerFailed, reason)
            val error = assertThrows(IOException::class.java) { period.maybeThrowPrepareError() }
            assertEquals("Live sample buffer limit reached", error.message)
        } finally { harness.close() }
    }

    @Test
    fun `live queues filled without a coordinator handler still fail the subscription with an accurate reason`() = runTest {
        val harness = RestartSourceHarness(this, timeshiftSeconds = null)
        try {
            harness.start()
            val period = harness.createPeriod()
            harness.started(SubscriptionStreamType.MPEG2_AUDIO)
            harness.fillLiveQueues(period)
            val reason = (harness.subscription.state.value as SubscriptionState.Terminal).reason
            assertSame(at.bernhardberger.tvheadend.sdk.playback.SubscriptionTerminalReason.ConsumerFailed, reason)
            val error = assertThrows(IOException::class.java) { period.maybeThrowPrepareError() }
            assertEquals("Live sample buffer limit reached", error.message)
        } finally { harness.close() }
    }

    @Test
    fun `only the attachment leading the target can hand over full queues`() {
        val handedOver = mutableListOf<LiveTimeshiftControlBridge.Attachment>()
        val bridge = LiveTimeshiftControlBridge(
            token = PlaybackTargetToken(),
            publish = {},
            publishIssue = {},
            onBufferExhausted = { attachment, settle -> handedOver += attachment; settle(false); true },
        )
        val subscription = FakeTimeshiftSubscription(null)
        val replaced = bridge.newAttachment().also { it.bind(subscription) }
        val restarted = bridge.newAttachment().also { it.bind(subscription) }
        assertFalse(replaced.bufferExhausted())
        assertTrue(restarted.bufferExhausted())
        restarted.detach()
        assertFalse(restarted.bufferExhausted())
        assertFalse(restarted.leadsTarget())
        assertEquals(listOf(restarted), handedOver)

        val unhandled = LiveTimeshiftControlBridge(PlaybackTargetToken()) {}
        assertFalse(unhandled.newAttachment().also { it.bind(subscription) }.bufferExhausted())
    }
}

/** Media3 exposes a nullable setter but no getter; preserve its exact pinned-library test override. */
internal fun media3LegacyBoundsChecksOverride(): Boolean? {
    val field = androidx.media3.common.util.ParsableByteArray::class.java
        .getDeclaredField("shouldEnforceLimitOnLegacyMethods").also { it.isAccessible = true }
    return (field.get(null) as java.util.concurrent.atomic.AtomicReference<*>).get() as Boolean?
}

internal class RestartSourceHarness(
    private val scope: TestScope,
    private val timeshiftSeconds: Long? = 120,
    bridgeOverride: LiveTimeshiftControlBridge? = null,
    onBufferExhausted: ((LiveTimeshiftControlBridge.Attachment) -> Unit)? = null,
) {
    init {
        // The shared harness must also work when a coordinator regression runs in isolation.
        androidx.media3.common.util.ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
    }

    val connection = ScriptedSubscriptionConnection()
    val looper = QueuedCoordinatorLooper()
    val allocator = DefaultAllocator(false, 1_024)
    private val dispatcher = StandardTestDispatcher(scope.testScheduler)
    private val manager = createSubscriptionManager(connection, dispatcher)
    lateinit var subscription: ActiveSubscription
    var consumerCalls = 0
    var pendingConsumerCalls = 0
    var cancelledConsumerCalls = 0
    val timelines = mutableListOf<Timeline>()
    private val periods = mutableListOf<TvheadendLiveMediaPeriod>()
    private val preparations = mutableMapOf<MediaPeriod, Int>()
    var state: LiveTimeshiftState = LiveTimeshiftState.Unavailable
    val observedIssues = mutableListOf<SubscriptionIssue?>()
    val observations = mutableListOf<LivePlaybackObservation.Active>()
    val bridge = bridgeOverride ?: LiveTimeshiftControlBridge(
        token = PlaybackTargetToken(),
        publish = { state = it },
        publishIssue = {},
        publishObservation = { observation ->
            observations += observation
            if (observedIssues.lastOrNull() != observation.subscriptionIssue || observedIssues.isEmpty()) {
                observedIssues += observation.subscriptionIssue
            }
        },
        onBufferExhausted = onBufferExhausted?.let { handler ->
            { attachment, settle -> handler(attachment); settle(false); true }
        },
    )
    val source = TvheadendLiveMediaSource(object : CoordinatorLiveTarget {
        override val isCurrent = true
        override suspend fun open(consumer: SubscriptionEventConsumer, options: SubscriptionOptions): SubscriptionOpenResult =
            manager.open(SubscriptionChannelId(1), object : SubscriptionEventConsumer {
                override fun tracksReady(tracks: SubscriptionTracks) = consumer.tracksReady(tracks)
                override suspend fun accept(event: SubscriptionEvent) {
                    consumerCalls++
                    pendingConsumerCalls++
                    try {
                        consumer.accept(event)
                    } catch (cancellation: CancellationException) {
                        cancelledConsumerCalls++
                        throw cancellation
                    } finally {
                        pendingConsumerCalls--
                    }
                }
            }, options).also {
                if (it is SubscriptionOpenResult.Opened) subscription = it.subscription
            }
    }, SubscriptionOptions(timeshiftPeriod = (timeshiftSeconds ?: 0L).seconds), bridge, {}, dispatcher, { looper })
    private val caller = MediaSource.MediaSourceCaller { _, timeline ->
        assertTrue(looper.isCurrent())
        timelines += timeline
    }

    fun start() {
        connection.scriptSubscribe(
            SubscriptionOperationResult.Ok(SubscriptionConfirmation(null, null, null, timeshiftSeconds)),
        )
        manager.startAdmission()
        looper.post { source.prepareSource(caller, PlayerId.UNSET, BandwidthMeter.NO_OP) }
        flush()
    }

    fun createPeriod(): TvheadendLiveMediaPeriod {
        val period = source.createPeriod(MediaSource.MediaPeriodId(timelines.last().getUidOfPeriod(0)), allocator, 0) as TvheadendLiveMediaPeriod
        periods += period
        period.prepare(object : MediaPeriod.Callback {
            override fun onPrepared(mediaPeriod: MediaPeriod) {
                assertTrue(looper.isCurrent())
                preparations[mediaPeriod] = (preparations[mediaPeriod] ?: 0) + 1
            }
            override fun onContinueLoadingRequested(source: MediaPeriod) = Unit
        }, 0)
        return period
    }

    suspend fun started(vararg types: SubscriptionStreamType) = emit(SubscriptionEvent.Started(types.mapIndexed { index, type ->
        SubscriptionStream(StreamIndex(index.toLong()), type, null, null, null, null, null,
            null, null, null, null, null, null, null, null)
    }, null))

    suspend fun stop() = emit(SubscriptionEvent.Stopped(SubscriptionCondition.NO_DETAIL))
    suspend fun audio(index: Long, timeUs: Long = 1_000_000) = packet(index, timeUs, fixture("mpeg-audio.bin"))
    suspend fun aac(index: Long, timeUs: Long) = packet(index, timeUs, fixture("aac-adts.bin"))
    suspend fun packet(index: Long, timeUs: Long?, bytes: ByteArray) = emit(SubscriptionEvent.Packet(
        MuxFrameType.I, StreamIndex(index), timeUs, timeUs, 40_000, SubscriptionBinaryFixture(bytes)))
    suspend fun history(start: Long, end: Long) = emit(SubscriptionEvent.Timeshift(0, 0, start, end, null))
    suspend fun emit(event: SubscriptionEvent) { connection.emit(event); scope.runCurrent() }
    fun flush() { scope.runCurrent(); looper.runAll(); scope.runCurrent(); looper.runAll() }
    fun preparations(period: MediaPeriod): Int = preparations[period] ?: 0

    /** Delivers audio no player reads until the period's bounded queues are full. */
    suspend fun fillLiveQueues(period: TvheadendLiveMediaPeriod) {
        var packets = 0
        while (period.isLoading) {
            check(++packets <= 4_200) { "Live queues did not fill" }
            audio(0)
        }
    }

    /** Multiple complete fixture frames fit the source hand-off bound but overflow the sample bound. */
    suspend fun queueLiveOverflow() {
        val frame = fixture("mpeg-audio.bin")
        val packet = ByteArray(frame.size * 8) { frame[it % frame.size] }
        repeat(600) { packet(0, 1_000_000, packet) }
    }
    fun reprepare(beforeWorkers: Boolean = false) {
        periods.forEach(source::releasePeriod)
        periods.clear()
        looper.post {
            source.releaseSource(caller)
            source.prepareSource(caller, PlayerId.UNSET, BandwidthMeter.NO_OP)
        }
        if (beforeWorkers) looper.runAll()
        flush()
    }
    fun releaseOldPeriods() { periods.dropLast(1).forEach(source::releasePeriod) }
    fun select(period: TvheadendLiveMediaPeriod, index: Int): SampleStream {
        val streams = arrayOfNulls<SampleStream>(1)
        period.selectTracks(arrayOf(FixedTrackSelection(period.trackGroups[index], 0)), booleanArrayOf(false),
            streams, booleanArrayOf(false), 0)
        return checkNotNull(streams[0])
    }
    fun sampleTime(stream: SampleStream): Long {
        val holder = FormatHolder()
        val buffer = buffer()
        assertEquals(C.RESULT_FORMAT_READ, stream.readData(holder, buffer, 0))
        assertEquals(C.RESULT_BUFFER_READ, stream.readData(holder, buffer, 0))
        return buffer.timeUs
    }
    suspend fun close(releaseSourceFirst: Boolean = true) {
        if (!releaseSourceFirst) manager.closeAndJoin()
        periods.forEach(source::releasePeriod)
        source.releaseSource(caller)
        flush()
        manager.closeAndJoin()
    }
}

private fun buffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
private fun fixture(name: String): ByteArray = checkNotNull(
    LiveStreamRestartTest::class.java.getResourceAsStream("/recorded-mux/$name"),
).use { it.readBytes() }

/** Maintained NAL boundary/type inspection, not a fixture-specific codec parser. */
private fun h264FixtureParts(): Pair<ByteArray, ByteArray> {
    val bytes = fixture("channel-016-stream-01-packet-001.bin")
    val configuration = java.io.ByteArrayOutputStream()
    val accessUnit = java.io.ByteArrayOutputStream()
    val configurationTypes = mutableSetOf<Int>()
    var start = NalUnitUtil.findNalUnit(bytes, 0, bytes.size, BooleanArray(3))
    while (start < bytes.size) {
        val end = NalUnitUtil.findNalUnit(bytes, start + 3, bytes.size, BooleanArray(3))
        val type = NalUnitUtil.getNalUnitType(bytes, start)
        if (type == 7 || type == 8) {
            configurationTypes += type
            configuration.write(bytes, start, end - start)
        } else {
            accessUnit.write(bytes, start, end - start)
        }
        start = end
    }
    assertEquals(setOf(7, 8), configurationTypes)
    assertTrue(accessUnit.size() > 0)
    return configuration.toByteArray() to accessUnit.toByteArray()
}

private class PrefixCountingBinary(private val bytes: ByteArray) : SubscriptionBinary {
    var copies = 0
    override val size: Int = bytes.size
    override fun copyInto(destination: ByteArray, destinationOffset: Int): Int {
        copies++
        bytes.copyInto(destination, destinationOffset)
        return bytes.size
    }
}
