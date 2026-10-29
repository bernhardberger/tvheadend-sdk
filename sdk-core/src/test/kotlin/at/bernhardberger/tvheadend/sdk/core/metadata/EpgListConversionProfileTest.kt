@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.metadata

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.Collections
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/**
 * Same production constructors and conversions before/after a change, including gateway freeze cost.
 * JVM allocation traffic and wall time only: no public projection, snapshot, wire decode or device claims.
 */
internal class EpgListConversionProfileTest {
    private val start = Instant.fromEpochSeconds(1_700_000_000)
    private val stop = Instant.fromEpochSeconds(1_700_001_800)
    private val channel = ChannelId(1)

    @Test
    fun `profile actual gateway construction and reducer conversion without performance thresholds`() {
        val allocation = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(allocation.isThreadAllocatedMemorySupported)
        allocation.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        val shapes = listOf(
            Triple("null", null, null),
            Triple("empty", emptyList(), emptyList()),
            Triple("singleton", listOf("category"), listOf("keyword")),
            Triple("multi", frozen("Synthetic", "Category"), frozen("benchmark", "programme", "keyword")),
        )
        for (size in listOf(5_000, 100_000)) {
            val ids = List(size) { EventId(it + 1L) }
            for ((shape, categories, keywords) in shapes) {
                for (isQuery in listOf(false, true)) {
                    val samples = mutableListOf<LongArray>()
                    repeat(50) { iteration ->
                        val gatewayBytesBefore = allocation.getThreadAllocatedBytes(thread)
                        val gatewayBefore = System.nanoTime()
                        // Keep both real gateway and reduced results alive through validation. Each
                        // measured phase includes one size-N result list, identical in both revisions.
                        val events = if (isQuery) null else List(size) { index ->
                            GatewayEpgEvent(ids[index], channel, start, stop,
                                categories = categories, keywords = keywords)
                        }
                        val queries = if (!isQuery) null else List(size) { index ->
                            GatewayEpgQueryEvent(ids[index], channel, start, stop,
                                categories = categories, keywords = keywords)
                        }
                        val gatewayNs = System.nanoTime() - gatewayBefore
                        val gatewayBytes = allocation.getThreadAllocatedBytes(thread) - gatewayBytesBefore
                        val reduceBytesBefore = allocation.getThreadAllocatedBytes(thread)
                        val reduceBefore = System.nanoTime()
                        val reduced = if (isQuery) {
                            checkNotNull(queries).map { checkNotNull(ReducedEpgEvent.fromQuery(it)) }
                        } else {
                            checkNotNull(events).map { checkNotNull(ReducedEpgEvent.fromEvent(it)) }
                        }
                        val reduceNs = System.nanoTime() - reduceBefore
                        val reduceBytes = allocation.getThreadAllocatedBytes(thread) - reduceBytesBefore

                        // Count observed identity reuse rather than assuming an implementation's
                        // array/wrapper allocation count. All checks and reporting are outside timing.
                        var sharedCategories = 0L
                        var sharedKeywords = 0L
                        reduced.forEachIndexed { index, event ->
                            check(event.id == ids[index])
                            check(event.categories == categories && event.keywords == keywords)
                            check(event.credits == null)
                            val gatewayCategories = if (isQuery) queries!![index].categories else events!![index].categories
                            val gatewayKeywords = if (isQuery) queries!![index].keywords else events!![index].keywords
                            if (gatewayCategories != null && gatewayCategories === event.categories) sharedCategories++
                            if (gatewayKeywords != null && gatewayKeywords === event.keywords) sharedKeywords++
                        }
                        assertEquals(size, reduced.size)
                        if (iteration >= 30) samples += longArrayOf(
                            gatewayNs, reduceNs, gatewayBytes, reduceBytes, sharedCategories, sharedKeywords,
                        )
                    }
                    println("EPG_LISTS size=$size shape=$shape query=$isQuery warmups=30 samples=20 " +
                        "categoryElements=${size.toLong() * (categories?.size ?: 0)} " +
                        "keywordElements=${size.toLong() * (keywords?.size ?: 0)} credits=0 " +
                        "resultListsPerSample=2 allocationScope=callerThread")
                    samples.forEachIndexed { index, values ->
                        println("sample=$index gatewayNs=${values[0]} reduceNs=${values[1]} " +
                            "gatewayBytes=${values[2]} reduceBytes=${values[3]} " +
                            "sharedCategories=${values[4]} sharedKeywords=${values[5]}")
                    }
                    val metrics = listOf("gatewayNs", "reduceNs", "gatewayBytes", "reduceBytes")
                    metrics.forEachIndexed { index, name -> report(name, samples.map { it[index] }) }
                    report("combinedNs", samples.map { it[0] + it[1] })
                    report("combinedBytes", samples.map { it[2] + it[3] })
                }
            }
        }
    }

    private fun frozen(vararg values: String): List<String> = Collections.unmodifiableList(ArrayList(values.toList()))

    private fun report(name: String, samples: List<Long>) {
        val values = samples.sorted()
        val median = (values[(values.size - 1) / 2] + values[values.size / 2]) / 2
        println("$name min=${values.first()} median=$median max=${values.last()}")
    }
}
