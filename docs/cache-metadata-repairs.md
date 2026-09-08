# Cache And Metadata Repairs

The 2026-09-08 audit was static. These repairs target SDK-08, SDK-09, PERF-03
and the hot observation paths in PERF-01. They do not establish a measured TV
speedup, frame-time improvement or physical playback acceptance.

## Cache Safety

SDK-08 replaces ambiguous colon concatenation with the already-used maintained
kotlinx-serialization protobuf list serializer and JDK SHA-256. The `v2-` prefix
separates new metadata, artwork and derived artwork memory keys from all legacy
unversioned namespaces. There is no fallback or migration of ambiguous identity.
Restore deletes recognized legacy namespaces, including metadata and artwork,
without interpreting their contents or following symbolic links. Current namespaces retain normal retention
and byte-budget pruning. Only the SDK-owned child of the supplied application
root is cleared. Passwords still do not participate.

`CacheNamespacesTest`, `FileMetadataCacheStoreTest` and `ArtworkCacheTest` cover
the audited IPv6/username tuple collision, separator-containing fields, distinct
metadata/artwork bytes, legacy isolation, and preservation of unrelated files,
including namespace-level and nested symlink targets.
Existing expiration, corruption, LRU, stale-generation and clear-epoch tests apply.

SDK-09 retires the old writer before attempting deletion and restores its
publication subscription in `finally`, while still holding the lifecycle mutex.
Cancellation propagates; deletion need not finish, but persistence must remain
available. `MetadataCacheRuntimeTest` suspends writer retirement and deletion
separately, cancels clear, then proves a later publication persists and an explicit
stop still retires the replacement. Clear does not flush bytes it would delete.

## Reconstruction And Lookups

PERF-03 caches `DvrReducer.snapshot()` until an accepted DVR mutation or reset.
Repeated EPG traffic therefore does not reconstruct entries and rules before the
batching decision. Known-channel updates retain the exact EPG snapshot; adding
channel membership still invalidates coverage. Channel-add query-authority
revisions remain independent of snapshot reuse.

`DvrReducerTest` and `EpgReducerTest` each exercise 1,000 unrelated updates with
snapshot identity assertions. `PhaseOneSessionMetadataTest` sends a 1,000-event
EPG burst followed by pointer updates and a real DVR mutation through the session
publication path. It protects deferred publication, changed content and immutable
historical snapshots. These identity checks establish avoided reconstruction,
not timing on an Android device.

PERF-01 uses two lazy indexes owned by each immutable `EpgSnapshot`, not by each
aggregate observation. The ID index preserves null for duplicate IDs. The channel
index preserves original list order, so overlapping-event selection and linked
Next precedence are unchanged. No index participates in equality, hashing or
generation authority; no process-global index retains obsolete snapshots.

`SessionObservationTest` compares selectors with scan-based reference results
over 20,000 events in 200 channels, with 100 candidates per channel. It also
protects duplicate ambiguity, linked/fallback Next, half-open boundaries,
relationship ambiguity and retained-versus-replacement isolation. Index storage
is linear in retained events. First use still builds an index in O(E); subsequent
event lookup is expected O(1), and Now/Next scans O(E-channel), not O(E).
Programme-to-DVR lookup is O(D) after event lookup and returns immediately for
absent/empty DVR. Reverse DVR-to-EPG, coverage and channel selectors remain scans;
they are not claimed as optimized. Lazy construction can still cost caller time.

## Deferred Boundaries

SDK-01 through SDK-07, SDK-10, SDK-11 and PERF-04 through PERF-07 are not repaired
here. PERF-02 belongs to the application. No coordinator or decoder redesign is
included. HTSP-01 remains an integration dependency: the SDK currently treats
`Stopped` as terminal and explicitly rejects a second `Started` as unsupported
track reconfiguration. A producer-only repair or dependency bump does not prove
decoder reconfiguration. This change retains released HTSP 0.7.0 and does not
substitute unpublished protocol source.

Subsequent [playback and EPG repairs](audit-defect-repairs.md) address SDK-01
through SDK-05 and SDK-10; they are not part of the cache repair scope above.
