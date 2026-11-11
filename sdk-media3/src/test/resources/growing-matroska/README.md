# Synthetic growing Matroska

`synthetic.mkv` contains generated test patterns and a generated sine wave, not broadcast media.
It is about 400 kB and contains H.264 video, MP2 audio, and one-second keyframe Clusters.
FFmpeg's CRC-32-first Cluster layout is retained. A focused header case also exercises
Tvheadend's built-in Timecode-first layout without CRC-32.

Generate from the repository root (FFmpeg 7.1, libx264; output verified byte-identical with 7.1.5):

```sh
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i testsrc2=size=160x90:rate=25 \
  -f lavfi -i sine=frequency=440:sample_rate=48000 -t 12 \
  -c:v libx264 -preset ultrafast -crf 30 -g 25 -keyint_min 25 \
  -sc_threshold 0 -bf 0 -c:a mp2 -b:a 64k \
  -cluster_time_limit 1000 -cluster_size_limit 10485760 -bitexact -y original.mkv
python3 sdk-media3/src/test/resources/growing-matroska/prepare.py original.mkv \
  sdk-media3/src/test/resources/growing-matroska/synthetic.mkv
```

`nonzero-origin.mkv` is a four-second, video-only variant (about 100 kB), generated
with the same encoder and post-processing. Its 280 ms timestamp offset makes
video samples start at 280 ms and keyframe Clusters start at 280, 1280, 2280,
and 3280 ms. These independently specified timestamps test Media3's absolute
timestamp domain rather than an origin-relative clock:

```sh
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i testsrc2=size=160x90:rate=25 -t 4 \
  -c:v libx264 -preset ultrafast -crf 30 -g 25 -keyint_min 25 \
  -sc_threshold 0 -bf 0 -an -output_ts_offset 0.28 \
  -cluster_time_limit 1000 -cluster_size_limit 10485760 -bitexact -y offset-original.mkv
python3 sdk-media3/src/test/resources/growing-matroska/prepare.py offset-original.mkv \
  sdk-media3/src/test/resources/growing-matroska/nonzero-origin.mkv
```

Both extraction tests share `growingMatroskaFixture` in `GrowingMatroskaExtentTest.kt`.
Malformed-header cases use fixed EBML byte strings: FFmpeg cannot emit those
invalid structures. The large-tail case pads a fixed SimpleBlock header in
memory rather than adding a large media asset.

The offline byte patch keeps offsets and complete Clusters unchanged, replaces SeekHead and
Duration with equal-length Void elements, and makes the Segment size unknown. FFmpeg's final
Cues stay after the last Cluster; growing tests expose prefixes without those Cues. This is
smaller and preserves real encoded samples better than a handwritten Matroska fixture writer.
FFmpeg cannot both suppress header finalization and retain final Cues in one output, so
`prepare.py` applies the patch offline and decodes only element boundaries. Runtime EBML varints use Media3's
maintained VarintReader through the package bridge.

FFmpeg 7.1 writing to `-f matroska pipe:1` does use an unknown Segment size, but still
writes Duration and a populated SeekHead. Adding `-live 1` removes Duration and final Cues,
but still populates SeekHead with Info/Tracks/Tags entries. Neither command alone produces
this fixture's empty SeekHead reservation plus final appended Cues; the transformation remains
necessary for that shape. The 10 MiB size limit avoids FFmpeg's zero-limit per-packet Clusters.
