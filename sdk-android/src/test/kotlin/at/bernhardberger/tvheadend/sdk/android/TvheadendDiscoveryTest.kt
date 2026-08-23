@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package at.bernhardberger.tvheadend.sdk.android

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class TvheadendDiscoveryTest {
    @Test
    fun `discovery resolves sorted snapshots and removes lost services`() = runTest {
        val backend = FakeNsdDiscoveryBackend()
        val discovery = TvheadendDiscovery(backend)
        val first = server(name = "Zulu", host = "192.0.2.2")
        val second = server(name = "Alpha", host = "192.0.2.1")
        val states = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            discovery.state.take(4).toList()
        }

        assertEquals("_htsp._tcp.", backend.serviceType)
        backend.resolve("z", first)
        backend.resolve("a", second)
        backend.lose("z")

        val emittedStates = states.await()
        assertEquals(
            listOf(
                TvheadendDiscoveryState.Discovering(emptyList()),
                TvheadendDiscoveryState.Discovering(listOf(first)),
                TvheadendDiscoveryState.Discovering(listOf(second, first)),
                TvheadendDiscoveryState.Discovering(listOf(second)),
            ),
            emittedStates,
        )
        val finalServers = (emittedStates.last() as TvheadendDiscoveryState.Discovering).servers
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (finalServers as MutableList<DiscoveredTvheadendServer>).add(first)
        }
        assertTrue(backend.closed)
    }

    @Test
    fun `start failure is typed terminal state that rejects later snapshots and closes registration`() = runTest {
        val backend = FakeNsdDiscoveryBackend()
        val discovery = TvheadendDiscovery(backend)
        val states = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            discovery.state.toList()
        }

        backend.fail(TvheadendDiscoveryFailure.PERMISSION_DENIED)
        backend.resolve("late", server(name = "Late", host = "192.0.2.30"))

        assertEquals(
            listOf(
                TvheadendDiscoveryState.Discovering(emptyList()),
                TvheadendDiscoveryState.Unavailable(
                    TvheadendDiscoveryFailure.PERMISSION_DENIED,
                ),
            ),
            states.await(),
        )
        assertTrue(backend.closed)
    }

    @Test
    fun `discovered endpoint string rendering is redacted`() {
        val server = server(name = "Private server", host = "192.0.2.10")

        assertEquals("DiscoveredTvheadendServer(<redacted>)", server.toString())
        assertEquals(
            "Discovering(servers=[DiscoveredTvheadendServer(<redacted>)])",
            TvheadendDiscoveryState.Discovering(listOf(server)).toString(),
        )
    }

    @Test
    fun `failed resolution retries once then permits a new service announcement`() {
        val platform = FakeNsdPlatform()
        val events = RecordingNsdDiscoveryListener()
        val registration = registration(platform, events)
        val first = serviceRecord("TVHeadend")
        val second = serviceRecord("TVHeadend")

        registration.start()
        platform.started()
        platform.found(first)
        platform.resolutions.single().listener.onResolveFailed()
        platform.resolutions.last().listener.onResolveFailed()
        platform.found(second)

        assertEquals(3, platform.resolutions.size)
        platform.resolutions.last().listener.onServiceResolved(
            ResolvedNsdService("TVHeadend", "192.0.2.20", 9_982),
        )
        assertEquals(listOf("TVHeadend"), events.resolvedKeys)
    }

    @Test
    fun `malformed resolution is retried once`() {
        val platform = FakeNsdPlatform()
        val events = RecordingNsdDiscoveryListener()
        val registration = registration(platform, events)
        val first = serviceRecord("TVHeadend")

        registration.start()
        platform.started()
        platform.found(first)
        platform.resolutions.single().listener.onServiceResolved(
            ResolvedNsdService("TVHeadend", null, 9_982),
        )

        assertEquals(2, platform.resolutions.size)
        platform.resolutions.last().listener.onServiceResolved(
            ResolvedNsdService("TVHeadend", "192.0.2.20", 9_982),
        )
        assertEquals(listOf("TVHeadend"), events.resolvedKeys)
    }

    @Test
    fun `old resolution cannot publish after same-name loss and rediscovery`() {
        val platform = FakeNsdPlatform()
        val events = RecordingNsdDiscoveryListener()
        val registration = registration(platform, events)
        val old = serviceRecord("TVHeadend")
        val replacement = serviceRecord("TVHeadend")

        registration.start()
        platform.started()
        platform.found(old)
        platform.lost(old)
        platform.found(replacement)
        platform.resolutions.first().listener.onServiceResolved(
            ResolvedNsdService("Old", "192.0.2.10", 9_982),
        )

        assertTrue(events.resolvedKeys.isEmpty())
        assertEquals(2, platform.resolutions.size)
        platform.resolutions.last().listener.onServiceResolved(
            ResolvedNsdService("Replacement", "192.0.2.11", 9_982),
        )
        assertEquals(listOf("TVHeadend"), events.resolvedKeys)
        assertEquals("Replacement", events.resolvedServers.single().name)
    }

    @Test
    fun `legacy resolutions are serialized across discovery registrations`() {
        val platform = FakeNsdPlatform()
        val coordinator = NsdResolutionCoordinator()
        val first = registration(platform, RecordingNsdDiscoveryListener(), coordinator)
        val second = registration(platform, RecordingNsdDiscoveryListener(), coordinator)

        first.start()
        second.start()
        platform.started(index = 0)
        platform.started(index = 1)
        platform.found(serviceRecord("First"), index = 0)
        platform.found(serviceRecord("Second"), index = 1)

        assertEquals(listOf("First"), platform.resolutions.map { it.service.key })
        platform.resolutions.single().listener.onResolveFailed()
        assertEquals(listOf("First", "Second"), platform.resolutions.map { it.service.key })
    }

    @Test
    fun `closing a queued registration retires its shared resolution request`() {
        val platform = FakeNsdPlatform()
        val coordinator = NsdResolutionCoordinator()
        val first = registration(platform, RecordingNsdDiscoveryListener(), coordinator)
        val second = registration(platform, RecordingNsdDiscoveryListener(), coordinator)

        first.start()
        second.start()
        platform.started(index = 0)
        platform.started(index = 1)
        platform.found(serviceRecord("First"), index = 0)
        platform.found(serviceRecord("Second"), index = 1)

        assertEquals(listOf("First"), platform.resolutions.map { it.service.key })
        second.close()
        platform.resolutions.single().listener.onServiceResolved(
            ResolvedNsdService("First", "192.0.2.20", 9_982),
        )
        assertEquals(listOf("First"), platform.resolutions.map { it.service.key })
    }

    @Test
    fun `service type accepts Android local-domain suffix`() {
        val platform = FakeNsdPlatform()
        val registration = registration(platform, RecordingNsdDiscoveryListener())

        registration.start()
        platform.started()
        platform.found(serviceRecord("TVHeadend", serviceType = "_HTSP._TCP.local."))

        assertEquals(listOf("TVHeadend"), platform.resolutions.map { it.service.key })
    }

    @Test
    fun `legacy reflection targets exist in the compile SDK`() {
        NsdManager::class.java.getMethod(
            "resolveService",
            NsdServiceInfo::class.java,
            NsdManager.ResolveListener::class.java,
        )
        NsdServiceInfo::class.java.getMethod("getHost")
    }

    @Test
    fun `close before discovery start retries one failed stop and then cleans up`() {
        val platform = FakeNsdPlatform(stopFailures = 1)
        val registration = registration(platform, RecordingNsdDiscoveryListener())

        registration.start()
        registration.close()
        platform.started()

        assertEquals(2, platform.stopCalls)
        platform.stopped()
        registration.close()
        assertEquals(2, platform.stopCalls)
    }

    @Test
    fun `start failure reports terminal state and stops the registered listener`() {
        val platform = FakeNsdPlatform()
        val events = RecordingNsdDiscoveryListener()
        val registration = registration(platform, events)

        registration.start()
        platform.startFailed()

        assertEquals(listOf(TvheadendDiscoveryFailure.START_FAILED), events.failures)
        assertEquals(1, platform.stopCalls)
        platform.stopped()
        registration.close()
        assertEquals(1, platform.stopCalls)
    }
}

