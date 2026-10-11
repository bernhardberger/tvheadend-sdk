package at.bernhardberger.tvheadend.sdk.android

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import at.bernhardberger.tvheadend.sdk.core.ServerProfileAuthenticationMode
import at.bernhardberger.tvheadend.sdk.core.ServerProfileReadResult
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class TvheadendServerProfileStoreTest {
    @Test
    fun `anonymous profile round trip normalizes endpoint without consulting crypto`() = runTest {
        val storage = FakeCredentialStorage()
        val cipher = FakeCredentialCipher()
        val store = TvheadendServerProfileStore.create(storage, cipher)

        val stored = store.storeAnonymous("  test.invalid  ", 4_242)
            as ServerProfileReadResult.Available
        assertEquals("test.invalid", stored.host)
        assertEquals(4_242, stored.port)
        assertSame(ServerProfileAuthenticationMode.ANONYMOUS, stored.authenticationMode)
        assertEquals(0, storage.readCalls)
        val loaded = store.loadProfile()
        assertTrue(loaded is ServerProfileReadResult.Available)
        loaded as ServerProfileReadResult.Available
        assertEquals("test.invalid", loaded.host)
        assertEquals(4_242, loaded.port)
        assertSame(ServerProfileAuthenticationMode.ANONYMOUS, loaded.authenticationMode)
        assertEquals("ServerProfile(<redacted>)", loaded.profile.toString())
        assertEquals("ServerProfileReadResult.Available(<redacted>)", loaded.toString())
        assertEquals(0, cipher.encryptCalls)
        assertEquals(0, cipher.decryptCalls)
    }

    @Test
    fun `password profile round trip keeps exact password behind opaque profile`() = runTest {
        val storage = FakeCredentialStorage()
        val cipher = FakeCredentialCipher()
        val store = TvheadendServerProfileStore.create(storage, cipher)

        val stored = store.storePassword(
            host = " test.invalid ",
            port = 4_242,
            username = " user ",
            password = " exact password ",
        )
        assertTrue(stored is ServerProfileReadResult.Available)
        assertEquals(0, storage.readCalls)
        assertEquals("user", cipher.lastUsername)
        assertEquals(" exact password ", cipher.lastPassword)
        val loaded = store.loadProfile() as ServerProfileReadResult.Available
        assertEquals("test.invalid", loaded.host)
        assertEquals(4_242, loaded.port)
        assertSame(ServerProfileAuthenticationMode.PASSWORD, loaded.authenticationMode)
        assertEquals("ServerProfile(<redacted>)", loaded.profile.toString())
        assertFalse(loaded.toString().contains("user"))
        assertFalse(loaded.toString().contains("password"))
    }

    @Test
    fun `edit read distinguishes every state and preserves exact editable values`() = runTest {
        val missing = TvheadendServerProfileStore.create(
            FakeCredentialStorage(),
            FakeCredentialCipher(),
        ).loadProfileForEditing()
        val unavailable = TvheadendServerProfileStore.create(
            FakeCredentialStorage(StoredCredentialRead.Unavailable),
            FakeCredentialCipher(),
        ).loadProfileForEditing()
        val anonymousCipher = FakeCredentialCipher()
        val anonymous = TvheadendServerProfileStore.create(
            FakeCredentialStorage(
                StoredCredentialRead.Available(anonymousProfileRecord("edit.invalid", 4_242)),
            ),
            anonymousCipher,
        ).loadProfileForEditing()
        val passwordStore = TvheadendServerProfileStore.create(
            FakeCredentialStorage(),
            FakeCredentialCipher(),
        )
        assertTrue(
            passwordStore.storePassword(
                host = " edit.invalid ",
                port = 4_242,
                username = " edit-user ",
                password = " exact password ",
            ) is ServerProfileReadResult.Available,
        )
        val password = passwordStore.loadProfileForEditing()

        assertSame(ServerProfileEditReadResult.Missing, missing)
        assertSame(ServerProfileEditReadResult.Unavailable, unavailable)
        assertTrue(anonymous is ServerProfileEditReadResult.Anonymous)
        anonymous as ServerProfileEditReadResult.Anonymous
        assertEquals("edit.invalid", anonymous.host)
        assertEquals(4_242, anonymous.port)
        assertEquals("ServerProfileEditReadResult.Anonymous(<redacted>)", anonymous.toString())
        assertEquals(0, anonymousCipher.decryptCalls)
        assertTrue(password is ServerProfileEditReadResult.Password)
        password as ServerProfileEditReadResult.Password
        assertEquals("edit.invalid", password.host)
        assertEquals(4_242, password.port)
        assertEquals("edit-user", password.username)
        assertEquals(" exact password ", password.password)
        assertEquals("ServerProfileEditReadResult.Password(<redacted>)", password.toString())
        assertEquals(0, ServerProfileEditReadJavaConsumer.inspect(missing))
        assertEquals(1, ServerProfileEditReadJavaConsumer.inspect(unavailable))
        assertEquals(2, ServerProfileEditReadJavaConsumer.inspect(anonymous))
        assertEquals(3, ServerProfileEditReadJavaConsumer.inspect(password))
    }

    @Test
    fun `edit read rejects legacy and corrupt credentials without exposing failure details`() = runTest {
        val legacyCipher = FakeCredentialCipher()
        val legacy = TvheadendServerProfileStore.create(
            FakeCredentialStorage(decodeCredentialRecord(mutablePreferencesOf(CREDENTIAL_VERSION_KEY to 1))),
            legacyCipher,
        ).loadProfileForEditing()
        val failure = IllegalStateException("private decryption detail")
        val corrupt = TvheadendServerProfileStore.create(
            FakeCredentialStorage(StoredCredentialRead.Available(passwordProfileRecord())),
            FakeCredentialCipher().apply { decryptFailure = failure },
        ).loadProfileForEditing()
        val bound = passwordProfileRecord(host = "bound.invalid", port = 4_242)
        val endpointMismatch = TvheadendServerProfileStore.create(
            FakeCredentialStorage(
                StoredCredentialRead.Available(
                    StoredCredentialRecord.Profile(
                        host = "changed.invalid",
                        port = bound.port,
                        authenticationMode = bound.authenticationMode,
                        credentials = bound.credentials,
                    ),
                ),
            ),
            FakeCredentialCipher(),
        ).loadProfileForEditing()

        assertSame(ServerProfileEditReadResult.Unavailable, legacy)
        assertEquals(0, legacyCipher.decryptCalls)
        assertSame(ServerProfileEditReadResult.Unavailable, corrupt)
        assertSame(ServerProfileEditReadResult.Unavailable, endpointMismatch)
        assertFalse(corrupt.toString().contains(checkNotNull(failure.message)))
    }

    @Test
    fun `all input validation completes before storage and crypto`() {
        val storage = FakeCredentialStorage()
        val cipher = FakeCredentialCipher()
        val store = TvheadendServerProfileStore.create(storage, cipher)
        val invalidOperations = listOf<suspend () -> Unit>(
            { store.storeAnonymous("  ") },
            { store.storeAnonymous("test.invalid", 0) },
            { store.storeAnonymous("test.invalid", 65_536) },
            { store.storePassword("test.invalid", username = "  ", password = "password") },
            { store.storePassword("test.invalid", username = "user", password = "  ") },
        )

        invalidOperations.forEach { operation ->
            assertThrows(IllegalArgumentException::class.java) { runTest { operation() } }
        }
        assertEquals(0, storage.readCalls)
        assertEquals(0, storage.writeCalls)
        assertEquals(0, cipher.encryptCalls)
    }

    @Test
    fun `missing records are missing profiles without crypto`() = runTest {
        val cipher = FakeCredentialCipher()
        assertSame(
            ServerProfileReadResult.Missing,
            TvheadendServerProfileStore.create(FakeCredentialStorage(), cipher).loadProfile(),
        )
        assertEquals(0, cipher.decryptCalls)
    }

    @Test
    fun `malformed unknown and partial records fail closed`() {
        val partialV1 = mutablePreferencesOf(CREDENTIAL_VERSION_KEY to 1)
        val completeV1 = mutablePreferencesOf(
            CREDENTIAL_VERSION_KEY to 1,
            ENCRYPTED_USERNAME_KEY to byteArrayOf(1),
            ENCRYPTED_PASSWORD_KEY to byteArrayOf(2),
        )
        val partialV2 = mutablePreferencesOf(
            CREDENTIAL_VERSION_KEY to 2,
            SERVER_HOST_KEY to "test.invalid",
            SERVER_PORT_KEY to 9_982,
            AUTHENTICATION_MODE_KEY to "password",
            ENCRYPTED_USERNAME_KEY to byteArrayOf(1),
        )
        val damagedAnonymous = mutablePreferencesOf(
            CREDENTIAL_VERSION_KEY to 2,
            SERVER_HOST_KEY to "test.invalid",
            SERVER_PORT_KEY to 9_982,
            AUTHENTICATION_MODE_KEY to "anonymous",
            ENCRYPTED_USERNAME_KEY to byteArrayOf(1),
            ENCRYPTED_PASSWORD_KEY to byteArrayOf(2),
        )
        val unknownVersion = mutablePreferencesOf(CREDENTIAL_VERSION_KEY to 3)
        val unknownKey = mutablePreferencesOf(intPreferencesKey("unknown") to 1)

        listOf(partialV1, completeV1, partialV2, damagedAnonymous, unknownVersion, unknownKey).forEach { record ->
            assertSame(StoredCredentialRead.Unavailable, decodeCredentialRecord(record))
        }
    }

    @Test
    fun `absent key corruption and endpoint associated data mismatches are unavailable`() = runTest {
        val absentKeyCipher = FakeCredentialCipher().apply {
            decryptFailure = IllegalStateException("Credential key is unavailable")
        }
        val validRecord = passwordProfileRecord()
        assertSame(
            ServerProfileReadResult.Unavailable,
            TvheadendServerProfileStore.create(
                FakeCredentialStorage(StoredCredentialRead.Available(validRecord)),
                absentKeyCipher,
            ).loadProfile(),
        )

        val mismatches = listOf(
            StoredCredentialRecord.Profile(
                "changed.invalid",
                validRecord.port,
                StoredAuthenticationMode.PASSWORD,
                validRecord.credentials,
            ),
            StoredCredentialRecord.Profile(
                validRecord.host,
                4_242,
                StoredAuthenticationMode.PASSWORD,
                validRecord.credentials,
            ),
            StoredCredentialRecord.Profile(
                validRecord.host,
                validRecord.port,
                StoredAuthenticationMode.PASSWORD,
                EncryptedCredentials(
                    checkNotNull(validRecord.credentials).copyPassword(),
                    validRecord.credentials.copyUsername(),
                ),
            ),
        )
        mismatches.forEach { mismatch ->
            assertSame(
                ServerProfileReadResult.Unavailable,
                TvheadendServerProfileStore.create(
                    FakeCredentialStorage(StoredCredentialRead.Available(mismatch)),
                    FakeCredentialCipher(),
                ).loadProfile(),
            )
        }
    }

    @Test
    fun `format two associated data is versioned length delimited and field endpoint mode bound`() {
        val context = CredentialCipherContext.Profile(
            "test.invalid",
            9_982,
            StoredAuthenticationMode.PASSWORD,
        )
        val username = credentialAssociatedData(context, CredentialField.USERNAME)
        val password = credentialAssociatedData(context, CredentialField.PASSWORD)
        val anonymous = credentialAssociatedData(
            CredentialCipherContext.Profile("test.invalid", 9_982, StoredAuthenticationMode.ANONYMOUS),
            CredentialField.USERNAME,
        )
        val changedHost = credentialAssociatedData(
            CredentialCipherContext.Profile("other.invalid", 9_982, StoredAuthenticationMode.PASSWORD),
            CredentialField.USERNAME,
        )
        val changedPort = credentialAssociatedData(
            CredentialCipherContext.Profile("test.invalid", 4_242, StoredAuthenticationMode.PASSWORD),
            CredentialField.USERNAME,
        )

        val bytes = ByteBuffer.wrap(username)
        val namespace = ByteArray(bytes.int).also(bytes::get).decodeToString()
        assertEquals("at.bernhardberger.tvheadend.sdk.credentials", namespace)
        assertEquals(2, bytes.int)
        assertEquals(CredentialField.USERNAME.associatedDataCode, bytes.get())
        assertEquals("test.invalid", ByteArray(bytes.int).also(bytes::get).decodeToString())
        assertEquals(9_982, bytes.int)
        assertEquals(StoredAuthenticationMode.PASSWORD.associatedDataCode, bytes.get())
        assertFalse(bytes.hasRemaining())
        assertFalse(username.contentEquals(password))
        assertFalse(username.contentEquals(anonymous))
        assertFalse(username.contentEquals(changedHost))
        assertFalse(username.contentEquals(changedPort))
    }

    @Test
    fun `failed encryption or persistence preserves the previous profile`() = runTest {
        val original = StoredCredentialRead.Available(anonymousProfileRecord("stable.invalid", 4_242))
        val encryptStorage = FakeCredentialStorage(original)
        val encryptCipher = FakeCredentialCipher().apply {
            encryptFailure = IllegalStateException("private crypto detail")
        }
        val writeStorage = FakeCredentialStorage(original).apply {
            writeFailure = IllegalStateException("private storage detail")
        }

        assertSame(
            ServerProfileReadResult.Unavailable,
            TvheadendServerProfileStore.create(encryptStorage, encryptCipher)
                .storePassword("new.invalid", username = "user", password = "password"),
        )
        assertSame(original, encryptStorage.state)
        assertEquals(0, encryptStorage.writeCalls)
        assertSame(
            ServerProfileReadResult.Unavailable,
            TvheadendServerProfileStore.create(writeStorage, FakeCredentialCipher())
                .storeAnonymous("new.invalid"),
        )
        assertSame(original, writeStorage.state)
    }

    @Test
    fun `profile replacement and clear each use one atomic storage mutation`() = runTest {
        val storage = FakeCredentialStorage(
            StoredCredentialRead.Unavailable,
        )
        val store = TvheadendServerProfileStore.create(storage, FakeCredentialCipher())

        assertTrue(
            store.storePassword("test.invalid", username = "user", password = "password")
                is ServerProfileReadResult.Available,
        )
        assertEquals(1, storage.writeCalls)
        assertSame(ServerProfileReadResult.Missing, store.clearProfile())
        assertEquals(1, storage.clearCalls)
        assertSame(StoredCredentialRead.Missing, storage.state)
    }

    @Test
    fun `corrupt credential file reads as missing and remains clearable`(@TempDir directory: File) = runTest {
        val dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val unhandled = corruptCredentialStore(File(directory, "unhandled"), dataStoreScope, null)
            val handled = corruptCredentialStore(
                File(directory, "handled"),
                dataStoreScope,
                credentialCorruptionHandler,
            )

            assertSame(ServerProfileReadResult.Unavailable, unhandled.loadProfile())
            assertSame(ServerProfileReadResult.Unavailable, unhandled.clearProfile())
            assertSame(ServerProfileReadResult.Missing, handled.loadProfile())
            assertSame(ServerProfileReadResult.Missing, handled.clearProfile())
            assertTrue(handled.storeAnonymous("test.invalid") is ServerProfileReadResult.Available)
            assertTrue(handled.loadProfile() is ServerProfileReadResult.Available)
        } finally {
            dataStoreScope.cancel()
        }
    }

    @Test
    fun `profile cancellation propagates from crypto write read and clear`() {
        val cancellation = CancellationException("cancelled")
        val encryptCipher = FakeCredentialCipher().apply { encryptFailure = cancellation }
        val writeStorage = FakeCredentialStorage().apply { writeFailure = cancellation }
        val readStorage = FakeCredentialStorage().apply { readFailure = cancellation }
        val clearStorage = FakeCredentialStorage().apply { clearFailure = cancellation }
        val operations = listOf<suspend () -> Unit>(
            {
                TvheadendServerProfileStore.create(FakeCredentialStorage(), encryptCipher)
                    .storePassword("test.invalid", username = "user", password = "password")
            },
            {
                TvheadendServerProfileStore.create(writeStorage, FakeCredentialCipher())
                    .storeAnonymous("test.invalid")
            },
            { TvheadendServerProfileStore.create(readStorage, FakeCredentialCipher()).loadProfile() },
            {
                TvheadendServerProfileStore.create(readStorage, FakeCredentialCipher())
                    .loadProfileForEditing()
            },
            {
                TvheadendServerProfileStore.create(
                    FakeCredentialStorage(
                        StoredCredentialRead.Available(passwordProfileRecord()),
                    ),
                    FakeCredentialCipher().apply { decryptFailure = cancellation },
                ).loadProfileForEditing()
            },
            { TvheadendServerProfileStore.create(clearStorage, FakeCredentialCipher()).clearProfile() },
        )

        operations.forEach { operation ->
            val thrown = assertThrows(CancellationException::class.java) { runTest { operation() } }
            assertSame(cancellation, thrown)
        }
    }

    @Test
    fun `public profile API exposes only intended context and available constructors`() {
        val storeConstructors = TvheadendServerProfileStore::class.java.constructors
            .filterNot { constructor -> constructor.isSynthetic }
        val availableConstructors = ServerProfileReadResult.Available::class.java.constructors
            .filterNot { constructor -> constructor.isSynthetic }
        val availableProperties = ServerProfileReadResult.Available::class.java.methods
            .map { method -> method.name }

        assertEquals(1, storeConstructors.size)
        assertTrue(storeConstructors.single().parameterTypes.contentEquals(arrayOf(Context::class.java)))
        assertEquals(0, availableConstructors.size)
        assertTrue(availableProperties.containsAll(listOf("getProfile", "getHost", "getPort", "getAuthenticationMode")))
        assertFalse(availableProperties.any { name ->
            name.contains("username", ignoreCase = true) ||
                name.contains("password", ignoreCase = true) ||
                name.contains("cipher", ignoreCase = true) ||
                name.contains("alias", ignoreCase = true) ||
                name.contains("path", ignoreCase = true) ||
                name.contains("cause", ignoreCase = true)
        })
        assertNotEquals("test.invalid", ServerProfileReadResult.Unavailable.toString())
    }

    @Test
    fun `edit results expose no public construction or generated secret APIs`() {
        val anonymousMethods = ServerProfileEditReadResult.Anonymous::class.java.declaredMethods
            .filterNot { method -> method.isSynthetic }
            .mapTo(mutableSetOf()) { method -> method.name }
        val passwordMethods = ServerProfileEditReadResult.Password::class.java.declaredMethods
            .filterNot { method -> method.isSynthetic }
            .mapTo(mutableSetOf()) { method -> method.name }

        assertEquals(
            0,
            ServerProfileEditReadResult.Anonymous::class.java.constructors
                .count { constructor -> !constructor.isSynthetic },
        )
        assertEquals(
            0,
            ServerProfileEditReadResult.Password::class.java.constructors
                .count { constructor -> !constructor.isSynthetic },
        )
        assertEquals(setOf("getHost", "getPort", "toString"), anonymousMethods)
        assertEquals(
            setOf("getHost", "getPort", "getUsername", "getPassword", "toString"),
            passwordMethods,
        )
        assertFalse(anonymousMethods.any { method -> method.startsWith("component") || method == "copy" })
        assertFalse(passwordMethods.any { method -> method.startsWith("component") || method == "copy" })
        assertFalse(java.io.Serializable::class.java.isAssignableFrom(ServerProfileEditReadResult.Anonymous::class.java))
        assertFalse(java.io.Serializable::class.java.isAssignableFrom(ServerProfileEditReadResult.Password::class.java))
        assertFalse(
            android.os.Parcelable::class.java.isAssignableFrom(
                ServerProfileEditReadResult.Anonymous::class.java,
            ),
        )
        assertFalse(
            android.os.Parcelable::class.java.isAssignableFrom(
                ServerProfileEditReadResult.Password::class.java,
            ),
        )
    }
}

private fun corruptCredentialStore(
    directory: File,
    scope: CoroutineScope,
    corruptionHandler: ReplaceFileCorruptionHandler<Preferences>?,
): TvheadendServerProfileStore {
    val file = File(directory, "credentials.preferences_pb")
    directory.mkdirs()
    // A length-delimited field that claims five bytes but supplies one.
    file.writeBytes(byteArrayOf(0x0A, 0x05, 0x01))
    val dataStore = PreferenceDataStoreFactory.create(
        corruptionHandler = corruptionHandler,
        scope = scope,
        produceFile = { file },
    )
    return TvheadendServerProfileStore.create(DataStoreCredentialStorage(dataStore), FakeCredentialCipher())
}
