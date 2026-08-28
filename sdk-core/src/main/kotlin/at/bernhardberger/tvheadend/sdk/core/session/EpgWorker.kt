package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EpgCoverage
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageRequestResult
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import java.util.concurrent.CancellationException
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
    if (coverage.queriedTo == null) return now + settings.warmupHorizon
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
                target = target.toWholeSecond(),
            )
        }
    }
    .sortedBy { (coverage, _) -> coverage.coveredTo }
    .take(settings.batchSize)
    .map { (_, plan) -> plan }
    .toList()

internal class EpgWorker(
    private val generation: GatewayGeneration,
    private val metadata: SessionMetadata,
    private val clock: Clock,
    private val settings: EpgWorkerSettings = EpgWorkerSettings(),
    private val queryEpg: suspend (
        generation: GatewayGeneration,
        channelId: ChannelId,
        maxTime: Instant,
    ) -> GatewayResult<List<GatewayEpgQueryEvent>>,
) : EpgCoverageRequester {
    private val activityLock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val inFlight = mutableSetOf<ChannelId>()
    private val coolingDown = mutableSetOf<ChannelId>()
    private val ineligible = mutableSetOf<ChannelId>()
    private val priorityTargets = linkedMapOf<ChannelId, Instant>()
    private var singleSlotPriorityTurn = true
    private var acceptingPriorities = true
    private var started = false

    override fun requestCoverage(
        channelId: ChannelId,
        through: Instant,
    ): EpgCoverageRequestResult {
        val snapshot = metadata.currentEpgSnapshot(generation)
            ?: return EpgCoverageRequestResult.GENERATION_LOST
        val coverage = snapshot.coverages.firstOrNull { candidate ->
            candidate.channelId == channelId
        } ?: return EpgCoverageRequestResult.INELIGIBLE
        val now = clock.now().toWholeSecond()
        val target = through.toWholeSecond()
        if (coverage.knownTo?.let { knownTo -> knownTo >= target } == true) {
            return EpgCoverageRequestResult.SATISFIED
        }
        if (target <= now || target > now + settings.steadyMaximum) {
            return EpgCoverageRequestResult.INELIGIBLE
        }

        return synchronized(activityLock) {
            when {
                !acceptingPriorities -> EpgCoverageRequestResult.GENERATION_LOST
                channelId in ineligible -> EpgCoverageRequestResult.INELIGIBLE
                else -> {
                    val previous = priorityTargets[channelId]
                    priorityTargets[channelId] = previous?.let { maxOf(it, target) } ?: target
                    wake.trySend(Unit)
                    EpgCoverageRequestResult.ACCEPTED
                }
            }
        }
    }

    internal fun stopAcceptingPriorities() {
        synchronized(activityLock) { acceptingPriorities = false }
    }

    internal suspend fun run(): Unit = coroutineScope {
        synchronized(activityLock) {
            check(!started) { "EPG worker may be started only once" }
            started = true
        }
        val stateObserver = launch(start = CoroutineStart.UNDISPATCHED) {
            metadata.observation.drop(1).collect { wake.trySend(Unit) }
        }
        try {
            while (currentCoroutineContext().isActive) {
                try {
                    val now = clock.now().toWholeSecond()
                    metadata.retainEpgEvents(
                        generation = generation,
                        from = now - settings.retainPast,
                        to = now + settings.retainFuture,
                    )
                    val plans = metadata.currentEpgSnapshot(generation)?.let { snapshot ->
                        selectBatch(snapshot, now)
                    }.orEmpty()
                    if (plans.isEmpty()) {
                        withTimeoutOrNull(settings.channelCooldown) { wake.receive() }
                    } else {
                        dispatchBatch(plans)
                    }
                } catch (cancellation: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    delay(settings.requestSpacing)
                } catch (_: Exception) {
                    delay(settings.requestSpacing)
                }
            }
        } finally {
            try {
                stateObserver.cancelAndJoin()
            } finally {
                synchronized(activityLock) {
                    acceptingPriorities = false
                    inFlight.clear()
                    coolingDown.clear()
                    ineligible.clear()
                    priorityTargets.clear()
                }
                wake.close()
            }
        }
    }

    private fun selectBatch(snapshot: EpgSnapshot, now: Instant): List<EpgQueryPlan> {
        val coverageByChannel = snapshot.coverages.associateBy(EpgCoverage::channelId)
        val scheduling = synchronized(activityLock) {
            priorityTargets.entries.removeAll { (channelId, target) ->
                val coverage = coverageByChannel[channelId]
                coverage == null ||
                    channelId in ineligible ||
                    coverage.knownTo?.let { knownTo -> knownTo >= target } == true
            }
            EpgSchedulingSnapshot(
                excluded = inFlight + coolingDown + ineligible,
                priorities = LinkedHashMap(priorityTargets),
                priorityTurn = singleSlotPriorityTurn,
            )
        }
        val priorityPlans = scheduling.priorities.mapNotNull { (channelId, target) ->
            val coverage = coverageByChannel[channelId]
            if (coverage == null || channelId in scheduling.excluded) {
                null
            } else {
                val ordinaryTarget = epgQueryTarget(coverage, now, settings)
                EpgQueryPlan(
                    channelId = channelId,
                    target = maxOf(target, ordinaryTarget ?: target).toWholeSecond(),
                )
            }
        }
        val ordinaryPlans = selectEpgQueries(
            snapshot = snapshot,
            now = now,
            settings = settings,
            excludedChannelIds = scheduling.excluded + scheduling.priorities.keys,
        )
        val selected = when {
            priorityPlans.isEmpty() -> ordinaryPlans
            ordinaryPlans.isEmpty() -> priorityPlans.take(settings.batchSize)
            settings.batchSize == 1 -> if (scheduling.priorityTurn) {
                priorityPlans.take(1)
            } else {
                ordinaryPlans.take(1)
            }
            else -> {
                val priorityShare = priorityPlans.take(settings.batchSize - 1)
                priorityShare + ordinaryPlans.take(settings.batchSize - priorityShare.size)
            }
        }
        synchronized(activityLock) {
            if (priorityPlans.isNotEmpty() && ordinaryPlans.isNotEmpty() && settings.batchSize == 1) {
                singleSlotPriorityTurn = !scheduling.priorityTurn
            }
            selected.forEach { plan -> inFlight += plan.channelId }
        }
        return selected
    }

    private suspend fun CoroutineScope.dispatchBatch(plans: List<EpgQueryPlan>) {
        val requests = ArrayList<Deferred<Unit>>(plans.size)
        plans.forEach { plan ->
            synchronized(activityLock) { coolingDown += plan.channelId }
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
                    val query = metadata.beginEpgQuery(generation, plan.channelId) ?: return@async
                    try {
                        when (val result = queryEpg(generation, plan.channelId, plan.target)) {
                            is GatewayResult.Ok -> metadata.applySuccessfulEpgQuery(
                                generation = generation,
                                query = query,
                                queriedTo = plan.target,
                                events = result.value,
                            )
                            GatewayResult.AccessDenied,
                            GatewayResult.NotSupported,
                            -> synchronized(activityLock) {
                                ineligible += plan.channelId
                                priorityTargets.remove(plan.channelId)
                            }
                            GatewayResult.ServerRejected,
                            GatewayResult.ConnectionLimit,
                            GatewayResult.Timeout,
                            GatewayResult.TransportUnavailable,
                            -> Unit
                        }
                    } finally {
                        metadata.abandonEpgQuery(generation, query)
                    }
                } catch (cancellation: CancellationException) {
                    currentCoroutineContext().ensureActive()
                } catch (_: Exception) {
                    // A channel-local failure must not terminate the worker.
                } finally {
                    synchronized(activityLock) { inFlight -= plan.channelId }
                    wake.trySend(Unit)
                }
            }
            delay(settings.requestSpacing)
        }
        requests.awaitAll()
        currentCoroutineContext().ensureActive()
    }
}

private data class EpgSchedulingSnapshot(
    internal val excluded: Set<ChannelId>,
    internal val priorities: Map<ChannelId, Instant>,
    internal val priorityTurn: Boolean,
)

private fun Instant.toWholeSecond(): Instant = Instant.fromEpochSeconds(epochSeconds)
