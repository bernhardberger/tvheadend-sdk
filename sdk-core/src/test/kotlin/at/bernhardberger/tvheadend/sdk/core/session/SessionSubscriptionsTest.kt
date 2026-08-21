@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.AutorecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrConfiguration
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpace
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleId
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectionFailureEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayState
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.ProtocolGateway
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerConfiguration
import at.bernhardberger.tvheadend.sdk.playback.StreamIndex
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionCondition
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionConfirmation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStream
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SessionSubscriptionsTest {
    @Test
    fun `children bind admission and teardown one exact generation at a time`() = runTest {
        val gateway = SubscriptionGateway()
        val children = PlaybackSessionChildren(
            gateway,
            PhaseOneSessionMetadata(),
            StandardTestDispatcher(testScheduler),
        )
        val generationA = GatewayGeneration()
        val generationB = GatewayGeneration()

        assertSame(
            SubscriptionOpenResult.NotReady,
            children.open(SubscriptionChannelId(1L), SubscriptionEventConsumer {}),
        )
        children.bindGeneration(generationA)
        assertSame(
            SubscriptionOpenResult.NotReady,
            children.open(SubscriptionChannelId(1L), SubscriptionEventConsumer {}),
        )
        assertFalse(children.startAdmission(generationB))
        assertTrue(children.startAdmission(generationA))

        val openA = async {
            children.open(SubscriptionChannelId(1L), SubscriptionEventConsumer {})
        }
        runCurrent()
        gateway.emitStarted(generationA)
        runCurrent()
        assertTrue(openA.await() is SubscriptionOpenResult.Opened)
        assertSame(generationA, gateway.collectedGenerations.single())
        assertTrue(
            gateway.collectedIds.single() == SubscriptionId(0L),
            "The first generation must allocate its first subscription identifier",
        )

        children.stopAdmission()
        children.closeAndJoinSubscriptions()
        assertSame(generationA, gateway.unsubscribedGenerations.single())
        assertTrue(
            gateway.unsubscribedIds.single() == SubscriptionId(0L),
            "Teardown must unsubscribe the admitted subscription",
        )
        assertSame(
            SubscriptionOpenResult.NotReady,
            children.open(SubscriptionChannelId(1L), SubscriptionEventConsumer {}),
        )

        children.bindGeneration(generationB)
        assertTrue(children.startAdmission(generationB))
        val openB = async {
            children.open(SubscriptionChannelId(1L), SubscriptionEventConsumer {})
        }
        runCurrent()
        gateway.emitStarted(generationB)
        runCurrent()
        assertTrue(openB.await() is SubscriptionOpenResult.Opened)
        assertSame(generationB, gateway.collectedGenerations.last())
        assertTrue(
            gateway.collectedIds.last() == SubscriptionId(0L),
            "A fresh generation must receive a fresh identifier namespace",
        )
        children.closeAndJoinSubscriptions()
    }

    @Test
    fun `cancelled teardown retains the manager until every subscription joins`() = runTest {
        val gateway = SubscriptionGateway()
        val children = PlaybackSessionChildren(
            gateway,
            PhaseOneSessionMetadata(),
            StandardTestDispatcher(testScheduler),
        )
        val generation = GatewayGeneration()
        val consumerEntered = CompletableDeferred<Unit>()
        val releaseConsumer = CompletableDeferred<Unit>()
        children.bindGeneration(generation)
        assertTrue(children.startAdmission(generation))
        val opening = async {
            children.open(
                SubscriptionChannelId(2L),
                SubscriptionEventConsumer { event ->
                    if (event is SubscriptionEvent.Packet) {
                        consumerEntered.complete(Unit)
                        releaseConsumer.await()
                    }
                },
            )
        }
        runCurrent()
        gateway.emitStarted(generation)
        runCurrent()
        opening.await()
        gateway.emitPacket(generation)
        consumerEntered.await()

        children.stopAdmission()
        val cancellationSeen = CompletableDeferred<Boolean>()
        val firstClose = launch {
            try {
                children.closeAndJoinSubscriptions()
                cancellationSeen.complete(false)
            } catch (_: CancellationException) {
                cancellationSeen.complete(true)
            }
        }
        runCurrent()
        firstClose.cancel(CancellationException("fixed teardown cancellation"))
        val secondClose = async { children.closeAndJoinSubscriptions() }
        runCurrent()
        assertFalse(firstClose.isCompleted)
        assertFalse(secondClose.isCompleted)

        releaseConsumer.complete(Unit)
        runCurrent()
        firstClose.join()
        secondClose.await()

        assertTrue(cancellationSeen.await())
        assertEquals(1, gateway.unsubscribeCount)
        assertSame(
            SubscriptionOpenResult.NotReady,
            children.open(SubscriptionChannelId(2L), SubscriptionEventConsumer {}),
        )
    }

    @Test
    fun `child cancellation clears the old manager before a fresh generation binds`() = runTest {
        val gateway = SubscriptionGateway()
        val children = PlaybackSessionChildren(
            gateway,
            PhaseOneSessionMetadata(),
            StandardTestDispatcher(testScheduler),
        )
        val generationA = GatewayGeneration()
        val generationB = GatewayGeneration()
        val consumerEntered = CompletableDeferred<Unit>()
        val releaseConsumer = CompletableDeferred<Unit>()
        val childCancellation = CancellationException("fixed child cancellation")
        children.bindGeneration(generationA)
        assertTrue(children.startAdmission(generationA))
        val opening = async {
            children.open(
                SubscriptionChannelId(3L),
                SubscriptionEventConsumer { event ->
                    if (event is SubscriptionEvent.Packet) {
                        consumerEntered.complete(Unit)
                        releaseConsumer.await()
                        throw childCancellation
                    }
                },
            )
        }
        runCurrent()
        gateway.emitStarted(generationA)
        runCurrent()
        opening.await()
        gateway.emitPacket(generationA)
        consumerEntered.await()
        children.stopAdmission()
        var observedCancellation: CancellationException? = null
        val closing = launch {
            try {
                children.closeAndJoinSubscriptions()
            } catch (cancellation: CancellationException) {
                observedCancellation = cancellation
            }
        }
        runCurrent()
        releaseConsumer.complete(Unit)
        runCurrent()
        closing.join()

        assertSame(childCancellation, observedCancellation)
        children.bindGeneration(generationB)
        assertTrue(children.startAdmission(generationB))
        val reopened = async {
            children.open(SubscriptionChannelId(3L), SubscriptionEventConsumer {})
        }
        runCurrent()
        gateway.emitStarted(generationB)
        runCurrent()
        assertTrue(reopened.await() is SubscriptionOpenResult.Opened)
        assertTrue(
            gateway.collectedIds.last() == SubscriptionId(0L),
            "A replacement generation must restart its identifier namespace",
        )
        children.closeAndJoinSubscriptions()
    }
}

