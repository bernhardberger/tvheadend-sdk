@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.metadata

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EpgCredit
import at.bernhardberger.tvheadend.sdk.core.EpgCreditRole
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/** Ownership regressions for both conversion entry points, independent of snapshot construction. */
internal class EpgListOwnershipTest {
    private val start = Instant.fromEpochSeconds(1_700_000_000)
    private val stop = Instant.fromEpochSeconds(1_700_001_800)
    private val shapes = listOf(null, emptyList(), listOf("one"), listOf("one", "two", "three"))

    @Test
    fun `live gateway category and keyword exposures reject mutation`() {
        for (values in shapes) {
            val event = live(values, values)
            assertMutationRejected(event.categories)
            assertMutationRejected(event.keywords)
        }
    }

    @Test
    fun `query gateway category and keyword exposures reject mutation`() {
        for (values in shapes) {
            val event = query(values, values)
            assertMutationRejected(event.categories)
            assertMutationRejected(event.keywords)
        }
    }

    @Test
    fun `live and query conversions isolate caller aliases before and after reduction`() {
        for (isQuery in listOf(false, true)) {
            for (values in shapes) {
                val categories = values?.toMutableList()
                val keywords = values?.toMutableList()
                val credits = mutableListOf(EpgCredit("Synthetic performer", EpgCreditRole.ACTOR))
                val event = live(categories, keywords, credits)
                val query = query(categories, keywords, credits)
                categories?.add("before reduction")
                keywords?.clear()
                credits.clear()

                val reduced = checkNotNull(if (isQuery) ReducedEpgEvent.fromQuery(query) else ReducedEpgEvent.fromEvent(event))
                val public = checkNotNull(reduced.toPublicOrNull())
                val equal = EpgEvent.create(
                    EventId(1), ChannelId(1), start, stop, categories = values, keywords = values,
                    credits = listOf(EpgCredit("Synthetic performer", EpgCreditRole.ACTOR)),
                )
                val reducedHash = reduced.hashCode()
                val publicHash = public.hashCode()
                val membership = hashSetOf(public)
                categories?.clear()
                keywords?.add("after reduction")

                assertEquals(values, reduced.categories)
                assertEquals(values, reduced.keywords)
                assertEquals(equal, public)
                assertEquals(equal.hashCode(), publicHash)
                assertEquals(reducedHash, reduced.hashCode())
                assertEquals(publicHash, public.hashCode())
                assertTrue(equal in membership)
                assertSame(public, reduced.toPublicOrNull())
                assertMutationRejected(reduced.categories)
                assertMutationRejected(reduced.keywords)
                assertMutationRejected(public.categories)
                assertMutationRejected(public.keywords)
                assertEquals(equal, public)
                assertEquals(publicHash, public.hashCode())
                // Credits keep their existing defensive reducer copy in this slice.
                assertNotSame(if (isQuery) query.credits else event.credits, reduced.credits)
            }
        }
    }

    @Test
    fun `conversions reuse frozen gateway categories and keywords`() {
        for (values in shapes) {
            val event = live(values, values)
            val query = query(values, values)
            val fromEvent = checkNotNull(ReducedEpgEvent.fromEvent(event))
            val fromQuery = checkNotNull(ReducedEpgEvent.fromQuery(query))
            assertSame(event.categories, fromEvent.categories)
            assertSame(event.keywords, fromEvent.keywords)
            assertSame(query.categories, fromQuery.categories)
            assertSame(query.keywords, fromQuery.keywords)
            assertEquals(fromEvent, fromQuery)
            assertEquals(fromEvent.hashCode(), fromQuery.hashCode())
        }
    }

    @Test
    fun `public factory still copies caller lists and preserves cached hashes`() {
        for (values in shapes) {
            val categories = values?.toMutableList()
            val keywords = values?.toMutableList()
            val public = EpgEvent.create(EventId(1), ChannelId(1), start, stop,
                categories = categories, keywords = keywords)
            val hash = public.hashCode()
            categories?.add("changed")
            keywords?.clear()
            assertEquals(values, public.categories)
            assertEquals(values, public.keywords)
            assertEquals(hash, public.hashCode())
            assertMutationRejected(public.categories)
            assertMutationRejected(public.keywords)
        }
    }

    private fun live(categories: List<String>?, keywords: List<String>?, credits: List<EpgCredit>? = null) =
        GatewayEpgEvent(EventId(1), ChannelId(1), start, stop,
            categories = categories, keywords = keywords, credits = credits)

    private fun query(categories: List<String>?, keywords: List<String>?, credits: List<EpgCredit>? = null) =
        GatewayEpgQueryEvent(EventId(1), ChannelId(1), start, stop,
            categories = categories, keywords = keywords, credits = credits)

    private fun assertMutationRejected(values: List<String>?) {
        if (values == null) return
        val expected = values.toList()
        fun rejected(operation: () -> Unit) {
            val failure = assertThrows(RuntimeException::class.java) { operation() }
            // Kotlin's empty immutable list can reject the mutable cast itself.
            assertTrue(failure is UnsupportedOperationException || failure is ClassCastException)
            assertEquals(expected, values)
        }
        rejected { (values as MutableList<String>).add("mutation") }
        if (values.isNotEmpty()) rejected { (values as MutableList<String>)[0] = "mutation" }
    }
}
