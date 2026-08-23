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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Direct suspending consumer of the complete committed subscription event order. */
@SubscriptionInfrastructureApi
public fun interface SubscriptionEventConsumer {
    /**
     * Consumes one event without an SDK-owned holding queue.
     *
     * Implementations must remain cancellation-cooperative and must not suspend indefinitely,
     * because ordered subscription shutdown drains already committed events through this call.
     * Implementations must not call joining lifecycle methods such as [ActiveSubscription.close]
     * or [ActiveSubscription.seek] from this callback.
     *
     * While a seek acknowledgement is pending, [SubscriptionEvent.Packet] and
     * [SubscriptionEvent.Dropped] are withheld and every other event is still delivered
     * immediately. Withheld events keep their relative order and are either replayed before the
     * rejecting acknowledgement or discarded before the accepting one.
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

/** Immutable validated track set that made a subscription playable. */
@SubscriptionInfrastructureApi
public class SubscriptionTracks internal constructor(streams: List<SubscriptionStream>) {
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
    /** Collection is registered but acknowledgement or tracks are still pending. */
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

    /** TVHeadend sent an ordered graceful stop. */
    public data object Stopped : SubscriptionTerminalReason

    /** The owning connection generation was replaced. */
    public data object GenerationLost : SubscriptionTerminalReason

    /** The owning transport closed. */
    public data object TransportClosed : SubscriptionTerminalReason

    /** Started did not contain a usable nonempty unique track set. */
    public data object InvalidTracks : SubscriptionTerminalReason

    /** A second Started attempted to replace initialized tracks. */
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
     */
    RESUMED_SEGMENT_UNANCHORABLE,
}

/** Typed outcome of one gated timeshift positioning request. */
@SubscriptionInfrastructureApi
public sealed interface SubscriptionSeekResult {
    /** The ordered acknowledgement accepted the request and withheld packets were discarded. */
    public data object Accepted : SubscriptionSeekResult

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

    /**
     * Returning to live produced no ordered acknowledgement; withheld packets were replayed.
     *
     * TVHeadend does not acknowledge a live request that changes nothing.
     */
    public data object NotAcknowledged : SubscriptionSeekResult

    /** The gate invalidated the subscription instead of mixing uncertain packets. */
    public class Invalidated(public val cause: SubscriptionSeekInvalidation) :
        SubscriptionSeekResult {
        override fun toString(): String = "SubscriptionSeekResult.Invalidated"
    }

    /** The subscription became terminal before the request resolved. */
    public data object SubscriptionEnded : SubscriptionSeekResult
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
) {
    override fun equals(other: Any?): Boolean = other is SubscriptionDiagnostics &&
        condition == other.condition &&
        graceTimeoutSeconds == other.graceTimeoutSeconds &&
        droppedPacketCount == other.droppedPacketCount &&
        droppedPacketCountOverflowed == other.droppedPacketCountOverflowed &&
        timestampAnchorCount == other.timestampAnchorCount &&
        rebaseDiscardedPacketCount == other.rebaseDiscardedPacketCount

    override fun hashCode(): Int {
        var result = condition.hashCode()
        result = 31 * result + (graceTimeoutSeconds?.hashCode() ?: 0)
        result = 31 * result + droppedPacketCount.hashCode()
        result = 31 * result + droppedPacketCountOverflowed.hashCode()
        result = 31 * result + timestampAnchorCount.hashCode()
        return 31 * result + rebaseDiscardedPacketCount.hashCode()
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
     */
    public suspend fun seek(target: SubscriptionSeekTarget): SubscriptionSeekResult

    /** Closes, unsubscribes, and joins this subscription exactly once. */
    public suspend fun close(): SubscriptionCloseResult
}

/** Opens subscriptions without exposing generation admission or teardown controls. */
@SubscriptionInfrastructureApi
public fun interface SubscriptionOpener {
    /**
     * Opens a subscription and returns only after it is playable or terminal.
     *
     * A positive [timeshiftPeriod] requests a server-side timeshift buffer. Only a subscription
     * whose server granted one can be repositioned through [ActiveSubscription.seek].
     */
    public suspend fun open(
        channelId: SubscriptionChannelId,
        consumer: SubscriptionEventConsumer,
        timeshiftPeriod: Duration,
    ): SubscriptionOpenResult

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
    /** Opens admission immediately before the owning session publishes Ready. */
    public fun startAdmission()

    /** Stops new admission immediately before the owning session leaves Ready. */
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

/** Creates a manager whose seek and rebase bounds are non-default for deterministic tests. */
internal fun createSubscriptionManager(
    connection: SubscriptionConnection,
    dispatcher: CoroutineDispatcher,
    seekGate: SeekGateSettings,
    rebase: TimestampRebaseSettings = TimestampRebaseSettings(),
): SubscriptionManager =
    SubscriptionManagerImpl(connection, dispatcher, seekGate = seekGate, rebase = rebase)

/** Bounded seek-gate limits kept internal and injectable. */
internal class SeekGateSettings(
    internal val acknowledgementTimeout: Duration = DEFAULT_SEEK_ACKNOWLEDGEMENT_TIMEOUT,
    internal val liveAcknowledgementTimeout: Duration = DEFAULT_LIVE_ACKNOWLEDGEMENT_TIMEOUT,
    internal val maximumPendingEvents: Int = DEFAULT_SEEK_PENDING_EVENTS,
    internal val maximumPendingBytes: Long = DEFAULT_SEEK_PENDING_BYTES,
) {
    init {
        require(acknowledgementTimeout > Duration.ZERO) {
            "Seek acknowledgement timeout must be positive"
        }
        require(liveAcknowledgementTimeout > Duration.ZERO) {
            "Live acknowledgement timeout must be positive"
        }
        require(liveAcknowledgementTimeout <= acknowledgementTimeout) {
            "Live acknowledgement timeout must not exceed the seek acknowledgement timeout"
        }
        require(maximumPendingEvents > 0) { "Seek gate capacity must be positive" }
        require(maximumPendingBytes > 0L) { "Seek gate byte capacity must be positive" }
    }
}

private val DEFAULT_SEEK_ACKNOWLEDGEMENT_TIMEOUT = 5.seconds

/**
 * Shorter bound for return to live, whose acknowledgement is absent whenever nothing changes.
 *
 * Holding the data plane for the full seek deadline on that common request would stall a healthy
 * live stream and can exhaust the byte bound on a high bitrate mux.
 */
private val DEFAULT_LIVE_ACKNOWLEDGEMENT_TIMEOUT = 1.seconds
private const val DEFAULT_SEEK_PENDING_EVENTS = 2_048
private const val DEFAULT_SEEK_PENDING_BYTES = 16L * 1024L * 1024L
private const val MAXIMUM_TIMESHIFT_PERIOD_SECONDS = 0xffff_ffffL

internal class SubscriptionIdAllocator(private var next: Long = 0L) {
    internal fun allocate(): SubscriptionId? = if (next > 0xffff_ffffL) {
        null
    } else {
        SubscriptionId(next).also { next += 1L }
    }
}

private class SubscriptionManagerImpl(
    private val connection: SubscriptionConnection,
    dispatcher: CoroutineDispatcher,
    initialSubscriptionId: Long = 0L,
    private val seekGate: SeekGateSettings = SeekGateSettings(),
    private val rebase: TimestampRebaseSettings = TimestampRebaseSettings(),
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
    ): SubscriptionOpenResult {
        require(timeshiftPeriod.isFinite() && !timeshiftPeriod.isNegative()) {
            "Requested timeshift period must be finite and not negative"
        }
        require(timeshiftPeriod.inWholeSeconds <= MAXIMUM_TIMESHIFT_PERIOD_SECONDS) {
            "Requested timeshift period must be an unsigned 32-bit second count"
        }
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
                scope = scope,
                seekGate = seekGate,
                rebase = rebase,
                timeshiftPeriod = timeshiftPeriod,
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
        val callerJob = currentCoroutineContext()[Job]
        val callerCancellation: CancellationException? = try {
            currentCoroutineContext().ensureActive()
            null
        } catch (cancellation: CancellationException) {
            cancellation
        }
        val handles = synchronized(lock) {
            check(handlesDoNotOwnCaller(handles = active.values, callerJob = callerJob)) {
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
        callerJob: Job?,
    ): Boolean = handles.none { handle -> handle.ownsDeliveryJob(callerJob) }
}

private class ActiveSubscriptionImpl(
    private val id: SubscriptionId,
    private val channelId: SubscriptionChannelId,
    private val connection: SubscriptionConnection,
    private val consumer: SubscriptionEventConsumer,
    private val scope: CoroutineScope,
    private val seekGate: SeekGateSettings,
    rebase: TimestampRebaseSettings,
    private val timeshiftPeriod: Duration,
    private val tryCommitPlayable: (
        ActiveSubscriptionImpl,
        publication: () -> Unit,
    ) -> PlayableCommitResult,
    private val onFinished: () -> Unit,
) : ActiveSubscription {
    private val lock = Any()
    private val deliveryMutex = Mutex()

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
    private var terminalCancellation: CancellationException? = null
    private var stopEventCollection = false
    private var collectionJob: Job? = null
    private var consumerEnabled = true
    private var pendingSeek: PendingSeek? = null
    private var seekAdmissionClosed = false
    private val seekDrivers = LinkedHashSet<Job>()

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

    /** Reports whether [job] is one of the coroutines that delivers events to the consumer. */
    internal fun ownsDeliveryJob(job: Job?): Boolean =
        synchronized(lock) { job != null && (collectionJob === job || job in seekDrivers) }

    internal fun requestClose() {
        synchronized(lock) { closeRequestedFlag = true }
        closeRequested.complete(Unit)
    }

    internal suspend fun awaitFinished(): SubscriptionCloseResult = finished.await()

    internal suspend fun awaitFinishedForManager(): FinishedOutcome {
        val result = finished.await()
        return FinishedOutcome(result, synchronized(lock) { terminalCancellation })
    }

    override suspend fun seek(target: SubscriptionSeekTarget): SubscriptionSeekResult {
        check(!ownsDeliveryJob(currentCoroutineContext()[Job])) {
            "Subscription seek cannot join from its event consumer"
        }
        currentCoroutineContext().ensureActive()
        val (pending, driver) = synchronized(lock) {
            if (
                terminal != null ||
                closeRequestedFlag ||
                seekAdmissionClosed ||
                !playablePublished
            ) {
                return SubscriptionSeekResult.SubscriptionEnded
            }
            if ((grantedTimeshiftSeconds ?: 0L) <= 0L) return SubscriptionSeekResult.NotSeekable
            if (pendingSeek != null) return SubscriptionSeekResult.AlreadyPending
            val created = PendingSeek(target)
            // A lazy start runs no user code under the monitor and keeps registration atomic.
            val job = scope.launch(start = CoroutineStart.LAZY) { driveSeek(created) }
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

    override suspend fun close(): SubscriptionCloseResult {
        check(!ownsDeliveryJob(currentCoroutineContext()[Job])) {
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
                val collection = async(start = CoroutineStart.UNDISPATCHED) {
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
                        val drivers = synchronized(lock) {
                            seekAdmissionClosed = true
                            seekDrivers.toList()
                        }
                        drivers.forEach { driver -> driver.cancelAndJoin() }
                        val cleanup = cleanUpCollection(collection)
                        closeResult = cleanup.result
                        if (primaryCancellation == null) {
                            primaryCancellation = cleanup.cancellation
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
            if (primaryCancellation != null) {
                openCompletion.completeExceptionally(requireNotNull(primaryCancellation))
                closeResult = SubscriptionCloseResult.CLEANUP_FAILED
            }
            setTerminal(finalReason)
            if (primaryCancellation == null) {
                openCompletion.complete(SubscriptionOpenResult.Failed(finalReason))
            }
            onFinished()
            finished.complete(closeResult)
        }
        primaryCancellation?.let { throw it }
    }

    private suspend fun collectEvents(): CollectionOutcome {
        val currentJob = currentCoroutineContext()[Job]
        synchronized(lock) { collectionJob = currentJob }
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
        } finally {
            synchronized(lock) { collectionJob = null }
        }
    }

    private suspend fun invokeSubscribe(): CommandOutcome = try {
        CommandOutcome.Result(connection.subscribe(id, channelId, timeshiftPeriod))
    } catch (cancellation: CancellationException) {
        CommandOutcome.Cancelled(cancellation)
    } catch (_: Exception) {
        CommandOutcome.Failed
    }

    private suspend fun acceptEvent(event: SubscriptionEvent): Boolean {
        deliveryMutex.withLock {
            when (val decision = admitToGate(event)) {
                GateDecision.Pass -> deliverToConsumer(event)
                GateDecision.Withheld, GateDecision.Discarded -> Unit
                is GateDecision.Resolved -> {
                    decision.replayed.forEach { withheld -> deliverToConsumer(withheld) }
                    deliverToConsumer(event)
                }
            }
            applyEventState(event)
        }
        return synchronized(lock) { !stopEventCollection }
    }

    /**
     * Classifies one committed event against the seek gate.
     *
     * Mux packets and drop markers are withheld while an acknowledgement is pending; every other
     * event is delivered immediately so terminal handling is never delayed by a gate.
     */
    private fun admitToGate(event: SubscriptionEvent): GateDecision = synchronized(lock) {
        val pending = pendingSeek ?: return GateDecision.Pass
        when (event) {
            is SubscriptionEvent.Packet, is SubscriptionEvent.Dropped -> {
                val bytes = (event as? SubscriptionEvent.Packet)?.payload?.size?.toLong() ?: 0L
                if (pending.canAccept(bytes, seekGate)) {
                    pending.enqueue(event, bytes)
                    GateDecision.Withheld
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
                    resolveSeekLocked(pending, SubscriptionSeekResult.Accepted)
                    GateDecision.Resolved(emptyList())
                }
                SkipOutcome.REJECTED -> {
                    val replayed = pending.drain()
                    resolveSeekLocked(pending, SubscriptionSeekResult.Rejected)
                    GateDecision.Resolved(replayed)
                }
                SkipOutcome.UNKNOWN -> {
                    val cause = SubscriptionSeekInvalidation.UNRECOGNIZED_ACKNOWLEDGEMENT
                    pending.discard()
                    resolveSeekLocked(
                        pending = pending,
                        result = SubscriptionSeekResult.Invalidated(cause),
                        terminalReason = SubscriptionTerminalReason.SeekInvalidated(cause),
                    )
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
        pending.outcome.complete(result)
        terminalReason?.let { reason ->
            setTerminalLocked(reason, stopCollection = true, completeOpen = false)
        }
    }

    /**
     * Issues the request and bounds the wait for its ordered acknowledgement.
     *
     * Only the request and the acknowledgement wait are deadline-bounded. Replaying withheld
     * events happens afterwards so an expiring deadline can never deliver a partial replay.
     */
    private suspend fun driveSeek(pending: PendingSeek) {
        if (synchronized(lock) { pendingSeek !== pending }) return
        val deadline = if (pending.target is SubscriptionSeekTarget.Live) {
            seekGate.liveAcknowledgementTimeout
        } else {
            seekGate.acknowledgementTimeout
        }
        val bounded = withTimeoutOrNull(deadline) {
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
        val resolution = bounded ?: if (pending.target is SubscriptionSeekTarget.Live) {
            // TVHeadend absorbs a live request that changes nothing, so silence is not uncertainty.
            SeekResolution.Replay(SubscriptionSeekResult.NotAcknowledged)
        } else {
            SeekResolution.Invalidate(SubscriptionSeekInvalidation.ACKNOWLEDGEMENT_TIMEOUT)
        }
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

    /** Completes [pending] and then replays every withheld event in committed order. */
    private suspend fun replaySeek(pending: PendingSeek, result: SubscriptionSeekResult) {
        deliveryMutex.withLock {
            val replayed = synchronized(lock) {
                if (pendingSeek !== pending) return@withLock
                pending.drain().also { resolveSeekLocked(pending, result) }
            }
            replayed.forEach { withheld -> deliverToConsumer(withheld) }
        }
    }

    /** Discards withheld events and ends the subscription rather than mixing uncertain packets. */
    private fun invalidateSeek(pending: PendingSeek, cause: SubscriptionSeekInvalidation) {
        synchronized(lock) {
            if (pendingSeek !== pending) return
            pending.discard()
            if (terminal != null || closeRequestedFlag) {
                resolveSeekLocked(pending, SubscriptionSeekResult.SubscriptionEnded)
            } else {
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
        try {
            consumer.accept(rebased)
        } catch (cancellation: CancellationException) {
            synchronized(lock) {
                consumerEnabled = false
                terminalCancellation = terminalCancellation ?: cancellation
                setTerminalLocked(
                    SubscriptionTerminalReason.InfrastructureFailed,
                    stopCollection = false,
                    completeOpen = false,
                )
            }
        } catch (_: Exception) {
            synchronized(lock) {
                consumerEnabled = false
                setTerminalLocked(
                    SubscriptionTerminalReason.ConsumerFailed,
                    stopCollection = false,
                    completeOpen = false,
                )
            }
        }
    }

    /**
     * Ends the subscription when the resumed segment can never re-anchor the delivered timeline.
     *
     * Every other uncertain path here is bounded and typed, so an unanchorable segment must not
     * decay into an unbounded silent discard that still reports a playable subscription.
     */
    private fun invalidateUnanchorableSegment() {
        synchronized(lock) {
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
                setStreamTerminal(SubscriptionTerminalReason.Stopped)
            }
            is SubscriptionEvent.Terminated -> setStreamTerminal(
                when (event.reason) {
                    SubscriptionTermination.GENERATION_LOST ->
                        SubscriptionTerminalReason.GenerationLost
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
            if (tracks != null) {
                consumerEnabled = false
                setTerminalLocked(
                    SubscriptionTerminalReason.TrackReconfigurationUnsupported,
                    stopCollection = false,
                    completeOpen = false,
                )
                return
            }
        }
        // The committed track set decides which stream indices may end an anchor wait.
        rebaser.onTracks(candidate)
        try {
            consumer.tracksReady(candidate)
        } catch (cancellation: CancellationException) {
            synchronized(lock) {
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
        synchronized(lock) {
            if (tracks != null || terminal != null || closeRequestedFlag) return
            tracks = candidate
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
        when (tryCommitPlayable(this) {
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
                    mutableState.value = SubscriptionState.Playable(currentTracks)
                    openCompletion.complete(SubscriptionOpenResult.Opened(this))
                }
            }
        }) {
            PlayableCommitResult.COMMITTED -> Unit
            PlayableCommitResult.LOCALLY_REJECTED ->
                setStartupFailure(SubscriptionTerminalReason.Closed)
            PlayableCommitResult.GENERATION_GONE ->
                setStartupFailure(SubscriptionTerminalReason.GenerationLost)
        }
    }

    private fun setStreamTerminal(reason: SubscriptionTerminalReason) {
        synchronized(lock) { setTerminalLocked(reason, stopCollection = true, completeOpen = false) }
    }

    private fun setLocalFailure(reason: SubscriptionTerminalReason) {
        synchronized(lock) {
            consumerEnabled = false
            setTerminalLocked(reason, stopCollection = false, completeOpen = false)
        }
    }

    private fun setTerminal(reason: SubscriptionTerminalReason) {
        synchronized(lock) { setTerminalLocked(reason, stopCollection = true, completeOpen = false) }
    }

    private fun setStartupFailure(reason: SubscriptionTerminalReason) {
        synchronized(lock) { setTerminalLocked(reason, stopCollection = false, completeOpen = false) }
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
        mutableState.value = SubscriptionState.Terminal(reason)
        pendingSeek?.let { pending ->
            pendingSeek = null
            pending.discard()
            pending.outcome.complete(SubscriptionSeekResult.SubscriptionEnded)
        }
        terminalSignal.complete(reason)
        if (completeOpen) openCompletion.complete(SubscriptionOpenResult.Failed(reason))
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
                    collection.await()
                } catch (failure: CancellationException) {
                    if (cancellation == null) cancellation = failure
                    CollectionOutcome.Failed
                } catch (_: Exception) {
                    CollectionOutcome.Failed
                }
            } else {
                collection.cancelAndJoin()
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
)

private fun SubscriptionDiagnostics.with(
    condition: SubscriptionCondition = this.condition,
    graceTimeoutSeconds: Long? = this.graceTimeoutSeconds,
    droppedPacketCount: Long = this.droppedPacketCount,
    droppedPacketCountOverflowed: Boolean = this.droppedPacketCountOverflowed,
    timestampAnchorCount: Long = this.timestampAnchorCount,
    rebaseDiscardedPacketCount: Long = this.rebaseDiscardedPacketCount,
): SubscriptionDiagnostics = SubscriptionDiagnostics(
    condition = condition,
    graceTimeoutSeconds = graceTimeoutSeconds,
    droppedPacketCount = droppedPacketCount,
    droppedPacketCountOverflowed = droppedPacketCountOverflowed,
    timestampAnchorCount = timestampAnchorCount,
    rebaseDiscardedPacketCount = rebaseDiscardedPacketCount,
)

/** Counts one more occurrence without wrapping a durable diagnostic counter. */
private fun saturatingIncrement(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1L

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
