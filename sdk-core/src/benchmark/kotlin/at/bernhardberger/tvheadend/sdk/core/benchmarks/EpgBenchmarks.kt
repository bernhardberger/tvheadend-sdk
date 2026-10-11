package at.bernhardberger.tvheadend.sdk.core.benchmarks

import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.gateway.EventId
import at.bernhardberger.tvheadend.sdk.core.session.PhaseOneSessionMetadata
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/** Public only for JMH's generated harness; one operation ingests a complete guide. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
public class EpgIngestionBenchmarks {
    /** 200 half-hour events per channel: 2,000 / 20,000 / 100,000 events. */
    @Param("10", "100", "500")
    public var channelCount: Int = 0
    private lateinit var fixture: EpgFixture

    /** Validate capacity and publication outside measurement. */
    @Setup
    public fun setup(): Unit {
        fixture = EpgFixture(channelCount)
        fixture.validate(fixture.readyMetadata())
    }

    /** Includes a fresh metadata owner, initial sync reconciliation and ready publication. */
    @Benchmark
    public fun ingestAndPublish(): SessionObservation = fixture.readyMetadata().observation.value
}

/** One operation updates 100 events and flushes a single deferred EPG publication. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
public class EpgUpdateBenchmarks {
    /** Same guide sizes as ingestion, held constant throughout the trial. */
    @Param("10", "100", "500")
    public var channelCount: Int = 0
    private lateinit var fixture: EpgFixture
    private lateinit var metadata: PhaseOneSessionMetadata
    private var revision = 0

    /** Exercise both alternating batches so a no-op update cannot pass validation. */
    @Setup
    public fun setup(): Unit {
        fixture = EpgFixture(channelCount)
        metadata = fixture.readyMetadata()
        fixture.validate(metadata)
        repeat(2) { expected ->
            val before = metadata.observation.value
            val after = updateBatchAndPublish()
            check(before !== after)
            repeat(100) { index ->
                check(after.event(EventId(index.toLong()))?.title == "Updated $expected")
            }
            check(before.event(EventId(0))?.title != after.event(EventId(0))?.title)
        }
    }

    /** Prebuilt alternating inputs exclude fixture allocation; SDK allocations remain measured. */
    @Benchmark
    public fun updateBatchAndPublish(): SessionObservation {
        fixture.updates[revision].forEach(metadata::acceptMetadataDeferringEpg)
        revision = 1 - revision
        metadata.flushDeferredMetadata()
        return metadata.observation.value
    }

    /** Ensure repeated updates never silently lose guide data. */
    @TearDown(Level.Iteration)
    public fun validate(): Unit = fixture.validate(metadata)
}

/** Repeated identical updates measure publication work when guide content does not change. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
public class EpgUnchangedUpdateBenchmarks {
    /** Same guide sizes and batch size as changed updates. */
    @Param("10", "100", "500")
    public var channelCount: Int = 0
    private lateinit var fixture: EpgFixture
    private lateinit var metadata: PhaseOneSessionMetadata

    /** Apply the batch once before measuring identical repeats. */
    @Setup
    public fun setup(): Unit {
        fixture = EpgFixture(channelCount)
        metadata = fixture.readyMetadata()
        val before = repeatBatchAndPublish()
        val after = repeatBatchAndPublish()
        check(before.epgState == after.epgState)
        repeat(100) { index -> check(after.event(EventId(index.toLong()))?.title == "Updated 0") }
        fixture.validate(metadata)
    }

    /** Input handling and flush still run; the SDK decides whether content changed. */
    @Benchmark
    public fun repeatBatchAndPublish(): SessionObservation {
        fixture.updates[0].forEach(metadata::acceptMetadataDeferringEpg)
        metadata.flushDeferredMetadata()
        return metadata.observation.value
    }

    /** Ensure repeated inputs retain the complete guide. */
    @TearDown(Level.Iteration)
    public fun validate(): Unit = fixture.validate(metadata)
}
