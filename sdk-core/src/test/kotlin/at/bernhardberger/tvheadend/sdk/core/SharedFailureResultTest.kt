package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SharedFailureResultTest {
    @Test
    fun `failure results compare by value and conflate equal observations`() {
        val constructors = listOf<(TvheadendFailure) -> Any>(
            { DvrMutationResult.Failed(it, mayHaveApplied = true) },
            { DvrProgressResult.Failed(it, mayHaveApplied = true) },
            { DvrCutpointsResult.Failed(it) },
            { StreamProfilesResult.Failed(it) },
            { EpgSearchResult.Failed(it) },
        )
        constructors.forEach { create ->
            val original = create(TvheadendFailure.MALFORMED_RESPONSE)
            val equal = create(TvheadendFailure.MALFORMED_RESPONSE)
            assertEquals(original, equal)
            assertEquals(original.hashCode(), equal.hashCode())
            assertNotEquals(original, create(TvheadendFailure.TRANSPORT_UNAVAILABLE))
            val state = MutableStateFlow(original)
            state.value = equal
            assertSame(original, state.value)
            val stage = if (original is DvrMutationResult.Failed || original is DvrProgressResult.Failed) {
                ", mayHaveApplied=true"
            } else {
                ""
            }
            assertEquals(
                "${original.javaClass.enclosingClass.simpleName}.Failed(failure=MALFORMED_RESPONSE$stage)",
                original.toString(),
            )
        }
    }

    @Test
    fun `artwork failures have value equality and conflate without exposing payloads`() {
        val original = ArtworkLoadResult.Unavailable(TvheadendFailure.FILE_UNAVAILABLE)
        val equal = ArtworkLoadResult.Unavailable(TvheadendFailure.FILE_UNAVAILABLE)
        assertEquals(original, equal)
        assertEquals(original.hashCode(), equal.hashCode())
        assertNotEquals(original, original.copy(failure = TvheadendFailure.CONNECTION_CHANGED))
        val state = MutableStateFlow(original)
        state.value = equal
        assertSame(original, state.value)
        assertEquals("ArtworkLoadResult.Unavailable(failure=FILE_UNAVAILABLE)", original.toString())
    }

    @Test
    fun `application uncertainty remains part of failure equality`() {
        val mutation = DvrMutationResult.Failed(TvheadendFailure.TRANSPORT_UNAVAILABLE, mayHaveApplied = false)
        val progress = DvrProgressResult.Failed(TvheadendFailure.TRANSPORT_UNAVAILABLE, mayHaveApplied = false)
        assertNotEquals(mutation, mutation.copy(mayHaveApplied = true))
        assertNotEquals(progress, progress.copy(mayHaveApplied = true))
    }
}
