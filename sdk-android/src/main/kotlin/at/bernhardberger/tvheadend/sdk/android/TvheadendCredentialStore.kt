@file:Suppress("DEPRECATION")

package at.bernhardberger.tvheadend.sdk.android

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import at.bernhardberger.tvheadend.sdk.core.ServerAuthentication
import com.google.crypto.tink.Aead
import com.google.crypto.tink.integration.android.AndroidKeystore
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.jvm.JvmSynthetic

/**
 * Persists TVHeadend password authentication without an endpoint.
 *
 * Use [TvheadendServerProfileStore] for new integrations. This compatibility
 * store remains scoped to one application process and shares its record with the profile store.
 */
@Deprecated(
    message = "Use TvheadendServerProfileStore",
    level = DeprecationLevel.WARNING,
)
public class TvheadendCredentialStore private constructor(
    private val storage: CredentialStorage,
    private val cipher: CredentialCipher,
    private val operationMutex: Mutex,
) {
    /** Creates a credential store scoped to the application that owns [context]. */
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
        ): TvheadendCredentialStore = TvheadendCredentialStore(storage, cipher, operationMutex)
    }

    /** Loads opaque password authentication, or a typed safe state when none can be loaded. */
    public suspend fun loadPassword(): CredentialReadResult = credentialResult(
        unavailable = CredentialReadResult.Unavailable,
    ) {
        operationMutex.withLock {
            when (val stored = storage.read()) {
                StoredCredentialRead.Missing -> CredentialReadResult.Missing
                StoredCredentialRead.Unavailable -> CredentialReadResult.Unavailable
                is StoredCredentialRead.Available -> when (val record = stored.record) {
                    is StoredCredentialRecord.LegacyPassword -> CredentialReadResult.Available.create(
                        cipher.decrypt(
                            credentials = record.credentials,
                            context = CredentialCipherContext.Legacy,
                        ) { username, password ->
                            ServerAuthentication.Password(username, password)
                        },
                    )
                    is StoredCredentialRecord.Profile -> when (record.authenticationMode) {
                        StoredAuthenticationMode.ANONYMOUS -> CredentialReadResult.Missing
                        StoredAuthenticationMode.PASSWORD -> CredentialReadResult.Available.create(
                            cipher.decrypt(
                                credentials = checkNotNull(record.credentials),
                                context = record.cipherContext(),
                            ) { username, password ->
                                ServerAuthentication.Password(username, password)
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * Atomically replaces password authentication while preserving a stored profile endpoint.
     *
     * @throws IllegalArgumentException if [username] or [password] is blank.
     */
    public suspend fun storePassword(
        username: String,
        password: String,
    ): CredentialOperationResult {
        val normalizedUsername = username.trim()
        require(normalizedUsername.isNotEmpty()) { "Username must not be blank" }
        require(password.isNotBlank()) { "Password must not be blank" }

        return credentialResult(CredentialOperationResult.UNAVAILABLE) {
            operationMutex.withLock {
                when (val stored = storage.read()) {
                    StoredCredentialRead.Unavailable -> CredentialOperationResult.UNAVAILABLE
                    StoredCredentialRead.Missing -> {
                        writeLegacyPassword(normalizedUsername, password)
                        CredentialOperationResult.SUCCESS
                    }
                    is StoredCredentialRead.Available -> when (val record = stored.record) {
                        is StoredCredentialRecord.LegacyPassword -> {
                            writeLegacyPassword(normalizedUsername, password)
                            CredentialOperationResult.SUCCESS
                        }
                        is StoredCredentialRecord.Profile -> {
                            validateStoredPassword(record)
                            val updated = record.withPassword(
                                cipher.encrypt(
                                    normalizedUsername,
                                    password,
                                    record.passwordCipherContext(),
                                ),
                            )
                            storage.write(updated)
                            CredentialOperationResult.SUCCESS
                        }
                    }
                }
            }
        }
    }

    /** Removes password authentication while preserving a valid stored profile endpoint. */
    public suspend fun clearPassword(): CredentialOperationResult = credentialResult(
        unavailable = CredentialOperationResult.UNAVAILABLE,
    ) {
        operationMutex.withLock {
            when (val stored = storage.read()) {
                StoredCredentialRead.Unavailable -> CredentialOperationResult.UNAVAILABLE
                StoredCredentialRead.Missing -> {
                    storage.clear()
                    CredentialOperationResult.SUCCESS
                }
                is StoredCredentialRead.Available -> when (val record = stored.record) {
                    is StoredCredentialRecord.LegacyPassword -> {
                        storage.clear()
                        CredentialOperationResult.SUCCESS
                    }
                    is StoredCredentialRecord.Profile -> {
                        validateStoredPassword(record)
                        storage.write(record.withAnonymousAuthentication())
                        CredentialOperationResult.SUCCESS
                    }
                }
            }
        }
    }

    private suspend fun writeLegacyPassword(username: String, password: String) {
        storage.write(
            StoredCredentialRecord.LegacyPassword(
                cipher.encrypt(username, password, CredentialCipherContext.Legacy),
            ),
        )
    }

    private suspend fun validateStoredPassword(record: StoredCredentialRecord.Profile) {
        if (record.authenticationMode == StoredAuthenticationMode.PASSWORD) {
            cipher.decrypt(
                credentials = checkNotNull(record.credentials),
                context = record.cipherContext(),
            ) { username, password ->
                ServerAuthentication.Password(username, password)
            }
        }
    }
}

/** Safe result of loading stored password authentication. */
public sealed interface CredentialReadResult {
    /** No password authentication is stored. */
    public data object Missing : CredentialReadResult

    /** Stored password authentication is available without exposing credential getters. */
    public class Available private constructor(
        public val authentication: ServerAuthentication.Password,
    ) : CredentialReadResult {
        internal companion object {
            @JvmSynthetic
            internal fun create(authentication: ServerAuthentication.Password): Available =
                Available(authentication)
        }

        override fun toString(): String = "CredentialReadResult.Available(<redacted>)"
    }

    /** Stored authentication could not be read or decrypted. */
    public data object Unavailable : CredentialReadResult
}

/** Safe result of a credential storage mutation. */
public enum class CredentialOperationResult {
    /** The complete requested mutation was persisted. */
    SUCCESS,

    /** The mutation could not be persisted. */
    UNAVAILABLE,
}

internal interface CredentialStorage {
    suspend fun read(): StoredCredentialRead

    suspend fun write(record: StoredCredentialRecord)

    suspend fun clear()
}

internal interface CredentialCipher {
    suspend fun encrypt(
        username: String,
        password: String,
        context: CredentialCipherContext,
    ): EncryptedCredentials

    suspend fun <T> decrypt(
        credentials: EncryptedCredentials,
        context: CredentialCipherContext,
        transform: (username: String, password: String) -> T,
    ): T
}

internal sealed interface CredentialCipherContext {
    data object Legacy : CredentialCipherContext

    class Profile(
        val host: String,
        val port: Int,
        val authenticationMode: StoredAuthenticationMode,
    ) : CredentialCipherContext
}

internal sealed interface StoredCredentialRead {
    data object Missing : StoredCredentialRead

    data object Unavailable : StoredCredentialRead

    class Available(val record: StoredCredentialRecord) : StoredCredentialRead
}

internal sealed interface StoredCredentialRecord {
    class LegacyPassword(val credentials: EncryptedCredentials) : StoredCredentialRecord {
        override fun toString(): String = "StoredCredentialRecord.LegacyPassword(<redacted>)"
    }

    class Profile(
        val host: String,
        val port: Int,
        val authenticationMode: StoredAuthenticationMode,
        val credentials: EncryptedCredentials?,
    ) : StoredCredentialRecord {
        init {
            require((authenticationMode == StoredAuthenticationMode.PASSWORD) == (credentials != null))
        }

        fun cipherContext(): CredentialCipherContext.Profile = CredentialCipherContext.Profile(
            host = host,
            port = port,
            authenticationMode = authenticationMode,
        )

        fun passwordCipherContext(): CredentialCipherContext.Profile = CredentialCipherContext.Profile(
            host = host,
            port = port,
            authenticationMode = StoredAuthenticationMode.PASSWORD,
        )

        fun withPassword(encrypted: EncryptedCredentials): Profile = Profile(
            host = host,
            port = port,
            authenticationMode = StoredAuthenticationMode.PASSWORD,
            credentials = encrypted,
        )

        fun withAnonymousAuthentication(): Profile = Profile(
            host = host,
            port = port,
            authenticationMode = StoredAuthenticationMode.ANONYMOUS,
            credentials = null,
        )

        override fun toString(): String = "StoredCredentialRecord.Profile(<redacted>)"
    }
}

internal enum class StoredAuthenticationMode(val serializedName: String, val associatedDataCode: Byte) {
    ANONYMOUS("anonymous", 0),
    PASSWORD("password", 1),
}

internal class EncryptedCredentials(
    encryptedUsername: ByteArray,
    encryptedPassword: ByteArray,
) {
    private val username: ByteArray = encryptedUsername.copyOf()
    private val password: ByteArray = encryptedPassword.copyOf()

    internal fun copyUsername(): ByteArray = username.copyOf()

    internal fun copyPassword(): ByteArray = password.copyOf()

    override fun toString(): String = "EncryptedCredentials(<redacted>)"
}

private class DataStoreCredentialStorage(
    private val dataStore: DataStore<Preferences>,
) : CredentialStorage {
    override suspend fun read(): StoredCredentialRead = decodeCredentialRecord(dataStore.data.first())

    override suspend fun write(record: StoredCredentialRecord) {
        dataStore.edit { preferences ->
            preferences.clear()
            when (record) {
                is StoredCredentialRecord.LegacyPassword -> {
                    preferences[CREDENTIAL_VERSION_KEY] = LEGACY_CREDENTIAL_FORMAT_VERSION
                    preferences[ENCRYPTED_USERNAME_KEY] = record.credentials.copyUsername()
                    preferences[ENCRYPTED_PASSWORD_KEY] = record.credentials.copyPassword()
                }
                is StoredCredentialRecord.Profile -> {
                    preferences[CREDENTIAL_VERSION_KEY] = PROFILE_CREDENTIAL_FORMAT_VERSION
                    preferences[SERVER_HOST_KEY] = record.host
                    preferences[SERVER_PORT_KEY] = record.port
                    preferences[AUTHENTICATION_MODE_KEY] = record.authenticationMode.serializedName
                    record.credentials?.let { credentials ->
                        preferences[ENCRYPTED_USERNAME_KEY] = credentials.copyUsername()
                        preferences[ENCRYPTED_PASSWORD_KEY] = credentials.copyPassword()
                    }
                }
            }
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences -> preferences.clear() }
    }
}

internal object AndroidTinkCredentialCipher : CredentialCipher {
    private val mutex = Mutex()

    override suspend fun encrypt(
        username: String,
        password: String,
        context: CredentialCipherContext,
    ): EncryptedCredentials = withContext(Dispatchers.IO) {
        mutex.withLock {
            val aead = credentialAead(createIfMissing = true)
            val usernameBytes = username.encodeToByteArray()
            val passwordBytes = password.encodeToByteArray()
            try {
                EncryptedCredentials(
                    encryptedUsername = aead.encrypt(
                        usernameBytes,
                        credentialAssociatedData(context, CredentialField.USERNAME),
                    ),
                    encryptedPassword = aead.encrypt(
                        passwordBytes,
                        credentialAssociatedData(context, CredentialField.PASSWORD),
                    ),
                )
            } finally {
                usernameBytes.fill(0)
                passwordBytes.fill(0)
            }
        }
    }

    override suspend fun <T> decrypt(
        credentials: EncryptedCredentials,
        context: CredentialCipherContext,
        transform: (username: String, password: String) -> T,
    ): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val aead = credentialAead(createIfMissing = false)
            var usernameBytes: ByteArray? = null
            var passwordBytes: ByteArray? = null
            try {
                usernameBytes = aead.decrypt(
                    credentials.copyUsername(),
                    credentialAssociatedData(context, CredentialField.USERNAME),
                )
                passwordBytes = aead.decrypt(
                    credentials.copyPassword(),
                    credentialAssociatedData(context, CredentialField.PASSWORD),
                )
                transform(
                    usernameBytes.decodeToString(throwOnInvalidSequence = true),
                    passwordBytes.decodeToString(throwOnInvalidSequence = true),
                )
            } finally {
                usernameBytes?.fill(0)
                passwordBytes?.fill(0)
            }
        }
    }

    private fun credentialAead(createIfMissing: Boolean): Aead {
        if (!AndroidKeystore.hasKey(KEY_ALIAS)) {
            if (!createIfMissing) {
                throw GeneralSecurityException("Credential key is unavailable")
            }
            AndroidKeystore.generateNewAes256GcmKey(KEY_ALIAS)
        }
        return AndroidKeystore.getAead(KEY_ALIAS)
    }
}

internal enum class CredentialField(val associatedDataCode: Byte) {
    USERNAME(0),
    PASSWORD(1),
}

internal fun credentialAssociatedData(
    context: CredentialCipherContext,
    field: CredentialField,
): ByteArray = when (context) {
    CredentialCipherContext.Legacy -> when (field) {
        CredentialField.USERNAME -> LEGACY_USERNAME_ASSOCIATED_DATA.copyOf()
        CredentialField.PASSWORD -> LEGACY_PASSWORD_ASSOCIATED_DATA.copyOf()
    }
    is CredentialCipherContext.Profile -> {
        val hostBytes = context.host.encodeToByteArray()
        ByteBuffer.allocate(
            Int.SIZE_BYTES + CREDENTIAL_NAMESPACE.size +
                Int.SIZE_BYTES + Byte.SIZE_BYTES +
                Int.SIZE_BYTES + hostBytes.size +
                Int.SIZE_BYTES + Byte.SIZE_BYTES,
        )
            .putInt(CREDENTIAL_NAMESPACE.size)
            .put(CREDENTIAL_NAMESPACE)
            .putInt(PROFILE_CREDENTIAL_FORMAT_VERSION)
            .put(field.associatedDataCode)
            .putInt(hostBytes.size)
            .put(hostBytes)
            .putInt(context.port)
            .put(context.authenticationMode.associatedDataCode)
            .array()
    }
}

internal fun decodeCredentialRecord(preferences: Preferences): StoredCredentialRead {
    val storedKeys = preferences.asMap().keys
    if (storedKeys.isEmpty()) return StoredCredentialRead.Missing
    if (!KNOWN_CREDENTIAL_KEYS.containsAll(storedKeys)) return StoredCredentialRead.Unavailable

    val version = preferences[CREDENTIAL_VERSION_KEY]
    val username = preferences[ENCRYPTED_USERNAME_KEY]
    val password = preferences[ENCRYPTED_PASSWORD_KEY]
    val host = preferences[SERVER_HOST_KEY]
    val port = preferences[SERVER_PORT_KEY]
    val mode = preferences[AUTHENTICATION_MODE_KEY]

    return when (version) {
        LEGACY_CREDENTIAL_FORMAT_VERSION -> {
            if (username == null || password == null || host != null || port != null || mode != null) {
                StoredCredentialRead.Unavailable
            } else {
                StoredCredentialRead.Available(
                    StoredCredentialRecord.LegacyPassword(EncryptedCredentials(username, password)),
                )
            }
        }
        PROFILE_CREDENTIAL_FORMAT_VERSION -> decodeProfileRecord(host, port, mode, username, password)
        else -> StoredCredentialRead.Unavailable
    }
}

private fun decodeProfileRecord(
    host: String?,
    port: Int?,
    mode: String?,
    username: ByteArray?,
    password: ByteArray?,
): StoredCredentialRead {
    if (host == null || host.isEmpty() || host != host.trim() || port == null || port !in 1..65_535) {
        return StoredCredentialRead.Unavailable
    }
    val authenticationMode = StoredAuthenticationMode.entries
        .singleOrNull { candidate -> candidate.serializedName == mode }
        ?: return StoredCredentialRead.Unavailable
    val credentials = when (authenticationMode) {
        StoredAuthenticationMode.ANONYMOUS -> {
            if (username != null || password != null) return StoredCredentialRead.Unavailable
            null
        }
        StoredAuthenticationMode.PASSWORD -> {
            if (username == null || password == null) return StoredCredentialRead.Unavailable
            EncryptedCredentials(username, password)
        }
    }
    return StoredCredentialRead.Available(
        StoredCredentialRecord.Profile(host, checkNotNull(port), authenticationMode, credentials),
    )
}

private suspend fun <T> credentialResult(
    unavailable: T,
    operation: suspend () -> T,
): T = try {
    operation()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    unavailable
}

internal fun productionCredentialStorage(context: Context): CredentialStorage =
    DataStoreCredentialStorage((context.applicationContext ?: context).tvheadendCredentialDataStore)

internal val credentialOperationMutex = Mutex()
private const val LEGACY_CREDENTIAL_FORMAT_VERSION = 1
private const val PROFILE_CREDENTIAL_FORMAT_VERSION = 2
private const val KEY_ALIAS = "at.bernhardberger.tvheadend.sdk.credentials.v1"
internal val CREDENTIAL_VERSION_KEY = intPreferencesKey("format_version")
internal val ENCRYPTED_USERNAME_KEY = byteArrayPreferencesKey("encrypted_username")
internal val ENCRYPTED_PASSWORD_KEY = byteArrayPreferencesKey("encrypted_password")
internal val SERVER_HOST_KEY = stringPreferencesKey("server_host")
internal val SERVER_PORT_KEY = intPreferencesKey("server_port")
internal val AUTHENTICATION_MODE_KEY = stringPreferencesKey("authentication_mode")
private val KNOWN_CREDENTIAL_KEYS = setOf(
    CREDENTIAL_VERSION_KEY,
    ENCRYPTED_USERNAME_KEY,
    ENCRYPTED_PASSWORD_KEY,
    SERVER_HOST_KEY,
    SERVER_PORT_KEY,
    AUTHENTICATION_MODE_KEY,
)
private val CREDENTIAL_NAMESPACE = "at.bernhardberger.tvheadend.sdk.credentials".encodeToByteArray()
private val LEGACY_USERNAME_ASSOCIATED_DATA =
    "at.bernhardberger.tvheadend.sdk.credentials.username.v1".encodeToByteArray()
private val LEGACY_PASSWORD_ASSOCIATED_DATA =
    "at.bernhardberger.tvheadend.sdk.credentials.password.v1".encodeToByteArray()
private val Context.tvheadendCredentialDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tvheadend_sdk_credentials",
)
