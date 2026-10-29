@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.metadata

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EpgCoverage
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/** Actual reducer work with installed carryover; synthetic JVM samples, never device predictions. */
internal class EpgCarryoverProfileTest {
    @Test
    fun `profile advancing live bounds with installed and empty carryover`() {
        val allocation = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(allocation.isThreadAllocatedMemorySupported)
        allocation.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        for (size in listOf(5_000, 100_000)) {
            val fixture = CarryoverFixture(size)
            for (installed in listOf(true, false)) {
                println("CARRYOVER seedSize=$size installed=$installed channels=143 live=143 history=1 " +
                    "capacity=${size + CHANNELS} updates=512 warmups=30 samples=12")
                repeat(42) { iteration ->
                    // Installation, message creation, previous publication and assertions are untimed.
                    val reducer = fixture.newReducer(installed)
                    val before = reducer.snapshot()
                    before.prepareForObservation()
                    fixture.verify(before, installed, appliedUpdates = 0)

                    val reduceBeforeBytes = allocation.getThreadAllocatedBytes(thread)
                    val reduceBefore = System.nanoTime()
                    for (update in fixture.updates) reducer.accept(update)
                    val reduceNanos = System.nanoTime() - reduceBefore
                    val reduceBytes = allocation.getThreadAllocatedBytes(thread) - reduceBeforeBytes

                    val snapshotBeforeBytes = allocation.getThreadAllocatedBytes(thread)
                    val snapshotBefore = System.nanoTime()
                    val snapshot = reducer.snapshot()
                    val snapshotNanos = System.nanoTime() - snapshotBefore
                    val snapshotBytes = allocation.getThreadAllocatedBytes(thread) - snapshotBeforeBytes

                    val indexesBeforeBytes = allocation.getThreadAllocatedBytes(thread)
                    val indexesBefore = System.nanoTime()
                    snapshot.prepareForObservation()
                    val indexesNanos = System.nanoTime() - indexesBefore
                    val indexesBytes = allocation.getThreadAllocatedBytes(thread) - indexesBeforeBytes

                    fixture.verify(snapshot, installed, appliedUpdates = UPDATES)
                    fixture.verify(before, installed, appliedUpdates = 0)
                    assertFalse(reducer.lastEventRefusedForCapacity)
                    assertSame(snapshot, reducer.snapshot())
                    if (iteration >= 30) {
                        println("sample=${iteration - 30} reduce512Ns=$reduceNanos snapshotNs=$snapshotNanos " +
                            "indexesNs=$indexesNanos reduce512Bytes=$reduceBytes " +
                            "snapshotBytes=$snapshotBytes indexesBytes=$indexesBytes")
                    }
                }
            }
        }
    }

    @Test
    fun `each round robin update removes exactly one carryover and preserves publication order`() {
        val fixture = CarryoverFixture(CHANNELS * 4)
        val reducer = fixture.newReducer(installed = true)
        fixture.verify(reducer.snapshot(), installed = true, appliedUpdates = 0)
        fixture.updates.forEachIndexed { index, update ->
            assertTrue(reducer.accept(update))
            assertFalse(reducer.lastEventRefusedForCapacity)
            fixture.verify(reducer.snapshot(), installed = true, appliedUpdates = index + 1)
        }
    }

    @Test
    fun `source visit estimates describe the fixture rather than elapsed time`() {
        for ((size, expectedGlobal, expectedChannel) in listOf(
            Triple(5_000, 2_429_184L, 17_227L),
            Triple(100_000, 51_069_184L, 357_382L),
        )) {
            // Every update crosses the earliest retained start on its channel, so the baseline
            // global removeIf visits R - i entries. A channel-only scan would visit its current
            // bucket, including the one entry removed. Neither total is a measured runtime counter.
            val globalVisits = (0 until UPDATES).sumOf { size.toLong() - it }
            val channelSizes = IntArray(CHANNELS)
            repeat(size) { channelSizes[it % CHANNELS] += 1 }
            var channelVisits = 0L
            repeat(UPDATES) { index ->
                val channel = index % CHANNELS
                channelVisits += channelSizes[channel]
                channelSizes[channel] -= 1
            }
            assertEquals(expectedGlobal, globalVisits)
            assertEquals(expectedChannel, channelVisits)
            println("CARRYOVER sourceModel seedSize=$size updates=512 " +
                "globalPredicateVisits=$globalVisits hypotheticalChannelVisits=$channelVisits")
        }
    }

