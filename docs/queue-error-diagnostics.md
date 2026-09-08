# Queue error diagnostics

SDK 0.11.0 carries `errorCount: Long?` from HTSP queue status through the
existing `SubscriptionEvent.Queue` to `LiveQueueDiagnostics`. `null` is unknown
or absent, not zero. Present values retain the full unsigned-u32 range
0..4294967295, including values above signed `Int.MAX_VALUE`.

The value is the server's cumulative data-error count, not queue depth, a
B/P/I frame-drop count, or `clientDroppedPacketCount`. A later absent queue
observation replaces the prior error count with unknown. Restart and terminal
events clear diagnostics as before. Equality includes the error count so an
error-only update is observable. All existing diagnostic rendering stays
redacted; no raw server text or subscription identifier is exposed.

## Pins and compatibility

HTSP dependency: `at.bernhardberger.tvheadend:htsp:0.10.0`, public tag
`v0.10.0`, source `a32af6157a3c29fe6e54fa732eb32927664dbea9`.
Producer P36-H1 verified all signed Central members and GitHub assets:

- [Public Maven coordinate](https://repo1.maven.org/maven2/at/bernhardberger/tvheadend/htsp/0.10.0/)
- [Release](https://github.com/bernhardberger/tvheadend-htsp/releases/tag/v0.10.0)
- JAR SHA-256: `7e62397d5af399dec4436e58afc98a0c6a340e37578a29fcf85b8fbbb416ad7f`
- Sources JAR SHA-256: `f4a2b3b3ce3a09cb9d268f7d1d98a712773fd6755300fbdce58af71ab33cc13b`
- Release manifest SHA-256: `bfa2f116907bdf72106c543921204c95014da94434d41bd8b62790dc9f0a7ec3`

Upstream TVHeadend pins `27295c5a48f2c575678bb224014cb9a26a773083` and
`9a6f78d37c1db9ca68df62512efb410a207885ec` share `src/htsp_server.c` blob
`2837efd3b41ae0ba7f82de2853d8a1d4a1ea88e1`: lines 4159-4160 accumulate
packet errors; lines 4225-4226 emit optional u32 `errors`.

Recompile all five SDK modules and downstream consumers together against
0.11.0. The SDK queue-event constructor changes its JVM signature; Kotlin's
default `null` is source convenience, not binary compatibility. Java callers
pass the new nullable `Long`. HTSP's `HtspQueueStatusMessage` constructor and
generated `copy` signatures also change. The new SDK minor version reflects
this known 0.x ABI break. No compatibility bridge or app UI is introduced.

The repository consumer contract compiles access to the new public diagnostic
getter. This is representative compile-time evidence, not deployed application
adoption. Application pin updates and recompilation remain centrally owned;
no live device or server verification is required or claimed here.

Media3 and FFmpeg dependency/native payloads and corresponding-source provenance
are unchanged by this release; the staged/public release checks verify them.
SDK public availability is established only by completed release verification,
not by this source declaration.
