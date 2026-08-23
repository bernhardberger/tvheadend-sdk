@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package at.bernhardberger.tvheadend.sdk.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.Collections
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** Discovers LAN TVHeadend servers through Android Network Service Discovery. */
public class TvheadendDiscovery internal constructor(
    private val backend: NsdDiscoveryBackend,
) {
    public constructor(context: Context) : this(
        AndroidNsdDiscoveryBackend(
            requireNotNull((context.applicationContext ?: context).getSystemService(NsdManager::class.java)) {
                "NSD service is unavailable"
            },
        ),
    )

    /** Cold discovery state; collection owns NSD registration, resolution, and cleanup. */
    public val state: Flow<TvheadendDiscoveryState> = callbackFlow {
        val lock = Any()
        val resolved = linkedMapOf<String, DiscoveredTvheadendServer>()
        var terminal = false

        fun snapshot(): TvheadendDiscoveryState.Discovering =
            TvheadendDiscoveryState.Discovering(
                servers = immutableList(
                    resolved.values.sortedWith(
                        compareBy(
                            DiscoveredTvheadendServer::name,
                            DiscoveredTvheadendServer::host,
                            DiscoveredTvheadendServer::port,
                        ),
                    ),
                ),
            )

        synchronized(lock) {
            trySend(snapshot())
        }
        val registration = backend.start(
            serviceType = HTSP_SERVICE_TYPE,
            listener = object : NsdDiscoveryListener {
                override fun onResolved(key: String, server: DiscoveredTvheadendServer) {
                    synchronized(lock) {
                        if (!terminal) {
                            resolved[key] = server
                            trySend(snapshot())
                        }
                    }
                }

                override fun onLost(key: String) {
                    synchronized(lock) {
                        if (!terminal && resolved.remove(key) != null) {
                            trySend(snapshot())
                        }
                    }
                }

                override fun onFailed(failure: TvheadendDiscoveryFailure) {
                    synchronized(lock) {
                        if (!terminal) {
                            terminal = true
                            trySend(TvheadendDiscoveryState.Unavailable(failure))
                            close()
                        }
                    }
                }
            },
        )
        awaitClose(registration::close)
    }.conflate()
}

/** A resolved TVHeadend endpoint. String rendering is always redacted. */
public data class DiscoveredTvheadendServer(
    public val name: String,
    public val host: String,
    public val port: Int,
) {
    init {
        require(name.isNotBlank()) { "Discovered server name must not be blank" }
        require(host.isNotBlank()) { "Discovered server host must not be blank" }
        require(port in 1..65_535) { "Discovered server port must be valid" }
    }

    override fun toString(): String = "DiscoveredTvheadendServer(<redacted>)"
}

/** Durable state of one lifecycle-bound discovery collection. */
public sealed interface TvheadendDiscoveryState {
    /** Discovery is active with the currently resolved immutable server snapshot. */
    @ConsistentCopyVisibility
    public data class Discovering internal constructor(
        public val servers: List<DiscoveredTvheadendServer>,
    ) : TvheadendDiscoveryState

    /** Discovery could not be started and this collection has ended. */
    public data class Unavailable(
        public val failure: TvheadendDiscoveryFailure,
    ) : TvheadendDiscoveryState
}

/** Safe reason that Android NSD discovery could not be started. */
public enum class TvheadendDiscoveryFailure {
    PERMISSION_DENIED,
    START_FAILED,
}

internal const val HTSP_SERVICE_TYPE: String = "_htsp._tcp."

internal interface NsdDiscoveryBackend {
    fun start(serviceType: String, listener: NsdDiscoveryListener): CallbackRegistration
}

internal interface NsdDiscoveryListener {
    fun onResolved(key: String, server: DiscoveredTvheadendServer)

    fun onLost(key: String)

    fun onFailed(failure: TvheadendDiscoveryFailure)
}

private class AndroidNsdDiscoveryBackend(
    private val manager: NsdManager,
) : NsdDiscoveryBackend {
    override fun start(
        serviceType: String,
        listener: NsdDiscoveryListener,
    ): CallbackRegistration {
        val registration = NsdDiscoveryRegistration(
            platform = AndroidNsdPlatform(manager),
            coordinator = sharedNsdResolutionCoordinator,
            serviceType = serviceType,
            listener = listener,
        )
        registration.start()
        return registration
    }
}

