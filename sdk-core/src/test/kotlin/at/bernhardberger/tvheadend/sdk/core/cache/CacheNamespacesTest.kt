package at.bernhardberger.tvheadend.sdk.core.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class CacheNamespacesTest {
    @Test
    fun `namespace derivation is deterministic for identical inputs`() {
        val first = cacheNamespace("tvh.example.org", 9982, "alice")
        val second = cacheNamespace("tvh.example.org", 9982, "alice")
        assertEquals(first, second)
    }

    @Test
    fun `namespace derivation is a 32 character lowercase hex digest`() {
        val namespace = cacheNamespace("tvh.example.org", 9982, "alice")
        assertEquals(32, namespace.value.length)
        assertTrue(namespace.value.all { char -> char in '0'..'9' || char in 'a'..'f' })
    }

    @Test
    fun `namespace derivation distinguishes host, port, and username`() {
        val baseline = cacheNamespace("tvh.example.org", 9982, "alice")
        assertNotEquals(baseline, cacheNamespace("other.example.org", 9982, "alice"))
        assertNotEquals(baseline, cacheNamespace("tvh.example.org", 9983, "alice"))
        assertNotEquals(baseline, cacheNamespace("tvh.example.org", 9982, "bob"))
    }

    @Test
    fun `anonymous profiles use an empty username`() {
        val anonymous = cacheNamespace("tvh.example.org", 9982, "")
        val named = cacheNamespace("tvh.example.org", 9982, "alice")
        assertNotEquals(anonymous, named)
        assertEquals(32, anonymous.value.length)
    }

    @Test
    fun `namespace value never contains the raw host in clear text`() {
        val namespace = cacheNamespace("secret-server.example.org", 9982, "alice")
        assertFalse(namespace.value.contains("secret"))
        assertFalse(namespace.value.contains("example"))
    }
}
