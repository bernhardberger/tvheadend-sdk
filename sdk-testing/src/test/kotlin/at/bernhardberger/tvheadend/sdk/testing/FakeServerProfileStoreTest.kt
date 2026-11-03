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
import org.junit.jupiter.api.Test

internal class FakeServerProfileStoreTest {
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
        assertSame(initial, store.loadActive())
        store.scriptMutationSuccess()
        store.scriptProfile(ServerProfileReadResult.Unavailable)
        assertSame(ServerProfileListResult.Unavailable, store.profiles())
        store.clearAll()
        assertSame(ServerProfileReadResult.Missing, store.loadActive())
        store.scriptProfile(initial)
        assertSame(initial, store.loadActive())
    }

    @Test
    fun `cancelled calls neither record nor mutate`() = runTest {
        val store = FakeServerProfileStore()
        val id = ServerProfileId.generate()
        val calls: List<suspend () -> Any?> = listOf(
            { store.profiles() }, { store.loadActive() }, { store.addAnonymous("test.invalid") },
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
