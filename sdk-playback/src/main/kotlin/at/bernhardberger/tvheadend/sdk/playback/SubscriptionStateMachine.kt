@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.playback

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.microseconds

/** Direct suspending consumer of the complete committed subscription event order. */
@SubscriptionInfrastructureApi
public fun interface SubscriptionEventConsumer {
    /**
     * Consumes one event without an SDK-owned holding queue.
     *
     * Implementations must remain cancellation-cooperative and must not suspend indefinitely.
     * Subscription teardown first waits for a committed seek replay. After a successful
     * unsubscribe it then waits for the in-flight delivery and the ordered drain of the remaining
     * stream events; after a failed unsubscribe it waits only for the in-flight delivery. Each of
     * these two waits has its own five-second grace period. When one expires, teardown cancels
     * consumer code still running and skips every later event. That cancellation is not a
     * subscription failure, and neither is a non-cancellation exception the cancelled call throws
     * afterwards: close keeps its terminal reason, returns [SubscriptionCloseResult.CLEANUP_FAILED],
     * and sets [SubscriptionDiagnostics.consumerDrainTimedOut]. Any other cancellation thrown by
     * this callback is still an infrastructure failure that is rethrown to close callers.
     *
     * The joining lifecycle methods [ActiveSubscription.close], [ActiveSubscription.seek], and
     * [SubscriptionManager.closeAndJoin] fail fast with [IllegalStateException] when called from
     * this callback or from any coroutine that inherits its coroutine context, for example through
     * `withContext`, `coroutineScope`, or `launch` and `async` in such a scope. A wait on an
     * unrelated coroutine that calls them is not detected: the grace period bounds it only while
     * the wait honours cancellation, and a blocking or non-cancellable wait holds teardown
     * indefinitely.
     *
     * While a seek acknowledgement is pending, [SubscriptionEvent.Packet] and
     * [SubscriptionEvent.Dropped] are withheld and every other event is still delivered
     * immediately. Withheld events keep their relative order and are either replayed before the
     * rejecting acknowledgement or discarded before the accepting one.
     * The exception is a stop/start boundary intersecting an unresolved seek: uncertain request
     * provenance disables consumer delivery, including that boundary and later controls. Observe
     * [ActiveSubscription.state] for the terminal seek invalidation; no unsafe replay is attempted.
     * Once close or owner teardown begins, an unresolved seek instead ends as
     * [SubscriptionSeekResult.SubscriptionEnded]: its withheld events and every later
     * [SubscriptionEvent.Packet] and [SubscriptionEvent.Dropped] of the closing drain are
     * discarded, while other events are still delivered. A stop or restart boundary in that drain
     * discards its later packets and drop markers the same way, because no successor is published.
     *
     * After an accepted [SubscriptionEvent.Skipped], packets are discarded until the timeline
     * re-anchors, and one shared offset is then applied to the presentation and decoding
     * timestamps of every track. Re-anchoring waits for a video keyframe, releases after a
     * bounded number of discarded packets so a stream that reports no frame type cannot stall it,
     * and happens on the first timed packet when no committed track can carry video.
     *
     * No delivered timestamp is therefore at or below the last timestamp delivered before that
     * discontinuity, and relative track positions inside the resumed segment stay unchanged. This
     * is not a claim of global monotonicity: cross-track interleaving and reordered frames still
     * present decreasing timestamps, exactly as they do without a seek.
     */
    public suspend fun accept(event: SubscriptionEvent)

    /** Receives the validated immutable tracks before the subscription can become playable. */
    public fun tracksReady(tracks: SubscriptionTracks): Unit = Unit
}

/**
 * Immutable validated tracks and identity of one playable segment. A restart produces a new instance
 * even for identical streams. The public constructor lets infrastructure fakes model this boundary.
 */
@SubscriptionInfrastructureApi
public class SubscriptionTracks public constructor(streams: List<SubscriptionStream>) {
    /** Ordered immutable stream descriptions. */
    public val streams: List<SubscriptionStream> = streams.toImmutableList()

    init {
        require(streams.isNotEmpty()) { "Playable tracks must not be empty" }
        require(streams.map { it.index }.distinct().size == streams.size) {
            "Playable track indices must be unique"
        }
    }

    override fun toString(): String = "SubscriptionTracks(<redacted>)"
}

/** Durable state of one admitted subscription. */
@SubscriptionInfrastructureApi
public sealed interface SubscriptionState {
    /** Acknowledgement or segment tracks are pending, including a nonterminal stream interruption. */
    public data object Starting : SubscriptionState

    /** Subscribe was acknowledged and a valid immutable track set was committed. */
    public class Playable(public val tracks: SubscriptionTracks) : SubscriptionState {
        override fun toString(): String = "SubscriptionState.Playable(<redacted>)"
    }

    /** The subscription reached one immutable terminal result. */
    public class Terminal(public val reason: SubscriptionTerminalReason) : SubscriptionState {
        override fun toString(): String = "SubscriptionState.Terminal(<redacted>)"
    }
}

/** Payload-free reason a subscription stopped being usable. */
@SubscriptionInfrastructureApi
public sealed interface SubscriptionTerminalReason {
    /** Explicit local close completed. */
    public data object Closed : SubscriptionTerminalReason

    /** Legacy terminal classification; an ordered stream stop now interrupts rather than ends it. */
    public data object Stopped : SubscriptionTerminalReason

    /** The owning connection generation was replaced. */
    public data object GenerationLost : SubscriptionTerminalReason

    /** The remote peer closed the transport between protocol frames. */
    public data object RemoteEof : SubscriptionTerminalReason

    /** Transport I/O failed. */
    public data object IoFailure : SubscriptionTerminalReason

    /** Protocol framing failed or EOF split a frame. */
    public data object FramingFailure : SubscriptionTerminalReason

    /** A recognized subscription message was malformed. */
    public data object MalformedMessage : SubscriptionTerminalReason

    /**
     * Transport timeout teardown terminated the stream, or a stopped segment received no
     * validated restart tracks within the restart deadline.
     */
    public data object Timeout : SubscriptionTerminalReason

    /** The local protocol connection explicitly retired the stream. */
    public data object LocalRetirement : SubscriptionTerminalReason

    /** Typed-event publication failed inside the protocol connection. */
    public data object PublicationFailure : SubscriptionTerminalReason

    /** The protocol reader failed without a more specific safe classification. */
    public data object InternalFailure : SubscriptionTerminalReason

    /** Legacy unattributed transport closure retained for SDK compatibility. */
    public data object TransportClosed : SubscriptionTerminalReason

    /** Started did not contain a usable nonempty unique track set. */
    public data object InvalidTracks : SubscriptionTerminalReason

    /** Legacy terminal classification; validated replacement tracks now start a new segment. */
    public data object TrackReconfigurationUnsupported : SubscriptionTerminalReason

    /** The ordered stream completed without a terminal event or local unsubscribe. */
    public data object UnexpectedStreamClosure : SubscriptionTerminalReason

    /** The ordered event consumer failed. */
    public data object ConsumerFailed : SubscriptionTerminalReason

    /** Subscription infrastructure failed without a safe detailed reason. */
    public data object InfrastructureFailed : SubscriptionTerminalReason

    /** Typed protocol operation failure. */
    public class OperationFailed(public val failure: SubscriptionOperationFailure) :
        SubscriptionTerminalReason {
        override fun toString(): String = "SubscriptionTerminalReason.OperationFailed"
    }

    /** The seek gate invalidated the subscription rather than mixing uncertain packets. */
    public class SeekInvalidated(public val cause: SubscriptionSeekInvalidation) :
        SubscriptionTerminalReason {
        override fun toString(): String = "SubscriptionTerminalReason.SeekInvalidated"
    }
}

/** Payload-free reason the seek path invalidated a subscription. */
@SubscriptionInfrastructureApi
public enum class SubscriptionSeekInvalidation {
    /** No ordered acknowledgement arrived before the gate deadline. */
    ACKNOWLEDGEMENT_TIMEOUT,

    /** Withheld packets exceeded the bounded gate capacity. */
    PENDING_QUEUE_OVERFLOW,

    /** The request outcome is unknown, so pre-seek and post-seek packets cannot be separated. */
    UNCERTAIN_REQUEST_OUTCOME,

    /** The acknowledgement carried an unrecognized result. */
    UNRECOGNIZED_ACKNOWLEDGEMENT,

    /**
     * The resumed segment never produced a packet able to re-anchor the delivered timeline.
     *
     * Only a packet carrying a presentation time can define the shared offset, so a segment that
     * omits them entirely ends the subscription instead of discarding every remaining packet
     * while still reporting a playable state.
     * This also covers a restarted segment that repeatedly falls below an established seek floor
     * until its bounded discard budget is exhausted.
     */
    RESUMED_SEGMENT_UNANCHORABLE,
}

/** Typed outcome of one gated timeshift positioning request. */
@SubscriptionInfrastructureApi
public sealed interface SubscriptionSeekResult {
    /** The ordered acknowledgement accepted the request and withheld packets were discarded. */
    public data object Accepted : SubscriptionSeekResult

    /** Accepted with a request-correlated absolute server reader coordinate, not displayed content. */
    public class AcceptedAt(public val position: Duration) : SubscriptionSeekResult {
        init {
            require(position.isFinite() && !position.isNegative()) { "Reached position must be finite and non-negative" }
        }

