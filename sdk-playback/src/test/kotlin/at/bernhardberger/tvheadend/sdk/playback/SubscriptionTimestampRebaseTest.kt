@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionTimestampRebaseTest {
    @Test
    fun `packets keep their exact identity until a discontinuity is accepted`() {
        val rebaser = rebaser()
        val first = packet(presentationTimeUs = 1_000L)
        val second = packet(presentationTimeUs = 2_000L, frameType = MuxFrameType.P)

        assertSame(first, rebaser.deliverPacket(first))
        assertSame(second, rebaser.deliverPacket(second))
    }

    @Test
    fun `an accepted skip waits for a video keyframe before re-anchoring`() {
        val rebaser = rebaser()
        rebaser.deliverPacket(packet(presentationTimeUs = 1_000L, durationUs = 40_000L))
        rebaser.classify(skipped(SkipOutcome.ACCEPTED))

        assertNull(
            rebaser.deliverPacket(
                packet(presentationTimeUs = 500_000L, frameType = MuxFrameType.P),
            ),
            "A mid-GOP packet must not re-anchor the timeline",
        )
        assertNull(
            rebaser.deliverPacket(
                packet(presentationTimeUs = 540_000L, frameType = MuxFrameType.B),
            ),
        )
        val decision = rebaser.classify(
            packet(presentationTimeUs = 580_000L, frameType = MuxFrameType.I),
        )

        assertTrue(decision is RebaseDecision.Deliver && decision.anchored)
        assertEquals(
            41_000L,
            ((decision as RebaseDecision.Deliver).event as SubscriptionEvent.Packet)
                .presentationTimeUs,
            "The anchor is presented one frame after the last delivered frame",
        )
    }

    @Test
    fun `one offset is shared by every track of the resumed segment`() {
        val rebaser = rebaser(
            tracks = listOf(
                stream(index = 0L),
                stream(index = 1L, type = SubscriptionStreamType.MPEG2_AUDIO),
            ),
        )
        rebaser.deliverPacket(
            packet(presentationTimeUs = 1_000L, streamIndex = 0L, durationUs = 40_000L),
        )
        rebaser.deliverPacket(
            packet(
                presentationTimeUs = 20_000L,
                frameType = MuxFrameType.UNKNOWN,
                streamIndex = 1L,
                durationUs = 24_000L,
            ),
        )
        rebaser.classify(skipped(SkipOutcome.ACCEPTED))

        val video = rebaser.deliverPacket(
            packet(presentationTimeUs = 580_000L, streamIndex = 0L),
        )
        val audio = rebaser.deliverPacket(
            packet(
                presentationTimeUs = 585_000L,
                frameType = MuxFrameType.UNKNOWN,
                streamIndex = 1L,
            ),
        )

        assertEquals(44_000L, video?.presentationTimeUs)
        assertEquals(49_000L, audio?.presentationTimeUs)
        assertEquals(
            585_000L - 580_000L,
            requireNotNull(audio?.presentationTimeUs) -
                requireNotNull(video?.presentationTimeUs),
            "One shared offset must preserve the relative track positions",
        )
    }

    @Test
    fun `the timeline re-anchors after exactly 256 discarded packets`() {
        val rebaser = rebaser()
        rebaser.deliverPacket(packet(presentationTimeUs = 0L, durationUs = 1_000L))
        rebaser.classify(skipped(SkipOutcome.ACCEPTED))

        repeat(256) { index ->
            assertNull(
                rebaser.deliverPacket(
                    packet(
                        presentationTimeUs = 100_000L + index,
                        frameType = MuxFrameType.UNKNOWN,
                    ),
                ),
                "A stream that reports no frame type must not anchor before its budget",
            )
        }
        val anchor = rebaser.deliverPacket(
            packet(presentationTimeUs = 100_256L, frameType = MuxFrameType.UNKNOWN),
        )

        assertEquals(1_000L, anchor?.presentationTimeUs)
    }

    @Test
    fun `a packet without a presentation time never consumes the pending discontinuity`() {
        val rebaser = rebaser()
        rebaser.deliverPacket(packet(presentationTimeUs = 1_000L, durationUs = 1_000L))
        rebaser.classify(skipped(SkipOutcome.ACCEPTED))

        repeat(300) {
            assertNull(rebaser.deliverPacket(packet(presentationTimeUs = null)))
        }
        assertNull(
            rebaser.deliverPacket(
                packet(presentationTimeUs = 90_000L, frameType = MuxFrameType.P),
            ),
            "Untimed packets must not spend the budget that releases a frame type of -1",
        )
        val anchor = rebaser.deliverPacket(packet(presentationTimeUs = 90_040L))

        assertEquals(
            2_000L,
            anchor?.presentationTimeUs,
            "Only a timed keyframe can define the shared offset",
        )
    }

    @Test
    fun `a resumed segment that never carries a timestamp stops discarding`() {
        val rebaser = rebaser(
            settings = TimestampRebaseSettings(anchorPacketBudget = 1, anchorDiscardLimit = 3),
        )
        rebaser.deliverPacket(packet(presentationTimeUs = 1_000L, durationUs = 1_000L))
        rebaser.classify(skipped(SkipOutcome.ACCEPTED))

        repeat(2) {
            assertSame(
                RebaseDecision.Discard,
                rebaser.classify(packet(presentationTimeUs = null)),
            )
        }

        assertSame(
            RebaseDecision.Unanchorable,
            rebaser.classify(packet(presentationTimeUs = null)),
            "An unanchorable segment must end the subscription instead of discarding forever",
        )
    }

    @Test
    fun `a timed stream always anchors before the discard limit is reached`() {
        val rebaser = rebaser(
            settings = TimestampRebaseSettings(anchorPacketBudget = 2, anchorDiscardLimit = 3),
        )
        rebaser.deliverPacket(packet(presentationTimeUs = 1_000L, durationUs = 1_000L))
        rebaser.classify(skipped(SkipOutcome.ACCEPTED))

        repeat(2) { index ->
            assertSame(
                RebaseDecision.Discard,
                rebaser.classify(
                    packet(
                        presentationTimeUs = 500_000L + index,
                        frameType = MuxFrameType.UNKNOWN,
                    ),
                ),
            )
        }
        val anchor = rebaser.deliverPacket(
            packet(presentationTimeUs = 500_002L, frameType = MuxFrameType.UNKNOWN),
        )

        assertEquals(
            2_000L,
            anchor?.presentationTimeUs,
            "The keyframe budget must resolve a timed stream before the discard limit can",
        )
    }

    @Test
    fun `an unclassified stream costs a bounded prefix before the budget releases it`() {
        val rebaser = rebaser(
            settings = TimestampRebaseSettings(anchorPacketBudget = 2),
            tracks = listOf(
                stream(index = 0L, type = SubscriptionStreamType.UNKNOWN),
                stream(index = 1L, type = SubscriptionStreamType.MPEG2_AUDIO),
            ),
        )
        rebaser.deliverPacket(
            packet(
                presentationTimeUs = 1_000L,
                frameType = MuxFrameType.UNKNOWN,
                streamIndex = 1L,
                durationUs = 1_000L,
            ),
        )
        rebaser.classify(skipped(SkipOutcome.ACCEPTED))

        repeat(2) { index ->
            assertNull(
                rebaser.deliverPacket(audioAt(500_000L + index)),
                "A stream this SDK cannot classify may still carry the video keyframe",
            )
        }
        val anchor = rebaser.deliverPacket(audioAt(500_002L))

        assertEquals(
            2_000L,
            anchor?.presentationTimeUs,
            "The bounded fallback releases audio once the unknown stream reports no keyframe",
        )
    }

    @Test
    fun `rebased packets at or below the post-seek floor are discarded`() {
        val rebaser = rebaser(
            tracks = listOf(
                stream(index = 0L),
                stream(index = 1L, type = SubscriptionStreamType.AC3),
            ),
        )
        rebaser.deliverPacket(
            packet(
                presentationTimeUs = 100_000L,
                frameType = MuxFrameType.UNKNOWN,
                streamIndex = 1L,
                durationUs = 24_000L,
            ),
        )
        rebaser.classify(skipped(SkipOutcome.ACCEPTED))
        val anchor = rebaser.deliverPacket(
            packet(presentationTimeUs = 500_000L, streamIndex = 0L, durationUs = 40_000L),
        )

        assertEquals(124_000L, anchor?.presentationTimeUs)
        assertNull(
            rebaser.deliverPacket(audioAt(470_000L)),
            "A track interleaved before the anchor must not move backwards",
        )
        assertNull(
            rebaser.deliverPacket(audioAt(476_000L)),
            "The floor itself is not a usable output position",
        )
        assertEquals(101_000L, rebaser.deliverPacket(audioAt(477_000L))?.presentationTimeUs)
    }

    @Test
    fun `the shared offset moves decoding timestamps and keeps the payload`() {
        val rebaser = rebaser()
        rebaser.deliverPacket(packet(presentationTimeUs = 1_000L, durationUs = 1_000L))
        rebaser.classify(skipped(SkipOutcome.ACCEPTED))
        val payload = CountingBinary(byteArrayOf(1, 2, 3))

        val anchor = rebaser.deliverPacket(
            packet(
                payload = payload,
                presentationTimeUs = 50_000L,
                decodingTimeUs = 49_000L,
                durationUs = 20_000L,
            ),
        )

        assertEquals(2_000L, anchor?.presentationTimeUs)
        assertEquals(1_000L, anchor?.decodingTimeUs)
        assertEquals(20_000L, anchor?.durationUs)
        assertEquals(MuxFrameType.I, anchor?.frameType)
        assertEquals(StreamIndex(0L), anchor?.streamIndex)
        assertSame(payload, anchor?.payload, "Rebasing must not copy or replace the payload")
        assertEquals(0, payload.copyCount)
    }

    @Test
    fun `tracks that cannot carry video anchor on their first timed packet`() {
        val rebaser = rebaser(
            tracks = listOf(stream(index = 0L, type = SubscriptionStreamType.MPEG2_AUDIO)),
        )
        rebaser.deliverPacket(
            packet(
                presentationTimeUs = 1_000L,
                frameType = MuxFrameType.UNKNOWN,
                durationUs = 24_000L,
            ),
        )
        rebaser.classify(skipped(SkipOutcome.ACCEPTED))

        val anchor = rebaser.deliverPacket(
            packet(presentationTimeUs = 700_000L, frameType = MuxFrameType.UNKNOWN),
        )

        assertEquals(
            25_000L,
            anchor?.presentationTimeUs,
            "There is no keyframe to wait for when no stream can carry video",
        )
    }

    @Test
    fun `a keyframe on a track that cannot carry video never ends the anchor wait`() {
        val rebaser = rebaser(
            tracks = listOf(
                stream(index = 0L),
                stream(index = 1L, type = SubscriptionStreamType.AC3),
            ),
        )
        rebaser.deliverPacket(packet(presentationTimeUs = 1_000L, durationUs = 1_000L))
        rebaser.classify(skipped(SkipOutcome.ACCEPTED))

        assertNull(
            rebaser.deliverPacket(packet(presentationTimeUs = 500_000L, streamIndex = 1L)),
            "An audio frame reported as a keyframe must not define the shared offset",
        )
        assertEquals(
            2_000L,
            rebaser.deliverPacket(
                packet(presentationTimeUs = 500_040L, streamIndex = 0L),
            )?.presentationTimeUs,
            "Only a committed stream that may carry video re-anchors the timeline",
        )
    }

    @Test
    fun `an unclassified stream still waits for a keyframe`() {
        val rebaser = rebaser(
            tracks = listOf(stream(index = 0L, type = SubscriptionStreamType.UNKNOWN)),
        )
        rebaser.deliverPacket(packet(presentationTimeUs = 1_000L, durationUs = 1_000L))
        rebaser.classify(skipped(SkipOutcome.ACCEPTED))

        assertNull(
            rebaser.deliverPacket(
                packet(presentationTimeUs = 700_000L, frameType = MuxFrameType.P),
            ),
        )
        assertEquals(
            2_000L,
            rebaser.deliverPacket(packet(presentationTimeUs = 740_000L))?.presentationTimeUs,
        )
    }

    @Test
    fun `the fallback frame gap is used when the last delivered packet reported none`() {
        val rebaser = rebaser(settings = TimestampRebaseSettings(fallbackFrameGapUs = 5_000L))
        rebaser.deliverPacket(packet(presentationTimeUs = 1_000L, durationUs = 0L))
        rebaser.classify(skipped(SkipOutcome.ACCEPTED))

        val anchor = rebaser.deliverPacket(packet(presentationTimeUs = 90_000L))

        assertEquals(6_000L, anchor?.presentationTimeUs)
    }

    @Test
    fun `nothing is rebased when no timestamp was delivered before the discontinuity`() {
        val rebaser = rebaser()
        rebaser.classify(skipped(SkipOutcome.ACCEPTED))

        assertNull(
            rebaser.deliverPacket(
                packet(presentationTimeUs = 900_000L, frameType = MuxFrameType.P),
            ),
            "The keyframe wait still applies to the first delivered segment",
        )
        val anchor = packet(presentationTimeUs = 940_000L)

        assertSame(
            anchor,
            rebaser.deliverPacket(anchor),
            "A resumed segment with no earlier output keeps its natural timeline",
        )
    }

    @Test
    fun `only an accepted acknowledgement arms a discontinuity`() {
        val rebaser = rebaser()
        rebaser.deliverPacket(packet(presentationTimeUs = 1_000L))
        val rejected = skipped(SkipOutcome.REJECTED)
        val unknown = skipped(SkipOutcome.UNKNOWN)

        assertSame(rejected, rebaser.deliver(rejected))
        val afterRejection = packet(presentationTimeUs = 2_000L, frameType = MuxFrameType.P)
        assertSame(afterRejection, rebaser.deliverPacket(afterRejection))

        assertSame(unknown, rebaser.deliver(unknown))
        val afterUnknown = packet(presentationTimeUs = 3_000L, frameType = MuxFrameType.P)
        assertSame(afterUnknown, rebaser.deliverPacket(afterUnknown))
    }

    @Test
    fun `a dropped packet marker is not a timestamp discontinuity`() {
        val rebaser = rebaser()
        rebaser.deliverPacket(packet(presentationTimeUs = 1_000L, durationUs = 40_000L))
        val dropped = SubscriptionEvent.Dropped(3L)

        assertSame(dropped, rebaser.deliver(dropped))
        val resumed = packet(presentationTimeUs = 9_000_000L, frameType = MuxFrameType.P)

        assertSame(
            resumed,
            rebaser.deliverPacket(resumed),
            "Queue pressure only moves the timeline forward, so it must not re-anchor",
        )
    }

    @Test
    fun `an untimed packet after the anchor keeps its rebased decoding time`() {
        val rebaser = rebaser()
        rebaser.deliverPacket(packet(presentationTimeUs = 1_000L, durationUs = 1_000L))
        rebaser.classify(skipped(SkipOutcome.ACCEPTED))
        assertEquals(
            2_000L,
            rebaser.deliverPacket(
                packet(presentationTimeUs = 90_000L, decodingTimeUs = 90_000L),
            )?.presentationTimeUs,
        )

        val untimed = rebaser.deliverPacket(
            packet(presentationTimeUs = null, decodingTimeUs = 91_000L),
        )

        assertNull(untimed?.presentationTimeUs)
        assertEquals(
            3_000L,
            untimed?.decodingTimeUs,
            "The shared offset still applies to a packet the floor cannot classify",
        )
    }

    @Test
    fun `control events keep their committed position while an anchor is pending`() {
        val rebaser = rebaser()
        rebaser.deliverPacket(packet(presentationTimeUs = 1_000L))
        rebaser.classify(skipped(SkipOutcome.ACCEPTED))
        val status = SubscriptionEvent.Status(SubscriptionCondition.STATUS_REPORTED)
        val dropped = SubscriptionEvent.Dropped(4L)

        assertSame(status, rebaser.deliver(status))
        assertSame(dropped, rebaser.deliver(dropped))
    }

    @Test
    fun `timeshift updates keep the server coordinates a seek target uses`() {
        val rebaser = rebaser()
        rebaser.deliverPacket(packet(presentationTimeUs = 1_000L, durationUs = 1_000L))
        rebaser.classify(skipped(SkipOutcome.ACCEPTED))
        assertEquals(
            2_000L,
            rebaser.deliverPacket(packet(presentationTimeUs = 900_000L))?.presentationTimeUs,
        )
        val timeshift = SubscriptionEvent.Timeshift(
            full = 300_000_000L,
            shift = 60_000_000L,
            start = 600_000L,
            end = 900_000L,
            speed = null,
        )

        assertSame(
            timeshift,
            rebaser.deliver(timeshift),
            "Seek coordinates stay in server time, so a timeshift report must not be rebased",
        )
    }

    @Test
    fun `a second discontinuity restarts the anchor budget`() {
        val rebaser = rebaser(settings = TimestampRebaseSettings(anchorPacketBudget = 2))
        rebaser.deliverPacket(packet(presentationTimeUs = 1_000L, durationUs = 1_000L))
        rebaser.classify(skipped(SkipOutcome.ACCEPTED))
        assertNull(
            rebaser.deliverPacket(
                packet(presentationTimeUs = 500_000L, frameType = MuxFrameType.P),
            ),
        )

        rebaser.classify(skipped(SkipOutcome.ACCEPTED))
        assertNull(
            rebaser.deliverPacket(
                packet(presentationTimeUs = 800_000L, frameType = MuxFrameType.P),
            ),
        )
        assertNull(
            rebaser.deliverPacket(
                packet(presentationTimeUs = 800_100L, frameType = MuxFrameType.P),
            ),
        )
        val anchor = rebaser.deliverPacket(
            packet(presentationTimeUs = 800_200L, frameType = MuxFrameType.P),
        )

        assertEquals(2_000L, anchor?.presentationTimeUs)
    }

    @Test
    fun `an extreme timeline saturates instead of wrapping backwards`() {
        val rebaser = rebaser()
        rebaser.deliverPacket(
            packet(presentationTimeUs = Long.MAX_VALUE - 1L, durationUs = 4L),
        )
        rebaser.classify(skipped(SkipOutcome.ACCEPTED))

        val anchor = rebaser.deliverPacket(packet(presentationTimeUs = 0L, decodingTimeUs = 0L))

        assertEquals(Long.MAX_VALUE, anchor?.presentationTimeUs)
        assertEquals(Long.MAX_VALUE, anchor?.decodingTimeUs)
    }

    @Test
    fun `rebase settings reject unusable bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            TimestampRebaseSettings(anchorPacketBudget = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimestampRebaseSettings(fallbackFrameGapUs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimestampRebaseSettings(anchorPacketBudget = 4, anchorDiscardLimit = 4)
        }
    }

    @Test
    fun `an accepted seek rebases the resumed segment onto the delivered timeline`() = runTest {
        val fixture = openRebasing()
        fixture.connection.emit(packet(presentationTimeUs = 1_000L, durationUs = 40_000L))
        runCurrent()

        val seeking = async {
            fixture.subscription.seek(SubscriptionSeekTarget.Absolute(30.seconds))
        }
        runCurrent()
        fixture.connection.emit(packet(presentationTimeUs = 41_000L, durationUs = 40_000L))
        fixture.connection.emit(skipped(SkipOutcome.ACCEPTED))
        runCurrent()
        assertSame(SubscriptionSeekResult.Accepted, seeking.await())

        fixture.connection.emit(
            packet(presentationTimeUs = 9_000_000L, frameType = MuxFrameType.P),
        )
        fixture.connection.emit(packet(presentationTimeUs = 9_040_000L))
        fixture.connection.emit(
            packet(presentationTimeUs = 9_080_000L, frameType = MuxFrameType.P),
        )
        runCurrent()

        assertEquals(
            listOf(1_000L, 41_000L, 81_000L),
            fixture.presentationTimes,
            "The resumed segment continues the delivered timeline, and the pre-seek packet the " +
                "gate discarded never defined the floor",
        )
        val diagnostics = fixture.subscription.diagnostics.value
        assertEquals(1L, diagnostics.timestampAnchorCount)
        assertEquals(1L, diagnostics.rebaseDiscardedPacketCount)
        fixture.close()
    }

    @Test
    fun `returning to live rebases the acknowledged forward jump`() = runTest {
        val fixture = openRebasing()
        fixture.connection.emit(packet(presentationTimeUs = 1_000L, durationUs = 40_000L))
        runCurrent()

        val seeking = async { fixture.subscription.seek(SubscriptionSeekTarget.Live) }
        runCurrent()
        fixture.connection.emit(skipped(SkipOutcome.ACCEPTED))
        runCurrent()
        assertSame(SubscriptionSeekResult.Accepted, seeking.await())

        fixture.connection.emit(packet(presentationTimeUs = 600_000_000L))
        fixture.connection.emit(
            packet(presentationTimeUs = 600_040_000L, frameType = MuxFrameType.P),
        )
        runCurrent()

        assertEquals(
            listOf(1_000L, 41_000L, 81_000L),
            fixture.presentationTimes,
            "A ten-minute jump to the live edge must not stall the delivered timeline",
        )
        assertEquals(1L, fixture.subscription.diagnostics.value.timestampAnchorCount)
        fixture.close()
    }

    @Test
    fun `a rejected seek replays withheld packets with unchanged timestamps`() = runTest {
        val fixture = openRebasing()
        fixture.connection.emit(packet(presentationTimeUs = 1_000L, durationUs = 40_000L))
        runCurrent()

        val seeking = async {
            fixture.subscription.seek(SubscriptionSeekTarget.Relative((-10).seconds))
        }
        runCurrent()
        fixture.connection.emit(
            packet(presentationTimeUs = 41_000L, frameType = MuxFrameType.P),
        )
        fixture.connection.emit(skipped(SkipOutcome.REJECTED))
        runCurrent()
        assertSame(SubscriptionSeekResult.Rejected, seeking.await())

        fixture.connection.emit(
            packet(presentationTimeUs = 81_000L, frameType = MuxFrameType.P),
        )
        runCurrent()

        assertEquals(listOf(1_000L, 41_000L, 81_000L), fixture.presentationTimes)
        val diagnostics = fixture.subscription.diagnostics.value
        assertEquals(0L, diagnostics.timestampAnchorCount)
        assertEquals(0L, diagnostics.rebaseDiscardedPacketCount)
        fixture.close()
    }

    @Test
    fun `an unsolicited accepted skip rebases the resumed segment`() = runTest {
        val fixture = openRebasing()
        fixture.connection.emit(packet(presentationTimeUs = 1_000L, durationUs = 40_000L))
        fixture.connection.emit(skipped(SkipOutcome.ACCEPTED))
        fixture.connection.emit(
            packet(presentationTimeUs = 5_000_000L, frameType = MuxFrameType.P),
        )
        fixture.connection.emit(packet(presentationTimeUs = 5_040_000L))
        runCurrent()

        assertTrue(
            fixture.connection.seekTargets.isEmpty(),
            "This discontinuity was not requested by the SDK",
        )
        assertEquals(listOf(1_000L, 41_000L), fixture.presentationTimes)
        assertEquals(1L, fixture.subscription.diagnostics.value.timestampAnchorCount)
        fixture.close()
    }

    @Test
    fun `two accepted seeks re-anchor the delivered timeline twice`() = runTest {
        val fixture = openRebasing()
        fixture.connection.emit(packet(presentationTimeUs = 1_000L, durationUs = 40_000L))
        runCurrent()
        acceptSeek(fixture, SubscriptionSeekTarget.Absolute(30.seconds))
        fixture.connection.emit(packet(presentationTimeUs = 9_040_000L, durationUs = 40_000L))
        runCurrent()

        acceptSeek(fixture, SubscriptionSeekTarget.Absolute(60.seconds))
        fixture.connection.emit(packet(presentationTimeUs = 20_000_000L, durationUs = 40_000L))
        runCurrent()

        assertEquals(listOf(1_000L, 41_000L, 81_000L), fixture.presentationTimes)
        assertEquals(2L, fixture.subscription.diagnostics.value.timestampAnchorCount)
        fixture.close()
    }

    @Test
    fun `a replayed packet re-anchors a discontinuity armed before the request`() = runTest {
        val fixture = openRebasing()
        fixture.connection.emit(packet(presentationTimeUs = 1_000L, durationUs = 40_000L))
        fixture.connection.emit(skipped(SkipOutcome.ACCEPTED))
        runCurrent()
        assertEquals(listOf(1_000L), fixture.presentationTimes)

        val seeking = async {
            fixture.subscription.seek(SubscriptionSeekTarget.Absolute(30.seconds))
        }
        runCurrent()
        fixture.connection.emit(
            packet(presentationTimeUs = 7_000_000L, frameType = MuxFrameType.P),
        )
        fixture.connection.emit(packet(presentationTimeUs = 7_040_000L))
        fixture.connection.emit(skipped(SkipOutcome.REJECTED))
        runCurrent()

        assertSame(SubscriptionSeekResult.Rejected, seeking.await())
        assertEquals(
            listOf(1_000L, 41_000L),
            fixture.presentationTimes,
            "A rejected replay still resolves the earlier discontinuity in committed order",
        )
        val diagnostics = fixture.subscription.diagnostics.value
        assertEquals(1L, diagnostics.timestampAnchorCount)
        assertEquals(1L, diagnostics.rebaseDiscardedPacketCount)
        fixture.close()
    }

    @Test
    fun `a rejected replay continues the offset an earlier accepted seek established`() = runTest {
        val fixture = openRebasing()
        fixture.connection.emit(packet(presentationTimeUs = 1_000L, durationUs = 40_000L))
        runCurrent()
        acceptSeek(fixture, SubscriptionSeekTarget.Absolute(30.seconds))
        fixture.connection.emit(packet(presentationTimeUs = 9_000_000L, durationUs = 40_000L))
        runCurrent()
        assertEquals(listOf(1_000L, 41_000L), fixture.presentationTimes)

        val seeking = async {
            fixture.subscription.seek(SubscriptionSeekTarget.Relative((-10).seconds))
        }
        runCurrent()
        fixture.connection.emit(
            packet(presentationTimeUs = 9_040_000L, frameType = MuxFrameType.P),
        )
        fixture.connection.emit(skipped(SkipOutcome.REJECTED))
        runCurrent()
        assertSame(SubscriptionSeekResult.Rejected, seeking.await())

        assertEquals(
            listOf(1_000L, 41_000L, 81_000L),
            fixture.presentationTimes,
            "A replayed pre-seek packet must continue the already rebased timeline",
        )
        assertEquals(1L, fixture.subscription.diagnostics.value.timestampAnchorCount)
        fixture.close()
    }

    @Test
    fun `the anchor budget bounds the discarded prefix of a live subscription`() = runTest {
        val fixture = openRebasing(rebase = TimestampRebaseSettings(anchorPacketBudget = 2))
        fixture.connection.emit(packet(presentationTimeUs = 1_000L, durationUs = 1_000L))
        runCurrent()

        val seeking = async {
            fixture.subscription.seek(SubscriptionSeekTarget.Absolute(30.seconds))
        }
        runCurrent()
        fixture.connection.emit(skipped(SkipOutcome.ACCEPTED))
        runCurrent()
        assertSame(SubscriptionSeekResult.Accepted, seeking.await())

        repeat(3) { index ->
            fixture.connection.emit(
                packet(
                    presentationTimeUs = 500_000L + index,
                    frameType = MuxFrameType.UNKNOWN,
                ),
            )
        }
        runCurrent()

        assertEquals(listOf(1_000L, 2_000L), fixture.presentationTimes)
        assertEquals(2L, fixture.subscription.diagnostics.value.rebaseDiscardedPacketCount)
        fixture.close()
    }

    @Test
    fun `an unanchorable resumed segment ends the subscription instead of stalling`() = runTest {
        val fixture = openRebasing(
            rebase = TimestampRebaseSettings(anchorPacketBudget = 1, anchorDiscardLimit = 2),
        )
        fixture.connection.emit(packet(presentationTimeUs = 1_000L, durationUs = 1_000L))
        runCurrent()
        acceptSeek(fixture, SubscriptionSeekTarget.Absolute(30.seconds))

        repeat(2) { fixture.connection.emit(packet(presentationTimeUs = null)) }
        runCurrent()

        assertEquals(
            listOf(1_000L),
            fixture.presentationTimes,
            "No packet of an unanchorable segment may reach the consumer",
        )
        val terminal = fixture.subscription.state.value as SubscriptionState.Terminal
        val reason = terminal.reason as SubscriptionTerminalReason.SeekInvalidated
        assertEquals(SubscriptionSeekInvalidation.RESUMED_SEGMENT_UNANCHORABLE, reason.cause)
        assertEquals(2L, fixture.subscription.diagnostics.value.rebaseDiscardedPacketCount)
        fixture.manager.closeAndJoin()
    }

    private suspend fun TestScope.openRebasing(
        rebase: TimestampRebaseSettings = TimestampRebaseSettings(),
    ): RebaseFixture {
        val connection = RecordingSubscriptionConnection()
        connection.subscribeAction = { successfulConfirmation(timeshiftPeriodSeconds = 300L) }
        val manager = createSubscriptionManager(
            connection,
            StandardTestDispatcher(testScheduler),
            SeekGateSettings(),
            rebase,
        )
        manager.startAdmission()
        val presentationTimes = ArrayList<Long?>()
        val opened = async {
            manager.open(
                SubscriptionChannelId(1L),
                SubscriptionEventConsumer { event ->
                    if (event is SubscriptionEvent.Packet) {
                        presentationTimes += event.presentationTimeUs
                    }
                },
                300.seconds,
            )
        }
        runCurrent()
        connection.emit(started(stream()))
        runCurrent()
        val subscription = (opened.await() as SubscriptionOpenResult.Opened).subscription
        return RebaseFixture(connection, manager, subscription, presentationTimes)
    }
}

private class RebaseFixture(
    internal val connection: RecordingSubscriptionConnection,
    internal val manager: SubscriptionManager,
    internal val subscription: ActiveSubscription,
    internal val presentationTimes: List<Long?>,
) {
    internal suspend fun close() {
        subscription.close()
        manager.closeAndJoin()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.acceptSeek(
    fixture: RebaseFixture,
    target: SubscriptionSeekTarget,
) {
    val seeking = async { fixture.subscription.seek(target) }
    runCurrent()
    fixture.connection.emit(skipped(SkipOutcome.ACCEPTED))
    runCurrent()
    assertSame(SubscriptionSeekResult.Accepted, seeking.await())
}

private fun rebaser(
    settings: TimestampRebaseSettings = TimestampRebaseSettings(),
    tracks: List<SubscriptionStream>? = listOf(stream()),
): SubscriptionTimestampRebaser = SubscriptionTimestampRebaser(settings).apply {
    tracks?.let { streams -> onTracks(SubscriptionTracks(streams)) }
}

private fun SubscriptionTimestampRebaser.deliver(event: SubscriptionEvent): SubscriptionEvent? =
    when (val decision = classify(event)) {
        RebaseDecision.Discard, RebaseDecision.Unanchorable -> null
        is RebaseDecision.Deliver -> decision.event
    }

private fun SubscriptionTimestampRebaser.deliverPacket(
    packet: SubscriptionEvent.Packet,
): SubscriptionEvent.Packet? = deliver(packet) as SubscriptionEvent.Packet?

private fun audioAt(presentationTimeUs: Long): SubscriptionEvent.Packet = packet(
    presentationTimeUs = presentationTimeUs,
    frameType = MuxFrameType.UNKNOWN,
    streamIndex = 1L,
)
