@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.extractor.mkv.GrowingMatroskaVarint
import androidx.media3.common.util.Util
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.MAX_RECORDING_READ_BYTES
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class GrowingMatroskaExtent(
    override val sizeBytes: Long,
    val firstClusterPosition: Long,
    val firstClusterEndPosition: Long,
    val lastClusterPosition: Long,
    val origin: Long,
    val timecodeScaleNs: Long,
    override val durationUs: Long,
    override val isFinal: Boolean,
) : GrowingRecordingExtent {
    fun timeUs(timecode: Long): Long = Util.scaleLargeTimestamp(timecode - origin, timecodeScaleNs, 1_000L)
}

internal const val MATROSKA_MAX_SEARCH_BYTES: Int = 32 * 1024 * 1024
private const val HEAD_BYTES = 1024 * 1024
private const val TAIL_BYTES = 256 * 1024
private const val CLUSTER_ID = 0x1f43b675L
private val SEGMENT_BOUNDARIES = setOf(CLUSTER_ID, 0x1c53bb6bL, 0x1043a770L, 0x1254c367L, 0xecL)

/** Samples finite head/tail windows; Media3 supplies EBML integer decoding. */
internal class GrowingMatroskaExtentProbe(private val lease: GrowingRecordingFileLease) :
    GrowingRecordingExtentProbe<GrowingMatroskaExtent> {
    private var reader: GrowingRecordingFileReader? = null
    private var head: MatroskaHead? = null
    private var previous: GrowingMatroskaExtent? = null
    private var lastCluster: MatroskaCluster? = null

    override suspend fun sample(): GrowingMatroskaExtent? {
        val result = try {
            withTimeoutOrNull(5_000L) { sampleWindow() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null // Optional duration evidence must not expose transport failures.
        }
        if (result == null) close() else previous = result
        return result
    }

    private suspend fun sampleWindow(): GrowingMatroskaExtent? {
        if (!lease.isCurrent) return null
        val active = reader ?: (lease.open(0L) as? RecordingFileResult.Ok)?.value
            ?.also { reader = it } ?: return null
        val size = (active.refreshSize() as? RecordingFileResult.Ok)?.value?.takeIf { it > 0L } ?: return null
        val sampledFinal = active.isFinal
        previous?.takeIf { it.sizeBytes == size }?.let {
            return it.copy(isFinal = sampledFinal).takeIf { lease.isCurrent }
        }
        val origin = head ?: readAt(active, 0L, minOf(size, HEAD_BYTES.toLong()).toInt())
            ?.let(::matroskaHead)?.also { head = it } ?: return null
        val tail = lastCluster?.let { walkForward(active, it, size) } ?: sampleTail(active, size) ?: return null
        lastCluster = tail
        val delta = tail.timecode - origin.cluster.timecode
        if (delta <= 0L || delta > Long.MAX_VALUE / origin.scaleNs) return null
        if (!lease.isCurrent) return null
        return GrowingMatroskaExtent(
            size, origin.cluster.position, origin.cluster.endPosition, tail.position, origin.cluster.timecode, origin.scaleNs,
            Util.scaleLargeTimestamp(delta, origin.scaleNs, 1_000L), sampledFinal,
        )
    }

    private suspend fun walkForward(
        active: GrowingRecordingFileReader,
        previousCluster: MatroskaCluster,
        size: Long,
    ): MatroskaCluster? {
        var cluster = previousCluster
        repeat(64) {
            if (cluster.endPosition >= size) return cluster
            val position = cluster.endPosition
            val bytes = readAt(active, position, minOf(size - position, 128L).toInt()) ?: return null
            val boundary = element(bytes, 0, bytes.size) ?: return null
            if (boundary.id in SEGMENT_BOUNDARIES && boundary.id != CLUSTER_ID) return cluster
            val next = findMatroskaCluster(bytes, position, size, 0, bytes.size) ?: return null
            if (next.position != position || next.timecode < cluster.timecode) return null
            cluster = next
        }
        return null // Long gaps or changed structure use the bounded bootstrap search.
    }

    private suspend fun sampleTail(active: GrowingRecordingFileReader, size: Long): MatroskaCluster? {
        var window = minOf(size, TAIL_BYTES.toLong()).toInt()
        var last: MatroskaCluster? = null
        while (last == null) {
            val start = size - window
            val bytes = readAt(active, start, window) ?: return null
            var offset = 0
            while (offset < bytes.size) {
                val cluster = findMatroskaCluster(bytes, start, size, offset, bytes.size) ?: break
                last = cluster
                // Skip the whole validated Cluster, not block payloads containing lookalike IDs.
                offset = (cluster.endPosition - start).coerceAtMost(bytes.size.toLong()).toInt()
            }
            if (last != null || window == minOf(size, MATROSKA_MAX_SEARCH_BYTES.toLong()).toInt()) break
            window = minOf(size, window * 2L, MATROSKA_MAX_SEARCH_BYTES.toLong()).toInt()
        }
        return last
    }

    override suspend fun close() {
        val active = reader ?: return
        reader = null
        withContext(NonCancellable) {
            try { active.close() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* Best-effort cleanup. */ }
        }
    }
}

private suspend fun readAt(reader: GrowingRecordingFileReader, position: Long, count: Int): ByteArray? {
    if (reader.seek(position) !is RecordingFileResult.Ok) return null
    val bytes = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val requested = minOf(count - offset, MAX_RECORDING_READ_BYTES)
        val read = (reader.read(bytes, offset, requested) as? RecordingFileResult.Ok)?.value ?: return null
        if (read !in 1..requested) return null
        offset += read
    }
    return bytes
}