        override fun toString(): String = "SubscriptionSeekResult.AcceptedAt"
    }

    /** The ordered acknowledgement rejected the request and withheld packets were replayed. */
    public data object Rejected : SubscriptionSeekResult

    /** The server refused the command itself; withheld packets were replayed unchanged. */
    public class Refused(public val failure: SubscriptionOperationFailure) :
        SubscriptionSeekResult {
        override fun toString(): String = "SubscriptionSeekResult.Refused"
    }

    /** The subscription holds no server-granted timeshift buffer. */
    public data object NotSeekable : SubscriptionSeekResult

    /** An earlier request is still awaiting its ordered acknowledgement. */
    public data object AlreadyPending : SubscriptionSeekResult

    /** Reserved for transports that can prove an unacknowledged request changed no playback state. */
    public data object NotAcknowledged : SubscriptionSeekResult

    /** The gate invalidated the subscription instead of mixing uncertain packets. */
    public class Invalidated(public val cause: SubscriptionSeekInvalidation) :
        SubscriptionSeekResult {
        override fun toString(): String = "SubscriptionSeekResult.Invalidated"
    }

    /** The subscription became terminal before the request resolved. */
    public data object SubscriptionEnded : SubscriptionSeekResult

    /** The expected segment was replaced or has no playable tracks. No request was registered. */
    public data object SegmentUnavailable : SubscriptionSeekResult
}

/** Payload-free subscription operation failure. */
@SubscriptionInfrastructureApi
public enum class SubscriptionOperationFailure {
    SERVER_REJECTED,
    ACCESS_DENIED,
    CONNECTION_LIMIT,
    TIMEOUT,
    TRANSPORT_UNAVAILABLE,
    NOT_SUPPORTED,
}

/** Durable redacted numeric diagnostics for one subscription. */
@SubscriptionInfrastructureApi
public class SubscriptionDiagnostics internal constructor(
    public val condition: SubscriptionCondition,
    public val graceTimeoutSeconds: Long?,
    public val droppedPacketCount: Long,
    public val droppedPacketCountOverflowed: Boolean,
    /** Times the delivered timeline re-anchored after an accepted skip. */
    public val timestampAnchorCount: Long,
    /** Packets discarded while awaiting a re-anchor or below the post-seek floor. */
    public val rebaseDiscardedPacketCount: Long,
    /**
     * Teardown cancelled consumer code that was still running when a teardown grace period
     * expired; the close result then reports cleanup failure. The value is final once
     * [ActiveSubscription.close] or [SubscriptionManager.closeAndJoin] returns, which can be after
     * [ActiveSubscription.state] became terminal.
     */
    public val consumerDrainTimedOut: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is SubscriptionDiagnostics &&
        condition == other.condition &&
        graceTimeoutSeconds == other.graceTimeoutSeconds &&
        droppedPacketCount == other.droppedPacketCount &&
        droppedPacketCountOverflowed == other.droppedPacketCountOverflowed &&
        timestampAnchorCount == other.timestampAnchorCount &&
        rebaseDiscardedPacketCount == other.rebaseDiscardedPacketCount &&
        consumerDrainTimedOut == other.consumerDrainTimedOut

    override fun hashCode(): Int {
        var result = condition.hashCode()
        result = 31 * result + (graceTimeoutSeconds?.hashCode() ?: 0)
        result = 31 * result + droppedPacketCount.hashCode()
        result = 31 * result + droppedPacketCountOverflowed.hashCode()
        result = 31 * result + timestampAnchorCount.hashCode()
        result = 31 * result + rebaseDiscardedPacketCount.hashCode()
        return 31 * result + consumerDrainTimedOut.hashCode()
    }

    override fun toString(): String = "SubscriptionDiagnostics(<redacted>)"
}

/** Result of opening a subscription. */
@SubscriptionInfrastructureApi
public sealed interface SubscriptionOpenResult {
    /** A playable active subscription. */
    public class Opened(public val subscription: ActiveSubscription) : SubscriptionOpenResult {
        override fun toString(): String = "SubscriptionOpenResult.Opened(<redacted>)"
    }

    /** Admission is closed for the connection generation. */
    public data object NotReady : SubscriptionOpenResult

    /** Every unsigned 32-bit identifier has been consumed by this generation. */
    public data object IdExhausted : SubscriptionOpenResult

    /** The selected profile was not discovered on this connection generation. */
    public data object ProfileUnavailable : SubscriptionOpenResult

    /** Startup reached a typed terminal result before becoming playable. */
    public class Failed(public val reason: SubscriptionTerminalReason) : SubscriptionOpenResult {
        override fun toString(): String = "SubscriptionOpenResult.Failed(<redacted>)"
    }
}

/** Result of explicit subscription cleanup. */
@SubscriptionInfrastructureApi
public enum class SubscriptionCloseResult { CLOSED, CLEANUP_FAILED }

/** Active generation-bound subscription handle. */
@SubscriptionInfrastructureApi
public interface ActiveSubscription {
    /** Stable durable lifecycle state. */
    public val state: StateFlow<SubscriptionState>

    /** Stable durable diagnostics. */
    public val diagnostics: StateFlow<SubscriptionDiagnostics>

    /** Timeshift buffer granted by the server, or null when it granted none. */
    public val grantedTimeshiftPeriod: Duration?

    /**
     * Requests one timeshift position change and returns only after its gate resolves.
     *
     * Mux packets and drop markers committed before the ordered acknowledgement are withheld from
     * the event consumer. An accepted acknowledgement discards them, a rejected acknowledgement
     * replays them through unchanged consumer state, and an uncertain outcome invalidates this
     * subscription instead of mixing pre-seek and post-seek packets.
     *
     * An accepted acknowledgement also rebases the resumed segment onto the delivered timeline, so
     * a repeated recording position is never presented at or below the last timestamp delivered
     * before this request. A bounded prefix of the resumed segment is discarded while the timeline
     * re-anchors, as documented on [SubscriptionEventConsumer.accept], and a segment that never
     * becomes anchorable ends the subscription with
     * [SubscriptionSeekInvalidation.RESUMED_SEGMENT_UNANCHORABLE] rather than discarding for the
     * remainder of the session.
     *
     * Requests are serialized: a second call while one is pending returns
     * [SubscriptionSeekResult.AlreadyPending]. Caller cancellation propagates and leaves the
     * pending gate under subscription ownership.
     * A stop or repeated start during an unresolved seek invalidates this subscription because the
     * protocol acknowledgement has no request or segment correlation. Otherwise stop is nonterminal.
     */
    public suspend fun seek(target: SubscriptionSeekTarget): SubscriptionSeekResult

    /**
     * Registers a seek only if [expectedTracks] is still the playable segment, atomically with
     * the seek gate. Retained content coordinates must use this overload across stream restarts.
     */
    public suspend fun seek(
        target: SubscriptionSeekTarget,
        expectedTracks: SubscriptionTracks,
    ): SubscriptionSeekResult

    /**
     * Requests server-side pause (`0`) or normal delivery (`100`).
     *
     * The ordered [SubscriptionEvent.Speed] event remains authoritative for observed state.
     */
    public suspend fun setSpeed(speed: Int): SubscriptionOperationResult<Unit> =
        SubscriptionOperationResult.NotSupported

    /**
     * Requests a server priority change for this accepted subscription.
     *
     * Returns [SubscriptionOperationResult.TransportUnavailable] before the server accepted the
     * subscription or after it ended or began closing. The default implementation returns
     * [SubscriptionOperationResult.NotSupported].
     */
    public suspend fun setPriority(priority: LiveSubscriptionPriority): SubscriptionOperationResult<Unit> =
        SubscriptionOperationResult.NotSupported

    /** Closes, unsubscribes, and joins this subscription exactly once. */
    public suspend fun close(): SubscriptionCloseResult
}

/** Opens subscriptions without exposing generation admission or teardown controls. */
@SubscriptionInfrastructureApi
public fun interface SubscriptionOpener {
    /**
     * Opens a subscription and returns only after it is playable or terminal.
     *
     * A session may admit a channel from its retained same-process catalog while initial metadata
     * is still synchronizing. A cold session without that catalog returns
     * [SubscriptionOpenResult.NotReady]. A channel absent from the retained catalog returns a
     * terminal server-rejected failure for that early attempt and may be retried after Ready.
     *
     * A positive [timeshiftPeriod] requests a server-side timeshift buffer. Only a subscription
     * whose server granted one can be repositioned through [ActiveSubscription.seek].
     */
    public suspend fun open(
        channelId: SubscriptionChannelId,
        consumer: SubscriptionEventConsumer,
        timeshiftPeriod: Duration,
    ): SubscriptionOpenResult

    /** Opens a live subscription with explicit profile and timeshift options. */
    public suspend fun open(
        channelId: SubscriptionChannelId,
        consumer: SubscriptionEventConsumer,
        options: SubscriptionOptions,
    ): SubscriptionOpenResult = if (options.streamProfileUuid == null) {
        open(channelId, consumer, options.timeshiftPeriod)
    } else {
        SubscriptionOpenResult.ProfileUnavailable
    }

    /**
     * Opens a live subscription without requesting a timeshift buffer.
     *
     * A functional interface cannot carry a default argument, so the live case stays an explicit
     * convenience over the single abstract member.
     */
    public suspend fun open(
        channelId: SubscriptionChannelId,
        consumer: SubscriptionEventConsumer,
    ): SubscriptionOpenResult = open(channelId, consumer, Duration.ZERO)
}

