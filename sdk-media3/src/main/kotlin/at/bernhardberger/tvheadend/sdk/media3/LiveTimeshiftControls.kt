@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import at.bernhardberger.tvheadend.sdk.playback.ActiveSubscription
import at.bernhardberger.tvheadend.sdk.playback.LiveSubscriptionDiagnostics
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
    private val publishDiagnostics: (LiveSubscriptionDiagnostics?) -> Unit = {},
) {
    internal constructor(
        token: PlaybackTargetToken,
        publish: (LiveTimeshiftState) -> Unit,
    ) : this(token, publish, {}, {})

    private val lock = Any()
    private var nextAttachmentSequence = 0L
    private var newestBoundSequence = -1L
    private var newestTerminalAttachment: Attachment? = null
    private var activeAttachment: Attachment? = null
    private var retired = false
    private var currentState: LiveTimeshiftState = LiveTimeshiftState.Unavailable
    private var currentIssue: SubscriptionIssue? = null
    private var currentDiagnostics: LiveSubscriptionDiagnostics? = null
    private var diagnosticsTerminalThroughSequence = -1L
    private var diagnosticsDetachedThroughSequence = -1L
    private var diagnosticsReplacedThroughSequence = -1L
    private var observationReplacement: ObservationReplacement? = null
    private var pendingRestoredObservations: PendingRestoredObservations? = null

    internal fun newAttachment(): Attachment = synchronized(lock) {
        check(nextAttachmentSequence != Long.MAX_VALUE) { "Timeshift attachment ids exhausted" }
        Attachment(nextAttachmentSequence++)
    }

    internal fun publishCurrent() {
        synchronized(lock) {
            if (!retired && token.isActive()) {
                publishStateLocked(currentState)
                publishIssueLocked(currentIssue)
                publishDiagnosticsLocked(currentDiagnostics)
            }
        }
    }

    internal fun beginObservationReplacement(): ObservationReplacement = synchronized(lock) {
        check(observationReplacement == null) { "Live observation replacement already active" }
        ObservationReplacement(
            lastAttachmentSequence = nextAttachmentSequence - 1L,
            state = currentState,
            issue = currentIssue,
            diagnostics = currentDiagnostics,
            diagnosticsTerminalThroughSequence = diagnosticsTerminalThroughSequence,
            diagnosticsDetachedThroughSequence = diagnosticsDetachedThroughSequence,
            diagnosticsReplacedThroughSequence = diagnosticsReplacedThroughSequence,
        ).also { observationReplacement = it }
    }

    internal fun commitObservationReplacement(replacement: ObservationReplacement) {
        synchronized(lock) {
            if (retired) return
            check(observationReplacement === replacement) { "Unexpected live observation replacement" }
            observationReplacement = null
            pendingRestoredObservations = null
        }
    }

    internal fun rollbackObservationReplacement(replacement: ObservationReplacement) {
        synchronized(lock) {
            if (retired) return
            check(observationReplacement === replacement) { "Unexpected live observation replacement" }
            observationReplacement = null
            diagnosticsDetachedThroughSequence = replacement.diagnosticsDetachedThroughSequence
            diagnosticsReplacedThroughSequence = replacement.diagnosticsReplacedThroughSequence
            val attachment = activeAttachment
            val replacementTerminal = newestTerminalAttachment?.takeIf {
                diagnosticsTerminalThroughSequence > replacement.diagnosticsTerminalThroughSequence
            }
            if (attachment != null) {
                pendingRestoredObservations = null
                currentIssue = if (
                    replacementTerminal == null ||
                    (attachment.sequence > replacementTerminal.sequence && attachment.issueObserved)
                ) {
                    if (!attachment.issueObserved) attachment.latestIssue = replacement.issue
                    attachment.latestIssue
                } else {
                    replacementTerminal.latestIssue
                }
                currentDiagnostics = if (
                    replacementTerminal == null ||
                    (attachment.sequence > replacementTerminal.sequence && attachment.diagnosticsObserved)
                ) {
                    if (!attachment.diagnosticsObserved) attachment.latestDiagnostics = replacement.diagnostics
                    updateDiagnosticsLocked()
                } else {
                    null
                }
                currentState = if (
                    replacementTerminal == null || attachment.sequence > replacementTerminal.sequence
                ) {
                    updateStateLocked()
                } else {
                    LiveTimeshiftState.Unavailable
                }
            } else if (replacementTerminal != null) {
                pendingRestoredObservations = null
                currentState = LiveTimeshiftState.Unavailable
                currentIssue = replacementTerminal.latestIssue
                currentDiagnostics = null
            } else {
                pendingRestoredObservations = PendingRestoredObservations(
                    afterSequence = replacement.lastAttachmentSequence,
                    issue = replacement.issue,
                    diagnostics = replacement.diagnostics,
                )
                currentState = replacement.state
                currentIssue = replacement.issue
                currentDiagnostics = replacement.diagnostics
            }
            publishStateLocked(currentState)
            publishIssueLocked(currentIssue)
            publishDiagnosticsLocked(currentDiagnostics)
        }
    }

    fun retire() {
        synchronized(lock) {
            if (retired && currentState === LiveTimeshiftState.Unavailable) return
            retired = true
            activeAttachment = null
            newestTerminalAttachment = null
            currentState = LiveTimeshiftState.Unavailable
            currentIssue = null
            currentDiagnostics = null
            observationReplacement = null
            pendingRestoredObservations = null
            publish(LiveTimeshiftState.Unavailable)
            publishIssueLocked(null)
            publishDiagnosticsLocked(null)
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
        internal var latestDiagnostics: LiveSubscriptionDiagnostics? = null
        internal var diagnosticsObserved = false
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
                    issueObserved = true
                    latestIssue = null
                    val diagnosticsInvalidated = terminalLocked()
                    consumePendingTerminalLocked()
                    if (activeAttachment !== this && diagnosticsInvalidated) {
                        publishDiagnosticsLocked(updateDiagnosticsLocked())
                    }
                    return
                }
                check(subscription == null || subscription === activeSubscription) {
                    "Timeshift attachment already has a subscription"
                }
                subscription = activeSubscription
                grant = activeSubscription.grantedTimeshiftPeriod
                pendingRestoredObservations?.takeIf { sequence > it.afterSequence }?.let { pending ->
                    if (!issueObserved) latestIssue = pending.issue
                    if (!diagnosticsObserved) latestDiagnostics = pending.diagnostics
                    pendingRestoredObservations = null
                }
                newestBoundSequence = sequence
                activeAttachment = this
                publishStateLocked(updateStateLocked())
                publishIssueLocked(updateIssueLocked())
                publishDiagnosticsLocked(updateDiagnosticsLocked())
            }
        }

        internal fun accept(event: SubscriptionEvent) {
            synchronized(lock) {
                if (retired || detached || terminal || !token.isActive()) return
                var diagnosticsInvalidated = false
                if (event.isDiagnosticsObservation()) {
                    diagnosticsObserved = true
                    latestDiagnostics = LiveSubscriptionDiagnostics.update(latestDiagnostics, event)
                }
                when (event) {
                    is SubscriptionEvent.Started -> {
                        issueObserved = true
                        latestIssue = event.issue
                        diagnosticsInvalidated = replaceDiagnosticsThroughLocked(sequence - 1L)
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
                        diagnosticsInvalidated = terminalLocked()
                        consumePendingTerminalLocked()
                    }
                    is SubscriptionEvent.Terminated -> {
                        issueObserved = true
                        latestIssue = null
                        diagnosticsInvalidated = terminalLocked()
                        consumePendingTerminalLocked()
                    }
                    is SubscriptionEvent.Queue,
                    is SubscriptionEvent.Signal,
                    -> Unit
                    else -> return
                }
                if (activeAttachment === this) {
                    publishStateLocked(updateStateLocked())
                    publishIssueLocked(updateIssueLocked())
                    publishDiagnosticsLocked(updateDiagnosticsLocked())
                } else if (diagnosticsInvalidated) {
                    publishDiagnosticsLocked(updateDiagnosticsLocked())
                }
            }
        }

        internal fun terminal(activeSubscription: ActiveSubscription) {
            synchronized(lock) {
                if (subscription !== activeSubscription || terminal) return
                issueObserved = true
                latestIssue = null
                val diagnosticsInvalidated = terminalLocked()
                if (activeAttachment === this) {
                    publishStateLocked(updateStateLocked())
                    publishIssueLocked(updateIssueLocked())
                    publishDiagnosticsLocked(updateDiagnosticsLocked())
                } else if (diagnosticsInvalidated) {
                    publishDiagnosticsLocked(updateDiagnosticsLocked())
                }
            }
        }

        internal fun detach() {
            synchronized(lock) {
                if (detached) return
                detached = true
                subscription = null
                diagnosticsObserved = true
                latestDiagnostics = null
                var diagnosticsInvalidated = detachDiagnosticsThroughLocked(sequence)
                if (
                    !terminal &&
                    observationReplacement?.let { sequence > it.lastAttachmentSequence } == true
                ) {
                    terminal = true
                    issueObserved = true
                    latestIssue = null
                    diagnosticsObserved = true
                    latestDiagnostics = null
                    diagnosticsInvalidated = recordTerminalLocked() || diagnosticsInvalidated
                }
                if (activeAttachment === this) {
                    activeAttachment = null
                    publishStateLocked(updateStateLocked())
                    publishIssueLocked(updateIssueLocked())
                    publishDiagnosticsLocked(updateDiagnosticsLocked())
                } else if (pendingRestoredObservations?.let { sequence > it.afterSequence } == true) {
                    latestIssue = null
                    latestDiagnostics = null
                    consumePendingTerminalLocked()
                } else if (diagnosticsInvalidated) {
                    publishDiagnosticsLocked(updateDiagnosticsLocked())
                }
            }
        }

        private fun terminalLocked(): Boolean {
            terminal = true
            subscription = null
            diagnosticsObserved = true
            latestDiagnostics = null
            return recordTerminalLocked()
        }

        private fun recordTerminalLocked(): Boolean {
            if (sequence <= diagnosticsTerminalThroughSequence) return false
            diagnosticsTerminalThroughSequence = sequence
            newestTerminalAttachment = this
            return true
        }

        private fun consumePendingTerminalLocked() {
            val pending = pendingRestoredObservations ?: return
            if (sequence <= pending.afterSequence || activeAttachment === this) return
            pendingRestoredObservations = null
            currentState = LiveTimeshiftState.Unavailable
            currentIssue = latestIssue
            currentDiagnostics = latestDiagnostics
            publishStateLocked(currentState)
            publishIssueLocked(currentIssue)
            publishDiagnosticsLocked(currentDiagnostics)
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

    private fun updateDiagnosticsLocked(): LiveSubscriptionDiagnostics? {
        val attachment = activeAttachment
        currentDiagnostics = if (
            retired ||
            attachment == null ||
            attachment.sequence <= maxOf(
                diagnosticsTerminalThroughSequence,
                diagnosticsDetachedThroughSequence,
                diagnosticsReplacedThroughSequence,
            )
        ) {
            null
        } else {
            attachment.latestDiagnostics
        }
        return currentDiagnostics
    }

    private fun detachDiagnosticsThroughLocked(sequence: Long): Boolean {
        if (sequence <= diagnosticsDetachedThroughSequence) return false
        diagnosticsDetachedThroughSequence = sequence
        return true
    }

    private fun replaceDiagnosticsThroughLocked(sequence: Long): Boolean {
        if (sequence <= diagnosticsReplacedThroughSequence) return false
        diagnosticsReplacedThroughSequence = sequence
        return true
    }

    private fun publishIssueLocked(issue: SubscriptionIssue?) {
        if (observationReplacement == null) publishIssue(issue)
    }

    private fun publishStateLocked(state: LiveTimeshiftState) {
        if (observationReplacement == null) publish(state)
    }

    private fun publishDiagnosticsLocked(diagnostics: LiveSubscriptionDiagnostics?) {
        if (observationReplacement == null) publishDiagnostics(diagnostics)
    }

    internal class ObservationReplacement internal constructor(
        internal val lastAttachmentSequence: Long,
        internal val state: LiveTimeshiftState,
        internal val issue: SubscriptionIssue?,
        internal val diagnostics: LiveSubscriptionDiagnostics?,
        internal val diagnosticsTerminalThroughSequence: Long,
        internal val diagnosticsDetachedThroughSequence: Long,
        internal val diagnosticsReplacedThroughSequence: Long,
    )

    private data class PendingRestoredObservations(
        val afterSequence: Long,
        val issue: SubscriptionIssue?,
        val diagnostics: LiveSubscriptionDiagnostics?,
    )

    private data class ControlHandle(
        val attachment: Attachment,
        val subscription: ActiveSubscription,
    )
}

private fun SubscriptionEvent.isDiagnosticsObservation(): Boolean =
    this is SubscriptionEvent.Started ||
        this is SubscriptionEvent.Queue ||
        this is SubscriptionEvent.Signal ||
        this is SubscriptionEvent.Stopped ||
        this is SubscriptionEvent.Terminated

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