internal class NsdDiscoveryRegistration(
    private val platform: NsdPlatform,
    private val coordinator: NsdResolutionCoordinator,
    private val serviceType: String,
    private val listener: NsdDiscoveryListener,
) : CallbackRegistration {
    private val lock = Any()
    private val pending = ArrayDeque<PendingResolution>()
    private val presentServices = mutableMapOf<String, PendingResolution>()
    private var resolving: PendingResolution? = null
    private var discoveryRequested = false
    private var discoveryStarted = false
    private var stopRequested = false
    private var closed = false

    private var stopAttempts = 0

    private val platformListener = object : NsdPlatformDiscoveryListener {
        override fun onDiscoveryStarted() {
            val shouldStop = synchronized(lock) {
                discoveryStarted = true
                closed && !stopRequested
            }
            if (shouldStop) {
                stopDiscovery()
            }
        }

        override fun onDiscoveryStopped() {
            val failedUnexpectedly = synchronized(lock) {
                discoveryRequested = false
                discoveryStarted = false
                stopRequested = false
                !closed
            }
            if (failedUnexpectedly) {
                fail(TvheadendDiscoveryFailure.START_FAILED)
            }
        }

        override fun onServiceFound(service: NsdServiceRecord) {
            val key = service.key
            if (key.isBlank() || !service.serviceType.matchesServiceType(serviceType)) {
                return
            }
            val pendingResolution = PendingResolution(key, service)
            val shouldResolve = synchronized(lock) {
                if (closed || key in presentServices) {
                    false
                } else {
                    presentServices[key] = pendingResolution
                    pending += pendingResolution
                    true
                }
            }
            if (shouldResolve) {
                resolveNext()
            }
        }

        override fun onServiceLost(service: NsdServiceRecord) {
            val key = service.key
            val wasPresent = synchronized(lock) {
                pending.removeAll { it.key == key }
                presentServices.remove(key) != null
            }
            if (wasPresent) {
                listener.onLost(key)
            }
        }

        override fun onStartDiscoveryFailed() {
            fail(TvheadendDiscoveryFailure.START_FAILED)
        }

        override fun onStopDiscoveryFailed() {
            val shouldRetry = synchronized(lock) {
                stopRequested = false
                closed && stopAttempts < MAX_STOP_ATTEMPTS
            }
            if (shouldRetry) {
                stopDiscovery()
            }
        }
    }

    fun start() {
        synchronized(lock) {
            discoveryRequested = true
        }
        try {
            platform.discoverServices(serviceType, platformListener)
        } catch (_: SecurityException) {
            synchronized(lock) {
                discoveryRequested = false
            }
            fail(TvheadendDiscoveryFailure.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            synchronized(lock) {
                discoveryRequested = false
            }
            fail(TvheadendDiscoveryFailure.START_FAILED)
        }
    }

    override fun close() {
        val shouldStop = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                pending.clear()
                presentServices.clear()
                discoveryStarted && !stopRequested
            }
        }
        coordinator.cancel(this)
        if (shouldStop) {
            stopDiscovery()
        }
    }

    private fun resolveNext() {
        val next = synchronized(lock) {
            if (closed || resolving != null) {
                return
            }
            pending.removeFirstOrNull()?.also { resolving = it }
        } ?: return

        coordinator.resolve(
            owner = this,
            isCancelled = { synchronized(lock) { closed } },
        ) { completion ->
            val shouldStart = synchronized(lock) {
                !closed && resolving === next && presentServices[next.key] === next
            }
            if (!shouldStart) {
                finishResolution(next)
                completion.close()
                resolveNext()
                return@resolve
            }
            try {
                platform.resolveService(
                    next.service,
                    resolutionListener(next, completion),
                )
            } catch (_: SecurityException) {
                retireResolution(next)
                completion.close()
                fail(TvheadendDiscoveryFailure.PERMISSION_DENIED)
            } catch (_: RuntimeException) {
                retryOrRetireResolution(next)
                completion.close()
                resolveNext()
            }
        }
    }

    private fun resolutionListener(
        pendingResolution: PendingResolution,
        completion: CallbackRegistration,
    ): NsdPlatformResolveListener =
        object : NsdPlatformResolveListener {
            override fun onResolveFailed() {
                retryOrRetireResolution(pendingResolution)
                completion.close()
                resolveNext()
            }

            override fun onServiceResolved(service: ResolvedNsdService) {
                val host = service.host
                val valid =
                    service.name.isNotBlank() &&
                        !host.isNullOrBlank() &&
                        service.port in 1..65_535
                val stillPresent = if (valid) {
                    finishResolution(pendingResolution)
                } else {
                    retryOrRetireResolution(pendingResolution)
                    false
                }
                try {
                    if (stillPresent) {
                        listener.onResolved(
                            pendingResolution.key,
                            DiscoveredTvheadendServer(
                                name = service.name,
                                host = host.orEmpty(),
                                port = service.port,
                            ),
                        )
                    }
                } finally {
                    completion.close()
                    resolveNext()
                }
            }
        }

    private fun finishResolution(pendingResolution: PendingResolution): Boolean = synchronized(lock) {
        if (resolving === pendingResolution) {
            resolving = null
        }
        !closed && presentServices[pendingResolution.key] === pendingResolution
    }

    private fun retireResolution(pendingResolution: PendingResolution) {
        synchronized(lock) {
            if (resolving === pendingResolution) {
                resolving = null
            }
            if (presentServices[pendingResolution.key] === pendingResolution) {
                presentServices.remove(pendingResolution.key)
            }
        }
    }

    private fun retryOrRetireResolution(pendingResolution: PendingResolution) {
        synchronized(lock) {
            if (resolving === pendingResolution) {
                resolving = null
            }
            if (
                !closed &&
                presentServices[pendingResolution.key] === pendingResolution &&
                pendingResolution.attempts < MAX_RESOLVE_ATTEMPTS
            ) {
                pendingResolution.attempts += 1
                pending += pendingResolution
            } else if (presentServices[pendingResolution.key] === pendingResolution) {
                presentServices.remove(pendingResolution.key)
            }
        }
    }

    private fun fail(failure: TvheadendDiscoveryFailure) {
        val plan = synchronized(lock) {
            if (closed) {
                FailurePlan(
                    notify = false,
                    stop = discoveryRequested && !stopRequested,
                )
            } else {
                closed = true
                pending.clear()
                presentServices.clear()
                FailurePlan(
                    notify = true,
                    stop = discoveryRequested && !stopRequested,
                )
            }
        }
        if (plan.notify) {
            coordinator.cancel(this)
            listener.onFailed(failure)
        }
        if (plan.stop) {
            stopDiscovery()
        }
    }

    private fun stopDiscovery() {
        val shouldStop = synchronized(lock) {
            if (stopRequested || stopAttempts >= MAX_STOP_ATTEMPTS) {
                false
            } else {
                stopRequested = true
                stopAttempts += 1
                true
            }
        }
        if (shouldStop) {
            try {
                platform.stopServiceDiscovery(platformListener)
            } catch (_: RuntimeException) {
                platformListener.onStopDiscoveryFailed()
            }
        }
    }
}

