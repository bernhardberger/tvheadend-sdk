@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
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
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekInvalidation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionState
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStream
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTerminalReason
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTermination
import at.bernhardberger.tvheadend.sdk.playback.createSubscriptionManager
import at.bernhardberger.tvheadend.sdk.testing.ScriptedSubscriptionConnection
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class TvheadendLiveMediaPeriodTest {
    @ParameterizedTest
    @ValueSource(strings = ["replay", "second-seek", "interrupt", "release", "deselect"])
    fun `finite IDR retains exact sample once and fences stale queue callbacks`(boundary: String) = runTest {
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken()) {}
        val harness = PeriodHarness(this, bridge.newAttachment())
        try {
            harness.start(SubscriptionStreamType.MPEG2_AUDIO, SubscriptionStreamType.H264)
            harness.audio()
            harness.video()
            harness.looper.runAll()
            val stream = harness.select(1)
            val preview = stream as FinitePreviewSampleStream
            harness.connection.emit(SubscriptionEvent.Timeshift(0, 0, 0, 120_000_000, 0))
            runCurrent()
            harness.seek(0.seconds)
            assertFalse(preview.outputAllowed())
            harness.video()
            assertTrue(harness.period.readDiscontinuity() != C.TIME_UNSET)
            val holder = FormatHolder()
            val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
            assertEquals(C.RESULT_FORMAT_READ, stream.readData(holder, buffer, 0))
            assertEquals(C.RESULT_BUFFER_READ, stream.readData(holder, buffer, 0))
            buffer.flip()
            val original = ByteArray(checkNotNull(buffer.data).remaining()).also { checkNotNull(buffer.data).get(it) }
            val time = buffer.timeUs
            assertFalse(preview.drainAndRewind())
            harness.period.discardBuffer(Long.MAX_VALUE, true)
            when (boundary) {
                "second-seek" -> harness.seek(120.seconds)
                "interrupt" -> harness.period.interrupt()
                "release" -> harness.period.release()
                "deselect" -> harness.period.selectTracks(arrayOfNulls<ExoTrackSelection>(1), booleanArrayOf(false),
                    arrayOf<SampleStream?>(stream), booleanArrayOf(false), 0)
            }
            preview.inputQueued()
            assertEquals(boundary == "replay", preview.drainAndRewind())
            assertFalse(preview.drainAndRewind())
            if (boundary == "replay") {
                buffer.clear()
                assertEquals(C.RESULT_BUFFER_READ, stream.readData(holder, buffer, 0))
                buffer.flip()
                val replay = ByteArray(checkNotNull(buffer.data).remaining()).also { checkNotNull(buffer.data).get(it) }
                assertArrayEquals(original, replay)
                assertEquals(time, buffer.timeUs)
                assertTrue(buffer.isKeyFrame)
                preview.inputQueued()
                assertFalse(preview.drainAndRewind())
            } else {
                assertFalse(preview.outputAllowed())
            }
        } finally {
            harness.close()
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [0, 120])
    fun `accepted edge seek discards old queues and waits for complete new samples`(edge: Long) = runTest {
        val harness = PeriodHarness(this)
        try {
            harness.start(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio()
            harness.looper.runAll()
            val stream = harness.select(0)
            val holder = FormatHolder()
            val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
            assertTrue(stream.isReady)
            harness.seek(edge.seconds)
            assertFalse(stream.isReady)
            assertEquals(C.RESULT_NOTHING_READ, stream.readData(holder, buffer, 0))
            assertEquals(0, stream.skipData(Long.MAX_VALUE))
            assertEquals(C.TIME_UNSET, harness.period.readDiscontinuity())
            // A timestamp anchor with no elementary sample cannot settle the seek.
            harness.packet(0, PeriodCountingBinary(byteArrayOf()), edge * 1_000_000)
            assertEquals(C.TIME_UNSET, harness.period.readDiscontinuity())
            harness.packet(0, PeriodCountingBinary(fixture("mpeg-audio.bin")), edge * 1_000_000 + 40_000)
            assertFalse(stream.isReady)
            val resumed = harness.period.readDiscontinuity()
            assertTrue(resumed >= 40_000, "Discontinuity uses the new rebased period coordinate")
            assertEquals(C.TIME_UNSET, harness.period.readDiscontinuity())
            assertEquals(C.RESULT_FORMAT_READ, stream.readData(holder, buffer, 0))
            assertEquals(C.RESULT_BUFFER_READ, stream.readData(holder, buffer, 0))
            assertEquals(resumed, buffer.timeUs)
            assertTrue(buffer.isKeyFrame)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `equal fresh group retains only its current queue and supports deselection`() = runTest {
        val harness = PeriodHarness(this)
        try {
            harness.start(SubscriptionStreamType.MPEG2_AUDIO, SubscriptionStreamType.AAC)
            harness.audio()
            harness.packet(1, PeriodCountingBinary(fixture("aac-adts.bin")))
            harness.looper.runAll()
            val group = harness.period.trackGroups[0]
            val fresh = TrackGroup(group.id, group.getFormat(0).buildUpon().build())
            assertEquals(group, fresh)
            assertNotSame(group, fresh)
            val streams = arrayOf<SampleStream?>(harness.select(0))
            val original = streams[0]
            val reset = booleanArrayOf(false)
            harness.period.selectTracks(arrayOf(FixedTrackSelection(fresh, 0)), booleanArrayOf(true), streams, reset, 0)
            assertSame(original, streams[0])
            assertFalse(reset[0])
            harness.period.selectTracks(
                arrayOf(FixedTrackSelection(harness.period.trackGroups[1], 0)), booleanArrayOf(true), streams, reset, 0,
            )
            assertNotSame(original, streams[0])
            assertTrue(reset[0])
            val holder = FormatHolder()
            assertEquals(C.RESULT_FORMAT_READ, checkNotNull(streams[0]).readData(
                holder, DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL), 0,
            ))
            assertEquals(MimeTypes.AUDIO_AAC, holder.format?.sampleMimeType)
            reset[0] = false
            harness.period.selectTracks(arrayOfNulls<ExoTrackSelection>(1), booleanArrayOf(true), streams, reset, 0)
            assertNull(streams[0])
            assertFalse(reset[0])
            harness.period.selectTracks(arrayOf(FixedTrackSelection(fresh, 0)), booleanArrayOf(false), streams, reset, 0)
            assertTrue(reset[0])
            harness.period.interrupt()
            assertFalse(checkNotNull(streams[0]).isReady)
            assertEquals(C.RESULT_NOTHING_READ, checkNotNull(streams[0]).readData(
                holder, DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL), 0,
            ))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `unavailable group and invalid single track indices are rejected`() = runTest {
        val harness = PeriodHarness(this)
        try {
            harness.start(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio()
            harness.looper.runAll()
            val group = harness.period.trackGroups[0]
            val valid = FixedTrackSelection(group, 0)
            val invalidSelections = listOf(
                FixedTrackSelection(TrackGroup("unavailable", group.getFormat(0)), 0),
                object : ExoTrackSelection by valid { override fun getIndexInTrackGroup(index: Int): Int = 1 },
                object : ExoTrackSelection by valid { override fun getIndexInTrackGroup(index: Int): Int = -1 },
                object : ExoTrackSelection by valid { override fun length(): Int = 2 },
            )
            invalidSelections.forEach { selection ->
                assertThrows(IllegalStateException::class.java) {
                    harness.period.selectTracks(arrayOf(selection), booleanArrayOf(false),
                        arrayOfNulls<SampleStream>(1), booleanArrayOf(false), 0)
                }
            }
            harness.select(0).maybeThrowError()
        } finally {
            harness.close()
        }
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun enableStrictMedia3BoundsChecks() {
            ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
        }
    }

    @Test
    fun `primary audio video prepares without silent alternative audio and freezes groups`() = runTest {
        val harness = PeriodHarness(this)
        try {
            harness.start(SubscriptionStreamType.MPEG2_AUDIO, SubscriptionStreamType.H264, SubscriptionStreamType.AAC)
            harness.audio()
            harness.video()
            harness.looper.runAll()
            assertEquals(0, harness.preparations)
            advanceTimeBy(999.milliseconds)
            runCurrent()
            harness.looper.runAll()
            assertEquals(0, harness.preparations)
            advanceTimeBy(1.milliseconds)
            runCurrent()
            harness.looper.runAll()
            assertEquals(1, harness.preparations)
            val groups = harness.period.trackGroups
            assertEquals(2, groups.length)
            assertEquals(MimeTypes.AUDIO_MPEG_L2, groups[0].getFormat(0).sampleMimeType)
            assertEquals(MimeTypes.VIDEO_H264, groups[1].getFormat(0).sampleMimeType)
            assertEquals(720, groups[1].getFormat(0).width)
            assertEquals(576, groups[1].getFormat(0).height)
            val allocated = harness.allocator.totalBytesAllocated
            val late = PeriodCountingBinary(fixture("aac-adts.bin"))
            repeat(100) { harness.packet(2, late) }
            assertEquals(0, late.copies)
            assertEquals(allocated, harness.allocator.totalBytesAllocated)
            assertSame(groups, harness.period.trackGroups)
            assertSame(groups[0], harness.period.trackGroups[0])
            assertSame(groups[1], harness.period.trackGroups[1])
            assertEquals(1, harness.preparations)
            harness.period.maybeThrowPrepareError()
            val audio = harness.select(0)
            val holder = FormatHolder()
            val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
            assertEquals(C.RESULT_FORMAT_READ, audio.readData(holder, buffer, 0))
            assertEquals(MimeTypes.AUDIO_MPEG_L2, holder.format?.sampleMimeType)
            assertEquals(C.RESULT_BUFFER_READ, audio.readData(holder, buffer, 0))
            assertEquals(576, buffer.data?.position())
            val sample = ByteArray(576)
            checkNotNull(buffer.data).flip()
            checkNotNull(buffer.data).get(sample)
            assertArrayEquals(fixture("mpeg-audio.bin"), sample)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `healthy interleaved audio alternatives remain selectable without waiting for deadline`() = runTest {
        val harness = PeriodHarness(this)
        try {
            harness.start(SubscriptionStreamType.MPEG2_AUDIO, SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio()
            harness.looper.runAll()
            assertEquals(0, harness.preparations)
            advanceTimeBy(100.milliseconds)
            harness.packet(1, PeriodCountingBinary(fixture("mpeg-audio.bin")))
            harness.looper.runAll()
            assertEquals(1, harness.preparations)
            assertEquals(2, harness.period.trackGroups.length)
            advanceTimeBy(1.seconds)
            runCurrent()
            harness.looper.runAll()
            assertEquals(1, harness.preparations)
            val audio = harness.select(1)
            assertEquals(
                C.RESULT_FORMAT_READ,
                audio.readData(FormatHolder(), DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL), 0),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `healthy alternative audio arriving after primary audio video is retained`() = runTest {
        val harness = PeriodHarness(this)
        try {
            harness.start(SubscriptionStreamType.MPEG2_AUDIO, SubscriptionStreamType.H264, SubscriptionStreamType.AAC)
            harness.audio()
            harness.video()
            harness.packet(2, PeriodCountingBinary(fixture("aac-adts.bin")))
            harness.looper.runAll()
            assertEquals(1, harness.preparations)
            assertEquals(3, harness.period.trackGroups.length)
            assertEquals(MimeTypes.AUDIO_AAC, harness.period.trackGroups[2].getFormat(0).sampleMimeType)
        } finally {
            harness.close()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `release and terminal state fence pending alternative discovery`(terminal: Boolean) = runTest {
        val harness = PeriodHarness(this)
        try {
            harness.start(SubscriptionStreamType.MPEG2_AUDIO, SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio()
            if (terminal) {
                harness.connection.emit(SubscriptionEvent.Terminated(SubscriptionTermination.LOCAL_RETIREMENT))
                runCurrent()
            } else {
                harness.period.release()
            }
            advanceTimeBy(1.seconds)
            runCurrent()
            harness.looper.runAll()
            assertEquals(0, harness.preparations)
            assertFalse(harness.period.isLoading)
            if (terminal) assertThrows(IOException::class.java) { harness.period.maybeThrowPrepareError() }
        } finally {
            harness.close()
        }
    }

    @Test
    fun `earlier audio cannot bypass advertised video`() = runTest {
        val harness = PeriodHarness(this)
        try {
            harness.start(SubscriptionStreamType.MPEG2_AUDIO, SubscriptionStreamType.H264)
            harness.audio()
            harness.looper.runAll()
            assertEquals(0, harness.preparations)
            assertTrue(harness.period.isLoading)
            harness.video()
            harness.looper.runAll()
            assertEquals(1, harness.preparations)
            assertEquals(2, harness.period.trackGroups.length)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `subtitle and video cannot bypass supported but silent audio category`() = runTest {
        val harness = PeriodHarness(this)
        try {
            harness.start(SubscriptionStreamType.MPEG2_AUDIO, SubscriptionStreamType.H264, SubscriptionStreamType.DVB_SUBTITLE)
            harness.looper.runAll()
            assertEquals(0, harness.preparations)
            harness.video()
            harness.looper.runAll()
            assertEquals(0, harness.preparations)
            assertTrue(harness.period.isLoading)
            harness.audio()
            harness.looper.runAll()
            assertEquals(1, harness.preparations)
            assertEquals(3, harness.period.trackGroups.length)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `unsupported packets are ignored but unknown indices still fail the period`() = runTest {
        val harness = PeriodHarness(this)
        try {
            harness.start(SubscriptionStreamType.MPEG2_AUDIO, SubscriptionStreamType.UNKNOWN)
            harness.audio()
            harness.looper.runAll()
            assertEquals(1, harness.preparations)
            val ignored = PeriodCountingBinary(byteArrayOf(1))
            harness.packet(1, ignored)
            assertEquals(0, ignored.copies)
            harness.period.maybeThrowPrepareError()
            harness.packet(2, ignored)
            assertEquals(0, ignored.copies)
            assertFalse(harness.period.isLoading)
            assertThrows(IOException::class.java) { harness.period.maybeThrowPrepareError() }
        } finally {
            harness.close()
        }
    }

    @Test
    fun `partial alternative video allocation is retired at preparation before late packets`() = runTest {
        val harness = PeriodHarness(this)
        try {
            harness.start(SubscriptionStreamType.MPEG2_AUDIO, SubscriptionStreamType.H264, SubscriptionStreamType.H264)
            harness.video()
            val primaryVideoBytes = harness.allocator.totalBytesAllocated
            harness.packet(2, PeriodCountingBinary(fixture("channel-016-stream-01-packet-001.bin").copyOf(2)))
            assertEquals(primaryVideoBytes + 1_024, harness.allocator.totalBytesAllocated)
            harness.audio()
            harness.period.bufferedPositionUs
            harness.period.nextLoadPositionUs
            harness.period.discardBuffer(0, false)
            advanceTimeBy(1.seconds)
            runCurrent()
            // One MPEG-audio allocation replaces the omitted partial-video allocation.
            assertEquals(primaryVideoBytes + 1_024, harness.allocator.totalBytesAllocated)
            val late = PeriodCountingBinary(fixture("channel-016-stream-01-packet-001.bin"))
            harness.packet(2, late)
            assertEquals(0, late.copies)
            harness.looper.runAll()
            assertEquals(1, harness.preparations)
            assertEquals(2, harness.period.trackGroups.length)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `state only unanchorable terminal after real preparation reaches period and sample stream`() = runTest {
        val harness = PeriodHarness(this)
        try {
            harness.start(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio()
            harness.looper.runAll()
            assertEquals(1, harness.preparations)
            val stream = harness.select(0)
            harness.invalidateResumedSegment()
            assertFalse(harness.period.isLoading)
            val error = assertThrows(IOException::class.java) { harness.period.maybeThrowPrepareError() }
            assertEquals("Live subscription preparation failed", error.message)
            assertSame(error, assertThrows(IOException::class.java) { stream.maybeThrowError() })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `state only terminal before preparation fences queued callback`() = runTest {
        val harness = PeriodHarness(this)
        try {
            harness.start(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio()
            harness.invalidateResumedSegment()
            harness.looper.runAll()
            assertEquals(0, harness.preparations)
            assertFalse(harness.period.isLoading)
            assertThrows(IOException::class.java) { harness.period.maybeThrowPrepareError() }
        } finally {
            harness.close()
        }
    }

    @Test
    fun `delivered clean termination retains eos without state observer error`() = runTest {
        val harness = PeriodHarness(this)
        try {
            harness.start(SubscriptionStreamType.MPEG2_AUDIO)
            harness.audio()
            harness.looper.runAll()
            val stream = harness.select(0)
            harness.connection.emit(SubscriptionEvent.Terminated(SubscriptionTermination.LOCAL_RETIREMENT))
            runCurrent()
            assertFalse(harness.period.isLoading)
            harness.period.maybeThrowPrepareError()
            stream.maybeThrowError()
            assertEquals(C.TIME_END_OF_SOURCE, harness.period.bufferedPositionUs)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `release before queued preparation fences callback and late packet`() = runTest {
        val harness = PeriodHarness(this)
        harness.start(SubscriptionStreamType.MPEG2_AUDIO)
        harness.audio()
        harness.period.release()
        val late = PeriodCountingBinary(fixture("mpeg-audio.bin"))
        harness.period.accept(packetEvent(0, late))
        harness.close()
        assertEquals(0, harness.preparations)
        assertFalse(harness.period.isLoading)
        assertEquals(0, late.copies)
        assertEquals(0, harness.allocator.totalBytesAllocated)
        harness.period.maybeThrowPrepareError()
    }

    @Test
    fun `rejected preparation callback stops loading with safe error`() = runTest {
        val harness = PeriodHarness(this)
        try {
            harness.start(SubscriptionStreamType.MPEG2_AUDIO)
            harness.looper.accepting = false
            harness.audio()
            assertFalse(harness.period.isLoading)
            assertThrows(IOException::class.java) { harness.period.maybeThrowPrepareError() }
            assertEquals(0, harness.preparations)
        } finally {
            harness.close()
        }
    }
}

private class PeriodHarness(private val scope: TestScope, attachment: LiveTimeshiftControlBridge.Attachment? = null) {
    val looper = QueuedCoordinatorLooper()
    val allocator = DefaultAllocator(false, 1_024)
    val connection = ScriptedSubscriptionConnection()
    private val dispatcher = StandardTestDispatcher(scope.testScheduler)
    private val manager = createSubscriptionManager(connection, dispatcher)
    private lateinit var subscription: ActiveSubscription
    var preparations = 0
    val period = TvheadendLiveMediaPeriod(
        allocator = allocator,
        timeshiftControls = attachment,
        onUnsupportedStream = {},
        workerDispatcher = dispatcher,
        callbackSchedulerFactory = { looper },
    )

    suspend fun start(vararg types: SubscriptionStreamType) {
        connection.scriptSubscribe(SubscriptionOperationResult.Ok(SubscriptionConfirmation(null, null, null, 120)))
        manager.startAdmission()
        period.prepare(object : MediaPeriod.Callback {
            override fun onPrepared(mediaPeriod: MediaPeriod) {
                assertTrue(looper.isCurrent())
                preparations++
            }
            override fun onContinueLoadingRequested(source: MediaPeriod) = Unit
        }, 0)
        val opening = scope.async { manager.open(SubscriptionChannelId(1), period, 120.seconds) }
        scope.runCurrent()
        connection.emit(SubscriptionEvent.Started(types.mapIndexed { index, type ->
            SubscriptionStream(
                StreamIndex(index.toLong()), type, null,
                if (type == SubscriptionStreamType.DVB_SUBTITLE) 1L else null,
                if (type == SubscriptionStreamType.DVB_SUBTITLE) 2L else null,
                null, null,
                null, null, null, null, null, null, null, null, null,
            )
        }, null, SubscriptionCondition.NO_DETAIL))
        scope.runCurrent()
        subscription = (opening.await() as SubscriptionOpenResult.Opened).subscription
        period.bind(subscription)
        scope.backgroundScope.launch {
            subscription.state.first { it is SubscriptionState.Terminal }
            period.terminal(subscription)
        }
        assertTrue(subscription.state.value is SubscriptionState.Playable)
    }

    suspend fun audio() = packet(0, PeriodCountingBinary(fixture("mpeg-audio.bin")))

    suspend fun video() = packet(1, PeriodCountingBinary(fixture("channel-016-stream-01-packet-001.bin")))

    suspend fun packet(index: Long, payload: SubscriptionBinary, timeUs: Long = 1_117_733) {
        connection.emit(packetEvent(index, payload, timeUs))
        scope.runCurrent()
    }

    suspend fun seek(position: kotlin.time.Duration, outcome: SkipOutcome = SkipOutcome.ACCEPTED) {
        val seeking = scope.async { subscription.seek(SubscriptionSeekTarget.Absolute(position)) }
        scope.runCurrent()
        // A packet already in flight before the acknowledgement must not enter the resumed queues.
        connection.emit(packetEvent(0, PeriodCountingBinary(fixture("mpeg-audio.bin")), 9_000_000))
        scope.runCurrent()
        connection.emit(SubscriptionEvent.Skipped(true, outcome, position.inWholeMicroseconds, null))
        scope.runCurrent()
        seeking.await()
    }

    fun select(index: Int): SampleStream {
        val streams = arrayOfNulls<SampleStream>(1)
        period.selectTracks(
            arrayOf(FixedTrackSelection(period.trackGroups[index], 0)),
            booleanArrayOf(false), streams, booleanArrayOf(false), 0,
        )
        return checkNotNull(streams[0])
    }

    suspend fun invalidateResumedSegment() {
        val seeking = scope.async { subscription.seek(SubscriptionSeekTarget.Absolute(5.seconds)) }
        scope.runCurrent()
        connection.emit(SubscriptionEvent.Skipped(true, SkipOutcome.ACCEPTED, 5_000_000, null))
        scope.runCurrent()
        assertTrue(seeking.await() is SubscriptionSeekResult.AcceptedAt)
        repeat(1_024) {
            connection.emit(packetEvent(0, PeriodCountingBinary(byteArrayOf()), timeUs = null))
        }
        scope.runCurrent()
        val terminal = subscription.state.value as SubscriptionState.Terminal
        assertEquals(
            SubscriptionSeekInvalidation.RESUMED_SEGMENT_UNANCHORABLE,
            (terminal.reason as SubscriptionTerminalReason.SeekInvalidated).cause,
        )
    }

    suspend fun close() {
        period.release()
        scope.runCurrent()
        looper.runAll()
        manager.closeAndJoin()
    }
}

private fun packetEvent(index: Long, payload: SubscriptionBinary, timeUs: Long? = 1_117_733) =
    SubscriptionEvent.Packet(MuxFrameType.I, StreamIndex(index), timeUs, timeUs, 40_000, payload)

private fun fixture(name: String): ByteArray = checkNotNull(
    TvheadendLiveMediaPeriodTest::class.java.getResourceAsStream("/recorded-mux/$name"),
).use { it.readBytes() }

private class PeriodCountingBinary(private val bytes: ByteArray) : SubscriptionBinary {
    var copies = 0
    override val size: Int = bytes.size
    override fun copyInto(destination: ByteArray, destinationOffset: Int): Int {
        copies++
        bytes.copyInto(destination, destinationOffset)
        return bytes.size
    }
}