private class FakeNsdDiscoveryBackend : NsdDiscoveryBackend {
    private lateinit var listener: NsdDiscoveryListener
    internal lateinit var serviceType: String
        private set
    internal var closed: Boolean = false
        private set

    override fun start(
        serviceType: String,
        listener: NsdDiscoveryListener,
    ): CallbackRegistration {
        this.serviceType = serviceType
        this.listener = listener
        return CallbackRegistration { closed = true }
    }

    internal fun resolve(key: String, server: DiscoveredTvheadendServer) {
        listener.onResolved(key, server)
    }

    internal fun lose(key: String) {
        listener.onLost(key)
    }

    internal fun fail(failure: TvheadendDiscoveryFailure) {
        listener.onFailed(failure)
    }
}

private class FakeNsdPlatform(
    private var stopFailures: Int = 0,
) : NsdPlatform {
    internal data class Resolution(
        val service: NsdServiceRecord,
        val listener: NsdPlatformResolveListener,
    )

    private val discoveryListeners = mutableListOf<NsdPlatformDiscoveryListener>()
    internal val resolutions = mutableListOf<Resolution>()
    internal var stopCalls: Int = 0
        private set

    override fun discoverServices(serviceType: String, listener: NsdPlatformDiscoveryListener) {
        assertEquals(HTSP_SERVICE_TYPE, serviceType)
        discoveryListeners += listener
    }

    override fun stopServiceDiscovery(listener: NsdPlatformDiscoveryListener) {
        stopCalls += 1
        if (stopFailures > 0) {
            stopFailures -= 1
            listener.onStopDiscoveryFailed()
        }
    }

    override fun resolveService(service: NsdServiceRecord, listener: NsdPlatformResolveListener) {
        resolutions += Resolution(service, listener)
    }

    internal fun started(index: Int = 0) {
        discoveryListeners[index].onDiscoveryStarted()
    }

    internal fun startFailed(index: Int = 0) {
        discoveryListeners[index].onStartDiscoveryFailed()
    }

    internal fun stopped(index: Int = 0) {
        discoveryListeners[index].onDiscoveryStopped()
    }

    internal fun found(service: NsdServiceRecord, index: Int = 0) {
        discoveryListeners[index].onServiceFound(service)
    }

    internal fun lost(service: NsdServiceRecord, index: Int = 0) {
        discoveryListeners[index].onServiceLost(service)
    }
}

