@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.benchmarks

import at.bernhardberger.tvheadend.sdk.core.EpgCoveragePolicy
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgUpdate
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.session.PhaseOneSessionMetadata
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Group
import org.openjdk.jmh.annotations.GroupThreads
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.infra.Blackhole
import kotlin.time.Instant

/** Replays a bounded history lifecycle while another worker browses immutable observations. */
@State(Scope.Group)
@BenchmarkMode(Mode.SampleTime)
@Threads(2)
public class MixedEpgBenchmarks {
    /** Resident guide sizes; four additional events exercise history without shrinking the guide. */
    @Param("100", "500")
    public var channelCount: Int = 0

    /** Last published observations retained independently of the concurrently browsing worker. */
    @Param("0", "8")
    public var retainedSnapshots: Int = 0

    private lateinit var fixture: EpgFixture
    private lateinit var metadata: PhaseOneSessionMetadata
    private lateinit var changed: List<List<MetadataEvent.EventUpdated>>
    private lateinit var unchanged: List<MetadataEvent.EventUpdated>
    private lateinit var additions: List<MetadataEvent.EventAdded>
    private lateinit var deletions: List<MetadataEvent.EventDeleted>
    private lateinit var held: Array<SessionObservation?>
    private lateinit var channels: List<ChannelId>
    private lateinit var viewTimes: List<Instant>
    private val historyNow = Instant.fromEpochSeconds(0)
    private val expiredNow = Instant.fromEpochSeconds(21_600)
    // Clock and retention ring are writer-owned. The reader owns only reads.
    private var serverNow = historyNow
    private var revision = 0
    private var ringCursor = 0
    private var reads = 0L
    private var writes = 0L

    /** Validate an entire trace, including intermediate history and retained snapshot isolation. */
    @Setup
    public fun setup(): Unit {
        fixture = EpgFixture(channelCount)
        metadata = fixture.readyMetadata(PhaseOneSessionMetadata(
            epgCoveragePolicy = EpgCoveragePolicy.create(
                futureHorizon = EpgCoveragePolicy.create().futureHorizon,
                maximumRetainedEvents = fixture.eventCount + 4,
            ),
            estimatedServerTime = { serverNow },
        ))
        changed = fixture.updates.map { it.take(50) }
        unchanged = (50 until 100).map { index ->
            MetadataEvent.EventUpdated(fixture.generation,
                GatewayEpgUpdate(EventId(index.toLong()), title = "Programme $index"))
        }
        additions = List(4) { index ->
            MetadataEvent.EventAdded(fixture.generation, GatewayEpgEvent(
                EventId((fixture.eventCount + index).toLong()), ChannelId(index.toLong()),
                Instant.fromEpochSeconds(-1800), historyNow, title = "Ended programme",
            ))
        }
        deletions = additions.map { MetadataEvent.EventDeleted(fixture.generation, it.event.id) }
        held = arrayOfNulls(retainedSnapshots)
        channels = List(channelCount) { ChannelId(it.toLong()) }
        viewTimes = List(4) { Instant.fromEpochSeconds(36_000L + it * 21_600L) }
        additions.forEach(metadata::acceptMetadataDeferringEpg)
        metadata.flushDeferredMetadata()
        val before = metadata.observation.value
        updateAndDelete()
        val history = metadata.observation.value
        check(history.epgSnapshotForDisplay?.historicalEvents?.size == 4)
        check(history.epgSnapshotForDisplay?.events?.size == fixture.eventCount)
        expireHistory()
        check(metadata.observation.value.epgSnapshotForDisplay?.historicalEvents?.isEmpty() == true)
        replenish()
        check(before.event(EventId(0))?.title == "Programme 0")
        check(history.epgSnapshotForDisplay?.historicalEvents?.size == 4)
        check(metadata.observation.value.event(EventId(0))?.title == "Updated 0")
        checkNotNull(metadata.observation.value.eventAt(channels[0], viewTimes[0]))
        checkNotNull(metadata.observation.value.nextEvent(channels[0], viewTimes[0]))
        revision = 1
        repeat(3) { replayMixedTrace() }
        validateGuide()
        check(held.all { it != null })
    }

    /** Reset counters only; resident data and held observations persist across iterations. */
    @Setup(Level.Iteration)
    public fun resetProgress(): Unit {
        reads = 0
        writes = 0
    }

    /** Twelve visible channels, two public now/next lookups each, scrolling across four times. */
    @Benchmark
    @Group("mixed")
    @GroupThreads(1)
    public fun browse(blackhole: Blackhole): Unit {
        val observation = metadata.observation.value
        val time = viewTimes[(reads % viewTimes.size).toInt()]
        val firstChannel = (reads % channelCount).toInt()
        repeat(12) { offset ->
            val channel = channels[(firstChannel + offset) % channelCount]
            blackhole.consume(observation.eventAt(channel, time))
            blackhole.consume(observation.nextEvent(channel, time))
        }
        reads += 1
    }

    /** 50 changed + 50 duplicate updates, four deletions, expiry trigger, four adds; three flushes. */
    @Benchmark
    @Group("mixed")
    @GroupThreads(1)
    public fun replayMixedTrace(): Unit {
        updateAndDelete()
        expireHistory()
        replenish()
        revision = 1 - revision
        writes += 1
    }

    private fun updateAndDelete() {
        // Replay the same simulated six-hour interval, not elapsed benchmark wall-clock time.
        serverNow = historyNow
        changed[revision].forEach(metadata::acceptMetadataDeferringEpg)
        unchanged.forEach(metadata::acceptMetadataDeferringEpg)
        deletions.forEach(metadata::acceptMetadataDeferringEpg)
        publishAndHold()
    }

    private fun expireHistory() {
        serverNow = expiredNow
        metadata.acceptMetadataDeferringEpg(unchanged[0])
        publishAndHold()
    }

    private fun replenish() {
        additions.forEach(metadata::acceptMetadataDeferringEpg)
        publishAndHold()
    }

    private fun publishAndHold() {
        metadata.flushDeferredMetadata()
        if (held.isNotEmpty()) {
            held[ringCursor] = metadata.observation.value
            ringCursor = (ringCursor + 1) % held.size
        }
    }

    /** Check worker progress, stable live cardinality, duplicate content and bounded history. */
    @TearDown(Level.Iteration)
    public fun validate(): Unit {
        check(reads > 0 && writes > 0)
        validateGuide()
        check(metadata.observation.value.event(EventId(0))?.title == "Updated ${1 - revision}")
        check(metadata.observation.value.event(EventId(50))?.title == "Programme 50")
        held.filterNotNull().forEach { observation ->
            val snapshot = checkNotNull(observation.epgSnapshotForDisplay)
            check(snapshot.events.size + snapshot.historicalEvents.size in fixture.eventCount..fixture.eventCount + 4)
        }
    }

    private fun validateGuide() {
        val observation = metadata.observation.value
        checkNotNull(observation.currentSession)
        check(observation.channelCatalogForDisplay?.channels?.size == channelCount)
        check(observation.epgSnapshotForDisplay?.events?.size == fixture.eventCount + 4)
        check(observation.epgSnapshotForDisplay?.historicalEvents?.isEmpty() == true)
    }
}
