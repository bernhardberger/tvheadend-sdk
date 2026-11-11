# Synthetic growing Matroska

`synthetic.mkv` contains generated test patterns and a generated sine wave, not broadcast media.
It is about 400 kB and contains H.264 video, MP2 audio, and one-second keyframe Clusters.
FFmpeg's CRC-32-first Cluster layout is retained. A focused header case also exercises
Tvheadend's built-in Timecode-first layout without CRC-32.

Generate from the repository root (FFmpeg 6.1, libx264):

```sh
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i testsrc2=size=160x90:rate=25 \
  -f lavfi -i sine=frequency=440:sample_rate=48000 -t 12 \
  -c:v libx264 -preset ultrafast -crf 30 -g 25 -keyint_min 25 \
  -sc_threshold 0 -bf 0 -c:a mp2 -b:a 64k \
  -cluster_time_limit 1000 -cluster_size_limit 10485760 -bitexact -y original.mkv
```

The offline byte patch keeps offsets and complete Clusters unchanged, replaces SeekHead and
Duration with equal-length Void elements, and makes the Segment size unknown. FFmpeg's final
Cues stay after the last Cluster; growing tests expose prefixes without those Cues. This is
smaller and preserves real encoded samples better than a handwritten Matroska fixture writer.
FFmpeg cannot both suppress header finalization and retain final Cues in one output, so the
patch is applied offline to element boundaries only. Runtime EBML varints use Media3's
maintained VarintReader through the package bridge.

FFmpeg 6.1 writing to `-f matroska pipe:1` does use an unknown Segment size, but still
writes Duration and a populated SeekHead. Adding `-live 1` removes Duration and final Cues,
but still populates SeekHead with Info/Tracks/Tags entries. Neither command alone produces
this fixture's empty SeekHead reservation plus final appended Cues; the transformation remains
necessary for that shape. The 10 MiB size limit avoids FFmpeg's zero-limit per-packet Clusters.
