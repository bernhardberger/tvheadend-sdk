@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * Creates Media3's default TS readers with the audio type and language live playback reports.
 *
 * Media3 reads both from the ISO 639 language descriptor only. A broadcaster can mark its audio
 * description or clear audio solely in the DVB supplementary audio descriptor, which Tvheadend
 * reads for live subscriptions. A stream without a readable supplementary audio descriptor
 * reaches Media3's factory unchanged.
 */
internal class SupplementaryAudioPayloadReaderFactory : TsPayloadReader.Factory {
    private val delegate = DefaultTsPayloadReaderFactory()

    override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> = delegate.createInitialPayloadReaders()

    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? =
        delegate.createPayloadReader(streamType, esInfo.withSupplementaryAudio())
}

/*
 * Follows Tvheadend's dvb_psi_pmt.c, psi_desc_supplementary_audio and its caller: mix_type is
 * ignored, an unlisted editorial classification keeps the ISO 639 audio type, a present language
 * code replaces the ISO 639 one, and the last descriptor that carries a value wins.
 */
private fun TsPayloadReader.EsInfo.withSupplementaryAudio(): TsPayloadReader.EsInfo {
    var found = false
    var audioType = audioType
    var language = language
    var supplementaryLanguage: String? = null
    val data = ParsableByteArray(descriptorBytes)
    while (data.bytesLeft() >= DESCRIPTOR_HEADER_BYTES) {
        val tag = data.readUnsignedByte()
        val length = data.readUnsignedByte()
        if (length > data.bytesLeft()) break
        val next = data.position + length
        if (tag == DVB_EXTENSION_DESCRIPTOR && length >= 2 && data.readUnsignedByte() == SUPPLEMENTARY_AUDIO_EXTENSION) {
            found = true
            // mix_type(1), editorial_classification(5), reserved(1), language_code_present(1).
            val flags = data.readUnsignedByte()
            when (flags shr 2 and 0x1f) {
                EDITORIAL_AUDIO_DESCRIPTION, EDITORIAL_SPOKEN_SUBTITLES ->
                    audioType = TsPayloadReader.EsInfo.AUDIO_TYPE_VISUAL_IMPAIRED_COMMENTARY
                EDITORIAL_CLEAR_AUDIO -> audioType = TsPayloadReader.EsInfo.AUDIO_TYPE_HEARING_IMPAIRED
            }
            if (flags and 1 != 0 && length >= 2 + LANGUAGE_CODE_LENGTH) {
                supplementaryLanguage = data.readString(LANGUAGE_CODE_LENGTH, Charsets.ISO_8859_1)
            }
        } else if (tag == ISO_639_LANGUAGE_DESCRIPTOR && length >= LANGUAGE_CODE_LENGTH) {
            // Media3 trims the code, which Tvheadend reads as broadcast.
            language = data.readString(LANGUAGE_CODE_LENGTH, Charsets.ISO_8859_1)
        }
        data.position = next
    }
    if (!found) return this
    return TsPayloadReader.EsInfo(
        streamType,
        (supplementaryLanguage ?: language)?.let(::tvheadendLanguage),
        audioType,
        dvbSubtitleInfos,
        descriptorBytes,
    )
}

/**
 * The code Tvheadend's `lang_code_get2` reports for [code], which Media3 then normalizes.
 *
 * Media3 already folds the bibliographic, terminological and two-letter forms of a language
 * together. Tvheadend's table of known codes is not repeated here, so a well-formed code
 * missing from it is kept instead of becoming undetermined.
 */
private fun tvheadendLanguage(code: String): String {
    val letters = code.takeWhile { it !in "\u0000;,-" }.filter { it != ' ' }.lowercase()
    return when {
        letters.length < 2 || letters.any { it !in 'a'..'z' } -> C.LANGUAGE_UNDETERMINED
        letters.length == LANGUAGE_CODE_LENGTH && letters.startsWith('q') -> LOCAL_USE_LANGUAGE
        else -> letters
    }
}

private const val DESCRIPTOR_HEADER_BYTES = 2
private const val ISO_639_LANGUAGE_DESCRIPTOR = 0x0a

// ETSI EN 300 468: extension_descriptor and its supplementary_audio_descriptor.
private const val DVB_EXTENSION_DESCRIPTOR = 0x7f
private const val SUPPLEMENTARY_AUDIO_EXTENSION = 0x06
private const val EDITORIAL_AUDIO_DESCRIPTION = 1
private const val EDITORIAL_CLEAR_AUDIO = 2
private const val EDITORIAL_SPOKEN_SUBTITLES = 3
private const val LANGUAGE_CODE_LENGTH = 3

/** Tvheadend reports this first ISO 639-2 local-use code for every three-letter code starting with q. */
private const val LOCAL_USE_LANGUAGE = "qaa"