internal interface NsdPlatform {
    fun discoverServices(serviceType: String, listener: NsdPlatformDiscoveryListener)

    fun stopServiceDiscovery(listener: NsdPlatformDiscoveryListener)

    fun resolveService(service: NsdServiceRecord, listener: NsdPlatformResolveListener)
}

internal interface NsdPlatformDiscoveryListener {
    fun onDiscoveryStarted()

    fun onDiscoveryStopped()

    fun onServiceFound(service: NsdServiceRecord)

    fun onServiceLost(service: NsdServiceRecord)

    fun onStartDiscoveryFailed()

    fun onStopDiscoveryFailed()
}

internal interface NsdPlatformResolveListener {
    fun onResolveFailed()

    fun onServiceResolved(service: ResolvedNsdService)
}

internal data class NsdServiceRecord(
    val key: String,
    val serviceType: String,
    val platformValue: Any,
)

internal data class ResolvedNsdService(
    val name: String,
    val host: String?,
    val port: Int,
)

internal class NsdResolutionCoordinator {
    private val lock = Any()
    private val pending = ArrayDeque<ResolutionRequest>()
    private var active = false

    fun resolve(
        owner: Any,
        isCancelled: () -> Boolean,
        start: (CallbackRegistration) -> Unit,
    ) {
        val shouldStart = synchronized(lock) {
            if (isCancelled()) {
                return
            }
            pending += ResolutionRequest(owner, start)
            !active
        }
        if (shouldStart) {
            startNext()
        }
    }

