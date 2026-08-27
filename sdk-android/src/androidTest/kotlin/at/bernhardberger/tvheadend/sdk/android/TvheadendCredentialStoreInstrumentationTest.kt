@file:Suppress("DEPRECATION")

package at.bernhardberger.tvheadend.sdk.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class TvheadendCredentialStoreInstrumentationTest {
    @Test
    fun encrypted_credentials_round_trip_through_recreated_store_object() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = TvheadendCredentialStore(context)
        assertEquals(
            ServerProfileOperationResult.SUCCESS,
            TvheadendServerProfileStore(context).clearProfile(),
        )

        try {
            assertEquals(
                CredentialOperationResult.SUCCESS,
                first.storePassword("instrumented-user", "instrumented-password"),
            )

            val loaded = TvheadendCredentialStore(context).loadPassword()

            assertTrue(loaded is CredentialReadResult.Available)
            assertTrue(
                "Credential result rendering was not redacted",
                loaded.toString() == "CredentialReadResult.Available(<redacted>)",
            )
        } finally {
            first.clearPassword()
        }

        assertTrue(
            "Credential cleanup did not produce a missing state",
            TvheadendCredentialStore(context).loadPassword() === CredentialReadResult.Missing,
        )
    }

    @Test
    fun server_profile_round_trips_through_recreated_stores_and_legacy_clear_preserves_endpoint() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profileStore = TvheadendServerProfileStore(context)
        assertEquals(ServerProfileOperationResult.SUCCESS, profileStore.clearProfile())

        try {
            assertEquals(
                ServerProfileOperationResult.SUCCESS,
                profileStore.storePassword(
                    host = " instrumented.invalid ",
                    port = 4_242,
                    username = " instrumented-user ",
                    password = "instrumented-password",
                ),
            )

            val loaded = TvheadendServerProfileStore(context).loadProfile()
            assertTrue(loaded is ServerProfileReadResult.Available)
            loaded as ServerProfileReadResult.Available
            assertEquals("instrumented.invalid", loaded.host)
            assertEquals(4_242, loaded.port)
            assertEquals(ServerProfileAuthenticationMode.PASSWORD, loaded.authenticationMode)
            assertTrue(
                TvheadendCredentialStore(context).loadPassword() is CredentialReadResult.Available,
            )
            assertEquals(
                CredentialOperationResult.SUCCESS,
                TvheadendCredentialStore(context).clearPassword(),
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
