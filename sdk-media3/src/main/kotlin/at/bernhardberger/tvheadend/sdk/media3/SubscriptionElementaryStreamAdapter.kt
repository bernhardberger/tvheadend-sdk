@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
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
    private val payloadAllocator: (Int) -> ByteArray = ::ByteArray,
) {
    private var ended = false

    init {
        reader.createTracks(output, TsPayloadReader.TrackIdGenerator(firstTrackId, 1))
    }

    fun accept(event: SubscriptionEvent) {
        check(!ended) { "Elementary stream is already terminal" }
        when (event) {
            is SubscriptionEvent.Packet -> consume(event)
            is SubscriptionEvent.Skipped -> if (event.outcome == SkipOutcome.ACCEPTED) reader.seek()
            is SubscriptionEvent.Dropped -> reader.seek()
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
        reader.endOfInputReached()
    }

    private fun consume(packet: SubscriptionEvent.Packet) {
        val bytes = payloadAllocator(packet.payload.size)
        check(bytes.size == packet.payload.size) { "Subscription payload allocation size was invalid" }
        check(packet.payload.copyInto(bytes) == bytes.size) { "Subscription payload copy was incomplete" }
        val flags = TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR or
            if (packet.frameType == MuxFrameType.I) {
                TsPayloadReader.FLAG_RANDOM_ACCESS_INDICATOR
            } else {
                0
            }
        reader.packetStarted(packet.presentationTimeUs ?: C.TIME_UNSET, flags)
        reader.consume(ParsableByteArray(bytes))
        reader.packetFinished()
    }
}
