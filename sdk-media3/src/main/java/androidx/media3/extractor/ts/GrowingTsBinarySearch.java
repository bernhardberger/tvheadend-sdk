package androidx.media3.extractor.ts;

import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.BinarySearchSeeker;

/** Access to Media3's maintained PCR binary seeker; no TS parsing is duplicated here. */
@UnstableApi
public final class GrowingTsBinarySearch {
  private GrowingTsBinarySearch() {}

  public static BinarySearchSeeker create(
      long firstPcr, long durationUs, long sizeBytes, int pcrPid, int searchBytes) {
    TimestampAdjuster adjuster = new TimestampAdjuster(0);
    adjuster.adjustTsTimestamp(firstPcr);
    return new TsBinarySearchSeeker(adjuster, durationUs, sizeBytes, pcrPid, searchBytes);
  }
}
