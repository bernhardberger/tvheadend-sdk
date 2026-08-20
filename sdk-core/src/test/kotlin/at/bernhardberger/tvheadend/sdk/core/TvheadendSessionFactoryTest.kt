package at.bernhardberger.tvheadend.sdk.core

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

internal class TvheadendSessionFactoryTest {
    @Test
    fun `factory enforces one production owner and releases it after shutdown`() = runTest {
        val first = createTvheadendSession()
        val same = createTvheadendSession()
        assertSame(first, same)

        first.shutdown()
        val replacement = createTvheadendSession()
        try {
            assertNotSame(first, replacement)
        } finally {
            replacement.shutdown()
        }
    }
}
