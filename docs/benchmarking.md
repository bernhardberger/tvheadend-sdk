# JVM benchmarks

The `sdk-core` benchmark source set uses kotlinx-benchmark 0.5.0's generated
JMH harness, following the HTSP library's setup. It has friend access to core
internals; its classes and runtime dependencies are not part of SDK publications.
Inputs are synthetic metadata; the cache scenarios also encode/load generated
files. No server, device, credentials, transport decoding, or application UI is
involved.

## Run

Use JDK 21 and the normal persistent Gradle caches:

```sh
# Compile and exercise every scenario, with allocation profiling.
./tools/verify :sdk-core:benchmarkSmokeBenchmark

# Measurement run: two fresh JVM forks, five warmups and five measurements.
./tools/verify :sdk-core:benchmarkBenchmark
```

The smoke profile uses one fork and one 100 ms warmup/measurement. It checks
execution and fixture invariants, not performance. The main profile uses 1 s
iterations. Cache lifecycle trials use single-shot mode instead: one complete
cycle per iteration. Both use a 512 MiB initial / 1 GiB maximum fork heap and JMH's GC
profiler. Failures stop the run. Ordinary `build check` compiles benchmark
sources and the generated JMH harness but does not run timing measurements or
apply performance thresholds.

Reports are overwritten on each run:

- `sdk-core/build/reports/benchmarks/smoke/benchmark.json`
- `sdk-core/build/reports/benchmarks/main/benchmark.json`

Copy a report before running a comparison. Record the source revision and dirty
diff, JDK/VM, CPU, operating system, heap settings, profile, and host contention
alongside it. Use the shared build launcher on managed hosts and avoid other
heavy workloads during measurements. Results from that memory-bounded build
slice are local reference points, not device budgets.

## Workloads and units

Each guide has 200 half-hour events per channel (100 hours). Channel counts
10 / 100 / 500 produce 2,000 / 20,000 / 100,000 events, up to the default retained
event capacity. This describes resident guide size; it does not request or
simulate a configured server query horizon.

| Benchmark | One operation | Primary metric |
|---|---|---|
| `EpgIngestionBenchmarks.ingestAndPublish` | Fresh metadata owner, all channels/events, initial-sync reconciliation, ready publication | Average µs per complete guide |
| `EpgUpdateBenchmarks.updateBatchAndPublish` | 100 changed events through the deferred EPG path, then one flush/publication | Average µs per 100-event batch |
| `EpgUnchangedUpdateBenchmarks.repeatBatchAndPublish` | Repeat 100 identical event updates, then one flush/publication | Average µs per 100-event batch |
| `MetadataContentionBenchmarks.publication` | One reader and one query/retention writer sharing a 100-channel / 20,000-event guide | Sampled µs; use the separate reader/writer secondary metrics |
| `MixedEpgBenchmarks.mixed` | One scrolling now/next reader and one mixed-update/history-trace writer; 20,000 or 100,000 guide events plus four lifecycle events; hold 0 or 8 previous publications | Sampled µs per browse or complete trace; use separate secondary metrics |
| `CacheLifecycleBenchmarks.restoreResyncAndClear` | Load cached guide, seed stale publication, resynchronize, disconnect and clear; hold 0 or 8 separately restored snapshots | Single-shot µs per complete lifecycle |
| `CacheRestoreBenchmarks.decodeGuide` | Decode a warm cached file of 33,000 generated events across 143 channels and approximately seven days; restore 4,719 near-window events | Average µs per file restore |

Fixture construction is outside measurement. Ingestion includes SDK-owned
allocation and publication, but not construction of gateway inputs. Updates
alternate between two prebuilt batches so every publication changes titles.
The unchanged-update scenario applies one batch in setup, then repeats it; it
isolates duplicate-input costs without assuming their frequency on a real server.
Returned observations are consumed by JMH. Setup and iteration teardown check
guide size, publication, and worker progress without timing assertions.

Cache restore uses a fixed injected device clock. The lifecycle workload restores
49 half-hour starts per channel through the inclusive 24-hour cut, then replaces
that partial snapshot with the full live sync. The decode-only workload excludes
fixture generation, persistence, observation indexing, connection and UI work;
it does not model cold disk IO or Android CPU contention.

