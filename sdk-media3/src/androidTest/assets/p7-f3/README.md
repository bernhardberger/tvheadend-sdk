# P7-F3 Synthetic H.264 MPEG-TS Fixture

`h264-synthetic.ts` is a deterministic 24-second MPEG transport stream generated
from FFmpeg's `testsrc2` video and `sine` audio filters. It contains no broadcast,
server, endpoint, credential, path, recording identifier, or user-provided data.
The fixture complements the P7-F1 synthetic MPEG-2 pass-through fixture.

Generation used Debian FFmpeg `7.1.5-0+deb13u1`:

```text
ffmpeg -f lavfi -i testsrc2=size=640x360:rate=25 \
  -f lavfi -i sine=frequency=880:sample_rate=48000 -t 24 \
  -c:v libx264 -preset medium -pix_fmt yuv420p -profile:v high \
  -level:v 3.0 -g 50 -keyint_min 50 -sc_threshold 0 -b:v 800k \
  -maxrate 800k -bufsize 1600k -c:a mp2 -b:a 128k \
  -mpegts_flags +resend_headers -f mpegts h264-synthetic.ts
```

`ffprobe` identifies one 640x360 H.264 video stream and one MPEG-2 audio stream.

| File | Size | SHA-256 |
|---|---:|---|
| `h264-synthetic.ts` | 3,060,264 | `46381f4fd260a7fefccddca432223e41bd04ab4d32c1406bbedc67d97227d950` |
