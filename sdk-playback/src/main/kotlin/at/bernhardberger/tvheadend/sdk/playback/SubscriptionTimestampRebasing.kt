@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.playback

/**
 * Bounded post-seek timestamp policy kept internal and injectable.
 *
 * [anchorPacketBudget] bounds how many timed packets may be discarded while waiting for a video
 * keyframe, so a stream that never reports one cannot stall the timeline. [anchorDiscardLimit]
 * bounds the whole wait, including packets that carry no presentation time and therefore can
 * never define an offset; it must exceed [anchorPacketBudget] so the keyframe fallback always
 * resolves a timed stream first. [fallbackFrameGapUs] is used only when the packet that produced
 * the last delivered timestamp reported no usable duration.
 */
internal class TimestampRebaseSettings(
    internal val anchorPacketBudget: Int = DEFAULT_ANCHOR_PACKET_BUDGET,
    internal val anchorDiscardLimit: Int = DEFAULT_ANCHOR_DISCARD_LIMIT,
    internal val fallbackFrameGapUs: Long = DEFAULT_FALLBACK_FRAME_GAP_US,
) {
    init {
        require(anchorPacketBudget > 0) { "Anchor packet budget must be positive" }
        require(anchorDiscardLimit > anchorPacketBudget) {
            "Anchor discard limit must exceed the anchor packet budget"
        }
        require(fallbackFrameGapUs > 0L) { "Fallback frame gap must be positive" }
    }
}

private const val DEFAULT_ANCHOR_PACKET_BUDGET = 256

/**
 * Four keyframe budgets, reached only by packets that carry no presentation time at all.
 *
 * A timed stream always anchors after at most [DEFAULT_ANCHOR_PACKET_BUDGET] discarded packets,
 * so this limit is unreachable while the server still timestamps the resumed segment.
 */
private const val DEFAULT_ANCHOR_DISCARD_LIMIT = 1024

/** One frame at 25 Hz, used only when a packet reports no duration of its own. */
private const val DEFAULT_FALLBACK_FRAME_GAP_US = 40_000L

/** Classification of one committed event against the shared post-seek timestamp offset. */
internal sealed interface RebaseDecision {
    /** Deliver [event], which is the original instance unless its timestamps moved. */
    class Deliver(
        internal val event: SubscriptionEvent,
        internal val anchored: Boolean,
    ) : RebaseDecision

    /** Discard the packet: it precedes the re-anchor point or sits at or below the floor. */
    data object Discard : RebaseDecision

    /**
     * The resumed segment cannot be anchored, so the subscription must end instead of stalling.
     *
     * Discarding without bound would leave a subscription reporting a healthy playable state
     * while no media ever reaches the consumer again.
     */
    data object Unanchorable : RebaseDecision
}

/**
 * Applies one shared timestamp offset to every track of a single subscription.
 *
 * A server-side seek resumes with recording timestamps that can be lower than timestamps already
 * handed to the readers. An accepted [SubscriptionEvent.Skipped] therefore arms a discontinuity;
 * the next usable video keyframe re-anchors the timeline one frame gap past the last delivered
 * timestamp, and one offset is then applied to the presentation and decoding timestamps of every
 * track so their relative positions survive unchanged.
 *
 * Only a packet that carries a presentation time can define the shared offset. Substituting a
 * decoding timestamp would offset the timeline by the reorder delay of the stream, which is
 * exactly the silent drift this class exists to prevent, so a resumed segment that never presents
 * a timed packet is reported as [RebaseDecision.Unanchorable] once
 * [TimestampRebaseSettings.anchorDiscardLimit] packets have been discarded.
 *
 * Instances are confined to the subscription's delivery mutex and hold no synchronization of
 * their own.
 */
internal class SubscriptionTimestampRebaser(private val settings: TimestampRebaseSettings) {
    private var offsetUs = 0L
    private var lastOutputUs: Long? = null

    /** Duration reported by whichever track's packet produced [lastOutputUs]. */
    private var lastOutputDurationUs = 0L
    private var floorUs: Long? = null
    private var awaitingAnchor = false

    /** Timed packets discarded since the discontinuity, bounding the keyframe wait. */
    private var discardedSinceDiscontinuity = 0

    /** Every packet discarded since the discontinuity, bounding the wait itself. */
    private var unanchoredPackets = 0
    private var anchorCandidates: Set<StreamIndex>? = null

    /** Records the committed track set so only a stream that may carry video can anchor. */
    internal fun onTracks(tracks: SubscriptionTracks) {
        anchorCandidates = tracks.streams
            .filter { stream -> stream.type.mayCarryVideo() }
            .map(SubscriptionStream::index)
            .toSet()
    }

    /** Classifies one committed event in delivery order. */
    internal fun classify(event: SubscriptionEvent): RebaseDecision = when (event) {
        is SubscriptionEvent.Packet -> classifyPacket(event)
        is SubscriptionEvent.Skipped -> {
            if (event.outcome == SkipOutcome.ACCEPTED) armDiscontinuity()
            RebaseDecision.Deliver(event, anchored = false)
        }
        // Every control event keeps its committed position; only packets carry media time.
        else -> RebaseDecision.Deliver(event, anchored = false)
    }

    private fun armDiscontinuity() {
        awaitingAnchor = true
        discardedSinceDiscontinuity = 0
        unanchoredPackets = 0
    }

    private fun classifyPacket(packet: SubscriptionEvent.Packet): RebaseDecision {
        var anchored = false
        if (awaitingAnchor) {
            val anchorUs = packet.presentationTimeUs
            // A packet with no presentation time cannot define an offset, so it never consumes
            // the pending discontinuity.
            if (anchorUs == null || !canAnchor(packet)) {
                return discardAwaitingAnchor(timed = anchorUs != null)
            }
            reanchor(anchorUs)
            anchored = true
        }
        return deliver(packet, anchored)
    }

