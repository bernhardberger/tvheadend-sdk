package at.bernhardberger.tvheadend.sdk.core

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class StreamProfilesTest {
    @Test
    fun `profile identity accepts only canonical lowercase UUIDs and stays redacted`() {
        val id = StreamProfileId("0123456789abcdef0123456789abcdef")

        assertEquals("0123456789abcdef0123456789abcdef", id.value)
        assertEquals("StreamProfileId(<redacted>)", id.toString())
        listOf(
            "0123456789abcdef0123456789abcde",
            "0123456789abcdef0123456789abcdef0",
            "0123456789ABCDEF0123456789ABCDEF",
            "01234567-89ab-cdef-0123-456789abcdef",
            "0123456789abcdef0123456789abcdeg",
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { StreamProfileId(invalid) }
        }
    }

    @Test
    fun `available profiles preserve wire order and own an immutable snapshot`() {
        val id = StreamProfileId("0123456789abcdef0123456789abcdef")
        val source = mutableListOf(StreamProfile(id, "Pass", "Original streams"))
        val currentSession = currentSession()
        val available = StreamProfilesResult.Available.create(source, currentSession)
        source.clear()

        assertEquals(listOf(StreamProfile(id, "Pass", "Original streams")), available.profiles)
        assertSame(currentSession, available.originatingSession)
        assertThrows(UnsupportedOperationException::class.java) {
            (available.profiles as MutableList<*>).clear()
        }
        assertEquals("StreamProfile(<redacted>)", available.profiles.single().toString())
        assertEquals("StreamProfilesResult.Available(<redacted>)", available.toString())
    }

    @Test
    fun `inherited discovery default propagates pre-existing caller cancellation`() = runTest {
        val session = sessionInheritingDefaults()
        val currentSession = currentSession()
        var returned = false
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel(CancellationException("fixed caller cancellation"))
            session.getStreamProfiles(currentSession)
            returned = true
        }

        caller.join()

        assertTrue(caller.isCancelled)
        assertFalse(returned)
    }

    private fun currentSession(): CurrentSessionObservation = requireNotNull(
        SessionObservation.create(
            sessionState = SessionState.Ready(
                ServerCapabilities.create(CapabilityAccess.UNKNOWN, CapabilityAccess.UNKNOWN),
            ),
            channelState = ChannelRepositoryState.Current(ChannelCatalog.create()),
            epgState = EpgRepositoryState.Current(EpgSnapshot.create()),
            dvrState = DvrRepositoryState.Current(DvrSnapshot.create()),
        ).currentSession,
    )
}

@Suppress("UNCHECKED_CAST")
private fun sessionInheritingDefaults(): TvheadendSession = Proxy.newProxyInstance(
    TvheadendSession::class.java.classLoader,
    arrayOf(TvheadendSession::class.java),
) { proxy, method, arguments ->
    if (method.isDefault) {
        InvocationHandler.invokeDefault(proxy, method, *(arguments ?: emptyArray()))
    } else {
        error("Unexpected session call")
    }
} as TvheadendSession