/** Owns every subscription and identifier for one connection generation. */
@SubscriptionInfrastructureApi
public interface SubscriptionManager : SubscriptionOpener {
    /** Opens admission when the owning session's streaming stage begins. */
    public fun startAdmission()

    /** Stops new admission immediately before the owning streaming stage ends. */
    public fun stopAdmission()

    /** Opens a subscription and returns only after it is playable or terminal. */
    override suspend fun open(
        channelId: SubscriptionChannelId,
        consumer: SubscriptionEventConsumer,
        timeshiftPeriod: Duration,
    ): SubscriptionOpenResult

    /** Stops admission, closes every admitted subscription, and joins all work. */
    public suspend fun closeAndJoin()
}

/** Creates an initially non-admitting manager for one generation-bound [connection]. */
@SubscriptionInfrastructureApi
public fun createSubscriptionManager(
    connection: SubscriptionConnection,
    dispatcher: CoroutineDispatcher,
): SubscriptionManager = SubscriptionManagerImpl(connection, dispatcher)

/** Creates a manager with injectable bounds and a pre-cancellation handoff for deterministic tests. */
internal fun createSubscriptionManager(
    connection: SubscriptionConnection,
    dispatcher: CoroutineDispatcher,
    seekGate: SeekGateSettings,
    rebase: TimestampRebaseSettings = TimestampRebaseSettings(),
    beforeTeardownCancellation: (suspend () -> Unit)? = null,
): SubscriptionManager =
    SubscriptionManagerImpl(
        connection, dispatcher, seekGate = seekGate, rebase = rebase,
        beforeTeardownCancellation = beforeTeardownCancellation,
    )

/** Bounded seek-gate limits kept internal and injectable. */
internal class SeekGateSettings(
    internal val acknowledgementTimeout: Duration = DEFAULT_SEEK_ACKNOWLEDGEMENT_TIMEOUT,
    internal val maximumPendingEvents: Int = DEFAULT_SEEK_PENDING_EVENTS,
    internal val maximumPendingBytes: Long = DEFAULT_SEEK_PENDING_BYTES,
) {
    init {
        require(acknowledgementTimeout > Duration.ZERO) {
            "Seek acknowledgement timeout must be positive"
        }
        require(maximumPendingEvents > 0) { "Seek gate capacity must be positive" }
        require(maximumPendingBytes > 0L) { "Seek gate byte capacity must be positive" }
    }
}

private val DEFAULT_SEEK_ACKNOWLEDGEMENT_TIMEOUT = 5.seconds
private val DEFAULT_RESTART_TIMEOUT = 5.seconds

/** Each teardown wait for consumer code lasts no longer than a stopped stream waits for restart metadata. */
private val TEARDOWN_CONSUMER_GRACE = DEFAULT_RESTART_TIMEOUT
private const val DEFAULT_SEEK_PENDING_EVENTS = 2_048
private const val DEFAULT_SEEK_PENDING_BYTES = 16L * 1024L * 1024L
private const val PAUSED_SUBSCRIPTION_SPEED = 0
private const val NORMAL_SUBSCRIPTION_SPEED = 100
internal class SubscriptionIdAllocator(private var next: Long = 0L) {
    internal fun allocate(): SubscriptionId? = if (next > 0xffff_ffffL) {
        null
    } else {
        SubscriptionId(next).also { next += 1L }
    }
}

private class SubscriptionManagerImpl(
    private val connection: SubscriptionConnection,
    private val dispatcher: CoroutineDispatcher,
    initialSubscriptionId: Long = 0L,
    private val seekGate: SeekGateSettings = SeekGateSettings(),
    private val rebase: TimestampRebaseSettings = TimestampRebaseSettings(),
    private val beforeTeardownCancellation: (suspend () -> Unit)? = null,
) : SubscriptionManager {
    private val lock = Any()
    private val rootJob = SupervisorJob()
    private val scope = CoroutineScope(dispatcher + rootJob)
    private val allocator = SubscriptionIdAllocator(initialSubscriptionId)
    private val active = LinkedHashMap<Any, ActiveSubscriptionImpl>()
    private var accepting = false
    private var closed = false

    override fun startAdmission() {
        synchronized(lock) {
            check(!closed) { "Subscription manager is closed" }
            accepting = true
        }
    }

    override fun stopAdmission() {
        val handles = synchronized(lock) {
            accepting = false
            active.values.toList()
        }
        handles.forEach(ActiveSubscriptionImpl::requestClose)
    }

    override suspend fun open(
        channelId: SubscriptionChannelId,
        consumer: SubscriptionEventConsumer,
        timeshiftPeriod: Duration,
    ): SubscriptionOpenResult = open(
        channelId,
        consumer,
        SubscriptionOptions(timeshiftPeriod = timeshiftPeriod),
    )

    override suspend fun open(
        channelId: SubscriptionChannelId,
        consumer: SubscriptionEventConsumer,
        options: SubscriptionOptions,
    ): SubscriptionOpenResult {
        currentCoroutineContext().ensureActive()
        val token = Any()
        val handle = synchronized(lock) {
            if (!accepting || closed) return SubscriptionOpenResult.NotReady
            val id = allocator.allocate() ?: return SubscriptionOpenResult.IdExhausted
            ActiveSubscriptionImpl(
                id = id,
                channelId = channelId,
                connection = connection,
                consumer = consumer,
                dispatcher = dispatcher,
                scope = scope,
                seekGate = seekGate,
                rebase = rebase,
                beforeTeardownCancellation = beforeTeardownCancellation,
                options = options,
                tryCommitPlayable = { candidate, publication ->
                    connection.commitIfLive {
                        synchronized(lock) {
                            if (!accepting || closed || active[token] !== candidate) {
                                PlayableCommitResult.LOCALLY_REJECTED
                            } else {
                                publication()
                                PlayableCommitResult.COMMITTED
                            }
                        }
                    } ?: PlayableCommitResult.GENERATION_GONE
                },
                onFinished = { synchronized(lock) { active.remove(token) } },
            ).also { active[token] = it }
        }
        handle.start()
        return try {
            handle.awaitOpen()
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                try {
                    handle.close()
                } catch (_: CancellationException) {
                    // Preserve the cancellation that interrupted open.
                }
            }
            throw cancellation
        }
    }

    override suspend fun closeAndJoin() {
        val callerContext = currentCoroutineContext()
        val callerCancellation: CancellationException? = try {
            currentCoroutineContext().ensureActive()
            null
        } catch (cancellation: CancellationException) {
            cancellation
        }
        val handles = synchronized(lock) {
            check(handlesDoNotOwnCaller(handles = active.values, callerContext = callerContext)) {
                "Subscription manager cannot join from its event consumer"
            }
            accepting = false
            closed = true
            active.values.toList()
        }
        var childCancellation: CancellationException? = null
        withContext(NonCancellable) {
            handles.forEach(ActiveSubscriptionImpl::requestClose)
            handles.forEach { handle ->
                val outcome = handle.awaitFinishedForManager()
                if (childCancellation == null) childCancellation = outcome.cancellation
            }
            rootJob.cancelAndJoin()
        }
        callerCancellation?.let { throw it }
        currentCoroutineContext().ensureActive()
        childCancellation?.let { throw it }
    }

    private fun handlesDoNotOwnCaller(
        handles: Collection<ActiveSubscriptionImpl>,
        callerContext: CoroutineContext,
    ): Boolean = handles.none { handle -> handle.deliversIn(callerContext) }
}