private class RecordingNsdDiscoveryListener : NsdDiscoveryListener {
    internal val resolvedKeys = mutableListOf<String>()
    internal val resolvedServers = mutableListOf<DiscoveredTvheadendServer>()
    internal val failures = mutableListOf<TvheadendDiscoveryFailure>()

    override fun onResolved(key: String, server: DiscoveredTvheadendServer) {
        resolvedKeys += key
        resolvedServers += server
    }

    override fun onLost(key: String) = Unit

    override fun onFailed(failure: TvheadendDiscoveryFailure) {
        failures += failure
    }
}

private fun registration(
    platform: NsdPlatform,
    listener: NsdDiscoveryListener,
    coordinator: NsdResolutionCoordinator = NsdResolutionCoordinator(),
): NsdDiscoveryRegistration =
    NsdDiscoveryRegistration(
        platform = platform,
        coordinator = coordinator,
        serviceType = HTSP_SERVICE_TYPE,
        listener = listener,
    )

private fun serviceRecord(
    name: String,
    serviceType: String = "_HTSP._TCP",
): NsdServiceRecord =
    NsdServiceRecord(
        key = name,
        serviceType = serviceType,
        platformValue = Any(),
    )

private fun server(name: String, host: String): DiscoveredTvheadendServer =
    DiscoveredTvheadendServer(name = name, host = host, port = 9_982)
