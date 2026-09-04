package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.ProtocolGateway
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerAuthentication as GatewayAuthentication
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerConfiguration
import at.bernhardberger.tvheadend.sdk.core.gateway.htsp.HtspProtocolGateway
import at.bernhardberger.tvheadend.sdk.core.session.ConnectionOwner
import at.bernhardberger.tvheadend.sdk.core.session.DvrMutationCoordinator
import at.bernhardberger.tvheadend.sdk.core.session.DvrProgressCoordinator
import at.bernhardberger.tvheadend.sdk.core.session.EpgSearchCommands
import at.bernhardberger.tvheadend.sdk.core.session.EpgWorkerSettings
import at.bernhardberger.tvheadend.sdk.core.session.ExponentialReconnectBackoff
import at.bernhardberger.tvheadend.sdk.core.session.PhaseOneSessionMetadata
import at.bernhardberger.tvheadend.sdk.core.session.PlaybackSessionChildren
import at.bernhardberger.tvheadend.sdk.core.session.SessionChildren
import at.bernhardberger.tvheadend.sdk.core.session.SessionMetadata
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlin.jvm.JvmSynthetic
import kotlin.random.Random
import kotlin.time.Clock

/** Owns one TVHeadend connection lifecycle. */
public interface TvheadendSession {
    /** Atomic lifecycle, metadata, storage, and capability state for the selected server profile. */
    public val observation: StateFlow<SessionObservation>

    /** Programme-guide coverage commands for the selected server profile. */
    public val epgRepository: EpgRepository

    /** Recording mutation commands for the selected server profile. */
    public val dvrRepository: DvrRepository

    /** Generation-bound authenticated artwork loader used by platform image integrations. */
    public val artwork: ArtworkLoader

    /**
     * Reports whether [currentSession] is the current proof at this instant without leasing it.
     *
     * A true result can become stale immediately after this method returns.
     */
    public fun isCurrent(currentSession: CurrentSessionObservation): Boolean =
        observation.value.currentSession === currentSession

    /**
     * Waits for a current proof distinct by identity from [replaced].
     *
     * Null accepts the current proof, if one exists. The returned proof can be retired immediately
     * after return, and caller cancellation always propagates.
     */
    public suspend fun awaitCurrentSession(
        replaced: CurrentSessionObservation? = null,
    ): CurrentSessionObservation {
        currentCoroutineContext().ensureActive()
        val currentSession = observation.first { candidate ->
            candidate.currentSession != null && candidate.currentSession !== replaced
        }.currentSession!!
        currentCoroutineContext().ensureActive()
        return currentSession
    }

    /** Discovers immutable stream profiles for the originating current session observation. */
    public suspend fun getStreamProfiles(
        currentSession: CurrentSessionObservation,
    ): StreamProfilesResult {
        currentCoroutineContext().ensureActive()
        return StreamProfilesResult.NotReady
    }

    /** Binds one live target to the exact observation that selected it. */
    public fun bindLivePlayback(
        currentSession: CurrentSessionObservation,
        channelId: ChannelId,
    ): PlaybackBindingResult<PlaybackBinding.Live>

    /** Binds one recording target to the exact observation that selected it. */
    public fun bindRecordingPlayback(
        currentSession: CurrentSessionObservation,
        recordingId: DvrEntryId,
    ): PlaybackBindingResult<PlaybackBinding.Recording>

    /**
     * Selects [profile] and starts connection work.
     *
     * Completion reports command admission, not connection readiness. Observe [observation] for
     * the durable outcome.
     */
    public suspend fun connect(profile: ServerProfile): SessionCommandResult

    /** Immediately retries the selected profile when its failure policy permits. */
    public suspend fun retry(): SessionCommandResult

    /**
     * Completes reusable connection teardown and leaves this session available for reconnect.
     *
     * Metadata snapshots remain stale for a same-profile reconnect. A different profile or
     * terminal [shutdown] discards them before new synchronization begins.
     */
    public suspend fun disconnect()

