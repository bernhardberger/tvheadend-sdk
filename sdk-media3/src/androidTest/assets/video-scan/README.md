# Synthetic Scan-Type Inputs

These inputs were generated from FFmpeg's `testsrc2` source for the SDK's video
scan-type tests. They contain no captured broadcast content. Each `*-switch`
file holds three interlaced pictures followed by three progressive pictures, so
its sequence parameters change the scan type once inside one stream.

Generation used FFmpeg 7.1.5 with libx264, libx265 4.1 and the native MPEG-2
encoder on 2026-11-06. x265 output is not byte-identical across builds.

```sh
src='-f lavfi -i testsrc2=size=64x64:rate=25 -frames:v 3 -an -pix_fmt yuv420p'
ffmpeg $src -c:v libx264 -x264-params keyint=3:bframes=0 h264-progressive.h264
ffmpeg $src -c:v libx264 -flags +ildct+ilme -x264-params keyint=3:bframes=0 h264-interlaced.h264
ffmpeg $src -c:v libx265 -x265-params keyint=3:bframes=0 h265-progressive.h265
ffmpeg $src -c:v libx265 -x265-params keyint=3:bframes=0:interlace=tff h265-interlaced.h265
ffmpeg $src -c:v mpeg2video -g 3 -bf 0 mpeg2-progressive.m2v
ffmpeg $src -c:v mpeg2video -g 3 -bf 0 -flags +ildct+ilme mpeg2-interlaced.m2v
cat h264-interlaced.h264 h264-progressive.h264 > h264-switch.h264
cat h265-interlaced.h265 h265-progressive.h265 > h265-switch.h265
cat mpeg2-interlaced.m2v mpeg2-progressive.m2v > mpeg2-switch.m2v
ffmpeg -framerate 25 -f h264 -i h264-switch.h264 -c copy -f mpegts h264-switch.ts
ffmpeg -framerate 25 -f hevc -i h265-switch.h265 -c copy -f mpegts h265-switch.ts
ffmpeg -fflags +genpts -r 25 -f mpegvideo -i mpeg2-switch.m2v -c copy -f mpegts mpeg2-switch.ts
ffmpeg -framerate 25 -f h264 -i h264-interlaced.h264 -c copy h264-interlaced.mkv
ffmpeg -framerate 25 -f h264 -i h264-progressive.h264 -c copy h264-progressive.mkv
ffmpeg -fflags +genpts -framerate 25 -f h264 -i h264-switch.h264 -c copy h264-switch.mkv
ffmpeg -fflags +genpts -framerate 25 -f hevc -i h265-switch.h265 -c copy h265-switch.mkv
ffmpeg -fflags +genpts -r 25 -f mpegvideo -i mpeg2-switch.m2v -c copy mpeg2-switch.mkv
ffmpeg -i h264-interlaced.mkv -c copy h264-interlaced.mp4
ffmpeg -i h264-progressive.mkv -c copy h264-progressive.mp4
ffmpeg -fflags +genpts -framerate 25 -f h264 -i h264-switch.h264 -c copy -tag:v avc3 h264-switch.mp4
ffmpeg -fflags +genpts -framerate 25 -f hevc -i h265-switch.h265 -c copy -tag:v hev1 h265-switch.mp4
ffmpeg -fflags +genpts -framerate 25 -f h264 -i h264-switch.h264 -c copy -tag:v avc3 -movflags +frag_keyframe+empty_moov h264-switch-fragmented.mp4
ffmpeg -fflags +genpts -r 25 -f mpegvideo -i mpeg2-switch.m2v -c copy -f vob mpeg2-switch.mpg
```

FFmpeg `trace_headers` confirms the declarations the tests rely on:

| Input | Interlaced part | Progressive part |
| --- | --- | --- |
| H.264 | `frame_mbs_only_flag=0`, `mb_adaptive_frame_field_flag=1` | `frame_mbs_only_flag=1` |
| H.265 | `general_progressive_source_flag=0`, `general_interlaced_source_flag=1`, `field_seq_flag=1` | `general_progressive_source_flag=1`, `general_interlaced_source_flag=0` |
| MPEG-2 | `progressive_sequence=0` | `progressive_sequence=1` |

The single-scan H.264 MKV and MP4 files exercise container codec setup. The
switching MKV, MP4 (`avc3`/`hev1`), fragmented MP4 and MPEG-PS files retain the
in-band declarations. MPEG-2 MKV has no usable codec setup for scan inspection:
its initial format is `UNKNOWN`, then its first picture resolves to `INTERLACED`.

`VideoScanInspectionTest` also crafts bounded H.264/H.265 SPS, PPS and first-slice
prefixes in memory. SPS 0 declares interlaced and SPS 1 progressive; PPS 0 keeps
referencing SPS 0 until a picture activates PPS 1, which references SPS 1. These
prefixes test id activation, out-of-range ids, H.265 sub-layer PTL skipping,
oversize units and seek/discontinuity handling; they are not decodable pictures.
They also cover discarded SPS replacements in unfinished samples, one-byte input
fragments and first-chunk PPS activation before the track declares its format.
The test helper writes Exp-Golomb values, an RBSP stop bit and emulation-prevention
bytes because Media3 has no maintained public writer for these test prefixes.
Malformed-input tests replace the second SPS in the H.264/H.265 TS files with
garbage, truncated headers or an overlong Exp-Golomb prefix, leaving the first
valid parameter sets and picture samples in place.
