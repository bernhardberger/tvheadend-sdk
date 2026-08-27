@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import at.bernhardberger.tvheadend.sdk.playback.ActiveSubscription
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/** Stable application-safe state for the coordinator's current live timeshift target. */
public sealed interface LiveTimeshiftState {
    /** No current live target has a positive server-granted timeshift buffer. */
    public data object Unavailable : LiveTimeshiftState

    /** Current server grant and the latest valid ordered status observations. */
    @ConsistentCopyVisibility
    public data class Available internal constructor(
        public val grantedPeriod: Duration,
        public val bufferedDuration: Duration?,
        public val positionBehindLive: Duration?,
        public val serverPaused: Boolean?,
    ) : LiveTimeshiftState
}

/** Typed application-safe outcome of one high-level timeshift command. */
public enum class TimeshiftCommandResult {
    ACCEPTED,
    REJECTED,
    UNAVAILABLE,
    ALREADY_PENDING,
    NOT_ACKNOWLEDGED,
    ACKNOWLEDGEMENT_TIMEOUT,
    PENDING_QUEUE_OVERFLOW,
    UNCERTAIN_REQUEST_OUTCOME,
    UNRECOGNIZED_ACKNOWLEDGEMENT,
    RESUMED_SEGMENT_UNANCHORABLE,
    SUBSCRIPTION_ENDED,
    SERVER_REJECTED,
    ACCESS_DENIED,
    CONNECTION_LIMIT,
    TIMEOUT,
    TRANSPORT_UNAVAILABLE,
    NOT_SUPPORTED,
    NOT_RUNNING,
    SHUT_DOWN,
}

internal class LiveTimeshiftControlBridge(
    private val token: PlaybackTargetToken,
    private val publish: (LiveTimeshiftState) -> Unit,
) {
    private val lock = Any()
    private var nextAttachmentSequence = 0L
    private var newestBoundSequence = -1L
    private var activeAttachment: Attachment? = null
    private var retired = false
    private var currentState: LiveTimeshiftState = LiveTimeshiftState.Unavailable

    internal fun newAttachment(): Attachment = synchronized(lock) {
        check(nextAttachmentSequence != Long.MAX_VALUE) { "Timeshift attachment ids exhausted" }
        Attachment(nextAttachmentSequence++)
    }

    internal fun publishCurrent() {
        synchronized(lock) {
            if (!retired && token.isActive()) publish(currentState)
        }
    }

    fun retire() {
        synchronized(lock) {
            if (retired && currentState === LiveTimeshiftState.Unavailable) return
            retired = true
            activeAttachment = null
            currentState = LiveTimeshiftState.Unavailable
            publish(LiveTimeshiftState.Unavailable)
        }
    }

    suspend fun seek(target: SubscriptionSeekTarget): SubscriptionSeekResult? {
        val handle = currentHandle() ?: return null
        val result = handle.subscription.seek(target)
        if (result is SubscriptionSeekResult.Invalidated ||
            result === SubscriptionSeekResult.SubscriptionEnded
        ) {
            handle.attachment.terminal(handle.subscription)
        }
        return result
    }

    suspend fun setSpeed(speed: Int): SubscriptionOperationResult<Unit>? =
        currentHandle()?.subscription?.setSpeed(speed)

    private fun currentHandle(): ControlHandle? = synchronized(lock) {
        val attachment = activeAttachment ?: return@synchronized null
        val subscription = attachment.subscription ?: return@synchronized null
        if (
            retired ||
            !token.isActive() ||
            attachment.grant?.let { granted -> granted > Duration.ZERO } != true
        ) {
            null
        } else {
            ControlHandle(attachment, subscription)
        }
    }

    internal inner class Attachment internal constructor(
        private val sequence: Long,
    ) {
        internal var subscription: ActiveSubscription? = null
        internal var grant: Duration? = null
        private var latestStatus: SubscriptionEvent.Timeshift? = null
        private var latestSpeed: Int? = null
        private var detached = false
        internal var terminal = false

        internal fun bind(activeSubscription: ActiveSubscription) {
            synchronized(lock) {
                if (
                    retired ||
                    detached ||
                    terminal ||
                    !token.isActive() ||
                    sequence < newestBoundSequence
                ) {
                    return
                }
                if (activeSubscription.state.value is SubscriptionState.Terminal) {
                    terminal = true
                    return
                }
                check(subscription == null || subscription === activeSubscription) {
                    "Timeshift attachment already has a subscription"
                }
                subscription = activeSubscription
                grant = activeSubscription.grantedTimeshiftPeriod
                newestBoundSequence = sequence
                activeAttachment = this
                publish(updateStateLocked())
            }
        }

        internal fun accept(event: SubscriptionEvent) {
            synchronized(lock) {
                if (retired || detached || terminal || !token.isActive()) return
                when (event) {
                    is SubscriptionEvent.Timeshift -> {
                        latestStatus = event
                        if (event.speed != null) latestSpeed = event.speed
                    }
                    is SubscriptionEvent.Speed -> latestSpeed = event.speed
                    is SubscriptionEvent.Stopped,
                    is SubscriptionEvent.Terminated,
                    -> terminalLocked()
                    else -> return
                }
                if (activeAttachment === this) publish(updateStateLocked())
            }
        }

        internal fun terminal(activeSubscription: ActiveSubscription) {
            synchronized(lock) {
                if (subscription !== activeSubscription || terminal) return
                terminalLocked()
                if (activeAttachment === this) publish(updateStateLocked())
            }
        }

        internal fun detach() {
            synchronized(lock) {
                if (detached) return
                detached = true
                subscription = null
                if (activeAttachment === this) {
                    activeAttachment = null
                    publish(updateStateLocked())
                }
            }
        }

        private fun terminalLocked() {
            terminal = true
            subscription = null
        }

        internal fun availableState(): LiveTimeshiftState =
            grant?.takeIf { it > Duration.ZERO }?.let { granted ->
                val buffered = latestStatus?.observedBufferedDuration()
                LiveTimeshiftState.Available(
                    grantedPeriod = granted,
                    bufferedDuration = buffered,
                    positionBehindLive = latestStatus?.observedPositionBehindLive(buffered),
                    serverPaused = when (latestSpeed) {
                        PAUSED_SPEED -> true
                        NORMAL_SPEED -> false
                        else -> null
                    },
                )
            } ?: LiveTimeshiftState.Unavailable
    }

    private fun updateStateLocked(): LiveTimeshiftState {
        currentState = if (retired) {
            LiveTimeshiftState.Unavailable
        } else {
            activeAttachment?.takeUnless { it.terminal }?.availableState()
                ?: LiveTimeshiftState.Unavailable
        }
        return currentState
    }

    private data class ControlHandle(
        val attachment: Attachment,
        val subscription: ActiveSubscription,
    )
}

private fun SubscriptionEvent.Timeshift.observedBufferedDuration(): Duration? {
    val observedStart = start ?: return null
    val observedEnd = end ?: return null
    if (observedEnd < observedStart) return null
    val microseconds = try {
        Math.subtractExact(observedEnd, observedStart)
    } catch (_: ArithmeticException) {
        return null
    }
    return microseconds.microseconds.takeIf(Duration::isFinite)
}

private fun SubscriptionEvent.Timeshift.observedPositionBehindLive(
    bufferedDuration: Duration?,
): Duration? {
    val buffer = bufferedDuration ?: return null
    if (shift < 0L) return null
    val observed = shift.microseconds.takeIf(Duration::isFinite) ?: return null
    return observed.coerceAtMost(buffer)
}

internal const val PAUSED_SPEED: Int = 0
internal const val NORMAL_SPEED: Int = 100
