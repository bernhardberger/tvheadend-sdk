package at.bernhardberger.tvheadend.sdk.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class TvheadendFailureTest {
    @Test
    fun `known failures expose stable labels and recovery predicates`() {
        val cases = listOf(
            Case(TvheadendFailure.NOT_READY, "NOT_READY", transient = true),
            Case(TvheadendFailure.OBSERVATION_EXPIRED, "OBSERVATION_EXPIRED", transient = true, fresh = true),
            Case(TvheadendFailure.CONNECTION_CHANGED, "CONNECTION_CHANGED", transient = true, fresh = true),
            Case(TvheadendFailure.SERVER_REJECTED, "SERVER_REJECTED"),
            Case(TvheadendFailure.MALFORMED_RESPONSE, "MALFORMED_RESPONSE"),
            Case(TvheadendFailure.ACCESS_DENIED, "ACCESS_DENIED", access = true),
            Case(TvheadendFailure.CONNECTION_LIMIT, "CONNECTION_LIMIT", transient = true),
            Case(TvheadendFailure.TIMEOUT, "TIMEOUT", transient = true),
            Case(TvheadendFailure.TRANSPORT_UNAVAILABLE, "TRANSPORT_UNAVAILABLE", transient = true),
            Case(TvheadendFailure.NOT_SUPPORTED, "NOT_SUPPORTED", unsupported = true),
            Case(TvheadendFailure.FILE_UNAVAILABLE, "FILE_UNAVAILABLE"),
        )
        assertEquals(11, cases.map { it.failure }.toSet().size)
        cases.forEach { case ->
            assertEquals(case.label, case.failure.toString())
            assertEquals(case.transient, case.failure.isTransient, case.label)
            assertEquals(case.fresh, case.failure.requiresFreshObservation, case.label)
            assertEquals(case.access, case.failure.isAccessRelated, case.label)
            assertEquals(case.unsupported, case.failure.isUnsupported, case.label)
        }
    }

    private data class Case(
        val failure: TvheadendFailure,
        val label: String,
        val transient: Boolean = false,
        val fresh: Boolean = false,
        val access: Boolean = false,
        val unsupported: Boolean = false,
    )
}
