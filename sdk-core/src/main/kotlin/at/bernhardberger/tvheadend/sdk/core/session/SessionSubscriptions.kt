@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.ProtocolGateway
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionConnection
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionConfirmation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionManager
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import at.bernhardberger.tvheadend.sdk.playback.createSubscriptionManager
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration

internal class PlaybackSessionChildren(
    private val gateway: ProtocolGateway,
    private val metadata: SessionMetadata,
    private val dispatcher: CoroutineDispatcher,
    private val clock: Clock = Clock.System,
    private val epgSettings: EpgWorkerSettings = EpgWorkerSettings(),
) : SessionChildren {
    private val lock = Any()
    private var generation: GatewayGeneration? = null
    private var epgWorker: ActiveEpgWorker? = null
    private var subscriptions: SubscriptionManager? = null

    override suspend fun open(
        channelId: SubscriptionChannelId,
        consumer: SubscriptionEventConsumer,
        timeshiftPeriod: Duration,
    ): SubscriptionOpenResult {
        currentCoroutineContext().ensureActive()
        return synchronized(lock) { subscriptions }
            ?.open(channelId, consumer, timeshiftPeriod)
            ?: SubscriptionOpenResult.NotReady
    }

    override fun bindGeneration(generation: GatewayGeneration) {
        synchronized(lock) {
            check(epgWorker == null) { "Previous EPG worker is still active" }
            check(subscriptions == null) { "Previous subscription generation is still active" }
            this.generation = generation
            subscriptions = createSubscriptionManager(
                GatewaySubscriptionConnection(gateway, generation),
                dispatcher,
            )
        }
    }

    override fun startAdmission(generation: GatewayGeneration): Boolean = synchronized(lock) {
        if (this.generation !== generation) {
            false
        } else {
            subscriptions?.startAdmission()
            true
        }
    }

    override fun stopAdmission() {
        synchronized(lock) { subscriptions?.stopAdmission() }
    }

    override fun startEpgWorker(generation: GatewayGeneration): Boolean {
        val active = synchronized(lock) {
            if (this.generation !== generation || epgWorker != null) return false
            val worker = EpgWorker(
                metadata = metadata,
                clock = clock,
                settings = epgSettings,
                queryEpg = gateway::queryEpg,
            )
            val job = CoroutineScope(dispatcher).launch(start = CoroutineStart.LAZY) {
                worker.run(generation)
            }
            ActiveEpgWorker(generation, worker, job).also { epgWorker = it }
        }
        active.job.start()
        return true
    }

    override suspend fun awaitEpgWarmup(generation: GatewayGeneration) {
        val active = synchronized(lock) {
            epgWorker?.takeIf { worker -> worker.generation === generation }
        } ?: throw CancellationException("EPG worker generation is no longer active")
        active.worker.awaitWarmup()
    }

    override suspend fun cancelAndJoinEpgWorker() {
        val active = synchronized(lock) { epgWorker } ?: return
        withContext(NonCancellable) {
            active.job.cancelAndJoin()
            synchronized(lock) {
                if (epgWorker === active) epgWorker = null
            }
        }
        currentCoroutineContext().ensureActive()
    }

    override suspend fun closeAndJoinSubscriptions() {
        val manager = synchronized(lock) { subscriptions } ?: return
        var childCancellation: CancellationException? = null
        withContext(NonCancellable) {
            try {
                manager.closeAndJoin()
            } catch (cancellation: CancellationException) {
                childCancellation = cancellation
            } finally {
                synchronized(lock) {
                    if (subscriptions === manager) {
                        generation = null
                        subscriptions = null
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        childCancellation?.let { throw it }
    }
}

private class ActiveEpgWorker(
    internal val generation: GatewayGeneration,
    internal val worker: EpgWorker,
    internal val job: Job,
)

private class GatewaySubscriptionConnection(
    private val gateway: ProtocolGateway,
    private val generation: GatewayGeneration,
) : SubscriptionConnection {
    override fun events(id: SubscriptionId): Flow<SubscriptionEvent> =
        gateway.subscription(generation, id)

    override suspend fun subscribe(
        id: SubscriptionId,
        channelId: SubscriptionChannelId,
        timeshiftPeriod: Duration,
    ): SubscriptionOperationResult<SubscriptionConfirmation> = gateway.subscribe(
        generation = generation,
        id = id,
        channelId = ChannelId(channelId.value),
        timeshiftPeriod = timeshiftPeriod,
    )

    override suspend fun skip(
        id: SubscriptionId,
        target: SubscriptionSeekTarget,
    ): SubscriptionOperationResult<Unit> = gateway.skipSubscription(generation, id, target)

    override suspend fun unsubscribe(id: SubscriptionId): SubscriptionOperationResult<Unit> =
        gateway.unsubscribe(generation, id)

    override fun <T> commitIfLive(block: () -> T): T? = gateway.commitIfLive(generation, block)
}
