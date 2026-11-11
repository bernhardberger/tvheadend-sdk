@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util
import androidx.media3.container.Mp4Box
import androidx.media3.extractor.GaplessInfoHolder
import androidx.media3.extractor.mp4.BoxParser
import androidx.media3.extractor.mp4.Track
import java.math.BigInteger

internal const val MP4_PROBE_BYTES: Int = 16 * 1024 * 1024
internal const val MP4_FRAGMENT_BYTES: Int = 64 * 1024

/** Shares fragment metadata between finished-file probing and timestamp seeking. */
internal data class RecordingMp4Track(
    val track: Track,
    val duration: Long,
    val flags: Int,
    val restartTrackIds: Set<Int>,
    val hasUnsignedNegativeDuration: Boolean,
) {
    fun timeUs(value: Long): Long = Util.scaleLargeTimestamp(value, C.MICROS_PER_SECOND, track.timescale)
}

internal data class RecordingMp4Fragment(
    val position: Long,
    val endPosition: Long,
    val timeUs: Long,
    val endTimeUs: Long,
    val sync: Boolean,
    val restartSafe: Boolean,
)

/** Uses Media3's box model and track parser; fragment field parsers are private in Media3. */
internal object RecordingMp4Fragments {
    fun track(moovBytes: ByteArray): RecordingMp4Track? {
        val moov = tree(moovBytes, 0, moovBytes.size) ?: return null
        val mvex = moov.getContainerBoxOfType(Mp4Box.TYPE_mvex) ?: return null
        val tables = BoxParser.parseTraks(moov, GaplessInfoHolder(), C.TIME_UNSET, null, false, false, { it }, false)
        val primary = (tables.firstOrNull { it.track.type == C.TRACK_TYPE_VIDEO }
            ?: tables.firstOrNull { it.track.type == C.TRACK_TYPE_AUDIO })?.track ?: return null
        val trex = mvex.leafChildren.firstOrNull {
            it.type == Mp4Box.TYPE_trex && it.data.limit() >= 32 && it.data.apply { position = 12 }.readInt() == primary.id
        }?.data ?: return null
        trex.position = 20
        val duration = trex.readUnsignedInt()
        trex.skipBytes(4)
        val trak = moov.containerChildren.firstOrNull {
            it.type == Mp4Box.TYPE_trak && it.getLeafBoxOfType(Mp4Box.TYPE_tkhd)?.data?.let { data ->
                data.position = 8
                val version = BoxParser.parseFullBoxVersion(data.readInt())
                data.position = if (version == 1) 28 else 20
                data.readInt() == primary.id
            } == true
        }
        return RecordingMp4Track(
            primary, duration, trex.readInt(), tables.filter { it.sampleCount > 0 }.map { it.track.id }.toSet(),
            corruptDuration(trak, primary),
        )
    }

    private fun corruptDuration(trak: Mp4Box.ContainerBox?, track: Track): Boolean {
        // ISO/IEC 14496-12 TrackHeaderBox: v0 duration at 28; v1 at 36, after track_ID/reserved.
        val movie = headerDuration(trak?.getLeafBoxOfType(Mp4Box.TYPE_tkhd)?.data, 28, 36) ?: return false
        // MediaHeaderBox: creation/modification then timescale, hence v0 at 24 and v1 at 32.
        val media = headerDuration(
            trak?.getContainerBoxOfType(Mp4Box.TYPE_mdia)?.getLeafBoxOfType(Mp4Box.TYPE_mdhd)?.data, 24, 32,
        ) ?: return movie.negative
        if (!movie.negative && !media.negative) return false
        // Media3's scaler cannot represent unsigned 64-bit inputs. JDK arithmetic also avoids
        // overflow while comparing the two independently scaled header durations.
        val micros = BigInteger.valueOf(C.MICROS_PER_SECOND)
        val movieUs = movie.unsigned.multiply(micros).divide(BigInteger.valueOf(track.movieTimescale))
        val mediaUs = media.unsigned.multiply(micros).divide(BigInteger.valueOf(track.timescale))
        return movieUs.subtract(mediaUs).abs() > micros
    }

