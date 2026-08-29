package at.bernhardberger.tvheadend.sdk.android

import android.content.Context
import at.bernhardberger.tvheadend.sdk.core.ServerAuthentication
import at.bernhardberger.tvheadend.sdk.core.ServerProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.jvm.JvmSynthetic

/**
 * Atomically persists the single TVHeadend server profile selected by an application.
 *
 * Use this store from one application process; it does not coordinate Android multi-process access.
 */
public class TvheadendServerProfileStore private constructor(
    private val storage: CredentialStorage,
    private val cipher: CredentialCipher,
    private val operationMutex: Mutex,
) {
    /** Creates a profile store scoped to the application that owns [context]. */
    public constructor(context: Context) : this(
        storage = productionCredentialStorage(context),
        cipher = AndroidTinkCredentialCipher,
        operationMutex = credentialOperationMutex,
    )

    internal companion object {
        @JvmSynthetic
        internal fun create(
            storage: CredentialStorage,
            cipher: CredentialCipher,
            operationMutex: Mutex = Mutex(),
        ): TvheadendServerProfileStore = TvheadendServerProfileStore(storage, cipher, operationMutex)
    }

    /** Loads the complete selected profile without exposing password fields. */
    public suspend fun loadProfile(): ServerProfileReadResult = profileResult(
        unavailable = ServerProfileReadResult.Unavailable,
    ) {
        operationMutex.withLock {
            when (val stored = storage.read()) {
                StoredCredentialRead.Missing -> ServerProfileReadResult.Missing
                StoredCredentialRead.Unavailable -> ServerProfileReadResult.Unavailable
                is StoredCredentialRead.Available -> when (val record = stored.record) {
                    is StoredCredentialRecord.LegacyPassword -> ServerProfileReadResult.Missing
                    is StoredCredentialRecord.Profile -> loadProfile(record)
                }
            }
        }
    }

    /**
     * Loads the selected profile fields for a secure edit surface.
     *
     * A [ServerProfileEditReadResult.Password] contains immutable plaintext strings that cannot
     * be zeroed. Keep that result only in private memory while the active edit surface needs it,
     * then drop every reference.
     */
    public suspend fun loadProfileForEditing(): ServerProfileEditReadResult = profileResult(
        unavailable = ServerProfileEditReadResult.Unavailable,
    ) {
        operationMutex.withLock {
            when (val stored = storage.read()) {
                StoredCredentialRead.Missing -> ServerProfileEditReadResult.Missing
                StoredCredentialRead.Unavailable -> ServerProfileEditReadResult.Unavailable
                is StoredCredentialRead.Available -> when (val record = stored.record) {
                    is StoredCredentialRecord.LegacyPassword -> ServerProfileEditReadResult.Missing
                    is StoredCredentialRecord.Profile -> loadProfileForEditing(record)
                }
            }
        }
    }

    /**
     * Atomically stores an anonymous profile after normalizing and validating its endpoint.
     *
     * @throws IllegalArgumentException if [host] or [port] is invalid.
     */
    public suspend fun storeAnonymous(
        host: String,
        port: Int = 9_982,
    ): ServerProfileOperationResult {
        val normalizedHost = normalizeEndpoint(host, port)
        return profileResult(ServerProfileOperationResult.UNAVAILABLE) {
            operationMutex.withLock {
                storage.write(
                    StoredCredentialRecord.Profile(
                        host = normalizedHost,
                        port = port,
                        authenticationMode = StoredAuthenticationMode.ANONYMOUS,
                        credentials = null,
                    ),
                )
                ServerProfileOperationResult.SUCCESS
            }
        }
    }

    /**
     * Atomically stores an endpoint-bound password profile after validating all input.
     *
     * @throws IllegalArgumentException if the endpoint, [username], or [password] is invalid.
     */
    public suspend fun storePassword(
        host: String,
        port: Int = 9_982,
        username: String,
        password: String,
    ): ServerProfileOperationResult {
        val normalizedHost = normalizeEndpoint(host, port)
        val normalizedUsername = username.trim()
        require(normalizedUsername.isNotEmpty()) { "Username must not be blank" }
        require(password.isNotBlank()) { "Password must not be blank" }
        val context = CredentialCipherContext.Profile(
            host = normalizedHost,
            port = port,
            authenticationMode = StoredAuthenticationMode.PASSWORD,
        )

        return profileResult(ServerProfileOperationResult.UNAVAILABLE) {
            operationMutex.withLock {
                val credentials = cipher.encrypt(normalizedUsername, password, context)
                storage.write(
                    StoredCredentialRecord.Profile(
                        host = normalizedHost,
                        port = port,
                        authenticationMode = StoredAuthenticationMode.PASSWORD,
                        credentials = credentials,
                    ),
                )
                ServerProfileOperationResult.SUCCESS
            }
        }
    }

    /** Removes the complete selected profile in one atomic DataStore update. */
    public suspend fun clearProfile(): ServerProfileOperationResult = profileResult(
        unavailable = ServerProfileOperationResult.UNAVAILABLE,
    ) {
        operationMutex.withLock {
            storage.clear()
            ServerProfileOperationResult.SUCCESS
        }
    }

    private suspend fun loadProfile(record: StoredCredentialRecord.Profile): ServerProfileReadResult {
        val authentication = when (record.authenticationMode) {
            StoredAuthenticationMode.ANONYMOUS -> ServerAuthentication.Anonymous
            StoredAuthenticationMode.PASSWORD -> cipher.decrypt(
                credentials = checkNotNull(record.credentials),
                context = record.cipherContext(),
            ) { username, password ->
                ServerAuthentication.Password(username, password)
            }
        }
        return ServerProfileReadResult.Available.create(
            profile = ServerProfile(record.host, record.port, authentication),
            host = record.host,
            port = record.port,
            authenticationMode = when (record.authenticationMode) {
                StoredAuthenticationMode.ANONYMOUS -> ServerProfileAuthenticationMode.ANONYMOUS
                StoredAuthenticationMode.PASSWORD -> ServerProfileAuthenticationMode.PASSWORD
            },
        )
    }

    private suspend fun loadProfileForEditing(
        record: StoredCredentialRecord.Profile,
    ): ServerProfileEditReadResult = when (record.authenticationMode) {
        StoredAuthenticationMode.ANONYMOUS -> ServerProfileEditReadResult.Anonymous.create(
            host = record.host,
            port = record.port,
        )
        StoredAuthenticationMode.PASSWORD -> cipher.decrypt(
            credentials = checkNotNull(record.credentials),
            context = record.cipherContext(),
        ) { username, password ->
            ServerProfileEditReadResult.Password.create(
                host = record.host,
                port = record.port,
                username = username,
                password = password,
            )
        }
    }
}

