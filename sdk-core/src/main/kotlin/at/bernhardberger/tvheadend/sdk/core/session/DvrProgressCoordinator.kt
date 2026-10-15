@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.DVR_PROGRESS_MINIMUM_PROTOCOL_VERSION
import at.bernhardberger.tvheadend.sdk.core.DVR_CUTPOINTS_MINIMUM_PROTOCOL_VERSION
import at.bernhardberger.tvheadend.sdk.core.DvrCutpointCommands
import at.bernhardberger.tvheadend.sdk.core.DvrCutpointsResult
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrProgressCommands
import at.bernhardberger.tvheadend.sdk.core.DvrProgressResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.ProtocolGateway
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal interface DvrProgressLifecycle {
    public fun bindGeneration(generation: GatewayGeneration, protocolVersion: Long?)

    public fun startAdmission(generation: GatewayGeneration): Boolean

    /** Distinguishes connection retirement from shutdown or readiness-admission rollback. */
    public fun stopAdmission(connectionRetired: Boolean = false)

    public data object None : DvrProgressLifecycle {
        override fun bindGeneration(generation: GatewayGeneration, protocolVersion: Long?) = Unit

        override fun startAdmission(generation: GatewayGeneration): Boolean = true

        override fun stopAdmission(connectionRetired: Boolean) = Unit
    }
}