The contention reader calls `resolveGeneration` and `currentObservation`, as in
`PhaseOneSessionMetadataContentionTest`. The writer alternates a one-event query
result, publishes it, and scans retention. Its fixed retention window preserves
the workload across iterations. Unlike the regression test's 40 latch-released
cycles, this benchmark continuously runs both workers without a per-read gate.
It measures steady-state reader/writer interference, including reads that do not
overlap publication; it is not a guaranteed worst-case lock wait. Keep the
paused-query regression test as the deterministic correctness gate.

Read the `readObservation` secondary metric's median, p95, p99 and maximum in
the JSON report, alongside `publishQueryAndRetain`. The combined group score
mixes two different operations and is not a reader latency. SampleTime samples
invocations; maxima and tails depend on sample count and host scheduling.

### Mixed browsing and history trace

The mixed benchmark adds interacting operations rather than a claim about a
typical server's update distribution. One writer replays a bounded trace:

1. Apply 50 genuinely changed titles and 50 identical titles, delete four ended
   events, and flush once. The deleted events become visible history.
2. Advance injected server time by six hours, submit one identical update to
   trigger history expiry, and flush again.
3. Re-add the four lifecycle events and flush. Live cardinality is restored.

Each writer operation therefore contains **109 metadata inputs and three
publications**, not one 100-event update batch. The lifecycle events occupy
separate past intervals without overlapping the guide. The retained-event limit
is explicitly the guide size plus four. Setup verifies intermediate history,
expiry, final cardinality, changed content and old-snapshot isolation; iteration
teardown checks worker progress and stable guide content.

The trace resets its synthetic server clock at the beginning of each replay.
It compresses a six-hour lifecycle into one operation to keep the resident
workload bounded; it does not simulate elapsed time, natural deletion frequency,
or continuous real-world retention. Changed titles alternate between replays.

The reader captures one immutable observation per operation and consumes 24
public lookups: `eventAt` and `nextEvent` for twelve channels. It scrolls the
channel position and cycles four timestamps six hours apart. This includes SDK
lookup work, not UI rendering, network searches or an EPG-window API.

The writer optionally retains a ring of the last eight publications, including
intermediate history states. This guarantees bounded snapshot retention even
when the reader misses publications; the reader can additionally hold its
current observation. The ring models references an application might retain,
not eight guaranteed distinct live event objects per programme. Public event
projections can be shared. Compare `retainedSnapshots=0` and `8` under otherwise
identical conditions; these cases do not measure retained bytes or peak heap.

Both workers run continuously without rate limiting. Use `browse` and
`replayMixedTrace` secondary scores separately. The mixed group's GC allocation
metrics cover both workers and harness activity; normalized B/op is not the
allocation cost of a writer trace or a browse operation alone.

## Allocation and memory

`gc.alloc.rate.norm` reports bytes allocated per benchmark operation. For
ingestion that is a complete guide; for updates it is a 100-event batch. These
are useful independent of raw allocation rate (MB/s), which also changes when
the code runs faster. JMH's GC profiler is process-wide: the contention group's
allocation result mixes readers, writer work and harness overhead. Do not
interpret it as reader-only B/op.

Allocated bytes are not retained heap, peak live memory, or a leak measurement.
The mixed and cache lifecycle scenarios explicitly hold bounded snapshots; the
other trials release superseded observations. Android/device memory budgets
still require separate profiling.

### Cache lifecycle and heap checkpoints

`CacheLifecycleBenchmarks.restoreResyncAndClear` loads the real record-stream
cache, seeds stale metadata, rebuilds the same guide through initial sync,
disconnects retaining display data, and clears the owner. It holds zero or eight
distinct consecutive publications across cycles to model consumer retention.
Cache creation and retention-ring filling are outside measurement; lightweight
invariant checks run inside it. Reads use the warm local filesystem cache.
This exercises metadata ownership
and serialization, not network connection latency, artwork, or the asynchronous
cache writer.

Normal runs use single-shot timing and allocate no heap-diagnostic events. For
after-GC lifecycle observations, run a separate diagnostic trial:

