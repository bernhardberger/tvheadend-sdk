@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.metadata

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/** Synthetic JVM publication phase measurements; no wall-time pass/fail thresholds. */
internal class EpgSnapshotProfileTest {
    @Test
    fun `profile changed coverage and unchanged guide snapshot phases`() {
        val allocation = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(allocation.isThreadAllocatedMemorySupported)
        allocation.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        val generation = GatewayGeneration()
        for (size in listOf(5_000, 100_000)) {
            val reducer = EpgReducer()
            repeat(100) { index ->
                reducer.accept(MetadataEvent.ChannelAdded(generation, GatewayChannelMetadata(
                    ChannelId(index.toLong()), name = null, uuid = null, number = null, numberMinor = null,
                    icon = null, currentEventId = null, nextEventId = null, services = null, tagIds = null,
                )))
            }
            fun event(index: Int, title: String = "Programme $index"): GatewayEpgEvent = GatewayEpgEvent(
                id = EventId(index.toLong()), channelId = ChannelId((index % 100).toLong()),
                start = Instant.fromEpochSeconds(1_700_000_000L + index / 100 * 1800L),
                stop = Instant.fromEpochSeconds(1_700_000_000L + index / 100 * 1800L + 1800L),
                title = title,
            )
            repeat(size) { reducer.accept(MetadataEvent.EventAdded(generation, event(it))) }
            reducer.snapshot().prepareForObservation()
            for (workload in listOf("one-event", "coverage-only", "unchanged")) {
                val samples = mutableListOf<List<Long>>()
                repeat(40) { iteration ->
                    when (workload) {
                        "one-event" -> reducer.accept(MetadataEvent.EventUpdated(generation, event(size / 2, "Changed $iteration")))
                        "coverage-only" -> reducer.recordSuccessfulQuery(ChannelId(0), Instant.fromEpochSeconds(1_800_000_000L + iteration))
                    }
                    val beforeBytes = allocation.getThreadAllocatedBytes(thread)
                    val before = System.nanoTime()
                    val snapshot = reducer.snapshot()
                    val snapshotNanos = System.nanoTime() - before
                    val snapshotBytes = allocation.getThreadAllocatedBytes(thread) - beforeBytes
                    val indexBeforeBytes = allocation.getThreadAllocatedBytes(thread)
                    val indexBefore = System.nanoTime()
                    snapshot.prepareForObservation()
                    val indexNanos = System.nanoTime() - indexBefore
                    val indexBytes = allocation.getThreadAllocatedBytes(thread) - indexBeforeBytes
                    assertEquals(size, snapshot.events.size)
                    assertSame(snapshot, reducer.snapshot())
                    assertEquals(size, snapshot.eventsById.size)
                    assertEquals(size, snapshot.eventsByChannel.values.sumOf { it.size })
                    if (iteration >= 20) samples += listOf(snapshotNanos, indexNanos, snapshotBytes, indexBytes)
                }
                println("SNAPSHOT size=$size workload=$workload warmups=20 samples=20 channels=100 history=0 carryover=0")
                samples.forEachIndexed { index, values -> println("sample=$index snapshotNs=${values[0]} indexesNs=${values[1]} snapshotBytes=${values[2]} indexesBytes=${values[3]}") }
                listOf("snapshotNs", "indexesNs", "snapshotBytes", "indexesBytes").forEachIndexed { index, name ->
                    val values = samples.map { it[index] }.sorted()
                    println("$name min=${values.first()} median=${(values[9] + values[10]) / 2} max=${values.last()}")
                }
            }
        }
    }
}
