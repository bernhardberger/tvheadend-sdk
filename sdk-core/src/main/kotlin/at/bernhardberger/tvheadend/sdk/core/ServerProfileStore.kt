package at.bernhardberger.tvheadend.sdk.core

/**
 * Persists the single server profile selected by an application.
 *
 * A successful mutation reports only locally normalized persistence. It does not prove server
 * reachability, authentication, or session readiness; pass the returned [ServerProfile] to
 * [TvheadendSession.connect] when the application is ready to establish a session.
 */
public interface ServerProfileStore {
    /** Loads the selected profile without exposing password fields. */
    public suspend fun loadProfile(): ServerProfileReadResult

    /** Stores and returns a locally normalized anonymous profile. */
    public suspend fun storeAnonymous(
        host: String,
        port: Int = 9_982,
    ): ServerProfileReadResult

    /** Stores and returns a locally normalized password profile. */
    public suspend fun storePassword(
        host: String,
        port: Int = 9_982,
        username: String,
        password: String,
    ): ServerProfileReadResult

    /** Clears the selected profile and returns authoritative local missing state. */
    public suspend fun clearProfile(): ServerProfileReadResult
}

/** Authentication mode associated with a selected server profile. */
public enum class ServerProfileAuthenticationMode {
    /** Connect without credentials. */
    ANONYMOUS,

    /** Connect with password authentication. */
    PASSWORD,
}

/** Safe selected-profile state returned by reads and local persistence mutations. */
public sealed interface ServerProfileReadResult {
    /** No server profile is stored. */
    public data object Missing : ServerProfileReadResult

    /** A connectable profile and its non-secret endpoint fields are available. */
    public class Available private constructor(
        public val profile: ServerProfile,
        public val host: String,
        public val port: Int,
        public val authenticationMode: ServerProfileAuthenticationMode,
    ) : ServerProfileReadResult {
        override fun toString(): String = "ServerProfileReadResult.Available(<redacted>)"

        internal companion object {
            @JvmSynthetic
            internal fun create(
                profile: ServerProfile,
                authenticationMode: ServerProfileAuthenticationMode,
            ): Available = Available(
                profile = profile,
                host = profile.host,
                port = profile.port,
                authenticationMode = authenticationMode,
            )
        }
    }

    /** Profile data could not be read, validated, decrypted, or persisted. */
    public data object Unavailable : ServerProfileReadResult

    public companion object {
        /** Constructs a validated anonymous result for implementations and consumer fakes. */
        @JvmStatic
        public fun anonymous(
            host: String,
            port: Int = 9_982,
        ): Available = Available.create(
            profile = ServerProfile(host, port),
            authenticationMode = ServerProfileAuthenticationMode.ANONYMOUS,
        )

        /**
         * Constructs a validated password result for implementations and consumer fakes.
         *
         * The returned result retains credentials only inside its opaque [ServerProfile]. Do not
         * serialize or log the input credentials or retain extra copies after this call.
         */
        @JvmStatic
        public fun password(
            host: String,
            port: Int = 9_982,
            username: String,
            password: String,
        ): Available = Available.create(
            profile = ServerProfile(host, port, ServerAuthentication.Password(username, password)),
            authenticationMode = ServerProfileAuthenticationMode.PASSWORD,
        )
    }
}