private class SubscriptionGateway : ProtocolGateway {
    private val lock = Any()
    private val live = java.util.Collections.newSetFromMap(
        IdentityHashMap<GatewayGeneration, Boolean>(),
    )
    private val streams = IdentityHashMap<GatewayGeneration, Channel<SubscriptionEvent>>()

    internal val collectedGenerations = ArrayList<GatewayGeneration>()
    internal val collectedIds = ArrayList<SubscriptionId>()
    internal val unsubscribedGenerations = ArrayList<GatewayGeneration>()
    internal val unsubscribedIds = ArrayList<SubscriptionId>()
    internal var unsubscribeCount: Int = 0
        private set

    override val connectionState = MutableStateFlow<GatewayState>(GatewayState.Disconnected)
    override val metadata: Flow<MetadataEvent> = emptyFlow()
    override val connectionFailures: Flow<GatewayConnectionFailureEvent> = emptyFlow()

    override suspend fun connect(server: ServerConfiguration): GatewayConnectResult =
        error("Connection is not used by this test")

    override suspend fun disconnect() = Unit

    override suspend fun shutdown() = Unit

    override fun <T> commitIfLive(
        generation: GatewayGeneration,
        block: () -> T,
    ): T? = synchronized(lock) {
        if (live.add(generation) || generation in live) block() else null
    }

    override suspend fun enableInitialMetadata(generation: GatewayGeneration): GatewayResult<Unit> =
        GatewayResult.Ok(Unit)

    override suspend fun queryEpg(
        generation: GatewayGeneration,
        channelId: ChannelId,
        maxTime: Instant,
    ): GatewayResult<List<GatewayEpgQueryEvent>> = GatewayResult.Ok(emptyList())

    override suspend fun getDvrConfigs(
        generation: GatewayGeneration,
    ): GatewayResult<List<DvrConfiguration>> = GatewayResult.Ok(emptyList())

    override suspend fun getDiskSpace(
        generation: GatewayGeneration,
    ): GatewayResult<DvrDiskSpace> = GatewayResult.Ok(DvrDiskSpace(0, 0, 0))

