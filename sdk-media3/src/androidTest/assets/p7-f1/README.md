# P7-F1 Synthetic MPEG-2 MPEG-TS Fixture

`pass-through.ts` is a deterministic 24-second MPEG transport stream generated
from FFmpeg's `testsrc2` video and `sine` audio filters. It contains no broadcast,
server, endpoint, credential, path, recording identifier, or user-provided data.

Generation used Debian FFmpeg `7.1.5-0+deb13u1`:

```text
ffmpeg -fflags +bitexact -f lavfi -i testsrc2=size=640x360:rate=25 \
  -f lavfi -i sine=frequency=660:sample_rate=48000 -t 24 \
  -map 0:v:0 -map 1:a:0 -c:v mpeg2video -pix_fmt yuv420p \
  -g 50 -bf 2 -sc_threshold 0 -b:v 1000k -maxrate:v 1000k \
  -bufsize:v 1835k -flags:v +bitexact -c:a mp2 -b:a 128k \
  -flags:a +bitexact -metadata service_name="TVHeadend SDK synthetic fixture" \
  -metadata service_provider=tvheadend-sdk -mpegts_flags +resend_headers \
  -muxdelay 0 -muxpreload 0 -f mpegts pass-through.ts
```

Two independent invocations produced identical bytes. `ffprobe` identifies one
640x360 MPEG-2 video stream and one MPEG-2 audio stream, and the file ends on a
188-byte packet boundary.

| File | Size | SHA-256 |
|---|---:|---|
| `pass-through.ts` | 3,722,776 | `ac5450c47d40b34277e3c304392f2476273717c9fcf8c91b78252702052a2447` |
