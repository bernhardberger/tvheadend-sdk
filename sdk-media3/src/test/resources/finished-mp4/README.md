# Finished fragmented MP4

`synthetic.mp4` contains six seconds of generated test video and a generated
440 Hz tone. It contains no broadcast material. FFmpeg 7.1 generated it with:

```sh
ffmpeg -hide_banner -loglevel error -fflags +bitexact \
  -f lavfi -i testsrc2=size=160x120:rate=25 \
  -f lavfi -i sine=frequency=440:sample_rate=48000 \
  -t 6 -c:v libx264 -g 25 -bf 2 -pix_fmt yuv420p \
  -c:a mp2 -b:a 64k -map_metadata -1 \
  -flags:v +bitexact -flags:a +bitexact -bitexact \
  -f mp4 -frag_duration 1 -ism_lookahead 0 -y synthetic.mp4
```

A second generation on the same host was byte-identical: 205,742 bytes,
SHA-256 `00469872cc119c283685b5f2a82b6ebc5cadf8d12dd4c4a7984ff605cb8921e1`.
Other FFmpeg or libx264 versions need not produce identical bytes.

Like Tvheadend's libav MP4 output, this has an initial `moov` with `mvex`,
short header durations, frame-sized fragments, and a final `mfra` whose entries
are not restricted to video keyframes. Video presentation runs from 80 ms to
6.08 s. The initial video keyframe is in the first `mdat`, before the fragments.

Tests patch Tvheadend's unsigned negative `tkhd`/`mdhd` durations in memory;
FFmpeg alone does not reproduce those values. Another in-memory patch replaces
one keyframe fragment's audio `traf` with an equal-size `free` box, preserving
all sample offsets. It reproduces the stale-track restart failure without a
second fixture or a generator script.
