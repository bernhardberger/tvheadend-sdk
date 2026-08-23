@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayRecordingFile
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.ProtocolGateway
import at.bernhardberger.tvheadend.sdk.playback.RecordingFile
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Binds one open server-side handle to the generation and gateway that produced it.
 *
 * The handle keeps [generation] strongly, so the gateway can still resolve it for the closing round
 * trip even after the owning session has moved on.
 */
internal class GatewayRecordingFileHandle(
    private val gateway: ProtocolGateway,
    private val generation: GatewayGeneration,
    private val file: GatewayRecordingFile,
) : RecordingFile {
    private val closed = AtomicBoolean(false)

    override val sizeBytes: Long? = file.sizeBytes

    override suspend fun seek(position: Long): RecordingFileResult<Long> {
        if (closed.get()) return CLOSED_RECORDING_HANDLE
        return gateway.seekRecordingFile(generation, file, position)
            .toRecordingFileResult { offset -> offset }
    }

    override suspend fun read(
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): RecordingFileResult<Int> {
        if (closed.get()) return CLOSED_RECORDING_HANDLE
        return gateway
            .readRecordingFile(generation, file, position, destination, destinationOffset, length)
            .toRecordingFileResult { count -> count }
    }

    override suspend fun close(): RecordingFileResult<Unit> {
        if (!closed.compareAndSet(false, true)) return RecordingFileResult.Ok(Unit)
        return gateway.closeRecordingFile(generation, file).toRecordingFileResult {}
    }

    override fun toString(): String = "GatewayRecordingFileHandle(<redacted>)"
}

/**
 * Maps one gateway outcome onto the transport-neutral recording failure classification.
 *
 * A lost transport generation becomes [RecordingFileFailure.CONNECTION_CHANGED] so a caller can
 * distinguish a connection it may reopen from a file this server will not serve.
 */
internal inline fun <T, R> GatewayResult<T>.toRecordingFileResult(
    transform: (T) -> R,
): RecordingFileResult<R> = when (this) {
    is GatewayResult.Ok -> RecordingFileResult.Ok(transform(value))
    GatewayResult.ServerRejected -> RecordingFileResult.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
    GatewayResult.AccessDenied -> RecordingFileResult.Failed(RecordingFileFailure.ACCESS_DENIED)
    GatewayResult.ConnectionLimit ->
        RecordingFileResult.Failed(RecordingFileFailure.CONNECTION_LIMIT)
    GatewayResult.Timeout -> RecordingFileResult.Failed(RecordingFileFailure.TIMEOUT)
    GatewayResult.TransportUnavailable ->
        RecordingFileResult.Failed(RecordingFileFailure.CONNECTION_CHANGED)
    GatewayResult.NotSupported -> RecordingFileResult.Failed(RecordingFileFailure.NOT_SUPPORTED)
}

private val CLOSED_RECORDING_HANDLE =
    RecordingFileResult.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
