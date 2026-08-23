@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package at.bernhardberger.tvheadend.sdk.android

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class TvheadendConnectivityTest {
    @Test
    fun `status is distinct and collection closes the Android callback`() = runTest {
        val backend = FakeConnectivityBackend(initiallyAvailable = false)
        val connectivity = TvheadendConnectivity(backend)
        val states = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            connectivity.status.take(4).toList()
        }

        backend.publish(available = false)
        backend.publish(available = true)
        backend.publish(available = true)
        backend.publish(available = false)

        assertEquals(
            listOf(
                TvheadendConnectivityStatus.UNKNOWN,
                TvheadendConnectivityStatus.UNAVAILABLE,
                TvheadendConnectivityStatus.AVAILABLE,
                TvheadendConnectivityStatus.UNAVAILABLE,
            ),
            states.await(),
        )
        assertTrue(backend.closed)
    }

    @Test
    fun `session retry runs only when connectivity becomes available`() = runTest {
        var retries = 0

        retryWhenAvailable(
            status = flowOf(
                TvheadendConnectivityStatus.UNKNOWN,
                TvheadendConnectivityStatus.UNAVAILABLE,
                TvheadendConnectivityStatus.AVAILABLE,
                TvheadendConnectivityStatus.UNAVAILABLE,
                TvheadendConnectivityStatus.AVAILABLE,
            ),
            retry = { retries += 1 },
        )

        assertEquals(2, retries)
    }

    @Test
    fun `platform registration aggregates networks and unregisters once`() {
        val platform = FakeConnectivityPlatform()
        val states = mutableListOf<Boolean>()
        val registration = ConnectivityRegistration(platform, states::add)

        registration.start()
        platform.available("wifi")
        platform.available("cellular")
        platform.lost("wifi")
        platform.lost("cellular")
        platform.unavailable()
        registration.close()
        registration.close()

        assertEquals(listOf(true, true, true, false, false), states)
        assertEquals(1, platform.unregisterCalls)
    }

    @Test
    fun `close during platform registration still unregisters`() {
        val platform = FakeConnectivityPlatform()
        lateinit var registration: ConnectivityRegistration
        registration = ConnectivityRegistration(platform) {}
        platform.onRegister = registration::close

        registration.start()

        assertEquals(1, platform.unregisterCalls)
    }
}

private class FakeConnectivityBackend(
    private val initiallyAvailable: Boolean,
) : ConnectivityBackend {
    private lateinit var listener: (Boolean) -> Unit
    internal var closed: Boolean = false
        private set

    override fun start(onAvailabilityChanged: (Boolean) -> Unit): CallbackRegistration {
        listener = onAvailabilityChanged
        listener(initiallyAvailable)
        return CallbackRegistration { closed = true }
    }

    internal fun publish(available: Boolean) {
        listener(available)
    }
}

private class FakeConnectivityPlatform : ConnectivityPlatform {
    private lateinit var listener: ConnectivityPlatformListener
    internal var onRegister: () -> Unit = {}
    internal var unregisterCalls: Int = 0
        private set

    override fun register(listener: ConnectivityPlatformListener) {
        this.listener = listener
        onRegister()
    }

    override fun unregister(listener: ConnectivityPlatformListener) {
        assertTrue(this.listener === listener)
        unregisterCalls += 1
    }

    internal fun available(network: Any) {
        listener.onAvailable(network)
    }

    internal fun lost(network: Any) {
        listener.onLost(network)
    }

    internal fun unavailable() {
        listener.onUnavailable()
    }
}
