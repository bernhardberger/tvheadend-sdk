package at.bernhardberger.tvheadend.sdk.core

import java.util.UUID

/** Identifies a stored profile independently of edits to its connection fields. */
public class ServerProfileId private constructor(
    /** Canonical UUID suitable for application-owned per-profile state keys. */
    public val value: String,
) {
    override fun equals(other: Any?): Boolean = other is ServerProfileId && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    public companion object {
        /** Restores a canonical lowercase UUID, rejecting other representations. */
        @JvmStatic
        public fun from(value: String): ServerProfileId {
            val uuid = try {
                UUID.fromString(value)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid profile id")
            }
            require(uuid.toString() == value) { "Invalid profile id" }
            return ServerProfileId(value)
        }

        /** Generates an opaque identifier for profile-store implementations. */
        @JvmStatic
        public fun generate(): ServerProfileId = ServerProfileId(UUID.randomUUID().toString())
    }
}

/** Persists ordered server profiles with at most one active selection. */
public interface ServerProfileStore {
    /** Lists profiles in insertion order; implementations may decrypt when migrating older data. */
    public suspend fun profiles(): ServerProfileListResult

    /** Loads the active profile, or [ServerProfileReadResult.Missing] when none is selected. */
    public suspend fun loadActive(): ServerProfileReadResult

    /**
     * Loads [id], or [ServerProfileReadResult.Missing] when it does not exist.
     * Returns [ServerProfileReadResult.Unavailable] for unreadable storage, credentials marked
     * unavailable, or decryption failure (including transient faults).
     */
    public suspend fun load(id: ServerProfileId): ServerProfileReadResult

    /**
     * Adds a profile; success proves only local persistence, not server readiness.
     * @throws IllegalArgumentException if [host], [port], or [label] is invalid.
     */
    public suspend fun addAnonymous(
        host: String,
        port: Int = DEFAULT_HTSP_PORT,
        label: String? = null,
        activate: Boolean = false,
    ): ServerProfileWriteResult

    /**
     * Adds a password profile; success proves only local persistence, not authentication.
     * @throws IllegalArgumentException if the endpoint, credentials, or [label] is invalid.
     */
    public suspend fun addPassword(
        host: String,
        port: Int = DEFAULT_HTSP_PORT,
        username: String,
        password: String,
        label: String? = null,
        activate: Boolean = false,
    ): ServerProfileWriteResult

    /**
     * Replaces fields while keeping [id]; null [label] clears the label.
     * @throws IllegalArgumentException if [host], [port], or [label] is invalid.
     */
    public suspend fun updateAnonymous(
        id: ServerProfileId,
        host: String,
        port: Int,
        label: String?,
    ): ServerProfileWriteResult

    /**
     * Replaces fields and authentication while keeping [id]; null [label] clears it.
     * @throws IllegalArgumentException if the endpoint, credentials, or [label] is invalid.
     */
    public suspend fun updatePassword(
        id: ServerProfileId,
        host: String,
        port: Int,
        username: String,
        password: String,
        label: String?,
    ): ServerProfileWriteResult

    /** Selects [id] locally without changing an existing session connection. */
    public suspend fun setActive(id: ServerProfileId): ServerProfileWriteResult

    /** Removes [id]; removing the active profile leaves no active selection. */
    public suspend fun remove(id: ServerProfileId): ServerProfileWriteResult

    /** Deletes all profiles and selection, including unreadable persisted data. */
    public suspend fun clearAll()
}

/** Describes authentication without exposing credentials. */
public enum class ServerProfileAuthenticationMode {
    /** Connect without credentials. */
    ANONYMOUS,
    /** Connect with password authentication. */
    PASSWORD,
}

/** Lists non-secret profile fields, redacting endpoints in diagnostics. */
public class ServerProfileSummary private constructor(
    /** Stable profile identifier. */
    public val id: ServerProfileId,
    /** Optional normalized display label. */
    public val label: String?,
    /** Normalized server host. */
    public val host: String,
    /** HTSP port. */
    public val port: Int,
    /** Authentication mode, without credentials. */
    public val authenticationMode: ServerProfileAuthenticationMode,
    /**
     * Reports the stored credential-availability marker.
     * True does not guarantee decryption succeeds, for example after restoring without the key,
     * and does not prove server authentication.
     */
    public val credentialsAvailable: Boolean,
) {
    override fun toString(): String = "ServerProfileSummary(<redacted>)"

    public companion object {
        /** Constructs validated fields for store implementations and consumer fakes. */
        @JvmStatic
        public fun create(
            id: ServerProfileId,
            label: String?,
            host: String,
            port: Int,
            authenticationMode: ServerProfileAuthenticationMode,
            credentialsAvailable: Boolean = true,
        ): ServerProfileSummary {
            require(credentialsAvailable || authenticationMode == ServerProfileAuthenticationMode.PASSWORD) {
                "Invalid credential availability"
            }
            val profile = ServerProfile(host, port)
            val normalizedLabel = label?.trim()?.takeIf { it.isNotEmpty() }
            require(label == null || label.none { it.isISOControl() }) { "Invalid profile label" }
            require(normalizedLabel == null || normalizedLabel.length <= 64) { "Invalid profile label" }
            return ServerProfileSummary(id, normalizedLabel, profile.host, profile.port, authenticationMode, credentialsAvailable)
        }
    }
}

