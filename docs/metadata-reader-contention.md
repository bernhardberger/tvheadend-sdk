# Metadata reader contention

## Repair

`PhaseOneSessionMetadata.resolveGeneration` and `currentObservation` previously
acquired the same monitor as EPG query reduction, snapshot construction and
retention. The EPG repository also acquired that monitor to select its coverage
requester. These reads need the published immutable observation, not the working
reducer state.

They now use the existing `SessionObservationStore` synchronization and a volatile
metadata generation fence. The coverage requester is safely published through a
volatile reference, read before checking that generation. Retirement writers must
update generation before clearing the requester; both orderings are load-bearing.
The store still checks owner, generation and exact current-session proof together with the snapshot.
An overlapping reset can therefore expire a read or allow the preceding complete
observation; it cannot authorize a replacement generation with an old proof.
Reducer writes, query/live-update ordering and publication keep their existing
monitor and lifecycle. Public declarations are unchanged.

The EPG search fence still acquires the reducer monitor: it pairs the generation
with mutable `generationBindRevision`. Search and other reducer-backed operations
are outside this repair's published-observation and coverage-reader measurement.

## Representative before/after evidence

`PhaseOneSessionMetadataContentionTest` runs 40 contended query/publication and
retention cycles over 100 channels with 20,000 initial events. Each interval starts
while the query is inside the reducer monitor. A list fixture releases the writer
before the actual read, so writer completion races the read; no sleep or artificial delay is included.
The interval includes that release, generation resolution and observation read.
Both versions run the identical workload on the same JDK 21 JVM host.
This is a reproducible measurement workload without a timing threshold; the
separate paused-query test below provides the deterministic regression gate.

| Implementation | Samples | Median reader interval | Maximum reader interval |
| --- | ---: | ---: | ---: |
| `37c2c6652dbc44d9e4bf9953698ba7a1cfb5ee6a` with the new test | 40 | 3.206931 ms | 10.613487 ms |
| Reader repair | 40 | 0.007453 ms | 0.018826 ms |

These are local workload measurements, not a latency guarantee or a Player UI
benchmark. The earlier consumer's 408.407 ms wait motivated investigation; it is
not this workload's baseline. Its separate 104.062 ms lazy lookup remains a
distinct attribution lead.

## Correctness evidence

The deterministic regression pauses a real query inside reduction and requires
readers, single/batch coverage acquisition and batch cancellation to finish before
the writer is released. It fails on the original implementation with a reader
timeout and passes with the repair. It verifies the preceding exact observation,
ordered/deduplicated batch results, subsequent query and live publication,
retention, old-snapshot immutability and expired authority after reset/rebind.
A concurrent replacement regression checks channel/EPG coherence across 100
generations. Existing metadata, observation, reducer and worker checks cover
late replies, live-update fences and cancellation settlement.

Run the focused regression with:

```sh
./gradlew :sdk-core:test --tests '*PhaseOneSessionMetadataContentionTest'
```

The release also requires the affected module checks, full `build check`,
independent engineering review and exact staged real Player consumer evidence
before publication. Local measurements and staging do not establish public
availability or device performance.
