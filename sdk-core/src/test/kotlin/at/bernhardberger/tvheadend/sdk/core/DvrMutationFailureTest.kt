@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class DvrMutationFailureTest {
    @Test
    fun `display message is normalized stripped bounded and optional`() {
        fun failed(message: String?) = DvrMutationResult.Failed(TvheadendFailure.SERVER_REJECTED, false, message)
        assertEquals("Café  denied", failed(" \tCafe\u0301\n\u202edenied\u0000 ").serverMessage)
        assertEquals("x".repeat(256), failed("x".repeat(300)).serverMessage)
        assertEquals("x".repeat(255), failed("x".repeat(255) + "😀").serverMessage)
        assertNull(failed("\n\u0000 \t").serverMessage)
        assertNull(failed(null).serverMessage)
        assertNull(DvrMutationResult.Failed(TvheadendFailure.SERVER_REJECTED, false).serverMessage)
        for (failure in listOf(TvheadendFailure.TIMEOUT, TvheadendFailure.ACCESS_DENIED,
            TvheadendFailure.CONNECTION_CHANGED, TvheadendFailure.NOT_READY)) {
            assertNull(DvrMutationResult.Failed(failure, true, "display message").serverMessage)
        }
    }

    @Test
    fun `supplementary format characters and line separators cannot survive display sanitization`() {
        for (codePoint in listOf(0xE0001, 0xE0020, 0xE007F, 0x1BCA0, 0x1BCA3, 0x1D173, 0x1D17A, 0x13430, 0x1343F,
            0x2028, 0x2029)) {
            val character = String(Character.toChars(codePoint))
            assertNull(DvrMutationResult.Failed(TvheadendFailure.SERVER_REJECTED, false, character).serverMessage)
            assertEquals("a b", DvrMutationResult.Failed(
                TvheadendFailure.SERVER_REJECTED, false, "a${character}b",
            ).serverMessage)
        }
    }

    @Test
    fun `input is bounded before normalization without splitting supplementary characters`() {
        fun failed(message: String) = DvrMutationResult.Failed(TvheadendFailure.SERVER_REJECTED, false, message)
        assertNull(failed(" ".repeat(1024) + "beyond input bound").serverMessage)
        assertNull(failed(" ".repeat(1023) + "😀").serverMessage)
        assertEquals("😀", failed(" ".repeat(1022) + "😀").serverMessage)
        assertEquals("x".repeat(254) + "😀", failed("x".repeat(254) + "😀tail").serverMessage)
    }

    @Test
    fun `display message does not participate in identity or diagnostics`() {
        val first = DvrMutationResult.Failed(TvheadendFailure.SERVER_REJECTED, false, "first message")
        val second = DvrMutationResult.Failed(TvheadendFailure.SERVER_REJECTED, false, "second message")
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(first.toString(), second.toString())
        assertFalse(first.toString().contains("first message"))
        assertEquals(DvrMutationResult.Failed(TvheadendFailure.SERVER_REJECTED, false), first)
    }
}
