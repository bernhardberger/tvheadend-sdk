package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayServerFacts
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.metadata.ChannelTagCatalogState
import at.bernhardberger.tvheadend.sdk.core.metadata.ChannelTagReducer
import at.bernhardberger.tvheadend.sdk.core.metadata.ChannelTagSnapshot
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface SessionMetadata {
    public val channelsAndTags: StateFlow<ChannelTagCatalogState>

    public fun resetWorkingStateRetainingPublishedSnapshot()

    public fun bindGeneration(generation: GatewayGeneration)

    public fun applyDvrAccess(generation: GatewayGeneration, access: Boolean?)

    public fun publishServerFacts(generation: GatewayGeneration, facts: GatewayServerFacts)

    public fun acceptMetadata(event: MetadataEvent)

    public suspend fun awaitChannelsAndTagsCurrent(generation: GatewayGeneration)

    public fun capabilities(generation: GatewayGeneration): ServerCapabilities
}

internal class PhaseOneSessionMetadata : SessionMetadata {
    private val lock = Any()
    private val reducer = ChannelTagReducer()
    private val mutableChannelsAndTags = MutableStateFlow<ChannelTagCatalogState>(
        ChannelTagCatalogState.Empty,
    )
    private var generation: GatewayGeneration? = null
    private var initialSync = CompletableDeferred<Unit>()
    private var publishedSnapshot: ChannelTagSnapshot? = null
    private var synchronizedCurrent = false
    private var streaming: Boolean? = null
    private var dvrAccess: Boolean? = null

    override val channelsAndTags: StateFlow<ChannelTagCatalogState> =
        mutableChannelsAndTags.asStateFlow()

    override fun resetWorkingStateRetainingPublishedSnapshot() {
        val retiredFence = synchronized(lock) {
            generation = null
            val previousFence = initialSync
            initialSync = CompletableDeferred()
            synchronizedCurrent = false
            streaming = null
            dvrAccess = null
            reducer.clear()
            mutableChannelsAndTags.value = publishedSnapshot?.let { snapshot ->
                ChannelTagCatalogState.Stale(snapshot)
            }
                ?: ChannelTagCatalogState.Empty
            previousFence
        }
        retiredFence.cancel(CancellationException("Session generation is no longer current"))
    }

    override fun bindGeneration(generation: GatewayGeneration) {
        val retiredFence = synchronized(lock) {
            val previousFence = initialSync
            this.generation = generation
            initialSync = CompletableDeferred()
            synchronizedCurrent = false
            streaming = null
            dvrAccess = null
            reducer.clear()
            mutableChannelsAndTags.value = ChannelTagCatalogState.Synchronizing(publishedSnapshot)
            previousFence
        }
        retiredFence.cancel(CancellationException("Session generation is no longer current"))
    }

    override fun applyDvrAccess(generation: GatewayGeneration, access: Boolean?) {
        synchronized(lock) {
            if (this.generation === generation) {
                dvrAccess = access
            }
        }
    }

    override fun publishServerFacts(generation: GatewayGeneration, facts: GatewayServerFacts) {
        synchronized(lock) {
            if (this.generation === generation) {
                streaming = facts.streaming
            }
        }
    }

    override fun acceptMetadata(event: MetadataEvent) {
        val completedFence = synchronized(lock) {
            if (event.generation !== generation) {
                return@synchronized null
            }
            when (event) {
                is MetadataEvent.InitialSyncCompleted -> {
                    if (synchronizedCurrent) {
                        null
                    } else {
                        reducer.reconcileReferences()
                        val snapshot = reducer.snapshot()
                        synchronizedCurrent = true
                        publishCurrent(snapshot)
                        initialSync
                    }
                }
                is MetadataEvent.ChannelAdded,
                is MetadataEvent.ChannelUpdated,
                is MetadataEvent.ChannelDeleted,
                is MetadataEvent.TagAdded,
                is MetadataEvent.TagUpdated,
                is MetadataEvent.TagDeleted,
                is MetadataEvent.EventDeleted,
                -> {
                    reducer.accept(event)
                    if (synchronizedCurrent) {
                        publishCurrent(reducer.snapshot())
                    }
                    null
                }
                is MetadataEvent.Deferred -> null
            }
        }
        completedFence?.complete(Unit)
    }

    override suspend fun awaitChannelsAndTagsCurrent(generation: GatewayGeneration) {
        val fence = synchronized(lock) {
            check(this.generation === generation) { "Session generation is not current" }
            initialSync
        }
        fence.await()
        synchronized(lock) {
            check(
                this.generation === generation &&
                    synchronizedCurrent &&
                    mutableChannelsAndTags.value is ChannelTagCatalogState.Current,
            ) { "Session generation is not current" }
        }
    }

    override fun capabilities(generation: GatewayGeneration): ServerCapabilities = synchronized(lock) {
        check(this.generation === generation && synchronizedCurrent) {
            "Session generation is not current"
        }
        ServerCapabilities(
            streaming = streaming.toCapabilityAccess(),
            dvrWrite = dvrAccess.toCapabilityAccess(),
        )
    }

    private fun publishCurrent(snapshot: ChannelTagSnapshot) {
        mutableChannelsAndTags.value = ChannelTagCatalogState.Current(snapshot)
        publishedSnapshot = (mutableChannelsAndTags.value as ChannelTagCatalogState.Current).snapshot
    }
}

private fun Boolean?.toCapabilityAccess(): CapabilityAccess = when (this) {
    null -> CapabilityAccess.UNKNOWN
    true -> CapabilityAccess.ALLOWED
    false -> CapabilityAccess.DENIED
}

internal interface SessionChildren {
    public fun stopAdmission()

    public suspend fun cancelAndJoinEpgWorker()

    public suspend fun closeAndJoinSubscriptions()

    public data object None : SessionChildren {
        override fun stopAdmission() = Unit

        override suspend fun cancelAndJoinEpgWorker() = Unit

        override suspend fun closeAndJoinSubscriptions() = Unit
    }
}
