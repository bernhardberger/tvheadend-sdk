# Synthetic TS fixture with an audio description track

`two-latm-audio.ts` is a deterministic 3-second MPEG transport stream generated
from FFmpeg's `testsrc2` video and `sine` audio filters. It contains no
broadcast, server, endpoint, credential, path, recording identifier, or
user-provided data.

It carries H.264 video on PID 0x101, main LATM AAC audio on PID 0x102, and a
second LATM AAC stream on PID 0x103 marked as visual-impaired and dependent, as
broadcasters announce audio description. `RecordingFormatlessTrackTest` drops
every PID 0x103 packet in memory, which leaves a program map that announces an
audio description stream that never carries data.

Generation used Debian FFmpeg `7.1.5-0+deb13u1`:

```text
ffmpeg -fflags +bitexact -f lavfi -i testsrc2=size=160x90:rate=25 \
  -f lavfi -i sine=frequency=440:sample_rate=48000 \
  -f lavfi -i sine=frequency=880:sample_rate=48000 -t 3 \
  -map 0:v:0 -map 1:a:0 -map 2:a:0 -c:v libx264 -profile:v baseline -g 25 \
  -pix_fmt yuv420p -b:v 64k -flags:v +bitexact -c:a aac -ac 1 -b:a 32k \
  -flags:a +bitexact -metadata:s:a:0 language=eng -metadata:s:a:1 language=eng \
  -disposition:a:1 visual_impaired+dependent \
  -streamid 0:0x101 -streamid 1:0x102 -streamid 2:0x103 \
  -metadata service_name="TVHeadend SDK synthetic fixture" \
  -metadata service_provider=tvheadend-sdk -mpegts_flags +latm \
  -muxdelay 0 -muxpreload 0 -f mpegts two-latm-audio.ts
```

Two independent invocations produced identical bytes.

| File | Size | SHA-256 |
|---|---:|---|
| `two-latm-audio.ts` | 97,196 | `e2592f9475fd19887ccb62b62ff5fb0c97840a267ff11f5fe777b745dbfbd19a` |
