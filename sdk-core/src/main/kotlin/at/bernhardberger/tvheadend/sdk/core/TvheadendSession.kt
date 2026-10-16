package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.core.cache.CacheNamespace
import at.bernhardberger.tvheadend.sdk.core.cache.FileMetadataCacheStore
import at.bernhardberger.tvheadend.sdk.core.cache.MetadataCacheRuntime
import at.bernhardberger.tvheadend.sdk.core.cache.cacheNamespace
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.ProtocolGateway
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerAuthentication as GatewayAuthentication
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerConfiguration
import at.bernhardberger.tvheadend.sdk.core.gateway.htsp.HtspProtocolGateway
import at.bernhardberger.tvheadend.sdk.core.session.ConnectionOwner
import at.bernhardberger.tvheadend.sdk.core.session.CurrentDvrEntryLookup
import at.bernhardberger.tvheadend.sdk.core.session.DvrMutationCoordinator
import at.bernhardberger.tvheadend.sdk.core.session.DvrProgressCoordinator
import at.bernhardberger.tvheadend.sdk.core.session.EpgSearchCommands
import at.bernhardberger.tvheadend.sdk.core.session.EpgWorkerSettings
import at.bernhardberger.tvheadend.sdk.core.session.ExponentialReconnectBackoff
import at.bernhardberger.tvheadend.sdk.core.session.PhaseOneSessionMetadata
import at.bernhardberger.tvheadend.sdk.core.session.PlaybackSessionChildren
import at.bernhardberger.tvheadend.sdk.core.session.SessionChildren
import at.bernhardberger.tvheadend.sdk.core.session.SessionMetadata
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlin.jvm.JvmSynthetic
import kotlin.random.Random
import kotlin.time.Clock

/** Owns one Tvheadend connection lifecycle. */
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
     * Persistent cache owned by this session.
     *
     * Without a [MetadataCachePolicy] nothing is persisted, statistics stay empty, and
     * [SessionCache.clear] completes without effect.
     */
    public val cache: SessionCache

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
        return StreamProfilesResult.Failed(TvheadendFailure.NOT_READY)
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
 * Returns the process-wide Tvheadend session owner.
 *
 * Repeated calls return the same instance until its terminal [TvheadendSession.shutdown] completes.
 * A newly created owner uses the default 24-hour EPG coverage policy. Shutdown affects every holder
 * of that shared instance; a later call creates a fresh owner.
 * An existing owner's policies are preserved regardless of their configuration.
 */
public fun createTvheadendSession(): TvheadendSession =
    SessionRegistry.acquire()

/**
 * Returns the process-wide Tvheadend session owner with [epgCoveragePolicy].
 *
 * Requests [epgCoveragePolicy] with caching disabled. An existing owner is returned only when its
 * configuration equals this request. Comparison and acquisition are atomic.
 *
 * @throws IllegalStateException if an existing owner has different policies. The message contains
 * no configuration values. After terminal shutdown completes, different policies may be supplied.
 */
public fun createTvheadendSession(epgCoveragePolicy: EpgCoveragePolicy): TvheadendSession =
    SessionRegistry.acquire(epgCoveragePolicy, cachePolicy = null)

/**
 * Returns the process-wide Tvheadend session owner with [epgCoveragePolicy] and [cachePolicy].
 *
 * With a cache policy the owner restores the last channel catalog and programme guide of a
 * profile as stale metadata before connecting, and persists published metadata under
 * [MetadataCachePolicy.root]. An existing owner is returned only when both policies compare equal,
 * using [MetadataCachePolicy]'s normalized absolute root-path rule. Comparison and acquisition are atomic.
 *
 * @throws IllegalStateException if an existing owner has different policies. The message contains
 * no configuration values. After terminal shutdown completes, different policies may be supplied.
 */
public fun createTvheadendSession(
    epgCoveragePolicy: EpgCoveragePolicy,
    cachePolicy: MetadataCachePolicy,
): TvheadendSession = SessionRegistry.acquire(epgCoveragePolicy, cachePolicy)

internal object SessionRegistry {
    private class ActiveOwner(
        val owner: ConnectionOwner,
        val epgCoveragePolicy: EpgCoveragePolicy,
        val cachePolicy: MetadataCachePolicy?,
    )

