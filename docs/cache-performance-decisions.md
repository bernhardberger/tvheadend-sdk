# Cache performance decisions for the 0.38 candidate

The remaining work is saved-guide restore and interruption-safe logo-index updates.
This is candidate work: a reviewed local `-SNAPSHOT`, checksums and an owner-run
G10 comparison precede any adoption decision. Release, tag, publication and
release-preparation commits require a fresh owner approval for the exact version
through the Player primary.

## Remaining audit items

These decisions cover the October 28 cache/session/model audit and the items
left without a performance claim in 0.37. “Keep” means retain the current
behavior; it does not mean a speedup was established.

| Item | Decision for 0.38 | Evidence and cost retained |
|---|---|---|
| Saved-guide JSON and strict UTF-8 cost | **Keep the strict reader; restore latency remains unresolved.** | G10 stream and canonical ProtoBuf arms were slower. String improved both 100k invocations by 14.5–24.2%, but its 5k invocations reversed direction and device fallback-heavy cost remains unmeasured. Retain String as an experiment, not a general replacement. |
| Visible-window-first restoration | **Keep current single publication; reject measured staged v1 for startup speed.** | Full retained-file validation still precedes publication. At 100k, both staged first-observation medians arrived after both strict full-observation medians; staged completion was 1.9–2.6% slower. First SDK observation is not a rendered frame. |
| Full logo-index rewrite per mutation | **Keep 32 fixed shards in the candidate.** | Matched G10 whole-call medians improved 75–86% and allocation fell about 80% for first-read-plus-32-store workloads. First-read medians increased roughly 11–18 ms. Atomic bucket replacement and legacy tombstones preserve process-interruption safety. |
| Catalog value equality and metadata-only statistics | **Keep.** Equal catalogs already skip persistence; metadata byte queries already avoid artwork walks. | Regression evidence establishes the avoided operations. The original 38 writes / 40 statistics calls included real changes and occurred mostly after Ready; their totals cannot be claimed as startup savings. |
| Rebuilding an equal catalog in the reducer; coalescing genuinely changed catalogs | **Keep current immediate-change persistence.** | Equality suppression removes the demonstrated redundant disk writes. No isolated measurement shows remaining catalog projection dominates; coalescing would change the durability interval. The direct-store benchmark does not measure runtime equality suppression. |
| Serialization 1.11.0 versus 1.12.0 | **Keep 1.11.0.** | Matched device artwork workloads on 1.12.0 took 2.1–2.6 times as long and allocated about 4.6 times as much. Other workload effects remain inconclusive; this is not a general library-speed claim. |
| Coverage-only snapshot/index reuse | **Keep.** | Final 100k G10 whole-call medians A1/B2/B3/A4 were 292/142/76/294 ms. Index preparation fell from about 121 ms to .03 ms; allocation fell about 92%. Snapshot traversal and equality remain linear. |
| Single-event snapshot/index rebuilding and publication lock duration | **Keep current atomic publication.** | Host 100k one-event publication medians were 8.881–11.977 ms before and 8.644–9.435 ms after coverage reuse; ranges overlap. About 6.97 MB is still allocated per publication. No device single-event/lock-duration comparison establishes a gain from an incremental publication redesign. |
| History and protected-capacity indexes | **Keep.** | Matched 100k device history reduction fell from 1,861 to 15 ms; repeated protected arrivals from about 23,131 to 97 ms. These are bounded synthetic workloads, not reductions in Ready time. |
| Carryover channel index, including empty-control uncertainty | **Keep the measured time/memory tradeoff.** | Final 100k carryover whole-call medians A1/B2/B3/A4 were 9,748/329/448/10,307 ms. Installed-minus-live post-GC occupancy rose by 5,402,816 bytes at 100k. The host empty-control difference remains unexplained; there is no established JIT attribution. |
| Gateway-to-reducer category/keyword reuse | **Keep for allocation.** | Host saving: 104 bytes/event for the focused multi-element fixture. Device HTSP saving: 9.56–9.60 MB/100k. One B arm was 19.3–20.5% slower while the repeat overlapped A; no latency gain is claimed. |
| Public-model, credits and cache-DTO defensive copies; upstream HTSP copies | **Keep ownership boundaries.** | The measured allocation saving above covers only SDK-owned gateway lists. No isolated result supports removing the remaining copies. Caller-controlled collections must not alias published immutable models. |
| Timestamp-validation reorder during restore | **Keep the original ordering.** | Host all-retained load medians A1/B1/B2/A2 were 15.526/16.626/17.341/15.266 ms. The experiment was reverted; its later-record prefix-mismatch regression remains. |
| Whole-guide save sorting and minute-level coalescing | **Keep.** | Save work remains O(N log N + bytes), coalesced and flushed at stop. The audit found no new save dependency on cold Ready. A format candidate must measure write cost as well as restore cost. |
| Ready ordering, compilation sensitivity and Player startup | **Keep current ordering and separate claims.** | Ready does not join guide restore. Earlier full-AOT runs were materially faster, but compilation state, fixture and harness differences prevent attribution to a single SDK method or a claim of a 0.32-to-current regression. Player owns matched server-backed startup and compilation-profile work. |
| Largest-guide memory and candidate-only workload timings | **Keep explicit limitations.** | The largest final normal post-GC reading was 115.251 MiB. It is neither peak memory nor proof that Player fits its heap. Candidate-only catalog/logo/burst/protected runs establish completion, not matched latency improvement. |

