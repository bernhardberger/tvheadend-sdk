@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import at.bernhardberger.tvheadend.sdk.playback.ActiveSubscription
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionIssue
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
    private val publishIssue: (SubscriptionIssue?) -> Unit,
) {
    internal constructor(
        token: PlaybackTargetToken,
        publish: (LiveTimeshiftState) -> Unit,
    ) : this(token, publish, {})

    private val lock = Any()
    private var nextAttachmentSequence = 0L
    private var newestBoundSequence = -1L
    private var newestAttachment: Attachment? = null
    private var activeAttachment: Attachment? = null
    private var retired = false
    private var currentState: LiveTimeshiftState = LiveTimeshiftState.Unavailable
    private var currentIssue: SubscriptionIssue? = null
    private var issueReplacement: IssueReplacement? = null
    private var pendingRestoredIssue: PendingRestoredIssue? = null

    internal fun newAttachment(): Attachment = synchronized(lock) {
        check(nextAttachmentSequence != Long.MAX_VALUE) { "Timeshift attachment ids exhausted" }
        Attachment(nextAttachmentSequence++).also { newestAttachment = it }
    }

    internal fun publishCurrent() {
        synchronized(lock) {
            if (!retired && token.isActive()) {
                publish(currentState)
                publishIssueLocked(currentIssue)
            }
        }
    }

    internal fun beginIssueReplacement(): IssueReplacement = synchronized(lock) {
        check(issueReplacement == null) { "Timeshift issue replacement already active" }
        IssueReplacement(
            lastAttachmentSequence = nextAttachmentSequence - 1L,
            issue = currentIssue,
        ).also { issueReplacement = it }
    }

    internal fun commitIssueReplacement(replacement: IssueReplacement) {
        synchronized(lock) {
            check(issueReplacement === replacement) { "Unexpected timeshift issue replacement" }
            issueReplacement = null
            pendingRestoredIssue = null
        }
    }

    internal fun rollbackIssueReplacement(replacement: IssueReplacement) {
        synchronized(lock) {
            check(issueReplacement === replacement) { "Unexpected timeshift issue replacement" }
            issueReplacement = null
            val attachment = activeAttachment
            val newerTerminal = newestAttachment?.takeIf { newest ->
                newest.sequence > replacement.lastAttachmentSequence && newest.terminal
            }
            if (newerTerminal != null) {
                pendingRestoredIssue = null
                currentIssue = newerTerminal.latestIssue
            } else if (attachment != null) {
                if (!attachment.issueObserved) attachment.latestIssue = replacement.issue
                pendingRestoredIssue = null
                currentIssue = attachment.latestIssue
            } else {
                pendingRestoredIssue = PendingRestoredIssue(
                    afterSequence = replacement.lastAttachmentSequence,
                    issue = replacement.issue,
                )
                currentIssue = replacement.issue
            }
            publishIssueLocked(currentIssue)
        }
    }

    fun retire() {
        synchronized(lock) {
            if (retired && currentState === LiveTimeshiftState.Unavailable) return
            retired = true
            activeAttachment = null
            newestAttachment = null
            currentState = LiveTimeshiftState.Unavailable
            currentIssue = null
            issueReplacement = null
            pendingRestoredIssue = null
            publish(LiveTimeshiftState.Unavailable)
            publishIssueLocked(null)
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
        internal val sequence: Long,
    ) {
        internal var subscription: ActiveSubscription? = null
        internal var grant: Duration? = null
        private var latestStatus: SubscriptionEvent.Timeshift? = null
        private var latestSpeed: Int? = null
        internal var latestIssue: SubscriptionIssue? = null
        internal var issueObserved = false
        internal var detached = false
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
                    consumePendingTerminalLocked()
                    return
                }
                check(subscription == null || subscription === activeSubscription) {
                    "Timeshift attachment already has a subscription"
                }
                subscription = activeSubscription
                grant = activeSubscription.grantedTimeshiftPeriod
                pendingRestoredIssue?.takeIf { sequence > it.afterSequence }?.let { pending ->
                    if (!issueObserved) latestIssue = pending.issue
                    pendingRestoredIssue = null
                }
                newestBoundSequence = sequence
                activeAttachment = this
                publish(updateStateLocked())
                publishIssueLocked(updateIssueLocked())
            }
        }

        internal fun accept(event: SubscriptionEvent) {
            synchronized(lock) {
                if (retired || detached || terminal || !token.isActive()) return
                when (event) {
                    is SubscriptionEvent.Started -> {
                        issueObserved = true
                        latestIssue = event.issue
                    }
                    is SubscriptionEvent.Status -> {
                        issueObserved = true
                        latestIssue = event.issue
                    }
                    is SubscriptionEvent.Timeshift -> {
                        latestStatus = event
                        if (event.speed != null) latestSpeed = event.speed
                    }
                    is SubscriptionEvent.Speed -> latestSpeed = event.speed
                    is SubscriptionEvent.Stopped -> {
                        issueObserved = true
                        latestIssue = event.issue
                        terminalLocked()
                        consumePendingTerminalLocked()
                    }
                    is SubscriptionEvent.Terminated -> {
                        issueObserved = true
                        latestIssue = null
                        terminalLocked()
                        consumePendingTerminalLocked()
                    }
                    else -> return
                }
                if (activeAttachment === this) {
                    publish(updateStateLocked())
                    publishIssueLocked(updateIssueLocked())
                }
            }
        }

        internal fun terminal(activeSubscription: ActiveSubscription) {
            synchronized(lock) {
                if (subscription !== activeSubscription || terminal) return
                issueObserved = true
                latestIssue = null
                terminalLocked()
                if (activeAttachment === this) {
                    publish(updateStateLocked())
                    publishIssueLocked(updateIssueLocked())
                }
            }
        }

        internal fun detach() {
            synchronized(lock) {
                if (detached) return
                detached = true
                subscription = null
                if (
                    !terminal &&
                    issueReplacement?.let { sequence > it.lastAttachmentSequence } == true
                ) {
                    terminal = true
                    issueObserved = true
                    latestIssue = null
                }
                if (activeAttachment === this) {
                    activeAttachment = null
                    publish(updateStateLocked())
                    publishIssueLocked(updateIssueLocked())
                } else if (pendingRestoredIssue?.let { sequence > it.afterSequence } == true) {
                    latestIssue = null
                    consumePendingTerminalLocked()
                }
            }
        }

        private fun terminalLocked() {
            terminal = true
            subscription = null
        }

        private fun consumePendingTerminalLocked() {
            val pending = pendingRestoredIssue ?: return
            if (sequence <= pending.afterSequence || activeAttachment === this) return
            pendingRestoredIssue = null
            currentIssue = latestIssue
            publishIssueLocked(currentIssue)
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

    private fun updateIssueLocked(): SubscriptionIssue? {
        currentIssue = if (retired) null else activeAttachment?.latestIssue
        return currentIssue
    }

    private fun publishIssueLocked(issue: SubscriptionIssue?) {
        if (issueReplacement == null) publishIssue(issue)
    }

    internal class IssueReplacement internal constructor(
        internal val lastAttachmentSequence: Long,
        internal val issue: SubscriptionIssue?,
    )

    private data class PendingRestoredIssue(
        val afterSequence: Long,
        val issue: SubscriptionIssue?,
    )

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
