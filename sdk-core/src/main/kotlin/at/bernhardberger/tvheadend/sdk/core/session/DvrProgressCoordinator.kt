@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

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
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal interface DvrProgressLifecycle {
    public fun bindGeneration(generation: GatewayGeneration, protocolVersion: Int?)

    public fun startAdmission(generation: GatewayGeneration): Boolean

    public fun stopAdmission()

    public data object None : DvrProgressLifecycle {
        override fun bindGeneration(generation: GatewayGeneration, protocolVersion: Int?) = Unit

        override fun startAdmission(generation: GatewayGeneration): Boolean = true

        override fun stopAdmission() = Unit
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
    private var admitted = false
    private var notSupported = false
    private var cutpointsNotSupported = false

    override fun bindGeneration(generation: GatewayGeneration, protocolVersion: Int?) {
        synchronized(lock) {
            admitted = false
            this.generation = generation
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

    override fun stopAdmission() {
        synchronized(lock) {
            admitted = false
            generation = null
            notSupported = false
            cutpointsNotSupported = false
        }
    }

    override suspend fun reportProgress(
        id: DvrEntryId,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult = reportProgress(id, progress, expectedGeneration = null)

    override suspend fun reportProgress(
        lease: GrowingRecordingFileLease,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult {
        val bound = lease as? GenerationBoundGrowingRecordingFileLease
            ?: return DvrProgressResult.NotReady
        return reportProgress(
            id = bound.boundRecordingId,
            progress = progress,
            expectedGeneration = bound.boundGeneration,
            targetIsCurrent = bound::isProgressBindingCurrent,
        )
    }

    private suspend fun reportProgress(
        id: DvrEntryId,
        progress: DvrPlaybackProgress,
        expectedGeneration: GatewayGeneration?,
        targetIsCurrent: (() -> Boolean)? = null,
    ): DvrProgressResult {
        val activeGeneration = synchronized(lock) {
            if (expectedGeneration != null && generation !== expectedGeneration) {
                return DvrProgressResult.TransportUnavailable
            }
            generation.takeIf { admitted }
        } ?: return DvrProgressResult.NotReady
        if (!isSessionReady(activeGeneration)) {
            return DvrProgressResult.NotReady
        }
        val unsupported = synchronized(lock) {
            if (!admitted || generation !== activeGeneration) {
                return DvrProgressResult.TransportUnavailable
            }
            notSupported
        }
        if (unsupported) {
            return DvrProgressResult.NotSupported
        }
        if (targetIsCurrent != null) {
            if (!targetIsCurrent()) return DvrProgressResult.TransportUnavailable
            synchronized(lock) {
                if (!admitted || generation !== activeGeneration) {
                    return DvrProgressResult.TransportUnavailable
                }
            }
        }
        val result = try {
            gateway.reportDvrProgress(activeGeneration, id, progress)
        } catch (cancellation: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw cancellation
        } catch (_: Exception) {
            GatewayResult.TransportUnavailable
        }
        val classified = synchronized(lock) {
            if (!admitted || generation !== activeGeneration) {
                ClassifiedProgress(DvrProgressResult.TransportUnavailable, proof = null)
            } else {
                when (result) {
                    is GatewayResult.Ok -> ClassifiedProgress(DvrProgressResult.Accepted, proof = true)
                    GatewayResult.AccessDenied ->
                        ClassifiedProgress(DvrProgressResult.AccessDenied, proof = false)
                    GatewayResult.NotSupported -> {
                        notSupported = true
                        ClassifiedProgress(DvrProgressResult.NotSupported, proof = null)
                    }
                    GatewayResult.ServerRejected ->
                        ClassifiedProgress(DvrProgressResult.ServerRejected, proof = null)
                    GatewayResult.ConnectionLimit ->
                        ClassifiedProgress(DvrProgressResult.ConnectionLimit, proof = null)
                    GatewayResult.Timeout -> ClassifiedProgress(DvrProgressResult.Timeout, proof = null)
                    GatewayResult.TransportUnavailable ->
                        ClassifiedProgress(DvrProgressResult.TransportUnavailable, proof = null)
                }
            }
        }
        classified.proof?.let { allowed -> onDvrAccessProof(activeGeneration, allowed) }
        if (
            result === GatewayResult.NotSupported &&
            classified.result === DvrProgressResult.NotSupported
        ) {
            onProgressNotSupported(activeGeneration)
        }
        return classified.result
    }

    override suspend fun getCutpoints(id: DvrEntryId): DvrCutpointsResult {
        val activeGeneration = synchronized(lock) {
            generation.takeIf { admitted }
        } ?: return DvrCutpointsResult.NotReady
        if (!isSessionReady(activeGeneration)) {
            return DvrCutpointsResult.NotReady
        }
        val unsupported = synchronized(lock) {
            if (!admitted || generation !== activeGeneration) {
                return DvrCutpointsResult.TransportUnavailable
            }
            cutpointsNotSupported
        }
        if (unsupported) {
            return DvrCutpointsResult.NotSupported
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
            if (!admitted || generation !== activeGeneration) {
                DvrCutpointsResult.TransportUnavailable
            } else {
                when (result) {
                    is GatewayResult.Ok -> DvrCutpointsResult.Available.create(result.value)
                    GatewayResult.AccessDenied -> DvrCutpointsResult.AccessDenied
                    GatewayResult.NotSupported -> {
                        cutpointsNotSupported = true
                        DvrCutpointsResult.NotSupported
                    }
                    GatewayResult.ServerRejected -> DvrCutpointsResult.ServerRejected
                    GatewayResult.ConnectionLimit -> DvrCutpointsResult.ConnectionLimit
                    GatewayResult.Timeout -> DvrCutpointsResult.Timeout
                    GatewayResult.TransportUnavailable -> DvrCutpointsResult.TransportUnavailable
                }
            }
        }
    }
}

private class ClassifiedProgress(
    internal val result: DvrProgressResult,
    internal val proof: Boolean?,
)