The 0.37 evidence is retained under `build/reports/remaining037-*`,
`build/handoff/cache-final.md`, and the sibling benchmark worktree's
`device-benchmark/results/g10-final037-1/`. Device numbers are debuggable,
non-R8 synthetic measurements. Each ABBA arm uses its own median of samples
1–3, with sample 0 kept separately; arms are not pooled.

## Restore experiment: host evidence and ART decision

The host comparison used the exact G10 event generator: 143 channels, multilingual
descriptions, categories, keywords and one credit, at 5k and 100k retained events.
Actual load/save tests used ext4-backed files with warm OS caches, five warm-ups
and fifteen samples. A separate workload put literal U+FFFD text in every record.
These measurements cover the SDK calls, not device startup or the full G10 harness.

With matched test selection, the conditional String decoder changed the normal
100k load median from 226.134 to 215.564 ms, with overlapping timing ranges, but
allocated 543.05 MB instead of 444.66 MB. Replacement-heavy 100k loads changed from
224.003 to 244.910 ms; 5k loads also regressed. The unchanged save control varied
between runs, so the timing difference cannot be assigned a specific runtime cause.
The production change was reverted byte-for-byte. Stronger later-record corruption,
Unicode and cancellation regressions remain.

The seven-option 100k host comparison measured strict reusable-buffer JSON at
222.071 ms / 449.28 MB, strict prevalidation plus streaming JSON at
299.820 ms / 347.73 MB, and canonical ProtoBuf decode/re-encode at
291.180 ms / 721.23 MB. Option allocations include wrappers not present in the
production reader and are not interchangeable with actual-load allocations.
Raw streaming JSON and ProtoBuf were ineligible because they accepted malformed
UTF-8. Lazy validation would violate rejection of corrupt retained records before
publication; the current public snapshot is eager. This host study did not change
the staged Maven candidate's format.

Encode/save costs were measured too. At 100k, JSON String encoding took
127.720 ms / 403.16 MB versus streaming JSON at 148.427 ms / 132.27 MB. Those
encode-only numbers do not establish a complete-save improvement. The existing
writer, sorting, coalescing and stop-flush behavior remain unchanged. Full source
identities, raw samples and rejected alternatives are in
`build/reports/restore038/g10-followup.md` and its accompanying XML/logs.

The owner subsequently requested device comparisons because the host results do
not establish rankings on G10's ART runtime. Separate experimental SDK source
copies use String decoding with strict fallback, strict-prevalidated JSON
streaming, and native canonical ProtoBuf records. The binary arm uses EPG6 and
invalidates the old format; nullable collection wrappers preserve absent versus
empty values. Decode/re-encode equality rejects noncanonical representations,
but does not authenticate against another valid canonical value.

A fourth experimental copy retains the existing strict decoder and offers a
now-through-plus-six-hour display snapshot after full retained-record validation,
then the complete snapshot. Near publication is skipped after Ready or when
filtering would make a duplicate ID unambiguous. Partial snapshots never become
carryover or persistence authority. This experiment does not defer decoding;
the comparison measures indexing and actual SDK observation receipt separately
from restore completion. Player's startup profile does not transfer to the
separate benchmark package.

The owner completed 40 G10 invocations and 160 samples. Integrity checks verified
all reports, source identities, APKs, extraction hashes and zero exit statuses.
Each table cell below is one invocation's median of samples 1–3, in ABBA order;
sample 0 is retained separately in the detailed report. Times are milliseconds.

