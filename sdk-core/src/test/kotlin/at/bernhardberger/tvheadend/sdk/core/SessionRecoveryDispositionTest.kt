package at.bernhardberger.tvheadend.sdk.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SessionRecoveryDispositionTest {
    @Test
    fun `every session failure exposes SDK-authored recovery guidance`() {
        val cases = listOf(
            SessionFailure.AuthenticationRejected to SessionRecoveryDisposition.PROFILE_CHANGE_REQUIRED,
            SessionFailure.PermissionDenied to SessionRecoveryDisposition.PROFILE_CHANGE_REQUIRED,
            SessionFailure.ServerUnreachable to SessionRecoveryDisposition.AUTOMATIC_BACKOFF,
            SessionFailure.NetworkUnavailable to SessionRecoveryDisposition.AUTOMATIC_BACKOFF,
            SessionFailure.IncompatibleServer to SessionRecoveryDisposition.PROFILE_CHANGE_REQUIRED,
            SessionFailure.NoChannels to SessionRecoveryDisposition.EXPLICIT_RETRY,
            SessionFailure.TransportUnavailable to SessionRecoveryDisposition.AUTOMATIC_BACKOFF,
            SessionFailure.UnexpectedFailure to SessionRecoveryDisposition.EXPLICIT_RETRY,
        )

        cases.forEach { (failure, expected) ->
            assertEquals(expected, failure.recoveryDisposition, failure.toString())
        }
    }

    @Test
    fun `every synchronization failure exposes matching nested guidance`() {
        val expected = mapOf(
            SessionOperationFailure.SERVER_REJECTED to SessionRecoveryDisposition.EXPLICIT_RETRY,
            SessionOperationFailure.ACCESS_DENIED to SessionRecoveryDisposition.PROFILE_CHANGE_REQUIRED,
            SessionOperationFailure.CONNECTION_LIMIT to SessionRecoveryDisposition.AUTOMATIC_BACKOFF,
            SessionOperationFailure.TIMEOUT to SessionRecoveryDisposition.AUTOMATIC_BACKOFF,
            SessionOperationFailure.TRANSPORT_UNAVAILABLE to SessionRecoveryDisposition.AUTOMATIC_BACKOFF,
            SessionOperationFailure.NOT_SUPPORTED to SessionRecoveryDisposition.PROFILE_CHANGE_REQUIRED,
        )

        assertEquals(SessionOperationFailure.entries.toSet(), expected.keys)
        expected.forEach { (failure, disposition) ->
            assertEquals(disposition, failure.recoveryDisposition, failure.name)
            assertEquals(
                disposition,
                SessionFailure.SynchronizationFailed(failure).recoveryDisposition,
                failure.name,
            )
        }
    }
}
