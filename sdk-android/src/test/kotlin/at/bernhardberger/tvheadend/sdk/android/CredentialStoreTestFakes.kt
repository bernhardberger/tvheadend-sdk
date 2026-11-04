package at.bernhardberger.tvheadend.sdk.android

import at.bernhardberger.tvheadend.sdk.core.ServerProfileId
import at.bernhardberger.tvheadend.sdk.core.ServerProfileWriteResult

internal class FakeCredentialStorage(
    initialState: StoredCredentialRead = StoredCredentialRead.Missing,
) : CredentialStorage {
    internal var state: StoredCredentialRead = initialState
        private set
    internal var readFailure: Exception? = null
    internal var writeFailure: Exception? = null
    internal var clearFailure: Exception? = null
    internal var readCalls: Int = 0
        private set
    internal var writeCalls: Int = 0
        private set
    internal var clearCalls: Int = 0
        private set

    override suspend fun read(): StoredCredentialRead {
        readFailure?.let { failure -> throw failure }
        readCalls += 1
        return state
    }

    override suspend fun write(record: StoredCredentialRecord) {
        writeFailure?.let { failure -> throw failure }
        writeCalls += 1
        state = StoredCredentialRead.Available(record)
    }

    override suspend fun updateLabel(id: ServerProfileId, label: String?): ServerProfileWriteResult {
        val normalizedLabel = at.bernhardberger.tvheadend.sdk.core.ServerProfileSummary.normalizeLabel(label)
        val stored = read()
        if (stored == StoredCredentialRead.Unavailable) return ServerProfileWriteResult.Unavailable
        val collection = (stored as? StoredCredentialRead.Available)?.record as? StoredCredentialRecord.Collection
            ?: return ServerProfileWriteResult.NotFound
        val old = collection.profiles.singleOrNull { it.id == id } ?: return ServerProfileWriteResult.NotFound
        val replacement = StoredCredentialRecord.Profile(old.host, old.port, old.authenticationMode,
            old.credentials, old.id, normalizedLabel, old.credentialsUnavailable, old.username)
        write(StoredCredentialRecord.Collection(collection.profiles.map { if (it.id == id) replacement else it }, collection.activeId))
        return ServerProfileWriteResult.Applied(id)
    }

    override suspend fun clear() {
        clearFailure?.let { failure -> throw failure }
        clearCalls += 1
        state = StoredCredentialRead.Missing
    }
}

internal open class FakeCredentialCipher : CredentialCipher {
    internal var encryptFailure: Exception? = null
    internal var decryptFailure: Exception? = null
    internal var encryptCalls: Int = 0
        private set
    internal var decryptCalls: Int = 0
        private set
    internal var lastUsername: String? = null
        private set
    internal var lastPassword: String? = null
        private set
    internal var lastContext: CredentialCipherContext? = null
        private set

    override suspend fun encrypt(
        password: String,
        context: CredentialCipherContext,
    ): EncryptedCredentials {
        encryptFailure?.let { failure -> throw failure }
        encryptCalls += 1
        val username = checkNotNull((context as CredentialCipherContext.Profile).username)
        lastUsername = username
        lastPassword = password
        lastContext = context
        return encryptedCredentials(username, password, context)
    }

    override suspend fun <T> decrypt(
        credentials: EncryptedCredentials,
        context: CredentialCipherContext,
        transform: (username: String, password: String) -> T,
    ): T {
        val profile = context as CredentialCipherContext.Profile
        if (profile.id == null) validateCredentialCiphertext(credentials.copyUsername())
        validateCredentialCiphertext(credentials.copyPassword())
        decryptFailure?.let { failure -> throw failure }
        decryptCalls += 1
        lastContext = context
        val prefix = contextPrefix(context)
        val usernamePrefix = "$prefix|username|"
        val passwordPrefix = "$prefix|password|"
        val username = credentials.copyUsername().decodeToString()
        val password = credentials.copyPassword().decodeToString()
        if ((profile.id == null && !username.startsWith(usernamePrefix)) || !password.startsWith(passwordPrefix)) {
            throw CredentialUnreadableException()
        }
        return transform(
            if (profile.id == null) username.removePrefix(usernamePrefix) else checkNotNull(profile.username),
            password.removePrefix(passwordPrefix),
        )
    }
}

internal fun encryptedCredentials(
    username: String,
    password: String,
    context: CredentialCipherContext,
): EncryptedCredentials {
    val prefix = contextPrefix(context)
    return EncryptedCredentials(
        if ((context as CredentialCipherContext.Profile).id == null) "$prefix|username|$username".encodeToByteArray() else byteArrayOf(),
        "$prefix|password|$password".encodeToByteArray(),
    )
}

internal fun passwordProfileRecord(
    host: String = "test.invalid",
    port: Int = 9_982,
    username: String = "test-user",
    password: String = "test-password",
): StoredCredentialRecord.Profile {
    val context = CredentialCipherContext.Profile(host, port, StoredAuthenticationMode.PASSWORD)
    return StoredCredentialRecord.Profile(
        host = host,
        port = port,
        authenticationMode = StoredAuthenticationMode.PASSWORD,
        credentials = encryptedCredentials(username, password, context),
    )
}

internal fun anonymousProfileRecord(
    host: String = "test.invalid",
    port: Int = 9_982,
): StoredCredentialRecord.Profile = StoredCredentialRecord.Profile(
    host = host,
    port = port,
    authenticationMode = StoredAuthenticationMode.ANONYMOUS,
    credentials = null,
)

private fun contextPrefix(context: CredentialCipherContext): String =
    java.util.Base64.getEncoder().encodeToString(credentialAssociatedData(context, CredentialField.PASSWORD))
