package at.bernhardberger.tvheadend.sdk.core.gateway

import at.bernhardberger.tvheadend.sdk.core.AutorecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.DvrConfiguration
import at.bernhardberger.tvheadend.sdk.core.DvrCutpoint
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpace
import at.bernhardberger.tvheadend.sdk.core.DvrEntryUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleUpdate
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionConfirmation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Instant

@OptIn(SubscriptionInfrastructureApi::class)
internal interface ProtocolGateway {
    public val connectionState: StateFlow<GatewayState>
    public val metadata: Flow<MetadataEvent>
    public val connectionFailures: Flow<GatewayConnectionFailureEvent>

    public suspend fun connect(server: ServerConfiguration): GatewayConnectResult

    public suspend fun disconnect()

    public suspend fun shutdown()

    public fun <T> commitIfLive(
        generation: GatewayGeneration,
        block: () -> T,
    ): T?

    public suspend fun enableInitialMetadata(
        generation: GatewayGeneration,
    ): GatewayResult<Unit>

    public suspend fun queryEpg(
        generation: GatewayGeneration,
        channelId: ChannelId,
        maxTime: Instant,
    ): GatewayResult<List<GatewayEpgQueryEvent>>

    public suspend fun getDvrConfigs(
        generation: GatewayGeneration,
    ): GatewayResult<List<DvrConfiguration>>

    public suspend fun getDiskSpace(
        generation: GatewayGeneration,
    ): GatewayResult<DvrDiskSpace>

    public suspend fun getDvrCutpoints(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<List<DvrCutpoint>>

    public suspend fun scheduleDvrEntry(
        generation: GatewayGeneration,
        request: DvrScheduleRequest,
    ): GatewayResult<DvrEntryId>

    public suspend fun updateDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
        update: DvrEntryUpdate,
    ): GatewayResult<Unit>

    public suspend fun stopDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<Unit>

    public suspend fun cancelDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<Unit>

    public suspend fun deleteDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<Unit>

    public suspend fun createAutorecRule(
        generation: GatewayGeneration,
        request: AutorecRuleCreate,
    ): GatewayResult<AutorecRuleId>

    public suspend fun updateAutorecRule(
        generation: GatewayGeneration,
        id: AutorecRuleId,
        update: AutorecRuleUpdate,
    ): GatewayResult<Unit>

    public suspend fun deleteAutorecRule(
        generation: GatewayGeneration,
        id: AutorecRuleId,
    ): GatewayResult<Unit>

    public suspend fun createTimerecRule(
        generation: GatewayGeneration,
        request: TimerecRuleCreate,
    ): GatewayResult<TimerecRuleId>

    public suspend fun updateTimerecRule(
        generation: GatewayGeneration,
        id: TimerecRuleId,
        update: TimerecRuleUpdate,
    ): GatewayResult<Unit>

    public suspend fun deleteTimerecRule(
        generation: GatewayGeneration,
        id: TimerecRuleId,
    ): GatewayResult<Unit>

    public suspend fun reportDvrProgress(
        generation: GatewayGeneration,
        id: DvrEntryId,
        progress: DvrPlaybackProgress,
    ): GatewayResult<Unit>

    public suspend fun loadArtwork(
        generation: GatewayGeneration,
        id: ArtworkId,
    ): GatewayResult<ByteArray> = GatewayResult.NotSupported

    public suspend fun openRecordingFile(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<GatewayRecordingFile>

    public suspend fun seekRecordingFile(
        generation: GatewayGeneration,
        file: GatewayRecordingFile,
        position: Long,
    ): GatewayResult<Long>

    public suspend fun readRecordingFile(
        generation: GatewayGeneration,
        file: GatewayRecordingFile,
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): GatewayResult<Int>

    public suspend fun statRecordingFile(
        generation: GatewayGeneration,
        file: GatewayRecordingFile,
    ): GatewayResult<GatewayRecordingFileStat> = GatewayResult.NotSupported

    public suspend fun closeRecordingFile(
        generation: GatewayGeneration,
        file: GatewayRecordingFile,
    ): GatewayResult<Unit>

    public fun subscription(
        generation: GatewayGeneration,
        id: SubscriptionId,
    ): Flow<SubscriptionEvent>

    public suspend fun subscribe(
        generation: GatewayGeneration,
        id: SubscriptionId,
        channelId: ChannelId,
        timeshiftPeriod: Duration,
    ): SubscriptionOperationResult<SubscriptionConfirmation>

    public suspend fun skipSubscription(
        generation: GatewayGeneration,
        id: SubscriptionId,
        target: SubscriptionSeekTarget,
    ): SubscriptionOperationResult<Unit>

    public suspend fun skipSubscriptionNearLive(
        generation: GatewayGeneration,
        id: SubscriptionId,
        status: SubscriptionEvent.Timeshift,
        marginSeconds: Long,
    ): SubscriptionOperationResult<Unit> = SubscriptionOperationResult.NotSupported

    public suspend fun unsubscribe(
        generation: GatewayGeneration,
        id: SubscriptionId,
    ): SubscriptionOperationResult<Unit>
}

internal class ServerConfiguration(
    host: String,
    internal val port: Int,
    internal val authentication: ServerAuthentication = ServerAuthentication.Anonymous,
) {
    internal val host: String = host.trim()

    init {
        require(this.host.isNotEmpty()) { "Server host must not be blank" }
        require(port in 1..65_535) { "Server port must be valid" }
    }

    override fun toString(): String = "ServerConfiguration(<redacted>)"
}

internal sealed interface ServerAuthentication {
    public data object Anonymous : ServerAuthentication

