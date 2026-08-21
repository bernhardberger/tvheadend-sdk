@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.ChannelRepository
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgRepository
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.StateBackedChannelRepository
import at.bernhardberger.tvheadend.sdk.core.StateBackedEpgRepository
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayServerFacts
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.metadata.ChannelTagReducer
import at.bernhardberger.tvheadend.sdk.core.metadata.EpgReducer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpener
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant

internal interface SessionMetadata : ChannelRepository {
    public val channelsAndTags: StateFlow<ChannelRepositoryState>
        get() = state

    public val epgRepository: EpgRepository

    public fun resetWorkingStateRetainingPublishedSnapshot()

    public fun clearAllState()

    public fun bindGeneration(generation: GatewayGeneration)

    public fun applyDvrAccess(generation: GatewayGeneration, access: Boolean?)

    public fun publishServerFacts(generation: GatewayGeneration, facts: GatewayServerFacts)

    public fun acceptMetadata(event: MetadataEvent)

    public suspend fun awaitMetadataCurrent(generation: GatewayGeneration)

    public fun recordSuccessfulEpgQuery(
        generation: GatewayGeneration,
        channelId: ChannelId,
        queriedTo: Instant,
    )

    public fun retainEpgEvents(generation: GatewayGeneration, from: Instant, to: Instant)

    public fun capabilities(generation: GatewayGeneration): ServerCapabilities
}

internal class PhaseOneSessionMetadata : StateBackedChannelRepository(), SessionMetadata {
    private val lock = Any()
    private val reducer = ChannelTagReducer()
    private val epgReducer = EpgReducer()
    private val mutableChannelsAndTags = MutableStateFlow<ChannelRepositoryState>(
        ChannelRepositoryState.Empty,
    )
    private val mutableEpg = MutableStateFlow<EpgRepositoryState>(EpgRepositoryState.Empty)
    private val stateBackedEpgRepository = object : StateBackedEpgRepository() {
        override val state: StateFlow<EpgRepositoryState> = mutableEpg.asStateFlow()
    }
    private var generation: GatewayGeneration? = null
    private var initialSync = CompletableDeferred<Unit>()
    private var publishedCatalog: ChannelCatalog? = null
    private var publishedEpgSnapshot: EpgSnapshot? = null
    private var synchronizedCurrent = false
    private var serverFacts: GatewayServerFacts? = null
    private var dvrAccess: Boolean? = null

    override val state: StateFlow<ChannelRepositoryState> =
        mutableChannelsAndTags.asStateFlow()
    override val epgRepository: EpgRepository = stateBackedEpgRepository

    override fun resetWorkingStateRetainingPublishedSnapshot() {
        resetState(retainPublishedCatalog = true)
    }

    override fun clearAllState() {
        resetState(retainPublishedCatalog = false)
    }

