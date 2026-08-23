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
        assertEquals(CredentialOperationResult.SUCCESS, first.clearPassword())

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
}