    /** Completes terminal, ordered, and idempotent lifecycle shutdown. */
    public suspend fun shutdown()
}

/**
 * Returns the process-wide TVHeadend session owner.
 *
 * Repeated calls return the same instance until its terminal [TvheadendSession.shutdown] completes.
 * A newly created owner uses the default 24-hour EPG coverage policy. Shutdown affects every holder
 * of that shared instance; a later call creates a fresh owner.
 */
public fun createTvheadendSession(): TvheadendSession =
    SessionRegistry.acquire(EpgCoveragePolicy.create())

/**
 * Returns the process-wide TVHeadend session owner with [epgCoveragePolicy].
 *
 * The policy applies when this call creates a fresh owner. If an owner is already active, this call
 * returns that instance without reconfiguring its connection generation.
 */
public fun createTvheadendSession(epgCoveragePolicy: EpgCoveragePolicy): TvheadendSession =
    SessionRegistry.acquire(epgCoveragePolicy)

internal object SessionRegistry {
    private var active: ConnectionOwner? = null

    internal fun acquire(epgCoveragePolicy: EpgCoveragePolicy): TvheadendSession = synchronized(this) {
        active ?: createOwner(epgCoveragePolicy).also { active = it }
    }

    internal fun createOwner(
        epgCoveragePolicy: EpgCoveragePolicy,
        gatewayFactory: (EpgCoveragePolicy) -> ProtocolGateway = { policy ->
            HtspProtocolGateway(Dispatchers.IO, policy)
        },
        childrenFactory: (
            ProtocolGateway,
            SessionMetadata,
            EpgWorkerSettings,
        ) -> SessionChildren = { gateway, metadata, settings ->
            PlaybackSessionChildren(
                gateway = gateway,
                metadata = metadata,
                dispatcher = Dispatchers.Default,
                clock = Clock.System,
                epgSettings = settings,
            )
        },
    ): ConnectionOwner {
        val epgSettings = EpgWorkerSettings(coveragePolicy = epgCoveragePolicy)
        val gateway = gatewayFactory(epgCoveragePolicy)
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
            onProgressNotSupported = { generation ->
                owner.applyRecordingProgressNotSupported(generation)
            },
        )
        metadata = PhaseOneSessionMetadata(
            epgCoveragePolicy = epgCoveragePolicy,
            mutationCommands = dvrMutations,
            searchCommands = EpgSearchCommands { generation, request ->
                gateway.searchEpg(generation, request)
            },
            progressCommands = dvrProgress,
            cutpointCommands = dvrProgress,
            onDvrMetadataAccepted = dvrMutations::acceptMetadata,
        )
        owner = ConnectionOwner(
            gateway = gateway,
            metadata = metadata,
            children = childrenFactory(gateway, metadata, epgSettings),
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

    /** The selected failure policy does not permit explicit retry. */
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

    /**
     * Initial metadata is being synchronized.
     *
     * Retained metadata remains selectable, but new playback bindings, DVR mutations, and progress
     * observation remain unavailable until [Ready].
     */
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
    /** Stable recovery guidance for this failure. */
    public val recoveryDisposition: SessionRecoveryDisposition

    /** Authentication was rejected; retry requires a profile configuration change. */
    public data object AuthenticationRejected : SessionFailure {
        override val recoveryDisposition: SessionRecoveryDisposition =
            SessionRecoveryDisposition.PROFILE_CHANGE_REQUIRED
    }

    /** The authenticated user lacks permission; retry requires a profile configuration change. */
    public data object PermissionDenied : SessionFailure {
        override val recoveryDisposition: SessionRecoveryDisposition =
            SessionRecoveryDisposition.PROFILE_CHANGE_REQUIRED
    }

    /** The server endpoint could not be reached and is retried with backoff. */
    public data object ServerUnreachable : SessionFailure {
        override val recoveryDisposition: SessionRecoveryDisposition =
            SessionRecoveryDisposition.AUTOMATIC_BACKOFF
    }

    /** The client network is unavailable and is retried with backoff. */
    public data object NetworkUnavailable : SessionFailure {
        override val recoveryDisposition: SessionRecoveryDisposition =
            SessionRecoveryDisposition.AUTOMATIC_BACKOFF
    }

    /** The server protocol is incompatible; retry requires a profile configuration change. */
    public data object IncompatibleServer : SessionFailure {
        override val recoveryDisposition: SessionRecoveryDisposition =
            SessionRecoveryDisposition.PROFILE_CHANGE_REQUIRED
    }

    /** The synchronized server contains no channels and permits only explicit retry. */
    public data object NoChannels : SessionFailure {
        override val recoveryDisposition: SessionRecoveryDisposition =
            SessionRecoveryDisposition.EXPLICIT_RETRY
    }

    /** The active transport is unavailable and is retried with backoff. */
    public data object TransportUnavailable : SessionFailure {
        override val recoveryDisposition: SessionRecoveryDisposition =
            SessionRecoveryDisposition.AUTOMATIC_BACKOFF
    }

    /** Initial synchronization failed with a typed operation result and its documented policy. */
    public data class SynchronizationFailed(
        public val failure: SessionOperationFailure,
    ) : SessionFailure {
        override val recoveryDisposition: SessionRecoveryDisposition
            get() = failure.recoveryDisposition
    }

    /** An unexpected internal operation failed without exposing its cause and permits explicit retry. */
    public data object UnexpectedFailure : SessionFailure {
        override val recoveryDisposition: SessionRecoveryDisposition =
            SessionRecoveryDisposition.EXPLICIT_RETRY
    }
}

/**
 * Stable SDK-authored recovery guidance for an unavailable session.
 *
 * Guidance describes who may initiate another attempt, not whether that attempt will succeed.
 * Applications retain ownership of copy, action labels, presentation of retry timing and counts,
 * connectivity, and navigation. Consumers should retain a fallback for future enum values.
 */
public enum class SessionRecoveryDisposition {
    /** The SDK continues connection attempts using its internal backoff policy. */
    AUTOMATIC_BACKOFF,

