package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class SessionRecoveryDispositionTest {
    @Test
    fun `equal failures preserve unavailable state flow conflation`() {
        val first = SessionFailure.synchronizationFailed(TvheadendFailure.TIMEOUT)
        val equal = SessionFailure.synchronizationFailed(TvheadendFailure.TIMEOUT)
        assertNotSame(first, equal)
        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertEquals(1, setOf(first, equal).size)
        assertNotEquals(first, SessionFailure.synchronizationFailed(TvheadendFailure.SERVER_REJECTED))
        assertNotEquals(first, SessionFailure.SERVER_UNREACHABLE)
        val initial = SessionState.Unavailable(first)
        val state = MutableStateFlow(initial)
        state.value = SessionState.Unavailable(equal)
        assertSame(initial, state.value)
        assertEquals("SessionFailure(kind=SYNCHRONIZATION_FAILED, operationFailure=TIMEOUT)", first.toString())
        assertEquals("SessionFailure(kind=SERVER_UPGRADE_REQUIRED, operationFailure=null)", SessionFailure.SERVER_UPGRADE_REQUIRED.toString())
    }

    @Test
    fun `every session failure exposes SDK-authored recovery guidance`() {
        val cases = listOf(
            SessionFailure.AUTHENTICATION_REJECTED to SessionRecoveryDisposition.PROFILE_CHANGE_REQUIRED,
            SessionFailure.SERVER_UNREACHABLE to SessionRecoveryDisposition.AUTOMATIC_BACKOFF,
            SessionFailure.NETWORK_UNAVAILABLE to SessionRecoveryDisposition.AUTOMATIC_BACKOFF,
            SessionFailure.INCOMPATIBLE_SERVER to SessionRecoveryDisposition.PROFILE_CHANGE_REQUIRED,
            SessionFailure.SERVER_UPGRADE_REQUIRED to SessionRecoveryDisposition.EXPLICIT_RETRY,
            SessionFailure.METADATA_OVERFLOW to SessionRecoveryDisposition.AUTOMATIC_BACKOFF,
            SessionFailure.TRANSPORT_UNAVAILABLE to SessionRecoveryDisposition.AUTOMATIC_BACKOFF,
            SessionFailure.UNEXPECTED_FAILURE to SessionRecoveryDisposition.EXPLICIT_RETRY,
        )

        cases.forEach { (failure, expected) ->
            assertEquals(expected, failure.recoveryDisposition, failure.toString())
            assertNull(failure.operationFailure)
            assertNotEquals(SessionFailureKind.SYNCHRONIZATION_FAILED, failure.kind)
        }
    }

    @Test
    fun `every synchronization failure exposes matching nested guidance`() {
        val expected = mapOf(
            TvheadendFailure.SERVER_REJECTED to SessionRecoveryDisposition.EXPLICIT_RETRY,
            TvheadendFailure.ACCESS_DENIED to SessionRecoveryDisposition.PROFILE_CHANGE_REQUIRED,
            TvheadendFailure.CONNECTION_LIMIT to SessionRecoveryDisposition.AUTOMATIC_BACKOFF,
            TvheadendFailure.TIMEOUT to SessionRecoveryDisposition.AUTOMATIC_BACKOFF,
            TvheadendFailure.TRANSPORT_UNAVAILABLE to SessionRecoveryDisposition.AUTOMATIC_BACKOFF,
            TvheadendFailure.NOT_SUPPORTED to SessionRecoveryDisposition.PROFILE_CHANGE_REQUIRED,
            TvheadendFailure.MALFORMED_RESPONSE to SessionRecoveryDisposition.EXPLICIT_RETRY,
            TvheadendFailure.NOT_READY to SessionRecoveryDisposition.EXPLICIT_RETRY,
            TvheadendFailure.OBSERVATION_EXPIRED to SessionRecoveryDisposition.EXPLICIT_RETRY,
            TvheadendFailure.CONNECTION_CHANGED to SessionRecoveryDisposition.EXPLICIT_RETRY,
            TvheadendFailure.FILE_UNAVAILABLE to SessionRecoveryDisposition.EXPLICIT_RETRY,
        )

        expected.forEach { (failure, disposition) ->
            assertEquals(disposition, failure.recoveryDisposition, failure.toString())
            assertEquals(
                disposition,
                SessionFailure.synchronizationFailed(failure).recoveryDisposition,
                failure.toString(),
            )
            val result = SessionFailure.synchronizationFailed(failure)
            assertSame(SessionFailureKind.SYNCHRONIZATION_FAILED, result.kind)
            assertSame(failure, result.operationFailure)
        }
    }

    @Test
    fun `callers cannot construct arbitrary kind and payload combinations`() {
        // Kotlin's synthetic constructor bridge is not callable from Kotlin or Java source.
        assertTrue(SessionFailure::class.java.constructors.all { it.isSynthetic })
        val factories = SessionFailure::class.java.declaredMethods.filter {
            java.lang.reflect.Modifier.isPublic(it.modifiers) &&
                java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == SessionFailure::class.java
        }
        assertEquals(listOf("synchronizationFailed"), factories.map { it.name })
        assertEquals(listOf(TvheadendFailure::class.java), factories.single().parameterTypes.toList())
    }
}
