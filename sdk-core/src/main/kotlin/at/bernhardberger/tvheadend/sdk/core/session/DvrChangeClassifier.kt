package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.DvrChange
import at.bernhardberger.tvheadend.sdk.core.DvrChangeKind
import at.bernhardberger.tvheadend.sdk.core.DvrChangeOrigin
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.SessionGenerationIdentity
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrUpdateProvenance
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.metadata.ReducedDvrEntry
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal fun classifyDvrChange(
    event: MetadataEvent,
    previous: ReducedDvrEntry?,
    current: ReducedDvrEntry?,
    identity: SessionGenerationIdentity,
    serverTime: Instant?,
    intents: DvrIntentLedger?,
    failureReported: Boolean,
): DvrChange? {
    if (event is MetadataEvent.DvrEntryUpdated && event.provenance == GatewayDvrUpdateProvenance.STATS_ONLY) return null
    val before = previous?.toPublicOrNull()
    val after = current?.toPublicOrNull()
    val kind = when (event) {
        is MetadataEvent.DvrEntryDeleted -> if (before != null) DvrChangeKind.REMOVED else null
        is MetadataEvent.DvrEntryAdded -> if (before == null) when (after?.state) {
            DvrEntryState.SCHEDULED -> DvrChangeKind.SCHEDULED
            DvrEntryState.RECORDING -> DvrChangeKind.RECORDING_STARTED
            else -> null
        } else transition(before, after, current?.failure, serverTime)
        is MetadataEvent.DvrEntryUpdated -> transition(before, after, current?.failure, serverTime)
        else -> null
    }
    val terminalMove = before?.state in recordingStates && after?.state in terminalStates && before?.state != after?.state
    val entry = after ?: before ?: return null
    val stopOrigin = if (terminalMove) intents?.takeStop(event.generation, entry) else null
    val resolvedKind = if (stopOrigin != null) DvrChangeKind.RECORDING_STOPPED else kind ?: return null
    if (resolvedKind == DvrChangeKind.RECORDING_FAILED && failureReported) return null
    // A recording error is still active, so must not consume a pending STOP/CANCEL intent.
    val origin = stopOrigin ?: if (after?.state == DvrEntryState.RECORDING_ERROR) DvrChangeOrigin.External else
        intents?.origin(event.generation, entry, resolvedKind)
            ?: DvrChangeOrigin.External
    return DvrChange.create(
        resolvedKind,
        before, after, origin, identity,
    )
}

private fun transition(
    before: DvrEntry?,
    after: DvrEntry?,
    failure: GatewayDvrFailure?,
    serverTime: Instant?,
): DvrChangeKind? {
    if (before == null || after == null || before.state == after.state) return null
    if (before.state == DvrEntryState.INVALID || after.state == DvrEntryState.INVALID) return null
    return when {
        failure == GatewayDvrFailure.ABORTED && after.state in terminalStates -> DvrChangeKind.RECORDING_ABORTED
        failure == GatewayDvrFailure.ABORTED -> null
        before.state in setOf(DvrEntryState.SCHEDULED, DvrEntryState.RECORDING) && after.state in errorStates -> DvrChangeKind.RECORDING_FAILED
        before.state == DvrEntryState.SCHEDULED && after.state == DvrEntryState.RECORDING -> DvrChangeKind.RECORDING_STARTED
        before.state in recordingStates && after.state == DvrEntryState.COMPLETED -> {
            val completedAt = after.files?.mapNotNull { it.stop }?.maxOrNull() ?: serverTime
            if (completedAt != null && after.stop != null && after.stop - completedAt > 60.seconds) {
                DvrChangeKind.RECORDING_STOPPED
            } else DvrChangeKind.RECORDING_COMPLETED
        }
        before.state == DvrEntryState.SCHEDULED && after.state == DvrEntryState.MISSED -> DvrChangeKind.MISSED
        else -> null
    }
}

private val recordingStates = setOf(DvrEntryState.RECORDING, DvrEntryState.RECORDING_ERROR)
private val errorStates = setOf(DvrEntryState.RECORDING_ERROR, DvrEntryState.COMPLETED_ERROR, DvrEntryState.FILE_MISSING)
private val terminalStates = setOf(DvrEntryState.COMPLETED, DvrEntryState.COMPLETED_ERROR, DvrEntryState.FILE_MISSING, DvrEntryState.MISSED)