internal class DvrProgressCoordinator(
    private val gateway: ProtocolGateway,
    private val isSessionReady: (GatewayGeneration) -> Boolean = { true },
    private val onDvrAccessProof: suspend (GatewayGeneration, Boolean) -> Unit = { _, _ -> },
    private val onProgressNotSupported: suspend (GatewayGeneration) -> Unit = {},
) : DvrProgressCommands, DvrCutpointCommands, DvrProgressLifecycle {
    private val lock = Any()
    private var generation: GatewayGeneration? = null
    private var latestBoundGeneration: GatewayGeneration? = null
    private var admitted = false
    private var stoppedFailure: TvheadendFailure? = null
    private var notSupported = false
    private var cutpointsNotSupported = false

    override fun bindGeneration(generation: GatewayGeneration, protocolVersion: Long?) {
        synchronized(lock) {
            admitted = false
            stoppedFailure = null
            this.generation = generation
            latestBoundGeneration = generation
            notSupported = protocolVersion == null ||
                protocolVersion < DVR_PROGRESS_MINIMUM_PROTOCOL_VERSION
            cutpointsNotSupported = protocolVersion == null ||
                protocolVersion < DVR_CUTPOINTS_MINIMUM_PROTOCOL_VERSION
        }
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
        synchronized(lock) {
            admitted = false
            stoppedFailure = if (connectionRetired) TvheadendFailure.CONNECTION_CHANGED else TvheadendFailure.NOT_READY
            generation = null
            notSupported = false
            cutpointsNotSupported = false
        }
    }

    override suspend fun resetResume(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): DvrProgressResult = performProgressCommand(
        expectedGeneration = generation,
        admissionExpired = TvheadendFailure.OBSERVATION_EXPIRED,
        targetIsCurrent = { true },
        command = { gateway.resetDvrResume(it, id) },
    )

    override suspend fun updateWatchState(
        generation: GatewayGeneration,
        id: DvrEntryId,
        watched: Boolean,
    ): DvrProgressResult = performProgressCommand(
        expectedGeneration = generation,
        admissionExpired = TvheadendFailure.OBSERVATION_EXPIRED,
        targetIsCurrent = { true },
        command = { gateway.updateDvrWatchState(it, id, watched) },
    )

    override suspend fun reportProgress(
        generation: GatewayGeneration,
        id: DvrEntryId,
        progress: DvrPlaybackProgress,
        targetIsCurrent: () -> Boolean,
    ): DvrProgressResult = performProgressCommand(
        expectedGeneration = generation,
        admissionExpired = TvheadendFailure.OBSERVATION_EXPIRED,
        targetIsCurrent = targetIsCurrent,
        command = { gateway.reportDvrProgress(it, id, progress) },
    )

    override suspend fun reportProgress(
        lease: GrowingRecordingFileLease,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult {
        val bound = lease as? GenerationBoundGrowingRecordingFileLease
            ?: return DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)
        return performProgressCommand(
            expectedGeneration = bound.boundGeneration,
            admissionExpired = TvheadendFailure.CONNECTION_CHANGED,
            targetIsCurrent = bound::isProgressBindingCurrent,
            command = { gateway.reportDvrProgress(it, bound.boundRecordingId, progress) },
        )
    }

    private suspend fun performProgressCommand(
        expectedGeneration: GatewayGeneration,
        admissionExpired: TvheadendFailure,
        targetIsCurrent: () -> Boolean,
        command: suspend (GatewayGeneration) -> GatewayResult<Unit>,
    ): DvrProgressResult {
        currentCoroutineContext().ensureActive()
        val activeGeneration = synchronized(lock) {
            if (latestBoundGeneration !== expectedGeneration) {
                return DvrProgressResult.Failed(admissionExpired, mayHaveApplied = false)
            }
            if (generation !== expectedGeneration) {
                return DvrProgressResult.Failed(stoppedFailure ?: admissionExpired, mayHaveApplied = false)
            }
            generation.takeIf { admitted }
        } ?: return DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)
        if (!isSessionReady(activeGeneration)) {
            return DvrProgressResult.Failed(
                inactiveFailure(activeGeneration) ?: TvheadendFailure.NOT_READY,
                mayHaveApplied = false,
            )
        }
        val unsupported = synchronized(lock) {
            inactiveFailure(activeGeneration)?.let { failure ->
                return DvrProgressResult.Failed(failure, mayHaveApplied = false)
            }
            notSupported
        }
        if (unsupported) {
            return DvrProgressResult.Failed(TvheadendFailure.NOT_SUPPORTED, mayHaveApplied = false)
        }
        if (!targetIsCurrent()) {
            return DvrProgressResult.Failed(
                inactiveFailure(activeGeneration) ?: TvheadendFailure.TRANSPORT_UNAVAILABLE,
                mayHaveApplied = false,
            )
        }
        synchronized(lock) {
            inactiveFailure(activeGeneration)?.let { failure ->
                return DvrProgressResult.Failed(failure, mayHaveApplied = false)
            }
        }
        val result = try {
            command(activeGeneration)
        } catch (cancellation: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw cancellation
        } catch (_: Exception) {
            GatewayResult.TransportUnavailable
        }
        currentCoroutineContext().ensureActive()
        val classified = synchronized(lock) {
            if (!admitted || generation !== activeGeneration) {
                ClassifiedProgress(
                    DvrProgressResult.Failed(
                        TvheadendFailure.CONNECTION_CHANGED,
                        mayHaveApplied = result.mayHaveAppliedDvrCommand(),
                    ),
                    proof = null,
                )
            } else {
                when (result) {
                    is GatewayResult.Ok -> ClassifiedProgress(DvrProgressResult.Accepted, proof = true)
                    GatewayResult.AccessDenied ->
                        ClassifiedProgress(
                            DvrProgressResult.Failed(TvheadendFailure.ACCESS_DENIED, mayHaveApplied = false),
                            proof = false,
                        )
                    GatewayResult.NotSupported -> {
                        notSupported = true
                        ClassifiedProgress(
                            DvrProgressResult.Failed(TvheadendFailure.NOT_SUPPORTED, mayHaveApplied = false),
                            proof = null,
                        )
                    }
                    is GatewayResult.ServerRejected ->
                        ClassifiedProgress(
                            DvrProgressResult.Failed(TvheadendFailure.SERVER_REJECTED, mayHaveApplied = false),
                            proof = null,
                        )
                    GatewayResult.MalformedReply ->
                        ClassifiedProgress(
                            DvrProgressResult.Failed(TvheadendFailure.MALFORMED_RESPONSE, mayHaveApplied = true),
                            proof = null,
                        )
                    GatewayResult.ConnectionLimit ->
                        ClassifiedProgress(
                            DvrProgressResult.Failed(TvheadendFailure.CONNECTION_LIMIT, mayHaveApplied = false),
                            proof = null,
                        )
                    GatewayResult.Timeout -> ClassifiedProgress(
                        DvrProgressResult.Failed(TvheadendFailure.TIMEOUT, mayHaveApplied = true),
                        proof = null,
                    )
                    GatewayResult.TransportUnavailable ->
                        ClassifiedProgress(
                            DvrProgressResult.Failed(TvheadendFailure.TRANSPORT_UNAVAILABLE, mayHaveApplied = true),
                            proof = null,
                        )
                }
            }
        }
        classified.proof?.let { allowed -> onDvrAccessProof(activeGeneration, allowed) }
        if (
            result === GatewayResult.NotSupported &&
            classified.result == DvrProgressResult.Failed(TvheadendFailure.NOT_SUPPORTED, mayHaveApplied = false)
        ) {
            onProgressNotSupported(activeGeneration)
        }
        return classified.result
    }

    override suspend fun getCutpoints(
        generation: GatewayGeneration,
        id: DvrEntryId,
        targetIsCurrent: () -> Boolean,
    ): DvrCutpointsResult {
        val activeGeneration = synchronized(lock) {
            if (latestBoundGeneration !== generation) {
                return DvrCutpointsResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED)
            }
            if (this.generation !== generation) {
                return DvrCutpointsResult.Failed(stoppedFailure ?: TvheadendFailure.OBSERVATION_EXPIRED)
            }
            this.generation.takeIf { admitted }
        } ?: return DvrCutpointsResult.Failed(TvheadendFailure.NOT_READY)
        if (!isSessionReady(activeGeneration)) {
            return DvrCutpointsResult.Failed(inactiveFailure(activeGeneration) ?: TvheadendFailure.NOT_READY)
        }
        val unsupported = synchronized(lock) {
            inactiveFailure(activeGeneration)?.let { failure ->
                return DvrCutpointsResult.Failed(failure)
            }
            cutpointsNotSupported
        }
        if (unsupported) {
            return DvrCutpointsResult.Failed(TvheadendFailure.NOT_SUPPORTED)
        }
        if (!targetIsCurrent()) {
            return DvrCutpointsResult.Failed(inactiveFailure(activeGeneration) ?: TvheadendFailure.TRANSPORT_UNAVAILABLE)
        }
        synchronized(lock) {
            inactiveFailure(activeGeneration)?.let { failure ->
                return DvrCutpointsResult.Failed(failure)
            }
        }
        val result = try {
            gateway.getDvrCutpoints(activeGeneration, id)
        } catch (cancellation: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw cancellation
        } catch (_: Exception) {
            GatewayResult.TransportUnavailable
        }
        return synchronized(lock) {
            if (!admitted || this.generation !== activeGeneration) {
                DvrCutpointsResult.Failed(TvheadendFailure.CONNECTION_CHANGED)
            } else {
                when (result) {
                    is GatewayResult.Ok -> DvrCutpointsResult.Available.create(result.value)
                    GatewayResult.AccessDenied -> DvrCutpointsResult.Failed(TvheadendFailure.ACCESS_DENIED)
                    GatewayResult.NotSupported -> {
                        cutpointsNotSupported = true
                        DvrCutpointsResult.Failed(TvheadendFailure.NOT_SUPPORTED)
                    }
                    is GatewayResult.ServerRejected -> DvrCutpointsResult.Failed(TvheadendFailure.SERVER_REJECTED)
                    GatewayResult.MalformedReply -> DvrCutpointsResult.Failed(TvheadendFailure.MALFORMED_RESPONSE)
                    GatewayResult.ConnectionLimit -> DvrCutpointsResult.Failed(TvheadendFailure.CONNECTION_LIMIT)
                    GatewayResult.Timeout -> DvrCutpointsResult.Failed(TvheadendFailure.TIMEOUT)
                    GatewayResult.TransportUnavailable ->
                        DvrCutpointsResult.Failed(TvheadendFailure.TRANSPORT_UNAVAILABLE)
                }
            }
        }
    }

    private fun inactiveFailure(expectedGeneration: GatewayGeneration): TvheadendFailure? = synchronized(lock) {
        when {
            generation !== expectedGeneration -> stoppedFailure ?: TvheadendFailure.CONNECTION_CHANGED
            !admitted -> TvheadendFailure.NOT_READY
            else -> null
        }
    }
}

private class ClassifiedProgress(
    internal val result: DvrProgressResult,
    internal val proof: Boolean?,
)
