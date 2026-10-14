@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Instant

internal class EpgCreditTest {
    @Test
    fun `known roles are interned and unknown roles have exact value semantics`() {
        val roles = listOf(EpgCreditRole.ACTOR, EpgCreditRole.DIRECTOR, EpgCreditRole.GUEST,
            EpgCreditRole.PRESENTER, EpgCreditRole.WRITER)
        assertEquals(listOf("actor", "director", "guest", "presenter", "writer"), roles.map { it.raw })
        for (role in roles) {
            assertSame(role, EpgCreditRole.of(role.raw))
            assertEquals(role.raw, role.toString())
        }
        val unknown = EpgCreditRole.of("composer")
        assertEquals(unknown, EpgCreditRole.of("composer"))
        assertEquals(unknown.hashCode(), EpgCreditRole.of("composer").hashCode())
        assertEquals("composer", unknown.toString())
        assertNotEquals(unknown, EpgCreditRole.WRITER)
        assertNotEquals(EpgCreditRole.ACTOR, EpgCreditRole.of("Actor"))
        assertEquals("", EpgCreditRole.of("").raw)
    }

    @Test
    fun `event credits are immutable redacted and participate in equality and cached hash`() {
        val credit = EpgCredit("Private person", EpgCreditRole.ACTOR)
        val input = mutableListOf(credit)
        val start = Instant.fromEpochSeconds(10)
        fun event(credits: List<EpgCredit>? = null): EpgEvent = EpgEvent.create(
            EventId(1), start = start, stop = start, credits = credits,
        )
        val populated = event(input)
        input.clear()
        assertEquals(listOf(credit), populated.credits)
        assertThrows(UnsupportedOperationException::class.java) {
            (populated.credits as MutableList<EpgCredit>).clear()
        }
        assertEquals(event(listOf(credit)), populated)
        assertEquals(event(listOf(credit)).hashCode(), populated.hashCode())
        assertNotEquals(event(), populated)
        assertNotEquals(event().hashCode(), populated.hashCode())
        assertNull(event().credits)
        assertEquals(emptyList<EpgCredit>(), event(emptyList()).credits)
        assertEquals("EpgCredit(<redacted>)", credit.toString())
        assertEquals("EpgEvent(<redacted>)", populated.toString())
    }
}