    private class CarryoverFixture(private val size: Int) {
        private val generation = GatewayGeneration()
        private val channels = List(CHANNELS) { ChannelId(it.toLong()) }
        private val seed = List(size) { index ->
            val start = BASE + (index / CHANNELS + 1) * SLOT
            EpgEvent.create(EventId(index.toLong()), channels[index % CHANNELS], instant(start),
                instant(start + SLOT), title = "Carryover $index")
        }
        private val historical = EpgEvent.create(EventId((size + CHANNELS).toLong()), channels.first(),
            instant(BASE - SLOT), instant(BASE), title = "History")
        val updates = List(UPDATES) { index ->
            val channel = index % CHANNELS
            // Initial live stop equals the first seed start, which the exclusive live bound keeps.
            // Each update passes exactly one subsequent start by one second.
            MetadataEvent.EventUpdated(generation, live(channel, BASE + (index / CHANNELS + 1) * SLOT + 1))
        }

        fun newReducer(installed: Boolean): EpgReducer = EpgReducer(size + CHANNELS).also { reducer ->
            channels.forEach { channel ->
                reducer.accept(MetadataEvent.ChannelAdded(generation, GatewayChannelMetadata(
                    channel, name = null, uuid = null, number = null, numberMinor = null,
                    icon = null, currentEventId = null, nextEventId = null, services = null, tagIds = null,
                )))
            }
            reducer.accept(MetadataEvent.EventAdded(generation, GatewayEpgEvent(
                historical.id, historical.channelId, historical.start, historical.stop, title = historical.title,
            )))
            reducer.accept(MetadataEvent.EventDeleted(generation, historical.id), serverNow = instant(BASE))
            repeat(CHANNELS) { reducer.accept(MetadataEvent.EventAdded(generation, live(it, BASE + SLOT))) }
            // The empty control uses the same capacity, messages, history and installation lifecycle.
            reducer.installCarryover(if (installed) seed else emptyList(), channels.associateWith { instant(BASE) })
        }

        fun verify(snapshot: EpgSnapshot, installed: Boolean, appliedUpdates: Int) {
            val expectedLive = channels.mapIndexed { index, channel ->
                val count = if (appliedUpdates <= index) 0 else (appliedUpdates - 1 - index) / CHANNELS + 1
                val stop = if (count == 0) BASE + SLOT else BASE + count * SLOT + 1
                EpgEvent.create(EventId((size + index).toLong()), channel, instant(BASE), instant(stop),
                    title = "Live $index")
            }
            val remaining = if (installed) seed.drop(appliedUpdates) else emptyList()
            // Compare exact contents and global insertion order, not just cardinality or a hash.
            val expectedEvents = expectedLive + remaining
            assertEquals(expectedEvents, snapshot.events)
            assertEquals(expectedEvents.associateBy { it.id }, snapshot.eventsById)
            assertEquals(expectedEvents.groupBy { it.channelId }, snapshot.eventsByChannel)
            assertEquals(listOf(historical), snapshot.historicalEvents)
            assertEquals(mapOf(channels.first() to listOf(historical)), snapshot.historyByChannel)
            assertEquals(expectedLive.map { event ->
                EpgCoverage.create(requireNotNull(event.channelId), event.start, event.stop)
            }, snapshot.coverages)
        }

        private fun live(channel: Int, stop: Long): GatewayEpgEvent = GatewayEpgEvent(
            EventId((size + channel).toLong()), channels[channel], instant(BASE), instant(stop), title = "Live $channel",
        )
    }

    private companion object {
        const val CHANNELS = 143
        const val UPDATES = 512
        const val BASE = 1_700_000_000L
        const val SLOT = 1_800L

        fun instant(seconds: Long): Instant = Instant.fromEpochSeconds(seconds)
    }
}
