package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.ServerProfileAuthenticationMode
import at.bernhardberger.tvheadend.sdk.core.ServerProfileReadResult
import at.bernhardberger.tvheadend.sdk.core.SessionCommandResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class FakeServerProfileStoreTest {
    @Test
    fun `successful write returns normalized profile for explicit connection without readback`() = runTest {
        val store = FakeServerProfileStore()
        val session = FakeTvheadendSession()

        val stored = store.storePassword(
            host = "  test.invalid  ",
            port = 4_242,
            username = " user ",
            password = "exact password",
        ) as ServerProfileReadResult.Available

        assertEquals("test.invalid", stored.host)
        assertEquals(4_242, stored.port)
        assertSame(ServerProfileAuthenticationMode.PASSWORD, stored.authenticationMode)
        assertEquals(listOf(FakeServerProfileStoreCall.STORE_PASSWORD), store.calls)
        assertSame(SessionCommandResult.STARTED, session.connect(stored.profile))
        assertEquals(listOf(FakeSessionCall.CONNECT), session.calls)
        assertSame(stored, store.loadProfile())
    }

    @Test
    fun `scripts unavailable mutations preserves state and successful clear publishes missing`() = runTest {
        val initial = ServerProfileReadResult.anonymous("stable.invalid")
        val store = FakeServerProfileStore(initial)
        store.scriptMutationUnavailable()

        assertSame(
            ServerProfileReadResult.Unavailable,
            store.storeAnonymous("replacement.invalid"),
        )
        assertSame(ServerProfileReadResult.Unavailable, store.clearProfile())
        assertSame(initial, store.loadProfile())

        store.scriptMutationSuccess()
        assertSame(ServerProfileReadResult.Missing, store.clearProfile())
        assertSame(ServerProfileReadResult.Missing, store.loadProfile())
        assertTrue(store.calls.contains(FakeServerProfileStoreCall.CLEAR_PROFILE))
    }

    @Test
    fun `scripts positive and negative reads with supported results`() = runTest {
        val store = FakeServerProfileStore(ServerProfileReadResult.Unavailable)
        assertSame(ServerProfileReadResult.Unavailable, store.loadProfile())

        val available = ServerProfileReadResult.anonymous("scripted.invalid")
        store.scriptProfile(available)
        assertSame(available, store.loadProfile())
        store.scriptProfile(ServerProfileReadResult.Missing)
        assertSame(ServerProfileReadResult.Missing, store.loadProfile())
    }
}