    private var active: ActiveOwner? = null

    internal fun acquire(
        epgCoveragePolicy: EpgCoveragePolicy? = null,
        cachePolicy: MetadataCachePolicy? = null,
        ownerFactory: (EpgCoveragePolicy, MetadataCachePolicy?) -> ConnectionOwner = { policy, cache ->
            createOwner(policy, cacheRuntime = cache?.let(::createCacheRuntime))
        },
    ): TvheadendSession = synchronized(this) {
        val existing = active
        if (existing != null) {
            check(
                epgCoveragePolicy == null ||
                    (existing.epgCoveragePolicy == epgCoveragePolicy && existing.cachePolicy == cachePolicy)
            ) { "The active Tvheadend session has different policies" }
            existing.owner
        } else {
            val policy = epgCoveragePolicy ?: EpgCoveragePolicy.create()
            ownerFactory(policy, cachePolicy)
                .also { active = ActiveOwner(it, policy, cachePolicy) }
        }
    }

    private fun createCacheRuntime(policy: MetadataCachePolicy): MetadataCacheRuntime =
        MetadataCacheRuntime(
            store = FileMetadataCacheStore(policy.root),
            policy = policy,
            ioDispatcher = Dispatchers.IO,
            clock = Clock.System,
        )

    internal fun createOwner(
        epgCoveragePolicy: EpgCoveragePolicy,
        cacheRuntime: MetadataCacheRuntime? = null,
        clock: Clock = Clock.System,
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
                clock = clock,
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
            currentEntry = { generation, id ->
                (metadata.currentDvrEntry(generation, id) as? CurrentDvrEntryLookup.Current)?.entry
            },
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
            clock = clock,
            epgWorkerSettings = epgSettings,
            epgCoveragePolicy = epgCoveragePolicy,
            estimatedServerTime = gateway::estimatedServerTime,
            mutationCommands = dvrMutations,
            dvrIntents = dvrMutations.intents,
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
            cacheRuntime = cacheRuntime,
            backoff = ExponentialReconnectBackoff(
                nextJitter = { Random.Default.nextDouble() },
            ),
            onShutdown = {
                synchronized(this) {
                    if (active?.owner === owner) {
                        active = null
                    }
                }
            },
        )
        return owner
    }
}

/** Default Tvheadend HTSP port used when a server profile does not specify one. */
public const val DEFAULT_HTSP_PORT: Int = 9_982

