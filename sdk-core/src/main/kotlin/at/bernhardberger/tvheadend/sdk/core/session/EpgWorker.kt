package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation
import at.bernhardberger.tvheadend.sdk.core.EpgCoverage
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageAcquisitionResult
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageBatchResult
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageBatchSettlement
import at.bernhardberger.tvheadend.sdk.core.EpgCoveragePolicy
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.metadata.EpgQueryAcceptance
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
    internal val coveragePolicy: EpgCoveragePolicy = EpgCoveragePolicy.create(),
    internal val warmupHorizon: Duration = 4.hours,
    internal val steadyMinimum: Duration = 20.hours,
    internal val queryChunk: Duration = 4.hours,
    internal val channelCooldown: Duration = 10.minutes,
    internal val requestSpacing: Duration = 250.milliseconds,
    internal val batchSize: Int = 6,
    internal val retainPast: Duration = 6.hours,
) {
    init {
        listOf(
            warmupHorizon,
            steadyMinimum,
            queryChunk,
            channelCooldown,
            requestSpacing,
            retainPast,
        ).forEach { duration ->
            require(duration.isFinite() && duration > Duration.ZERO) {
                "EPG worker durations must be finite and positive"
            }
        }
        require(warmupHorizon <= steadyMinimum) {
            "EPG warmup horizon must not exceed the steady minimum"
        }
        require(steadyMinimum <= coveragePolicy.futureHorizon) {
            "EPG steady minimum must not exceed the coverage horizon"
        }
        require(queryChunk <= coveragePolicy.futureHorizon) {
            "EPG query chunk must not exceed the coverage horizon"
        }
        require(batchSize > 0) { "EPG batch size must be positive" }
    }
}

internal data class EpgQueryPlan(
    internal val channelId: ChannelId,
    internal val target: Instant,
    internal val priority: Boolean = false,
)

// Cover the upstream 30 s slide timer plus whole-second estimate and transport lag.
// This allowance affects future pruning only, never admission or query targets.
private const val EPG_FUTURE_PRUNE_ALLOWANCE_SECONDS = 60L

/** Refreshed under the metadata lock; no range objects or additional locks per admission. */
internal class EpgRetentionBounds(
    private val clock: Clock,
    private val retainPast: Duration,
    private val futureHorizon: Duration,
) {
    internal var fromEpochSeconds: Long = Long.MIN_VALUE
        private set
    internal var toEpochSeconds: Long = Long.MAX_VALUE
        private set

    internal fun refresh(serverNow: Instant?) {
        val deviceNow = clock.now().epochSeconds
        val estimatedNow = serverNow?.epochSeconds ?: deviceNow
        fromEpochSeconds = minOf(deviceNow, estimatedNow) - retainPast.inWholeSeconds
        toEpochSeconds = maxOf(deviceNow, estimatedNow) + futureHorizon.inWholeSeconds + EPG_FUTURE_PRUNE_ALLOWANCE_SECONDS
    }
}

internal fun epgQueryTarget(
    coverage: EpgCoverage,
    now: Instant,
    settings: EpgWorkerSettings,
    initialSyncThrough: Instant? = null,
): Instant? {
    if (coverage.queriedTo == null && initialSyncThrough == null) return now + settings.warmupHorizon
    val knownTo = listOfNotNull(coverage.knownTo, initialSyncThrough).maxOrNull()
    val warmupTarget = now + settings.warmupHorizon
    if (knownTo == null || knownTo < warmupTarget) return warmupTarget

    val steadyMinimumTarget = now + settings.steadyMinimum
    if (knownTo >= steadyMinimumTarget) return null
    return minOf(knownTo + settings.queryChunk, now + settings.coveragePolicy.futureHorizon)
}