| Comparison / events | A1 | B2 | B3 | A4 |
|---|---:|---:|---:|---:|
| String / 5k | 738.400 | 1077.751 | 649.669 | 742.840 |
| String / 100k | 12450.387 | 9438.828 | 9439.422 | 11038.140 |
| Strict stream / 5k | 726.337 | 1113.363 | 1116.478 | 758.058 |
| Strict stream / 100k | 12440.022 | 18639.161 | 17148.132 | 11233.063 |
| Canonical ProtoBuf / 5k | 753.292 | 969.788 | 946.261 | 761.807 |
| Canonical ProtoBuf / 100k | 11018.209 | 28182.330 | 28025.271 | 11262.753 |
| Staged lifecycle / 5k | 773.380 | 846.144 | 820.710 | 813.379 |
| Staged lifecycle / 100k | 11312.909 | 11604.259 | 11528.788 | 11308.562 |

String's 100k gain is consistent across the two invocations, but its first 5k
invocation is 45–46% slower and the second is 12–12.5% faster. Caller CPU follows
the reversal; neither JIT nor noise is an established cause. Allocation is broadly
unchanged on this device fixture. Resolving that reversal and measuring literal
U+FFFD fallback on-device are prerequisites for adopting String. The host allocation
increase is not a measured Android allocation increase.

Strict streaming saves about 1.9% allocation while substantially increasing
latency. Canonical ProtoBuf takes about 2.5 times as long and allocates about
2.33 times as much at 100k. These results reject the measured implementations,
not every possible binary format. Staged v1 publishes the intended near counts
(1,859 or 33,605 events), but does not improve first visibility at 100k and adds
about 5.7 MB allocation. No alternative is adopted into the frozen Maven candidate.

Detailed first-sample, phase, allocation and limitation evidence is retained in
`build/handoff/restore038-results.md`, with integrity results in
`build/reports/restore038/device-integrity.json`. Debug/non-R8 measurements used a
192 MiB Java heap and prewarmed file pages. Allocation is cumulative process
traffic, not retained or peak memory. The roughly 11-second large-guide restore
problem remains open.

## Artwork candidate: fixed index shards

The candidate uses 32 ProtoBuf index shards and preserves synchronous payload and
index replacement. Legacy migration publishes every bucket, including empty
tombstones, before retiring the old index. Independent source review found no
concrete regression in interruption handling, retry state, ownership or runtime
integration. Strengthened crash tests and combined SDK/consumer gates passed.
The owner-run matched device comparison supports retaining this implementation
in the candidate; Player adoption and publication remain separate decisions.

Same-workload host measurements used disk-backed fixtures, five warm-ups and
twelve samples. JVM and operating-system file caches were warm:

| Workload | Baseline → candidate |
|---|---:|
| 32 stores, 439 existing entries | 8.647 → 6.212 ms |
| 32 stores, 4,390 existing entries | 32.628 → 7.130 ms |
| First lookup, 439 entries, new in-memory index | 2.887 → 3.207 ms |
| First lookup, 4,390 entries, new in-memory index | 17.480 → 19.153 ms |

Both implementations perform 64 atomic writes per 32-store batch. Sharding
reduces serialized index bytes rather than deferring publication. Native first
lookup reads 32 indexes instead of one and allocates more. Migration adds a
one-time 32-index publication; a touch flush spanning all buckets can also write
32 indexes. IDs concentrated in one bucket show essentially no insertion benefit.
Mirrored bucket maps add O(N) storage; retained heap bytes have not been measured.

Device batch-store phase medians were 639/96/94/641 ms for the 5k preparation
and 604/113/95/991 ms for the 100k preparation, in old/new/new/old order.
Both cases contain 439 initial logos and 32 stores. First-read medians increased
from 42–47 to 58–60 ms and from 31–33 to 44–47 ms respectively; first samples
also regressed. Allocation fell from about 14.8 MB to 3.0 MB for the whole call.
The older 100k anchors drifted materially, but both new invocations beat the
faster old anchor. This establishes the measured batch tradeoff, not faster first
reads, migration speed or lower retained artwork memory.

Atomic rename without file/directory fsync establishes no new power-loss
guarantee. Raw host measurements, source hashes and the interruption-test matrix
are in `build/reports/artwork038/`; device results are in
`build/handoff/restore038-results.md`.

## Portal usage

October's exact namespace usage could not be read: opening the Sonatype
publishing portal redirected to its login page. No authenticated portal session
was available. The owner's stated limit is 35 releases / 3,320 files per month
for `at.bernhardberger.tvheadend`; repository tags and staged-file counts are
not substitutes for the portal's usage counters.