/** A normalized server profile selected for a session. */
public class ServerProfile(
    host: String,
    port: Int = DEFAULT_HTSP_PORT,
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

    /** Cache namespace derived from host, port, and username; the password never contributes. */
    internal fun cacheNamespace(): CacheNamespace = cacheNamespace(
        host = host,
        port = port,
        username = (authentication as? ServerAuthentication.Password)?.username.orEmpty(),
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

/** Durable state of a Tvheadend session. */
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

/**
 * Safe, value-equal reason that a session is unavailable.
 *
 * Compare [kind] with known values and retain a fallback for future kinds. Equality includes
 * [operationFailure], preserving conflation of equal [SessionState.Unavailable] observations.
 */
public class SessionFailure private constructor(
    /** The SDK-authored reason. */
    public val kind: SessionFailureKind,
    /** The operation failure, present only for synchronization failures. */
    public val operationFailure: TvheadendFailure? = null,
) {
    init {
        require((kind == SessionFailureKind.SYNCHRONIZATION_FAILED) == (operationFailure != null)) {
            "Only synchronization failures require an operation failure"
        }
    }

    /** Stable recovery guidance derived from the kind and operation failure. */
    public val recoveryDisposition: SessionRecoveryDisposition
        get() = when (kind) {
            SessionFailureKind.AUTHENTICATION_REJECTED,
            SessionFailureKind.INCOMPATIBLE_SERVER -> SessionRecoveryDisposition.PROFILE_CHANGE_REQUIRED
            SessionFailureKind.SERVER_UNREACHABLE,
            SessionFailureKind.NETWORK_UNAVAILABLE,
            SessionFailureKind.METADATA_OVERFLOW,
            SessionFailureKind.TRANSPORT_UNAVAILABLE -> SessionRecoveryDisposition.AUTOMATIC_BACKOFF
            SessionFailureKind.SYNCHRONIZATION_FAILED -> requireNotNull(operationFailure).recoveryDisposition
            else -> SessionRecoveryDisposition.EXPLICIT_RETRY
        }

    override fun equals(other: Any?): Boolean =
        other is SessionFailure && kind == other.kind && operationFailure == other.operationFailure

    override fun hashCode(): Int = 31 * kind.hashCode() + (operationFailure?.hashCode() ?: 0)

    override fun toString(): String = "SessionFailure(kind=$kind, operationFailure=$operationFailure)"

    /** Known session failures and synchronization failure factory. */
    public companion object {
        /** Authentication was rejected; recovery requires a changed profile. */
        @JvmField
        public val AUTHENTICATION_REJECTED: SessionFailure = SessionFailure(SessionFailureKind.AUTHENTICATION_REJECTED)
        /** The server could not be reached; the SDK retries with backoff. */
        @JvmField
        public val SERVER_UNREACHABLE: SessionFailure = SessionFailure(SessionFailureKind.SERVER_UNREACHABLE)
        /** The network is unavailable; the SDK retries with backoff. */
        @JvmField
        public val NETWORK_UNAVAILABLE: SessionFailure = SessionFailure(SessionFailureKind.NETWORK_UNAVAILABLE)
        /** The protocol is incompatible; recovery requires a changed profile. */
        @JvmField
        public val INCOMPATIBLE_SERVER: SessionFailure = SessionFailure(SessionFailureKind.INCOMPATIBLE_SERVER)
        /** The server needs an upgrade before an explicit retry. */
        @JvmField
        public val SERVER_UPGRADE_REQUIRED: SessionFailure = SessionFailure(SessionFailureKind.SERVER_UPGRADE_REQUIRED)
        /** Metadata overflow requires a full resync with backoff. */
        @JvmField
        public val METADATA_OVERFLOW: SessionFailure = SessionFailure(SessionFailureKind.METADATA_OVERFLOW)
        /** The active transport is unavailable; the SDK retries with backoff. */
        @JvmField
        public val TRANSPORT_UNAVAILABLE: SessionFailure = SessionFailure(SessionFailureKind.TRANSPORT_UNAVAILABLE)
        /** An unexpected internal failure permits explicit retry without exposing its cause. */
        @JvmField
        public val UNEXPECTED_FAILURE: SessionFailure = SessionFailure(SessionFailureKind.UNEXPECTED_FAILURE)

        /** Initial synchronization failed with the given safe operation failure. */
        @JvmStatic
        public fun synchronizationFailed(operationFailure: TvheadendFailure): SessionFailure =
            SessionFailure(SessionFailureKind.SYNCHRONIZATION_FAILED, operationFailure)
    }
}

/** Open session failure classification. Consumers must retain a fallback for future values. */
public class SessionFailureKind private constructor(private val label: String) {
    override fun toString(): String = label

    /** Known session failure kinds. */
    public companion object {
        /** Authentication was rejected. */
        @JvmField
        public val AUTHENTICATION_REJECTED: SessionFailureKind = SessionFailureKind("AUTHENTICATION_REJECTED")
        /** The server could not be reached. */
        @JvmField
        public val SERVER_UNREACHABLE: SessionFailureKind = SessionFailureKind("SERVER_UNREACHABLE")
        /** The network is unavailable. */
        @JvmField
        public val NETWORK_UNAVAILABLE: SessionFailureKind = SessionFailureKind("NETWORK_UNAVAILABLE")
        /** The server protocol is incompatible. */
        @JvmField
        public val INCOMPATIBLE_SERVER: SessionFailureKind = SessionFailureKind("INCOMPATIBLE_SERVER")
        /** The server is below the supported protocol floor. */
        @JvmField
        public val SERVER_UPGRADE_REQUIRED: SessionFailureKind = SessionFailureKind("SERVER_UPGRADE_REQUIRED")
        /** Metadata delivery overflowed. */
        @JvmField
        public val METADATA_OVERFLOW: SessionFailureKind = SessionFailureKind("METADATA_OVERFLOW")
        /** The active transport is unavailable. */
        @JvmField
        public val TRANSPORT_UNAVAILABLE: SessionFailureKind = SessionFailureKind("TRANSPORT_UNAVAILABLE")
        /** Initial synchronization failed. */
        @JvmField
        public val SYNCHRONIZATION_FAILED: SessionFailureKind = SessionFailureKind("SYNCHRONIZATION_FAILED")
        /** An unexpected internal operation failed. */
        @JvmField
        public val UNEXPECTED_FAILURE: SessionFailureKind = SessionFailureKind("UNEXPECTED_FAILURE")
    }
}

/**
 * Stable SDK-authored recovery guidance for an unavailable session.
 *
 * Guidance describes who may initiate another attempt, not whether that attempt will succeed.
 * Applications retain ownership of copy, action labels, presentation of retry timing and counts,
 * connectivity, and navigation. Consumers must retain a fallback for future values.
 */
public class SessionRecoveryDisposition private constructor(private val label: String) {
    override fun toString(): String = label

    /** Known recovery dispositions. */
    public companion object {
        /** The SDK continues connection attempts using its internal backoff policy. */
        @JvmField
        public val AUTOMATIC_BACKOFF: SessionRecoveryDisposition = SessionRecoveryDisposition("AUTOMATIC_BACKOFF")

        /** The SDK has stopped retrying, but a user-requested [TvheadendSession.retry] is permitted. */
        @JvmField
        public val EXPLICIT_RETRY: SessionRecoveryDisposition = SessionRecoveryDisposition("EXPLICIT_RETRY")

        /** The current profile is not retried; recovery requires connecting a changed profile. */
        @JvmField
        public val PROFILE_CHANGE_REQUIRED: SessionRecoveryDisposition = SessionRecoveryDisposition("PROFILE_CHANGE_REQUIRED")

        /** Terminal guidance: the SDK offers no retry path for the failure. */
        @JvmField
        public val NO_RETRY: SessionRecoveryDisposition = SessionRecoveryDisposition("NO_RETRY")
    }
}

/**
 * Recovery guidance for an initial synchronization operation failure.
 *
 * Timeouts, transport unavailability, and connection limits use automatic backoff. Server rejection
 * and malformed replies permit explicit retry. Access denial and unsupported operations require
 * a profile change. All other and future failures conservatively permit only explicit retry.
 */
public val TvheadendFailure.recoveryDisposition: SessionRecoveryDisposition
    get() = when (this) {
        TvheadendFailure.ACCESS_DENIED,
        TvheadendFailure.NOT_SUPPORTED -> SessionRecoveryDisposition.PROFILE_CHANGE_REQUIRED
        TvheadendFailure.CONNECTION_LIMIT,
        TvheadendFailure.TIMEOUT,
        TvheadendFailure.TRANSPORT_UNAVAILABLE -> SessionRecoveryDisposition.AUTOMATIC_BACKOFF
        else -> SessionRecoveryDisposition.EXPLICIT_RETRY
    }

/**
 * Capabilities and server observations proven for the current synchronized generation.
 * Numeric server facts retain the full unsigned 32-bit range in [Long], or null when absent.
 */
@ConsistentCopyVisibility
public data class ServerCapabilities private constructor(
    public val streaming: CapabilityAccess,
    public val dvrWrite: CapabilityAccess,
    public val protocolDvr: CapabilityAccess,
    /**
     * Access to failed recordings. When [CapabilityAccess.DENIED], Tvheadend stops sending
     * `dvrEntryUpdate` once an entry counts as failed, without sending `dvrEntryDelete`.
     * The SDK retains its last observed state (possibly scheduled or recording) until reconnect
     * removes the now-hidden entry. Mutations return [TvheadendFailure.SERVER_REJECTED] with an
     * access message; the same rejection can mean owner mismatch, so the SDK does not infer failure.
     * Consumers may label a still-scheduled/recording entry as possibly failed when this right is
     * denied and the current time exceeds its stop plus post padding and an application grace period.
     */
    public val failedDvr: CapabilityAccess,
    public val admin: CapabilityAccess,
    public val anonymous: CapabilityAccess,
    public val apiVersion: Long?,
    public val allLimit: Long?,
    public val dvrLimit: Long?,
    public val streamingLimit: Long?,
    public val uiLevel: Long?,
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
            apiVersion: Long? = null,
            allLimit: Long? = null,
            dvrLimit: Long? = null,
            streamingLimit: Long? = null,
            uiLevel: Long? = null,
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
