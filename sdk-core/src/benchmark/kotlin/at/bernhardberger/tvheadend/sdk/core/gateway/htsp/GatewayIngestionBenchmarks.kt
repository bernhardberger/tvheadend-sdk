@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.gateway.htsp

import at.bernhardberger.tvheadend.htsp.connection.HtspConnectOptions
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectOutcome
import at.bernhardberger.tvheadend.htsp.connection.HtspConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectionGeneration
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectionState
import at.bernhardberger.tvheadend.htsp.connection.HtspEndpoint
import at.bernhardberger.tvheadend.htsp.connection.HtspLiveConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspResult
import at.bernhardberger.tvheadend.htsp.connection.HtspServerFacts
import at.bernhardberger.tvheadend.htsp.connection.HtspSubscriptionEvent
import at.bernhardberger.tvheadend.htsp.connection.HtspTransportEvent
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspEventAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspInitialSyncCompletedMessage
import at.bernhardberger.tvheadend.htsp.model.HtspChannel
import at.bernhardberger.tvheadend.htsp.model.HtspEvent
import at.bernhardberger.tvheadend.htsp.requests.HtspRequest
import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectResult
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerConfiguration
import at.bernhardberger.tvheadend.sdk.core.session.PhaseOneSessionMetadata
import at.bernhardberger.tvheadend.sdk.core.session.drainMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/** Existing ingestion workload extended through the gateway and drain; no wire decoding or I/O. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
public class GatewayIngestionBenchmarks {
    /** 200 half-hour events per channel, matching EpgIngestionBenchmarks. */
    @Param("10", "100", "500")
    public var channelCount: Int = 0
    private lateinit var connection: IngestionConnection

    /** Prebuild decoded transport inputs and validate the complete published guide. */
    @Setup
    public fun setup(): Unit {
        connection = IngestionConnection(channelCount)
        val observation = ingestAndPublish()
        check(observation.currentSession != null)
        check(observation.channelCatalogForDisplay?.channels?.size == channelCount)
        check(observation.epgSnapshotForDisplay?.events?.size == channelCount * 200)
    }

    /** Includes mapping, draining, fresh reducers, reconciliation and ready publication. */
    @Benchmark
    public fun ingestAndPublish(): SessionObservation = runBlocking {
        val gateway = HtspProtocolGateway(connection)
        val generation = (gateway.connect(ServerConfiguration("synthetic", 9982)) as GatewayConnectResult.Connected)
            .connection.generation
        val metadata = PhaseOneSessionMetadata()
        metadata.bindGeneration(generation)
        drainMetadata(gateway.metadataBatches, metadata)
        metadata.publishSessionState(
            SessionState.Ready(ServerCapabilities.create(CapabilityAccess.UNKNOWN, CapabilityAccess.UNKNOWN)),
            RecordingProgressCapability.UNKNOWN, generation,
        )
        metadata.observation.value
    }
}

/** Gateway-owned fake: both APIs emit the same predecoded, prebuilt ordered inputs. */
private class IngestionConnection(channelCount: Int) : HtspConnection {
    private val generation = HtspConnectionGeneration()
    private val live = HtspLiveConnection(generation, 43, true, HtspServerFacts())
    override val connectionState = MutableStateFlow<HtspConnectionState>(HtspConnectionState.Connected("synthetic", 9982, live))
    private val inputs = buildList<HtspTransportEvent> {
        repeat(channelCount) { index ->
            add(HtspTransportEvent.ServerMessage(HtspChannelAddMessage(HtspChannel(
                channelId = index.toLong(), channelUuid = null, channelNumber = 0, channelNumberMinor = null,
                channelName = "Channel $index", channelIcon = null, currentEventId = 0, nextEventId = 0,
                services = emptyList(), tagIds = emptyList(),
            )), generation, size.toLong() + 1))
        }
        repeat(channelCount * 200) { index ->
            add(HtspTransportEvent.ServerMessage(HtspEventAddMessage(HtspEvent(
                eventId = index.toLong(), channelId = (index % channelCount).toLong(),
                startEpochSeconds = index / channelCount * 1800L,
                stopEpochSeconds = index / channelCount * 1800L + 1800,
                title = "Programme $index", summary = "Synthetic programme summary",
            )), generation, size.toLong() + 1))
        }
        add(HtspTransportEvent.ServerMessage(HtspInitialSyncCompletedMessage, generation, size.toLong() + 1))
    }
    private val batches = inputs.chunked(512)
    override val events: Flow<HtspTransportEvent> = flow { inputs.forEach { emit(it) } }
    override fun eventBatches(maxBatchSize: Int): Flow<List<HtspTransportEvent>> {
        require(maxBatchSize in 1..4096)
        return flow { (if (maxBatchSize == 512) batches else inputs.chunked(maxBatchSize)).forEach { emit(it) } }
    }
    override suspend fun connect(endpoint: HtspEndpoint, options: HtspConnectOptions): HtspConnectOutcome =
        HtspConnectOutcome.Connected(live)
    override fun isCurrent(generation: HtspConnectionGeneration): Boolean = generation === this.generation
    override suspend fun disconnect(expectedGeneration: HtspConnectionGeneration?): Boolean = true
    override suspend fun close(expectedGeneration: HtspConnectionGeneration?): Boolean = true
    override fun subscriptionEvents(subscriptionId: Long): Flow<HtspSubscriptionEvent> = error("Unused")
    override fun subscriptionEvents(subscriptionId: Long, expectedGeneration: HtspConnectionGeneration): Flow<HtspSubscriptionEvent> =
        error("Unused")
    override suspend fun <R> execute(request: HtspRequest<R>, timeoutMs: Long, expectedGeneration: HtspConnectionGeneration?): HtspResult<R> =
        error("Unused")
}
