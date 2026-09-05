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
import java.util.Collections
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
        /** Server reader shift, not the displayed position or the client queue depth. */
        public val positionBehindLive: Duration?,
        public val serverPaused: Boolean?,
        public val timeline: TimeshiftTimeline? = null,
    ) : LiveTimeshiftState
}

/** Closed disposition of a timeshift command without exhaustively defining its exact outcome. */
public enum class TimeshiftCommandDisposition {
    ACCEPTED,
    NOT_ACCEPTED,
    UNCONFIRMED,
}

/** Stable, non-exclusive category shared by high-level playback outcomes. */
public enum class PlaybackOutcomeCategory {
    TRANSIENT,
    TERMINAL,
    UNSUPPORTED,
    CONFIGURATION_OR_ACCESS,
    UNCERTAIN,
}

/**
 * Typed application-safe outcome of one high-level timeshift command.
 *
 * Exact values are SDK-owned singletons rather than an exhaustive enum. Applications may compare
 * a known value or use the stable disposition, categories, and predicates, but must retain a
 * fallback for future exact outcomes. An empty category set means no stable broad classification
 * is available. A transient result does not promise that replay is safe.
 */
public class TimeshiftCommandResult private constructor(
    private val label: String,
    public val disposition: TimeshiftCommandDisposition,
    public val categories: Set<PlaybackOutcomeCategory>,
) {
    public val isAccepted: Boolean
        get() = disposition == TimeshiftCommandDisposition.ACCEPTED

    public val isTransient: Boolean
        get() = PlaybackOutcomeCategory.TRANSIENT in categories

    public val isTerminal: Boolean
        get() = PlaybackOutcomeCategory.TERMINAL in categories

    public val isUnsupported: Boolean
        get() = PlaybackOutcomeCategory.UNSUPPORTED in categories

    public val isConfigurationOrAccessRelated: Boolean
        get() = PlaybackOutcomeCategory.CONFIGURATION_OR_ACCESS in categories

    public val isOutcomeUncertain: Boolean
        get() = PlaybackOutcomeCategory.UNCERTAIN in categories

    override fun toString(): String = label

    public companion object {
        @JvmField
        public val ACCEPTED: TimeshiftCommandResult = accepted("ACCEPTED")

        @JvmField
        public val REJECTED: TimeshiftCommandResult = result("REJECTED")

        @JvmField
        public val UNAVAILABLE: TimeshiftCommandResult = transient("UNAVAILABLE")

        @JvmField
        public val ALREADY_PENDING: TimeshiftCommandResult = transient("ALREADY_PENDING")

        @JvmField
        public val NOT_ACKNOWLEDGED: TimeshiftCommandResult = transient("NOT_ACKNOWLEDGED")

        @JvmField
        public val ACKNOWLEDGEMENT_TIMEOUT: TimeshiftCommandResult = terminalUncertain(
            "ACKNOWLEDGEMENT_TIMEOUT",
        )

        @JvmField
        public val PENDING_QUEUE_OVERFLOW: TimeshiftCommandResult = terminalUncertain(
            "PENDING_QUEUE_OVERFLOW",
        )

        @JvmField
        public val UNCERTAIN_REQUEST_OUTCOME: TimeshiftCommandResult = terminalUncertain(
            "UNCERTAIN_REQUEST_OUTCOME",
        )

        @JvmField
        public val UNRECOGNIZED_ACKNOWLEDGEMENT: TimeshiftCommandResult = terminalUncertain(
            "UNRECOGNIZED_ACKNOWLEDGEMENT",
        )

        @JvmField
        public val RESUMED_SEGMENT_UNANCHORABLE: TimeshiftCommandResult = terminal(
            "RESUMED_SEGMENT_UNANCHORABLE",
            TimeshiftCommandDisposition.ACCEPTED,
        )

        @JvmField
        public val SUBSCRIPTION_ENDED: TimeshiftCommandResult = terminalUncertain(
            "SUBSCRIPTION_ENDED",
        )

        @JvmField
        public val SERVER_REJECTED: TimeshiftCommandResult = result("SERVER_REJECTED")

        @JvmField
        public val ACCESS_DENIED: TimeshiftCommandResult = categorized(
            "ACCESS_DENIED",
            PlaybackOutcomeCategory.CONFIGURATION_OR_ACCESS,
        )

        @JvmField
        public val CONNECTION_LIMIT: TimeshiftCommandResult = transient("CONNECTION_LIMIT")

        @JvmField
        public val TIMEOUT: TimeshiftCommandResult = categorized(
            "TIMEOUT",
            TimeshiftCommandDisposition.UNCONFIRMED,
            PlaybackOutcomeCategory.TRANSIENT,
            PlaybackOutcomeCategory.UNCERTAIN,
        )

        @JvmField
        public val TRANSPORT_UNAVAILABLE: TimeshiftCommandResult = transient(
            "TRANSPORT_UNAVAILABLE",
        )

        @JvmField
        public val NOT_SUPPORTED: TimeshiftCommandResult = categorized(
            "NOT_SUPPORTED",
            PlaybackOutcomeCategory.UNSUPPORTED,
        )

        @JvmField
        public val NOT_RUNNING: TimeshiftCommandResult = transient("NOT_RUNNING")

        @JvmField
        public val SHUT_DOWN: TimeshiftCommandResult = terminal("SHUT_DOWN")

        private fun accepted(label: String): TimeshiftCommandResult = TimeshiftCommandResult(
            label,
            TimeshiftCommandDisposition.ACCEPTED,
            emptySet(),
        )

        private fun result(label: String): TimeshiftCommandResult = TimeshiftCommandResult(
            label,
            TimeshiftCommandDisposition.NOT_ACCEPTED,
            emptySet(),
        )

        private fun transient(label: String): TimeshiftCommandResult = categorized(
            label,
            PlaybackOutcomeCategory.TRANSIENT,
        )

        private fun terminal(
            label: String,
            disposition: TimeshiftCommandDisposition = TimeshiftCommandDisposition.NOT_ACCEPTED,
        ): TimeshiftCommandResult = categorized(
            label,
            disposition,
            PlaybackOutcomeCategory.TERMINAL,
        )

        private fun terminalUncertain(label: String): TimeshiftCommandResult = categorized(
            label,
            TimeshiftCommandDisposition.UNCONFIRMED,
            PlaybackOutcomeCategory.TERMINAL,
            PlaybackOutcomeCategory.UNCERTAIN,
        )

        private fun categorized(
            label: String,
            vararg categories: PlaybackOutcomeCategory,
        ): TimeshiftCommandResult = TimeshiftCommandResult(
            label,
            TimeshiftCommandDisposition.NOT_ACCEPTED,
            Collections.unmodifiableSet(categories.toSet()),
        )

        private fun categorized(
            label: String,
            disposition: TimeshiftCommandDisposition,
            vararg categories: PlaybackOutcomeCategory,
        ): TimeshiftCommandResult = TimeshiftCommandResult(
            label,
            disposition,
            Collections.unmodifiableSet(categories.toSet()),
        )
    }
}