```sh
./tools/verify :sdk-core:benchmarkBenchmark --args='CacheLifecycleBenchmarks -p channelCount=500 -wi 8 -i 5 -f 1 -prof jfr:dir=/tmp/opencode/sdk-cache-jfr -foe true -jvmArgs "-Xms512m -Xmx1g -Dtvheadend.benchmark.heapCheckpoints=true"'
for recording in /tmp/opencode/sdk-cache-jfr/*/profile.jfr; do
    jfr print --events tvheadend.benchmark.CacheHeapCheckpoint "$recording"
done
```

The retention ring is filled during setup; eight warmup cycles further settle
the heap and JIT before measurement. The opt-in checkpoints request synchronous
full GC and record process-wide used heap at
baseline, restored, resynchronizing, current, disconnected, and cleared stages.
The run fails if no completed collection is observed. Use the documented JDK
and default GC; disabling explicit GC or using asynchronous explicit collection
is unsupported. Compare steady cycles, not a single first sample. The fixture,
JMH and JVM are included in each heap reading, so compare phase deltas and
plateaus rather than treating the absolute count as SDK-only bytes.

**Ignore timing and allocation scores from heap-checkpoint runs.** Forced GC
changes both. These observations measure reachable heap at selected stages,
not the maximum transient allocation between stages or a whole-application
memory budget. JDK Flight Recorder manages recording/export; checkpoints emit
only synthetic phase names, cycle numbers, counts, and heap bytes.

The diagnostic `--args` replaces the task's default JMH arguments. This command
writes JFR recordings, not the default JSON report; an existing
`reports/benchmarks/main/benchmark.json` remains from an earlier run. Add
`-rf json -rff <output-file>` when explicitly requesting a separate JSON report,
but do not use diagnostic timing/allocation scores for performance comparisons.

#### Local heap reference (2026-10-11)

After the three optimizations below, 100,000 events, eight warmup cycles and
five measured cycles per retention setting, one JFR fork each:

| After-GC phase | Hold 0: used heap, MiB | Hold 8: used heap, MiB |
|---|---:|---:|
| Baseline (new empty owner) | 35.804–35.805 | 324.924–325.003 |
| Cache restored and indexed | 71.977–71.978 | 324.925–325.047 |
| Incoming guide before initial-sync publication | 95.435–95.436 | 348.383–348.505 |
| Current synchronized guide | 106.489–106.491 | 359.478–359.599 |
| Disconnected, display publication retained | 73.959–73.960 | 326.913–327.029 |
| Owner cleared, consumer references unchanged | 37.813–37.814 | 326.884–327.000 |

These are **whole-process stage readings**, including the decoded input fixture
and harness. The zero-retention owner drops most guide memory on clear; its
empty reusable containers still retain capacity, so it is about 2 MiB above the
fresh-owner baseline until that owner itself is released. No increasing
after-clear trend was observed across these five cycles; this is a bounded
plateau check, not a long-duration leak test.

The eight held publications here come from **separate cache loads**, each with
its own decoded graph. They add roughly 289 MiB to the cleared plateau. Eight
successive changed-update snapshots in the mixed benchmark share most event
objects and are a different retention workload. At steady ring capacity,
restoring another guide evicts an older one before the checkpoint. Temporary
ninth-guide overlap and other between-checkpoint peaks are not measured.

The synchronized stage retains both the restored display snapshot and rebuilt
reducer state when equal-content publication reuses the display snapshot.
Reducing that overlap would require sharing/reconciling those independently
built graphs; this experiment establishes its size, not justification for a
new lifecycle/interning mechanism. Consumers releasing obsolete display
snapshots is the direct control on the retention case above.

A separate normal timing run (two forks, eight warmups, five measurements,
`-prof gc`, no heap checkpoints) measured complete 100k-event cache lifecycles
at 77.3 ± 3.2 ms with zero held snapshots and 98.9 ± 22.2 ms with eight.
Both allocated about 145.5 MB per lifecycle. The intervals are wide and the
filesystem cache is warm; these are initial references, not a retention penalty
estimate or cold-start budget.

## Comparing results

Use identical fixtures, JDK, JVM flags, profiler, machine and profile. Compare
multiple forks and repeated runs; treat changes within observed variance as
inconclusive. Keep timing thresholds advisory until variance is characterized.
Compare allocation/op as well as elapsed time and reader tails. Never use smoke
results as evidence of an optimization.

