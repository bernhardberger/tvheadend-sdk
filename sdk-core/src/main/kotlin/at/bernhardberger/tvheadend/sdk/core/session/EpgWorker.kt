package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EpgCoverage
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal data class EpgWorkerSettings(
    internal val warmupHorizon: Duration = 4.hours,
    internal val steadyMinimum: Duration = 20.hours,
    internal val steadyMaximum: Duration = 24.hours,
    internal val queryChunk: Duration = 4.hours,
    internal val channelCooldown: Duration = 10.minutes,
    internal val requestSpacing: Duration = 250.milliseconds,
    internal val batchSize: Int = 6,
    internal val retainPast: Duration = 6.hours,
    internal val retainFuture: Duration = 24.hours,
) {
    init {
        listOf(
            warmupHorizon,
            steadyMinimum,
            steadyMaximum,
            queryChunk,
            channelCooldown,
            requestSpacing,
            retainPast,
            retainFuture,
        ).forEach { duration ->
            require(duration.isFinite() && duration > Duration.ZERO) {
                "EPG worker durations must be finite and positive"
            }
        }
        require(warmupHorizon <= steadyMinimum) {
            "EPG warmup horizon must not exceed the steady minimum"
        }
        require(steadyMinimum <= steadyMaximum) {
            "EPG steady minimum must not exceed the steady maximum"
        }
        require(queryChunk <= steadyMaximum) {
            "EPG query chunk must not exceed the steady maximum"
        }
        require(batchSize > 0) { "EPG batch size must be positive" }
    }
}

internal data class EpgQueryPlan(
    internal val channelId: ChannelId,
    internal val target: Instant,
)

internal fun epgQueryTarget(
    coverage: EpgCoverage,
    now: Instant,
    settings: EpgWorkerSettings,
): Instant? {
    val knownTo = coverage.knownTo
    val warmupTarget = now + settings.warmupHorizon
    if (knownTo == null || knownTo < warmupTarget) return warmupTarget

    val steadyMinimumTarget = now + settings.steadyMinimum
    if (knownTo >= steadyMinimumTarget) return null
    return minOf(knownTo + settings.queryChunk, now + settings.steadyMaximum)
}

internal fun selectEpgQueries(
    snapshot: EpgSnapshot,
    now: Instant,
    settings: EpgWorkerSettings,
    excludedChannelIds: Set<ChannelId> = emptySet(),
): List<EpgQueryPlan> = snapshot.coverages
    .asSequence()
    .filter { coverage -> coverage.channelId !in excludedChannelIds }
    .mapNotNull { coverage ->
        epgQueryTarget(coverage, now, settings)?.let { target ->
            coverage to EpgQueryPlan(
                channelId = coverage.channelId,
                target = Instant.fromEpochSeconds(target.epochSeconds),
            )
        }
    }
    .sortedBy { (coverage, _) -> coverage.coveredTo }
    .take(settings.batchSize)
    .map { (_, plan) -> plan }
    .toList()

internal fun isEpgWarm(
    snapshot: EpgSnapshot,
    now: Instant,
    settings: EpgWorkerSettings,
    satisfiedChannelIds: Set<ChannelId> = emptySet(),
): Boolean {
    val requiredTo = now + settings.warmupHorizon
    return snapshot.coverages.all { coverage ->
        coverage.channelId in satisfiedChannelIds ||
            coverage.knownTo?.let { knownTo -> knownTo >= requiredTo } == true
    }
}

