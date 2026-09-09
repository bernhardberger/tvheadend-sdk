# Synthetic Paused-Seek Inputs

These two H.264 packets were generated from FFmpeg's `testsrc2` source for SDK
regression tests. They contain no captured broadcast content.

`synthetic-open-gop.mp4` contains the complete two-second version of the same
synthetic input, muxed with `-movflags +faststart`. Instrumentation uses Media3's
MP4 extractor to obtain Annex-B samples, timestamps and synchronization flags;
no test-owned media parser or hand-edited encoded data is involved.

Generation used FFmpeg/libx264 on 2026-09-09:

```sh
ffmpeg -f lavfi -i 'testsrc2=size=320x180:rate=25' -t 2 -an -c:v libx264 \
  -pix_fmt yuv420p \
  -x264-params 'keyint=10:min-keyint=10:scenecut=0:open-gop=1:bframes=2:repeat-headers=1' \
  -f segment -segment_time 0.4 -segment_format h264 /tmp/opencode/direct-intra-%02d.h264
ffmpeg -i /tmp/opencode/direct-intra-00.h264 -frames:v 1 -c:v copy -f h264 synthetic-idr.h264
ffmpeg -i /tmp/opencode/direct-intra-01.h264 -frames:v 1 -c:v copy -f h264 synthetic-nonidr.h264
```

FFmpeg `trace_headers` confirms that `synthetic-nonidr.h264` contains SPS, PPS,
SEI and a type-1 non-IDR slice with `slice_type=7` (all I slices), not a type-5
IDR. The finite-input adapter test retains the IDR eligibility boundary. Queue
tests exercise single and multiple IDR samples. Playing and coordinator-owned
paused-seek instrumentation use the complete open-GOP stream to verify recovery
prefix preservation and continued decoding. A standalone non-IDR intra packet
is not sufficient evidence of paused-seek support; the coordinator obtains
normal stream input while retaining paused presentation intent.
