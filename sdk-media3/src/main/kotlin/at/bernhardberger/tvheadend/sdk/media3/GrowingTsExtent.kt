@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.ts.TsUtil
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.MAX_RECORDING_READ_BYTES
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A finite, packet-aligned extent of the same continuity-bound file as playback. */
internal data class GrowingTsExtent(
    val sizeBytes: Long,
    val firstPcr: Long,
    val pcrPid: Int,
    val durationUs: Long,
    val isFinal: Boolean = false,
)

// Media3's default PCR search window, rounded to complete TS packets.
internal const val GROWING_TS_SEARCH_BYTES: Int = 600 * GROWING_TS_PACKET_BYTES

/** Reads only the head and tail, never scans the recording or waits for future content. */
internal class GrowingTsExtentProbe(private val lease: GrowingRecordingFileLease) {
    private var reader: GrowingRecordingFileReader? = null
    private var origin: Pair<Int, Long>? = null
    private var previous: GrowingTsExtent? = null

    suspend fun sample(): GrowingTsExtent? {
        val result = try {
            withTimeoutOrNull(5_000L) {
                if (!lease.isCurrent) return@withTimeoutOrNull null
                val active = reader ?: (lease.open(0L) as? RecordingFileResult.Ok)?.value
                    ?.also { reader = it } ?: return@withTimeoutOrNull null
                val size = (active.refreshSize() as? RecordingFileResult.Ok)?.value
                    ?.let { it - it % GROWING_TS_PACKET_BYTES }
                    ?.takeIf { it >= GROWING_TS_PACKET_BYTES * 5L }
                    ?: return@withTimeoutOrNull null
                // Reads may discover a later final stat. Only this refresh's size/finality
                // pair describes the byte window about to be measured.
                val sampledFinal = active.isFinal
                val old = previous
                if (old != null && size == old.sizeBytes) {
                    return@withTimeoutOrNull old.copy(isFinal = sampledFinal)
                        .takeIf { lease.isCurrent }
                }
                if (origin == null) {
                    if (active.seek(0L) !is RecordingFileResult.Ok) return@withTimeoutOrNull null
                    val head = readProbeBytes(active, minOf(size, GROWING_TS_SEARCH_BYTES.toLong()).toInt())
                        ?: return@withTimeoutOrNull null
                    val values = pcrValues(head)
                    if (values.map { it.first }.distinct().size != 1) return@withTimeoutOrNull null
                    origin = values.first()
                }
                val tailSize = minOf(size, GROWING_TS_SEARCH_BYTES.toLong()).toInt()
                if (active.seek(size - tailSize) !is RecordingFileResult.Ok) return@withTimeoutOrNull null
                val tail = readProbeBytes(active, tailSize) ?: return@withTimeoutOrNull null
                if (!lease.isCurrent) return@withTimeoutOrNull null
                val (pid, firstPcr) = checkNotNull(origin)
                val lastPcr = pcrValues(tail).lastOrNull { it.first == pid }?.second
                    ?: return@withTimeoutOrNull null
                extentFromPcr(size, pid, firstPcr, lastPcr)?.copy(isFinal = sampledFinal)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null // Optional enrichment: never emit raw transport errors or kill playback.
        }
        if (result == null) close()
        else previous = result
        return result
    }

    suspend fun close() {
        val active = reader ?: return
        reader = null
        withContext(NonCancellable) {
            try {
                active.close()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* Best-effort transport cleanup. */ }
        }
    }
}

internal suspend fun probeGrowingTsExtent(lease: GrowingRecordingFileLease): GrowingTsExtent? {
    val probe = GrowingTsExtentProbe(lease)
    return try { probe.sample() } finally { probe.close() }
}

private suspend fun readProbeBytes(reader: GrowingRecordingFileReader, count: Int): ByteArray? {
    val bytes = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val requested = minOf(count - offset, MAX_RECORDING_READ_BYTES)
        val read = (reader.read(bytes, offset, requested) as? RecordingFileResult.Ok)?.value
            ?: return null
        if (read !in 1..requested) return null
        offset += read
    }
    return bytes
}

internal fun growingTsExtentFromPackets(size: Long, head: ByteArray, tail: ByteArray): GrowingTsExtent? {
    val firstValues = pcrValues(head)
    val pid = firstValues.map { it.first }.distinct().singleOrNull() ?: return null
    val firstPcr = firstValues.first().second
    val lastPcr = pcrValues(tail).lastOrNull { it.first == pid }?.second ?: return null
    return extentFromPcr(size, pid, firstPcr, lastPcr)
}

private fun extentFromPcr(size: Long, pid: Int, firstPcr: Long, lastPcr: Long): GrowingTsExtent? {
    if (lastPcr == firstPcr) return null
    // Media3 owns PCR decoding and wrap handling. Header inspection above only selects the PID.
    val adjuster = TimestampAdjuster(0L)
    val start = adjuster.adjustTsTimestamp(firstPcr)
    // Choose the nearest wrap, not a forced forward wrap: a backwards reset must not invent
    // a 26-hour duration. More than half a PCR cycle cannot be established by two endpoints.
    val end = adjuster.adjustTsTimestamp(lastPcr)
    val duration = (end - start).takeIf { it > 0L } ?: return null
    return GrowingTsExtent(size, firstPcr, pid, duration)
}

private fun pcrValues(bytes: ByteArray): List<Pair<Int, Long>> {
    val buffer = ParsableByteArray(bytes)
    return buildList {
        var offset = 0
        while (offset + GROWING_TS_PACKET_BYTES <= bytes.size) {
            if (TsUtil.isStartOfTsPacket(bytes, 0, bytes.size, offset)) {
                val pid = ((bytes[offset + 1].toInt() and 0x1f) shl 8) or
                    (bytes[offset + 2].toInt() and 0xff)
                val pcr = TsUtil.readPcrFromPacket(buffer, offset, pid)
                if (pcr != C.TIME_UNSET) add(pid to pcr)
            }
            offset += GROWING_TS_PACKET_BYTES
        }
    }
}