internal class EpgWorker(
    private val metadata: SessionMetadata,
    private val clock: Clock,
    private val settings: EpgWorkerSettings = EpgWorkerSettings(),
    private val queryEpg: suspend (
        generation: GatewayGeneration,
        channelId: ChannelId,
        maxTime: Instant,
    ) -> GatewayResult<List<GatewayEpgQueryEvent>>,
) {
    private val activityLock = Any()
    private val inFlight = mutableSetOf<ChannelId>()
    private val coolingDown = mutableSetOf<ChannelId>()
    private val attempted = mutableSetOf<ChannelId>()
    private val ineligible = mutableSetOf<ChannelId>()
    private val warmup = CompletableDeferred<Unit>()
    private var started = false

    internal val isWarm: Boolean
        get() = warmup.isCompleted && !warmup.isCancelled

    internal suspend fun awaitWarmup() {
        warmup.await()
    }

    internal suspend fun run(generation: GatewayGeneration): Unit = coroutineScope {
        synchronized(activityLock) {
            check(!started) { "EPG worker may be started only once" }
            started = true
        }
        val wake = Channel<Unit>(Channel.CONFLATED)
        val warmupStartedAt = Instant.fromEpochSeconds(clock.now().epochSeconds)
        val stateObserver = launch(start = CoroutineStart.UNDISPATCHED) {
            metadata.epgRepository.state.drop(1).collect { wake.trySend(Unit) }
        }
        try {
            while (currentCoroutineContext().isActive) {
                try {
                    val now = Instant.fromEpochSeconds(clock.now().epochSeconds)
                    metadata.retainEpgEvents(
                        generation = generation,
                        from = now - settings.retainPast,
                        to = now + settings.retainFuture,
                    )
                    val snapshot = metadata.currentEpgSnapshot(generation)
                    if (
                        snapshot != null &&
                        isEpgWarm(
                            snapshot,
                            warmupStartedAt,
                            settings,
                            satisfiedChannelIds(),
                        )
                    ) {
                        warmup.complete(Unit)
                    }
                    val plans = snapshot?.let { current ->
                        selectEpgQueries(
                            snapshot = current,
                            now = now,
                            settings = settings,
                            excludedChannelIds = excludedChannelIds(),
                        )
                    }.orEmpty()
                    if (plans.isEmpty()) {
                        withTimeoutOrNull(settings.channelCooldown) { wake.receive() }
                    } else {
                        dispatchBatch(generation, plans, wake)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    delay(settings.requestSpacing)
                }
            }
        } finally {
            stateObserver.cancelAndJoin()
            if (!warmup.isCompleted) {
                warmup.cancel(CancellationException("EPG worker stopped before warmup"))
            }
            wake.close()
        }
    }

    private suspend fun CoroutineScope.dispatchBatch(
        generation: GatewayGeneration,
        plans: List<EpgQueryPlan>,
        wake: Channel<Unit>,
    ) {
        val requests = ArrayList<Deferred<Unit>>(plans.size)
        plans.forEach { plan ->
            synchronized(activityLock) {
                inFlight += plan.channelId
                coolingDown += plan.channelId
            }
            launch {
                try {
                    delay(settings.channelCooldown)
                } finally {
                    synchronized(activityLock) { coolingDown -= plan.channelId }
                    wake.trySend(Unit)
                }
            }
            requests += async {
                try {
                    when (val result = queryEpg(generation, plan.channelId, plan.target)) {
                        is GatewayResult.Ok -> metadata.applySuccessfulEpgQuery(
                            generation = generation,
                            channelId = plan.channelId,
                            queriedTo = plan.target,
                            events = result.value,
                        )
                        GatewayResult.AccessDenied,
                        GatewayResult.NotSupported,
                        -> synchronized(activityLock) { ineligible += plan.channelId }
                        GatewayResult.ServerRejected,
                        GatewayResult.ConnectionLimit,
                        GatewayResult.Timeout,
                        GatewayResult.TransportUnavailable,
                        -> Unit
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // A channel-local failure must not terminate the worker.
                } finally {
                    synchronized(activityLock) {
                        inFlight -= plan.channelId
                        attempted += plan.channelId
                    }
                    wake.trySend(Unit)
                }
            }
            delay(settings.requestSpacing)
        }
        requests.awaitAll()
        currentCoroutineContext().ensureActive()
    }

    private fun excludedChannelIds(): Set<ChannelId> = synchronized(activityLock) {
        inFlight + coolingDown + ineligible
    }

    private fun satisfiedChannelIds(): Set<ChannelId> = synchronized(activityLock) {
        ineligible + attempted
    }
}