internal fun selectEpgQueries(
    snapshot: EpgSnapshot,
    now: Instant,
    settings: EpgWorkerSettings,
    excludedChannelIds: Set<ChannelId> = emptySet(),
    initialEpgCoverage: Map<ChannelId, Instant> = emptyMap(),
): List<EpgQueryPlan> = snapshot.coverages
    .asSequence()
    .filter { coverage -> coverage.channelId !in excludedChannelIds }
    .mapNotNull { coverage ->
        epgQueryTarget(coverage, now, settings, initialEpgCoverage[coverage.channelId])?.let { target ->
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
    private val estimatedServerTime: () -> Instant? = { null },
    private val queryEpg: suspend (
        generation: GatewayGeneration,
        channelId: ChannelId,
        maxTime: Instant,
    ) -> GatewayResult<List<GatewayEpgQueryEvent>>,
) : EpgCoverageRequester {
    private val activityLock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val inFlight = mutableSetOf<ChannelId>()
    // Each dispatch replaces its channel's period token, so only the latest period's timer ends it.
    private val coolingDown = mutableMapOf<ChannelId, Any>()
    private val ineligible = mutableSetOf<ChannelId>()
    private val priorityTargets = linkedMapOf<ChannelId, Instant>()

    // Highest priority query target dispatched per channel in its current cooldown period.
    private val priorityAttempts = mutableMapOf<ChannelId, Instant>()
    private val waiters = linkedMapOf<ChannelId, LinkedHashSet<EpgCoverageWaiter>>()
    private var singleSlotPriorityTurn = true
    private var acceptingPriorities = true
    private var started = false

    override suspend fun acquireCoverage(
        currentSession: CurrentSessionObservation,
        channelId: ChannelId,
        through: Instant,
    ): EpgCoverageAcquisitionResult {
        currentCoroutineContext().ensureActive()
        return protectingRequest(listOf(channelId), through) {
            acquireChannelCoverage(currentSession, channelId, through)
        }
    }

    // A requested window may be the one on screen: keep it out of eviction while the call is
    // pending, and afterwards while it is among the most recent calls.
    private suspend fun <T> protectingRequest(
        channelIds: Collection<ChannelId>,
        through: Instant,
        acquire: suspend () -> T,
    ): T {
        val target = through.toWholeSecond()
        val request = if (target <= queryTime() + settings.coveragePolicy.futureHorizon) {
            metadata.rememberEpgCoverageRequest(generation, channelIds, target)
        } else {
            null
        }
        return try {
            acquire()
        } finally {
            request?.let { metadata.releaseEpgCoverageRequest(generation, it) }
        }
    }

    private suspend fun acquireChannelCoverage(
        currentSession: CurrentSessionObservation,
        channelId: ChannelId,
        through: Instant,
    ): EpgCoverageAcquisitionResult {
        val observation = metadata.currentObservation(generation, currentSession)
            ?: return EpgCoverageAcquisitionResult.ObservationExpired
        val coverage = observation.coverage(channelId)
            ?: return EpgCoverageAcquisitionResult.Ineligible
        val now = queryTime()
        val target = through.toWholeSecond()
        if (coverage.knownTo?.let { knownTo -> knownTo >= target } == true) {
            return coveredResult(observation, coverage)
        }
        if (target <= now || target > now + settings.coveragePolicy.futureHorizon) {
            return EpgCoverageAcquisitionResult.Ineligible
        }
        return awaitCoverage(currentSession, channelId, target)
    }

    private suspend fun awaitCoverage(
        currentSession: CurrentSessionObservation,
        channelId: ChannelId,
        target: Instant,
    ): EpgCoverageAcquisitionResult {
        val waiter = EpgCoverageWaiter(currentSession, channelId, target)
        val immediate = synchronized(activityLock) {
            when {
                !acceptingPriorities -> EpgCoverageAcquisitionResult.ObservationExpired
                channelId in ineligible -> EpgCoverageAcquisitionResult.Ineligible
                else -> {
                    waiters.getOrPut(channelId, ::linkedSetOf).add(waiter)
                    val previous = priorityTargets[channelId]
                    priorityTargets[channelId] = previous?.let { maxOf(it, target) } ?: target
                    wake.trySend(Unit)
                    null
                }
            }
        }
        if (immediate != null) {
            return if (
                immediate === EpgCoverageAcquisitionResult.Ineligible &&
                metadata.currentObservation(generation, currentSession) == null
            ) {
                EpgCoverageAcquisitionResult.ObservationExpired
            } else {
                immediate
            }
        }
        return try {
            // A result may already be reduced but waiting for the rest of its ordinary batch.
            metadata.flushDeferredMetadata(generation)
            settleCoveredWaiters(channelId)
            waiter.settlement.await()
        } finally {
            removeWaiter(waiter)
        }
    }

    override suspend fun acquireCoverageBatch(
        currentSession: CurrentSessionObservation,
        channelIds: List<ChannelId>,
        through: Instant,
    ): EpgCoverageBatchResult = coroutineScope {
        currentCoroutineContext().ensureActive()
        val originatingObservation = metadata.currentObservation(generation, currentSession)
            ?: return@coroutineScope expiredBatch(channelIds)
        val distinct = channelIds.distinct()
        val present = distinct.filter { channelId -> originatingObservation.coverage(channelId) != null }
        val settlements = protectingRequest(present, through) {
            distinct.map { channelId ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    if (originatingObservation.coverage(channelId) == null) {
                        EpgCoverageBatchSettlement.TargetAbsent(channelId)
                    } else {
                        acquireChannelCoverage(currentSession, channelId, through).toBatchSettlement(channelId)
                    }
                }
            }.awaitAll()
        }
        EpgCoverageBatchResult.create(settlements)
    }

    internal fun stopAcceptingPriorities() {
        expireWaiters()
    }

    // Query scheduling follows the server estimate; retention additionally needs the raw
    // device clock so its bounds really cover both clocks rather than two server readings.
    private fun queryTime(): Instant = (estimatedServerTime() ?: clock.now()).toWholeSecond()

    internal suspend fun run(): Unit = coroutineScope {
        val retentionBounds = EpgRetentionBounds(clock, settings.retainPast, settings.coveragePolicy.futureHorizon)
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
                    settleCoveredWaiters()
                    val now = queryTime()
                    metadata.retainEpgEvents(
                        generation = generation,
                        bounds = retentionBounds,
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
                expireWaiters()
                synchronized(activityLock) {
                    inFlight.clear()
                    coolingDown.clear()
                    priorityAttempts.clear()
                    ineligible.clear()
                    priorityTargets.clear()
                    waiters.clear()
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
                excluded = inFlight + coolingDown.keys + ineligible,
                // An explicit target may skip the top-up cooldown unless this period already queried
                // through it; a repeated unsatisfied target therefore waits for the period to end.
                priorityExcluded = inFlight + ineligible + coolingDown.keys.filter { channelId ->
                    val attempted = priorityAttempts[channelId]
                    val target = priorityTargets[channelId]
                    attempted != null && target != null && attempted >= target
                },
                priorities = LinkedHashMap(priorityTargets),
                priorityTurn = singleSlotPriorityTurn,
            )
        }
        val priorityPlans = scheduling.priorities.mapNotNull { (channelId, target) ->
            val coverage = coverageByChannel[channelId]
            if (coverage == null || channelId in scheduling.priorityExcluded) {
                null
            } else {
                val ordinaryTarget = epgQueryTarget(coverage, now, settings)
                EpgQueryPlan(
                    channelId = channelId,
                    target = maxOf(target, ordinaryTarget ?: target).toWholeSecond(),
                    priority = true,
                )
            }
        }
        val ordinaryPlans = selectEpgQueries(
            snapshot = snapshot,
            now = now,
            settings = settings,
            excludedChannelIds = scheduling.excluded + scheduling.priorities.keys,
            initialEpgCoverage = metadata.initialEpgCoverage(generation),
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
        try {
            coroutineScope {
                val requests = ArrayList<Deferred<Unit>>(plans.size)
                plans.forEach { plan ->
                    val period = Any()
                    synchronized(activityLock) {
                        // Record the attempt with its period: an earlier period's timer may expire
                        // during request spacing and must not clear an attempt it did not start.
                        coolingDown[plan.channelId] = period
                        if (plan.priority) {
                            priorityAttempts[plan.channelId] = priorityAttempts[plan.channelId]
                                ?.let { attempted -> maxOf(attempted, plan.target) }
                                ?: plan.target
                        }
                    }
                    this@dispatchBatch.launch {
                        try {
                            delay(settings.channelCooldown)
                        } finally {
                            synchronized(activityLock) {
                                if (coolingDown.remove(plan.channelId, period)) {
                                    priorityAttempts -= plan.channelId
                                }
                            }
                            wake.trySend(Unit)
                        }
                    }
                    requests += async { dispatchQuery(plan) }
                    delay(settings.requestSpacing)
                }
                requests.awaitAll()
                metadata.flushDeferredMetadata(generation)
                currentCoroutineContext().ensureActive()
            }
        } finally {
            // The request scope has joined its children, so no accepted result can arrive after this flush.
            metadata.flushDeferredMetadata(generation)
        }
    }

    private suspend fun dispatchQuery(plan: EpgQueryPlan) {
        try {
            val query = metadata.beginEpgQuery(generation, plan.channelId) ?: return
            try {
                when (val result = queryEpg(generation, plan.channelId, plan.target)) {
                    is GatewayResult.Ok -> metadata.applySuccessfulEpgQuery(
                        generation = generation,
                        query = query,
                        queriedTo = plan.target,
                        events = result.value,
                        deferPublication = synchronized(activityLock) {
                            plan.channelId !in priorityTargets && waiters[plan.channelId].isNullOrEmpty()
                        },
                    ).also { acceptance ->
                        val hasWaiters = synchronized(activityLock) { !waiters[plan.channelId].isNullOrEmpty() }
                        if (acceptance == EpgQueryAcceptance.APPLIED && hasWaiters) {
                            metadata.flushDeferredMetadata(generation)
                        }
                        settleCoveredWaiters(plan.channelId)
                        if (acceptance == EpgQueryAcceptance.CAPACITY_REJECTED) {
                            settleRejected(plan.channelId, EpgCoverageAcquisitionResult.CapacityExhausted)
                        }
                    }
                    GatewayResult.AccessDenied,
                    GatewayResult.NotSupported,
                    GatewayResult.ObservationExpired,
                    GatewayResult.MalformedReply,
                    -> settleRejected(plan.channelId)
                    is GatewayResult.ServerRejected,
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

    private fun settleCoveredWaiters(channelId: ChannelId? = null) {
        val candidates = synchronized(activityLock) {
            if (channelId == null) {
                waiters.values.flatMap { channelWaiters -> channelWaiters.toList() }
            } else {
                waiters[channelId]?.toList().orEmpty()
            }
        }
        candidates.forEach { waiter ->
            val observation = metadata.currentObservation(generation, waiter.currentSession)
            val result = when {
                observation == null -> EpgCoverageAcquisitionResult.ObservationExpired
                else -> {
                    val coverage = observation.coverage(waiter.channelId)
                    when {
                        coverage == null -> EpgCoverageAcquisitionResult.Ineligible
                        coverage.knownTo?.let { knownTo -> knownTo >= waiter.target } == true ->
                            coveredResult(observation, coverage)
                        else -> null
                    }
                }
            }
            if (result != null) completeWaiter(waiter, result)
        }
    }

    private fun settleRejected(
        channelId: ChannelId,
        rejection: EpgCoverageAcquisitionResult = EpgCoverageAcquisitionResult.Ineligible,
    ) {
        val retired = synchronized(activityLock) {
            // Capacity can become available later; only server capability denial is latched.
            if (rejection === EpgCoverageAcquisitionResult.Ineligible) ineligible += channelId
            priorityTargets.remove(channelId)
            waiters.remove(channelId)?.toList().orEmpty()
        }
        retired.forEach { waiter ->
            val result = if (metadata.currentObservation(generation, waiter.currentSession) == null) {
                EpgCoverageAcquisitionResult.ObservationExpired
            } else {
                rejection
            }
            waiter.settlement.complete(result)
        }
    }

    private fun expireWaiters() {
        val retired = synchronized(activityLock) {
            acceptingPriorities = false
            priorityTargets.clear()
            waiters.values.flatMap { channelWaiters -> channelWaiters.toList() }.also {
                waiters.clear()
            }
        }
        retired.forEach { waiter ->
            waiter.settlement.complete(EpgCoverageAcquisitionResult.ObservationExpired)
        }
    }

    private fun completeWaiter(
        waiter: EpgCoverageWaiter,
        result: EpgCoverageAcquisitionResult,
    ) {
        val removed = synchronized(activityLock) {
            val channelWaiters = waiters[waiter.channelId] ?: return@synchronized false
            if (!channelWaiters.remove(waiter)) return@synchronized false
            updatePriorityTargetLocked(waiter.channelId, channelWaiters)
            true
        }
        if (removed) waiter.settlement.complete(result)
    }

    private fun removeWaiter(waiter: EpgCoverageWaiter) {
        synchronized(activityLock) {
            val channelWaiters = waiters[waiter.channelId] ?: return
            if (channelWaiters.remove(waiter)) {
                updatePriorityTargetLocked(waiter.channelId, channelWaiters)
            }
        }
    }

    private fun updatePriorityTargetLocked(
        channelId: ChannelId,
        channelWaiters: Set<EpgCoverageWaiter>,
    ) {
        val target = channelWaiters.maxOfOrNull(EpgCoverageWaiter::target)
        if (target == null) {
            waiters.remove(channelId)
            priorityTargets.remove(channelId)
        } else {
            priorityTargets[channelId] = target
        }
    }
}

private class EpgCoverageWaiter(
    internal val currentSession: CurrentSessionObservation,
    internal val channelId: ChannelId,
    internal val target: Instant,
) {
    internal val settlement = CompletableDeferred<EpgCoverageAcquisitionResult>()
}

private data class EpgSchedulingSnapshot(
    internal val excluded: Set<ChannelId>,
    internal val priorityExcluded: Set<ChannelId>,
    internal val priorities: Map<ChannelId, Instant>,
    internal val priorityTurn: Boolean,
)

private fun coveredResult(
    observation: SessionObservation,
    coverage: EpgCoverage,
): EpgCoverageAcquisitionResult = if (coverage.isEmpty) {
    EpgCoverageAcquisitionResult.CoveredEmpty(observation)
} else {
    EpgCoverageAcquisitionResult.CoveredWithData(observation)
}

private fun EpgCoverageAcquisitionResult.toBatchSettlement(
    channelId: ChannelId,
): EpgCoverageBatchSettlement = when (this) {
    is EpgCoverageAcquisitionResult.CoveredWithData ->
        EpgCoverageBatchSettlement.CoveredWithData(channelId, observation)
    is EpgCoverageAcquisitionResult.CoveredEmpty ->
        EpgCoverageBatchSettlement.CoveredEmpty(channelId, observation)
    EpgCoverageAcquisitionResult.Ineligible -> EpgCoverageBatchSettlement.Rejected(channelId)
    EpgCoverageAcquisitionResult.CapacityExhausted -> EpgCoverageBatchSettlement.CapacityExhausted(channelId)
    EpgCoverageAcquisitionResult.ObservationExpired ->
        EpgCoverageBatchSettlement.ObservationExpired(channelId)
}

private fun expiredBatch(channelIds: List<ChannelId>): EpgCoverageBatchResult =
    EpgCoverageBatchResult.create(
        channelIds.distinct().map(EpgCoverageBatchSettlement::ObservationExpired),
    )

private fun Instant.toWholeSecond(): Instant = Instant.fromEpochSeconds(epochSeconds)
