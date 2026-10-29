package at.bernhardberger.tvheadend.sdk.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ChannelIconTest {
    @Test
    fun `image cache selectors retain their typed artwork ID`() {
        for (selector in listOf("imagecache/12", "/imagecache/12")) {
            assertEquals(ChannelIcon.ImageCache(ArtworkId(12)), ChannelIcon.parse(selector))
        }
    }

    @Test
    fun `HTTP and HTTPS URLs preserve their exact spelling and have value equality`() {
        val urls = listOf(
            "http://example.invalid/logo.png",
            "https://example.invalid:8443/logos/a%20b.png?size=2#image",
            "HTTPS://Example.Invalid/logo.png",
            "http://127.0.0.1/logo.png",
            "https://[::1]:8443/logo.png",
            "https://example.invalid/logos/Das Erste Österreich|HD.png?channel=Schöne Welt|HD",
            "http://user:password@example.invalid/logo Ä|HD.png?name=Österreich HD",
            "https://example.invalid/a%GG.png",
        )
        urls.forEach { url ->
            val icon = ChannelIcon.parse(url) as ChannelIcon.RemoteUrl
            assertTrue(icon.url == url)
            assertEquals(icon, ChannelIcon.parse(url))
            assertEquals(icon.hashCode(), ChannelIcon.parse(url).hashCode())
            assertEquals("ChannelIcon.RemoteUrl(<redacted>)", icon.toString())
            assertNull(ArtworkId.parse(url))
        }
    }

    @Test
    fun `absent malformed and unsupported selectors are dropped`() {
        val selectors = listOf(
            null, "", "imagecache/0", "imagecache/-1", "imagecache/2147483648",
            "picon/logo.png", "/logo.png", "//example.invalid/logo.png",
            "file:///logo.png", "ftp://example.invalid/logo.png", "data:image/png;base64,abc",
            "http:logo.png", "http:///logo.png", "https://", "http://?image=1",
            "https://bad host/logo Ä.png", "https://user:%GG@example.invalid/logo.png",
            "https://bad_host/logo.png", "https://[broken/logo.png",
            "https://example.invalid:abc/logo.png", "https://example.invalid:65536/logo.png",
            "https://example.invalid:-2/logo.png", " https://example.invalid/logo.png",
            "https://example.invalid/logo.png\n",
        )
        selectors.forEach { assertNull(ChannelIcon.parse(it)) }
    }

    @Test
    fun `nested model strings redact icon URLs including query and user information`() {
        val icon = ChannelIcon.parse("https://user:password@example.invalid/logo.png?token=secret")
        assertTrue(icon is ChannelIcon.RemoteUrl)
        assertEquals("Channel(<redacted>)", Channel.create(ChannelId(1), icon = icon).toString())
        assertEquals("ChannelTag(<redacted>)", ChannelTag.create(ChannelTagId(1), icon = icon).toString())
        assertEquals("ChannelIcon.ImageCache(<redacted>)", ChannelIcon.ImageCache(ArtworkId(1)).toString())
    }
}
