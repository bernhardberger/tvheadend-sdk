package at.bernhardberger.tvheadend.sdk.core.benchmarks

import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgUpdate
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.session.PhaseOneSessionMetadata
import kotlin.time.Instant

/** Already-decoded synthetic inputs: transport, string construction and I/O are not measured. */
internal class EpgFixture(channelCount: Int) {
    val generation = GatewayGeneration()
    val eventCount = channelCount * 200
    val from: Instant = Instant.fromEpochSeconds(0)
    val to: Instant = Instant.fromEpochSeconds(360_000)
    private val channels = List(channelCount) { index ->
        MetadataEvent.ChannelAdded(generation, GatewayChannelMetadata(
            id = ChannelId(index.toLong()), name = "Channel $index", uuid = null,
            number = null, numberMinor = null, icon = null, currentEventId = null,
            nextEventId = null, services = null, tagIds = null,
        ))
    }
    private val events = List(eventCount) { index ->
        MetadataEvent.EventAdded(generation, GatewayEpgEvent(
            EventId(index.toLong()), ChannelId((index % channelCount).toLong()),
            Instant.fromEpochSeconds(index / channelCount * 1800L),
            Instant.fromEpochSeconds(index / channelCount * 1800L + 1800),
            title = "Programme $index", summary = "Synthetic programme summary",
        ))
    }
    val updates: List<List<MetadataEvent.EventUpdated>> = List(2) { revision ->
        List(100) { index ->
            MetadataEvent.EventUpdated(generation, GatewayEpgUpdate(
                EventId(index.toLong()), title = "Updated $revision",
            ))
        }
    }
    val queries: List<List<GatewayEpgQueryEvent>> = List(2) { revision ->
        listOf(GatewayEpgQueryEvent(
            EventId(0), ChannelId(0), from, Instant.fromEpochSeconds(1800),
            title = "Query $revision",
        ))
    }

    fun readyMetadata(
        metadata: PhaseOneSessionMetadata = PhaseOneSessionMetadata(),
        beforeInitialSync: () -> Unit = {},
    ): PhaseOneSessionMetadata = metadata.also {
        metadata.bindGeneration(generation)
        channels.forEach(metadata::acceptMetadata)
        events.forEach(metadata::acceptMetadata)
        beforeInitialSync()
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        metadata.publishSessionState(
            SessionState.Ready(ServerCapabilities.create(CapabilityAccess.UNKNOWN, CapabilityAccess.UNKNOWN)),
            RecordingProgressCapability.UNKNOWN, generation,
        )
    }

    fun validate(metadata: PhaseOneSessionMetadata) {
        val observation = metadata.observation.value
        checkNotNull(observation.currentSession)
        check(observation.channelCatalogForDisplay?.channels?.size == channels.size)
        check(observation.epgSnapshotForDisplay?.events?.size == eventCount)
    }
}