/** Authentication mode stored with a selected server profile. */
public enum class ServerProfileAuthenticationMode {
    /** Connect without credentials. */
    ANONYMOUS,

    /** Connect with endpoint-bound password authentication. */
    PASSWORD,
}

/** Safe result of loading a selected server profile. */
public sealed interface ServerProfileReadResult {
    /** No server profile is stored. */
    public data object Missing : ServerProfileReadResult

    /** A connectable profile and its non-secret editable endpoint fields are available. */
    public class Available private constructor(
        public val profile: ServerProfile,
        public val host: String,
        public val port: Int,
        public val authenticationMode: ServerProfileAuthenticationMode,
    ) : ServerProfileReadResult {
        internal companion object {
            @JvmSynthetic
            internal fun create(
                profile: ServerProfile,
                host: String,
                port: Int,
                authenticationMode: ServerProfileAuthenticationMode,
            ): Available = Available(profile, host, port, authenticationMode)
        }

        override fun toString(): String = "ServerProfileReadResult.Available(<redacted>)"
    }

    /** Stored profile data could not be read, validated, or decrypted. */
    public data object Unavailable : ServerProfileReadResult
}

/** Result of loading the selected server profile for editing. */
public sealed interface ServerProfileEditReadResult {
    /** No server profile is stored. */
    public data object Missing : ServerProfileEditReadResult

    /** Editable fields for an anonymous profile. */
    public class Anonymous private constructor(
        /** Normalized server host. */
        public val host: String,
        /** Server HTSP port. */
        public val port: Int,
    ) : ServerProfileEditReadResult {
        internal companion object {
            @JvmSynthetic
            internal fun create(host: String, port: Int): Anonymous = Anonymous(host, port)
        }

        override fun toString(): String = "ServerProfileEditReadResult.Anonymous(<redacted>)"
    }

    /**
     * Editable fields for a password profile.
     *
     * [username] and [password] are immutable plaintext strings and cannot be zeroed. Keep this
     * result only in private memory while the active secure edit surface needs it, then drop every
     * reference. Do not serialize it, place it in saved state, log it, or use it as a diagnostic.
     */
    public class Password private constructor(
        /** Normalized server host. */
        public val host: String,
        /** Server HTSP port. */
        public val port: Int,
        /** Exact normalized username. */
        public val username: String,
        /** Exact password. */
        public val password: String,
    ) : ServerProfileEditReadResult {
        internal companion object {
            @JvmSynthetic
            internal fun create(
                host: String,
                port: Int,
                username: String,
                password: String,
            ): Password {
                val normalizedUsername = username.trim()
                require(normalizedUsername.isNotEmpty()) { "Username must not be blank" }
                require(password.isNotBlank()) { "Password must not be blank" }
                return Password(host, port, normalizedUsername, password)
            }
        }

        override fun toString(): String = "ServerProfileEditReadResult.Password(<redacted>)"
    }

    /** Stored profile data could not be read, validated, or decrypted. */
    public data object Unavailable : ServerProfileEditReadResult
}

/** Safe result of a server profile storage mutation. */
public enum class ServerProfileOperationResult {
    /** The complete requested mutation was persisted. */
    SUCCESS,

    /** The mutation could not be persisted. */
    UNAVAILABLE,
}

private fun normalizeEndpoint(host: String, port: Int): String {
    val normalizedHost = host.trim()
    require(normalizedHost.isNotEmpty()) { "Server host must not be blank" }
    require(port in 1..65_535) { "Server port must be valid" }
    return normalizedHost
}

private suspend fun <T> profileResult(
    unavailable: T,
    operation: suspend () -> T,
): T = try {
    operation()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    unavailable
}
