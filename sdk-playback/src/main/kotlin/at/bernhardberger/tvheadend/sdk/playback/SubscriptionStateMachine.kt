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
import kotlinx.coroutines.withContext

/** Direct suspending consumer of the complete committed subscription event order. */
@SubscriptionInfrastructureApi
public fun interface SubscriptionEventConsumer {
    /**
     * Consumes one event without an SDK-owned holding queue.
     *
     * Implementations must remain cancellation-cooperative and must not suspend indefinitely,
     * because ordered subscription shutdown drains already committed events through this call.
     * Implementations must not call joining lifecycle methods such as [ActiveSubscription.close]
     * from this callback.
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
) {
    override fun equals(other: Any?): Boolean = other is SubscriptionDiagnostics &&
        condition == other.condition &&
        graceTimeoutSeconds == other.graceTimeoutSeconds &&
        droppedPacketCount == other.droppedPacketCount &&
        droppedPacketCountOverflowed == other.droppedPacketCountOverflowed

    override fun hashCode(): Int {
        var result = condition.hashCode()
        result = 31 * result + (graceTimeoutSeconds?.hashCode() ?: 0)
        result = 31 * result + droppedPacketCount.hashCode()
        return 31 * result + droppedPacketCountOverflowed.hashCode()
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

    /** Closes, unsubscribes, and joins this subscription exactly once. */
    public suspend fun close(): SubscriptionCloseResult
}

/** Opens subscriptions without exposing generation admission or teardown controls. */
@SubscriptionInfrastructureApi
public fun interface SubscriptionOpener {
    /** Opens a subscription and returns only after it is playable or terminal. */
    public suspend fun open(
        channelId: SubscriptionChannelId,
        consumer: SubscriptionEventConsumer,
    ): SubscriptionOpenResult
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
                scope = scope,
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
    ): Boolean = handles.none { handle -> handle.ownsCollectionJob(callerJob) }
}

private class ActiveSubscriptionImpl(
    private val id: SubscriptionId,
    private val channelId: SubscriptionChannelId,
    private val connection: SubscriptionConnection,
    private val consumer: SubscriptionEventConsumer,
    private val scope: CoroutineScope,
    private val tryCommitPlayable: (
        ActiveSubscriptionImpl,
        publication: () -> Unit,
    ) -> PlayableCommitResult,
    private val onFinished: () -> Unit,
) : ActiveSubscription {
    private val lock = Any()
    private val started = AtomicBoolean()
    private val mutableState = MutableStateFlow<SubscriptionState>(SubscriptionState.Starting)
    private val mutableDiagnostics = MutableStateFlow(emptyDiagnostics())
    private val openCompletion = CompletableDeferred<SubscriptionOpenResult>()
    private val closeRequested = CompletableDeferred<Unit>()
    private val terminalSignal = CompletableDeferred<SubscriptionTerminalReason>()
    private val finished = CompletableDeferred<SubscriptionCloseResult>()
    private var subscribeAccepted = false
    private var tracks: SubscriptionTracks? = null
    private var terminal: SubscriptionTerminalReason? = null
    private var closeRequestedFlag = false
    private var playablePublished = false
    private var terminalCancellation: CancellationException? = null
    private var stopEventCollection = false
    private var collectionJob: Job? = null
    private var consumerEnabled = true

    override val state: StateFlow<SubscriptionState> = mutableState.asStateFlow()
    override val diagnostics: StateFlow<SubscriptionDiagnostics> = mutableDiagnostics.asStateFlow()

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

    internal fun ownsCollectionJob(job: Job?): Boolean =
        synchronized(lock) { job != null && collectionJob === job }

    internal fun requestClose() {
        synchronized(lock) { closeRequestedFlag = true }
        closeRequested.complete(Unit)
    }

    internal suspend fun awaitFinished(): SubscriptionCloseResult = finished.await()

    internal suspend fun awaitFinishedForManager(): FinishedOutcome {
        val result = finished.await()
        return FinishedOutcome(result, synchronized(lock) { terminalCancellation })
    }

    override suspend fun close(): SubscriptionCloseResult {
        val callerJob = currentCoroutineContext()[Job]
        check(synchronized(lock) { callerJob !== collectionJob }) {
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
                                            synchronized(lock) { subscribeAccepted = true }
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
        CommandOutcome.Result(connection.subscribe(id, channelId))
    } catch (cancellation: CancellationException) {
        CommandOutcome.Cancelled(cancellation)
    } catch (_: Exception) {
        CommandOutcome.Failed
    }

    private suspend fun acceptEvent(event: SubscriptionEvent): Boolean {
        if (synchronized(lock) { consumerEnabled }) {
            try {
                consumer.accept(event)
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
        return synchronized(lock) { !stopEventCollection }
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
        terminalSignal.complete(reason)
        if (completeOpen) openCompletion.complete(SubscriptionOpenResult.Failed(reason))
    }

    private fun updateDiagnostics(
        condition: SubscriptionCondition = mutableDiagnostics.value.condition,
        graceTimeoutSeconds: Long? = mutableDiagnostics.value.graceTimeoutSeconds,
    ) {
        val current = mutableDiagnostics.value
        mutableDiagnostics.value = SubscriptionDiagnostics(
            condition = condition,
            graceTimeoutSeconds = graceTimeoutSeconds,
            droppedPacketCount = current.droppedPacketCount,
            droppedPacketCountOverflowed = current.droppedPacketCountOverflowed,
        )
    }

    private fun addDroppedPackets(count: Long) {
        val current = mutableDiagnostics.value
        val overflow = Long.MAX_VALUE - current.droppedPacketCount < count
        mutableDiagnostics.value = SubscriptionDiagnostics(
            condition = current.condition,
            graceTimeoutSeconds = current.graceTimeoutSeconds,
            droppedPacketCount = if (overflow) Long.MAX_VALUE else current.droppedPacketCount + count,
            droppedPacketCountOverflowed = current.droppedPacketCountOverflowed || overflow,
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
)

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
