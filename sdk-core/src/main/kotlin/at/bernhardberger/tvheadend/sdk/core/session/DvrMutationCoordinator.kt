package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.AutorecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrMutationCommands
import at.bernhardberger.tvheadend.sdk.core.DvrMutationResult
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleId
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.ProtocolGateway
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

    public fun stopAdmission()

    public data object None : DvrMutationLifecycle {
        override fun bindGeneration(generation: GatewayGeneration) = Unit

        override fun startAdmission(generation: GatewayGeneration): Boolean = true

        override fun stopAdmission() = Unit
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
    private val onDvrAccessProof: (GatewayGeneration, Boolean) -> Unit = { _, _ -> },
) : DvrMutationCommands, DvrMutationLifecycle {
    private val lock = Any()
    private val operationMutex = Mutex()
    private var generation: GatewayGeneration? = null
    private var admitted = false
    private var pending: PendingMutation? = null

    override fun bindGeneration(generation: GatewayGeneration) {
        val retired = synchronized(lock) {
            val previous = pending
            pending = null
            admitted = false
            this.generation = generation
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

    override fun stopAdmission() {
        val retired = synchronized(lock) {
            admitted = false
            generation = null
            pending.also { pending = null }
        }
        retired?.retire()
    }

    internal fun acceptMetadata(event: MetadataEvent) {
        val key = event.confirmationKey() ?: return
        synchronized(lock) {
            if (event.generation === generation) {
                pending?.accept(key)
            }
        }
    }

    override suspend fun scheduleEntry(
        request: DvrScheduleRequest,
    ): DvrMutationResult<DvrEntryId> = create(
        confirmationKind = ConfirmationKind.ENTRY_ADDED,
        command = { generation -> gateway.scheduleDvrEntry(generation, request) },
        confirmation = { id -> ConfirmationKey(ConfirmationKind.ENTRY_ADDED, id) },
    )

    override suspend fun updateEntry(
        id: DvrEntryId,
        update: DvrEntryUpdate,
    ): DvrMutationResult<Unit> = mutate(
        confirmations = setOf(ConfirmationKey(ConfirmationKind.ENTRY_UPDATED, id)),
        command = { generation -> gateway.updateDvrEntry(generation, id, update) },
    )

    override suspend fun stopEntry(id: DvrEntryId): DvrMutationResult<Unit> = mutate(
        confirmations = setOf(ConfirmationKey(ConfirmationKind.ENTRY_UPDATED, id)),
        command = { generation -> gateway.stopDvrEntry(generation, id) },
    )

    override suspend fun cancelEntry(id: DvrEntryId): DvrMutationResult<Unit> = mutate(
        confirmations = setOf(
            ConfirmationKey(ConfirmationKind.ENTRY_UPDATED, id),
            ConfirmationKey(ConfirmationKind.ENTRY_DELETED, id),
        ),
        command = { generation -> gateway.cancelDvrEntry(generation, id) },
    )

    override suspend fun deleteEntry(id: DvrEntryId): DvrMutationResult<Unit> = mutate(
        confirmations = setOf(ConfirmationKey(ConfirmationKind.ENTRY_DELETED, id)),
        command = { generation -> gateway.deleteDvrEntry(generation, id) },
    )

    override suspend fun createAutorecRule(
        request: AutorecRuleCreate,
    ): DvrMutationResult<AutorecRuleId> = create(
        confirmationKind = ConfirmationKind.AUTOREC_ADDED,
        command = { generation -> gateway.createAutorecRule(generation, request) },
        confirmation = { id -> ConfirmationKey(ConfirmationKind.AUTOREC_ADDED, id) },
    )

    override suspend fun updateAutorecRule(
        id: AutorecRuleId,
        update: AutorecRuleUpdate,
    ): DvrMutationResult<Unit> = mutate(
        confirmations = setOf(ConfirmationKey(ConfirmationKind.AUTOREC_UPDATED, id)),
        command = { generation -> gateway.updateAutorecRule(generation, id, update) },
    )

    override suspend fun deleteAutorecRule(id: AutorecRuleId): DvrMutationResult<Unit> = mutate(
        confirmations = setOf(ConfirmationKey(ConfirmationKind.AUTOREC_DELETED, id)),
        command = { generation -> gateway.deleteAutorecRule(generation, id) },
    )

    override suspend fun createTimerecRule(
        request: TimerecRuleCreate,
    ): DvrMutationResult<TimerecRuleId> = create(
        confirmationKind = ConfirmationKind.TIMEREC_ADDED,
        command = { generation -> gateway.createTimerecRule(generation, request) },
        confirmation = { id -> ConfirmationKey(ConfirmationKind.TIMEREC_ADDED, id) },
    )

    override suspend fun updateTimerecRule(
        id: TimerecRuleId,
        update: TimerecRuleUpdate,
    ): DvrMutationResult<Unit> = mutate(
        confirmations = setOf(ConfirmationKey(ConfirmationKind.TIMEREC_UPDATED, id)),
        command = { generation -> gateway.updateTimerecRule(generation, id, update) },
    )

    override suspend fun deleteTimerecRule(id: TimerecRuleId): DvrMutationResult<Unit> = mutate(
        confirmations = setOf(ConfirmationKey(ConfirmationKind.TIMEREC_DELETED, id)),
        command = { generation -> gateway.deleteTimerecRule(generation, id) },
    )

    private suspend fun <T : Any> create(
        confirmationKind: ConfirmationKind,
        command: suspend (GatewayGeneration) -> GatewayResult<T>,
        confirmation: (T) -> ConfirmationKey,
    ): DvrMutationResult<T> = operationMutex.withLock {
        val operation = register(createKind = confirmationKind) ?: return@withLock DvrMutationResult.NotReady
        try {
            when (val outcome = awaitCommand(operation, command)) {
                GatewayCommandOutcome.Retired -> DvrMutationResult.TransportUnavailable
                is GatewayCommandOutcome.Cancelled -> throw outcome.exception
                is GatewayCommandOutcome.Completed -> when (val result = outcome.result) {
                    is GatewayResult.Ok -> {
                        if (!applyProof(operation, allowed = true)) {
                            DvrMutationResult.TransportUnavailable
                        } else if (!setExpected(operation, setOf(confirmation(result.value)))) {
                            DvrMutationResult.TransportUnavailable
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
                    -> failure(operation, result)
                }
            }
        } finally {
            clear(operation)
        }
    }

    private suspend fun mutate(
        confirmations: Set<ConfirmationKey>,
        command: suspend (GatewayGeneration) -> GatewayResult<Unit>,
    ): DvrMutationResult<Unit> = operationMutex.withLock {
        val operation = register(confirmations = confirmations)
            ?: return@withLock DvrMutationResult.NotReady
        try {
            when (val outcome = awaitCommand(operation, command)) {
                GatewayCommandOutcome.Retired -> DvrMutationResult.TransportUnavailable
                is GatewayCommandOutcome.Cancelled -> throw outcome.exception
                is GatewayCommandOutcome.Completed -> when (val result = outcome.result) {
                    is GatewayResult.Ok -> {
                        if (!applyProof(operation, allowed = true)) {
                            DvrMutationResult.TransportUnavailable
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
                    -> failure(operation, result)
                }
            }
        } finally {
            clear(operation)
        }
    }

    private fun register(
        confirmations: Set<ConfirmationKey> = emptySet(),
        createKind: ConfirmationKind? = null,
    ): PendingMutation? {
        val activeGeneration = synchronized(lock) {
            generation.takeIf { admitted && pending == null }
        } ?: return null
        if (!isSessionReady(activeGeneration)) return null
        return synchronized(lock) {
            if (!admitted || generation !== activeGeneration || pending != null) {
                null
            } else {
                PendingMutation(activeGeneration, confirmations, createKind).also { pending = it }
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
                DvrMutationResult.TransportUnavailable
            }
            ConfirmationOutcome.Retired -> DvrMutationResult.TransportUnavailable
            ConfirmationOutcome.TimedOut -> if (isActive(operation)) {
                DvrMutationResult.AcceptedButUnconfirmed(value)
            } else {
                DvrMutationResult.TransportUnavailable
            }
        }
    }

    private fun denied(operation: PendingMutation): DvrMutationResult<Nothing> =
        if (applyProof(operation, allowed = false)) {
            DvrMutationResult.AccessDenied
        } else {
            DvrMutationResult.TransportUnavailable
        }

    private fun failure(
        operation: PendingMutation,
        result: GatewayResult<*>,
    ): DvrMutationResult<Nothing> {
        if (!isActive(operation)) return DvrMutationResult.TransportUnavailable
        return when (result) {
            is GatewayResult.Ok -> error("A successful result is not a DVR mutation failure")
            GatewayResult.ServerRejected -> DvrMutationResult.ServerRejected
            GatewayResult.AccessDenied -> error("Access denial must update DVR proof")
            GatewayResult.ConnectionLimit -> DvrMutationResult.ConnectionLimit
            GatewayResult.Timeout -> DvrMutationResult.Timeout
            GatewayResult.TransportUnavailable -> DvrMutationResult.TransportUnavailable
            GatewayResult.NotSupported -> DvrMutationResult.NotSupported
        }
    }

    private fun applyProof(operation: PendingMutation, allowed: Boolean): Boolean {
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
    ENTRY_DELETED,
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

private fun MetadataEvent.confirmationKey(): ConfirmationKey? = when (this) {
    is MetadataEvent.DvrEntryAdded -> ConfirmationKey(ConfirmationKind.ENTRY_ADDED, entry.id)
    is MetadataEvent.DvrEntryUpdated -> ConfirmationKey(ConfirmationKind.ENTRY_UPDATED, entry.id)
    is MetadataEvent.DvrEntryDeleted -> ConfirmationKey(ConfirmationKind.ENTRY_DELETED, entryId)
    is MetadataEvent.AutorecRuleAdded -> ConfirmationKey(ConfirmationKind.AUTOREC_ADDED, rule.id)
    is MetadataEvent.AutorecRuleUpdated -> ConfirmationKey(ConfirmationKind.AUTOREC_UPDATED, rule.id)
    is MetadataEvent.AutorecRuleDeleted -> ConfirmationKey(ConfirmationKind.AUTOREC_DELETED, ruleId)
    is MetadataEvent.TimerecRuleAdded -> ConfirmationKey(ConfirmationKind.TIMEREC_ADDED, rule.id)
    is MetadataEvent.TimerecRuleUpdated -> ConfirmationKey(ConfirmationKind.TIMEREC_UPDATED, rule.id)
    is MetadataEvent.TimerecRuleDeleted -> ConfirmationKey(ConfirmationKind.TIMEREC_DELETED, ruleId)
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
    -> null
}
