package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.AutorecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrEntryUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrMutationCommands
import at.bernhardberger.tvheadend.sdk.core.DvrMutationKind
import at.bernhardberger.tvheadend.sdk.core.DvrMutationResult
import at.bernhardberger.tvheadend.sdk.core.DvrSchedule
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleId
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrUpdateProvenance
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.ProtocolGateway
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal interface DvrMutationLifecycle {
    public fun bindGeneration(generation: GatewayGeneration)

    public fun startAdmission(generation: GatewayGeneration): Boolean

    /** Distinguishes connection retirement from shutdown or readiness-admission rollback. */
    public fun stopAdmission(connectionRetired: Boolean = false)

    public data object None : DvrMutationLifecycle {
        override fun bindGeneration(generation: GatewayGeneration) = Unit

        override fun startAdmission(generation: GatewayGeneration): Boolean = true

        override fun stopAdmission(connectionRetired: Boolean) = Unit
    }
}

internal class DvrMutationSettings(
    internal val confirmationTimeout: Duration = 5.seconds,
) {
    init {
        require(confirmationTimeout.isFinite() && confirmationTimeout.isPositive()) {
            "DVR confirmation timeout must be finite and positive"
        }
    }
}

internal class DvrMutationCoordinator(
    private val gateway: ProtocolGateway,
    private val settings: DvrMutationSettings = DvrMutationSettings(),
    private val isSessionReady: (GatewayGeneration) -> Boolean = { true },
    private val onDvrAccessProof: suspend (GatewayGeneration, Boolean) -> Unit = { _, _ -> },
    internal val intents: DvrIntentLedger = DvrIntentLedger(),
) : DvrMutationCommands, DvrMutationLifecycle {
    private val lock = Any()
    private val operationMutex = Mutex()
    private var generation: GatewayGeneration? = null
    private var latestBoundGeneration: GatewayGeneration? = null
    private var admitted = false
    private var stoppedFailure: TvheadendFailure? = null
    private var pending: PendingMutation? = null

    override fun bindGeneration(generation: GatewayGeneration) {
        val retired = synchronized(lock) {
            val previous = pending
            pending = null
            admitted = false
            stoppedFailure = null
            this.generation = generation
            latestBoundGeneration = generation
            intents.bind(generation)
            previous
        }
        retired?.retire()
    }

    override fun startAdmission(generation: GatewayGeneration): Boolean = synchronized(lock) {
        if (this.generation !== generation) {
            false
        } else {
            admitted = true
            true
        }
    }

    override fun stopAdmission(connectionRetired: Boolean) {
        val retired = synchronized(lock) {
            admitted = false
            stoppedFailure = if (connectionRetired) TvheadendFailure.CONNECTION_CHANGED else TvheadendFailure.NOT_READY
            generation = null
            intents.bind(null)
            pending.also { pending = null }
        }
        retired?.retire()
    }

    internal fun acceptMetadata(event: MetadataEvent) {
        val keys = event.confirmationKeys()
        if (keys.isEmpty()) return
        synchronized(lock) {
            if (event.generation === generation) {
                // A confirmed caller may resume inline and register its next operation; this event
                // must not confirm that operation too.
                val operation = pending ?: return
                keys.forEach(operation::accept)
            }
        }
    }

    override suspend fun scheduleEntry(
        generation: GatewayGeneration,
        request: DvrScheduleRequest,
    ): DvrMutationResult<DvrEntryId> = create(
        expectedGeneration = generation,
        confirmationKind = ConfirmationKind.ENTRY_ADDED,
        command = { generation ->
            withIntent(generation, DvrMutationKind.SCHEDULE, eventId = (request.schedule as? DvrSchedule.Programme)?.eventId) {
                gateway.scheduleDvrEntry(generation, request)
            }
        },
        confirmation = { id -> ConfirmationKey(ConfirmationKind.ENTRY_ADDED, id) },
    )

    override suspend fun updateEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
        update: DvrEntryUpdate,
    ): DvrMutationResult<Unit> = mutate(
        expectedGeneration = generation,
        confirmations = setOf(ConfirmationKey(ConfirmationKind.ENTRY_UPDATED, id)),
        command = { generation -> gateway.updateDvrEntry(generation, id, update) },
    )

    override suspend fun stopEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): DvrMutationResult<Unit> = mutate(
        expectedGeneration = generation,
        confirmations = setOf(ConfirmationKey(ConfirmationKind.ENTRY_ENDED, id)),
        command = { generation ->
            withIntent(generation, DvrMutationKind.STOP, entryId = id) { gateway.stopDvrEntry(generation, id) }
        },
    )

    override suspend fun cancelEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): DvrMutationResult<Unit> = mutate(
        expectedGeneration = generation,
        confirmations = setOf(
            ConfirmationKey(ConfirmationKind.ENTRY_ENDED, id),
            ConfirmationKey(ConfirmationKind.ENTRY_DELETED, id),
        ),
        command = { generation ->
            withIntent(generation, DvrMutationKind.CANCEL, entryId = id) { gateway.cancelDvrEntry(generation, id) }
        },
    )

    override suspend fun deleteEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): DvrMutationResult<Unit> = mutate(
        expectedGeneration = generation,
        confirmations = setOf(
            ConfirmationKey(ConfirmationKind.ENTRY_DELETED, id),
            ConfirmationKey(ConfirmationKind.ENTRY_FILE_MISSING, id),
        ),
        command = { generation ->
            withIntent(generation, DvrMutationKind.DELETE, entryId = id) { gateway.deleteDvrEntry(generation, id) }
        },
    )

    override suspend fun createAutorecRule(
        generation: GatewayGeneration,
        request: AutorecRuleCreate,
    ): DvrMutationResult<AutorecRuleId> = create(
        expectedGeneration = generation,
        confirmationKind = ConfirmationKind.AUTOREC_ADDED,
        command = { generation -> gateway.createAutorecRule(generation, request) },
        confirmation = { id -> ConfirmationKey(ConfirmationKind.AUTOREC_ADDED, id) },
    )

    override suspend fun updateAutorecRule(
        generation: GatewayGeneration,
        id: AutorecRuleId,
        update: AutorecRuleUpdate,
    ): DvrMutationResult<Unit> = mutate(
        expectedGeneration = generation,
        confirmations = setOf(ConfirmationKey(ConfirmationKind.AUTOREC_UPDATED, id)),
        command = { generation -> gateway.updateAutorecRule(generation, id, update) },
    )

    override suspend fun deleteAutorecRule(
        generation: GatewayGeneration,
        id: AutorecRuleId,
    ): DvrMutationResult<Unit> = mutate(
        expectedGeneration = generation,
        confirmations = setOf(ConfirmationKey(ConfirmationKind.AUTOREC_DELETED, id)),
        command = { generation -> gateway.deleteAutorecRule(generation, id) },
    )

    override suspend fun createTimerecRule(
        generation: GatewayGeneration,
        request: TimerecRuleCreate,
    ): DvrMutationResult<TimerecRuleId> = create(
        expectedGeneration = generation,
        confirmationKind = ConfirmationKind.TIMEREC_ADDED,
        command = { generation -> gateway.createTimerecRule(generation, request) },
        confirmation = { id -> ConfirmationKey(ConfirmationKind.TIMEREC_ADDED, id) },
    )

    override suspend fun updateTimerecRule(
        generation: GatewayGeneration,
        id: TimerecRuleId,
        update: TimerecRuleUpdate,
    ): DvrMutationResult<Unit> = mutate(
        expectedGeneration = generation,
        confirmations = setOf(ConfirmationKey(ConfirmationKind.TIMEREC_UPDATED, id)),
        command = { generation -> gateway.updateTimerecRule(generation, id, update) },
    )

    override suspend fun deleteTimerecRule(
        generation: GatewayGeneration,
        id: TimerecRuleId,
    ): DvrMutationResult<Unit> = mutate(
        expectedGeneration = generation,
        confirmations = setOf(ConfirmationKey(ConfirmationKind.TIMEREC_DELETED, id)),
        command = { generation -> gateway.deleteTimerecRule(generation, id) },
    )

    private suspend fun <T> withIntent(
        generation: GatewayGeneration,
        kind: DvrMutationKind,
        entryId: DvrEntryId? = null,
        eventId: EventId? = null,
        command: suspend () -> GatewayResult<T>,
    ): GatewayResult<T> {
        val intent = intents.record(generation, kind, entryId, eventId)
        var accepted = false
        try {
            val result = command()
            accepted = result is GatewayResult.Ok
            if (accepted) intents.accepted(intent)
            return result
        } finally {
            if (!accepted) intents.failed(intent)
        }
    }

    private suspend fun <T : Any> create(
        expectedGeneration: GatewayGeneration,
        confirmationKind: ConfirmationKind,
        command: suspend (GatewayGeneration) -> GatewayResult<T>,
        confirmation: (T) -> ConfirmationKey,
    ): DvrMutationResult<T> = operationMutex.withLock {
        val operation = when (
            val registration = register(expectedGeneration, createKind = confirmationKind)
        ) {
            is MutationRegistration.Failed ->
                return@withLock DvrMutationResult.Failed(registration.failure, mayHaveApplied = false)
            is MutationRegistration.Registered -> registration.operation
        }
        try {
            when (val outcome = awaitCommand(operation, command)) {
                GatewayCommandOutcome.Retired ->
                    DvrMutationResult.Failed(TvheadendFailure.CONNECTION_CHANGED, mayHaveApplied = true)
                is GatewayCommandOutcome.Cancelled -> throw outcome.exception
                is GatewayCommandOutcome.Completed -> when (val result = outcome.result) {
                    is GatewayResult.Ok -> {
                        if (!applyProof(operation, allowed = true)) {
                            DvrMutationResult.Failed(TvheadendFailure.CONNECTION_CHANGED, mayHaveApplied = true)
                        } else if (!setExpected(operation, setOf(confirmation(result.value)))) {
                            DvrMutationResult.Failed(TvheadendFailure.CONNECTION_CHANGED, mayHaveApplied = true)
                        } else {
                            awaitConfirmation(operation, result.value)
                        }
                    }
                    GatewayResult.AccessDenied -> denied(operation)
                    GatewayResult.ServerRejected,
                    GatewayResult.ConnectionLimit,
                    GatewayResult.Timeout,
                    GatewayResult.TransportUnavailable,
                    GatewayResult.NotSupported,
                    GatewayResult.MalformedReply,
                    -> failure(operation, result)
                }
            }
        } finally {
            clear(operation)
        }
    }

    private suspend fun mutate(
        expectedGeneration: GatewayGeneration,
        confirmations: Set<ConfirmationKey>,
        command: suspend (GatewayGeneration) -> GatewayResult<Unit>,
    ): DvrMutationResult<Unit> = operationMutex.withLock {
        val operation = when (
            val registration = register(expectedGeneration, confirmations = confirmations)
        ) {
            is MutationRegistration.Failed ->
                return@withLock DvrMutationResult.Failed(registration.failure, mayHaveApplied = false)
            is MutationRegistration.Registered -> registration.operation
        }
        try {
            when (val outcome = awaitCommand(operation, command)) {
                GatewayCommandOutcome.Retired ->
                    DvrMutationResult.Failed(TvheadendFailure.CONNECTION_CHANGED, mayHaveApplied = true)
                is GatewayCommandOutcome.Cancelled -> throw outcome.exception
                is GatewayCommandOutcome.Completed -> when (val result = outcome.result) {
                    is GatewayResult.Ok -> {
                        if (!applyProof(operation, allowed = true)) {
                            DvrMutationResult.Failed(TvheadendFailure.CONNECTION_CHANGED, mayHaveApplied = true)
                        } else {
                            awaitConfirmation(operation, Unit)
                        }
                    }
                    GatewayResult.AccessDenied -> denied(operation)
                    GatewayResult.ServerRejected,
                    GatewayResult.ConnectionLimit,
                    GatewayResult.Timeout,
                    GatewayResult.TransportUnavailable,
                    GatewayResult.NotSupported,
                    GatewayResult.MalformedReply,
                    -> failure(operation, result)
                }
            }
        } finally {
            clear(operation)
        }
    }

    private fun register(
        expectedGeneration: GatewayGeneration,
        confirmations: Set<ConfirmationKey> = emptySet(),
        createKind: ConfirmationKind? = null,
    ): MutationRegistration {
        val initiallyAdmitted = synchronized(lock) {
            when {
                latestBoundGeneration !== expectedGeneration ->
                    return MutationRegistration.Failed(TvheadendFailure.OBSERVATION_EXPIRED)
                generation !== expectedGeneration -> return MutationRegistration.Failed(
                    stoppedFailure ?: TvheadendFailure.OBSERVATION_EXPIRED,
                )
                !admitted || pending != null -> false
                else -> true
            }
        }
        if (!initiallyAdmitted) return MutationRegistration.Failed(TvheadendFailure.NOT_READY)
        if (!isSessionReady(expectedGeneration)) {
            return synchronized(lock) {
                MutationRegistration.Failed(
                    stoppedFailure ?: if (generation !== expectedGeneration) {
                        TvheadendFailure.CONNECTION_CHANGED
                    } else {
                        TvheadendFailure.NOT_READY
                    },
                )
            }
        }
        return synchronized(lock) {
            when {
                generation !== expectedGeneration -> MutationRegistration.Failed(
                    stoppedFailure ?: TvheadendFailure.CONNECTION_CHANGED,
                )
                !admitted || pending != null -> MutationRegistration.Failed(TvheadendFailure.NOT_READY)
                else -> {
                    val operation = PendingMutation(
                        expectedGeneration,
                        confirmations,
                        createKind,
                    ).also { pending = it }
                    MutationRegistration.Registered(operation)
                }
            }
        }
    }

    private suspend fun <T> awaitCommand(
        operation: PendingMutation,
        command: suspend (GatewayGeneration) -> GatewayResult<T>,
    ): GatewayCommandOutcome<T> = coroutineScope {
        val commandResult = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                GatewayCommandOutcome.Completed(command(operation.generation))
            } catch (cancellation: CancellationException) {
                currentCoroutineContext().ensureActive()
                GatewayCommandOutcome.Cancelled(cancellation)
            } catch (_: Exception) {
                GatewayCommandOutcome.Completed(GatewayResult.TransportUnavailable)
            }
        }
        val outcome = select {
            commandResult.onAwait { result -> result }
            operation.retired.onAwait { GatewayCommandOutcome.Retired }
        }
        if (outcome === GatewayCommandOutcome.Retired) {
            commandResult.cancelAndJoin()
        }
        outcome
    }

    private suspend fun <T : Any> awaitConfirmation(
        operation: PendingMutation,
        value: T,
    ): DvrMutationResult<T> {
        val outcome = withTimeoutOrNull(settings.confirmationTimeout) {
            select {
                operation.confirmed.onAwait { ConfirmationOutcome.Confirmed }
                operation.retired.onAwait { ConfirmationOutcome.Retired }
            }
        } ?: ConfirmationOutcome.TimedOut
        return when (outcome) {
            ConfirmationOutcome.Confirmed -> if (isActive(operation)) {
                DvrMutationResult.Confirmed(value)
            } else {
                DvrMutationResult.Failed(TvheadendFailure.CONNECTION_CHANGED, mayHaveApplied = true)
            }
            ConfirmationOutcome.Retired ->
                DvrMutationResult.Failed(TvheadendFailure.CONNECTION_CHANGED, mayHaveApplied = true)
            ConfirmationOutcome.TimedOut -> if (isActive(operation)) {
                DvrMutationResult.AcceptedButUnconfirmed(value)
            } else {
                DvrMutationResult.Failed(TvheadendFailure.CONNECTION_CHANGED, mayHaveApplied = true)
            }
        }
    }

    private suspend fun denied(operation: PendingMutation): DvrMutationResult<Nothing> =
        if (applyProof(operation, allowed = false)) {
            DvrMutationResult.Failed(TvheadendFailure.ACCESS_DENIED, mayHaveApplied = false)
        } else {
            DvrMutationResult.Failed(TvheadendFailure.CONNECTION_CHANGED, mayHaveApplied = false)
        }

    private fun failure(
        operation: PendingMutation,
        result: GatewayResult<*>,
    ): DvrMutationResult<Nothing> {
        if (!isActive(operation)) {
            return DvrMutationResult.Failed(
                TvheadendFailure.CONNECTION_CHANGED,
                mayHaveApplied = result.mayHaveAppliedDvrCommand(),
            )
        }
        return when (result) {
            is GatewayResult.Ok -> error("A successful result is not a DVR mutation failure")
            GatewayResult.ServerRejected ->
                DvrMutationResult.Failed(TvheadendFailure.SERVER_REJECTED, mayHaveApplied = false)
            GatewayResult.AccessDenied -> error("Access denial must update DVR proof")
            GatewayResult.ConnectionLimit ->
                DvrMutationResult.Failed(TvheadendFailure.CONNECTION_LIMIT, mayHaveApplied = false)
            GatewayResult.Timeout -> DvrMutationResult.Failed(TvheadendFailure.TIMEOUT, mayHaveApplied = true)
            GatewayResult.TransportUnavailable ->
                DvrMutationResult.Failed(TvheadendFailure.TRANSPORT_UNAVAILABLE, mayHaveApplied = true)
            GatewayResult.NotSupported ->
                DvrMutationResult.Failed(TvheadendFailure.NOT_SUPPORTED, mayHaveApplied = false)
            GatewayResult.MalformedReply ->
                DvrMutationResult.Failed(TvheadendFailure.MALFORMED_RESPONSE, mayHaveApplied = true)
        }
    }

    private suspend fun applyProof(operation: PendingMutation, allowed: Boolean): Boolean {
        val active = isActive(operation)
        if (active) onDvrAccessProof(operation.generation, allowed)
        return active
    }

    private fun setExpected(
        operation: PendingMutation,
        confirmations: Set<ConfirmationKey>,
    ): Boolean = synchronized(lock) {
        if (!isActiveLocked(operation)) {
            false
        } else {
            operation.setExpected(confirmations)
            true
        }
    }

    private fun isActive(operation: PendingMutation): Boolean = synchronized(lock) {
        isActiveLocked(operation)
    }

    private fun isActiveLocked(operation: PendingMutation): Boolean =
        admitted && generation === operation.generation && pending === operation

    private fun clear(operation: PendingMutation) {
        synchronized(lock) {
            if (pending === operation) pending = null
        }
    }
}

