# Versioning and compatibility

The source is configured for the provisional `0.6.1` release under these
coordinates:

- `at.bernhardberger.tvheadend:sdk-android:0.6.1`
- `at.bernhardberger.tvheadend:sdk-core:0.6.1`
- `at.bernhardberger.tvheadend:sdk-media3:0.6.1`
- `at.bernhardberger.tvheadend:sdk-playback:0.6.1`
- `at.bernhardberger.tvheadend:sdk-testing:0.6.1`

The `v0.6.0` tag marks the preceding release. Source, local staging, and CI
do not establish that the configured `0.6.1` coordinates are publicly available;
check Maven Central before selecting them.

The signed `v0.3.3` tag is retained as development-release evidence after its
Central deployment failed. No `0.3.3` Maven artifacts or GitHub release became
available.

The signed `v0.1.0` and `v0.1.1` tags stopped before publication and are retained
as historical evidence. They did not create Maven artifacts or GitHub releases.

Publication and availability are independently verified external state for
every release. Local source, CI, and staged-publication checks do not establish
that a new coordinate is available. Published release bytes are immutable and
must never be replaced.

## Provisional 0.x policy

While the major version is zero, the public API and behavior are provisional.
No source, binary, or behavioral compatibility is promised for the provisional
0.x line. A known breaking change requires the next minor version, not a patch
version. Patch versions are reserved for backward-compatible fixes. The
configured `0.6.1` patch fixes the missing public consumer-testing route for
`0.6.0` timeshift contracts without changing existing public signatures or
production coordinate semantics.

The entire `1.x` line is reserved for a separately attended final stable
release. Development packages must not select, recommend, tag, publish, or
release a `1.x.x` version.

Read the matching changelog section before selecting a release as a baseline.
Candidate checks do not establish distribution, runtime support, or release
readiness.