private class ActiveSubscriptionImpl(
    private val id: SubscriptionId,
    private val channelId: SubscriptionChannelId,
    private val connection: SubscriptionConnection,
    private val consumer: SubscriptionEventConsumer,
    private val dispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val seekGate: SeekGateSettings,
    rebase: TimestampRebaseSettings,
    private val beforeTeardownCancellation: (suspend () -> Unit)?,
    private val options: SubscriptionOptions,
    private val tryCommitPlayable: (
        ActiveSubscriptionImpl,
        publication: () -> Unit,
    ) -> PlayableCommitResult,
    private val onFinished: () -> Unit,
) : ActiveSubscription {
    private val lock = Any()
    private val deliveryMutex = Mutex()

    /** Committed under [lock], invoked outside every monitor in commit order. */
    private val notifications = ArrayDeque<() -> Unit>()
    private var notifying = false

    /** Mutates state, then releases the monitor before resuming any potentially unconfined caller. */
    private inline fun <T> transition(block: () -> T): T = try {
        synchronized(lock) { block() }
    } finally {
        notifyCommitted()
    }

    /** One drainer preserves publication order without holding the monitor across caller code. */
    private fun notifyCommitted() {
        synchronized(lock) {
            if (notifying) return
            notifying = true
        }
        try {
            while (true) {
                val notification = synchronized(lock) {
                    if (notifications.isEmpty()) {
                        notifying = false
                        return
                    }
                    notifications.removeFirst()
                }
                notification()
            }
        } catch (failure: Throwable) {
            synchronized(lock) { notifying = false }
            throw failure
        }
    }

    /**
     * Waits outside the monitor for this delivery's committed prefix, not for later notifications.
     * Usually [transition] has already drained it and this does not allocate or suspend. Another
     * drainer may still be resuming an unconfined caller, though: FIFO alone does not make the state
     * visible before this coroutine delivers its next boundary or committed replay.
     */
    private suspend fun awaitCommittedNotifications() {
        val published = synchronized(lock) {
            if (!notifying && notifications.isEmpty()) return
            CompletableDeferred<Unit>().also { completion ->
                notifications.addLast { completion.complete(Unit) }
            }
        }
        notifyCommitted()
        published.await()
    }

    /** Detaches the deadline under the monitor; cancelling it can run coroutine completion code. */
    private fun retireInterruptionDeadlineLocked() {
        interruptionDeadline?.let { deadline -> notifications.addLast { deadline.cancel() } }
        interruptionDeadline = null
    }

    /** Confined to [deliveryMutex], which serializes every ordered and replayed delivery. */
    private val rebaser = SubscriptionTimestampRebaser(rebase)
    private val started = AtomicBoolean()
    private val mutableState = MutableStateFlow<SubscriptionState>(SubscriptionState.Starting)

    /**
     * Read-modify-written only under [deliveryMutex], which serializes state application and
     * delivery, so the non-atomic counter updates below cannot interleave.
     */
    private val mutableDiagnostics = MutableStateFlow(emptyDiagnostics())
    private val openCompletion = CompletableDeferred<SubscriptionOpenResult>()
    private val closeRequested = CompletableDeferred<Unit>()
    private val terminalSignal = CompletableDeferred<SubscriptionTerminalReason>()
    private val finished = CompletableDeferred<SubscriptionCloseResult>()
    private var subscribeAccepted = false
    private var grantedTimeshiftSeconds: Long? = null
    private var tracks: SubscriptionTracks? = null
    private var terminal: SubscriptionTerminalReason? = null
    private var closeRequestedFlag = false
    private var playablePublished = false
    private var interruptionDeadline: Job? = null
    private var interruption: Any? = null
    private var terminalCancellation: CancellationException? = null
    private var stopEventCollection = false
    private var consumerEnabled = true
    private var pendingSeek: PendingSeek? = null
    private var seekAdmissionClosed = false
    private val seekDrivers = LinkedHashSet<Job>()

    /** The driver replaying a committed refusal; teardown joins it instead of cancelling it. */
    private var replayingSeekDriver: Job? = null

    /**
     * Set once a closing or ended subscription can no longer place drained packets: an abandoned
     * request left them at an unknown position, or a stop began a segment that is never published.
     */
    private var drainPacketsFenced = false

    /** Set once an ordered [SubscriptionEvent.Terminated] is admitted; it owns the terminal reason. */
    private var streamTerminalAdmitted = false

    /** Identifies the call and delivery job that an expiring teardown grace period may interrupt. */
    private var consumerCallInFlight: ConsumerCall? = null

    /** Set once teardown cancels consumer code that outlasted [TEARDOWN_CONSUMER_GRACE]. */
    private var consumerCancelledByTeardown = false
    /** A grace expiry cancelled live delivery work, even if it had not entered consumer code. */
    private var deliverySkippedByTeardown = false

    /** Carried by every coroutine that runs consumer code, and inherited by the coroutines it starts. */
    private val deliveryContext = ConsumerDelivery(this)

    override val state: StateFlow<SubscriptionState> = mutableState.asStateFlow()
    override val diagnostics: StateFlow<SubscriptionDiagnostics> = mutableDiagnostics.asStateFlow()

    override val grantedTimeshiftPeriod: Duration?
        get() = synchronized(lock) { grantedTimeshiftSeconds }
            ?.takeIf { seconds -> seconds > 0L }
            ?.seconds

    internal fun start() {
        check(started.compareAndSet(false, true)) { "Subscription was already started" }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { runOwner() }
    }

    internal suspend fun awaitOpen(): SubscriptionOpenResult = try {
        openCompletion.await()
    } catch (cancellation: CancellationException) {
        currentCoroutineContext().ensureActive()
        throw synchronized(lock) { terminalCancellation } ?: cancellation
    }

    /**
     * Reports whether [context] belongs to a coroutine that runs this subscription's consumer code,
     * or to one started from it; a joining call from there would wait for its own teardown.
     */
    internal fun deliversIn(context: CoroutineContext): Boolean = context[ConsumerDelivery]?.subscription === this

    internal fun requestClose() {
        transition {
            closeRequestedFlag = true
            // Close owns the terminal reason; the interruption itself still fences stale packets.
            retireInterruptionDeadlineLocked()
            notifications.addLast { closeRequested.complete(Unit) }
        }
    }

    internal suspend fun awaitFinished(): SubscriptionCloseResult = finished.await()

    internal suspend fun awaitFinishedForManager(): FinishedOutcome {
        val result = finished.await()
        return FinishedOutcome(result, synchronized(lock) { terminalCancellation })
    }

    override suspend fun seek(target: SubscriptionSeekTarget): SubscriptionSeekResult = seekSegment(target, null)

    override suspend fun seek(
        target: SubscriptionSeekTarget,
        expectedTracks: SubscriptionTracks,
    ): SubscriptionSeekResult = seekSegment(target, expectedTracks)

    private suspend fun seekSegment(
        target: SubscriptionSeekTarget,
        expectedTracks: SubscriptionTracks?,
    ): SubscriptionSeekResult {
        check(!deliversIn(currentCoroutineContext())) {
            "Subscription seek cannot join from its event consumer"
        }
        currentCoroutineContext().ensureActive()
        val (pending, driver) = synchronized(lock) {
            if (
                terminal != null ||
                closeRequestedFlag ||
                seekAdmissionClosed
            ) {
                return SubscriptionSeekResult.SubscriptionEnded
            }
            if (!playablePublished || (expectedTracks != null && tracks !== expectedTracks)) {
                return SubscriptionSeekResult.SegmentUnavailable
            }
            if ((grantedTimeshiftSeconds ?: 0L) <= 0L) return SubscriptionSeekResult.NotSeekable
            if (pendingSeek != null) return SubscriptionSeekResult.AlreadyPending
            val created = PendingSeek(target)
            // A lazy start runs no user code under the monitor and keeps registration atomic.
            val job = scope.launch(deliveryContext, start = CoroutineStart.LAZY) { driveSeek(created) }
            pendingSeek = created
            seekDrivers += job
            created to job
        }
        driver.invokeOnCompletion { synchronized(lock) { seekDrivers -= driver } }
        driver.start()
        return try {
            pending.outcome.await()
        } catch (cancellation: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw cancellation
        }
    }

    override suspend fun setSpeed(speed: Int): SubscriptionOperationResult<Unit> {
        require(speed == PAUSED_SUBSCRIPTION_SPEED || speed == NORMAL_SUBSCRIPTION_SPEED) {
            "Subscription speed must be 0 or 100"
        }
        currentCoroutineContext().ensureActive()
        val available = synchronized(lock) {
            terminal == null &&
                !closeRequestedFlag &&
                !seekAdmissionClosed &&
                playablePublished &&
                (grantedTimeshiftSeconds ?: 0L) > 0L
        }
        if (!available) return SubscriptionOperationResult.TransportUnavailable
        return invokeCommand { connection.speed(id, speed) }
    }

    override suspend fun setPriority(priority: LiveSubscriptionPriority): SubscriptionOperationResult<Unit> {
        currentCoroutineContext().ensureActive()
        val available = synchronized(lock) {
            terminal == null && !closeRequestedFlag && subscribeAccepted
        }
        if (!available) return SubscriptionOperationResult.TransportUnavailable
        return invokeCommand { connection.changePriority(id, priority) }
    }

    private suspend fun invokeCommand(
        command: suspend () -> SubscriptionOperationResult<Unit>,
    ): SubscriptionOperationResult<Unit> = try {
        val outcome = withContext(dispatcher) {
            try {
                Result.success(command())
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
        outcome.getOrThrow()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        SubscriptionOperationResult.TransportUnavailable
    }

    override suspend fun close(): SubscriptionCloseResult {
        check(!deliversIn(currentCoroutineContext())) {
            "Subscription close cannot join from its event consumer"
        }
        requestClose()
        val result = try {
            finished.await()
        } catch (cancellation: CancellationException) {
            val callerCancellation = try {
                currentCoroutineContext().ensureActive()
                cancellation
            } catch (callerCancellation: CancellationException) {
                callerCancellation
            }
            withContext(NonCancellable) { finished.await() }
            throw callerCancellation
        }
        synchronized(lock) { terminalCancellation }?.let { throw it }
        return result
    }

    private suspend fun runOwner() {
        var closeResult = SubscriptionCloseResult.CLOSED
        var primaryCancellation: CancellationException? = null
        try {
            supervisorScope {
                val collection = async(deliveryContext, start = CoroutineStart.UNDISPATCHED) {
                    collectEvents()
                }
                val subscribe = async { invokeSubscribe() }
                try {
                    var lifecycleComplete = false
                    var subscribeHandled = false
                    while (!lifecycleComplete && currentCoroutineContext().isActive) {
                        when (val outcome = select<OwnerOutcome> {
                            closeRequested.onAwait { OwnerOutcome.Close }
                            terminalSignal.onAwait { OwnerOutcome.Terminal(it) }
                            collection.onAwait { OwnerOutcome.Collection(it) }
                            if (!subscribeHandled) {
                                subscribe.onAwait { OwnerOutcome.Subscribe(it) }
                            }
                        }) {
                            OwnerOutcome.Close -> lifecycleComplete = true
                            is OwnerOutcome.Terminal -> lifecycleComplete = true
                            is OwnerOutcome.Collection -> {
                                val collectionOutcome = outcome.outcome
                                if (collectionOutcome is CollectionOutcome.Cancelled) {
                                    primaryCancellation = collectionOutcome.cancellation
                                } else if (currentTerminal() == null) {
                                    setTerminal(collectionOutcome.toTerminalReason())
                                }
                                lifecycleComplete = true
                            }
                            is OwnerOutcome.Subscribe -> {
                                subscribeHandled = true
                                when (val result = outcome.outcome) {
                                    is CommandOutcome.Result -> when (val value = result.value) {
                                        is SubscriptionOperationResult.Ok -> {
                                            synchronized(lock) {
                                                subscribeAccepted = true
                                                grantedTimeshiftSeconds =
                                                    value.value.timeshiftPeriodSeconds
                                            }
                                            tryPublishPlayable()
                                        }
                                        SubscriptionOperationResult.ServerRejected,
                                        SubscriptionOperationResult.AccessDenied,
                                        SubscriptionOperationResult.ConnectionLimit,
                                        SubscriptionOperationResult.Timeout,
                                        SubscriptionOperationResult.TransportUnavailable,
                                        SubscriptionOperationResult.NotSupported,
                                        -> {
                                            setStartupFailure(
                                                SubscriptionTerminalReason.OperationFailed(
                                                    value.toFailure(),
                                                ),
                                            )
                                            lifecycleComplete = true
                                        }
                                    }
                                    CommandOutcome.Failed -> {
                                        setStartupFailure(SubscriptionTerminalReason.InfrastructureFailed)
                                        lifecycleComplete = true
                                    }
                                    is CommandOutcome.Cancelled -> {
                                        primaryCancellation = result.cancellation
                                        lifecycleComplete = true
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    withContext(NonCancellable) {
                        subscribe.cancelAndJoin()
                        val (drivers, replaying) = synchronized(lock) {
                            seekAdmissionClosed = true
                            seekDrivers.filter { driver -> driver !== replayingSeekDriver } to
                                replayingSeekDriver
                        }
                        drivers.forEach { driver -> driver.cancelAndJoin() }
                        // A committed replay may finish within the grace period before it is cancelled.
                        if (replaying != null && !joinWithinGrace(replaying)) cancelAfterGrace(replaying)
                        transition { pendingSeek?.let(::abandonSeekLocked) }
                        val cleanup = cleanUpCollection(collection)
                        closeResult = cleanup.result
                        if (primaryCancellation == null) {
                            primaryCancellation = cleanup.cancellation
                        }
                        if (synchronized(lock) { deliverySkippedByTeardown }) {
                            closeResult = SubscriptionCloseResult.CLEANUP_FAILED
                        }
                        if (synchronized(lock) { consumerCancelledByTeardown }) {
                            // Every delivery has ended, so the free mutex keeps diagnostics confined to it.
                            deliveryMutex.withLock {
                                mutableDiagnostics.value = mutableDiagnostics.value.with(consumerDrainTimedOut = true)
                            }
                        }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            primaryCancellation = cancellation
        } catch (_: Exception) {
            setTerminal(SubscriptionTerminalReason.InfrastructureFailed)
            closeResult = SubscriptionCloseResult.CLEANUP_FAILED
        } finally {
            val finalReason = synchronized(lock) {
                terminal ?: if (closeRequestedFlag) {
                    SubscriptionTerminalReason.Closed
                } else {
                    SubscriptionTerminalReason.InfrastructureFailed
                }
            }
            primaryCancellation = synchronized(lock) {
                if (terminalCancellation == null) terminalCancellation = primaryCancellation
                terminalCancellation
            }
            val cancellation = primaryCancellation
            if (cancellation != null) closeResult = SubscriptionCloseResult.CLEANUP_FAILED
            val result = closeResult
            transition {
                setTerminalLocked(finalReason, completeOpen = false)
                if (cancellation != null) {
                    notifications.addLast { openCompletion.completeExceptionally(cancellation) }
                }
                if (cancellation == null) {
                    notifications.addLast { openCompletion.complete(SubscriptionOpenResult.Failed(finalReason)) }
                }
                notifications.addLast {
                    onFinished()
                    finished.complete(result)
                }
            }
        }
        primaryCancellation?.let { throw it }
    }

    private suspend fun collectEvents(): CollectionOutcome {
        return try {
            connection.events(id).takeWhile { event -> acceptEvent(event) }.collect()
            if (synchronized(lock) { terminal } == null) {
                CollectionOutcome.Completed
            } else {
                CollectionOutcome.Terminal
            }
        } catch (cancellation: CancellationException) {
            CollectionOutcome.Cancelled(cancellation)
        } catch (_: Exception) {
            CollectionOutcome.Failed
        }
    }

    private suspend fun invokeSubscribe(): CommandOutcome = try {
        CommandOutcome.Result(connection.subscribe(id, channelId, options))
    } catch (cancellation: CancellationException) {
        CommandOutcome.Cancelled(cancellation)
    } catch (_: Exception) {
        CommandOutcome.Failed
    }

    private suspend fun acceptEvent(event: SubscriptionEvent): Boolean {
        deliveryMutex.withLock {
            // Fence the segment and its seek admission before any boundary callback can re-enter.
            if (event is SubscriptionEvent.Stopped ||
                (event is SubscriptionEvent.Started && synchronized(lock) { tracks != null || interruption != null })
            ) {
                interruptSegment()
            }
            val decision = admitToGate(event)
            awaitCommittedNotifications()
            when (decision) {
                GateDecision.Pass -> deliverToConsumer(event)
                GateDecision.Withheld, GateDecision.Discarded -> Unit
                is GateDecision.Resolved -> {
                    decision.replayed.forEach { withheld -> deliverToConsumer(withheld) }
                    deliverToConsumer(event)
                }
            }
            // Ordered terminal delivery precedes terminal state so consumers can preserve clean EOS.
            applyEventState(event)
            awaitCommittedNotifications()
        }
        return synchronized(lock) { !stopEventCollection }
    }

    /**
     * Classifies one committed event against the seek gate.
     *
     * Mux packets and drop markers are withheld while an acknowledgement is pending; every other
     * event is delivered immediately so terminal handling is never delayed by a gate.
     */
    private fun admitToGate(event: SubscriptionEvent): GateDecision = transition {
        if (event is SubscriptionEvent.Terminated) {
            // Its state applies only after the consumer returns, so no deadline may expire first.
            streamTerminalAdmitted = true
            interruption = null
            retireInterruptionDeadlineLocked()
        }
        if (interruption != null && tracks == null && (event is SubscriptionEvent.Packet || event is SubscriptionEvent.Dropped)) {
            return GateDecision.Discarded
        }
        if (drainPacketsFenced && (event is SubscriptionEvent.Packet || event is SubscriptionEvent.Dropped)) {
            return GateDecision.Discarded
        }
        val pending = pendingSeek ?: return GateDecision.Pass
        val closing = closeRequestedFlag || seekAdmissionClosed
        when (event) {
            is SubscriptionEvent.Packet, is SubscriptionEvent.Dropped -> {
                val bytes = (event as? SubscriptionEvent.Packet)?.payload?.size?.toLong() ?: 0L
                if (pending.canAccept(bytes, seekGate)) {
                    pending.enqueue(event, bytes)
                    GateDecision.Withheld
                } else if (closing) {
                    abandonSeekLocked(pending)
                    GateDecision.Discarded
                } else {
                    val cause = SubscriptionSeekInvalidation.PENDING_QUEUE_OVERFLOW
                    pending.discard()
                    resolveSeekLocked(
                        pending = pending,
                        result = SubscriptionSeekResult.Invalidated(cause),
                        terminalReason = SubscriptionTerminalReason.SeekInvalidated(cause),
                    )
                    GateDecision.Discarded
                }
            }
            is SubscriptionEvent.Skipped -> when (event.outcome) {
                SkipOutcome.ACCEPTED -> {
                    pending.discard()
                    val reached = event.time?.takeIf { event.absolute == true && it >= 0L }
                    resolveSeekLocked(
                        pending,
                        reached?.let { SubscriptionSeekResult.AcceptedAt(it.microseconds) }
                            ?: SubscriptionSeekResult.Accepted,
                    )
                    GateDecision.Resolved(emptyList())
                }
                SkipOutcome.REJECTED -> {
                    val replayed = pending.drain()
                    resolveSeekLocked(pending, SubscriptionSeekResult.Rejected)
                    GateDecision.Resolved(replayed)
                }
                SkipOutcome.UNKNOWN -> {
                    if (closing) {
                        abandonSeekLocked(pending)
                    } else {
                        val cause = SubscriptionSeekInvalidation.UNRECOGNIZED_ACKNOWLEDGEMENT
                        pending.discard()
                        resolveSeekLocked(
                            pending = pending,
                            result = SubscriptionSeekResult.Invalidated(cause),
                            terminalReason = SubscriptionTerminalReason.SeekInvalidated(cause),
                        )
                    }
                    GateDecision.Resolved(emptyList())
                }
            }
            else -> GateDecision.Pass
        }
    }

    /**
     * Removes [pending] from the gate, completes its caller outcome, and optionally invalidates
     * the subscription.
     *
     * The outcome completes before any withheld event is delivered so a cancelled replay can never
     * strand the caller.
     */
    private fun resolveSeekLocked(
        pending: PendingSeek,
        result: SubscriptionSeekResult,
        terminalReason: SubscriptionTerminalReason? = null,
    ) {
        if (pendingSeek === pending) pendingSeek = null
        terminalReason?.let { reason ->
            setTerminalLocked(reason, stopCollection = true, completeOpen = false)
        }
        notifications.addLast { pending.outcome.complete(result) }
    }

    /**
     * Ends [pending] with its subscription while something else owns the terminal reason: close,
     * an admitted ordered terminal, or owner teardown after subscribe or collection cancellation.
     *
     * Later packets of the closing drain have the same unknown position as the withheld ones, so
     * they are discarded rather than delivered; controls and terminal events still pass.
     */
    private fun abandonSeekLocked(pending: PendingSeek) {
        pending.discard()
        drainPacketsFenced = true
        resolveSeekLocked(pending, SubscriptionSeekResult.SubscriptionEnded)
    }

    /**
     * Issues the request and bounds the wait for its ordered acknowledgement.
     *
     * Only the request and the acknowledgement wait are deadline-bounded. Replaying withheld
     * events happens afterwards so an expiring deadline can never deliver a partial replay.
     */
    private suspend fun driveSeek(pending: PendingSeek) {
        if (synchronized(lock) { pendingSeek !== pending }) return
        val bounded = try {
            withTimeoutOrNull(seekGate.acknowledgementTimeout) {
                when (val result = invokeSkip(pending.target)) {
                    null -> SeekResolution.Invalidate(
                        SubscriptionSeekInvalidation.UNCERTAIN_REQUEST_OUTCOME,
                    )
                    is SubscriptionOperationResult.Ok -> {
                        pending.outcome.await()
                        SeekResolution.Acknowledged
                    }
                    // A timed out command may still have executed, so replaying could mix packets.
                    SubscriptionOperationResult.Timeout -> SeekResolution.Invalidate(
                        SubscriptionSeekInvalidation.UNCERTAIN_REQUEST_OUTCOME,
                    )
                    else -> SeekResolution.Replay(
                        SubscriptionSeekResult.Refused(result.toFailure()),
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            transition {
                // An ordered acknowledgement or owner teardown may already own settlement.
                if (
                    pendingSeek === pending &&
                    terminal == null &&
                    !closeRequestedFlag &&
                    !seekAdmissionClosed &&
                    !streamTerminalAdmitted
                ) {
                    pendingSeek = null
                    pending.discard()
                    consumerEnabled = false
                    setTerminalLocked(
                        SubscriptionTerminalReason.SeekInvalidated(SubscriptionSeekInvalidation.UNCERTAIN_REQUEST_OUTCOME),
                        stopCollection = true,
                        completeOpen = false,
                    )
                    notifications.addLast { pending.outcome.completeExceptionally(cancellation) }
                }
            }
            throw cancellation
        }
        val resolution = bounded
            ?: SeekResolution.Invalidate(SubscriptionSeekInvalidation.ACKNOWLEDGEMENT_TIMEOUT)
        when (resolution) {
            SeekResolution.Acknowledged -> Unit
            is SeekResolution.Replay -> replaySeek(pending, resolution.result)
            is SeekResolution.Invalidate -> invalidateSeek(pending, resolution.cause)
        }
    }

    private suspend fun invokeSkip(
        target: SubscriptionSeekTarget,
    ): SubscriptionOperationResult<Unit>? = try {
        connection.skip(id, target)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    /**
     * Completes [pending] and then replays every withheld event in committed order.
     *
     * The replay commits under the same monitor that closes seek admission, so teardown either
     * abandons the request before the commit or joins the whole replay after it.
     */
    private suspend fun replaySeek(pending: PendingSeek, result: SubscriptionSeekResult) {
        val driver = currentCoroutineContext()[Job]
        deliveryMutex.withLock {
            val replayed = transition {
                if (pendingSeek !== pending || seekAdmissionClosed) return@withLock
                replayingSeekDriver = driver
                pending.drain().also { resolveSeekLocked(pending, result) }
            }
            try {
                awaitCommittedNotifications()
                replayed.forEach { withheld -> deliverToConsumer(withheld) }
            } finally {
                synchronized(lock) { if (replayingSeekDriver === driver) replayingSeekDriver = null }
            }
        }
    }

    /** Discards withheld events and ends the subscription rather than mixing uncertain packets. */
    private fun invalidateSeek(pending: PendingSeek, cause: SubscriptionSeekInvalidation) {
        transition {
            if (pendingSeek !== pending) return
            if (terminal != null || closeRequestedFlag || streamTerminalAdmitted) {
                abandonSeekLocked(pending)
            } else {
                pending.discard()
                // The collection coroutine stops only after its next delivery, so silence the
                // consumer here or one packet of unknown provenance still reaches the readers.
                consumerEnabled = false
                resolveSeekLocked(
                    pending = pending,
                    result = SubscriptionSeekResult.Invalidated(cause),
                    terminalReason = SubscriptionTerminalReason.SeekInvalidated(cause),
                )
            }
        }
    }

    private suspend fun deliverToConsumer(event: SubscriptionEvent) {
        if (!synchronized(lock) { consumerEnabled }) return
        val rebased = when (val decision = rebaser.classify(event)) {
            RebaseDecision.Discard -> {
                addRebaseDiscardedPacket()
                return
            }
            RebaseDecision.Unanchorable -> {
                addRebaseDiscardedPacket()
                invalidateUnanchorableSegment()
                return
            }
            is RebaseDecision.Deliver -> {
                if (decision.anchored) addTimestampAnchor()
                decision.event
            }
        }
        val call = ConsumerCall(checkNotNull(currentCoroutineContext()[Job]))
        synchronized(lock) {
            // An expired teardown grace period skips later calls and cancels only one in flight.
            if (!consumerEnabled) return
            consumerCallInFlight = call
        }
        try {
            consumer.accept(rebased)
        } catch (cancellation: CancellationException) {
            transition {
                consumerEnabled = false
                // Teardown's own cancellation of a call that outlasted its grace period is not a failure.
                if (cancellation.causedByTeardown()) return
                terminalCancellation = terminalCancellation ?: cancellation
                setTerminalLocked(
                    SubscriptionTerminalReason.InfrastructureFailed,
                    stopCollection = false,
                    completeOpen = false,
                )
            }
        } catch (_: Exception) {
            transition {
                consumerEnabled = false
                // Once teardown cancelled this call, its failure follows from that; close keeps its reason.
                if (call.cancelledByTeardown) return
                setTerminalLocked(
                    SubscriptionTerminalReason.ConsumerFailed,
                    stopCollection = false,
                    completeOpen = false,
                )
            }
        } finally {
            synchronized(lock) { consumerCallInFlight = null }
        }
    }

    /**
     * Ends the subscription when the resumed segment can never re-anchor the delivered timeline.
     *
     * Every other uncertain path here is bounded and typed, so an unanchorable segment must not
     * decay into an unbounded silent discard that still reports a playable subscription.
     */
    private fun invalidateUnanchorableSegment() {
        transition {
            // The collection coroutine stops only after its next delivery, so silence the
            // consumer here as well; no later packet can be placed on the delivered timeline.
            consumerEnabled = false
            setTerminalLocked(
                SubscriptionTerminalReason.SeekInvalidated(
                    SubscriptionSeekInvalidation.RESUMED_SEGMENT_UNANCHORABLE,
                ),
                stopCollection = true,
                completeOpen = false,
            )
        }
    }

    private fun applyEventState(event: SubscriptionEvent) {
        when (event) {
            is SubscriptionEvent.Started -> acceptStarted(event)
            is SubscriptionEvent.Status -> updateDiagnostics(condition = event.condition)
            is SubscriptionEvent.Grace -> updateDiagnostics(graceTimeoutSeconds = event.timeoutSeconds)
            is SubscriptionEvent.Dropped -> addDroppedPackets(event.count)
            is SubscriptionEvent.Stopped -> {
                updateDiagnostics(condition = event.condition)
            }
            is SubscriptionEvent.Terminated -> setStreamTerminal(
                when (event.reason) {
                    SubscriptionTermination.GENERATION_LOST ->
                        SubscriptionTerminalReason.GenerationLost
                    SubscriptionTermination.REMOTE_EOF ->
                        SubscriptionTerminalReason.RemoteEof
                    SubscriptionTermination.IO_FAILURE ->
                        SubscriptionTerminalReason.IoFailure
                    SubscriptionTermination.FRAMING_FAILURE ->
                        SubscriptionTerminalReason.FramingFailure
                    SubscriptionTermination.MALFORMED_MESSAGE ->
                        SubscriptionTerminalReason.MalformedMessage
                    SubscriptionTermination.TIMEOUT ->
                        SubscriptionTerminalReason.Timeout
                    SubscriptionTermination.LOCAL_RETIREMENT ->
                        SubscriptionTerminalReason.LocalRetirement
                    SubscriptionTermination.PUBLICATION_FAILURE ->
                        SubscriptionTerminalReason.PublicationFailure
                    SubscriptionTermination.INTERNAL_FAILURE ->
                        SubscriptionTerminalReason.InternalFailure
                    SubscriptionTermination.TRANSPORT_CLOSED ->
                        SubscriptionTerminalReason.TransportClosed
                },
            )
            is SubscriptionEvent.Packet,
            is SubscriptionEvent.Skipped,
            is SubscriptionEvent.Speed,
            is SubscriptionEvent.Timeshift,
            is SubscriptionEvent.Queue,
            is SubscriptionEvent.Signal,
            is SubscriptionEvent.Descramble,
            -> Unit
        }
    }

    private fun acceptStarted(event: SubscriptionEvent.Started) {
        val streams = event.streams
        updateDiagnostics(condition = event.condition)
        if (streams.isNullOrEmpty()) {
            setLocalFailure(SubscriptionTerminalReason.InvalidTracks)
            return
        }
        if (streams.map { it.index }.distinct().size != streams.size) {
            setLocalFailure(SubscriptionTerminalReason.InvalidTracks)
            return
        }
        val candidate = SubscriptionTracks(streams)
        synchronized(lock) {
            if (terminal != null || closeRequestedFlag) return
        }
        // The committed track set decides which stream indices may end an anchor wait.
        rebaser.onTracks(candidate)
        try {
            consumer.tracksReady(candidate)
        } catch (cancellation: CancellationException) {
            transition {
                terminalCancellation = terminalCancellation ?: cancellation
                consumerEnabled = false
                setTerminalLocked(
                    SubscriptionTerminalReason.InfrastructureFailed,
                    stopCollection = false,
                    completeOpen = false,
                )
            }
            return
        } catch (_: Exception) {
            setLocalFailure(SubscriptionTerminalReason.ConsumerFailed)
            return
        }
        transition {
            if (tracks != null || terminal != null || closeRequestedFlag) return
            tracks = candidate
            // Restart metadata ends the interruption; the subscribe command owns its acknowledgement.
            interruption = null
            retireInterruptionDeadlineLocked()
        }
        tryPublishPlayable()
    }

    private fun tryPublishPlayable() {
        val ready = synchronized(lock) {
            subscribeAccepted &&
                tracks != null &&
                terminal == null &&
                !closeRequestedFlag &&
                !playablePublished
        }
        if (!ready) return
        val committed = try {
            tryCommitPlayable(this) {
                synchronized(lock) {
                    val currentTracks = tracks
                    if (
                        subscribeAccepted &&
                        currentTracks != null &&
                        terminal == null &&
                        !closeRequestedFlag &&
                        !playablePublished
                    ) {
                        playablePublished = true
                        interruption = null
                        retireInterruptionDeadlineLocked()
                        notifications.addLast { mutableState.value = SubscriptionState.Playable(currentTracks) }
                        notifications.addLast { openCompletion.complete(SubscriptionOpenResult.Opened(this)) }
                    }
                }
            }
        } finally {
            // The publication callback also holds the manager and connection-generation monitors.
            // This thread does not resume callers until the complete commit boundary has returned;
            // an already-active drainer on another thread can publish the committed notifications.
            notifyCommitted()
        }
        when (committed) {
            PlayableCommitResult.COMMITTED -> Unit
            PlayableCommitResult.LOCALLY_REJECTED ->
                setStartupFailure(SubscriptionTerminalReason.Closed)
            PlayableCommitResult.GENERATION_GONE ->
                setStartupFailure(SubscriptionTerminalReason.GenerationLost)
        }
    }

    private fun setStreamTerminal(reason: SubscriptionTerminalReason) {
        transition { setTerminalLocked(reason, stopCollection = true, completeOpen = false) }
    }

    /**
     * Fences the current segment and bounds the wait for its restart metadata.
     *
     * Repeated stops share one deadline. Committing the validated tracks of a later Started ends
     * the interruption even before the subscribe acknowledgement; close cancels the deadline.
     * A boundary in a closing or ended drain cannot publish its successor, so it only fences the
     * drain's later packets from readers prepared for the delivered tracks.
     */
    private fun interruptSegment() {
        transition {
            if (terminal != null || closeRequestedFlag) {
                drainPacketsFenced = true
                return
            }
            pendingSeek?.let { pending ->
                consumerEnabled = false
                pending.discard()
                val cause = SubscriptionSeekInvalidation.UNCERTAIN_REQUEST_OUTCOME
                resolveSeekLocked(pending, SubscriptionSeekResult.Invalidated(cause),
                    SubscriptionTerminalReason.SeekInvalidated(cause))
                return
            }
            tracks = null
            playablePublished = false
            notifications.addLast { mutableState.value = SubscriptionState.Starting }
            if (interruption == null) {
                val epoch = Any()
                interruption = epoch
                val deadline = scope.launch(start = CoroutineStart.LAZY) {
                    delay(DEFAULT_RESTART_TIMEOUT)
                    transition {
                        if (interruption === epoch && terminal == null && !closeRequestedFlag) {
                            consumerEnabled = false
                            setTerminalLocked(
                                SubscriptionTerminalReason.Timeout,
                                stopCollection = true,
                                completeOpen = false,
                            )
                        }
                    }
                }
                interruptionDeadline = deadline
                notifications.addLast { deadline.start() }
            }
        }
    }

    private fun setLocalFailure(reason: SubscriptionTerminalReason) {
        transition {
            consumerEnabled = false
            setTerminalLocked(reason, stopCollection = false, completeOpen = false)
        }
    }

    private fun setTerminal(reason: SubscriptionTerminalReason) {
        transition { setTerminalLocked(reason, stopCollection = true, completeOpen = false) }
    }

    private fun setStartupFailure(reason: SubscriptionTerminalReason) {
        transition { setTerminalLocked(reason, stopCollection = false, completeOpen = false) }
    }

    private fun currentTerminal(): SubscriptionTerminalReason? = synchronized(lock) { terminal }

    private fun setTerminalLocked(
        reason: SubscriptionTerminalReason,
        stopCollection: Boolean = true,
        completeOpen: Boolean = true,
    ) {
        if (stopCollection) stopEventCollection = true
        if (terminal != null) return
        terminal = reason
        interruption = null
        retireInterruptionDeadlineLocked()
        notifications.addLast { mutableState.value = SubscriptionState.Terminal(reason) }
        pendingSeek?.let { pending ->
            pendingSeek = null
            pending.discard()
            notifications.addLast { pending.outcome.complete(SubscriptionSeekResult.SubscriptionEnded) }
        }
        notifications.addLast { terminalSignal.complete(reason) }
        if (completeOpen) notifications.addLast { openCompletion.complete(SubscriptionOpenResult.Failed(reason)) }
    }

    private fun updateDiagnostics(
        condition: SubscriptionCondition = mutableDiagnostics.value.condition,
        graceTimeoutSeconds: Long? = mutableDiagnostics.value.graceTimeoutSeconds,
    ) {
        mutableDiagnostics.value = mutableDiagnostics.value.with(
            condition = condition,
            graceTimeoutSeconds = graceTimeoutSeconds,
        )
    }

    private fun addDroppedPackets(count: Long) {
        val current = mutableDiagnostics.value
        val overflow = Long.MAX_VALUE - current.droppedPacketCount < count
        mutableDiagnostics.value = current.with(
            droppedPacketCount = if (overflow) Long.MAX_VALUE else current.droppedPacketCount + count,
            droppedPacketCountOverflowed = current.droppedPacketCountOverflowed || overflow,
        )
    }

    private fun addTimestampAnchor() {
        val current = mutableDiagnostics.value
        mutableDiagnostics.value = current.with(
            timestampAnchorCount = saturatingIncrement(current.timestampAnchorCount),
        )
    }

    private fun addRebaseDiscardedPacket() {
        val current = mutableDiagnostics.value
        mutableDiagnostics.value = current.with(
            rebaseDiscardedPacketCount = saturatingIncrement(current.rebaseDiscardedPacketCount),
        )
    }

    /** Waits up to [TEARDOWN_CONSUMER_GRACE] for [job]; a job that completed at the deadline still counts. */
    private suspend fun joinWithinGrace(job: Job): Boolean =
        withTimeoutOrNull(TEARDOWN_CONSUMER_GRACE) { job.join() } != null || job.isCompleted

    /**
     * Cancels [job] after its teardown wait expired. Later deliveries are skipped, and consumer code
     * still running receives a [TeardownCancellation], which never counts as a subscription failure
     * and is never thrown to close callers.
     */
    private suspend fun cancelAfterGrace(job: Job) {
        beforeTeardownCancellation?.invoke()
        val cancel = synchronized(lock) {
            // A completed replay may already have handed delivery to collection. Neither stop that
            // delivery nor attribute its failure to cancellation of the old replay.
            if (job.isActive) {
                consumerEnabled = false
                deliverySkippedByTeardown = true
                consumerCallInFlight?.takeIf { it.owner === job }?.let { call ->
                    call.cancelledByTeardown = true
                    consumerCancelledByTeardown = true
                }
                // The request is committed to this call only. A later call must not inherit it.
                true
            } else false
        }
        // Cancellation may synchronously invoke consumer-installed handlers. Never hold the monitor.
        if (cancel) job.cancel(TeardownCancellation())
        job.join()
    }

    private suspend fun cleanUpCollection(collection: Deferred<CollectionOutcome>): CleanupOutcome =
        withContext(NonCancellable) {
            var cancellation: CancellationException? = null
            val unsubscribe = try {
                connection.unsubscribe(id)
            } catch (failure: CancellationException) {
                cancellation = failure
                null
            } catch (_: Exception) {
                null
            }
            val generationGone = try {
                connection.commitIfLive { true } != true
            } catch (failure: CancellationException) {
                if (cancellation == null) cancellation = failure
                false
            } catch (_: Exception) {
                false
            }
            val collectionOutcome = if (
                unsubscribe is SubscriptionOperationResult.Ok || generationGone
            ) {
                try {
                    if (joinWithinGrace(collection)) {
                        collection.await()
                    } else {
                        cancelAfterGrace(collection)
                        CollectionOutcome.Failed
                    }
                } catch (failure: CancellationException) {
                    if (cancellation == null) cancellation = failure
                    CollectionOutcome.Failed
                } catch (_: Exception) {
                    CollectionOutcome.Failed
                }
            } else {
                // The stream may never end, so stop it between deliveries while the grace period lasts.
                var betweenDeliveries = false
                withTimeoutOrNull(TEARDOWN_CONSUMER_GRACE) {
                    deliveryMutex.lock()
                    // No suspension follows, so a racing timeout cannot hide an acquired lock.
                    betweenDeliveries = true
                }
                if (betweenDeliveries) {
                    try {
                        collection.cancelAndJoin()
                    } finally {
                        deliveryMutex.unlock()
                    }
                } else {
                    cancelAfterGrace(collection)
                }
                CollectionOutcome.Failed
            }
            if (collectionOutcome is CollectionOutcome.Cancelled && cancellation == null) {
                cancellation = collectionOutcome.cancellation
            }
            CleanupOutcome(
                result = if (
                    unsubscribe is SubscriptionOperationResult.Ok &&
                    collectionOutcome != CollectionOutcome.Failed &&
                    collectionOutcome !is CollectionOutcome.Cancelled
                ) {
                    SubscriptionCloseResult.CLOSED
                } else {
                    SubscriptionCloseResult.CLEANUP_FAILED
                },
                cancellation = cancellation,
            )
        }
}

private fun emptyDiagnostics(): SubscriptionDiagnostics = SubscriptionDiagnostics(
    condition = SubscriptionCondition.NO_DETAIL,
    graceTimeoutSeconds = null,
    droppedPacketCount = 0L,
    droppedPacketCountOverflowed = false,
    timestampAnchorCount = 0L,
    rebaseDiscardedPacketCount = 0L,
    consumerDrainTimedOut = false,
)

private fun SubscriptionDiagnostics.with(
    condition: SubscriptionCondition = this.condition,
    graceTimeoutSeconds: Long? = this.graceTimeoutSeconds,
    droppedPacketCount: Long = this.droppedPacketCount,
    droppedPacketCountOverflowed: Boolean = this.droppedPacketCountOverflowed,
    timestampAnchorCount: Long = this.timestampAnchorCount,
    rebaseDiscardedPacketCount: Long = this.rebaseDiscardedPacketCount,
    consumerDrainTimedOut: Boolean = this.consumerDrainTimedOut,
): SubscriptionDiagnostics = SubscriptionDiagnostics(
    condition = condition,
    graceTimeoutSeconds = graceTimeoutSeconds,
    droppedPacketCount = droppedPacketCount,
    droppedPacketCountOverflowed = droppedPacketCountOverflowed,
    timestampAnchorCount = timestampAnchorCount,
    rebaseDiscardedPacketCount = rebaseDiscardedPacketCount,
    consumerDrainTimedOut = consumerDrainTimedOut,
)

/** Counts one more occurrence without wrapping a durable diagnostic counter. */
private fun saturatingIncrement(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1L

/**
 * Marks the coroutines that run [subscription]'s consumer code. Every coroutine started from them
 * inherits it, so a joining lifecycle call from that code fails fast instead of waiting on itself.
 */
private class ConsumerDelivery(
    internal val subscription: ActiveSubscriptionImpl,
) : AbstractCoroutineContextElement(ConsumerDelivery) {
    companion object Key : CoroutineContext.Key<ConsumerDelivery>
}

/** Teardown's cancellation of consumer code that outlasted its grace period; consumers cannot create it. */
private class TeardownCancellation : CancellationException("Subscription teardown cancelled consumer delivery")

/** One invocation, with its owning delivery job; mutable attribution is guarded by the subscription lock. */
private class ConsumerCall(val owner: Job) {
    var cancelledByTeardown: Boolean = false
}

/** Reports whether this cancellation is teardown's own, possibly copied or wrapped on its way back. */
private fun CancellationException.causedByTeardown(): Boolean {
    val seen = HashSet<Throwable>()
    var current: Throwable? = this
    while (current != null && seen.add(current)) {
        if (current is TeardownCancellation) return true
        current = current.cause
    }
    return false
}

/** One in-flight seek request and its bounded queue of withheld data-plane events. */
private class PendingSeek(internal val target: SubscriptionSeekTarget) {
    internal val outcome = CompletableDeferred<SubscriptionSeekResult>()
    private val withheld = ArrayDeque<SubscriptionEvent>()
    private var withheldBytes = 0L

    internal fun canAccept(bytes: Long, settings: SeekGateSettings): Boolean =
        withheld.size < settings.maximumPendingEvents &&
            withheldBytes <= settings.maximumPendingBytes - bytes

    internal fun enqueue(event: SubscriptionEvent, bytes: Long) {
        withheld.addLast(event)
        withheldBytes += bytes
    }

    internal fun drain(): List<SubscriptionEvent> {
        val snapshot = withheld.toList()
        discard()
        return snapshot
    }

    internal fun discard() {
        withheld.clear()
        withheldBytes = 0L
    }
}

private sealed interface SeekResolution {
    /** The ordered acknowledgement already resolved the gate. */
    public data object Acknowledged : SeekResolution

    /** Withheld events must be replayed and the caller told [result]. */
    public class Replay(internal val result: SubscriptionSeekResult) : SeekResolution

    /** Withheld events must be discarded and the subscription invalidated. */
    public class Invalidate(internal val cause: SubscriptionSeekInvalidation) : SeekResolution
}

private sealed interface GateDecision {
    /** The event is delivered immediately in committed order. */
    public data object Pass : GateDecision

    /** The event joined the bounded gate queue. */
    public data object Withheld : GateDecision

    /** The gate overflowed, so the event and every withheld event were dropped. */
    public data object Discarded : GateDecision

    /** The acknowledgement resolved the gate; [replayed] precedes it in committed order. */
    public class Resolved(internal val replayed: List<SubscriptionEvent>) : GateDecision
}

private sealed interface OwnerOutcome {
    public data object Close : OwnerOutcome
    public class Terminal(internal val reason: SubscriptionTerminalReason) : OwnerOutcome
    public class Collection(internal val outcome: CollectionOutcome) : OwnerOutcome
    public class Subscribe(internal val outcome: CommandOutcome) : OwnerOutcome
}

private sealed interface CollectionOutcome {
    public data object Completed : CollectionOutcome
    public data object Terminal : CollectionOutcome
    public data object Failed : CollectionOutcome
    public class Cancelled(internal val cancellation: CancellationException) : CollectionOutcome
}

private fun CollectionOutcome.toTerminalReason(): SubscriptionTerminalReason = when (this) {
    CollectionOutcome.Completed -> SubscriptionTerminalReason.UnexpectedStreamClosure
    CollectionOutcome.Terminal -> error("Terminal collection already has a terminal reason")
    CollectionOutcome.Failed -> SubscriptionTerminalReason.InfrastructureFailed
    is CollectionOutcome.Cancelled -> error("Cancelled collection is handled separately")
}

private class CleanupOutcome(
    internal val result: SubscriptionCloseResult,
    internal val cancellation: CancellationException?,
)

private class FinishedOutcome(
    internal val result: SubscriptionCloseResult,
    internal val cancellation: CancellationException?,
)

private enum class PlayableCommitResult {
    COMMITTED,
    LOCALLY_REJECTED,
    GENERATION_GONE,
}

private sealed interface CommandOutcome {
    public class Result(
        internal val value: SubscriptionOperationResult<SubscriptionConfirmation>,
    ) : CommandOutcome

    public data object Failed : CommandOutcome

    public class Cancelled(internal val cancellation: CancellationException) : CommandOutcome
}

private fun SubscriptionOperationResult<*>.toFailure(): SubscriptionOperationFailure = when (this) {
    is SubscriptionOperationResult.Ok -> error("Successful operation has no failure")
    SubscriptionOperationResult.ServerRejected -> SubscriptionOperationFailure.SERVER_REJECTED
    SubscriptionOperationResult.AccessDenied -> SubscriptionOperationFailure.ACCESS_DENIED
    SubscriptionOperationResult.ConnectionLimit -> SubscriptionOperationFailure.CONNECTION_LIMIT
    SubscriptionOperationResult.Timeout -> SubscriptionOperationFailure.TIMEOUT
    SubscriptionOperationResult.TransportUnavailable ->
        SubscriptionOperationFailure.TRANSPORT_UNAVAILABLE
    SubscriptionOperationResult.NotSupported -> SubscriptionOperationFailure.NOT_SUPPORTED
}