### Initial local reference (2026-10-11)

Measured against production revision `ceb2fd82bad13ee1dec9c2743af40b4079b2568e`
with this benchmark harness added in the working tree. Main profile, JMH 1.37,
Debian OpenJDK 21.0.12.1+1, Linux 7.0.14-5-pve, AMD Ryzen 5 7640HS (eight online
logical CPUs), default GC, compiler blackholes, and the heap settings above.
The shared build slice had an 8 GiB ceiling; no other SDK verification ran
concurrently, but host load and CPU frequency were not controlled. One run with
two forks is a reference, not a characterized performance budget.

Time is mean µs/op ± JMH's reported 99.9% confidence error. Allocation is rounded
mean B/op, not resident memory.

| Events | Ingest time | Ingest B/op | 100-event update time | Update B/op |
|---:|---:|---:|---:|---:|
| 2,000 | 286.5 ± 28.4 | 1,135,778 | 97.3 ± 2.3 | 300,209 |
| 20,000 | 3,688.4 ± 77.8 | 11,553,626 | 1,351.0 ± 106.8 | 2,960,103 |
| 100,000 | 34,869.1 ± 1,199.2 | 58,051,104 | 13,456.0 ± 500.5 | 14,171,897 |

At 20,000 events under contention, reader median / p95 / p99 / sampled maximum
were 0.040 / 0.050 / 0.051 / 37.696 µs. The writer averaged 1,476.9 ± 13.2 µs/op.
These describe continuous sampled reads, not the old latch-gated regression
measurement or a guaranteed maximum lock wait.

The fixed-size update batch allocates more as the resident guide grows. That is
a useful lead for profiling publication costs; this run alone does not identify
the allocation sites or justify an optimization.

### First optimization: size the event-ID index (2026-10-11)

A separate JMH Flight Recorder run of the 100,000-event update scenario found
allocation stacks in `MapBuilder.ensureCapacity` and `MapBuilder.rehash` while
building `EpgSnapshot.eventsById`. The producer knows the event count, so using
`buildMap(events.size)` avoids growing and copying its backing arrays. The
existing duplicate-ID ambiguity handling and immutable snapshot semantics are
unchanged.

Paired runs used the main profile's JVM and measurement settings above, without
Flight Recorder. Each operation still updates 100 events and publishes once:

| Resident events | Before B/op | Pre-sized B/op | Reduction |
|---:|---:|---:|---:|
| 2,000 | 300,209 | 224,481 | 25.2% |
| 20,000 | 2,960,103 | 1,864,341 | 37.0% |
| 100,000 | 14,171,895 | 9,556,023 | 32.6% |

A repeated before/after comparison reproduced these allocation reductions. Its
update times (mean µs/op ± 99.9% confidence error) were:

| Resident events | Before | Pre-sized |
|---:|---:|---:|
| 2,000 | 95.6 ± 2.1 | 90.5 ± 5.2 |
| 20,000 | 1,328.7 ± 169.8 | 1,185.3 ± 162.9 |
| 100,000 | 13,538.4 ± 599.4 | 12,083.7 ± 533.3 |

Timing is less conclusive than allocation: the first optimized 100,000-event
run was noisy at 14,575.3 ± 6,272.1 µs/op. The repeated optimized run exercised
the full suite; ingestion means were 259.8 / 3,636.4 / 34,070.4 µs/op, and the
contention reader's median / p95 / p99 remained 0.040 / 0.050 / 0.051 µs. Its
sampled maximum was 44.672 µs, versus 37.696 µs in the initial reference; neither
maximum is a latency guarantee.

This reduces transient allocation; it does not establish retained-heap savings.
At this step, publication still scans and hashes the guide and rebuilds both
event indexes. These observations motivated the later profiling below rather
than a more complex incremental snapshot implementation.

### Second optimization: identical event updates (2026-10-11)

After the event-ID index change, an identical 100-event batch still invalidated
the reducer snapshot. Publication scanned and hashed the entire guide before
recognizing equal content and reusing the published indexes. The reducer now
retains its cached snapshot when the merged live event is unchanged. Authority
tracking and history supersession still execute; pruning, history restoration,
and rejected replacements that remove history still invalidate the snapshot.

Measured on the `v0.24.0` production base with the first optimization and the
same main-profile settings, before/after this change:

