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
    val trackNumbers: Set<Long>,
    val lastClusterTimecode: Long,
    override val durationUs: Long,
    override val isFinal: Boolean,
) : GrowingRecordingExtent {
    fun timeUs(timecode: Long): Long = checkNotNull(matroskaTimeUs(timecode, timecodeScaleNs))
}

internal const val MATROSKA_MAX_SEARCH_BYTES: Int = 16 * 1024 * 1024
private const val HEAD_BYTES = 1024 * 1024
private const val TAIL_BYTES = 256 * 1024
private const val CLUSTER_ID = 0x1f43b675L
private val SEGMENT_BOUNDARIES = setOf(CLUSTER_ID, 0x1c53bb6bL, 0x1043a770L, 0x1254c367L, 0xecL, 0x114d9b74L, 0x1941a469L)

private fun matroskaTimeUs(timecode: Long, scaleNs: Long): Long? {
    // Reject unrepresentable nanoseconds before Media3's multiply/divide can wrap.
    if (timecode < 0L || scaleNs <= 0L || timecode > Long.MAX_VALUE / scaleNs) return null
    return Util.scaleLargeTimestamp(timecode, scaleNs, 1_000L)
}

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
        val tail = lastCluster?.let { walkForward(active, it, size, origin) } ?: sampleTail(active, size, origin) ?: return null
        lastCluster = tail
        val durationUs = matroskaTimeUs(tail.timecode, origin.scaleNs)?.takeIf { it > 0L } ?: return null
        if (!lease.isCurrent) return null
        return GrowingMatroskaExtent(
            size, origin.cluster.position, origin.cluster.endPosition, tail.position, origin.cluster.timecode, origin.scaleNs,
            origin.trackNumbers, tail.timecode, durationUs, sampledFinal,
        )
    }

    private suspend fun walkForward(
        active: GrowingRecordingFileReader,
        previousCluster: MatroskaCluster,
        size: Long,
        head: MatroskaHead,
    ): MatroskaCluster? {
        var cluster = previousCluster
        repeat(64) {
            if (cluster.endPosition >= size) return cluster
            val position = cluster.endPosition
            val bytes = readAt(active, position, minOf(size - position, 128L).toInt()) ?: return null
            val boundary = element(bytes, 0, bytes.size) ?: return null
            if (boundary.id != CLUSTER_ID && validSegmentBoundary(bytes, 0, bytes.size, position, size)) return cluster
            val next = findMatroskaCluster(bytes, position, size, 0, bytes.size, head.trackNumbers, head.cluster.timecode, scaleNs = head.scaleNs) ?: return null
            if (next.position != position || next.timecode < cluster.timecode) return null
            cluster = next
        }
        return null // Long gaps or changed structure use the bounded bootstrap search.
    }

    private suspend fun sampleTail(active: GrowingRecordingFileReader, size: Long, head: MatroskaHead): MatroskaCluster? {
        var window = minOf(size, TAIL_BYTES.toLong()).toInt()
        var previousStart = size
        var seam = ByteArray(0)
        while (true) {
            val start = size - window
            // Fetch only newly exposed bytes, keeping enough seam for a split header.
            val bytes = readAt(active, start, (previousStart - start).toInt(), seam) ?: return null
            var last: MatroskaCluster? = null
            var offset = 0
            while (offset < bytes.size) {
                val cluster = findMatroskaCluster(bytes, start, size, offset, bytes.size, head.trackNumbers, head.cluster.timecode, scaleNs = head.scaleNs) ?: break
                if (cluster.endPosition < size && cluster.endPosition >= start + bytes.size) {
                    val boundary = readAt(active, cluster.endPosition, minOf(16L, size - cluster.endPosition).toInt()) ?: return null
                    if (!validSegmentBoundary(boundary, 0, boundary.size, cluster.endPosition, size)) {
                        offset = (cluster.position - start + 1L).toInt()
                        continue
                    }
                }
                if (last == null || cluster.timecode >= last.timecode) last = cluster
                // Skip the whole validated Cluster, not block payloads containing lookalike IDs.
                offset = (cluster.endPosition - start).coerceAtMost(bytes.size.toLong()).toInt()
            }
            if (last != null) return last
            if (window == minOf(size, MATROSKA_MAX_SEARCH_BYTES.toLong()).toInt()) return null
            seam = bytes.copyOfRange(0, minOf(256, bytes.size))
            previousStart = start
            window = minOf(size, window * 2L, MATROSKA_MAX_SEARCH_BYTES.toLong()).toInt()
        }
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

private suspend fun readAt(reader: GrowingRecordingFileReader, position: Long, count: Int, suffix: ByteArray = ByteArray(0)): ByteArray? {
    if (reader.seek(position) !is RecordingFileResult.Ok) return null
    val bytes = ByteArray(count + suffix.size)
    var offset = 0
    while (offset < count) {
        val requested = minOf(count - offset, MAX_RECORDING_READ_BYTES)
        val read = (reader.read(bytes, offset, requested) as? RecordingFileResult.Ok)?.value ?: return null
        if (read !in 1..requested) return null
        offset += read
    }
    suffix.copyInto(bytes, count)
    return bytes
}

internal data class MatroskaCluster(val position: Long, val endPosition: Long, val timecode: Long)
private data class MatroskaHead(val cluster: MatroskaCluster, val scaleNs: Long, val trackNumbers: Set<Long>)
private data class EbmlElement(val id: Long, val data: Int, val size: Long) {
    val end: Long get() = if (size == Long.MAX_VALUE) Long.MAX_VALUE else data + size
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
    if (knownSize == Long.MAX_VALUE && id != 0x18538067L) return null
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
    var tracks = emptySet<Long>()
    while (offset < bytes.size) {
        val child = element(bytes, offset, bytes.size) ?: return null
        if (child.id == CLUSTER_ID) {
            if (tracks.isEmpty()) return null
            val cluster = findMatroskaCluster(bytes, 0L, bytes.size.toLong(), offset, bytes.size, tracks, scaleNs = scale)
                ?.takeIf { it.position == offset.toLong() } ?: return null
            return MatroskaHead(cluster, scale, tracks)
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
        if (child.id == 0x1654ae6bL) tracks = trackNumbers(bytes, child) ?: return null
        offset = child.end.toInt()
    }
    return null
}

private fun trackNumbers(bytes: ByteArray, tracks: EbmlElement): Set<Long>? {
    val numbers = mutableSetOf<Long>()
    var offset = tracks.data
    while (offset < tracks.end) {
        val entry = element(bytes, offset, tracks.end.toInt()) ?: return null
        if (entry.end > tracks.end || entry.end <= offset) return null
        if (entry.id == 0xaeL) {
            var fieldOffset = entry.data
            while (fieldOffset < entry.end) {
                val field = element(bytes, fieldOffset, entry.end.toInt()) ?: return null
                if (field.end > entry.end || field.end <= fieldOffset) return null
                if (field.id == 0xd7L) numbers += unsigned(bytes, field, entry.end.toInt())?.takeIf { it > 0L } ?: return null
                fieldOffset = field.end.toInt()
            }
        }
        offset = entry.end.toInt()
    }
    return numbers
}

internal fun findMatroskaCluster(
    bytes: ByteArray,
    basePosition: Long,
    sizeBytes: Long,
    from: Int,
    limit: Int,
    trackNumbers: Set<Long>? = null,
    minimumTimecode: Long = 0L,
    maximumTimecode: Long = Long.MAX_VALUE,
    scaleNs: Long = 1_000_000L,
): MatroskaCluster? {
    for (offset in from until (limit - 4).coerceAtLeast(from)) {
        if (bytes[offset] != 0x1f.toByte() || bytes[offset + 1] != 0x43.toByte() ||
            bytes[offset + 2] != 0xb6.toByte() || bytes[offset + 3] != 0x75.toByte()
        ) continue
        val cluster = element(bytes, offset, limit) ?: continue
        if (cluster.size <= 0L || cluster.size > MATROSKA_MAX_SEARCH_BYTES) continue
        val timecode = clusterTimecodeElement(bytes, cluster, limit) ?: continue
        val time = unsigned(bytes, timecode, limit) ?: continue
        if (time !in minimumTimecode..maximumTimecode || matroskaTimeUs(time, scaleNs) == null) continue
        if (!validFirstBlock(bytes, timecode.end.toInt(), cluster.end, limit, trackNumbers)) continue
        val end = basePosition + cluster.end
        if (end < sizeBytes) {
            if (cluster.end < limit) {
                if (!validSegmentBoundary(bytes, cluster.end.toInt(), limit, basePosition, sizeBytes)) continue
            }
        }
        return MatroskaCluster(basePosition + offset, end, time)
    }
    return null
}

private fun validSegmentBoundary(bytes: ByteArray, offset: Int, limit: Int, basePosition: Long, size: Long): Boolean {
    val next = element(bytes, offset, limit) ?: return false
    if (next.id !in SEGMENT_BOUNDARIES) return false
    return if (next.id == CLUSTER_ID) next.size in 1L..MATROSKA_MAX_SEARCH_BYTES.toLong()
    else next.end <= size - basePosition
}

private fun validFirstBlock(bytes: ByteArray, start: Int, clusterEnd: Long, limit: Int, tracks: Set<Long>?): Boolean {
    var offset = start
    while (offset < minOf(clusterEnd, limit.toLong())) {
        val child = element(bytes, offset, limit) ?: return false
        if (child.end > clusterEnd || child.size <= 0L) return false
        when (child.id) {
            0xa3L -> return validBlockTrack(bytes, child, limit, tracks)
            0xa0L -> {
                val block = element(bytes, child.data, limit)?.takeIf { it.id == 0xa1L } ?: return false
                return block.end <= child.end && validBlockTrack(bytes, block, limit, tracks)
            }
            0xa7L, 0xabL -> if (child.size > 8L) return false
            0xecL -> Unit
            else -> return false
        }
        if (child.end > limit) return false
        offset = child.end.toInt()
    }
    return false
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

private fun validBlockTrack(bytes: ByteArray, block: EbmlElement, limit: Int, trackNumbers: Set<Long>?): Boolean {
    if (block.data >= limit) return false
    val length = GrowingMatroskaVarint.length(bytes[block.data].toInt() and 0xff)
    if (length !in 1..8 || block.data + length > limit || block.size < length + 3L) return false
    val number = GrowingMatroskaVarint.value(bytes.copyOfRange(block.data, block.data + length), true)
    return number > 0L && (trackNumbers == null || number in trackNumbers)
}
