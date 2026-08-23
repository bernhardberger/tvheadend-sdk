@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.playback

import java.util.concurrent.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration

/** Deterministic single-collector transport shared by the playback state-machine tests. */
internal class RecordingSubscriptionConnection : SubscriptionConnection {
    private val lock = Any()
    private val streams = LinkedHashMap<Long, Channel<SubscriptionEvent>>()
    private val consumed = LinkedHashSet<Long>()
    private val mutableCalls = ArrayList<Call>()
    private val mutableRegisteredIds = ArrayList<SubscriptionId>()
    private val mutableSeekTargets = ArrayList<SubscriptionSeekTarget>()
    internal var subscribeAction:
        suspend () -> SubscriptionOperationResult<SubscriptionConfirmation> = {
            successfulConfirmation()
        }
    internal var unsubscribeAction: suspend () -> SubscriptionOperationResult<Unit> = {
        SubscriptionOperationResult.Ok(Unit)
    }
    internal var skipAction: suspend () -> SubscriptionOperationResult<Unit> = {
        SubscriptionOperationResult.Ok(Unit)
    }
    internal var beforeLiveCommit: (() -> Unit)? = null
    internal var live = true
    internal var liveCommitCount = 0
        private set
    internal var unsubscribeCount = 0
        private set
    internal var requestedTimeshiftPeriod: Duration? = null
        private set

    internal val calls: List<Call>
        get() = synchronized(lock) { mutableCalls.toList() }

    internal val registeredIds: List<SubscriptionId>
        get() = synchronized(lock) { mutableRegisteredIds.toList() }

    internal val seekTargets: List<SubscriptionSeekTarget>
        get() = synchronized(lock) { mutableSeekTargets.toList() }

    override fun events(id: SubscriptionId): Flow<SubscriptionEvent> = flow {
        val stream = synchronized(lock) {
            check(consumed.add(id.value)) { "Subscription ID was reused" }
            Channel<SubscriptionEvent>(Channel.UNLIMITED).also { created ->
                streams[id.value] = created
                mutableRegisteredIds += id
                mutableCalls += Call.COLLECTION_REGISTERED
            }
        }
        try {
            for (event in stream) emit(event)
        } finally {
            synchronized(lock) { streams.remove(id.value, stream) }
        }
    }

    override suspend fun subscribe(
        id: SubscriptionId,
        channelId: SubscriptionChannelId,
        timeshiftPeriod: Duration,
    ): SubscriptionOperationResult<SubscriptionConfirmation> {
        synchronized(lock) {
            check(streams.containsKey(id.value)) { "Collector did not register before subscribe" }
            mutableCalls += Call.SUBSCRIBE
            requestedTimeshiftPeriod = timeshiftPeriod
        }
        return subscribeAction()
    }

    override suspend fun skip(
        id: SubscriptionId,
        target: SubscriptionSeekTarget,
    ): SubscriptionOperationResult<Unit> {
        synchronized(lock) {
            mutableCalls += Call.SKIP
            mutableSeekTargets += target
        }
        return skipAction()
    }

    override suspend fun unsubscribe(id: SubscriptionId): SubscriptionOperationResult<Unit> {
        synchronized(lock) {
            unsubscribeCount += 1
            mutableCalls += Call.UNSUBSCRIBE
        }
        val result = unsubscribeAction()
        if (result is SubscriptionOperationResult.Ok) {
            synchronized(lock) { streams[id.value] }?.close()
        }
        return result
    }

    override fun <T> commitIfLive(block: () -> T): T? {
        synchronized(lock) {
            liveCommitCount += 1
            mutableCalls += Call.LIVE_COMMIT
        }
        beforeLiveCommit?.invoke()
        return if (live) block() else null
    }

    internal suspend fun emit(event: SubscriptionEvent) {
        val targets = synchronized(lock) { streams.values.toList() }
        targets.forEach { it.send(event) }
    }

    internal suspend fun emit(id: SubscriptionId, event: SubscriptionEvent) {
        val target = synchronized(lock) { streams[id.value] }
        checkNotNull(target) { "Subscription stream is not active" }.send(event)
    }

    internal fun complete(id: SubscriptionId) {
        synchronized(lock) { streams[id.value] }?.close()
    }
}

internal enum class Call { COLLECTION_REGISTERED, SUBSCRIBE, SKIP, UNSUBSCRIBE, LIVE_COMMIT }

internal class CountingBinary(private val bytes: ByteArray) : SubscriptionBinary {
    internal var copyCount: Int = 0
        private set

    override val size: Int
        get() = bytes.size

    override fun copyInto(destination: ByteArray, destinationOffset: Int): Int {
        copyCount += 1
        val count = minOf(bytes.size, destination.size - destinationOffset)
        bytes.copyInto(destination, destinationOffset, 0, count)
        return count
    }
}

internal fun successfulConfirmation(
    timeshiftPeriodSeconds: Long? = null,
): SubscriptionOperationResult<SubscriptionConfirmation> =
    SubscriptionOperationResult.Ok(
        SubscriptionConfirmation(null, null, null, timeshiftPeriodSeconds),
    )

internal fun started(vararg streams: SubscriptionStream): SubscriptionEvent.Started =
    SubscriptionEvent.Started(
        streams = streams.toList(),
        codecMetadata = null,
        condition = SubscriptionCondition.NO_DETAIL,
    )

internal fun stream(
    index: Long = 0L,
    codecMetadata: SubscriptionBinary? = null,
    type: SubscriptionStreamType = SubscriptionStreamType.H264,
): SubscriptionStream = SubscriptionStream(
    index = StreamIndex(index),
    type = type,
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
    codecMetadata = codecMetadata,
)

internal fun packet(
    payload: SubscriptionBinary = CountingBinary(byteArrayOf(7)),
    presentationTimeUs: Long? = 2L,
    frameType: MuxFrameType = MuxFrameType.I,
    streamIndex: Long = 0L,
    decodingTimeUs: Long? = 1L,
    durationUs: Long = 3L,
): SubscriptionEvent.Packet = SubscriptionEvent.Packet(
    frameType = frameType,
    streamIndex = StreamIndex(streamIndex),
    decodingTimeUs = decodingTimeUs,
    presentationTimeUs = presentationTimeUs,
    durationUs = durationUs,
    payload = payload,
)

internal fun skipped(outcome: SkipOutcome): SubscriptionEvent.Skipped = SubscriptionEvent.Skipped(
    absolute = null,
    outcome = outcome,
    time = null,
    sizeBytes = null,
)

internal suspend fun caughtCancellation(block: suspend () -> Unit): CancellationException = try {
    block()
    error("Expected cancellation")
} catch (cancellation: CancellationException) {
    cancellation
}
