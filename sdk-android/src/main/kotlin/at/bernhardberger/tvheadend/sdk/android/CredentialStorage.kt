package at.bernhardberger.tvheadend.sdk.android

import android.content.Context
import at.bernhardberger.tvheadend.sdk.core.ServerProfileId
import at.bernhardberger.tvheadend.sdk.core.ServerProfileSummary
import at.bernhardberger.tvheadend.sdk.core.ServerProfileAuthenticationMode
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.integration.android.AndroidKeystore
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    class Profile(
        val host: String,
        val port: Int,
        val authenticationMode: StoredAuthenticationMode,
        val id: ServerProfileId? = null,
    ) : CredentialCipherContext
}

internal sealed interface StoredCredentialRead {
    data object Missing : StoredCredentialRead

    data object Unavailable : StoredCredentialRead

    class Available(val record: StoredCredentialRecord) : StoredCredentialRead
}

internal sealed interface StoredCredentialRecord {
    class Collection(
        val profiles: List<Profile>,
        val activeId: ServerProfileId?,
    ) : StoredCredentialRecord {
        override fun toString(): String = "StoredCredentialRecord.Collection(<redacted>)"
    }

    class Profile(
        val host: String,
        val port: Int,
        val authenticationMode: StoredAuthenticationMode,
        val credentials: EncryptedCredentials?,
        val id: ServerProfileId? = null,
        val label: String? = null,
        val credentialsUnavailable: Boolean = false,
    ) : StoredCredentialRecord {
        init {
            require(
                if (authenticationMode == StoredAuthenticationMode.PASSWORD) {
                    (credentials != null) != credentialsUnavailable
                } else {
                    credentials == null && !credentialsUnavailable
                },
            )
        }

        fun cipherContext(): CredentialCipherContext.Profile = CredentialCipherContext.Profile(
            host = host,
            port = port,
            authenticationMode = authenticationMode,
            id = id,
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

internal class DataStoreCredentialStorage(
    private val dataStore: DataStore<Preferences>,
) : CredentialStorage {
    override suspend fun read(): StoredCredentialRead = decodeCredentialRecord(dataStore.data.first())

    override suspend fun write(record: StoredCredentialRecord) {
        dataStore.edit { preferences ->
            preferences.clear()
            when (record) {
                is StoredCredentialRecord.Collection -> {
                    preferences[CREDENTIAL_VERSION_KEY] = PROFILE_COLLECTION_FORMAT_VERSION
                    preferences[PROFILE_ORDER_KEY] = record.profiles.joinToString("\n") { checkNotNull(it.id).value }
                    record.activeId?.let { preferences[ACTIVE_PROFILE_KEY] = it.value }
                    record.profiles.forEach { profile ->
                        val prefix = "profile.${checkNotNull(profile.id).value}."
                        preferences[stringPreferencesKey(prefix + "host")] = profile.host
                        preferences[intPreferencesKey(prefix + "port")] = profile.port
                        preferences[stringPreferencesKey(prefix + "mode")] = profile.authenticationMode.serializedName
                        profile.label?.let { preferences[stringPreferencesKey(prefix + "label")] = it }
                        if (profile.credentialsUnavailable) {
                            preferences[stringPreferencesKey(prefix + "credentials_unavailable")] = "true"
                        }
                        profile.credentials?.let {
                            preferences[byteArrayPreferencesKey(prefix + "username")] = it.copyUsername()
                            preferences[byteArrayPreferencesKey(prefix + "password")] = it.copyPassword()
                        }
                    }
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
    is CredentialCipherContext.Profile -> {
        val hostBytes = context.host.encodeToByteArray()
        val idBytes = context.id?.value?.encodeToByteArray()
        ByteBuffer.allocate(
            Int.SIZE_BYTES + CREDENTIAL_NAMESPACE.size +
                Int.SIZE_BYTES + Byte.SIZE_BYTES +
                Int.SIZE_BYTES + hostBytes.size +
                Int.SIZE_BYTES + Byte.SIZE_BYTES +
                (if (idBytes == null) 0 else Int.SIZE_BYTES + idBytes.size),
        )
            .putInt(CREDENTIAL_NAMESPACE.size)
            .put(CREDENTIAL_NAMESPACE)
            .putInt(if (idBytes == null) PROFILE_CREDENTIAL_FORMAT_VERSION else PROFILE_COLLECTION_FORMAT_VERSION)
            .put(field.associatedDataCode)
            .putInt(hostBytes.size)
            .put(hostBytes)
            .putInt(context.port)
            .put(context.authenticationMode.associatedDataCode)
            .apply { if (idBytes != null) putInt(idBytes.size).put(idBytes) }
            .array()
    }
}

internal fun decodeCredentialRecord(preferences: Preferences): StoredCredentialRead {
    return try {
        decodeStoredPreferences(preferences)
    } catch (_: IllegalArgumentException) {
        StoredCredentialRead.Unavailable
    } catch (_: ClassCastException) {
        StoredCredentialRead.Unavailable
    }
}

private fun decodeStoredPreferences(preferences: Preferences): StoredCredentialRead {
    val storedKeys = preferences.asMap().keys
    if (storedKeys.isEmpty()) return StoredCredentialRead.Missing
    if (preferences[CREDENTIAL_VERSION_KEY] == PROFILE_COLLECTION_FORMAT_VERSION) {
        return decodeCollection(preferences)
    }
    if (!KNOWN_CREDENTIAL_KEYS.containsAll(storedKeys)) return StoredCredentialRead.Unavailable

    val version = preferences[CREDENTIAL_VERSION_KEY]
    val username = preferences[ENCRYPTED_USERNAME_KEY]
    val password = preferences[ENCRYPTED_PASSWORD_KEY]
    val host = preferences[SERVER_HOST_KEY]
    val port = preferences[SERVER_PORT_KEY]
    val mode = preferences[AUTHENTICATION_MODE_KEY]

    return when (version) {
        PROFILE_CREDENTIAL_FORMAT_VERSION -> decodeProfileRecord(host, port, mode, username, password)
        else -> StoredCredentialRead.Unavailable
    }
}

private fun decodeCollection(preferences: Preferences): StoredCredentialRead {
    val order = preferences[PROFILE_ORDER_KEY] ?: return StoredCredentialRead.Unavailable
    val ids = if (order.isEmpty()) emptyList() else order.split('\n').map(ServerProfileId::from)
    if (ids.distinct().size != ids.size) return StoredCredentialRead.Unavailable
    val active = preferences[ACTIVE_PROFILE_KEY]?.let(ServerProfileId::from)
    if (active != null && active !in ids) return StoredCredentialRead.Unavailable
    val knownKeys = mutableSetOf<Preferences.Key<*>>(CREDENTIAL_VERSION_KEY, PROFILE_ORDER_KEY, ACTIVE_PROFILE_KEY)
    val records = ids.map { id ->
        val prefix = "profile.${id.value}."
        val hostKey = stringPreferencesKey(prefix + "host")
        val portKey = intPreferencesKey(prefix + "port")
        val modeKey = stringPreferencesKey(prefix + "mode")
        val labelKey = stringPreferencesKey(prefix + "label")
        val unavailableKey = stringPreferencesKey(prefix + "credentials_unavailable")
        val usernameKey = byteArrayPreferencesKey(prefix + "username")
        val passwordKey = byteArrayPreferencesKey(prefix + "password")
        knownKeys += listOf(hostKey, portKey, modeKey, labelKey, unavailableKey, usernameKey, passwordKey)
        val host = preferences[hostKey] ?: return StoredCredentialRead.Unavailable
        val port = preferences[portKey] ?: return StoredCredentialRead.Unavailable
        val mode = StoredAuthenticationMode.entries.singleOrNull { it.serializedName == preferences[modeKey] }
            ?: return StoredCredentialRead.Unavailable
        val label = preferences[labelKey]
        val summary = ServerProfileSummary.create(
            id, label, host, port,
            if (mode == StoredAuthenticationMode.ANONYMOUS) ServerProfileAuthenticationMode.ANONYMOUS
            else ServerProfileAuthenticationMode.PASSWORD,
        )
        if (summary.host != host || summary.label != label) return StoredCredentialRead.Unavailable
        val marker = preferences[unavailableKey]
        if (marker != null && marker != "true") return StoredCredentialRead.Unavailable
        val username = preferences[usernameKey]
        val password = preferences[passwordKey]
        val credentials = when {
            username == null && password == null -> null
            username != null && password != null -> EncryptedCredentials(username, password)
            else -> return StoredCredentialRead.Unavailable
        }
        StoredCredentialRecord.Profile(host, port, mode, credentials, id, label, marker != null)
    }
    if (!knownKeys.containsAll(preferences.asMap().keys)) return StoredCredentialRead.Unavailable
    return StoredCredentialRead.Available(StoredCredentialRecord.Collection(records, active))
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

internal fun productionCredentialStorage(context: Context): CredentialStorage =
    DataStoreCredentialStorage((context.applicationContext ?: context).tvheadendCredentialDataStore)

internal val credentialOperationMutex = Mutex()
private const val PROFILE_CREDENTIAL_FORMAT_VERSION = 2
internal const val PROFILE_COLLECTION_FORMAT_VERSION = 3
// Formats 2 and 3 share this key; the alias is not the record format version.
private const val KEY_ALIAS = "at.bernhardberger.tvheadend.sdk.credentials.v1"
internal val CREDENTIAL_VERSION_KEY = intPreferencesKey("format_version")
internal val ENCRYPTED_USERNAME_KEY = byteArrayPreferencesKey("encrypted_username")
internal val ENCRYPTED_PASSWORD_KEY = byteArrayPreferencesKey("encrypted_password")
internal val SERVER_HOST_KEY = stringPreferencesKey("server_host")
internal val SERVER_PORT_KEY = intPreferencesKey("server_port")
internal val AUTHENTICATION_MODE_KEY = stringPreferencesKey("authentication_mode")
internal val PROFILE_ORDER_KEY = stringPreferencesKey("profile_order")
internal val ACTIVE_PROFILE_KEY = stringPreferencesKey("active")
private val KNOWN_CREDENTIAL_KEYS = setOf(
    CREDENTIAL_VERSION_KEY,
    ENCRYPTED_USERNAME_KEY,
    ENCRYPTED_PASSWORD_KEY,
    SERVER_HOST_KEY,
    SERVER_PORT_KEY,
    AUTHENTICATION_MODE_KEY,
)
private val CREDENTIAL_NAMESPACE = "at.bernhardberger.tvheadend.sdk.credentials".encodeToByteArray()

// Preserve an unavailable marker after corruption, while allowing an atomic clear to recover.
internal val credentialCorruptionHandler: ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.mutablePreferencesOf(CREDENTIAL_VERSION_KEY to -1) }

private val Context.tvheadendCredentialDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tvheadend_sdk_credentials",
    corruptionHandler = credentialCorruptionHandler,
)