    /** The SDK has stopped retrying, but a user-requested [TvheadendSession.retry] is permitted. */
    EXPLICIT_RETRY,

    /** The current profile is not retried; recovery requires connecting a changed profile. */
    PROFILE_CHANGE_REQUIRED,

    /** Terminal guidance: the SDK offers no retry path for the failure. */
    NO_RETRY,
}

/**
 * Safe failure returned by initial synchronization.
 *
 * Timeouts, transport unavailability, and connection limits use automatic backoff. Server rejection
 * permits explicit retry. Access denial and unsupported operations require a profile change.
 */
public enum class SessionOperationFailure(
    public val recoveryDisposition: SessionRecoveryDisposition,
) {
    SERVER_REJECTED(SessionRecoveryDisposition.EXPLICIT_RETRY),
    ACCESS_DENIED(SessionRecoveryDisposition.PROFILE_CHANGE_REQUIRED),
    CONNECTION_LIMIT(SessionRecoveryDisposition.AUTOMATIC_BACKOFF),
    TIMEOUT(SessionRecoveryDisposition.AUTOMATIC_BACKOFF),
    TRANSPORT_UNAVAILABLE(SessionRecoveryDisposition.AUTOMATIC_BACKOFF),
    NOT_SUPPORTED(SessionRecoveryDisposition.PROFILE_CHANGE_REQUIRED),
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

/** Current-generation support for safe recording close and separate progress/watch mutation. */
public enum class RecordingProgressCapability {
    /** No ready generation has established whether the complete semantic contract is available. */
    UNKNOWN,

    /** The ready generation supports the complete HTSP v27 recording-progress contract. */
    SUPPORTED,

    /** The ready generation cannot provide the complete recording-progress contract. */
    UNSUPPORTED,
}
