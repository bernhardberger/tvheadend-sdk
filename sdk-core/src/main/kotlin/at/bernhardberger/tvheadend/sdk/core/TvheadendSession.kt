package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerAuthentication as GatewayAuthentication
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerConfiguration
import at.bernhardberger.tvheadend.sdk.core.gateway.htsp.HtspProtocolGateway
import at.bernhardberger.tvheadend.sdk.core.session.ConnectionOwner
import at.bernhardberger.tvheadend.sdk.core.session.DvrMutationCoordinator
import at.bernhardberger.tvheadend.sdk.core.session.DvrProgressCoordinator
import at.bernhardberger.tvheadend.sdk.core.session.ExponentialReconnectBackoff
import at.bernhardberger.tvheadend.sdk.core.session.PhaseOneSessionMetadata
import at.bernhardberger.tvheadend.sdk.core.session.PlaybackSessionChildren
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileOpener
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpener
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlin.jvm.JvmSynthetic
import kotlin.random.Random
import kotlin.time.Clock

/** Owns one TVHeadend connection lifecycle. */
public interface TvheadendSession {
    /** Current durable connection and synchronization state. */
    public val state: StateFlow<SessionState>

    /** Channel and channel-tag metadata for the selected server profile. */
    public val channelRepository: ChannelRepository

    /** Programme-guide metadata and retained coverage for the selected server profile. */
    public val epgRepository: EpgRepository

    /** Recording entries, rules, configurations, and disk space for the selected server profile. */
    public val dvrRepository: DvrRepository

    /** Generation-bound subscription entry point used by SDK playback adapters. */
    @SubscriptionInfrastructureApi
    public val subscriptions: SubscriptionOpener

    /** Generation-bound recording file entry point used by SDK recording playback adapters. */
    @SubscriptionInfrastructureApi
    public val recordings: RecordingFileOpener

    /**
     * Selects [profile] and starts connection work.
     *
     * Completion reports command admission, not connection readiness. Observe [state] for the
     * durable outcome.
     */
    public suspend fun connect(profile: ServerProfile): SessionCommandResult

    /** Immediately retries the selected profile when its failure policy permits. */
    public suspend fun retry(): SessionCommandResult

    /** Completes reusable connection teardown and leaves this session available for reconnect. */
    public suspend fun disconnect()

    /** Completes terminal, ordered, and idempotent lifecycle shutdown. */
    public suspend fun shutdown()
}

/**
 * Returns the process-wide TVHeadend session owner.
 *
 * Repeated calls return the same instance until its terminal [TvheadendSession.shutdown] completes.
 * Shutdown affects every holder of that shared instance; a later call creates a fresh owner.
 */
public fun createTvheadendSession(): TvheadendSession = SessionRegistry.acquire()

private object SessionRegistry {
    private var active: ConnectionOwner? = null

    internal fun acquire(): TvheadendSession = synchronized(this) {
        active ?: createOwner().also { active = it }
    }

    private fun createOwner(): ConnectionOwner {
        val gateway = HtspProtocolGateway(Dispatchers.IO)
        lateinit var owner: ConnectionOwner
        lateinit var metadata: PhaseOneSessionMetadata
        val onDvrAccessProof: suspend (GatewayGeneration, Boolean) -> Unit = { generation, allowed ->
            owner.applyDvrAccessProof(generation, allowed)
        }
        val dvrMutations = DvrMutationCoordinator(
            gateway = gateway,
            isSessionReady = { generation -> owner.isDvrMutationReady(generation) },
            onDvrAccessProof = onDvrAccessProof,
        )
        val dvrProgress = DvrProgressCoordinator(
            gateway = gateway,
            isSessionReady = { generation -> owner.isDvrMutationReady(generation) },
            onDvrAccessProof = onDvrAccessProof,
        )
        metadata = PhaseOneSessionMetadata(
            mutationCommands = dvrMutations,
            progressCommands = dvrProgress,
            cutpointCommands = dvrProgress,
            onDvrMetadataAccepted = dvrMutations::acceptMetadata,
        )
        owner = ConnectionOwner(
            gateway = gateway,
            metadata = metadata,
            children = PlaybackSessionChildren(
                gateway = gateway,
                metadata = metadata,
                dispatcher = Dispatchers.Default,
                clock = Clock.System,
            ),
            dvrMutations = dvrMutations,
            dvrProgress = dvrProgress,
            defaultDispatcher = Dispatchers.Default,
            backoff = ExponentialReconnectBackoff(
                nextJitter = { Random.Default.nextDouble() },
            ),
            onShutdown = {
                synchronized(this) {
                    if (active === owner) {
                        active = null
                    }
                }
            },
        )
        return owner
    }
}

