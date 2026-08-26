@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.playback.SubscriptionBinary
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionConfirmation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionConnection
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration

/** Safe call-order observation emitted by [ScriptedSubscriptionConnection]. */
@SubscriptionInfrastructureApi
public enum class ScriptedSubscriptionCall {
    COLLECTION_REGISTERED,
    SUBSCRIBE,
    SKIP,
    UNSUBSCRIBE,
    LIVE_COMMIT,
}

/** Deterministic generation-bound subscription transport for JVM SDK tests. */
@SubscriptionInfrastructureApi
public class ScriptedSubscriptionConnection : SubscriptionConnection {
    private val lock = Any()
    private val ownerToken = Any()
    private val streams = LinkedHashMap<Long, ActiveScriptedStream>()
    private val consumedIds = LinkedHashSet<Long>()
    private val registrations = Channel<ScriptedSubscriptionRegistration>(Channel.UNLIMITED)
    private val mutableCalls = ArrayList<ScriptedSubscriptionCall>()
    private var subscribeResult: SubscriptionOperationResult<SubscriptionConfirmation> =
        SubscriptionOperationResult.Ok(SubscriptionConfirmation(null, null, null, null))
    private var skipResult: SubscriptionOperationResult<Unit> =
        SubscriptionOperationResult.Ok(Unit)
    private var unsubscribeResult: SubscriptionOperationResult<Unit> =
        SubscriptionOperationResult.Ok(Unit)
    private var live = true
    private val mutableSeekTargets = ArrayList<SubscriptionSeekTarget>()

    /** Snapshot of value-free invocation order. */
    public val calls: List<ScriptedSubscriptionCall>
        get() = synchronized(lock) { mutableCalls.toImmutableList() }

    /** Number of subscribe calls. */
    public val subscribeCount: Int
        get() = synchronized(lock) { mutableCalls.count { it == ScriptedSubscriptionCall.SUBSCRIBE } }

    /** Number of unsubscribe calls. */
    public val unsubscribeCount: Int
        get() = synchronized(lock) { mutableCalls.count { it == ScriptedSubscriptionCall.UNSUBSCRIBE } }

    /** Ordered timeshift positioning requests issued through this connection. */
    public val seekTargets: List<SubscriptionSeekTarget>
        get() = synchronized(lock) { mutableSeekTargets.toImmutableList() }

    /** Requested timeshift period in whole seconds, or null before subscribe. */
    public var requestedTimeshiftSeconds: Long? = null
        get() = synchronized(lock) { field }
        private set

    /** Requested canonical stream-profile UUID, or null for the server default. */
    public var requestedStreamProfileUuid: String? = null
        get() = synchronized(lock) { field }
        private set

    /** Scripts the next and subsequent subscribe result. */
    public fun scriptSubscribe(result: SubscriptionOperationResult<SubscriptionConfirmation>) {
        synchronized(lock) { subscribeResult = result }
    }

    /** Scripts the next and subsequent skip result. */
    public fun scriptSkip(result: SubscriptionOperationResult<Unit>) {
        synchronized(lock) { skipResult = result }
    }

    /** Scripts the next and subsequent unsubscribe result. */
    public fun scriptUnsubscribe(result: SubscriptionOperationResult<Unit>) {
        synchronized(lock) { unsubscribeResult = result }
    }

    /** Controls generation liveness for atomic playable publication. */
    public fun setLive(value: Boolean) {
        synchronized(lock) { live = value }
    }

    /** Marks this generation lost, delivers its ordered terminal, and completes every stream. */
    public suspend fun loseGeneration() {
        currentCoroutineContext().ensureActive()
        val targets = synchronized(lock) {
            live = false
            streams.values.map(ActiveScriptedStream::events)
        }
        targets.forEach { stream ->
            stream.send(
                SubscriptionEvent.Terminated(
                    at.bernhardberger.tvheadend.sdk.playback.SubscriptionTermination.GENERATION_LOST,
                ),
            )
            stream.close()
        }
    }

    /** Suspends until one event collector has registered. */
    public suspend fun awaitCollectionRegistered(): ScriptedSubscriptionRegistration =
        registrations.receive()

    /** Emits [event] to one opaque registered stream. */
    public suspend fun emit(
        registration: ScriptedSubscriptionRegistration,
        event: SubscriptionEvent,
    ) {
        val target = synchronized(lock) {
            check(registration.belongsTo(ownerToken)) { "Subscription registration belongs to another connection" }
            streams.values.singleOrNull { active -> active.registration === registration }?.events
        }
        checkNotNull(target) { "Subscription stream is not active" }.send(event)
    }

    /** Emits [event] to every currently registered stream in registration order. */
    public suspend fun emit(event: SubscriptionEvent) {
        val targets = synchronized(lock) { streams.values.map(ActiveScriptedStream::events) }
        targets.forEach { stream -> stream.send(event) }
    }

