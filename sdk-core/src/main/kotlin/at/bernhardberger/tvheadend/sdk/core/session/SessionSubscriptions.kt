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
import at.bernhardberger.tvheadend.sdk.playback.createSubscriptionManager
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

internal class PlaybackSessionChildren(
    private val gateway: ProtocolGateway,
    private val dispatcher: CoroutineDispatcher,
) : SessionChildren {
    private val lock = Any()
    private var generation: GatewayGeneration? = null
    private var subscriptions: SubscriptionManager? = null

    override suspend fun open(
        channelId: SubscriptionChannelId,
        consumer: SubscriptionEventConsumer,
    ): SubscriptionOpenResult {
        currentCoroutineContext().ensureActive()
        return synchronized(lock) { subscriptions }
            ?.open(channelId, consumer)
            ?: SubscriptionOpenResult.NotReady
    }

    override fun bindGeneration(generation: GatewayGeneration) {
        synchronized(lock) {
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

    override suspend fun cancelAndJoinEpgWorker() = Unit

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

private class GatewaySubscriptionConnection(
    private val gateway: ProtocolGateway,
    private val generation: GatewayGeneration,
) : SubscriptionConnection {
    override fun events(id: SubscriptionId): Flow<SubscriptionEvent> =
        gateway.subscription(generation, id)

    override suspend fun subscribe(
        id: SubscriptionId,
        channelId: SubscriptionChannelId,
    ): SubscriptionOperationResult<SubscriptionConfirmation> = gateway.subscribe(
        generation = generation,
        id = id,
        channelId = ChannelId(channelId.value),
    )

    override suspend fun unsubscribe(id: SubscriptionId): SubscriptionOperationResult<Unit> =
        gateway.unsubscribe(generation, id)

    override fun <T> commitIfLive(block: () -> T): T? = gateway.commitIfLive(generation, block)
}