internal class LiveTimeshiftControlBridge(
    private val token: PlaybackTargetToken,
    private val publish: (LiveTimeshiftState) -> Unit,
    private val publishIssue: (SubscriptionIssue?) -> Unit,
    private val publishDiagnostics: (LiveSubscriptionDiagnostics?) -> Unit = {},
    private val publishObservation: (LivePlaybackObservation.Active) -> Unit = {},
) {
    internal constructor(
        token: PlaybackTargetToken,
        publish: (LiveTimeshiftState) -> Unit,
    ) : this(token, publish, {}, {}, {})

    private val lock = Any()
    private var nextAttachmentSequence = 0L
    private var newestBoundSequence = -1L
    private var newestTerminalAttachment: Attachment? = null
    private var activeAttachment: Attachment? = null
    private var attachedPeriodCount = 0
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
        attachedPeriodCount++
        Attachment(nextAttachmentSequence++)
    }

    internal fun publishCurrent() {
        synchronized(lock) {
            if (!retired && token.isActive()) {
                publishCurrentLocked()
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
            publishCurrentLocked()
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
            publishIssue(null)
            publishDiagnostics(null)
            publishObservation(
                LivePlaybackObservation.Active(LiveTimeshiftState.Unavailable, null, null),
            )
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

    suspend fun seekContent(target: TimeshiftContentTarget): TimeshiftContentSeekResult {
        val handle = synchronized(lock) {
            val current = currentHandle() ?: return TimeshiftContentSeekResult.Replaced
            validateTimeshiftTarget(target, current.attachment, current.attachment.timeline())?.let { return it }
            current
        }
        val result = handle.subscription.seek(SubscriptionSeekTarget.Absolute(target.position))
        return synchronized(lock) {
            if (currentHandle()?.attachment !== handle.attachment) {
                return@synchronized TimeshiftContentSeekResult.Replaced
            }
            val reached = (result as? SubscriptionSeekResult.AcceptedAt)?.position
                ?.takeIf { it.isFinite() && !it.isNegative() }
                ?.let { TimeshiftContentTarget(handle.attachment, it) }
            if (result is SubscriptionSeekResult.Invalidated || result === SubscriptionSeekResult.SubscriptionEnded) {
                handle.attachment.terminal(handle.subscription)
            }
            TimeshiftContentSeekResult.Completed(result.toPublicTimeshiftResult(), reached)
        }
    }

    internal fun mappingAttachment(): Attachment? = synchronized(lock) {
        currentHandle()?.attachment?.takeIf { attachedPeriodCount == 1 }
    }

    internal fun playbackPosition(attachment: Attachment, position: Duration): TimeshiftPlaybackPosition =
        synchronized(lock) {
            if (mappingAttachment() !== attachment || !position.isFinite() || position.isNegative()) {
                return@synchronized TimeshiftPlaybackPosition.Unavailable
            }
            attachment.packetMapping.map(position.inWholeMicroseconds)?.let {
                TimeshiftPlaybackPosition.Estimate(TimeshiftContentTarget(attachment, it.microseconds))
            } ?: TimeshiftPlaybackPosition.Unavailable
        }

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
        internal val packetMapping = TimeshiftPacketMapping()
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
                        updateDiagnosticsLocked()
                        publishCurrentLocked(state = false, issue = false)
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
                updateStateLocked()
                updateIssueLocked()
                updateDiagnosticsLocked()
                publishCurrentLocked()
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
                    is SubscriptionEvent.Packet -> {
                        packetMapping.accept(event.presentationTimeUs, event.serverPresentationTimeUs)
                        return
                    }
                    is SubscriptionEvent.Skipped -> {
                        if (event.outcome == at.bernhardberger.tvheadend.sdk.playback.SkipOutcome.ACCEPTED) {
                            packetMapping.discontinuity()
                        }
                        return
                    }
                    is SubscriptionEvent.Dropped -> {
                        packetMapping.discontinuity()
                        return
                    }
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
                    updateStateLocked()
                    updateIssueLocked()
                    updateDiagnosticsLocked()
                    publishCurrentLocked()
                } else if (diagnosticsInvalidated) {
                    updateDiagnosticsLocked()
                    publishCurrentLocked(state = false, issue = false)
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
                    updateStateLocked()
                    updateIssueLocked()
                    updateDiagnosticsLocked()
                    publishCurrentLocked()
                } else if (diagnosticsInvalidated) {
                    updateDiagnosticsLocked()
                    publishCurrentLocked(state = false, issue = false)
                }
            }
        }

        internal fun detach() {
            synchronized(lock) {
                if (detached) return
                detached = true
                attachedPeriodCount--
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
                    updateStateLocked()
                    updateIssueLocked()
                    updateDiagnosticsLocked()
                    publishCurrentLocked()
                } else if (pendingRestoredObservations?.let { sequence > it.afterSequence } == true) {
                    latestIssue = null
                    latestDiagnostics = null
                    consumePendingTerminalLocked()
                } else if (diagnosticsInvalidated) {
                    updateDiagnosticsLocked()
                    publishCurrentLocked(state = false, issue = false)
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
            publishCurrentLocked()
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
                    timeline = timeline(),
                )
            } ?: LiveTimeshiftState.Unavailable

        internal fun timeline(): TimeshiftTimeline? {
            val start = latestStatus?.start ?: return null
            val end = latestStatus?.end ?: return null
            if (start < 0L || end < start) return null
            return TimeshiftTimeline(this, start.microseconds, end.microseconds)
        }
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

    private fun publishCurrentLocked(
        state: Boolean = true,
        issue: Boolean = true,
        diagnostics: Boolean = true,
    ) {
        if (observationReplacement != null) return
        if (state) publishStateLocked(currentState)
        if (issue) publishIssueLocked(currentIssue)
        if (diagnostics) publishDiagnosticsLocked(currentDiagnostics)
        publishObservation(
            LivePlaybackObservation.Active(currentState, currentIssue, currentDiagnostics),
        )
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