/** Called only after dispatch; an explicit refusal proves that the command did not take effect. */
internal fun GatewayResult<*>.mayHaveAppliedDvrCommand(): Boolean = when (this) {
    GatewayResult.ServerRejected,
    GatewayResult.AccessDenied,
    GatewayResult.ConnectionLimit,
    GatewayResult.NotSupported,
    -> false
    is GatewayResult.Ok,
    GatewayResult.Timeout,
    GatewayResult.TransportUnavailable,
    GatewayResult.MalformedReply,
    -> true
}

private sealed interface MutationRegistration {
    class Failed(internal val failure: TvheadendFailure) : MutationRegistration

    class Registered(internal val operation: PendingMutation) : MutationRegistration
}

private class PendingMutation(
    internal val generation: GatewayGeneration,
    confirmations: Set<ConfirmationKey>,
    private val createKind: ConfirmationKind?,
) {
    internal val confirmed = CompletableDeferred<Unit>()
    internal val retired = CompletableDeferred<Unit>()
    private var expected: Set<ConfirmationKey> = confirmations
    private val earlyConfirmations = LinkedHashSet<ConfirmationKey>()

    internal fun accept(key: ConfirmationKey) {
        if (key in expected) {
            confirmed.complete(Unit)
        } else if (expected.isEmpty() && key.kind == createKind) {
            earlyConfirmations += key
        }
    }

    internal fun setExpected(confirmations: Set<ConfirmationKey>) {
        expected = confirmations
        if (earlyConfirmations.any(confirmations::contains)) {
            confirmed.complete(Unit)
        }
        earlyConfirmations.clear()
    }

    internal fun retire() {
        retired.complete(Unit)
    }
}

