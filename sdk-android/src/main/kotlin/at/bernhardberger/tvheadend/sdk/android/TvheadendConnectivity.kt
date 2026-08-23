@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package at.bernhardberger.tvheadend.sdk.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/** Observes the Android default network without retaining an application coroutine scope. */
public class TvheadendConnectivity internal constructor(
    private val backend: ConnectivityBackend,
) {
    public constructor(context: Context) : this(
        AndroidConnectivityBackend(
            requireNotNull((context.applicationContext ?: context).getSystemService(ConnectivityManager::class.java)) {
                "Connectivity service is unavailable"
            },
        ),
    )

    /** Cold network-availability state; collection owns callback registration and cleanup. */
    public val status: Flow<TvheadendConnectivityStatus> = callbackFlow {
        trySend(TvheadendConnectivityStatus.UNKNOWN)
        val registration = backend.start { available ->
            trySend(
                if (available) {
                    TvheadendConnectivityStatus.AVAILABLE
                } else {
                    TvheadendConnectivityStatus.UNAVAILABLE
                },
            )
        }
        awaitClose(registration::close)
    }.conflate().distinctUntilChanged()

    /**
     * Interrupts the selected session's retry delay whenever a default network becomes available.
     *
     * This function runs until its caller cancels collection and retries once at collection start if
     * a default network already exists. The session remains the only owner of connection policy;
     * command outcomes that require profile changes are left to the application.
     */
    public suspend fun retrySessionWhenAvailable(session: TvheadendSession) {
        retryWhenAvailable(status) {
            session.retry()
        }
    }
}

/** Android's current knowledge of the application default network. */
public enum class TvheadendConnectivityStatus {
    /** Android has not reported availability, including when callback registration fails. */
    UNKNOWN,

    /** Android reports an application default network. */
    AVAILABLE,

    /** Android reports that no application default network is available. */
    UNAVAILABLE,
}

internal suspend fun retryWhenAvailable(
    status: Flow<TvheadendConnectivityStatus>,
    retry: suspend () -> Unit,
) {
    status.collect { current ->
        if (current == TvheadendConnectivityStatus.AVAILABLE) {
            retry()
        }
    }
}

internal fun interface ConnectivityBackend {
    fun start(onAvailabilityChanged: (Boolean) -> Unit): CallbackRegistration
}

internal fun interface CallbackRegistration {
    fun close()
}

private class AndroidConnectivityBackend(
    private val manager: ConnectivityManager,
) : ConnectivityBackend {
    override fun start(onAvailabilityChanged: (Boolean) -> Unit): CallbackRegistration {
        val registration = ConnectivityRegistration(
            platform = AndroidConnectivityPlatform(manager),
            onAvailabilityChanged = onAvailabilityChanged,
        )
        registration.start()
        return registration
    }
}

internal class ConnectivityRegistration(
    private val platform: ConnectivityPlatform,
    private val onAvailabilityChanged: (Boolean) -> Unit,
) : CallbackRegistration {
    private val lock = Any()
    private val availableNetworks = mutableSetOf<Any>()
    private var registered = false
    private var closed = false

    private val listener = object : ConnectivityPlatformListener {
        override fun onAvailable(network: Any) {
            update(network, available = true)
        }

        override fun onLost(network: Any) {
            update(network, available = false)
        }

        override fun onUnavailable() {
            publishUnavailable()
        }
    }

    fun start() {
        try {
            platform.register(listener)
        } catch (_: SecurityException) {
            return
        } catch (_: RuntimeException) {
            return
        }
        val shouldUnregister = synchronized(lock) {
            registered = true
            closed
        }
        if (shouldUnregister) {
            unregister()
        }
    }

    override fun close() {
        val shouldUnregister = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                registered
            }
        }
        if (shouldUnregister) {
            unregister()
        }
    }

    private fun update(network: Any, available: Boolean) {
        val current = synchronized(lock) {
            if (closed) {
                return
            }
            if (available) {
                availableNetworks += network
            } else {
                availableNetworks -= network
            }
            availableNetworks.isNotEmpty()
        }
        onAvailabilityChanged(current)
    }

    private fun publishUnavailable() {
        val shouldPublish = synchronized(lock) {
            if (closed) {
                false
            } else {
                availableNetworks.clear()
                true
            }
        }
        if (shouldPublish) {
            onAvailabilityChanged(false)
        }
    }

    private fun unregister() {
        try {
            platform.unregister(listener)
        } catch (_: IllegalArgumentException) {
            // The platform already removed this callback.
        }
    }
}

internal interface ConnectivityPlatform {
    fun register(listener: ConnectivityPlatformListener)

    fun unregister(listener: ConnectivityPlatformListener)
}

internal interface ConnectivityPlatformListener {
    fun onAvailable(network: Any)

    fun onLost(network: Any)

    fun onUnavailable()
}

private class AndroidConnectivityPlatform(
    private val manager: ConnectivityManager,
) : ConnectivityPlatform {
    private val lock = Any()
    private val callbacks = mutableMapOf<ConnectivityPlatformListener, ConnectivityManager.NetworkCallback>()

    override fun register(listener: ConnectivityPlatformListener) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                listener.onAvailable(network)
            }

            override fun onLost(network: Network) {
                listener.onLost(network)
            }

            override fun onUnavailable() {
                listener.onUnavailable()
            }
        }
        synchronized(lock) {
            check(callbacks.put(listener, callback) == null) { "Connectivity listener is already registered" }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
        } catch (failure: RuntimeException) {
            synchronized(lock) {
                callbacks.remove(listener)
            }
            throw failure
        }
    }

    override fun unregister(listener: ConnectivityPlatformListener) {
        val callback = synchronized(lock) { callbacks.remove(listener) } ?: return
        manager.unregisterNetworkCallback(callback)
    }
}