    /** Completes every currently registered stream. */
    public fun completeStreams() {
        val targets = synchronized(lock) { streams.values.map(ActiveScriptedStream::events) }
        targets.forEach { stream -> stream.close() }
    }

    override fun events(id: SubscriptionId): Flow<SubscriptionEvent> = flow {
        currentCoroutineContext().ensureActive()
        val active = synchronized(lock) {
            check(consumedIds.add(id.value)) { "Subscription stream was already consumed" }
            ActiveScriptedStream(
                registration = ScriptedSubscriptionRegistration(ownerToken),
                events = Channel(Channel.UNLIMITED),
            ).also { created ->
                streams[id.value] = created
                mutableCalls += ScriptedSubscriptionCall.COLLECTION_REGISTERED
            }
        }
        try {
            registrations.send(active.registration)
            for (event in active.events) emit(event)
        } finally {
            synchronized(lock) { streams.remove(id.value, active) }
        }
    }

    override suspend fun subscribe(
        id: SubscriptionId,
        channelId: SubscriptionChannelId,
        timeshiftPeriod: Duration,
    ): SubscriptionOperationResult<SubscriptionConfirmation> = recordSubscribe(
        id = id,
        streamProfileUuid = null,
        timeshiftPeriod = timeshiftPeriod,
    )

    override suspend fun subscribe(
        id: SubscriptionId,
        channelId: SubscriptionChannelId,
        options: SubscriptionOptions,
    ): SubscriptionOperationResult<SubscriptionConfirmation> = recordSubscribe(
        id = id,
        streamProfileUuid = options.streamProfileUuid,
        timeshiftPeriod = options.timeshiftPeriod,
    )

    private suspend fun recordSubscribe(
        id: SubscriptionId,
        streamProfileUuid: String?,
        timeshiftPeriod: Duration,
    ): SubscriptionOperationResult<SubscriptionConfirmation> {
        currentCoroutineContext().ensureActive()
        return synchronized(lock) {
            check(streams.containsKey(id.value)) { "Collector must register before subscribe" }
            mutableCalls += ScriptedSubscriptionCall.SUBSCRIBE
            requestedStreamProfileUuid = streamProfileUuid
            requestedTimeshiftSeconds = timeshiftPeriod.inWholeSeconds
            subscribeResult
        }
    }

    override suspend fun skip(
        id: SubscriptionId,
        target: SubscriptionSeekTarget,
    ): SubscriptionOperationResult<Unit> {
        currentCoroutineContext().ensureActive()
        return synchronized(lock) {
            check(streams.containsKey(id.value)) { "Subscription stream is not active" }
            mutableCalls += ScriptedSubscriptionCall.SKIP
            mutableSeekTargets += target
            skipResult
        }
    }

    override suspend fun unsubscribe(id: SubscriptionId): SubscriptionOperationResult<Unit> {
        currentCoroutineContext().ensureActive()
        val result = synchronized(lock) {
            mutableCalls += ScriptedSubscriptionCall.UNSUBSCRIBE
            unsubscribeResult
        }
        if (result is SubscriptionOperationResult.Ok) {
            synchronized(lock) { streams[id.value]?.events }?.close()
        }
        return result
    }

    override fun <T> commitIfLive(block: () -> T): T? = synchronized(lock) {
        mutableCalls += ScriptedSubscriptionCall.LIVE_COMMIT
        if (live) block() else null
    }

    override fun toString(): String = "ScriptedSubscriptionConnection(<redacted>)"
}

/** Opaque redacted handle for one scripted stream registration. */
@SubscriptionInfrastructureApi
public class ScriptedSubscriptionRegistration internal constructor(private val ownerToken: Any) {
    internal fun belongsTo(ownerToken: Any): Boolean = this.ownerToken === ownerToken

    override fun toString(): String = "ScriptedSubscriptionRegistration(<redacted>)"
}

private class ActiveScriptedStream(
    internal val registration: ScriptedSubscriptionRegistration,
    internal val events: Channel<SubscriptionEvent>,
)

/** Defensive bounded-copy binary fixture with observable copy count. */
@SubscriptionInfrastructureApi
public class SubscriptionBinaryFixture(bytes: ByteArray) : SubscriptionBinary {
    private val bytes = bytes.copyOf()
    private val copies = AtomicInteger()

    override val size: Int
        get() = bytes.size

    /** Number of explicit [copyInto] calls. */
    public val copyCount: Int
        get() = copies.get()

    override fun copyInto(destination: ByteArray, destinationOffset: Int): Int {
        require(destinationOffset in 0..destination.size) { "Destination offset is out of bounds" }
        val count = minOf(bytes.size, destination.size - destinationOffset)
        bytes.copyInto(destination, destinationOffset, 0, count)
        copies.incrementAndGet()
        return count
    }

    override fun toString(): String = "SubscriptionBinaryFixture(<redacted>)"
}

private fun <T> List<T>.toImmutableList(): List<T> =
    java.util.Collections.unmodifiableList(ArrayList(this))