private enum class ConfirmationKind {
    ENTRY_ADDED,
    ENTRY_UPDATED,

    /** A full entry update whose state shows the entry is no longer scheduled or recording. */
    ENTRY_ENDED,
    ENTRY_DELETED,
    /** File deletion may retain the DVR log entry and report a missing file instead of removal. */
    ENTRY_FILE_MISSING,
    AUTOREC_ADDED,
    AUTOREC_UPDATED,
    AUTOREC_DELETED,
    TIMEREC_ADDED,
    TIMEREC_UPDATED,
    TIMEREC_DELETED,
}

private data class ConfirmationKey(
    internal val kind: ConfirmationKind,
    internal val id: Any,
)

private sealed interface GatewayCommandOutcome<out T> {
    public class Completed<out T>(internal val result: GatewayResult<T>) : GatewayCommandOutcome<T>

    public class Cancelled(internal val exception: CancellationException) :
        GatewayCommandOutcome<Nothing>

    public data object Retired : GatewayCommandOutcome<Nothing>
}

private enum class ConfirmationOutcome {
    Confirmed,
    Retired,
    TimedOut,
}

private fun MetadataEvent.confirmationKeys(): List<ConfirmationKey> = when (this) {
    is MetadataEvent.DvrEntryAdded -> listOf(ConfirmationKey(ConfirmationKind.ENTRY_ADDED, entry.id))
    is MetadataEvent.DvrEntryUpdated -> when {
        // Stats-only updates report recording progress, not the server's reflection of a mutation.
        provenance == GatewayDvrUpdateProvenance.STATS_ONLY -> emptyList()
        else -> buildList {
            add(ConfirmationKey(ConfirmationKind.ENTRY_UPDATED, entry.id))
            if (entry.state in endedEntryStates) add(ConfirmationKey(ConfirmationKind.ENTRY_ENDED, entry.id))
            if (entry.failure == GatewayDvrFailure.FILE_MISSING) {
                add(ConfirmationKey(ConfirmationKind.ENTRY_FILE_MISSING, entry.id))
            }
        }
    }
    is MetadataEvent.DvrEntryDeleted -> listOf(ConfirmationKey(ConfirmationKind.ENTRY_DELETED, entryId))
    is MetadataEvent.AutorecRuleAdded -> listOf(ConfirmationKey(ConfirmationKind.AUTOREC_ADDED, rule.id))
    is MetadataEvent.AutorecRuleUpdated -> listOf(ConfirmationKey(ConfirmationKind.AUTOREC_UPDATED, rule.id))
    is MetadataEvent.AutorecRuleDeleted -> listOf(ConfirmationKey(ConfirmationKind.AUTOREC_DELETED, ruleId))
    is MetadataEvent.TimerecRuleAdded -> listOf(ConfirmationKey(ConfirmationKind.TIMEREC_ADDED, rule.id))
    is MetadataEvent.TimerecRuleUpdated -> listOf(ConfirmationKey(ConfirmationKind.TIMEREC_UPDATED, rule.id))
    is MetadataEvent.TimerecRuleDeleted -> listOf(ConfirmationKey(ConfirmationKind.TIMEREC_DELETED, ruleId))
    is MetadataEvent.ChannelAdded,
    is MetadataEvent.ChannelUpdated,
    is MetadataEvent.ChannelDeleted,
    is MetadataEvent.TagAdded,
    is MetadataEvent.TagUpdated,
    is MetadataEvent.TagDeleted,
    is MetadataEvent.EventAdded,
    is MetadataEvent.EventUpdated,
    is MetadataEvent.EventDeleted,
    is MetadataEvent.InitialSyncCompleted,
    -> emptyList()
}

// Raw server states; error variants are derived later by DvrReducer.
private val endedEntryStates = setOf(DvrEntryState.COMPLETED, DvrEntryState.MISSED)