internal data class MatroskaCluster(val position: Long, val endPosition: Long, val timecode: Long)
private data class MatroskaHead(val cluster: MatroskaCluster, val scaleNs: Long)
private data class EbmlElement(val id: Long, val data: Int, val size: Long) {
    val end: Long get() = data + size
}

private fun element(bytes: ByteArray, offset: Int, limit: Int): EbmlElement? {
    fun vint(at: Int, removeMask: Boolean, maxLength: Int): Pair<Long, Int>? {
        if (at !in 0 until limit) return null
        val length = GrowingMatroskaVarint.length(bytes[at].toInt() and 0xff)
        if (length !in 1..maxLength || at + length > limit) return null
        val value = GrowingMatroskaVarint.value(bytes.copyOfRange(at, at + length), removeMask)
        return value to length
    }
    val (id, idLength) = vint(offset, false, 4) ?: return null
    val (size, sizeLength) = vint(offset + idLength, true, 8) ?: return null
    val knownSize = if (size == (1L shl (sizeLength * 7)) - 1L) Long.MAX_VALUE else size
    return EbmlElement(id, offset + idLength + sizeLength, knownSize)
}

private fun unsigned(bytes: ByteArray, element: EbmlElement, limit: Int): Long? {
    if (element.size !in 1L..8L || element.end > limit) return null
    var value = 0L
    for (offset in element.data until element.end.toInt()) {
        if (value > Long.MAX_VALUE ushr 8) return null
        value = (value shl 8) or (bytes[offset].toLong() and 0xff)
    }
    return value
}

private fun matroskaHead(bytes: ByteArray): MatroskaHead? {
    val ebml = element(bytes, 0, bytes.size)?.takeIf { it.id == 0x1a45dfa3L } ?: return null
    if (ebml.end >= bytes.size) return null
    val segment = element(bytes, ebml.end.toInt(), bytes.size)?.takeIf { it.id == 0x18538067L } ?: return null
    var offset = segment.data
    var scale = 1_000_000L
    while (offset < bytes.size) {
        val child = element(bytes, offset, bytes.size) ?: return null
        if (child.id == CLUSTER_ID) {
            val timecode = clusterTimecode(bytes, child, bytes.size) ?: return null
            return MatroskaHead(MatroskaCluster(offset.toLong(), child.end, timecode), scale)
        }
        if (child.end > bytes.size || child.end <= offset) return null
        if (child.id == 0x1549a966L) {
            var infoOffset = child.data
            while (infoOffset < child.end) {
                val info = element(bytes, infoOffset, child.end.toInt()) ?: return null
                if (info.end > child.end || info.end <= infoOffset) return null
                if (info.id == 0x2ad7b1L) scale = unsigned(bytes, info, bytes.size)?.takeIf { it > 0L } ?: return null
                infoOffset = info.end.toInt()
            }
        }
        offset = child.end.toInt()
    }
    return null
}

