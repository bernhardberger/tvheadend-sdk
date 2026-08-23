package at.bernhardberger.tvheadend.sdk.android

import android.content.Context
import at.bernhardberger.tvheadend.sdk.core.ServerAuthentication
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class TvheadendCredentialStoreTest {
    @Test
    fun `store normalizes username and atomically replaces encrypted fields`() = runTest {
        val storage = FakeCredentialStorage()
        val cipher = FakeCredentialCipher()
        val store = TvheadendCredentialStore.create(storage, cipher)

        val result = store.storePassword("  test-user  ", " test-password ")

        assertSame(CredentialOperationResult.SUCCESS, result)
        assertTrue(cipher.receivedNormalizedUsername)
        assertTrue(cipher.receivedExactPassword)
        assertTrue(storage.state is EncryptedCredentialRead.Available)
        assertEquals(1, storage.writeCalls)
    }

    @Test
    fun `invalid credential input fails before encryption with safe messages`() {
        val cipher = FakeCredentialCipher()
        val store = TvheadendCredentialStore.create(FakeCredentialStorage(), cipher)

        val usernameFailure = assertThrows(IllegalArgumentException::class.java) {
            runTest { store.storePassword("  ", "test-password") }
        }
        val passwordFailure = assertThrows(IllegalArgumentException::class.java) {
            runTest { store.storePassword("test-user", "  ") }
        }

        assertEquals("Username must not be blank", usernameFailure.message)
        assertEquals("Password must not be blank", passwordFailure.message)
        assertEquals(0, cipher.encryptCalls)
    }

    @Test
    fun `missing credentials do not create or consult a key`() = runTest {
        val cipher = FakeCredentialCipher()
        val store = TvheadendCredentialStore.create(FakeCredentialStorage(), cipher)

        val result = store.loadPassword()

        assertSame(CredentialReadResult.Missing, result)
        assertEquals(0, cipher.decryptCalls)
    }

    @Test
    fun `available credentials remain opaque and redacted`() = runTest {
        val cipher = FakeCredentialCipher()
        val store = TvheadendCredentialStore.create(
            FakeCredentialStorage(EncryptedCredentialRead.Available(cipher.encrypted)),
            cipher,
        )

        val result = store.loadPassword()

        assertTrue(result is CredentialReadResult.Available)
        val available = result as CredentialReadResult.Available
        assertTrue(
            available.toString() == "CredentialReadResult.Available(<redacted>)",
            "Credential result rendering was not redacted",
        )
        assertTrue(
            available.authentication.toString() == "ServerAuthentication.Password(<redacted>)",
            "Authentication rendering was not redacted",
        )
        assertTrue(
            cipher.encrypted.toString() == "EncryptedCredentials(<redacted>)",
            "Encrypted credential rendering was not redacted",
        )
    }

    @Test
    fun `partial or unknown stored records fail closed without decryption`() = runTest {
        val cipher = FakeCredentialCipher()
        val store = TvheadendCredentialStore.create(
            FakeCredentialStorage(EncryptedCredentialRead.Unavailable),
            cipher,
        )

        val result = store.loadPassword()

        assertSame(CredentialReadResult.Unavailable, result)
        assertEquals(0, cipher.decryptCalls)
    }

    @Test
    fun `read and decryption failures become one safe unavailable result`() = runTest {
        val readStorage = FakeCredentialStorage().apply {
            readFailure = IllegalStateException("private storage detail")
        }
        val decryptCipher = FakeCredentialCipher().apply {
            decryptFailure = IllegalStateException("private crypto detail")
        }

        assertSame(
            CredentialReadResult.Unavailable,
            TvheadendCredentialStore.create(readStorage, FakeCredentialCipher()).loadPassword(),
        )
        assertSame(
            CredentialReadResult.Unavailable,
            TvheadendCredentialStore.create(
                FakeCredentialStorage(EncryptedCredentialRead.Available(decryptCipher.encrypted)),
                decryptCipher,
            ).loadPassword(),
        )
        assertFalse(CredentialReadResult.Unavailable.toString().contains("private"))
    }

    @Test
    fun `failed encryption leaves the previous record untouched`() = runTest {
        val cipher = FakeCredentialCipher().apply {
            encryptFailure = IllegalStateException("private crypto detail")
        }
        val original = EncryptedCredentialRead.Available(cipher.encrypted)
        val storage = FakeCredentialStorage(original)
        val store = TvheadendCredentialStore.create(storage, cipher)

        val result = store.storePassword("test-user", "test-password")

        assertSame(CredentialOperationResult.UNAVAILABLE, result)
        assertSame(original, storage.state)
        assertEquals(0, storage.writeCalls)
    }

    @Test
    fun `failed persistence is typed and does not disclose its cause`() = runTest {
        val storage = FakeCredentialStorage().apply {
            writeFailure = IllegalStateException("private storage detail")
        }
        val store = TvheadendCredentialStore.create(storage, FakeCredentialCipher())

        val result = store.storePassword("test-user", "test-password")

        assertSame(CredentialOperationResult.UNAVAILABLE, result)
        assertFalse(result.toString().contains("private"))
    }

    @Test
    fun `clear removes the complete record without consulting crypto`() = runTest {
        val cipher = FakeCredentialCipher()
        val storage = FakeCredentialStorage(EncryptedCredentialRead.Available(cipher.encrypted))
        val store = TvheadendCredentialStore.create(storage, cipher)

        val result = store.clearPassword()

        assertSame(CredentialOperationResult.SUCCESS, result)
        assertSame(EncryptedCredentialRead.Missing, storage.state)
        assertEquals(0, cipher.decryptCalls)
        assertEquals(0, cipher.encryptCalls)
    }

    @Test
    fun `cancellation propagates from every suspending boundary`() {
        val encryptCancellation = CancellationException("cancelled")
        val encryptCipher = FakeCredentialCipher().apply {
            encryptFailure = encryptCancellation
        }
        val thrownFromStore = assertThrows(CancellationException::class.java) {
            runTest {
                TvheadendCredentialStore.create(FakeCredentialStorage(), encryptCipher)
                    .storePassword("test-user", "test-password")
            }
        }

        val writeCancellation = CancellationException("cancelled")
        val writeStorage = FakeCredentialStorage().apply { writeFailure = writeCancellation }
        val thrownFromWrite = assertThrows(CancellationException::class.java) {
            runTest {
                TvheadendCredentialStore.create(writeStorage, FakeCredentialCipher())
                    .storePassword("test-user", "test-password")
            }
        }

        val readCancellation = CancellationException("cancelled")
        val readStorage = FakeCredentialStorage().apply { readFailure = readCancellation }
        val thrownFromRead = assertThrows(CancellationException::class.java) {
            runTest {
                TvheadendCredentialStore.create(readStorage, FakeCredentialCipher()).loadPassword()
            }
        }

        val decryptCancellation = CancellationException("cancelled")
        val decryptCipher = FakeCredentialCipher().apply { decryptFailure = decryptCancellation }
        val thrownFromDecrypt = assertThrows(CancellationException::class.java) {
            runTest {
                TvheadendCredentialStore.create(
                    FakeCredentialStorage(EncryptedCredentialRead.Available(decryptCipher.encrypted)),
                    decryptCipher,
                ).loadPassword()
            }
        }

        val clearCancellation = CancellationException("cancelled")
        val clearStorage = FakeCredentialStorage().apply { clearFailure = clearCancellation }
        val thrownFromClear = assertThrows(CancellationException::class.java) {
            runTest {
                TvheadendCredentialStore.create(clearStorage, FakeCredentialCipher()).clearPassword()
            }
        }

        assertSame(encryptCancellation, thrownFromStore)
        assertSame(writeCancellation, thrownFromWrite)
        assertSame(readCancellation, thrownFromRead)
        assertSame(decryptCancellation, thrownFromDecrypt)
        assertSame(clearCancellation, thrownFromClear)
    }

    @Test
    fun `only intended constructors are visible to Java source`() {
        val storeConstructors = TvheadendCredentialStore::class.java.constructors
            .filterNot { constructor -> constructor.isSynthetic }
        val availableConstructors = CredentialReadResult.Available::class.java.constructors
            .filterNot { constructor -> constructor.isSynthetic }

        assertEquals(1, storeConstructors.size)
        assertTrue(
            storeConstructors.single().parameterTypes.contentEquals(arrayOf(Context::class.java)),
            "Credential store exposed a non-Context constructor",
        )
        assertEquals(0, availableConstructors.size)
    }
}

