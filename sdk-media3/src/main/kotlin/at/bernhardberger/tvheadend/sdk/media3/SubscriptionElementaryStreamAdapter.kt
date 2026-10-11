@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.NalUnitUtil
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader
import at.bernhardberger.tvheadend.sdk.playback.MuxFrameType
import at.bernhardberger.tvheadend.sdk.playback.SkipOutcome
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent

internal class SubscriptionElementaryStreamAdapter(
    private val reader: ElementaryStreamReader,
    output: ExtractorOutput,
    firstTrackId: Int,
    private val onDiscontinuity: (() -> Unit)? = null,
    private val payloadAllocator: (Int) -> ByteArray = ::ByteArray,
) {
    private var ended = false
    private var payloadBuffer = EMPTY_PAYLOAD
    private val input = ParsableByteArray()

    init {
        reader.createTracks(output, TsPayloadReader.TrackIdGenerator(firstTrackId, 1))
    }

    fun accept(event: SubscriptionEvent) {
        check(!ended) { "Elementary stream is already terminal" }
        when (event) {
            is SubscriptionEvent.Packet -> consume(event)
            is SubscriptionEvent.Skipped -> if (event.outcome == SkipOutcome.ACCEPTED) resetForDiscontinuity()
            is SubscriptionEvent.Dropped -> resetForDiscontinuity()
            is SubscriptionEvent.Stopped,
            is SubscriptionEvent.Terminated,
            -> end()
            is SubscriptionEvent.Started,
            is SubscriptionEvent.Status,
            is SubscriptionEvent.Grace,
            is SubscriptionEvent.Speed,
            is SubscriptionEvent.Timeshift,
            is SubscriptionEvent.Queue,
            is SubscriptionEvent.Signal,
            is SubscriptionEvent.Descramble,
            -> Unit
        }
    }

    fun end() {
        if (ended) return
        ended = true
        try {
            reader.endOfInputReached()
        } finally {
            payloadBuffer = EMPTY_PAYLOAD
        }
    }

    fun acceptFiniteIdr(packet: SubscriptionEvent.Packet): Boolean {
        check(!ended)
        return consume(packet, finiteInput = true)
    }

    private fun resetForDiscontinuity() {
        payloadBuffer = EMPTY_PAYLOAD
        reader.seek()
        onDiscontinuity?.invoke()
    }

    private fun consume(packet: SubscriptionEvent.Packet, finiteInput: Boolean = false): Boolean {
        val size = packet.payload.size
        val bytes = if (size <= payloadBuffer.size) payloadBuffer else payloadAllocator(size).also {
            check(it.size == size) { "Subscription payload allocation size was invalid" }
            // Bound idle retention per reader; unusually large packets use temporary storage.
            if (size <= MAX_RETAINED_PAYLOAD_BYTES) payloadBuffer = it
        }
        check(packet.payload.copyInto(bytes) == size) { "Subscription payload copy was incomplete" }
        val flags = TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR or
            if (packet.frameType == MuxFrameType.I) {
                TsPayloadReader.FLAG_RANDOM_ACCESS_INDICATOR
            } else {
                0
            }
        // Readers consume synchronously, just as they do with Media3's reusable TS input.
        // Always reset the logical limit: a shorter packet must not expose stale trailing bytes.
        input.reset(bytes, size)
        try {
            reader.packetStarted(packet.presentationTimeUs ?: C.TIME_UNSET, flags)
            reader.consume(input)
            reader.packetFinished()
        } finally {
            // In particular, do not retain oversized temporary arrays through this wrapper.
            input.reset(EMPTY_PAYLOAD)
        }
        if (!finiteInput) return false
        // The first picture must start at an IDR. Later dependent pictures are valid input
        // for that decoder state and may share the same finite packet (including field pairs).
        var offset = 0
        var idr = false
        val prefixFlags = BooleanArray(3)
        while (offset < size) {
            val start = NalUnitUtil.findNalUnit(bytes, offset, size, prefixFlags)
            if (start + 3 >= size) break
            when (NalUnitUtil.getNalUnitType(bytes, start)) {
                NalUnitUtil.H264_NAL_UNIT_TYPE_IDR -> {
                    idr = true
                    break
                }
                NalUnitUtil.H264_NAL_UNIT_TYPE_NON_IDR -> return false
            }
            offset = start + 4
        }
        if (idr) reader.endOfInputReached()
        return idr
    }

    private companion object {
        val EMPTY_PAYLOAD = ByteArray(0)
        const val MAX_RETAINED_PAYLOAD_BYTES = 256 * 1024
    }
}
