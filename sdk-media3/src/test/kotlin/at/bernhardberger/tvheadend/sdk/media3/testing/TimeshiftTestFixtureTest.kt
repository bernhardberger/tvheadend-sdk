package at.bernhardberger.tvheadend.sdk.media3.testing

import at.bernhardberger.tvheadend.sdk.media3.LiveTimeshiftState
import at.bernhardberger.tvheadend.sdk.media3.TimeshiftCommandResult
import at.bernhardberger.tvheadend.sdk.media3.TimeshiftContentSeekResult
import at.bernhardberger.tvheadend.sdk.media3.TimeshiftPlaybackPosition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TimeshiftTestFixtureTest {
    private fun TimeshiftTestFixture.timeline() = (state.value as LiveTimeshiftState.Available).timeline!!

    @Test
    fun `history advances without changing retained targets and grant is not history`() = runTest {
        val fixture = TimeshiftTestFixture(120.seconds)
        assertNull((fixture.state.value as LiveTimeshiftState.Available).timeline)
        fixture.updateHistory(10.seconds, 100.seconds)
        val selected = fixture.timeline().select(40.seconds)!!
        fixture.updateHistory(20.seconds, 110.seconds)
        val result = fixture.seek(selected) {
            assertSame(selected, it)
            fixture.completed(readerReached = 39.seconds)
        } as TimeshiftContentSeekResult.Completed
        assertEquals(39.seconds, result.readerReached!!.position)
        assertSame(TimeshiftCommandResult.ACCEPTED, (fixture.seek(result.readerReached) as TimeshiftContentSeekResult.Completed).command)
        fixture.updateHistory(50.seconds, 140.seconds)
        assertSame(TimeshiftContentSeekResult.Expired, fixture.seek(selected) { error("Must not dispatch") })
        fixture.updateHistory(null, null)
        assertSame(TimeshiftContentSeekResult.Unavailable, fixture.seek(selected))
    }

    @Test
    fun `replacement fences targets and late outcomes without dispatching to successor`() = runTest {
        val fixture = TimeshiftTestFixture(120.seconds)
        fixture.updateHistory(0.seconds, 100.seconds)
        val target = fixture.timeline().select(40.seconds)!!
        val release = CompletableDeferred<TimeshiftContentSeekResult.Completed>()
        val result = async { fixture.seek(target) { release.await() } }
        runCurrent()
        val oldResult = fixture.completed(readerReached = 40.seconds)
        fixture.replaceSubscription()
        fixture.updateHistory(0.seconds, 100.seconds)
        release.complete(oldResult)
        assertSame(TimeshiftContentSeekResult.Replaced, result.await())
        assertSame(TimeshiftContentSeekResult.Replaced, fixture.seek(target) { error("Must not dispatch") })
        val foreign = TimeshiftTestFixture(120.seconds)
        assertSame(TimeshiftContentSeekResult.Replaced, foreign.seek(fixture.timeline().select(40.seconds)!!))
    }

    @Test
    fun `dispatch remains suspended for caller ordering tests and cancellation propagates`() = runTest {
        val fixture = TimeshiftTestFixture(120.seconds)
        fixture.updateHistory(0.seconds, 100.seconds)
        val target = fixture.timeline().select(40.seconds)!!
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = async {
            fixture.seek(target) {
                entered.complete(Unit)
                release.await()
                fixture.completed()
            }
        }
        entered.await()
        assertTrue(pending.isActive)
        pending.cancel()
        pending.join()
        assertTrue(pending.isCancelled)
        assertTrue(fixture.state.value is LiveTimeshiftState.Available)
        assertNull((fixture.seek(target) as TimeshiftContentSeekResult.Completed).readerReached)
    }

    @Test
    fun `reader movement does not change scripted displayed content or make it seekable`() = runTest {
        val fixture = TimeshiftTestFixture(120.seconds)
        fixture.updateHistory(0.seconds, 100.seconds, readerBehindLive = 0.seconds)
        val paused = fixture.playbackPosition(20.seconds) as TimeshiftPlaybackPosition.Estimate
        fixture.updateHistory(30.seconds, 130.seconds, readerBehindLive = 0.seconds, serverPaused = true)
        assertEquals(20.seconds, paused.target.position)
        assertSame(TimeshiftContentSeekResult.Expired, fixture.seek(paused.target))
        assertSame(TimeshiftPlaybackPosition.Unavailable, fixture.playbackPosition(null))
        fixture.endSubscription()
        assertSame(TimeshiftPlaybackPosition.Unavailable, fixture.playbackPosition(20.seconds))
        assertSame(TimeshiftContentSeekResult.Replaced, fixture.seek(paused.target))
    }

    @Test
    fun `uncertain nonterminal and terminal outcomes retain public classification`() = runTest {
        val fixture = TimeshiftTestFixture(120.seconds)
        fixture.updateHistory(0.seconds, 100.seconds)
        val target = fixture.timeline().select(40.seconds)!!
        val timeout = fixture.seek(target) { fixture.completed(TimeshiftCommandResult.TIMEOUT) }
            as TimeshiftContentSeekResult.Completed
        assertTrue(timeout.command.isOutcomeUncertain)
        assertNull(timeout.readerReached)
        val terminal = fixture.seek(target) { fixture.completed(TimeshiftCommandResult.SUBSCRIPTION_ENDED) }
            as TimeshiftContentSeekResult.Completed
        assertTrue(terminal.command.isTerminal)
        assertSame(LiveTimeshiftState.Unavailable, fixture.state.value)
        assertSame(TimeshiftContentSeekResult.Replaced, fixture.seek(target))
    }

    @Test
    fun `reply constructed after end is fenced and late history stays unavailable`() = runTest {
        val fixture = TimeshiftTestFixture(120.seconds)
        fixture.updateHistory(0.seconds, 100.seconds)
        val target = fixture.timeline().select(40.seconds)!!
        val release = CompletableDeferred<Unit>()
        val pending = async {
            fixture.seek(target) {
                release.await()
                fixture.completed(readerReached = 39.seconds)
            }
        }
        runCurrent()
        fixture.endSubscription()
        fixture.updateHistory(0.seconds, 110.seconds)
        release.complete(Unit)
        assertSame(TimeshiftContentSeekResult.Replaced, pending.await())
        assertSame(LiveTimeshiftState.Unavailable, fixture.state.value)
    }

    @Test
    fun `terminal reply commits before late caller cancellation is propagated`() = runTest {
        val fixture = TimeshiftTestFixture(120.seconds)
        fixture.updateHistory(0.seconds, 100.seconds)
        val pending = async {
            fixture.seek(fixture.timeline().select(40.seconds)!!) {
                currentCoroutineContext().cancel()
                fixture.completed(TimeshiftCommandResult.SUBSCRIPTION_ENDED)
            }
        }
        pending.join()
        assertTrue(pending.isCancelled)
        assertSame(LiveTimeshiftState.Unavailable, fixture.state.value)
    }

    @Test
    fun `reader shift is normalized and buffered history need not be selectable`() {
        val fixture = TimeshiftTestFixture(120.seconds)
        fixture.updateHistory(null, 100.seconds, readerBehindLive = 90.seconds)
        assertNull((fixture.state.value as LiveTimeshiftState.Available).positionBehindLive)
        fixture.updateHistory((-10).seconds, 20.seconds, readerBehindLive = 90.seconds)
        val available = fixture.state.value as LiveTimeshiftState.Available
        assertEquals(30.seconds, available.bufferedDuration)
        assertEquals(30.seconds, available.positionBehindLive)
        assertNull(available.timeline)
    }
}