    private data class HeaderDuration(val unsigned: BigInteger, val negative: Boolean)

    private fun headerDuration(data: ParsableByteArray?, shortPosition: Int, longPosition: Int): HeaderDuration? {
        if (data == null) return null
        data.position = 8
        val version = BoxParser.parseFullBoxVersion(data.readInt())
        data.position = if (version == 1) longPosition else shortPosition
        val signed = if (version == 1) data.readLong() else data.readInt().toLong()
        val unsigned = if (version == 1) signed else signed and 0xffffffffL
        return HeaderDuration(BigInteger(java.lang.Long.toUnsignedString(unsigned)), signed < 0)
    }

    fun find(bytes: ByteArray, base: Long, fileSize: Long, track: RecordingMp4Track, syncOnly: Boolean, from: Int = 0): RecordingMp4Fragment? {
        var offset = from
        val data = ParsableByteArray(bytes)
        while (offset <= bytes.size - 16) {
            data.position = offset + 4
            if (data.readInt() != Mp4Box.TYPE_moof) {
                offset++
                continue
            }
            val box = header(bytes, offset, bytes.size)
            if (box == null) {
                offset++
                continue
            }
            if (box.size > MP4_FRAGMENT_BYTES || box.end > bytes.size || box.end + 8 > bytes.size) {
                offset++
                continue
            }
            val mdat = header(bytes, box.end, bytes.size)
            if (mdat == null || mdat.type != Mp4Box.TYPE_mdat || base + mdat.end > fileSize) {
                offset++
                continue
            }
            val fragment = fragment(bytes, offset, box.end, base, track)
            if (fragment != null && (!syncOnly || fragment.sync && fragment.restartSafe)) {
                return fragment.copy(endPosition = base + mdat.end)
            }
            offset = minOf(mdat.end, bytes.size)
        }
        return null
    }

    fun lastEndTime(bytes: ByteArray, base: Long, fileSize: Long, track: RecordingMp4Track): Long? {
        var offset = 0
        var end: Long? = null
        while (offset < bytes.size) {
            val fragment = find(bytes, base, fileSize, track, false, offset) ?: break
            end = maxOf(end ?: 0, fragment.endTimeUs)
            offset = (fragment.endPosition - base).toInt()
        }
        return end
    }