This base is `1983ff49cbaf950a67d07ed976ab1874c4fd86cc`, newer than the
`ceb2fd82…` base used for the initial reference and first optimization above.
Each before/after comparison uses its own matched base; the two sections are
not a single continuous comparison across production revisions.

| Resident events | Before µs/op | After µs/op | Before B/op | After B/op |
|---:|---:|---:|---:|---:|
| 2,000 | 59.6 ± 2.0 | 8.8 ± 0.6 | 56,848 | 28,136 |
| 20,000 | 652.2 ± 37.0 | 10.7 ± 0.2 | 220,541 | 33,960 |
| 100,000 | 6,912.5 ± 232.6 | 17.1 ± 0.2 | 946,855 | 59,600 |

These are identical-input results, not a speedup claim for changed guide data.
No per-event hash cache or additional persistent index is introduced in this
step; event-hash caching is measured separately in the third optimization below.

A subsequent full-suite run reproduced 16.8 ± 0.2 µs and 59,600 B/op for the
100,000-event identical-update case. Changed updates at that size measured
12,076.4 ± 953.2 µs/op; the identical-input speedup does not apply to them.

### Mixed trace reference (2026-10-11)

Measured on the same `v0.24.0` base with both optimizations, using the JVM,
machine and main-profile settings above. One run, two forks per parameter
combination. These are secondary worker metrics; the combined group score is
not a meaningful operation latency.

| Guide events (plus four lifecycle events) | Held publications | Writer trace mean ± error, ms | Browse median, µs | Browse p95, µs | Browse p99, µs |
|---:|---:|---:|---:|---:|---:|
| 20,000 | 0 | 4.296 ± 0.063 | 11.840 | 16.832 | 27.200 |
| 20,000 | 8 | 3.920 ± 0.053 | 12.160 | 17.440 | 27.712 |
| 100,000 | 0 | 40.834 ± 0.535 | 23.008 | 30.752 | 43.072 |
| 100,000 | 8 | 41.605 ± 0.949 | 22.912 | 32.000 | 47.424 |

Each writer trace includes three publications; each browse contains 24 lookups.
Browsing remains much shorter than writer work in this workload. Guide-wide
publication remains the stronger profiling lead. Holding eight snapshots did
not produce a consistent writer slowdown across guide sizes; the faster 20k
case is not evidence that retaining snapshots improves performance. Parameter
order, GC behavior and uncontrolled host load limit that comparison. Neither
these timing results nor the group's allocation profiler establish retained
heap usage, peak memory or an Android frame-time budget.

### Third optimization: immutable event hashes (2026-10-11)

A new Flight Recorder profile after the first two optimizations found
`EpgEvent.hashCode` in 26.5% of 461 execution samples (inclusive stacks).
Snapshots repeatedly hash the same immutable events. Cache the existing
data-class hash in each event, retaining full field equality and the snapshot's
collision fallback. Mutable constructor inputs are still defensively copied.
No index or incremental publication state is introduced.

Matched before/after main-profile runs on the same `v0.24.0` base:

| Resident events | Changed batch before, µs | Changed batch after, µs | Ingest before, µs | Ingest after, µs |
|---:|---:|---:|---:|---:|
| 2,000 | 87.7 ± 2.2 | 70.1 ± 1.1 | 258.5 ± 5.3 | 263.5 ± 4.2 |
| 20,000 | 1,085.9 ± 38.8 | 974.5 ± 132.5 | 3,431.9 ± 275.8 | 3,698.4 ± 407.6 |
| 100,000 | 12,045.1 ± 206.2 | 8,861.6 ± 473.7 | 33,290.2 ± 922.1 | 33,644.0 ± 1,768.9 |

The 100k changed-update improvement was 26.4%; the 20k timing comparison is
inconclusive. Ingestion timing intervals overlap. This is a CPU/storage
tradeoff: ingestion allocated an additional eight bytes per event on this JVM,
or 800,000 bytes for the 100k guide. Changed-update allocation is essentially
unchanged (~9.56 MB per batch at 100k), because only replaced events need new
hash storage. Do not interpret this change as an allocation optimization.

