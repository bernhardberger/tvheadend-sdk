package at.bernhardberger.tvheadend.sdk.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import at.bernhardberger.tvheadend.sdk.core.ServerProfileAuthenticationMode
import at.bernhardberger.tvheadend.sdk.core.ServerProfileReadResult
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
        assertEquals(ServerProfileReadResult.Missing, profileStore.clearProfile())

        try {
            assertEquals(
                ServerProfileAuthenticationMode.PASSWORD,
                (profileStore.storePassword(
                    host = " instrumented.invalid ",
                    port = 4_242,
                    username = " instrumented-user ",
                    password = "instrumented-password",
                ) as ServerProfileReadResult.Available).authenticationMode,
            )

            val loaded = TvheadendServerProfileStore(context).loadProfile()
            assertTrue(loaded is ServerProfileReadResult.Available)
            loaded as ServerProfileReadResult.Available
            assertEquals("instrumented.invalid", loaded.host)
            assertEquals(4_242, loaded.port)
            assertEquals(ServerProfileAuthenticationMode.PASSWORD, loaded.authenticationMode)
            assertEquals("ServerProfileReadResult.Available(<redacted>)", loaded.toString())
            assertTrue(
                profileStore.storeAnonymous(loaded.host, loaded.port) is ServerProfileReadResult.Available,
            )

            val anonymous = TvheadendServerProfileStore(context).loadProfile()
            assertTrue(anonymous is ServerProfileReadResult.Available)
            anonymous as ServerProfileReadResult.Available
            assertEquals("instrumented.invalid", anonymous.host)
            assertEquals(4_242, anonymous.port)
            assertEquals(ServerProfileAuthenticationMode.ANONYMOUS, anonymous.authenticationMode)
        } finally {
            profileStore.clearProfile()
        }

        assertTrue(
            TvheadendServerProfileStore(context).loadProfile() === ServerProfileReadResult.Missing,
        )
    }
}
