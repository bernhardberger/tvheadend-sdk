package at.bernhardberger.tvheadend.sdk.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import at.bernhardberger.tvheadend.sdk.core.ServerProfileAuthenticationMode
import at.bernhardberger.tvheadend.sdk.core.ServerProfileListResult
import at.bernhardberger.tvheadend.sdk.core.ServerProfileReadResult
import at.bernhardberger.tvheadend.sdk.core.ServerProfileWriteResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class TvheadendServerProfileStoreInstrumentationTest {
    @Test
    fun server_profile_round_trips_through_recreated_stores_and_anonymous_replacement() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profileStore = TvheadendServerProfileStore(context)
        val cleared = profileStore.clearAll() as ServerProfileListResult.Available
        assertTrue(cleared.profiles.isEmpty())
        assertEquals(null, cleared.activeId)

        try {
            assertTrue(
                profileStore.addPassword(
                    host = " instrumented.invalid ",
                    port = 4_242,
                    username = " instrumented-user ",
                    password = "instrumented-password",
                    activate = true,
                ) is ServerProfileWriteResult.Applied,
            )

            val loaded = TvheadendServerProfileStore(context).loadActive()
            assertTrue(loaded is ServerProfileReadResult.Available)
            loaded as ServerProfileReadResult.Available
            assertEquals("instrumented.invalid", loaded.host)
            assertEquals(4_242, loaded.port)
            assertEquals(ServerProfileAuthenticationMode.PASSWORD, loaded.authenticationMode)
            assertTrue((profileStore.profiles() as ServerProfileListResult.Available).profiles.single().username == "instrumented-user")
            assertEquals("ServerProfileReadResult.Available(<redacted>)", loaded.toString())
            assertTrue(
                profileStore.updateAnonymous(loaded.id, loaded.host, loaded.port, null) is ServerProfileWriteResult.Applied,
            )

            val anonymous = TvheadendServerProfileStore(context).loadActive()
            assertTrue(anonymous is ServerProfileReadResult.Available)
            anonymous as ServerProfileReadResult.Available
            assertEquals("instrumented.invalid", anonymous.host)
            assertEquals(4_242, anonymous.port)
            assertEquals(ServerProfileAuthenticationMode.ANONYMOUS, anonymous.authenticationMode)
            assertTrue((profileStore.profiles() as ServerProfileListResult.Available).profiles.single().username == null)
        } finally {
            assertTrue(profileStore.clearAll() is ServerProfileListResult.Available)
        }

        assertTrue(
            TvheadendServerProfileStore(context).loadActive() === ServerProfileReadResult.Missing,
        )
    }
}