    public class Password(
        username: String,
        password: String,
    ) : ServerAuthentication {
        internal val username: String = username.trim()
        internal val password: String = password

        init {
            require(this.username.isNotEmpty()) { "Username must not be blank" }
            require(this.password.isNotBlank()) { "Password must not be blank" }
        }

        override fun toString(): String = "ServerAuthentication.Password(<redacted>)"
    }
}

internal sealed interface GatewayState {
    public data object Disconnected : GatewayState
    public data object Connecting : GatewayState
    public data object Connected : GatewayState
    public data object Failed : GatewayState
}

internal class GatewayGeneration {
    override fun toString(): String = "GatewayGeneration(<redacted>)"
}

internal class GatewayConnection(
    internal val generation: GatewayGeneration,
    internal val protocolVersion: Int?,
    internal val dvrAccess: Boolean?,
    internal val serverFacts: GatewayServerFacts,
) {
    override fun toString(): String = "GatewayConnection(<redacted>)"
}

/**
 * One open server-side recording file handle bound to the generation that opened it.
 *
 * [protocolVersion] is the version negotiated by that exact generation, snapshotted at open so a
 * later close applies the same wire capabilities the handle was created with.
 */
internal class GatewayRecordingFile(
    internal val handleId: Long,
    internal val sizeBytes: Long?,
    internal val protocolVersion: Int?,
) {
    override fun toString(): String = "GatewayRecordingFile(<redacted>)"
}

/** Optional size and modification time observed from one still-open recording handle. */
internal class GatewayRecordingFileStat(
    internal val sizeBytes: Long?,
    internal val modifiedAtUnixSeconds: Long?,
) {
    override fun toString(): String = "GatewayRecordingFileStat(<redacted>)"
}

internal class GatewayServerFacts(
    internal val serverName: String?,
    internal val serverVersion: String?,
    internal val webRoot: String?,
    internal val language: String?,
    serverCapabilities: List<String>?,
    internal val apiVersion: Int?,
    internal val admin: Boolean?,
    internal val streaming: Boolean?,
    internal val dvr: Boolean?,
    internal val failedDvr: Boolean?,
    internal val anonymous: Boolean?,
    internal val limitAll: Int?,
    internal val limitDvr: Int?,
    internal val limitStreaming: Int?,
    internal val uiLevel: Int?,
    internal val uiLanguage: String?,
) {
    internal val serverCapabilities: List<String>? = serverCapabilities?.toList()

    override fun toString(): String = "GatewayServerFacts(<redacted>)"
}

internal sealed interface GatewayConnectResult {
    public class Connected(
        internal val connection: GatewayConnection,
    ) : GatewayConnectResult {
        override fun toString(): String = "GatewayConnectResult.Connected(<redacted>)"
    }

    public class Failed(
        internal val failure: GatewayConnectionFailure,
    ) : GatewayConnectResult {
        override fun toString(): String = "GatewayConnectResult.Failed"
    }
}

internal enum class GatewayConnectionFailure {
    AUTHENTICATION_REJECTED,
    PERMISSION_DENIED,
    SERVER_UNREACHABLE,
    NETWORK_UNAVAILABLE,
    INCOMPATIBLE_SERVER,
    NO_CHANNELS,
    TRANSPORT_UNAVAILABLE,
}

internal class GatewayConnectionFailureEvent(
    internal val failure: GatewayConnectionFailure,
    internal val generation: GatewayGeneration?,
) {
    override fun toString(): String = "GatewayConnectionFailureEvent(<redacted>)"
}

internal sealed interface GatewayResult<out T> {
    public class Ok<out T>(
        internal val value: T,
    ) : GatewayResult<T> {
        override fun toString(): String = "GatewayResult.Ok(<redacted>)"
    }

    public data object ServerRejected : GatewayResult<Nothing>
    public data object AccessDenied : GatewayResult<Nothing>
    public data object ConnectionLimit : GatewayResult<Nothing>
    public data object Timeout : GatewayResult<Nothing>
    public data object TransportUnavailable : GatewayResult<Nothing>
    public data object NotSupported : GatewayResult<Nothing>
}