    private fun fragment(bytes: ByteArray, start: Int, end: Int, base: Long, track: RecordingMp4Track): RecordingMp4Fragment? {
        return try {
            val moof = tree(bytes, start, end) ?: return null
            // seek() restores each nonempty moov sample table until this moof supplies that track's traf.
            val presentTrackIds = moof.containerChildren.filter { it.type == Mp4Box.TYPE_traf }.mapNotNull {
                it.getLeafBoxOfType(Mp4Box.TYPE_tfhd)?.data?.apply { position = 12 }?.readInt()
            }.toSet()
            val traf = moof.containerChildren.firstOrNull { container ->
                container.type == Mp4Box.TYPE_traf && container.getLeafBoxOfType(Mp4Box.TYPE_tfhd)?.data?.let {
                    it.position = 12
                    it.readInt() == track.track.id
                } == true
            } ?: return null
            val tfhd = requireNotNull(traf.getLeafBoxOfType(Mp4Box.TYPE_tfhd)).data
            tfhd.position = 8
            val flags = BoxParser.parseFullBoxFlags(tfhd.readInt())
            tfhd.skipBytes(4)
            if (flags and 1 != 0) tfhd.skipBytes(8)
            if (flags and 2 != 0) tfhd.skipBytes(4)
            val defaultDuration = if (flags and 8 != 0) tfhd.readUnsignedInt() else track.duration
            if (flags and 16 != 0) tfhd.skipBytes(4)
            val defaultFlags = if (flags and 32 != 0) tfhd.readInt() else track.flags
            val tfdt = traf.getLeafBoxOfType(Mp4Box.TYPE_tfdt)?.data ?: return null
            tfdt.position = 8
            val version = BoxParser.parseFullBoxVersion(tfdt.readInt())
            var decode = if (version == 1) tfdt.readUnsignedLongToLong() else tfdt.readUnsignedInt()
            var firstTime: Long? = null
            var firstFlags = defaultFlags
            var lastEnd = 0L
            // Mirrors FragmentedMp4Extractor.isEdtsListDurationForEntireMediaTimeline (Media3 1.11.1).
            val mediaTimes = track.track.editListMediaTimes
            val durations = track.track.editListDurations
            val edit = if (mediaTimes != null && durations?.length() == 1 &&
                (durations[0] == 0L || Util.scaleLargeTimestamp(durations[0], C.MICROS_PER_SECOND, track.track.movieTimescale) +
                    track.timeUs(mediaTimes[0]) >= track.track.durationUs)
            ) mediaTimes[0] else 0
            for (run in traf.leafChildren.filter { it.type == Mp4Box.TYPE_trun }) {
                val data = run.data
                data.position = 8
                val runFlags = BoxParser.parseFullBoxFlags(data.readInt())
                val samples = data.readUnsignedInt()
                if (samples > 100_000) return null
                if (runFlags and 1 != 0) data.skipBytes(4)
                val runFirstFlags = if (runFlags and 4 != 0) data.readInt() else defaultFlags
                repeat(samples.toInt()) { index ->
                    val duration = if (runFlags and 0x100 != 0) data.readUnsignedInt() else defaultDuration
                    if (runFlags and 0x200 != 0) data.skipBytes(4)
                    val sampleFlags = if (runFlags and 0x400 != 0) data.readInt() else if (index == 0) runFirstFlags else defaultFlags
                    val composition = if (runFlags and 0x800 != 0) data.readInt().toLong() else 0
                    if (duration <= 0 || decode > Long.MAX_VALUE - duration - Int.MAX_VALUE) return null
                    val presentation = decode + composition - edit
                    if (firstTime == null) {
                        firstTime = track.timeUs(presentation)
                        firstFlags = sampleFlags
                    }
                    lastEnd = maxOf(lastEnd, track.timeUs(presentation + duration))
                    decode += duration
                }
            }
            RecordingMp4Fragment(
                base + start, base + end, firstTime ?: return null, lastEnd,
                MimeTypes.allSamplesAreSyncSamples(track.track.format.sampleMimeType, track.track.format.codecs) ||
                    firstFlags and 0x10000 == 0,
                presentTrackIds.containsAll(track.restartTrackIds),
            )
        } catch (_: IndexOutOfBoundsException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun tree(bytes: ByteArray, start: Int, limit: Int, depth: Int = 0): Mp4Box.ContainerBox? {
        if (depth > 8) return null
        val box = header(bytes, start, limit) ?: return null
        if (box.end > limit) return null
        val container = Mp4Box.ContainerBox(box.type, box.end.toLong())
        var offset = start + box.headerSize
        while (offset < box.end) {
            val child = header(bytes, offset, box.end) ?: return null
            if (child.end > box.end) return null
            if (child.type in containers) container.add(tree(bytes, offset, child.end, depth + 1) ?: return null)
            else container.add(Mp4Box.LeafBox(child.type, ParsableByteArray(bytes.copyOfRange(offset, child.end))))
            offset = child.end
        }
        return container
    }

    internal fun header(bytes: ByteArray, start: Int, limit: Int): Header? {
        if (start < 0 || limit - start < 8) return null
        val data = ParsableByteArray(bytes, limit)
        data.position = start
        var size = data.readUnsignedInt()
        val type = data.readInt()
        val headerSize = if (size == 1L) 16 else 8
        if (size == 1L) {
            if (limit - start < 16) return null
            size = data.readLong()
        }
        if (size < headerSize || size > Int.MAX_VALUE - start) return null
        return Header(type, size.toInt(), start + size.toInt(), headerSize)
    }

    internal data class Header(val type: Int, val size: Int, val end: Int, val headerSize: Int)

    private val containers = setOf(
        Mp4Box.TYPE_moov, Mp4Box.TYPE_trak, Mp4Box.TYPE_mdia, Mp4Box.TYPE_minf, Mp4Box.TYPE_stbl,
        Mp4Box.TYPE_edts, Mp4Box.TYPE_mvex, Mp4Box.TYPE_moof, Mp4Box.TYPE_traf,
    )
}
