package at.bernhardberger.tvheadend.sdk.core.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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

    public fun subscription(id: SubscriptionId): Flow<SubscriptionEvent>

    public suspend fun subscribe(
        generation: GatewayGeneration,
        id: SubscriptionId,
        channelId: ChannelId,
    ): GatewayResult<SubscriptionConfirmation>

    public suspend fun unsubscribe(
        generation: GatewayGeneration,
        id: SubscriptionId,
    ): GatewayResult<Unit>
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

internal class SubscriptionConfirmation(
    internal val ninetyKhz: Boolean?,
    internal val normalizedTimestamps: Boolean?,
    internal val weight: Long?,
    internal val timeshiftPeriodSeconds: Long?,
) {
    override fun toString(): String = "SubscriptionConfirmation(<redacted>)"
}
