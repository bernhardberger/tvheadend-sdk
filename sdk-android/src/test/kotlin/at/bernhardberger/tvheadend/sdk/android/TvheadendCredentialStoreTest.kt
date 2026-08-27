@file:Suppress("DEPRECATION")

package at.bernhardberger.tvheadend.sdk.android

import android.content.Context
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class TvheadendCredentialStoreTest {
    @Test
    fun `legacy password round trip retains format one behavior`() = runTest {
        val storage = FakeCredentialStorage()
        val cipher = FakeCredentialCipher()
        val store = TvheadendCredentialStore.create(storage, cipher)

        assertSame(
            CredentialOperationResult.SUCCESS,
            store.storePassword("  test-user  ", " test-password "),
        )
        assertEquals("test-user", cipher.lastUsername)
        assertEquals(" test-password ", cipher.lastPassword)
        assertTrue(
            (storage.state as StoredCredentialRead.Available).record is
                StoredCredentialRecord.LegacyPassword,
        )
        val loaded = store.loadPassword()
        assertTrue(loaded is CredentialReadResult.Available)
        assertEquals(
            "CredentialReadResult.Available(<redacted>)",
            loaded.toString(),
        )
    }

    @Test
    fun `legacy load reads password profile and treats anonymous profile as missing`() = runTest {
        val cipher = FakeCredentialCipher()
        val passwordStorage = FakeCredentialStorage(
            StoredCredentialRead.Available(passwordProfileRecord()),
        )
        val anonymousStorage = FakeCredentialStorage(
            StoredCredentialRead.Available(anonymousProfileRecord()),
        )

        assertTrue(
            TvheadendCredentialStore.create(passwordStorage, cipher).loadPassword() is
                CredentialReadResult.Available,
        )
        assertSame(
            CredentialReadResult.Missing,
            TvheadendCredentialStore.create(anonymousStorage, cipher).loadPassword(),
        )
        assertEquals(1, cipher.decryptCalls)
    }

    @Test
    fun `legacy store updates profile authentication without changing endpoint`() = runTest {
        val storage = FakeCredentialStorage(
            StoredCredentialRead.Available(anonymousProfileRecord("stable.invalid", 4_242)),
        )
        val store = TvheadendCredentialStore.create(storage, FakeCredentialCipher())

        assertSame(
            CredentialOperationResult.SUCCESS,
            store.storePassword("new-user", "new-password"),
        )

        val record = (storage.state as StoredCredentialRead.Available).record as
            StoredCredentialRecord.Profile
        assertEquals("stable.invalid", record.host)
        assertEquals(4_242, record.port)
        assertSame(StoredAuthenticationMode.PASSWORD, record.authenticationMode)
        assertEquals(1, storage.writeCalls)
        assertTrue(store.loadPassword() is CredentialReadResult.Available)
    }

    @Test
    fun `legacy clear converts a profile to anonymous and clears a legacy record`() = runTest {
        val profileStorage = FakeCredentialStorage(
            StoredCredentialRead.Available(passwordProfileRecord("stable.invalid", 4_242)),
        )
        val legacyStorage = FakeCredentialStorage(
            StoredCredentialRead.Available(legacyPasswordRecord()),
        )

        assertSame(
            CredentialOperationResult.SUCCESS,
            TvheadendCredentialStore.create(profileStorage, FakeCredentialCipher()).clearPassword(),
        )
        val profile = (profileStorage.state as StoredCredentialRead.Available).record as
            StoredCredentialRecord.Profile
        assertEquals("stable.invalid", profile.host)
        assertEquals(4_242, profile.port)
        assertSame(StoredAuthenticationMode.ANONYMOUS, profile.authenticationMode)
        assertSame(
            CredentialOperationResult.SUCCESS,
            TvheadendCredentialStore.create(legacyStorage, FakeCredentialCipher()).clearPassword(),
        )
        assertSame(StoredCredentialRead.Missing, legacyStorage.state)
    }

    @Test
    fun `invalid legacy input fails before storage or encryption`() {
        val storage = FakeCredentialStorage()
        val cipher = FakeCredentialCipher()
        val store = TvheadendCredentialStore.create(storage, cipher)

        assertEquals(
            "Username must not be blank",
            assertThrows(IllegalArgumentException::class.java) {
                runTest { store.storePassword("  ", "test-password") }
            }.message,
        )
        assertEquals(
            "Password must not be blank",
            assertThrows(IllegalArgumentException::class.java) {
                runTest { store.storePassword("test-user", "  ") }
            }.message,
        )
        assertEquals(0, storage.readCalls)
        assertEquals(0, cipher.encryptCalls)
    }

    @Test
    fun `damaged legacy record fails closed and is not overwritten or cleared`() = runTest {
        val storage = FakeCredentialStorage(StoredCredentialRead.Unavailable)
        val store = TvheadendCredentialStore.create(storage, FakeCredentialCipher())

        assertSame(CredentialReadResult.Unavailable, store.loadPassword())
        assertSame(
            CredentialOperationResult.UNAVAILABLE,
            store.storePassword("test-user", "test-password"),
        )
        assertSame(CredentialOperationResult.UNAVAILABLE, store.clearPassword())
        assertSame(StoredCredentialRead.Unavailable, storage.state)
        assertEquals(0, storage.writeCalls)
        assertEquals(0, storage.clearCalls)
    }

    @Test
    fun `legacy mutations reject endpoint bound password damage and an absent key`() = runTest {
        val valid = passwordProfileRecord(host = "bound.invalid", port = 4_242)
        val mismatched = StoredCredentialRecord.Profile(
            host = "changed.invalid",
            port = valid.port,
            authenticationMode = StoredAuthenticationMode.PASSWORD,
            credentials = valid.credentials,
        )
        val mismatchedState = StoredCredentialRead.Available(mismatched)
        val storeStorage = FakeCredentialStorage(mismatchedState)
        val clearStorage = FakeCredentialStorage(mismatchedState)

        assertSame(
            CredentialOperationResult.UNAVAILABLE,
            TvheadendCredentialStore.create(storeStorage, FakeCredentialCipher())
                .storePassword("new-user", "new-password"),
        )
        assertSame(
            CredentialOperationResult.UNAVAILABLE,
            TvheadendCredentialStore.create(clearStorage, FakeCredentialCipher()).clearPassword(),
        )
        assertSame(mismatchedState, storeStorage.state)
        assertSame(mismatchedState, clearStorage.state)
        assertEquals(0, storeStorage.writeCalls)
        assertEquals(0, clearStorage.writeCalls)

        val validState = StoredCredentialRead.Available(valid)
        val absentKeyStoreStorage = FakeCredentialStorage(validState)
        val absentKeyClearStorage = FakeCredentialStorage(validState)
        val absentKeyCipher = FakeCredentialCipher().apply {
            decryptFailure = IllegalStateException("Credential key is unavailable")
        }
        assertSame(
            CredentialOperationResult.UNAVAILABLE,
            TvheadendCredentialStore.create(absentKeyStoreStorage, absentKeyCipher)
                .storePassword("new-user", "new-password"),
        )
        assertSame(
            CredentialOperationResult.UNAVAILABLE,
            TvheadendCredentialStore.create(absentKeyClearStorage, absentKeyCipher).clearPassword(),
        )
        assertSame(StoredAuthenticationMode.PASSWORD, valid.authenticationMode)
        assertEquals(0, absentKeyCipher.encryptCalls)
        assertEquals(0, absentKeyStoreStorage.writeCalls)
        assertEquals(0, absentKeyClearStorage.writeCalls)
    }

    @Test
    fun `legacy failures are typed redacted and preserve the prior record`() = runTest {
        val original = StoredCredentialRead.Available(legacyPasswordRecord())
        val storage = FakeCredentialStorage(original).apply {
            writeFailure = IllegalStateException("private storage detail")
        }
        val cipher = FakeCredentialCipher()
        val result = TvheadendCredentialStore.create(storage, cipher)
            .storePassword("test-user", "test-password")

        assertSame(CredentialOperationResult.UNAVAILABLE, result)
        assertSame(original, storage.state)
        assertFalse(result.toString().contains("private"))
    }

    @Test
    fun `legacy cancellation propagates from read crypto write and clear`() {
        val cancellation = CancellationException("cancelled")
        val readStorage = FakeCredentialStorage().apply { readFailure = cancellation }
        val encryptCipher = FakeCredentialCipher().apply { encryptFailure = cancellation }
        val writeStorage = FakeCredentialStorage().apply { writeFailure = cancellation }
        val clearStorage = FakeCredentialStorage().apply { clearFailure = cancellation }
        val decryptCipher = FakeCredentialCipher().apply { decryptFailure = cancellation }

        listOf<suspend () -> Unit>(
            { TvheadendCredentialStore.create(readStorage, FakeCredentialCipher()).loadPassword() },
            {
                TvheadendCredentialStore.create(FakeCredentialStorage(), encryptCipher)
                    .storePassword("test-user", "test-password")
            },
            {
                TvheadendCredentialStore.create(writeStorage, FakeCredentialCipher())
                    .storePassword("test-user", "test-password")
            },
            {
                TvheadendCredentialStore.create(
                    FakeCredentialStorage(
                        StoredCredentialRead.Available(passwordProfileRecord()),
                    ),
                    decryptCipher,
                ).clearPassword()
            },
            { TvheadendCredentialStore.create(clearStorage, FakeCredentialCipher()).clearPassword() },
        ).forEach { operation ->
            val thrown = assertThrows(CancellationException::class.java) { runTest { operation() } }
            assertSame(cancellation, thrown)
        }
    }

    @Test
    fun `legacy and profile stores serialize against the same mutex`() = runTest {
        val operationMutex = Mutex()
        val storage = FakeCredentialStorage()
        val enteredEncryption = CompletableDeferred<Unit>()
        val releaseEncryption = CompletableDeferred<Unit>()
        val cipher = object : FakeCredentialCipher() {
            override suspend fun encrypt(
                username: String,
                password: String,
                context: CredentialCipherContext,
            ): EncryptedCredentials {
                enteredEncryption.complete(Unit)
                releaseEncryption.await()
                return super.encrypt(username, password, context)
            }
        }
        val profileStore = TvheadendServerProfileStore.create(storage, cipher, operationMutex)
        val legacyStore = TvheadendCredentialStore.create(storage, cipher, operationMutex)

        val profileWrite = async {
            profileStore.storePassword("test.invalid", username = "user", password = "password")
        }
        enteredEncryption.await()
        val legacyClear = async { legacyStore.clearPassword() }
        assertEquals(0, storage.readCalls)

        releaseEncryption.complete(Unit)
        assertSame(ServerProfileOperationResult.SUCCESS, profileWrite.await())
        assertSame(CredentialOperationResult.SUCCESS, legacyClear.await())
        val record = (storage.state as StoredCredentialRead.Available).record as
            StoredCredentialRecord.Profile
        assertSame(StoredAuthenticationMode.ANONYMOUS, record.authenticationMode)
    }

    @Test
    fun `only the original context constructor remains visible`() {
        val constructors = TvheadendCredentialStore::class.java.constructors
            .filterNot { constructor -> constructor.isSynthetic }

        assertEquals(1, constructors.size)
        assertTrue(constructors.single().parameterTypes.contentEquals(arrayOf(Context::class.java)))
        assertEquals(
            0,
            CredentialReadResult.Available::class.java.constructors
                .count { constructor -> !constructor.isSynthetic },
        )
    }
}
