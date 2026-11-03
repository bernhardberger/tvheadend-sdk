package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.ServerProfileId
import at.bernhardberger.tvheadend.sdk.core.ServerProfileListResult
import at.bernhardberger.tvheadend.sdk.core.ServerProfileReadResult
import at.bernhardberger.tvheadend.sdk.core.ServerProfileWriteResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class FakeServerProfileStoreTest {
    @Test
    fun `anonymous profiles cannot script unavailable credentials`() = runTest {
        val store = FakeServerProfileStore()
        val id = (store.addAnonymous("test.invalid") as ServerProfileWriteResult.Applied).id
        assertThrows(IllegalArgumentException::class.java) { store.scriptCredentialsUnavailable(id) }
        assertTrue((store.profiles() as ServerProfileListResult.Available).profiles.single().credentialsAvailable)
        assertTrue(store.load(id) is ServerProfileReadResult.Available)
    }

    @Test
    fun `keyed reads and unavailable credentials keep a readable repairable collection`() = runTest {
        val store = FakeServerProfileStore()
        val id = (store.addPassword("test.invalid", username = "user", password = "password", activate = true) as ServerProfileWriteResult.Applied).id
        assertEquals(id, (store.load(id) as ServerProfileReadResult.Available).id)
        assertSame(ServerProfileReadResult.Missing, store.load(ServerProfileId.generate()))
        store.scriptCredentialsUnavailable(id)
        assertSame(ServerProfileReadResult.Unavailable, store.load(id))
        assertSame(ServerProfileReadResult.Unavailable, store.loadActive())
        assertEquals(false, (store.profiles() as ServerProfileListResult.Available).profiles.single().credentialsAvailable)
        store.updatePassword(id, "test.invalid", 9982, "user", "replacement", null)
        assertTrue(store.load(id) is ServerProfileReadResult.Available)
        assertTrue((store.profiles() as ServerProfileListResult.Available).profiles.single().credentialsAvailable)
    }

    @Test
    fun `added profile loads for explicit session connection`() = runTest {
        val store = FakeServerProfileStore()
        val id = (store.addPassword(" test.invalid ", 4242, " user ", "password") as ServerProfileWriteResult.Applied).id
        val loaded = store.load(id) as ServerProfileReadResult.Available
        assertEquals("test.invalid", loaded.host)
        assertEquals(4242, loaded.port)
        val session = FakeTvheadendSession()
        assertSame(at.bernhardberger.tvheadend.sdk.core.SessionCommandResult.STARTED, session.connect(loaded.profile))
        assertEquals(listOf(FakeSessionCall.CONNECT), session.calls)
    }

    @Test
    fun `profile cache clear records a distinct value free call`() = runTest {
        val session = FakeTvheadendSession()
        val footprint = at.bernhardberger.tvheadend.sdk.core.CacheStatistics(10, 20, 2)
        session.cache.scriptStatistics(footprint)
        session.cache.clear(at.bernhardberger.tvheadend.sdk.core.ServerProfile("test.invalid"))
        assertEquals(footprint, session.cache.statistics.value)
        session.cache.clear()
        assertEquals(at.bernhardberger.tvheadend.sdk.core.CacheStatistics.EMPTY, session.cache.statistics.value)
        assertEquals(listOf(FakeSessionCall.CLEAR_PROFILE_CACHE, FakeSessionCall.CLEAR_CACHE), session.calls)
    }
    @Test
    fun `ordered profiles keep ids across edits and active removal chooses no successor`() = runTest {
        val ids = listOf(ServerProfileId.generate(), ServerProfileId.generate())
        val source = ids.iterator()
        val store = FakeServerProfileStore(idSource = { source.next() })
        assertEquals(ServerProfileWriteResult.Applied(ids[0]), store.addAnonymous("test.invalid", label = " First "))
        assertSame(ServerProfileReadResult.Missing, store.loadActive())
        store.addPassword("test.invalid", username = "user", password = "password", activate = true)
        val list = store.profiles() as ServerProfileListResult.Available
        assertEquals(ids, list.profiles.map { it.id })
        assertEquals(ids[1], list.activeId)
        store.updateAnonymous(ids[1], "changed.invalid", 9982, null)
        assertEquals(ids[1], (store.loadActive() as ServerProfileReadResult.Available).id)
        store.setActive(ids[0])
        assertEquals("First", (store.loadActive() as ServerProfileReadResult.Available).label)
        store.remove(ids[0])
        assertSame(ServerProfileReadResult.Missing, store.loadActive())
        assertSame(ServerProfileWriteResult.NotFound, store.remove(ids[0]))
        assertSame(ServerProfileWriteResult.NotFound, store.setActive(ids[0]))
        store.clearAll()
        assertTrue((store.profiles() as ServerProfileListResult.Available).profiles.isEmpty())
    }

    @Test
    fun `scripted unavailable preserves data and clear recovers unreadable collection`() = runTest {
        val initial = ServerProfileReadResult.anonymous(ServerProfileId.generate(), "test.invalid")
        val store = FakeServerProfileStore(initial)
        store.scriptMutationUnavailable()
        assertSame(ServerProfileWriteResult.Unavailable, store.addAnonymous("test.invalid"))
        try {
            store.clearAll()
            org.junit.jupiter.api.Assertions.fail<Unit>("Unavailable clear was accepted")
        } catch (_: IllegalStateException) {
            assertSame(initial, store.loadActive())
        }
        assertSame(initial, store.loadActive())
        store.scriptMutationSuccess()
        store.scriptProfile(ServerProfileReadResult.Unavailable)
        assertSame(ServerProfileReadResult.Unavailable, store.load(initial.id))
        assertSame(ServerProfileListResult.Unavailable, store.profiles())
        store.clearAll()
        assertSame(ServerProfileReadResult.Missing, store.loadActive())
        store.scriptProfile(initial)
        assertSame(initial, store.loadActive())
        store.scriptProfile(ServerProfileReadResult.Missing)
        assertSame(ServerProfileReadResult.Missing, store.load(initial.id))
    }

    @Test
    fun `cancelled calls neither record nor mutate`() = runTest {
        val store = FakeServerProfileStore()
        val id = ServerProfileId.generate()
        val calls: List<suspend () -> Any?> = listOf(
            { store.profiles() }, { store.loadActive() }, { store.load(id) }, { store.addAnonymous("test.invalid") },
            { store.addPassword("test.invalid", username = "user", password = "password") },
            { store.updateAnonymous(id, "test.invalid", 9982, null) },
            { store.updatePassword(id, "test.invalid", 9982, "user", "password", null) },
            { store.setActive(id) }, { store.remove(id) }, { store.clearAll() },
        )
        calls.forEach { call ->
            val job = launch(start = CoroutineStart.UNDISPATCHED) { cancel(); call() }
            job.join()
            assertTrue(job.isCancelled)
        }
        assertTrue(store.calls.isEmpty())
    }
}
