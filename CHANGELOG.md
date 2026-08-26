# Changelog

## [0.1.0]

Initial provisional release of the independently maintained TVHeadend SDK. It
provides five focused libraries for HTSP-backed session and metadata workflows,
subscription and timeshift state machines, Android integration, Media3
playback, and reusable test fixtures.

The release includes generation-scoped lifecycle and cancellation, typed EPG
and DVR operations, authenticated artwork, bounded recording-progress policy,
and direct live and recording playback coordination. Growing pass-through
MPEG-TS playback is intentionally limited to stable recording identity and
validated MPEG-2, H.264, or HEVC seek paths; other growing containers and
unvalidated codecs fail closed.

The Media3 module includes the corresponding FFmpeg 6.0 source archive for its
optional GPL decoder fallback. The source, binary, and redistribution evidence
is recorded in `docs/ffmpeg-contingency.md`.

This is a major-zero baseline. It does not promise source, binary, or behavioral
compatibility or support. Publication and availability are independently
verified external state and are not established by this changelog entry.