    /**
     * Counts one packet discarded while the timeline waits for an anchor.
     *
     * Only a packet that could have anchored advances the keyframe budget: letting an untimed
     * packet advance it would release the mandated keyframe wait on the strength of packets that
     * carry no timing information at all. Untimed packets therefore bound only the wait itself,
     * which ends the subscription rather than discarding for the rest of the session.
     */
    private fun discardAwaitingAnchor(timed: Boolean): RebaseDecision {
        if (timed) discardedSinceDiscontinuity += 1
        unanchoredPackets += 1
        return if (unanchoredPackets >= settings.anchorDiscardLimit) {
            RebaseDecision.Unanchorable
        } else {
            RebaseDecision.Discard
        }
    }

    /**
     * Reports whether [packet] may end the anchor wait.
     *
     * Committed tracks that contain no stream able to carry video have no keyframe to wait for,
     * so their first timed packet anchors immediately instead of discarding a bounded prefix that
     * could never contain one. Otherwise an I frame on a possible video stream anchors, and the
     * packet budget releases streams that never report a frame type. Before a track set is
     * committed the stream classification is unknown, so any I frame may anchor.
     *
     * An unclassified stream stays a candidate on purpose. TVHeadend reports a frame type only
     * for video packets, so an I frame on a stream this SDK cannot classify is positive evidence
     * that the stream carries video, and waiting for it is the accurate reading of the resumed
     * segment. A stream that is not video simply never reports one and the budget releases it.
     */
    private fun canAnchor(packet: SubscriptionEvent.Packet): Boolean {
        val candidates = anchorCandidates
        if (candidates != null && candidates.isEmpty()) return true
        if (discardedSinceDiscontinuity >= settings.anchorPacketBudget) return true
        return packet.frameType == MuxFrameType.I &&
            (candidates == null || packet.streamIndex in candidates)
    }

    /**
     * Moves the shared offset so [anchorUs] is presented one frame gap past the last output.
     *
     * The gap comes from the packet that produced the last delivered timestamp, on whichever
     * track produced it, so a mux whose audio leads video resumes one audio frame past that
     * audio rather than one video frame past the video. Nothing is rebased before the first
     * delivered timestamp, because there is no earlier output that a resumed recording position
     * could contradict.
     */
    private fun reanchor(anchorUs: Long) {
        awaitingAnchor = false
        discardedSinceDiscontinuity = 0
        unanchoredPackets = 0
        val previousOutputUs = lastOutputUs ?: return
        val frameGapUs = lastOutputDurationUs.takeIf { duration -> duration > 0L }
            ?: settings.fallbackFrameGapUs
        offsetUs = saturatingSubtract(saturatingAdd(previousOutputUs, frameGapUs), anchorUs)
        floorUs = previousOutputUs
    }

    private fun deliver(packet: SubscriptionEvent.Packet, anchored: Boolean): RebaseDecision {
        val outputUs = packet.presentationTimeUs?.let { time -> saturatingAdd(time, offsetUs) }
        val floor = floorUs
        // Tracks interleaved before the anchor rebase below the timeline already handed over.
        if (outputUs != null && floor != null && outputUs <= floor) return RebaseDecision.Discard
        val previousOutputUs = lastOutputUs
        if (outputUs != null && (previousOutputUs == null || outputUs > previousOutputUs)) {
            lastOutputUs = outputUs
            lastOutputDurationUs = packet.durationUs
        }
        return RebaseDecision.Deliver(rebased(packet, outputUs), anchored)
    }

    private fun rebased(
        packet: SubscriptionEvent.Packet,
        outputUs: Long?,
    ): SubscriptionEvent.Packet = if (offsetUs == 0L) {
        packet
    } else {
        SubscriptionEvent.Packet(
            frameType = packet.frameType,
            streamIndex = packet.streamIndex,
            decodingTimeUs = packet.decodingTimeUs?.let { time -> saturatingAdd(time, offsetUs) },
            presentationTimeUs = outputUs,
            durationUs = packet.durationUs,
            payload = packet.payload,
            serverPresentationTimeUs = packet.serverPresentationTimeUs,
        )
    }
}

private fun SubscriptionStreamType.mayCarryVideo(): Boolean = when (this) {
    SubscriptionStreamType.MPEG2_VIDEO,
    SubscriptionStreamType.H264,
    SubscriptionStreamType.H265,
    SubscriptionStreamType.UNKNOWN,
    -> true
    SubscriptionStreamType.AAC,
    SubscriptionStreamType.AC3,
    SubscriptionStreamType.EAC3,
    SubscriptionStreamType.MPEG2_AUDIO,
    SubscriptionStreamType.DVB_SUBTITLE,
    SubscriptionStreamType.TEXT_SUBTITLE,
    SubscriptionStreamType.TELETEXT,
    -> false
}

/** Adds without wrapping, because a wrapped media timestamp would silently move backwards. */
private fun saturatingAdd(value: Long, addend: Long): Long = try {
    Math.addExact(value, addend)
} catch (_: ArithmeticException) {
    if (addend > 0L) Long.MAX_VALUE else Long.MIN_VALUE
}

private fun saturatingSubtract(value: Long, subtrahend: Long): Long = try {
    Math.subtractExact(value, subtrahend)
} catch (_: ArithmeticException) {
    if (subtrahend > 0L) Long.MIN_VALUE else Long.MAX_VALUE
}