    override suspend fun scheduleDvrEntry(
        generation: GatewayGeneration,
        request: DvrScheduleRequest,
    ): GatewayResult<DvrEntryId> = GatewayResult.NotSupported

    override suspend fun updateDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
        update: DvrEntryUpdate,
    ): GatewayResult<Unit> = GatewayResult.NotSupported

    override suspend fun stopDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<Unit> = GatewayResult.NotSupported

    override suspend fun cancelDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<Unit> = GatewayResult.NotSupported

    override suspend fun deleteDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<Unit> = GatewayResult.NotSupported

    override suspend fun createAutorecRule(
        generation: GatewayGeneration,
        request: AutorecRuleCreate,
    ): GatewayResult<AutorecRuleId> = GatewayResult.NotSupported

    override suspend fun updateAutorecRule(
        generation: GatewayGeneration,
        id: AutorecRuleId,
        update: AutorecRuleUpdate,
    ): GatewayResult<Unit> = GatewayResult.NotSupported

    override suspend fun deleteAutorecRule(
        generation: GatewayGeneration,
        id: AutorecRuleId,
    ): GatewayResult<Unit> = GatewayResult.NotSupported

    override suspend fun createTimerecRule(
        generation: GatewayGeneration,
        request: TimerecRuleCreate,
    ): GatewayResult<TimerecRuleId> = GatewayResult.NotSupported

    override suspend fun updateTimerecRule(
        generation: GatewayGeneration,
        id: TimerecRuleId,
        update: TimerecRuleUpdate,
    ): GatewayResult<Unit> = GatewayResult.NotSupported

    override suspend fun deleteTimerecRule(
        generation: GatewayGeneration,
        id: TimerecRuleId,
    ): GatewayResult<Unit> = GatewayResult.NotSupported

    override suspend fun reportDvrProgress(
        generation: GatewayGeneration,
        id: DvrEntryId,
        progress: DvrPlaybackProgress,
    ): GatewayResult<Unit> = GatewayResult.NotSupported

    override fun subscription(
        generation: GatewayGeneration,
        id: SubscriptionId,
    ): Flow<SubscriptionEvent> = flow {
        val stream = synchronized(lock) {
            live += generation
            collectedGenerations += generation
            collectedIds += id
            Channel<SubscriptionEvent>(Channel.UNLIMITED).also { streams[generation] = it }
        }
        for (event in stream) emit(event)
    }

    override suspend fun subscribe(
        generation: GatewayGeneration,
        id: SubscriptionId,
        channelId: ChannelId,
    ): SubscriptionOperationResult<SubscriptionConfirmation> =
        SubscriptionOperationResult.Ok(SubscriptionConfirmation(null, null, null, null))

    override suspend fun unsubscribe(
        generation: GatewayGeneration,
        id: SubscriptionId,
    ): SubscriptionOperationResult<Unit> {
        synchronized(lock) {
            unsubscribeCount += 1
            unsubscribedGenerations += generation
            unsubscribedIds += id
            streams[generation]
        }?.close()
        return SubscriptionOperationResult.Ok(Unit)
    }

    internal suspend fun emitStarted(generation: GatewayGeneration) {
        val stream = synchronized(lock) { streams.getValue(generation) }
        stream.send(
            SubscriptionEvent.Started(
                streams = listOf(stream()),
                codecMetadata = null,
                condition = SubscriptionCondition.NO_DETAIL,
            ),
        )
    }


    internal suspend fun emitPacket(generation: GatewayGeneration) {
        val stream = synchronized(lock) { streams.getValue(generation) }
        stream.send(
            SubscriptionEvent.Packet(
                frameType = at.bernhardberger.tvheadend.sdk.playback.MuxFrameType.I,
                streamIndex = StreamIndex(0L),
                decodingTimeUs = 1L,
                presentationTimeUs = 2L,
                durationUs = 3L,
                payload = object : at.bernhardberger.tvheadend.sdk.playback.SubscriptionBinary {
                    override val size: Int = 0

                    override fun copyInto(destination: ByteArray, destinationOffset: Int): Int = 0
                },
            ),
        )
    }
}

private fun stream(): SubscriptionStream = SubscriptionStream(
    index = StreamIndex(0L),
    type = SubscriptionStreamType.H264,
    language = null,
    compositionId = null,
    ancillaryId = null,
    width = null,
    height = null,
    frameDuration = null,
    aspectNumerator = null,
    aspectDenominator = null,
    audioType = null,
    audioVersion = null,
    channelCount = null,
    rate = null,
    rdsUecp = null,
    codecMetadata = null,
)
