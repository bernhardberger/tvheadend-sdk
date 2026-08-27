# Changelog

## [0.2.0]

This provisional feature release adds generation-bound discovery and selection
of TVHeadend stream profiles for live playback. Live targets can request a
server timeshift period, and the Media3 coordinator now exposes app-safe
timeshift state plus signed seek, bounded return-live, and server pause and
resume controls without taking ownership of ordinary player controls.

Live playback also reports the current target's canonical TVHeadend
subscription issue through a safe enum. Unknown or localized values map to
`UNKNOWN`; raw server text and stale target state are not exposed.

On Android, `TvheadendServerProfileStore` atomically persists one normalized
endpoint and anonymous or password authentication. Password fields are
encrypted with endpoint-bound associated data and are returned only through an
opaque connectable profile. The deprecated credential store remains binary
compatible over the same record.

This remains a major-zero release. Source, binary, and behavioral compatibility
are provisional, and local or CI verification does not establish Maven Central
availability.

## [0.1.2]

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

This recovery aligns the tracked Maven OpenPGP public key and approved
fingerprint with the protected release environment. It does not change SDK
runtime behavior or API.

The signed `v0.1.0` and `v0.1.1` tags are retained as historical evidence after
their workflows stopped before signed-bundle retention or publication. No
`0.1.0` or `0.1.1` Maven artifacts or GitHub releases were created.

This is a major-zero baseline. It does not promise source, binary, or behavioral
compatibility or support. Publication and availability are independently
verified external state and are not established by this changelog entry.