    fun cancel(owner: Any) {
        synchronized(lock) {
            pending.removeAll { it.owner === owner }
        }
    }

    private fun startNext() {
        val next = synchronized(lock) {
            if (active) {
                return
            }
            pending.removeFirstOrNull()?.also { active = true }
        } ?: return
        var completed = false
        next.start(
            CallbackRegistration {
                val shouldContinue = synchronized(lock) {
                    if (completed) {
                        false
                    } else {
                        completed = true
                        active = false
                        true
                    }
                }
                if (shouldContinue) {
                    startNext()
                }
            },
        )
    }

    private data class ResolutionRequest(
        val owner: Any,
        val start: (CallbackRegistration) -> Unit,
    )
}

private class AndroidNsdPlatform(
    private val manager: NsdManager,
) : NsdPlatform {
    private val lock = Any()
    private val discoveryListeners =
        mutableMapOf<NsdPlatformDiscoveryListener, NsdManager.DiscoveryListener>()

    override fun discoverServices(serviceType: String, listener: NsdPlatformDiscoveryListener) {
        val androidListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                listener.onDiscoveryStarted()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                synchronized(lock) {
                    discoveryListeners.remove(listener)
                }
                listener.onDiscoveryStopped()
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                listener.onServiceFound(serviceInfo.toRecord())
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                listener.onServiceLost(serviceInfo.toRecord())
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                listener.onStartDiscoveryFailed()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                listener.onStopDiscoveryFailed()
            }
        }
        synchronized(lock) {
            check(discoveryListeners.put(listener, androidListener) == null) {
                "NSD listener is already registered"
            }
        }
        try {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, androidListener)
        } catch (failure: RuntimeException) {
            synchronized(lock) {
                discoveryListeners.remove(listener)
            }
            throw failure
        }
    }

    override fun stopServiceDiscovery(listener: NsdPlatformDiscoveryListener) {
        val androidListener = synchronized(lock) { discoveryListeners[listener] } ?: return
        manager.stopServiceDiscovery(androidListener)
    }

    override fun resolveService(service: NsdServiceRecord, listener: NsdPlatformResolveListener) {
        val serviceInfo = service.platformValue as NsdServiceInfo
        LegacyNsdCompat.resolveService(
            manager,
            serviceInfo,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    listener.onResolveFailed()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val resolved = try {
                        ResolvedNsdService(
                            name = serviceInfo.serviceName,
                            host = LegacyNsdCompat.hostAddress(serviceInfo),
                            port = serviceInfo.port,
                        )
                    } catch (_: RuntimeException) {
                        listener.onResolveFailed()
                        return
                    }
                    listener.onServiceResolved(resolved)
                }
            },
        )
    }
}

private data class PendingResolution(
    val key: String,
    val service: NsdServiceRecord,
    var attempts: Int = 1,
)

private data class FailurePlan(
    val notify: Boolean,
    val stop: Boolean,
)

private const val MAX_STOP_ATTEMPTS: Int = 2
private const val MAX_RESOLVE_ATTEMPTS: Int = 2

private val sharedNsdResolutionCoordinator = NsdResolutionCoordinator()

private fun String.matchesServiceType(expected: String): Boolean =
    normalizedServiceType().equals(expected.normalizedServiceType(), ignoreCase = true)

private fun String.normalizedServiceType(): String {
    val withoutTrailingDot = trimEnd('.')
    return if (withoutTrailingDot.endsWith(".local", ignoreCase = true)) {
        withoutTrailingDot.dropLast(".local".length)
    } else {
        withoutTrailingDot
    }
}

private fun NsdServiceInfo.toRecord(): NsdServiceRecord =
    NsdServiceRecord(
        key = serviceName,
        serviceType = serviceType,
        platformValue = this,
    )

private fun <T> immutableList(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
