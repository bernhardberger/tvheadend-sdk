package at.bernhardberger.tvheadend.sdk.android

import android.content.Context
import at.bernhardberger.tvheadend.sdk.core.ServerProfileReadResult
import at.bernhardberger.tvheadend.sdk.core.ServerProfileStore
import at.bernhardberger.tvheadend.sdk.core.ServerProfileId
import at.bernhardberger.tvheadend.sdk.core.ServerProfileSummary
import at.bernhardberger.tvheadend.sdk.core.ServerProfileAuthenticationMode
import at.bernhardberger.tvheadend.sdk.core.ServerProfileListResult
import at.bernhardberger.tvheadend.sdk.core.ServerProfileWriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.jvm.JvmSynthetic

/**
 * Atomically persists Tvheadend profiles and one optional active selection.
 *
 * Use this store from one application process; it does not coordinate Android multi-process access.
 */
public class TvheadendServerProfileStore private constructor(
    private val storage: CredentialStorage,
    private val cipher: CredentialCipher,
    private val operationMutex: Mutex,
) : ServerProfileStore {
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

    override suspend fun profiles(): ServerProfileListResult = access(ServerProfileListResult.Unavailable) { collection ->
        ServerProfileListResult.Available(collection.profiles.map { record ->
            ServerProfileSummary.create(
                checkNotNull(record.id), record.label, record.host, record.port, record.publicMode(),
                username = record.username,
                credentialsAvailable = !record.credentialsUnavailable,
            )
        }, collection.activeId)
    }

    override suspend fun loadActive(): ServerProfileReadResult = access(ServerProfileReadResult.Unavailable) { collection ->
        collection.profiles.singleOrNull { it.id == collection.activeId }?.let { loadProfile(it) }
            ?: ServerProfileReadResult.Missing
    }

    override suspend fun load(id: ServerProfileId): ServerProfileReadResult = access(ServerProfileReadResult.Unavailable) { collection ->
        collection.profiles.singleOrNull { it.id == id }?.let { loadProfile(it) }
            ?: ServerProfileReadResult.Missing
    }

    /**
     * Loads fields for [id] into a secure edit surface.
     *
     * A [ServerProfileEditReadResult.Password] contains immutable plaintext strings that cannot
     * be zeroed. Keep that result only in private memory while the active edit surface needs it,
     * then drop every reference.
     */
    public suspend fun loadForEditing(id: ServerProfileId): ServerProfileEditReadResult =
        access(ServerProfileEditReadResult.Unavailable) { collection ->
            collection.profiles.singleOrNull { it.id == id }?.let { loadProfileForEditing(it) }
                ?: ServerProfileEditReadResult.Missing
        }

    /**
     * Atomically stores an anonymous profile after normalizing and validating its endpoint.
     *
     * @throws IllegalArgumentException if [host], [port], or [label] is invalid.
     */
    override suspend fun addAnonymous(
        host: String,
        port: Int,
        label: String?,
        activate: Boolean,
    ): ServerProfileWriteResult = save(
        ServerProfileReadResult.anonymous(ServerProfileId.generate(), host, port, label),
        adding = true, activate = activate,
    )

    /**
     * Atomically stores an endpoint-bound password profile after validating all input.
     *
     * @throws IllegalArgumentException if the endpoint, credentials, or [label] is invalid.
     */
    override suspend fun addPassword(
        host: String,
        port: Int,
        username: String,
        password: String,
        label: String?,
        activate: Boolean,
    ): ServerProfileWriteResult = save(
        ServerProfileReadResult.password(ServerProfileId.generate(), host, port, username, password, label),
        adding = true, activate = activate, username = username.trim(), password = password,
    )

    /**
     * Replaces anonymous fields for [id], retaining its identity.
     * @throws IllegalArgumentException if [host], [port], or [label] is invalid.
     */
    override suspend fun updateAnonymous(
        id: ServerProfileId, host: String, port: Int, label: String?,
    ): ServerProfileWriteResult = save(ServerProfileReadResult.anonymous(id, host, port, label), adding = false)

    /**
     * Replaces password fields for [id], retaining its identity.
     * @throws IllegalArgumentException if the endpoint, credentials, or [label] is invalid.
     */
    override suspend fun updatePassword(
        id: ServerProfileId, host: String, port: Int, username: String, password: String, label: String?,
    ): ServerProfileWriteResult = save(
        ServerProfileReadResult.password(id, host, port, username, password, label),
        adding = false, username = username.trim(), password = password,
    )

    override suspend fun updateLabel(id: ServerProfileId, label: String?): ServerProfileWriteResult {
        val normalizedLabel = ServerProfileSummary.normalizeLabel(label)
        currentCoroutineContext().ensureActive()
        return try {
            operationMutex.withLock { storage.updateLabel(id, normalizedLabel) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: IllegalArgumentException) {
            throw invalid
        } catch (_: Exception) {
            ServerProfileWriteResult.Unavailable
        }
    }

    override suspend fun setActive(id: ServerProfileId): ServerProfileWriteResult =
        mutate(id) { collection -> StoredCredentialRecord.Collection(collection.profiles, id) }

    override suspend fun remove(id: ServerProfileId): ServerProfileWriteResult = mutate(id) { collection ->
        StoredCredentialRecord.Collection(
            collection.profiles.filterNot { it.id == id }, collection.activeId?.takeUnless { it == id },
        )
    }

    /** Removes every profile and the selection in one atomic DataStore update. */
    override suspend fun clearAll() {
        currentCoroutineContext().ensureActive()
        operationMutex.withLock { storage.clear() }
    }

    private suspend fun save(
        result: ServerProfileReadResult.Available,
        adding: Boolean,
        activate: Boolean = false,
        username: String? = null,
        password: String? = null,
    ): ServerProfileWriteResult = access(ServerProfileWriteResult.Unavailable) { collection ->
        if (!adding && collection.profiles.none { it.id == result.id }) {
            return@access ServerProfileWriteResult.NotFound
        }
        val mode = if (username == null) StoredAuthenticationMode.ANONYMOUS else StoredAuthenticationMode.PASSWORD
        val context = CredentialCipherContext.Profile(result.host, result.port, mode, result.id, username)
        val credentials = username?.let { cipher.encrypt(checkNotNull(password), context) }
        val record = StoredCredentialRecord.Profile(
            result.host, result.port, mode, credentials, result.id, result.label,
            username = username,
        )
        val records = if (adding) collection.profiles + record
        else collection.profiles.map { if (it.id == result.id) record else it }
        storage.write(StoredCredentialRecord.Collection(records, if (activate) result.id else collection.activeId))
        ServerProfileWriteResult.Applied(result.id)
    }

    private suspend fun mutate(
        id: ServerProfileId,
        transform: (StoredCredentialRecord.Collection) -> StoredCredentialRecord.Collection,
    ): ServerProfileWriteResult = access(ServerProfileWriteResult.Unavailable) { collection ->
        if (collection.profiles.none { it.id == id }) return@access ServerProfileWriteResult.NotFound
        storage.write(transform(collection))
        ServerProfileWriteResult.Applied(id)
    }

    private suspend fun <T> access(
        unavailable: T,
        operation: suspend (StoredCredentialRecord.Collection) -> T,
    ): T = profileResult(unavailable) {
        currentCoroutineContext().ensureActive()
        operationMutex.withLock {
            val collection = readCollection() ?: return@withLock unavailable
            operation(collection)
        }
    }

    private suspend fun readCollection(): StoredCredentialRecord.Collection? = when (val stored = storage.read()) {
        StoredCredentialRead.Missing -> StoredCredentialRecord.Collection(emptyList(), null)
        StoredCredentialRead.Unavailable -> null
        is StoredCredentialRead.Available -> when (val record = stored.record) {
            is StoredCredentialRecord.Collection -> record
            is StoredCredentialRecord.Profile -> migrate(record)
        }
    }

    private suspend fun migrate(legacy: StoredCredentialRecord.Profile): StoredCredentialRecord.Collection {
        val id = ServerProfileId.generate()
        var username: String? = null
        val credentials = if (legacy.authenticationMode == StoredAuthenticationMode.PASSWORD) {
            val plaintext = try {
                // Decrypt outside encrypt's callback: the production cipher serializes both calls.
                cipher.decrypt(checkNotNull(legacy.credentials), legacy.cipherContext()) { user, pass ->
                    val normalizedUsername = user.trim()
                    if (normalizedUsername.isEmpty()) throw CredentialUnreadableException()
                    normalizedUsername to pass
                }
            } catch (_: CredentialUnreadableException) {
                null
            }
            // Encryption/storage failures leave the legacy record intact for a later retry.
            plaintext?.let {
                username = it.first
                val context = CredentialCipherContext.Profile(legacy.host, legacy.port, legacy.authenticationMode, id, username)
                cipher.encrypt(it.second, context)
            }
        } else null
        val migrated = StoredCredentialRecord.Profile(
            legacy.host, legacy.port, legacy.authenticationMode, credentials, id,
            credentialsUnavailable = legacy.authenticationMode == StoredAuthenticationMode.PASSWORD && credentials == null,
            username = username,
        )
        return StoredCredentialRecord.Collection(listOf(migrated), id).also { storage.write(it) }
    }

    private suspend fun loadProfile(record: StoredCredentialRecord.Profile): ServerProfileReadResult {
        if (record.credentialsUnavailable) return ServerProfileReadResult.Unavailable
        return when (record.authenticationMode) {
            StoredAuthenticationMode.ANONYMOUS -> ServerProfileReadResult.anonymous(
                host = record.host,
                port = record.port,
                id = checkNotNull(record.id),
                label = record.label,
            )
            StoredAuthenticationMode.PASSWORD -> cipher.decrypt(
                credentials = checkNotNull(record.credentials),
                context = record.cipherContext(),
            ) { _, password ->
                ServerProfileReadResult.password(
                    host = record.host,
                    port = record.port,
                    username = checkNotNull(record.username),
                    password = password,
                    id = checkNotNull(record.id),
                    label = record.label,
                )
            }
        }
    }

    private suspend fun loadProfileForEditing(
        record: StoredCredentialRecord.Profile,
    ): ServerProfileEditReadResult = if (record.credentialsUnavailable) ServerProfileEditReadResult.Unavailable
    else when (record.authenticationMode) {
        StoredAuthenticationMode.ANONYMOUS -> ServerProfileEditReadResult.Anonymous.create(
            host = record.host,
            port = record.port,
            label = record.label,
        )
        StoredAuthenticationMode.PASSWORD -> cipher.decrypt(
            credentials = checkNotNull(record.credentials),
            context = record.cipherContext(),
        ) { _, password ->
            ServerProfileEditReadResult.Password.create(
                host = record.host,
                port = record.port,
                username = checkNotNull(record.username),
                password = password,
                label = record.label,
            )
        }
    }
}

private fun StoredCredentialRecord.Profile.publicMode(): ServerProfileAuthenticationMode = when (authenticationMode) {
    StoredAuthenticationMode.ANONYMOUS -> ServerProfileAuthenticationMode.ANONYMOUS
    StoredAuthenticationMode.PASSWORD -> ServerProfileAuthenticationMode.PASSWORD
}

/** Result of looking up a server profile by identifier for editing. */
public sealed interface ServerProfileEditReadResult {
    /** No profile exists for the requested identifier. */
    public data object Missing : ServerProfileEditReadResult

    /** Editable fields for an anonymous profile. */
    public class Anonymous private constructor(
        /** Normalized server host. */
        public val host: String,
        /** Server HTSP port. */
        public val port: Int,
        /** Optional normalized display label. */
        public val label: String?,
    ) : ServerProfileEditReadResult {
        internal companion object {
            @JvmSynthetic
            internal fun create(host: String, port: Int, label: String?): Anonymous = Anonymous(host, port, label)
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
        /** Optional normalized display label. */
        public val label: String?,
    ) : ServerProfileEditReadResult {
        internal companion object {
            @JvmSynthetic
            internal fun create(
                host: String,
                port: Int,
                username: String,
                password: String,
                label: String?,
            ): Password {
                val normalizedUsername = username.trim()
                require(normalizedUsername.isNotEmpty()) { "Username must not be blank" }
                require(password.isNotBlank()) { "Password must not be blank" }
                return Password(host, port, normalizedUsername, password, label)
            }
        }

        override fun toString(): String = "ServerProfileEditReadResult.Password(<redacted>)"
    }

    /** Stored profile data could not be read, validated, or decrypted. */
    public data object Unavailable : ServerProfileEditReadResult
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
