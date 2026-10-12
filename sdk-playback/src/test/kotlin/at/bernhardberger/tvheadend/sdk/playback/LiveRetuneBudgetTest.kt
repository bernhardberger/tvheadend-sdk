@file:OptIn(ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.playback

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class LiveRetuneBudgetTest {
    @Test
    fun `default schedule admits five one-based attempts then exhausts`() {
        val budget = LiveRetuneBudget(timeSource = TestTimeSource())

        listOf(0, 2, 5, 10, 20).forEachIndexed { index, seconds ->
            assertEquals(LiveRetuneDecision.Admitted(index + 1, seconds.seconds), budget.nextAttempt())
        }
        repeat(3) { assertSame(LiveRetuneDecision.Exhausted, budget.nextAttempt()) }
    }

    @Test
    fun `refill is inclusive and exhausted calls do not move admission mark`() {
        val clock = TestTimeSource()
        val budget = LiveRetuneBudget(delays = listOf(2.seconds), timeSource = clock)
        val first = LiveRetuneDecision.Admitted(1, 2.seconds)

        assertEquals(first, budget.nextAttempt())
        clock += 30.seconds
        assertSame(LiveRetuneDecision.Exhausted, budget.nextAttempt())
        clock += 30.seconds - 1.nanoseconds
        assertSame(LiveRetuneDecision.Exhausted, budget.nextAttempt())
        clock += 1.nanoseconds
        assertEquals(first, budget.nextAttempt())
    }

    @Test
    fun `refill measures previous admission rather than first admission or healthy playback`() {
        val clock = TestTimeSource()
        val budget = LiveRetuneBudget(timeSource = clock)

        assertEquals(LiveRetuneDecision.Admitted(1, 0.seconds), budget.nextAttempt())
        clock += 59.seconds
        assertEquals(LiveRetuneDecision.Admitted(2, 2.seconds), budget.nextAttempt())
        clock += 59.seconds
        assertEquals(LiveRetuneDecision.Admitted(3, 5.seconds), budget.nextAttempt())
        clock += 60.seconds
        assertEquals(LiveRetuneDecision.Admitted(1, 0.seconds), budget.nextAttempt())
        clock += 61.seconds
        assertEquals(LiveRetuneDecision.Admitted(1, 0.seconds), budget.nextAttempt())
    }

    @Test
    fun `custom interval and explicit reset restart numbering immediately`() {
        val clock = TestTimeSource()
        val budget = LiveRetuneBudget(listOf(3.seconds), resetAfter = 4.seconds, timeSource = clock)
        val first = LiveRetuneDecision.Admitted(1, 3.seconds)

        assertEquals(first, budget.nextAttempt())
        assertSame(LiveRetuneDecision.Exhausted, budget.nextAttempt())
        budget.reset()
        assertEquals(first, budget.nextAttempt())
        clock += 4.seconds
        assertEquals(first, budget.nextAttempt())
        budget.reset()
        budget.reset()
        assertEquals(first, budget.nextAttempt())
    }

    @Test
    fun `empty schedule never admits even after reset or elapsed interval`() {
        val clock = TestTimeSource()
        val budget = LiveRetuneBudget(emptyList(), timeSource = clock)

        assertSame(LiveRetuneDecision.Exhausted, budget.nextAttempt())
        clock += 60.seconds
        assertSame(LiveRetuneDecision.Exhausted, budget.nextAttempt())
        budget.reset()
        assertSame(LiveRetuneDecision.Exhausted, budget.nextAttempt())
    }

    @Test
    fun `schedule is a defensive snapshot`() {
        val delays = mutableListOf(0.seconds, 3.seconds)
        val budget = LiveRetuneBudget(delays, timeSource = TestTimeSource())
        delays[0] = 99.seconds
        delays.clear()

        assertEquals(LiveRetuneDecision.Admitted(1, 0.seconds), budget.nextAttempt())
        assertEquals(LiveRetuneDecision.Admitted(2, 3.seconds), budget.nextAttempt())
        assertSame(LiveRetuneDecision.Exhausted, budget.nextAttempt())
    }

    @Test
    fun `invalid durations are rejected without rendering supplied values`() {
        listOf((-1).nanoseconds, Duration.INFINITE, -Duration.INFINITE).forEach { delay ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                LiveRetuneBudget(listOf(delay))
            }
            assertEquals("Retune delays must be finite and non-negative", failure.message)
        }
        listOf(Duration.ZERO, (-1).nanoseconds, Duration.INFINITE, -Duration.INFINITE).forEach { interval ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                LiveRetuneBudget(resetAfter = interval)
            }
            assertEquals("Retune reset interval must be finite and positive", failure.message)
        }
    }

    @Test
    fun `concurrent claims neither duplicate attempts nor exceed budget`() {
        val budget = LiveRetuneBudget(List(17) { it.seconds }, timeSource = TestTimeSource())
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = List(8) {
                executor.submit<List<LiveRetuneDecision>> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    List(4) { budget.nextAttempt() }
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.flatMap { it.get(10, TimeUnit.SECONDS) }
            val admitted = results.filterIsInstance<LiveRetuneDecision.Admitted>().sortedBy { it.attempt }
            assertEquals((1..17).map { LiveRetuneDecision.Admitted(it, (it - 1).seconds) }, admitted)
            assertEquals(15, results.count { it == LiveRetuneDecision.Exhausted })
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }
}
