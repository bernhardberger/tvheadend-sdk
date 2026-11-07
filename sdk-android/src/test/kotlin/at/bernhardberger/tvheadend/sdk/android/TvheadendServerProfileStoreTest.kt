package at.bernhardberger.tvheadend.sdk.android

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import at.bernhardberger.tvheadend.sdk.core.ServerProfileId
import at.bernhardberger.tvheadend.sdk.core.ServerProfileListResult
import at.bernhardberger.tvheadend.sdk.core.ServerProfileReadResult
import at.bernhardberger.tvheadend.sdk.core.ServerProfileWriteResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class TvheadendServerProfileStoreTest {
    @Test
    fun `blank decrypted legacy username migrates as unavailable credentials`() = runTest {
        val legacy = passwordProfileRecord()
        val storage = FakeCredentialStorage(StoredCredentialRead.Available(legacy))
        val cipher = object : FakeCredentialCipher() {
            override suspend fun <T> decrypt(credentials: EncryptedCredentials, context: CredentialCipherContext,
                transform: (String, String) -> T): T = transform("  ", "password")
        }
        val store = TvheadendServerProfileStore.create(storage, cipher)
        val list = store.profiles() as ServerProfileListResult.Available
        val profile = list.profiles.single()
        assertTrue(profile.host == legacy.host)
        assertEquals(legacy.port, profile.port)
        assertNull(profile.username)
        assertFalse(profile.credentialsAvailable)
        assertEquals(profile.id, list.activeId)
        assertSame(ServerProfileReadResult.Unavailable, store.loadActive())
        assertEquals(1, storage.writeCalls)
        assertEquals(0, cipher.encryptCalls)
        assertEquals(ServerProfileWriteResult.Applied(profile.id), store.updateLabel(profile.id, "Repair"))
        assertEquals("Repair", (store.profiles() as ServerProfileListResult.Available).profiles.single().label)
    }

    @Test
    fun `invalid labels fail before storage access even for missing or unavailable profiles`() = runTest {
        val storage = FakeCredentialStorage().apply { readFailure = java.io.IOException("Storage failure") }
        val cipher = FakeCredentialCipher()
        val store = TvheadendServerProfileStore.create(storage, cipher, Mutex(locked = true))
        for (label in listOf("x".repeat(65), "bad\nlabel")) {
            try {
                store.updateLabel(ServerProfileId.generate(), label)
                fail<Unit>("Invalid label accepted")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, storage.readCalls)
        assertEquals(0, storage.writeCalls)
        assertEquals(0, cipher.encryptCalls)
        assertEquals(0, cipher.decryptCalls)
    }

    @Test
    fun `label edit result follows the last DataStore transform invocation`() = runTest {
        val id = ServerProfileId.generate()
        val prefix = "profile.${id.value}."
        val populated = mutablePreferencesOf(CREDENTIAL_VERSION_KEY to 4, PROFILE_ORDER_KEY to id.value,
            stringPreferencesKey(prefix + "host") to "test.invalid", intPreferencesKey(prefix + "port") to 9982,
            stringPreferencesKey(prefix + "mode") to "anonymous")
        val corrupt = mutablePreferencesOf(CREDENTIAL_VERSION_KEY to 99)
        for (first in listOf(populated, corrupt)) {
            val replaying = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
                override val data = kotlinx.coroutines.flow.flowOf(first)
                override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) ->
                    androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences {
                    transform(first)
                    return transform(mutablePreferencesOf())
                }
            }
            assertSame(ServerProfileWriteResult.NotFound, DataStoreCredentialStorage(replaying).updateLabel(id, "Renamed"))
        }
    }

    @Test
    fun `label edits change only label keys without accessing credentials`(@TempDir directory: File) = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "credentials.preferences_pb") })
            val storage = DataStoreCredentialStorage(dataStore)
            val readable = TvheadendServerProfileStore.create(storage, FakeCredentialCipher())
            val password = (readable.addPassword("test.invalid", username = "user", password = "password", activate = true) as ServerProfileWriteResult.Applied).id
            val anonymous = (readable.addAnonymous("test.invalid") as ServerProfileWriteResult.Applied).id
            val unreadable = (readable.addPassword("test.invalid", username = "other", password = "password") as ServerProfileWriteResult.Applied).id
            val marked = (readable.addPassword("test.invalid", username = "marked", password = "password") as ServerProfileWriteResult.Applied).id
            val unknown = (readable.addPassword("test.invalid", username = "unknown", password = "password") as ServerProfileWriteResult.Applied).id
            dataStore.edit { preferences ->
                for (id in listOf(marked, unknown)) {
                    preferences.remove(byteArrayPreferencesKey("profile.${id.value}.password"))
                    preferences[stringPreferencesKey("profile.${id.value}.credentials_unavailable")] = "true"
                }
                preferences.remove(stringPreferencesKey("profile.${unknown.value}.username"))
            }
            val cipher = object : CredentialCipher {
                var encryptCalls = 0
                var decryptCalls = 0
                override suspend fun encrypt(password: String, context: CredentialCipherContext): EncryptedCredentials {
                    encryptCalls += 1
                    error("Unexpected credential access")
                }
                override suspend fun <T> decrypt(credentials: EncryptedCredentials, context: CredentialCipherContext,
                    transform: (username: String, password: String) -> T): T {
                    decryptCalls += 1
                    error("Unexpected credential access")
                }
            }
            val store = TvheadendServerProfileStore.create(storage, cipher)
            val unreadableStore = TvheadendServerProfileStore.create(storage,
                FakeCredentialCipher().apply { decryptFailure = CredentialUnreadableException() })
            assertSame(ServerProfileReadResult.Unavailable, unreadableStore.load(unreadable))
            val before = dataStore.data.first().asMap()
            val beforeList = readable.profiles() as ServerProfileListResult.Available
            for (id in listOf(password, anonymous, unreadable, marked, unknown)) {
                assertEquals(ServerProfileWriteResult.Applied(id), store.updateLabel(id, " Renamed "))
            }
            for (label in listOf(null, "  ")) {
                assertEquals(ServerProfileWriteResult.Applied(anonymous), store.updateLabel(anonymous, label))
                assertNull(dataStore.data.first()[stringPreferencesKey("profile.${anonymous.value}.label")])
            }
            val after = dataStore.data.first().asMap().filterKeys { !it.name.endsWith(".label") }
            assertTrue(before.keys == after.keys)
            for ((key, value) in before) {
                val retained = after[key]
                assertTrue(if (value is ByteArray) retained is ByteArray && value.contentEquals(retained) else value == retained)
            }
            assertEquals(0, cipher.encryptCalls)
            assertEquals(0, cipher.decryptCalls)
            val afterList = store.profiles() as ServerProfileListResult.Available
            assertEquals(beforeList.profiles.map { it.id }, afterList.profiles.map { it.id })
            assertEquals(beforeList.activeId, afterList.activeId)
            for ((old, renamed) in beforeList.profiles.zip(afterList.profiles)) {
                assertTrue(old.username == renamed.username)
                assertEquals(old.credentialsAvailable, renamed.credentialsAvailable)
                assertEquals(if (renamed.id == anonymous) null else "Renamed", renamed.label)
            }
            assertTrue(readable.load(password) is ServerProfileReadResult.Available)
            assertEquals("Renamed", (readable.loadForEditing(password) as ServerProfileEditReadResult.Password).label)
            assertTrue(readable.load(anonymous) is ServerProfileReadResult.Available)
            for (id in listOf(unreadable, marked, unknown)) assertSame(ServerProfileReadResult.Unavailable, unreadableStore.load(id))
            assertEquals(0, cipher.encryptCalls)
            assertEquals(0, cipher.decryptCalls)
        } finally { scope.cancel() }
    }

    @Test
    fun `label edits reject invalid labels missing ids and corrupt collections`(@TempDir directory: File) = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "credentials.preferences_pb") })
            val store = TvheadendServerProfileStore.create(DataStoreCredentialStorage(dataStore), FakeCredentialCipher())
            val missing = ServerProfileId.generate()
            assertSame(ServerProfileWriteResult.NotFound, store.updateLabel(missing, "Missing"))
            try {
                store.updateLabel(missing, "x".repeat(65))
                fail<Unit>("Invalid label accepted")
            } catch (_: IllegalArgumentException) { }
            val id = (store.addAnonymous("test.invalid", label = "Original") as ServerProfileWriteResult.Applied).id
            assertSame(ServerProfileWriteResult.NotFound, store.updateLabel(missing, "Missing"))
            for (label in listOf("x".repeat(65), "bad\nlabel")) {
                try {
                    store.updateLabel(id, label)
                    fail<Unit>("Invalid label accepted")
                } catch (_: IllegalArgumentException) { }
                assertEquals("Original", (store.load(id) as ServerProfileReadResult.Available).label)
            }
            for (version in listOf(3, 99, 4)) {
                dataStore.edit {
                    it[CREDENTIAL_VERSION_KEY] = version
                    it[stringPreferencesKey("unexpected")] = "invalid"
                }
                val before = dataStore.data.first()
                assertSame(ServerProfileWriteResult.Unavailable, store.updateLabel(id, "Unavailable"))
                try {
                    store.updateLabel(id, "x".repeat(65))
                    fail<Unit>("Invalid label accepted")
                } catch (_: IllegalArgumentException) { }
                try {
                    DataStoreCredentialStorage(dataStore).updateLabel(id, "bad\nlabel")
                    fail<Unit>("Invalid label accepted")
                } catch (_: IllegalArgumentException) { }
                assertEquals(before, dataStore.data.first())
            }
        } finally { scope.cancel() }
    }

    @Test
    fun `label edits preserve legacy records and map IO failures while propagating cancellation`() = runTest {
        val storage = FakeCredentialStorage(StoredCredentialRead.Available(passwordProfileRecord()))
        val cipher = FakeCredentialCipher()
        val store = TvheadendServerProfileStore.create(storage, cipher)
        assertSame(ServerProfileWriteResult.NotFound, store.updateLabel(ServerProfileId.generate(), "Legacy"))
        assertEquals(0, storage.writeCalls)
        assertEquals(0, cipher.encryptCalls)
        assertEquals(0, cipher.decryptCalls)
        storage.readFailure = java.io.IOException("Storage failure")
        assertSame(ServerProfileWriteResult.Unavailable, store.updateLabel(ServerProfileId.generate(), "Read"))
        storage.readFailure = null
        val id = (store.addAnonymous("test.invalid") as ServerProfileWriteResult.Applied).id
        storage.writeFailure = java.io.IOException("Storage failure")
        assertSame(ServerProfileWriteResult.Unavailable, store.updateLabel(id, "Write"))
        storage.readFailure = CancellationException("Cancelled")
        try {
            store.updateLabel(id, "Cancelled")
            fail<Unit>("Cancellation swallowed")
        } catch (_: CancellationException) { }
    }

    @Test
    fun `store summary identity matches loaded profile identity for namespace derivation`() = runTest {
        val store = TvheadendServerProfileStore.create(FakeCredentialStorage(), FakeCredentialCipher())
        for (host in listOf(" EXAMPLE.invalid ", " [2001:DB8::1] ", " 2001:DB8::1 ")) {
            val anonymous = store.addAnonymous(host, 4242) as ServerProfileWriteResult.Applied
            val password = store.addPassword(host, 4242, " UsEr ", "password") as ServerProfileWriteResult.Applied
            for (id in listOf(anonymous.id, password.id)) {
                val loaded = store.load(id) as ServerProfileReadResult.Available
                val summary = (store.profiles() as ServerProfileListResult.Available).profiles.single { it.id == id }
                assertEquals(loaded.host, summary.host)
                assertEquals(loaded.port, summary.port)
                assertEquals(loaded.username, summary.username)
                assertEquals(host.trim(), summary.host)
                assertEquals(if (id == password.id) "UsEr" else null, summary.username)
            }
        }
    }

    @Test
    fun `plaintext username survives unreadable credentials and tampering fails password AAD`(@TempDir directory: File) = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "credentials.preferences_pb") })
            val cipher = FakeCredentialCipher()
            val store = TvheadendServerProfileStore.create(DataStoreCredentialStorage(dataStore), cipher)
            val id = (store.addPassword("test.invalid", username = " user ", password = "password") as ServerProfileWriteResult.Applied).id
            val usernameKey = stringPreferencesKey("profile.${id.value}.username")
            assertTrue(dataStore.data.first()[usernameKey] == "user")
            assertTrue((store.profiles() as ServerProfileListResult.Available).profiles.single().username == "user")
            cipher.decryptFailure = CredentialUnreadableException()
            assertSame(ServerProfileReadResult.Unavailable, store.load(id))
            assertTrue((store.profiles() as ServerProfileListResult.Available).profiles.single().username == "user")
            cipher.decryptFailure = null
            dataStore.edit { it[usernameKey] = "changed-user" }
            assertSame(ServerProfileReadResult.Unavailable, store.load(id))
            assertSame(ServerProfileEditReadResult.Unavailable, store.loadForEditing(id))
            assertTrue(store.updatePassword(id, "test.invalid", 9982, " replacement ", "password", null) is ServerProfileWriteResult.Applied)
            assertTrue(dataStore.data.first()[usernameKey] == "replacement")
            assertTrue((store.loadForEditing(id) as ServerProfileEditReadResult.Password).username == "replacement")
            dataStore.edit {
                it.remove(byteArrayPreferencesKey("profile.${id.value}.password"))
                it[stringPreferencesKey("profile.${id.value}.credentials_unavailable")] = "true"
            }
            val summary = (store.profiles() as ServerProfileListResult.Available).profiles.single()
            assertTrue(summary.username == "replacement")
            assertFalse(summary.credentialsAvailable)
            assertSame(ServerProfileReadResult.Unavailable, store.load(id))
        } finally { scope.cancel() }
    }

    @Test
    fun `v4 password metadata is strict and v3 collections are unsupported`() {
        val id = ServerProfileId.generate()
        val prefix = "profile.${id.value}."
        fun preferences() = mutablePreferencesOf(
            CREDENTIAL_VERSION_KEY to 4, PROFILE_ORDER_KEY to id.value,
            stringPreferencesKey(prefix + "host") to "test.invalid",
            intPreferencesKey(prefix + "port") to 9982,
            stringPreferencesKey(prefix + "mode") to "password",
            stringPreferencesKey(prefix + "username") to "user",
            byteArrayPreferencesKey(prefix + "password") to ByteArray(28),
        )
        assertTrue(decodeCredentialRecord(preferences()) is StoredCredentialRead.Available)
        val corruptions: List<MutablePreferences.() -> Unit> = listOf(
            { remove(stringPreferencesKey(prefix + "username")) },
            { this[stringPreferencesKey(prefix + "username")] = " user " },
            { this[stringPreferencesKey(prefix + "username")] = " " },
            { this[stringPreferencesKey(prefix + "username")] = "" },
            { this[CREDENTIAL_VERSION_KEY] = 3 },
            { this[stringPreferencesKey(prefix + "mode")] = "anonymous" },
        )
        corruptions.forEach { corrupt ->
            assertSame(StoredCredentialRead.Unavailable, decodeCredentialRecord(preferences().apply(corrupt)))
        }
        for (knownUsername in listOf(false, true)) {
            val unavailable = preferences().apply {
                remove(byteArrayPreferencesKey(prefix + "password"))
                if (!knownUsername) remove(stringPreferencesKey(prefix + "username"))
                this[stringPreferencesKey(prefix + "credentials_unavailable")] = "true"
            }
            val record = (decodeCredentialRecord(unavailable) as StoredCredentialRead.Available).record as StoredCredentialRecord.Collection
            assertTrue(record.profiles.single().credentialsUnavailable)
            assertTrue((record.profiles.single().username != null) == knownUsername)
        }
    }

    @Test
    fun `truncated legacy ciphertext migrates with unavailable credentials`() = runTest {
        for (size in listOf(0, 1, 27)) {
            assertThrows(CredentialUnreadableException::class.java) { validateCredentialCiphertext(ByteArray(size)) }
        }
        validateCredentialCiphertext(ByteArray(28))
        val original = passwordProfileRecord()
        val truncated = StoredCredentialRecord.Profile(original.host, original.port, original.authenticationMode,
            EncryptedCredentials(original.credentials!!.copyUsername(), byteArrayOf(1)))
        val storage = FakeCredentialStorage(StoredCredentialRead.Available(truncated))
        // A generic provider fault must not hide the definite length failure before key acquisition.
        val cipher = FakeCredentialCipher().apply { decryptFailure = java.security.GeneralSecurityException() }
        val store = TvheadendServerProfileStore.create(storage, cipher)
        val listed = store.profiles() as ServerProfileListResult.Available
        assertFalse(listed.profiles.single().credentialsAvailable)
        assertEquals(1, storage.writeCalls)
        assertSame(ServerProfileReadResult.Unavailable, store.loadActive())
    }

    @Test
    fun `cipher boundary distinguishes permanent unreadability from retryable security faults`() {
        for (failure in listOf(javax.crypto.BadPaddingException(), javax.crypto.AEADBadTagException(),
            java.nio.charset.CharacterCodingException())) {
            assertThrows(CredentialUnreadableException::class.java) { readCredential<Unit> { throw failure } }
        }
        for (failure in listOf(java.security.KeyStoreException(), java.security.UnrecoverableKeyException(),
            java.security.GeneralSecurityException())) {
            val caught = assertThrows(java.security.GeneralSecurityException::class.java) {
                readCredential<Unit> { throw failure }
            }
            assertSame(failure, caught)
        }
    }

    @Test
    fun `transient keystore failure preserves legacy and recovers on next access`() = runTest {
        val legacy = StoredCredentialRead.Available(passwordProfileRecord())
        val storage = FakeCredentialStorage(legacy)
        val cipher = FakeCredentialCipher().apply { decryptFailure = java.security.KeyStoreException() }
        val store = TvheadendServerProfileStore.create(storage, cipher)
        assertSame(ServerProfileReadResult.Unavailable, store.loadActive())
        assertSame(legacy, storage.state)
        assertEquals(0, storage.writeCalls)
        cipher.decryptFailure = null
        val loaded = store.loadActive() as ServerProfileReadResult.Available
        assertEquals(1, storage.writeCalls)
        cipher.decryptFailure = java.security.KeyStoreException()
        assertSame(ServerProfileReadResult.Unavailable, store.load(loaded.id))
        assertSame(ServerProfileReadResult.Unavailable, store.loadActive())
        assertEquals(1, storage.writeCalls)
        cipher.decryptFailure = CredentialUnreadableException()
        assertSame(ServerProfileReadResult.Unavailable, store.load(loaded.id))
        assertEquals(1, storage.writeCalls)
    }

    @Test
    fun `AAD bytes pin legacy v2 and username bound v4 layouts`() {
        val legacy = CredentialCipherContext.Profile("test.invalid", 9982, StoredAuthenticationMode.PASSWORD)
        val hex = java.util.HexFormat.of()
        assertArrayEquals(hex.parseHex("0000002b61742e6265726e686172646265726765722e747668656164656e642e73646b2e63726564656e7469616c7300000002000000000c746573742e696e76616c6964000026fe01"), credentialAssociatedData(legacy, CredentialField.USERNAME))
        assertArrayEquals(hex.parseHex("0000002b61742e6265726e686172646265726765722e747668656164656e642e73646b2e63726564656e7469616c7300000002010000000c746573742e696e76616c6964000026fe01"), credentialAssociatedData(legacy, CredentialField.PASSWORD))
        val current = CredentialCipherContext.Profile("test.invalid", 9982, StoredAuthenticationMode.PASSWORD,
            ServerProfileId.from("12345678-1234-1234-1234-123456789abc"), "user")
        // Independently encoded with Python struct.pack('>i', length) and UTF-8 fields.
        assertArrayEquals(hex.parseHex("0000002b61742e6265726e686172646265726765722e747668656164656e642e73646b2e63726564656e7469616c7300000004010000000c746573742e696e76616c6964000026fe010000002431323334353637382d313233342d313233342d313233342d3132333435363738396162630000000475736572"), credentialAssociatedData(current, CredentialField.PASSWORD))
    }

    @Test
    fun `unexpected decrypt failure preserves legacy for retry`() = runTest {
        val original = StoredCredentialRead.Available(passwordProfileRecord())
        val storage = FakeCredentialStorage(original)
        val cipher = FakeCredentialCipher().apply { decryptFailure = RuntimeException("Unavailable") }
        val store = TvheadendServerProfileStore.create(storage, cipher)
        assertSame(ServerProfileListResult.Unavailable, store.profiles())
        assertSame(original, storage.state)
        assertEquals(0, storage.writeCalls)
        cipher.decryptFailure = null
        assertTrue(store.loadActive() is ServerProfileReadResult.Available)
        assertEquals(1, storage.writeCalls)
    }

    @Test
    fun `migration cancellation during decrypt encrypt and write preserves legacy`() = runTest {
        for (phase in 0..2) {
            val original = StoredCredentialRead.Available(passwordProfileRecord())
            val storage = FakeCredentialStorage(original)
            val cipher = FakeCredentialCipher()
            val cancellation = CancellationException("Cancelled")
            when (phase) {
                0 -> cipher.decryptFailure = cancellation
                1 -> cipher.encryptFailure = cancellation
                2 -> storage.writeFailure = cancellation
            }
            try {
                TvheadendServerProfileStore.create(storage, cipher).loadActive()
                fail<Unit>("Cancellation was swallowed")
            } catch (actual: CancellationException) {
                assertSame(cancellation, actual)
            }
            assertSame(original, storage.state)
            assertEquals(0, storage.writeCalls)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `concurrent first access through shared mutex migrates exactly once`() = runTest {
        val storage = FakeCredentialStorage(StoredCredentialRead.Available(passwordProfileRecord()))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cipher = object : FakeCredentialCipher() {
            override suspend fun <T> decrypt(credentials: EncryptedCredentials, context: CredentialCipherContext,
                transform: (String, String) -> T): T {
                if ((context as CredentialCipherContext.Profile).id == null) {
                    entered.complete(Unit)
                    release.await()
                }
                return super.decrypt(credentials, context, transform)
            }
        }
        val mutex = Mutex()
        val first = TvheadendServerProfileStore.create(storage, cipher, mutex)
        val second = TvheadendServerProfileStore.create(storage, cipher, mutex)
        val a = async { first.loadActive() as ServerProfileReadResult.Available }
        entered.await()
        val b = async { second.loadActive() as ServerProfileReadResult.Available }
        runCurrent()
        assertEquals(0, storage.writeCalls)
        release.complete(Unit)
        assertEquals(a.await().id, b.await().id)
        assertEquals(1, storage.writeCalls)
    }

    @Test
    fun `cancelling suspended migration phases never replaces legacy state`() = runTest {
        for (phase in 0..2) {
            val original = StoredCredentialRead.Available(passwordProfileRecord())
            val backing = FakeCredentialStorage(original)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            suspend fun pause() {
                entered.complete(Unit)
                release.await()
            }
            val storage = object : CredentialStorage by backing {
                override suspend fun write(record: StoredCredentialRecord) {
                    if (phase == 2) pause()
                    backing.write(record)
                }
            }
            val cipher = object : FakeCredentialCipher() {
                override suspend fun encrypt(password: String,
                    context: CredentialCipherContext): EncryptedCredentials {
                    if (phase == 1) pause()
                    return super.encrypt(password, context)
                }
                override suspend fun <T> decrypt(credentials: EncryptedCredentials,
                    context: CredentialCipherContext, transform: (String, String) -> T): T {
                    if (phase == 0) pause()
                    return super.decrypt(credentials, context, transform)
                }
            }
            val reading = async { TvheadendServerProfileStore.create(storage, cipher).loadActive() }
            entered.await()
            reading.cancel()
            try {
                reading.await()
                fail<Unit>("Cancellation was swallowed")
            } catch (_: CancellationException) {
                assertTrue(reading.isCancelled)
            }
            assertSame(original, backing.state)
            assertEquals(0, backing.writeCalls)
        }
    }

    @Test
    fun `keyed load reads inactive profiles and missing identifiers`() = runTest {
        val store = TvheadendServerProfileStore.create(FakeCredentialStorage(), FakeCredentialCipher())
        val id = (store.addAnonymous("test.invalid") as ServerProfileWriteResult.Applied).id
        assertEquals(id, (store.load(id) as ServerProfileReadResult.Available).id)
        assertSame(ServerProfileReadResult.Missing, store.loadActive())
        assertSame(ServerProfileReadResult.Missing, store.load(ServerProfileId.generate()))
        val unavailable = TvheadendServerProfileStore.create(FakeCredentialStorage(StoredCredentialRead.Unavailable), FakeCredentialCipher())
        assertSame(ServerProfileReadResult.Unavailable, unavailable.load(id))
    }
    @Test
    fun `v4 rejects unknown keys invalid selection and malformed records`() {
        val id = ServerProfileId.generate()
        val prefix = "profile.${id.value}."
        val corruptions: List<MutablePreferences.() -> Unit> = listOf(
            { this[intPreferencesKey("unknown")] = 1 },
            { this[PROFILE_ORDER_KEY] = "${id.value}\n${id.value}" },
            { this[ACTIVE_PROFILE_KEY] = ServerProfileId.generate().value },
            { this[stringPreferencesKey(prefix + "host")] = " test.invalid " },
            { this[intPreferencesKey(prefix + "port")] = 0 },
            { this[stringPreferencesKey(prefix + "mode")] = "unknown" },
            { this[stringPreferencesKey(prefix + "mode")] = "password" },
            { this[stringPreferencesKey(prefix + "label")] = "bad\nlabel" },
            { this[stringPreferencesKey(prefix + "credentials_unavailable")] = "true" },
            { this[stringPreferencesKey(prefix + "username")] = "user" },
        )
        corruptions.forEach { corrupt ->
            val preferences = mutablePreferencesOf(
                CREDENTIAL_VERSION_KEY to 4, PROFILE_ORDER_KEY to id.value,
                stringPreferencesKey(prefix + "host") to "test.invalid",
                intPreferencesKey(prefix + "port") to 9982,
                stringPreferencesKey(prefix + "mode") to "anonymous",
            )
            preferences.corrupt()
            assertSame(StoredCredentialRead.Unavailable, decodeCredentialRecord(preferences))
        }
    }
    @Test
    fun `migration encryption failure preserves decryptable legacy data for retry`() = runTest {
        val legacy = StoredCredentialRead.Available(passwordProfileRecord())
        val storage = FakeCredentialStorage(legacy)
        val cipher = FakeCredentialCipher().apply { encryptFailure = IllegalStateException("Unavailable") }
        val store = TvheadendServerProfileStore.create(storage, cipher)
        assertSame(ServerProfileReadResult.Unavailable, store.loadActive())
        assertSame(legacy, storage.state)
        assertEquals(0, storage.writeCalls)
        cipher.encryptFailure = null
        assertTrue(store.loadActive() is ServerProfileReadResult.Available)
        assertEquals(1, storage.writeCalls)
    }
    @Test
    fun `data store migration replaces legacy keys with one v4 collection`(@TempDir directory: File) = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "credentials.preferences_pb") })
            val storage = DataStoreCredentialStorage(dataStore)
            storage.write(passwordProfileRecord())
            val store = TvheadendServerProfileStore.create(storage, FakeCredentialCipher())
            val profile = store.loadActive() as ServerProfileReadResult.Available
            val preferences = dataStore.data.first()
            assertEquals(4, preferences[CREDENTIAL_VERSION_KEY])
            assertTrue(preferences[stringPreferencesKey("profile.${profile.id.value}.username")] == "test-user")
            assertEquals(profile.id.value, preferences[PROFILE_ORDER_KEY])
            assertEquals(profile.id.value, preferences[ACTIVE_PROFILE_KEY])
            assertFalse(preferences.asMap().keys.any { it in setOf(SERVER_HOST_KEY, SERVER_PORT_KEY, AUTHENTICATION_MODE_KEY, ENCRYPTED_USERNAME_KEY, ENCRYPTED_PASSWORD_KEY) })
            assertEquals(profile.id, (store.loadActive() as ServerProfileReadResult.Available).id)
        } finally { scope.cancel() }
    }

    @Test
    fun `corrupt file stays unavailable until clear and then accepts new profiles`(@TempDir directory: File) = runTest {
        val file = File(directory, "credentials.preferences_pb")
        file.writeBytes(byteArrayOf(0x0A, 0x05, 0x01))
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.create(corruptionHandler = credentialCorruptionHandler, scope = scope, produceFile = { file })
            val store = TvheadendServerProfileStore.create(DataStoreCredentialStorage(dataStore), FakeCredentialCipher())
            assertSame(ServerProfileReadResult.Unavailable, store.loadActive())
            assertSame(ServerProfileWriteResult.Unavailable, store.addAnonymous("test.invalid"))
            val cleared = store.clearAll() as ServerProfileListResult.Available
            assertTrue(cleared.profiles.isEmpty())
            assertNull(cleared.activeId)
            assertSame(ServerProfileReadResult.Missing, store.loadActive())
            assertTrue(store.addAnonymous("test.invalid") is ServerProfileWriteResult.Applied)
        } finally { scope.cancel() }
    }

    @Test
    fun `encryption and persistence failure preserve a previous collection`() = runTest {
        val storage = FakeCredentialStorage()
        val cipher = FakeCredentialCipher()
        val store = TvheadendServerProfileStore.create(storage, cipher)
        val id = (store.addAnonymous("test.invalid", activate = true) as ServerProfileWriteResult.Applied).id
        val previous = storage.state
        cipher.encryptFailure = IllegalStateException("Unavailable")
        assertSame(ServerProfileWriteResult.Unavailable, store.updatePassword(id, "other.invalid", 9982, "user", "password", null))
        assertSame(previous, storage.state)
        storage.writeFailure = IllegalStateException("Unavailable")
        assertSame(ServerProfileWriteResult.Unavailable, store.updateAnonymous(id, "other.invalid", 9982, null))
        assertSame(previous, storage.state)
        assertEquals(1, storage.writeCalls)
    }

    @Test
    fun `edit results have no public construction copy or serialization APIs`() {
        listOf(ServerProfileEditReadResult.Anonymous::class.java, ServerProfileEditReadResult.Password::class.java).forEach { type ->
            assertTrue(type.constructors.all { it.isSynthetic })
            assertFalse(type.declaredMethods.any { it.name == "copy" || it.name.startsWith("component") })
            assertFalse(java.io.Serializable::class.java.isAssignableFrom(type))
            assertFalse(android.os.Parcelable::class.java.isAssignableFrom(type))
        }
        assertEquals(1, TvheadendServerProfileStore::class.java.constructors.count { !it.isSynthetic })
        assertTrue(ServerProfileReadResult.Available::class.java.constructors.all { it.isSynthetic })
        assertFalse(ServerProfileReadResult.Available::class.java.methods.any { it.name == "getPassword" })
    }

    @Test
    fun `anonymous v2 migrates once as the active profile`() = runTest {
        val storage = FakeCredentialStorage(StoredCredentialRead.Available(anonymousProfileRecord()))
        val cipher = FakeCredentialCipher()
        val store = TvheadendServerProfileStore.create(storage, cipher)
        val first = store.loadActive() as ServerProfileReadResult.Available
        assertEquals(null, first.label)
        assertEquals(first.id, (store.loadActive() as ServerProfileReadResult.Available).id)
        assertEquals(first.id, (store.profiles() as ServerProfileListResult.Available).activeId)
        assertEquals(1, storage.writeCalls)
        assertEquals(0, cipher.encryptCalls)
        assertEquals(0, cipher.decryptCalls)
    }

    @Test
    fun `password v2 migrates using username bound v4 associated data`() = runTest {
        val legacy = passwordProfileRecord(password = " exact password ")
        val storage = FakeCredentialStorage(StoredCredentialRead.Available(legacy))
        val cipher = FakeCredentialCipher()
        val store = TvheadendServerProfileStore.create(storage, cipher)
        val loaded = store.loadActive() as ServerProfileReadResult.Available
        val migrated = (storage.state as StoredCredentialRead.Available).record as StoredCredentialRecord.Collection
        assertEquals(loaded.id, migrated.activeId)
        assertTrue((store.profiles() as ServerProfileListResult.Available).profiles.single().username == "test-user")
        assertEquals(loaded.id, (cipher.lastContext as CredentialCipherContext.Profile).id)
        assertEquals(1, cipher.encryptCalls)
        assertFalse(legacy.credentials!!.copyPassword().contentEquals(migrated.profiles.single().credentials!!.copyPassword()))
        val edit = store.loadForEditing(loaded.id) as ServerProfileEditReadResult.Password
        assertEquals(" exact password ", edit.password)
        assertEquals("test-user", edit.username)
        assertEquals(1, storage.writeCalls)
    }

    @Test
    fun `undecryptable legacy remains listed unavailable and repairable`() = runTest {
        val storage = FakeCredentialStorage(StoredCredentialRead.Available(passwordProfileRecord()))
        val cipher = FakeCredentialCipher().apply { decryptFailure = CredentialUnreadableException() }
        val store = TvheadendServerProfileStore.create(storage, cipher)
        val listed = store.profiles() as ServerProfileListResult.Available
        val id = listed.profiles.single().id
        assertEquals(id, listed.activeId)
        assertFalse(listed.profiles.single().credentialsAvailable)
        assertNull(listed.profiles.single().username)
        assertSame(ServerProfileReadResult.Unavailable, store.load(id))
        assertSame(ServerProfileReadResult.Unavailable, store.loadActive())
        cipher.decryptFailure = null
        assertSame(ServerProfileEditReadResult.Unavailable, store.loadForEditing(id))
        assertTrue(store.updatePassword(id, "test.invalid", 9982, "user", "password", "Repair") is ServerProfileWriteResult.Applied)
        assertEquals("Repair", (store.loadActive() as ServerProfileReadResult.Available).label)
        assertTrue((store.profiles() as ServerProfileListResult.Available).profiles.single().credentialsAvailable)
        assertTrue(store.remove(id) is ServerProfileWriteResult.Applied)
        assertSame(ServerProfileReadResult.Missing, store.loadActive())
    }

    @Test
    fun `collection operations preserve insertion order id and explicit activation`() = runTest {
        val storage = FakeCredentialStorage()
        val store = TvheadendServerProfileStore.create(storage, FakeCredentialCipher())
        val first = (store.addAnonymous(" test.invalid ", label = " Home ") as ServerProfileWriteResult.Applied).id
        assertSame(ServerProfileReadResult.Missing, store.loadActive())
        val second = (store.addPassword("test.invalid", username = " user ", password = " exact ", activate = true) as ServerProfileWriteResult.Applied).id
        assertEquals(listOf(first, second), (store.profiles() as ServerProfileListResult.Available).profiles.map { it.id })
        assertEquals("Home", (store.profiles() as ServerProfileListResult.Available).profiles.first().label)
        assertEquals(second, (store.loadActive() as ServerProfileReadResult.Available).id)
        assertEquals(ServerProfileWriteResult.Applied(first), store.updatePassword(first, "other.invalid", 4242, "user", "password", null))
        assertEquals(ServerProfileWriteResult.Applied(first), store.setActive(first))
        assertEquals(null, (store.loadForEditing(first) as ServerProfileEditReadResult.Password).label)
        assertEquals(ServerProfileWriteResult.Applied(first), store.updateAnonymous(first, "test.invalid", 9982, "Anonymous"))
        assertTrue(store.loadForEditing(first) is ServerProfileEditReadResult.Anonymous)
        assertEquals(ServerProfileWriteResult.Applied(first), store.remove(first))
        assertSame(ServerProfileReadResult.Missing, store.loadActive())
        assertSame(ServerProfileEditReadResult.Missing, store.loadForEditing(first))
        assertSame(ServerProfileWriteResult.NotFound, store.setActive(first))
        assertSame(ServerProfileWriteResult.NotFound, store.remove(first))
        assertSame(ServerProfileWriteResult.NotFound, store.updateAnonymous(first, "test.invalid", 9982, null))
        assertEquals(6, storage.writeCalls)
        val cleared = store.clearAll() as ServerProfileListResult.Available
        assertTrue(cleared.profiles.isEmpty())
        assertNull(cleared.activeId)
        assertTrue((store.profiles() as ServerProfileListResult.Available).profiles.isEmpty())
        assertEquals(1, storage.clearCalls)
    }

    @Test
    fun `v4 data store round trip retains order label selection and credentials`(@TempDir directory: File) = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "credentials.preferences_pb") })
            val storage = DataStoreCredentialStorage(dataStore)
            val store = TvheadendServerProfileStore.create(storage, FakeCredentialCipher())
            val first = (store.addAnonymous("test.invalid", label = "Home") as ServerProfileWriteResult.Applied).id
            val second = (store.addPassword("other.invalid", username = "user", password = " exact ", activate = true) as ServerProfileWriteResult.Applied).id
            val reopened = TvheadendServerProfileStore.create(storage, FakeCredentialCipher())
            assertEquals(listOf(first, second), (reopened.profiles() as ServerProfileListResult.Available).profiles.map { it.id })
            assertEquals(second, (reopened.loadActive() as ServerProfileReadResult.Available).id)
            assertEquals(" exact ", (reopened.loadForEditing(second) as ServerProfileEditReadResult.Password).password)
            assertEquals("Home", (reopened.loadForEditing(first) as ServerProfileEditReadResult.Anonymous).label)
        } finally { scope.cancel() }
    }

    @Test
    fun `ciphertext copied between ids or endpoint contexts cannot decrypt`() = runTest {
        val storage = FakeCredentialStorage()
        val store = TvheadendServerProfileStore.create(storage, FakeCredentialCipher())
        val id = (store.addPassword("test.invalid", username = "user", password = "password", activate = true) as ServerProfileWriteResult.Applied).id
        val record = ((storage.state as StoredCredentialRead.Available).record as StoredCredentialRecord.Collection).profiles.single()
        val other = ServerProfileId.generate()
        val variants = listOf(
            StoredCredentialRecord.Profile(record.host, record.port, record.authenticationMode, record.credentials, other, username = "user"),
            StoredCredentialRecord.Profile("other.invalid", record.port, record.authenticationMode, record.credentials, id, username = "user"),
            StoredCredentialRecord.Profile(record.host, 4242, record.authenticationMode, record.credentials, id, username = "user"),
            StoredCredentialRecord.Profile(record.host, record.port, record.authenticationMode,
                EncryptedCredentials(encryptedPassword = byteArrayOf(1)), id, username = "user"),
            StoredCredentialRecord.Profile(record.host, record.port, record.authenticationMode, record.credentials, id, username = "other-user"),
        )
        variants.forEach { changed ->
            val changedStore = TvheadendServerProfileStore.create(
                FakeCredentialStorage(StoredCredentialRead.Available(StoredCredentialRecord.Collection(listOf(changed), changed.id))),
                FakeCredentialCipher(),
            )
            assertSame(ServerProfileReadResult.Unavailable, changedStore.loadActive())
        }
        assertFalse(credentialAssociatedData(record.cipherContext(), CredentialField.USERNAME).contentEquals(
            credentialAssociatedData(CredentialCipherContext.Profile(record.host, record.port, StoredAuthenticationMode.ANONYMOUS, id), CredentialField.USERNAME),
        ))
    }

    @Test
    fun `unknown malformed and partial formats fail closed and clear recovers`() = runTest {
        val malformed = listOf(
            mutablePreferencesOf(CREDENTIAL_VERSION_KEY to 1),
            mutablePreferencesOf(CREDENTIAL_VERSION_KEY to 99),
            mutablePreferencesOf(CREDENTIAL_VERSION_KEY to 2),
            mutablePreferencesOf(CREDENTIAL_VERSION_KEY to 3),
            mutablePreferencesOf(intPreferencesKey("unknown") to 1),
            mutablePreferencesOf(CREDENTIAL_VERSION_KEY to 3, stringPreferencesKey("profile_order") to "invalid"),
        )
        malformed.forEach { preferences ->
            val decoded = decodeCredentialRecord(preferences)
            assertSame(StoredCredentialRead.Unavailable, decoded)
            val store = TvheadendServerProfileStore.create(FakeCredentialStorage(decoded), FakeCredentialCipher())
            assertSame(ServerProfileListResult.Unavailable, store.profiles())
            assertSame(ServerProfileReadResult.Unavailable, store.loadActive())
            assertSame(ServerProfileWriteResult.Unavailable, store.addAnonymous("test.invalid"))
            val cleared = store.clearAll() as ServerProfileListResult.Available
            assertTrue(cleared.profiles.isEmpty())
            assertNull(cleared.activeId)
            assertSame(ServerProfileReadResult.Missing, store.loadActive())
            assertTrue(store.addAnonymous("test.invalid") is ServerProfileWriteResult.Applied)
        }
    }

    @Test
    fun `validation precedes storage and crypto and diagnostics redact sensitive fields`() = runTest {
        val storage = FakeCredentialStorage()
        val cipher = FakeCredentialCipher()
        val store = TvheadendServerProfileStore.create(storage, cipher)
        val operations = listOf<suspend () -> Unit>(
            { store.addAnonymous(" ") }, { store.addAnonymous("test.invalid", 0) },
            { store.addAnonymous("test.invalid", 65536) },
            { store.addPassword("test.invalid", username = " ", password = "password") },
            { store.addPassword("test.invalid", username = "user", password = " ") },
            { store.addAnonymous("test.invalid", label = "a".repeat(65)) },
            { store.addAnonymous("test.invalid", label = "a\nb") },
        )
        operations.forEach { operation ->
            try {
                operation()
                fail<Unit>("Invalid input was accepted")
            } catch (_: IllegalArgumentException) {
                // Validation must fail before touching storage or crypto.
            }
        }
        assertEquals(0, storage.readCalls)
        assertEquals(0, cipher.encryptCalls)
        val id = (store.addPassword("test.invalid", username = "user", password = "password", activate = true) as ServerProfileWriteResult.Applied).id
        assertEquals("ServerProfileReadResult.Available(<redacted>)", store.loadActive().toString())
        assertEquals("ServerProfileEditReadResult.Password(<redacted>)", store.loadForEditing(id).toString())
        assertEquals("ServerProfileSummary(<redacted>)", (store.profiles() as ServerProfileListResult.Available).profiles.single().toString())
    }

    @Test
    fun `clear storage failure returns unavailable without exposing its cause`() = runTest {
        val storage = FakeCredentialStorage()
        val store = TvheadendServerProfileStore.create(storage, FakeCredentialCipher())
        store.addAnonymous("test.invalid", activate = true)
        val previous = storage.state
        storage.clearFailure = java.io.IOException("/data/user/0/x/files/datastore/p.preferences_pb")

        assertSame(ServerProfileListResult.Unavailable, store.clearAll())
        assertSame(previous, storage.state)
        assertEquals(0, storage.clearCalls)
    }

    @Test
    fun `cancellation from storage crypto and clear always propagates`() {
        val cancellation = CancellationException("cancelled")
        val read = TvheadendServerProfileStore.create(FakeCredentialStorage().apply { readFailure = cancellation }, FakeCredentialCipher())
        val write = TvheadendServerProfileStore.create(FakeCredentialStorage().apply { writeFailure = cancellation }, FakeCredentialCipher())
        val encrypt = TvheadendServerProfileStore.create(FakeCredentialStorage(), FakeCredentialCipher().apply { encryptFailure = cancellation })
        val decrypt = TvheadendServerProfileStore.create(FakeCredentialStorage(StoredCredentialRead.Available(passwordProfileRecord())), FakeCredentialCipher().apply { decryptFailure = cancellation })
        val clear = TvheadendServerProfileStore.create(FakeCredentialStorage().apply { clearFailure = cancellation }, FakeCredentialCipher())
        val operations = listOf<suspend () -> Unit>(
            { read.loadActive() }, { read.profiles() }, { read.loadForEditing(ServerProfileId.generate()) },
            { write.addAnonymous("test.invalid") },
            { encrypt.addPassword("test.invalid", username = "user", password = "password") },
            { decrypt.loadActive() }, { clear.clearAll() },
        )
        operations.forEach { operation -> assertSame(cancellation, assertThrows(CancellationException::class.java) { runTest { operation() } }) }
    }
}
