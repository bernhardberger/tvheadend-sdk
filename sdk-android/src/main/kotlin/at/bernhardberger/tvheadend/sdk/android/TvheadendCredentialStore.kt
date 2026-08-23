package at.bernhardberger.tvheadend.sdk.android

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import at.bernhardberger.tvheadend.sdk.core.ServerAuthentication
import com.google.crypto.tink.Aead
import com.google.crypto.tink.integration.android.AndroidKeystore
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.jvm.JvmSynthetic

/**
 * Persists the selected TVHeadend password authentication in Android private storage.
 *
 * Use this store from one application process; it does not coordinate Android multi-process access.
 */
public class TvheadendCredentialStore private constructor(
    private val storage: CredentialStorage,
    private val cipher: CredentialCipher,
) {
    /** Creates a credential store scoped to the application that owns [context]. */
    public constructor(context: Context) : this(
        storage = DataStoreCredentialStorage(
            (context.applicationContext ?: context).tvheadendCredentialDataStore,
        ),
        cipher = AndroidTinkCredentialCipher,
    )

    internal companion object {
        @JvmSynthetic
        internal fun create(
            storage: CredentialStorage,
            cipher: CredentialCipher,
        ): TvheadendCredentialStore = TvheadendCredentialStore(storage, cipher)
    }

    /** Loads opaque password authentication, or a typed safe state when none can be loaded. */
    public suspend fun loadPassword(): CredentialReadResult = try {
        when (val stored = storage.read()) {
            EncryptedCredentialRead.Missing -> CredentialReadResult.Missing
            EncryptedCredentialRead.Unavailable -> CredentialReadResult.Unavailable
            is EncryptedCredentialRead.Available -> CredentialReadResult.Available.create(
                cipher.decrypt(stored.credentials),
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CredentialReadResult.Unavailable
    }

    /**
     * Atomically replaces the stored password authentication after encrypting both fields.
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

        return credentialOperation {
            storage.write(cipher.encrypt(normalizedUsername, password))
        }
    }

    /** Removes stored password authentication in one atomic DataStore update. */
    public suspend fun clearPassword(): CredentialOperationResult = credentialOperation(storage::clear)
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
    suspend fun read(): EncryptedCredentialRead

    suspend fun write(credentials: EncryptedCredentials)

    suspend fun clear()
}

internal interface CredentialCipher {
    suspend fun encrypt(username: String, password: String): EncryptedCredentials

    suspend fun decrypt(credentials: EncryptedCredentials): ServerAuthentication.Password
}

internal sealed interface EncryptedCredentialRead {
    data object Missing : EncryptedCredentialRead

    data object Unavailable : EncryptedCredentialRead

    class Available(
        val credentials: EncryptedCredentials,
    ) : EncryptedCredentialRead
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
    override suspend fun read(): EncryptedCredentialRead {
        val preferences = dataStore.data.first()
        val version = preferences[CREDENTIAL_VERSION_KEY]
        val username = preferences[ENCRYPTED_USERNAME_KEY]
        val password = preferences[ENCRYPTED_PASSWORD_KEY]

        return when {
            version == null && username == null && password == null -> EncryptedCredentialRead.Missing
            version != CREDENTIAL_FORMAT_VERSION || username == null || password == null ->
                EncryptedCredentialRead.Unavailable
            else -> EncryptedCredentialRead.Available(
                EncryptedCredentials(username, password),
            )
        }
    }

    override suspend fun write(credentials: EncryptedCredentials) {
        dataStore.edit { preferences ->
            preferences[CREDENTIAL_VERSION_KEY] = CREDENTIAL_FORMAT_VERSION
            preferences[ENCRYPTED_USERNAME_KEY] = credentials.copyUsername()
            preferences[ENCRYPTED_PASSWORD_KEY] = credentials.copyPassword()
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences -> preferences.clear() }
    }
}

private object AndroidTinkCredentialCipher : CredentialCipher {
    private val mutex = Mutex()

    override suspend fun encrypt(username: String, password: String): EncryptedCredentials =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val aead = credentialAead(createIfMissing = true)
                val usernameBytes = username.encodeToByteArray()
                val passwordBytes = password.encodeToByteArray()
                try {
                    EncryptedCredentials(
                        encryptedUsername = aead.encrypt(usernameBytes, USERNAME_ASSOCIATED_DATA),
                        encryptedPassword = aead.encrypt(passwordBytes, PASSWORD_ASSOCIATED_DATA),
                    )
                } finally {
                    usernameBytes.fill(0)
                    passwordBytes.fill(0)
                }
            }
        }

    override suspend fun decrypt(
        credentials: EncryptedCredentials,
    ): ServerAuthentication.Password = withContext(Dispatchers.IO) {
        mutex.withLock {
            val aead = credentialAead(createIfMissing = false)
            var usernameBytes: ByteArray? = null
            var passwordBytes: ByteArray? = null
            try {
                usernameBytes = aead.decrypt(credentials.copyUsername(), USERNAME_ASSOCIATED_DATA)
                passwordBytes = aead.decrypt(credentials.copyPassword(), PASSWORD_ASSOCIATED_DATA)
                ServerAuthentication.Password(
                    username = usernameBytes.decodeToString(throwOnInvalidSequence = true),
                    password = passwordBytes.decodeToString(throwOnInvalidSequence = true),
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

private suspend fun credentialOperation(
    operation: suspend () -> Unit,
): CredentialOperationResult = try {
    operation()
    CredentialOperationResult.SUCCESS
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    CredentialOperationResult.UNAVAILABLE
}

private const val CREDENTIAL_FORMAT_VERSION = 1
private const val KEY_ALIAS = "at.bernhardberger.tvheadend.sdk.credentials.v1"
private val CREDENTIAL_VERSION_KEY = intPreferencesKey("format_version")
private val ENCRYPTED_USERNAME_KEY = byteArrayPreferencesKey("encrypted_username")
private val ENCRYPTED_PASSWORD_KEY = byteArrayPreferencesKey("encrypted_password")
private val USERNAME_ASSOCIATED_DATA =
    "at.bernhardberger.tvheadend.sdk.credentials.username.v1".encodeToByteArray()
private val PASSWORD_ASSOCIATED_DATA =
    "at.bernhardberger.tvheadend.sdk.credentials.password.v1".encodeToByteArray()
private val Context.tvheadendCredentialDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tvheadend_sdk_credentials",
)
