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
    fun `namespace derivation is a versioned 32 character lowercase hex digest`() {
        val namespace = cacheNamespace("tvh.example.org", 9982, "alice")
        assertEquals(35, namespace.value.length)
        assertTrue(namespace.value.startsWith("v2-"))
        assertTrue(namespace.value.drop(3).all { char -> char in '0'..'9' || char in 'a'..'f' })
    }

    @Test
    fun `namespace derivation output is pinned for fixed inputs`() {
        // Existing caches are addressed by these values; any change orphans them.
        assertEquals(
            listOf(
                "v2-5fa4599b170752f690f29e9a4fe8a25a",
                "v2-21d022c6224bb557d540bc8507a24827",
                "v2-07dc07df5a189fbdbe6db6fb787b0898",
            ),
            listOf(
                cacheNamespace("tvh.example.org", 9982, "alice").value,
                cacheNamespace("tvh.example.org", 9982, "").value,
                cacheNamespace("::1", 2, "3:4:u").value,
            ),
        )
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
        assertEquals(35, anonymous.value.length)
    }

    @Test
    fun `delimiter-containing profile tuples do not share a namespace`() {
        assertNotEquals(cacheNamespace("::1", 2, "3:4:u"), cacheNamespace("::1:2:3", 4, "u"))
        assertNotEquals(cacheNamespace("a:1", 2, ""), cacheNamespace("a", 1, "2:"))
        assertNotEquals(cacheNamespace("a\u0000b", 2, "c"), cacheNamespace("a", 2, "b\u0000c"))
    }

    @Test
    fun `namespace value never contains the raw host in clear text`() {
        val namespace = cacheNamespace("secret-server.example.org", 9982, "alice")
        assertFalse(namespace.value.contains("secret"))
        assertFalse(namespace.value.contains("example"))
    }
}