/** Reports the locally stored profile collection. */
public sealed interface ServerProfileListResult {
    /** Returns profiles in insertion order and their optional active selection. */
    public class Available(
        profiles: List<ServerProfileSummary>,
        /** Selected profile identifier, or null when none is selected. */
        public val activeId: ServerProfileId?,
    ) : ServerProfileListResult {
        /** Snapshot of profiles in insertion order. */
        public val profiles: List<ServerProfileSummary> = profiles.toList()
        override fun toString(): String = "ServerProfileListResult.Available(<redacted>)"
    }

    /** Reports unreadable data or an unsupported format. */
    public data object Unavailable : ServerProfileListResult
}

/** Reports local persistence without claiming server reachability or authentication. */
public sealed interface ServerProfileWriteResult {
    /** Confirms local persistence for [id]. */
    public data class Applied(
        /** Stable identifier affected by the mutation. */
        public val id: ServerProfileId,
    ) : ServerProfileWriteResult
    /** Reports that the requested identifier does not exist. */
    public data object NotFound : ServerProfileWriteResult
    /** Reports unreadable storage; [ServerProfileStore.clearAll] can discard it. */
    public data object Unavailable : ServerProfileWriteResult
}

/** Reports a connectable profile without exposing password fields. */
public sealed interface ServerProfileReadResult {
    /** Reports no active profile or no matching profile. */
    public data object Missing : ServerProfileReadResult

    /** Returns a connectable profile with non-secret identifying fields. */
    public class Available private constructor(
        /** Stable profile identifier. */
        public val id: ServerProfileId,
        /** Optional normalized display label. */
        public val label: String?,
        /** Opaque connection configuration. */
        public val profile: ServerProfile,
        /** Normalized server host. */
        public val host: String,
        /** HTSP port. */
        public val port: Int,
        /** Authentication mode, without credentials. */
        public val authenticationMode: ServerProfileAuthenticationMode,
    ) : ServerProfileReadResult {
        override fun toString(): String = "ServerProfileReadResult.Available(<redacted>)"

        internal companion object {
            internal fun create(
                id: ServerProfileId,
                label: String?,
                profile: ServerProfile,
                mode: ServerProfileAuthenticationMode,
            ): Available {
                val summary = ServerProfileSummary.create(id, label, profile.host, profile.port, mode)
                return Available(id, summary.label, profile, profile.host, profile.port, mode)
            }
        }
    }

    /**
     * Reports unreadable storage or unavailable credentials. Check [ServerProfileStore.profiles]:
     * an unavailable collection may reflect a transient storage or migration fault; retry later.
     * [ServerProfileStore.clearAll] explicitly discards all profiles and is destructive recovery
     * for persistent unreadability, such as a corrupted file. An available collection
     * with [ServerProfileSummary.credentialsAvailable] false can be repaired by updating credentials
     * or removing that profile.
     */
    public data object Unavailable : ServerProfileReadResult

    public companion object {
        /** Constructs a validated anonymous result for implementations and consumer fakes. */
        @JvmStatic
        public fun anonymous(
            id: ServerProfileId,
            host: String,
            port: Int = DEFAULT_HTSP_PORT,
            label: String? = null,
        ): Available = Available.create(
            id, label, ServerProfile(host, port), ServerProfileAuthenticationMode.ANONYMOUS,
        )

        /** Constructs a password result retaining credentials only in its opaque [ServerProfile]. */
        @JvmStatic
        public fun password(
            id: ServerProfileId,
            host: String,
            port: Int = DEFAULT_HTSP_PORT,
            username: String,
            password: String,
            label: String? = null,
        ): Available = Available.create(
            id, label, ServerProfile(host, port, ServerAuthentication.Password(username, password)),
            ServerProfileAuthenticationMode.PASSWORD,
        )
    }
}
