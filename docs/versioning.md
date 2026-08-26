# Versioning and compatibility

The configured release candidate is `0.1.0` for these Maven coordinates:

- `at.bernhardberger.tvheadend:sdk-android:0.1.0`
- `at.bernhardberger.tvheadend:sdk-core:0.1.0`
- `at.bernhardberger.tvheadend:sdk-media3:0.1.0`
- `at.bernhardberger.tvheadend:sdk-playback:0.1.0`
- `at.bernhardberger.tvheadend:sdk-testing:0.1.0`

Local source, CI, and staged-publication checks do not establish that these
coordinates have been published or are available. Publication and availability
are independently verified external state for every release. Published release
bytes are immutable and must never be replaced.

## Provisional 0.x policy

While the major version is zero, the public API and behavior are provisional.
No source, binary, or behavioral compatibility is promised for the provisional
0.x line. A known breaking change requires the next minor version, not a patch
version. Patch versions are reserved for backward-compatible fixes.

Read the matching changelog section before selecting a release as a baseline.
Candidate checks do not establish distribution, runtime support, or release
readiness.