A repeat of the 100k changed-update case measured 9,115.4 ± 327.6 µs/op and
9,559,170 B/op, reproducing a 24.3% timing improvement against the matched
12,045.1 µs baseline.

The separate mixed-workload run reproduced the publication improvement:

| Resident guide events | Held snapshots | Writer before, ms | Writer after, ms | Browse p99 before / after, µs |
|---:|---:|---:|---:|---:|
| 20,000 | 0 | 4.296 ± 0.063 | 3.183 ± 0.021 | 27.200 / 25.248 |
| 20,000 | 8 | 3.920 ± 0.053 | 2.980 ± 0.029 | 27.712 / 29.440 |
| 100,000 | 0 | 40.834 ± 0.535 | 32.319 ± 0.344 | 43.072 / 41.536 |
| 100,000 | 8 | 41.605 ± 0.949 | 33.732 ± 0.756 | 47.424 / 42.941 |

The 100k writer improvement is 19–21% with and without snapshot retention.
Reader results are mixed, including a higher 20k/eight-snapshot p99; this is not
a claim of improved browsing latency. Writers are unthrottled, so the faster
writer also changes concurrent load. Retain the change for its reproduced
publication CPU reduction, with the event-storage tradeoff above.

The CLI override in `sdk-core/build.gradle.kts` enables `-prof gc` and
`-foe true`, which kotlinx-benchmark 0.5.0 does not expose directly. It explicitly
rejects unsupported profile settings rather than silently ignoring them. Extend
that mapping if adding filters, parameter overrides, or additional profiles.

## Android packet allocation diagnostic

`PacketAllocationInstrumentationTest#recorded_packet_allocation_diagnostic`
replays the existing recorded H.264, MPEG-2 video, MPEG audio and AC-3 fixtures
through the real readers into a synchronous consuming sink. Packet loading and
32 warmup cycles precede five measured trials of approximately 128 MiB of input
per codec. Packets are prebuilt and replayed without pacing. The injected payload
allocator counts adapter arrays exactly; `Debug.getRuntimeStat` reports ART
process-wide allocated bytes and GC counters. Output also records consumed
sample bytes and sample counts. ART counters are coarse and GC work can straddle
trial boundaries; GC time is accumulated collector time, not a pause percentile.

This diagnostic excludes transport, SampleQueue storage, decoding and rendering.
Elapsed time is supporting evidence only: these short sequential trials do not
have JMH forks or confidence intervals. They do not establish end-to-end playback
latency, steady-playback GC pauses, retained heap or a channel-switch plateau.
Device runs require explicit authority. Select the authorized device through
`ANDROID_SERIAL`, and use `tools/sdk-device` for installation, instrumentation
and cleanup as required by `AGENTS.md`; consult `tools/sdk-device --help` for
its commands. After building with `./tools/verify` and installing the test APK,
select the dedicated diagnostic class with:

```sh
tools/sdk-device instrument \
  --package at.bernhardberger.tvheadend.sdk.media3.test \
  --runner androidx.test.runner.AndroidJUnitRunner \
  --class at.bernhardberger.tvheadend.sdk.media3.PacketAllocationInstrumentationTest#recorded_packet_allocation_diagnostic
```

### Fourth optimization: bounded packet scratch reuse (2026-10-11)

Previously the adapter allocated one exact-size byte array and a
`ParsableByteArray` for every packet. It now reuses both, while preserving one
copy through `SubscriptionBinary` and keeping the packet's logical length
separate from capacity. The finite-IDR scan uses that same length, preventing
old trailing bytes from qualifying a shorter packet as an IDR.

Idle scratch retention is capped at **256 KiB per adapter**. Larger packets remain
supported with temporary exact-size arrays, and the input wrapper drops its array
reference in `finally`. Accepted skips, dropped-packet markers and terminal input clear retained
scratch. This trades bounded per-reader retained capacity for lower allocation;
it is not a claim of lower retained heap. The recorded fixture maximum is 74,360
bytes; the cap accommodates those packets without assuming a universal packet
size or rejecting larger input.