private class FakeCredentialStorage(
    initialState: EncryptedCredentialRead = EncryptedCredentialRead.Missing,
) : CredentialStorage {
    internal var state: EncryptedCredentialRead = initialState
        private set
    internal var readFailure: Exception? = null
    internal var writeFailure: Exception? = null
    internal var clearFailure: Exception? = null
    internal var writeCalls: Int = 0
        private set

    override suspend fun read(): EncryptedCredentialRead {
        readFailure?.let { failure -> throw failure }
        return state
    }

    override suspend fun write(credentials: EncryptedCredentials) {
        writeFailure?.let { failure -> throw failure }
        writeCalls += 1
        state = EncryptedCredentialRead.Available(credentials)
    }

    override suspend fun clear() {
        clearFailure?.let { failure -> throw failure }
        state = EncryptedCredentialRead.Missing
    }
}

private class FakeCredentialCipher : CredentialCipher {
    internal val encrypted = EncryptedCredentials(byteArrayOf(1), byteArrayOf(2))
    internal var encryptFailure: Exception? = null
    internal var decryptFailure: Exception? = null
    internal var encryptCalls: Int = 0
        private set
    internal var decryptCalls: Int = 0
        private set
    internal var receivedNormalizedUsername: Boolean = false
        private set
    internal var receivedExactPassword: Boolean = false
        private set

    override suspend fun encrypt(username: String, password: String): EncryptedCredentials {
        encryptFailure?.let { failure -> throw failure }
        encryptCalls += 1
        receivedNormalizedUsername = username == "test-user"
        receivedExactPassword = password == " test-password "
        return encrypted
    }

    override suspend fun decrypt(credentials: EncryptedCredentials): ServerAuthentication.Password {
        decryptFailure?.let { failure -> throw failure }
        decryptCalls += 1
        return ServerAuthentication.Password("test-user", "test-password")
    }
}
