package at.bernhardberger.tvheadend.sdk.media3.testing

import at.bernhardberger.tvheadend.sdk.media3.LiveTimeshiftState
import at.bernhardberger.tvheadend.sdk.media3.TimeshiftCommandResult
import at.bernhardberger.tvheadend.sdk.media3.TimeshiftContentSeekResult
import at.bernhardberger.tvheadend.sdk.media3.TimeshiftContentTarget
import at.bernhardberger.tvheadend.sdk.media3.TimeshiftPlaybackPosition
import at.bernhardberger.tvheadend.sdk.media3.TimeshiftSeekToken
import at.bernhardberger.tvheadend.sdk.media3.TimeshiftTimeline
import at.bernhardberger.tvheadend.sdk.media3.TimeshiftWallClockMapping
import at.bernhardberger.tvheadend.sdk.media3.validateTimeshiftTarget
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration

/**
 * Host-test fixture for application timeshift controls. It needs no Player, Android runtime or server.
 * Targets are valid only within this fixture's current subscription, never a real coordinator.
 * The fixture models observations and dispatch results, not app coalescing, rendering or protocol timing.
 */
public class TimeshiftTestFixture(public val grantedPeriod: Duration) {
    init {
        require(grantedPeriod.isFinite() && grantedPeriod > Duration.ZERO)
    }

    private val lock = Any()
    private var owner: Any = Any()
    private var subscriptionOwner: Any = owner
    private var active = true
    private val mutableState = MutableStateFlow<LiveTimeshiftState>(
        LiveTimeshiftState.Available(grantedPeriod, null, null, null),
    )

    public val state: StateFlow<LiveTimeshiftState> = mutableState.asStateFlow()

    /**
     * Missing bounds remove history evidence. Late observations after end are ignored.
     * Timing scripts availability directly, without production continuity or edge-advance gating.
     */
    public fun updateHistory(
        start: Duration?,
        end: Duration?,
        readerBehindLive: Duration? = null,
        serverPaused: Boolean? = null,
        estimatedLiveEdgeTime: kotlin.time.Instant? = null,
    ): Unit = synchronized(lock) {
        require(start == null || start.isFinite())
        require(end == null || end.isFinite())
        require(readerBehindLive == null || (readerBehindLive.isFinite() && readerBehindLive >= Duration.ZERO))
        if (!active) return@synchronized
        val buffered = if (start != null && end != null && end >= start) {
            (end - start).takeIf { it.isFinite() }
        } else null
        mutableState.value = LiveTimeshiftState.Available(
            grantedPeriod,
            buffered,
            buffered?.let { readerBehindLive?.coerceAtMost(it) },
            serverPaused,
            if (start != null && end != null && start >= Duration.ZERO && end >= start) {
                TimeshiftTimeline(
                    owner, start, end,
                    estimatedLiveEdgeTime?.let { TimeshiftWallClockMapping.Estimate(owner, end, it) }
                        ?: TimeshiftWallClockMapping.Unavailable,
                    subscriptionOwner,
                )
            } else null,
        )
    }

    /** Script displayed-content evidence independently from history and reader movement. Null means unavailable. */
    public fun playbackPosition(position: Duration?, seek: TimeshiftSeekToken? = null): TimeshiftPlaybackPosition = synchronized(lock) {
        require(position == null || (position.isFinite() && position >= Duration.ZERO))
        require(seek == null || seek.owner === owner) { "Seek belongs to another segment" }
        val current = owner
        if (position == null || !active) {
            TimeshiftPlaybackPosition.Unavailable
        } else {
            TimeshiftPlaybackPosition.Estimate(
                target = TimeshiftContentTarget(current, position),
                // Scripted evidence stays coherent: the sample reports the history currently
                // scripted for the same subscription.
                timeline = (mutableState.value as? LiveTimeshiftState.Available)?.timeline,
                seek = seek,
            )
        }
    }

    /** Create a scripted reader outcome. Even accepted seeks need not report a reached coordinate. */
    public fun completed(
        command: TimeshiftCommandResult = TimeshiftCommandResult.ACCEPTED,
        readerReached: Duration? = null,
        selection: at.bernhardberger.tvheadend.sdk.media3.TimeshiftSeekSelection? = null,
        seekCommand: TimeshiftCommandResult = command,
        buffering: TimeshiftCommandResult? = null,
        pauseRestoration: TimeshiftCommandResult? = null,
    ): TimeshiftContentSeekResult.Completed = synchronized(lock) {
        require(readerReached == null || (readerReached.isFinite() && readerReached >= Duration.ZERO))
        require(readerReached == null || seekCommand === TimeshiftCommandResult.ACCEPTED)
        require(selection == null || selection.target.owner === owner)
        TimeshiftContentSeekResult.Completed(
            command,
            readerReached?.let { TimeshiftContentTarget(owner, it) },
            if (seekCommand === TimeshiftCommandResult.ACCEPTED) TimeshiftSeekToken(owner) else null,
            seekCommand, buffering, pauseRestoration, selection,
        )
    }

    /**
     * Validate at dispatch, then call [dispatch] outside the fixture lock. Tests may suspend it with a
     * deferred result to check ordering and disposal. Cancellation propagates; replacement while awaiting
     * returns Replaced. No clamping, coalescing, queue or automatic displayed-position update is performed.
     */
    public suspend fun seek(
        target: TimeshiftContentTarget,
        dispatch: suspend (TimeshiftContentTarget) -> TimeshiftContentSeekResult.Completed = { completed() },
    ): TimeshiftContentSeekResult {
        currentCoroutineContext().ensureActive()
        val current = synchronized(lock) {
            val timeline = (mutableState.value as? LiveTimeshiftState.Available)?.timeline
            validateTimeshiftTarget(target, owner.takeIf { active }, timeline)?.let { return it }
            owner
        }
        val result = dispatch(target)
        val outcome = synchronized(lock) {
            if (!active || owner !== current) return@synchronized TimeshiftContentSeekResult.Replaced
            require(result.readerReached == null || result.readerReached.owner === current) {
                "Reader outcome belongs to another subscription"
            }
            require(result.seek == null || result.seek.owner === current) { "Seek belongs to another segment" }
            if (result.command.isTerminal) endSubscription()
            result
        }
        currentCoroutineContext().ensureActive()
        return outcome
    }

    /** Retire every old target and begin with no observed history. */
    public fun replaceSubscription(): Unit = synchronized(lock) {
        owner = Any()
        subscriptionOwner = owner
        active = true
        mutableState.value = LiveTimeshiftState.Available(grantedPeriod, null, null, null)
    }

    /** Restart the stream on the same transport, invalidating old targets and all mapping evidence. */
    public fun restartSegment(): Unit = synchronized(lock) {
        owner = Any()
        active = true
        mutableState.value = LiveTimeshiftState.Available(grantedPeriod, null, null, null)
    }

    /** End this subscription; old targets cannot be sent and displayed mapping becomes unavailable. */
    public fun endSubscription(): Unit = synchronized(lock) {
        active = false
        mutableState.value = LiveTimeshiftState.Unavailable
    }
}