The ownership check used pinned Media3 1.11.1 sources: built-in elementary-stream
readers consume synchronously or copy partial input into owned storage; SDK
readers likewise keep owned partial-input buffers. Media3's
[`TsExtractor`](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/ts/TsExtractor.java)
itself reuses its input buffer across reader calls.
[`SampleDataQueue`](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/SampleDataQueue.java)
and
[`SubtitleTranscodingTrackOutput`](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/text/SubtitleTranscodingTrackOutput.java)
copy bytes into their own storage. The interface documentation alone was not
taken as an explicit backing-storage lifetime guarantee. Recheck this assumption
when adding readers or changing the pinned Media3 version.

Matched debug-APK runs on an Android 16 / API 36 x86_64 TV emulator, on the
`v0.24.0` base plus the three EPG optimizations above, changed only the adapter
between measurements. Each APK was tested in two fresh instrumentation runs,
in baseline/optimized/baseline/optimized order. A temporary test application ID
isolated the runs from an existing SDK test installation; the temporary package
was uninstalled afterwards. First-run medians across five trials:

| Codec | Packets/trial | Payload arrays before / after | ART allocated MiB before / after |
|---|---:|---:|---:|
| H.264 | 23,120 | 23,120 / 0 | 141.375 / 2.047 |
| MPEG-2 video | 7,424 | 7,424 / 0 | 156.797 / <0.02 |
| MPEG audio | 232,960 | 232,960 / 0 | 145.047 / 3.672 |
| AC-3 | 87,360 | 87,360 / 0 | 138.797 / 5.375 |

The second runs reproduced zero warmed-up payload allocations and ART medians
of 2.047 / <0.02 / 3.672 / 5.375 MiB after the change. Array allocation during
warmup is excluded, not eliminated. Sample-byte and sample-count totals matched
between versions for every codec/trial. Process-wide GC counts were zero in the
first optimized run and 0–2 per trial in the repeat; baseline counts ranged from
0–36. These counters do not establish zero-GC playback.

All Media3 JVM tests passed, including exact reader fixture oracles. New focused
regressions cover capacity growth/reuse, empty and shorter packets, oversized
packets, incomplete copies, discontinuity reclamation, exception-path cleanup
and finite-IDR stale tails.
Android reader-to-SampleQueue and finite-IDR continuation tests passed. The
combined H.264/MPEG-audio rendering test failed identically on baseline and
optimized APKs because the emulator reports the MPEG audio track unsupported;
the paused-render test also failed on both APKs (baseline resume timeout,
optimized audio absent). These rendering checks remain unsuccessful on this
emulator; the successful finite-IDR check above is reader/SampleQueue continuation,
not a successful full audio/video rendering gate.

### Fifth optimization: single-insertion event-ID indexing (2026-10-11)

A fresh JFR profile after the earlier optimizations attributed 137 of 438
execution samples (31%) to event-ID index construction. The old loop checked
`containsKey` before each `put`, performing two lookups and boxing the event ID
twice. The new loop uses `put`'s previous value to detect duplicates, then marks
those IDs ambiguous after all insertions. The temporary duplicate-ID set is
empty for normal unique-ID guides and is not retained by the snapshot. The
index representation, snapshot immutability and lookup behavior are unchanged.
Focused regression coverage includes two, three and eight occurrences of an ID,
boundary IDs and lookup through both current and retained observations.

Matched runs used the same `v0.24.0` base plus the preceding optimizations, with
two forks, five one-second warmup and measurement iterations, the same heap and
GC profiler settings, and no JFR profiler during comparison. Each update still
changes 100 events and publishes the complete snapshot:

| Resident events | Before µs/op | After µs/op | Before B/op | After B/op |
|---:|---:|---:|---:|---:|
| 2,000 | 69.9 ± 1.1 | 70.7 ± 3.2 | 227,680 | 179,776 |
| 20,000 | 961.8 ± 122.4 | 883.3 ± 80.6 | 1,865,538 | 1,387,632 |
| 100,000 | 9,118.4 ± 324.6 | 8,661.0 ± 238.8 | 9,559,170 | 7,159,262 |

Allocation falls by 21–26%; the 100,000-event case saves about 2.4 MB per
publication. Timing confidence intervals overlap, so this is retained as an
allocation improvement, not an established latency improvement. Initial
ingestion timing likewise overlaps: 33,012.9 ± 1,575.7 versus
32,573.2 ± 1,829.4 µs for 100,000 events. No retained-heap reduction is claimed;
the returned index has the same storage representation.
