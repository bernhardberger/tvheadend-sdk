@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class SharedFailureResultTest {
    @Test
    fun `infrastructure failures compare by value and render only safe labels`() {
        val constructors = listOf<(TvheadendFailure) -> Any>(
            { RecordingFileResult.Failed(it) },
            { SubscriptionOperationResult.Failed(it) },
        )
        constructors.forEach { create ->
            val failure = create(TvheadendFailure.FILE_UNAVAILABLE)
            val equal = create(TvheadendFailure.FILE_UNAVAILABLE)
            assertEquals(failure, equal)
            assertEquals(failure.hashCode(), equal.hashCode())
            assertNotEquals(failure, create(TvheadendFailure.CONNECTION_CHANGED))
            assertEquals(
                "${failure.javaClass.enclosingClass.simpleName}.Failed(failure=FILE_UNAVAILABLE)",
                failure.toString(),
            )
        }
    }
}
