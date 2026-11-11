package androidx.media3.extractor.mkv;

import androidx.media3.common.util.UnstableApi;

/** Access to Media3's maintained EBML varint decoding without duplicating its codec. */
@UnstableApi
public final class GrowingMatroskaVarint {
  private GrowingMatroskaVarint() {}

  public static int length(int firstByte) {
    return VarintReader.parseUnsignedVarintLength(firstByte);
  }

  public static long value(byte[] bytes, boolean removeLengthMask) {
    return VarintReader.assembleVarint(bytes, bytes.length, removeLengthMask);
  }
}
