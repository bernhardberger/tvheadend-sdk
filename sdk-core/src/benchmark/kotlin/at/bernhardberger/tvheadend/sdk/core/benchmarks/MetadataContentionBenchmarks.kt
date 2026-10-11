package at.bernhardberger.tvheadend.sdk.core.benchmarks

import at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.EventId
import at.bernhardberger.tvheadend.sdk.core.metadata.EpgQueryAcceptance
import at.bernhardberger.tvheadend.sdk.core.session.PhaseOneSessionMetadata
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Group
import org.openjdk.jmh.annotations.GroupThreads
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads

/** A steady-state version of PhaseOneSessionMetadataContentionTest's reader/publication workload. */
@State(Scope.Group)
@BenchmarkMode(Mode.SampleTime)
@Threads(2)
public class MetadataContentionBenchmarks {
    private lateinit var fixture: EpgFixture
    private lateinit var metadata: PhaseOneSessionMetadata
    private lateinit var current: CurrentSessionObservation
    // Each counter has one worker owner; JMH joins workers before iteration teardown.
    private var reads = 0L
    private var writes = 0L
    private var revision = 0

    /** The regression workload's 100 channels and 20,000 events. */
    @Setup
    public fun setup(): Unit {
        fixture = EpgFixture(100)
        metadata = fixture.readyMetadata()
        current = checkNotNull(metadata.observation.value.currentSession)
        fixture.validate(metadata)
        publishQueryAndRetain()
        check(metadata.observation.value.event(EventId(0))?.title == "Query 0")
        check(readObservation().currentSession === current)
    }

    /** Reset progress counters outside the sampled operations. */
    @Setup(Level.Iteration)
    public fun resetProgress(): Unit {
        reads = 0
        writes = 0
    }

    /** One reader repeatedly resolves its proof and reads the immutable aggregate. */
    @Benchmark
    @Group("publication")
    @GroupThreads(1)
    public fun readObservation(): SessionObservation {
        check(metadata.resolveGeneration(current) === fixture.generation)
        val observation = checkNotNull(metadata.currentObservation(fixture.generation, current))
        reads += 1
        return observation
    }

    /** One writer publishes a changed query result, then scans the fixed retention window. */
    @Benchmark
    @Group("publication")
    @GroupThreads(1)
    public fun publishQueryAndRetain(): Unit {
        val query = checkNotNull(metadata.beginEpgQuery(fixture.generation, ChannelId(0)))
        check(metadata.applySuccessfulEpgQuery(
            fixture.generation, query, fixture.to, fixture.queries[revision],
        ) == EpgQueryAcceptance.APPLIED)
        revision = 1 - revision
        // Keep guide size stable rather than progressively deleting the measured workload.
        metadata.retainEpgEvents(fixture.generation, fixture.from, fixture.to)
        writes += 1
    }

    /** Check both workers progressed and retention did not shrink the fixture. */
    @TearDown(Level.Iteration)
    public fun validate(): Unit {
        check(reads > 0 && writes > 0)
        fixture.validate(metadata)
        check(metadata.observation.value.event(EventId(0))?.title == "Query ${1 - revision}")
    }
}