/** A normalized server profile selected for a session. */
public class ServerProfile(
    host: String,
    port: Int = 9_982,
    authentication: ServerAuthentication = ServerAuthentication.Anonymous,
) {
    internal val host: String = host.trim()
    internal val port: Int = port
    internal val authentication: ServerAuthentication = authentication

    init {
        require(this.host.isNotEmpty()) { "Server host must not be blank" }
        require(port in 1..65_535) { "Server port must be valid" }
    }

    internal fun toGatewayConfiguration(): ServerConfiguration = ServerConfiguration(
        host = host,
        port = port,
        authentication = when (val authentication = authentication) {
            ServerAuthentication.Anonymous -> GatewayAuthentication.Anonymous
            is ServerAuthentication.Password -> GatewayAuthentication.Password(
                username = authentication.username,
                password = authentication.password,
            )
        },
    )

    internal fun hasSameConfigurationAs(other: ServerProfile): Boolean =
        host == other.host &&
            port == other.port &&
            authentication.hasSameCredentialsAs(other.authentication)

    override fun toString(): String = "ServerProfile(<redacted>)"
}

/** Authentication selected for a server profile. */
public sealed interface ServerAuthentication {
    /** Connect without credentials. */
    public data object Anonymous : ServerAuthentication

    /** Password authentication with a normalized username and an exact password. */
    public class Password(
        username: String,
        password: String,
    ) : ServerAuthentication {
        @get:JvmSynthetic
        internal val username: String = username.trim()

        @get:JvmSynthetic
        internal val password: String = password

        init {
            require(this.username.isNotEmpty()) { "Username must not be blank" }
            require(this.password.isNotBlank()) { "Password must not be blank" }
        }

        override fun toString(): String = "ServerAuthentication.Password(<redacted>)"
    }
}

private fun ServerAuthentication.hasSameCredentialsAs(other: ServerAuthentication): Boolean =
    when (this) {
        ServerAuthentication.Anonymous -> other === ServerAuthentication.Anonymous
        is ServerAuthentication.Password ->
            other is ServerAuthentication.Password &&
                username == other.username &&
                password == other.password
    }

/** Result of admitting a session lifecycle command. */
public enum class SessionCommandResult {
    /** New lifecycle work was started. */
    STARTED,

    /** The requested lifecycle is already active. */
    NO_CHANGE,

    /** No server profile is selected. */
    NO_ACTIVE_PROFILE,

    /** The selected failure requires a configuration change. */
    RETRY_NOT_ALLOWED,

    /** The session has completed terminal shutdown. */
    SHUT_DOWN,
}

/** Durable state of a TVHeadend session. */
public sealed interface SessionState {
    /** No connection lifecycle is active. */
    public data object Disconnected : SessionState

    /** A transport connection is being established. */
    public data object Connecting : SessionState

    /** Initial metadata is being synchronized. */
    public data object Synchronizing : SessionState

    /** The selected generation is synchronized and available. */
    public data class Ready(
        public val capabilities: ServerCapabilities,
    ) : SessionState

    /** The selected profile is unavailable under the stated retry policy. */
    public data class Unavailable(
        public val reason: SessionFailure,
    ) : SessionState
}

/** Safe, SDK-authored reason that a session is unavailable. */
public sealed interface SessionFailure {
    /** Authentication was rejected; retry requires a profile configuration change. */
    public data object AuthenticationRejected : SessionFailure