    private fun resetState(retainPublishedCatalog: Boolean) {
        val retiredFence = synchronized(lock) {
            generation = null
            val previousFence = initialSync
            initialSync = CompletableDeferred()
            synchronizedCurrent = false
            serverFacts = null
            dvrAccess = null
            reducer.clear()
            epgReducer.clear()
            if (!retainPublishedCatalog) {
                publishedCatalog = null
                publishedEpgSnapshot = null
            }
            mutableChannelsAndTags.value = publishedCatalog?.let { catalog ->
                ChannelRepositoryState.Stale(catalog)
            } ?: ChannelRepositoryState.Empty
            mutableEpg.value = publishedEpgSnapshot?.let(EpgRepositoryState::Stale)
                ?: EpgRepositoryState.Empty
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
            serverFacts = null
            dvrAccess = null
            reducer.clear()
            epgReducer.clear()
            mutableChannelsAndTags.value = ChannelRepositoryState.Synchronizing(publishedCatalog)
            mutableEpg.value = EpgRepositoryState.Synchronizing(publishedEpgSnapshot)
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
                serverFacts = facts
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
                        val channelSnapshot = reducer.snapshot()
                        epgReducer.reconcileChannels(channelSnapshot.channels.map { channel -> channel.id })
                        val epgSnapshot = epgReducer.snapshot()
                        synchronizedCurrent = true
                        publishCurrent(channelSnapshot, epgSnapshot)
                        initialSync
                    }
                }
                is MetadataEvent.ChannelAdded,
                is MetadataEvent.ChannelUpdated,
                is MetadataEvent.ChannelDeleted,
                is MetadataEvent.TagAdded,
                is MetadataEvent.TagUpdated,
                is MetadataEvent.TagDeleted,
                is MetadataEvent.EventAdded,
                is MetadataEvent.EventUpdated,
                is MetadataEvent.EventDeleted,
                -> {
                    reducer.accept(event)
                    epgReducer.accept(event)
                    if (synchronizedCurrent) {
                        publishCurrent(reducer.snapshot(), epgReducer.snapshot())
                    }
                    null
                }
                is MetadataEvent.Deferred -> null
            }
        }
        completedFence?.complete(Unit)
    }

    override suspend fun awaitMetadataCurrent(generation: GatewayGeneration) {
        val fence = synchronized(lock) {
            check(this.generation === generation) { "Session generation is not current" }
            initialSync
        }
        fence.await()
        synchronized(lock) {
            check(
                this.generation === generation &&
                    synchronizedCurrent &&
                    mutableChannelsAndTags.value is ChannelRepositoryState.Current &&
                    mutableEpg.value is EpgRepositoryState.Current,
            ) { "Session generation is not current" }
        }
    }

    override fun recordSuccessfulEpgQuery(
        generation: GatewayGeneration,
        channelId: ChannelId,
        queriedTo: Instant,
    ) {
        synchronized(lock) {
            if (this.generation === generation) {
                epgReducer.recordSuccessfulQuery(channelId, queriedTo)
                if (synchronizedCurrent) publishCurrentEpg(epgReducer.snapshot())
            }
        }
    }

    override fun retainEpgEvents(generation: GatewayGeneration, from: Instant, to: Instant) {
        synchronized(lock) {
            if (this.generation === generation) {
                epgReducer.retainOverlapping(from, to)
                if (synchronizedCurrent) publishCurrentEpg(epgReducer.snapshot())
            }
        }
    }

    override fun capabilities(generation: GatewayGeneration): ServerCapabilities = synchronized(lock) {
        check(this.generation === generation && synchronizedCurrent) {
            "Session generation is not current"
        }
        val facts = serverFacts
        ServerCapabilities.create(
            streaming = facts?.streaming.toCapabilityAccess(),
            dvrWrite = dvrAccess.toCapabilityAccess(),
            protocolDvr = facts?.dvr.toCapabilityAccess(),
            failedDvr = facts?.failedDvr.toCapabilityAccess(),
            admin = facts?.admin.toCapabilityAccess(),
            anonymous = facts?.anonymous.toCapabilityAccess(),
            apiVersion = facts?.apiVersion,
            allLimit = facts?.limitAll,
            dvrLimit = facts?.limitDvr,
            streamingLimit = facts?.limitStreaming,
            uiLevel = facts?.uiLevel,
            features = facts?.serverCapabilities,
            serverName = facts?.serverName,
            serverVersion = facts?.serverVersion,
            webRoot = facts?.webRoot,
            language = facts?.language,
            uiLanguage = facts?.uiLanguage,
        )
    }

    private fun publishCurrent(catalog: ChannelCatalog, epgSnapshot: EpgSnapshot) {
        mutableChannelsAndTags.value = ChannelRepositoryState.Current(catalog)
        publishedCatalog = (mutableChannelsAndTags.value as ChannelRepositoryState.Current).catalog
        publishCurrentEpg(epgSnapshot)
    }

    private fun publishCurrentEpg(snapshot: EpgSnapshot) {
        mutableEpg.value = EpgRepositoryState.Current(snapshot)
        publishedEpgSnapshot = (mutableEpg.value as EpgRepositoryState.Current).snapshot
    }
}

private fun Boolean?.toCapabilityAccess(): CapabilityAccess = when (this) {
    null -> CapabilityAccess.UNKNOWN
    true -> CapabilityAccess.ALLOWED
    false -> CapabilityAccess.DENIED
}

internal interface SessionChildren : SubscriptionOpener {
    public fun bindGeneration(generation: GatewayGeneration)

    public fun startAdmission(generation: GatewayGeneration): Boolean

    public fun stopAdmission()

    public suspend fun cancelAndJoinEpgWorker()

    public suspend fun closeAndJoinSubscriptions()

    public data object None : SessionChildren {
        override suspend fun open(
            channelId: SubscriptionChannelId,
            consumer: SubscriptionEventConsumer,
        ): SubscriptionOpenResult = SubscriptionOpenResult.NotReady

        override fun bindGeneration(generation: GatewayGeneration) = Unit

        override fun startAdmission(generation: GatewayGeneration): Boolean = true

        override fun stopAdmission() = Unit

        override suspend fun cancelAndJoinEpgWorker() = Unit

        override suspend fun closeAndJoinSubscriptions() = Unit
    }
}