internal fun findMatroskaCluster(
    bytes: ByteArray,
    basePosition: Long,
    sizeBytes: Long,
    from: Int,
    limit: Int,
): MatroskaCluster? {
    for (offset in from until (limit - 4).coerceAtLeast(from)) {
        if (bytes[offset] != 0x1f.toByte() || bytes[offset + 1] != 0x43.toByte() ||
            bytes[offset + 2] != 0xb6.toByte() || bytes[offset + 3] != 0x75.toByte()
        ) continue
        val cluster = element(bytes, offset, limit) ?: continue
        if (cluster.size <= 0L || cluster.size > MATROSKA_MAX_SEARCH_BYTES) continue
        val timecode = clusterTimecodeElement(bytes, cluster, limit) ?: continue
        val time = unsigned(bytes, timecode, limit) ?: continue
        val firstChild = element(bytes, timecode.end.toInt(), limit) ?: continue
        if (firstChild.end > cluster.end || firstChild.size <= 0L) continue
        // A plausible block header and track vint reject payload ID lookalikes without fetching
        // the Cluster payload. Check the following Segment boundary only when already buffered.
        when (firstChild.id) {
            0xa3L -> if (!validBlockTrack(bytes, firstChild, limit)) continue
            0xa0L -> {
                val block = element(bytes, firstChild.data, limit)?.takeIf { it.id == 0xa1L } ?: continue
                if (block.end > firstChild.end || !validBlockTrack(bytes, block, limit)) continue
            }
            0xa7L, 0xabL -> if (firstChild.size > 8L) continue
            0xecL -> Unit
            else -> continue
        }
        val end = basePosition + cluster.end
        if (end < sizeBytes) {
            if (cluster.end < limit) {
                val next = element(bytes, cluster.end.toInt(), limit)
                if (next != null && next.id !in SEGMENT_BOUNDARIES) continue
                if (next == null && bytes[cluster.end.toInt()] == 0.toByte()) continue
            }
        }
        return MatroskaCluster(basePosition + offset, end, time)
    }
    return null
}

private fun clusterTimecode(bytes: ByteArray, cluster: EbmlElement, limit: Int): Long? {
    return clusterTimecodeElement(bytes, cluster, limit)?.let { unsigned(bytes, it, limit) }
}

private fun clusterTimecodeElement(bytes: ByteArray, cluster: EbmlElement, limit: Int): EbmlElement? {
    var offset = cluster.data
    while (offset < minOf(cluster.end, limit.toLong())) {
        val child = element(bytes, offset, limit) ?: return null
        if (child.end > cluster.end || child.end > limit || child.end <= offset) return null
        when (child.id) {
            0xe7L -> return child.takeIf { it.size in 1L..8L }
            0xbfL -> if (child.size != 4L) return null
            0xecL -> Unit
            else -> return null
        }
        offset = child.end.toInt()
    }
    return null
}

private fun validBlockTrack(bytes: ByteArray, block: EbmlElement, limit: Int): Boolean {
    if (block.data >= limit) return false
    val length = GrowingMatroskaVarint.length(bytes[block.data].toInt() and 0xff)
    if (length !in 1..8 || block.data + length > limit || block.size < length + 3L) return false
    return GrowingMatroskaVarint.value(bytes.copyOfRange(block.data, block.data + length), true) > 0L
}