    /** The authenticated user lacks permission; retry requires a profile configuration change. */
    public data object PermissionDenied : SessionFailure

    /** The server endpoint could not be reached and is retried with backoff. */
    public data object ServerUnreachable : SessionFailure

    /** The client network is unavailable and is retried with backoff. */
    public data object NetworkUnavailable : SessionFailure

    /** The server protocol is incompatible; retry requires a profile configuration change. */
    public data object IncompatibleServer : SessionFailure

    /** The synchronized server contains no channels and permits only explicit retry. */
    public data object NoChannels : SessionFailure

    /** The active transport is unavailable and is retried with backoff. */
    public data object TransportUnavailable : SessionFailure

    /** Initial synchronization failed with a typed operation result and its documented policy. */
    public data class SynchronizationFailed(
        public val failure: SessionOperationFailure,
    ) : SessionFailure

    /** An unexpected internal operation failed without exposing its cause and permits explicit retry. */
    public data object UnexpectedFailure : SessionFailure
}

/**
 * Safe failure returned by initial synchronization.
 *
 * Timeouts, transport unavailability, and connection limits use automatic backoff. Server rejection
 * permits explicit retry. Access denial and unsupported operations require a profile change.
 */
public enum class SessionOperationFailure {
    SERVER_REJECTED,
    ACCESS_DENIED,
    CONNECTION_LIMIT,
    TIMEOUT,
    TRANSPORT_UNAVAILABLE,
    NOT_SUPPORTED,
}

/** Capabilities and server observations proven for the current synchronized generation. */
@ConsistentCopyVisibility
public data class ServerCapabilities private constructor(
    public val streaming: CapabilityAccess,
    public val dvrWrite: CapabilityAccess,
    public val protocolDvr: CapabilityAccess,
    public val failedDvr: CapabilityAccess,
    public val admin: CapabilityAccess,
    public val anonymous: CapabilityAccess,
    public val apiVersion: Int?,
    public val allLimit: Int?,
    public val dvrLimit: Int?,
    public val streamingLimit: Int?,
    public val uiLevel: Int?,
    public val features: List<String>?,
    public val serverName: String?,
    public val serverVersion: String?,
    public val webRoot: String?,
    public val language: String?,
    public val uiLanguage: String?,
) {
    override fun toString(): String = "ServerCapabilities(<redacted>)"

    public companion object {
        /** Creates capabilities while defensively copying feature tokens. */
        public fun create(
            streaming: CapabilityAccess,
            dvrWrite: CapabilityAccess,
            protocolDvr: CapabilityAccess = CapabilityAccess.UNKNOWN,
            failedDvr: CapabilityAccess = CapabilityAccess.UNKNOWN,
            admin: CapabilityAccess = CapabilityAccess.UNKNOWN,
            anonymous: CapabilityAccess = CapabilityAccess.UNKNOWN,
            apiVersion: Int? = null,
            allLimit: Int? = null,
            dvrLimit: Int? = null,
            streamingLimit: Int? = null,
            uiLevel: Int? = null,
            features: List<String>? = null,
            serverName: String? = null,
            serverVersion: String? = null,
            webRoot: String? = null,
            language: String? = null,
            uiLanguage: String? = null,
        ): ServerCapabilities = ServerCapabilities(
            streaming = streaming,
            dvrWrite = dvrWrite,
            protocolDvr = protocolDvr,
            failedDvr = failedDvr,
            admin = admin,
            anonymous = anonymous,
            apiVersion = apiVersion,
            allLimit = allLimit,
            dvrLimit = dvrLimit,
            streamingLimit = streamingLimit,
            uiLevel = uiLevel,
            features = features?.let { Collections.unmodifiableList(ArrayList(it)) },
            serverName = serverName,
            serverVersion = serverVersion,
            webRoot = webRoot,
            language = language,
            uiLanguage = uiLanguage,
        )
    }
}

/** Positive, negative, or absent evidence for a server capability. */
public enum class CapabilityAccess {
    UNKNOWN,
    ALLOWED,
    DENIED,
}
