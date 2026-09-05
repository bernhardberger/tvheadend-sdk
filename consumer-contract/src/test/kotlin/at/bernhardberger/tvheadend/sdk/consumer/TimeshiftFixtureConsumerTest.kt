package at.bernhardberger.tvheadend.sdk.consumer

import at.bernhardberger.tvheadend.sdk.media3.LiveTimeshiftState
import at.bernhardberger.tvheadend.sdk.media3.TimeshiftContentSeekResult
import at.bernhardberger.tvheadend.sdk.media3.TimeshiftPlaybackPosition
import at.bernhardberger.tvheadend.sdk.media3.testing.TimeshiftTestFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class TimeshiftFixtureConsumerTest {
    @Test
    fun `public fixture retains selection and fences an in-flight external consumer reply`() = runTest {
        val fixture = TimeshiftTestFixture(120.seconds)
        fixture.updateHistory(10.seconds, 100.seconds)
        val timeline = (fixture.state.value as LiveTimeshiftState.Available).timeline!!
        val target = timeline.select(40.seconds)!!
        val paused = fixture.playbackPosition(40.seconds) as TimeshiftPlaybackPosition.Estimate
        fixture.updateHistory(20.seconds, 110.seconds, readerBehindLive = 0.seconds)
        val accepted = fixture.seek(target) { fixture.completed(readerReached = 39.seconds) }
            as TimeshiftContentSeekResult.Completed
        assertEquals(39.seconds, accepted.readerReached!!.position)
        assertEquals(40.seconds, paused.target.position)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = async {
            fixture.seek(target) {
                entered.complete(Unit)
                release.await()
                fixture.completed(readerReached = 40.seconds)
            }
        }
        entered.await()
        fixture.endSubscription()
        release.complete(Unit)
        assertSame(TimeshiftContentSeekResult.Replaced, pending.await())
        fixture.replaceSubscription()
        fixture.updateHistory(0.seconds, 100.seconds)
        assertSame(TimeshiftContentSeekResult.Replaced, fixture.seek(target))
        val next = (fixture.state.value as LiveTimeshiftState.Available).timeline!!.select(40.seconds)!!
        fixture.updateHistory(50.seconds, 140.seconds)
        assertSame(TimeshiftContentSeekResult.Expired, fixture.seek(next) { error("Expired target dispatched") })
    }
}
