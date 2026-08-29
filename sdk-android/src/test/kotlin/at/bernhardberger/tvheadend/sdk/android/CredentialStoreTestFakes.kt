package at.bernhardberger.tvheadend.sdk.android

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
        username: String,
        password: String,
        context: CredentialCipherContext,
    ): EncryptedCredentials {
        encryptFailure?.let { failure -> throw failure }
        encryptCalls += 1
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
        decryptFailure?.let { failure -> throw failure }
        decryptCalls += 1
        lastContext = context
        val prefix = contextPrefix(context)
        val usernamePrefix = "$prefix|username|"
        val passwordPrefix = "$prefix|password|"
        val username = credentials.copyUsername().decodeToString()
        val password = credentials.copyPassword().decodeToString()
        check(username.startsWith(usernamePrefix)) { "Associated data mismatch" }
        check(password.startsWith(passwordPrefix)) { "Associated data mismatch" }
        return transform(
            username.removePrefix(usernamePrefix),
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
        "$prefix|username|$username".encodeToByteArray(),
        "$prefix|password|$password".encodeToByteArray(),
    )
}

internal fun legacyPasswordRecord(
    username: String = "test-user",
    password: String = "test-password",
): StoredCredentialRecord.LegacyPassword = StoredCredentialRecord.LegacyPassword(
    encryptedCredentials(username, password, CredentialCipherContext.Legacy),
)

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

private fun contextPrefix(context: CredentialCipherContext): String = when (context) {
    CredentialCipherContext.Legacy -> "v1"
    is CredentialCipherContext.Profile ->
        "v2:${context.host}:${context.port}:${context.authenticationMode.serializedName}"
}
