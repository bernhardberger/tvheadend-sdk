@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import android.os.Looper
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.upstream.Allocator
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.ActiveSubscription
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTracks
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionState
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun createTvheadendLiveMediaSource(
    target: CoordinatorLiveTarget,
    options: SubscriptionOptions = SubscriptionOptions(),
    timeshiftControls: LiveTimeshiftControlBridge? = null,
    onUnsupportedStream: (SubscriptionStreamType) -> Unit = {},
): MediaSource = TvheadendLiveMediaSource(
    target,
    options,
    timeshiftControls,
    onUnsupportedStream,
)

internal class TvheadendLiveMediaSource(
    private val target: CoordinatorLiveTarget,
    private val options: SubscriptionOptions,
    private val timeshiftControls: LiveTimeshiftControlBridge?,
    private val onUnsupportedStream: (SubscriptionStreamType) -> Unit,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val callbackSchedulerFactory: () -> CoordinatorLooper = {
        HandlerCoordinatorLooper(checkNotNull(Looper.myLooper()))
    },
) : BaseMediaSource() {
    private val lock = Any()
    private var scope = CoroutineScope(SupervisorJob() + workerDispatcher)
    private var preparation = Any()
    private var handler: CoordinatorLooper? = null
    private var subscription: ActiveSubscription? = null
    private var opening: Job? = null
    private var released = false
    private var error: IOException? = null
    private var epoch = Any()
    private var started = false
    private var stopped = false
    private var refreshPosted = false
    private var drainPosted = false
    private var period: TvheadendLiveMediaPeriod? = null
    private var tracks: SubscriptionTracks? = null
    private var tracksDelivered = false
    private val pending = ArrayDeque<SubscriptionEvent>()
    private var pendingBytes = 0L
    private val mediaItem = MediaItem.Builder()
        .setMediaId("tvheadend-live")
        .build()

    override fun getMediaItem(): MediaItem = mediaItem

    override fun getInitialTimeline(): Timeline = synchronized(lock) { restartTimeline(mediaItem, epoch) }

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        val owner = synchronized(lock) {
            if (released) {
                // Media3 may prepare the same source again during an application rollback.
                // A retired owner's joins/posts must never mutate this new preparation.
                preparation = Any()
                scope = CoroutineScope(SupervisorJob() + workerDispatcher)
                released = false
                error = null
                epoch = Any()
                started = false
                stopped = false
                tracks = null
                tracksDelivered = false
                period = null
                refreshPosted = false
                drainPosted = false
            }
            handler = callbackSchedulerFactory()
            preparation
        }
        refreshSourceInfo(restartTimeline(mediaItem, epoch))
        val consumer = object : SubscriptionEventConsumer {
            override suspend fun accept(event: SubscriptionEvent) { acceptOrdered(owner, event) }
            override fun tracksReady(tracks: SubscriptionTracks) { acceptTracks(owner, tracks) }
        }
        opening = scope.launch {
            try {
                when (val result = target.open(consumer, options)) {
                    is SubscriptionOpenResult.Opened -> {
                        val closeLate = synchronized(lock) {
                            if (released || preparation !== owner) true else {
                                subscription = result.subscription
                                period?.bind(result.subscription)
                                false
                            }
                        }
                        if (closeLate) {
                            withContext(NonCancellable) { result.subscription.close() }
                        } else {
                            result.subscription.state.first { it is SubscriptionState.Terminal }
                            synchronized(lock) {
                                if (!released && preparation === owner) {
                                    if (period == null) failLocked() else period?.terminal(result.subscription)
                                }
                            }
                        }
                    }
                    else -> synchronized(lock) { if (preparation === owner) failLocked() }
                }
            } catch (cancellation: CancellationException) {
                synchronized(lock) { if (preparation === owner) failLocked() }
                throw cancellation
            } catch (_: Exception) {
                synchronized(lock) { if (preparation === owner) failLocked() }
            }
        }
    }

    override fun maybeThrowSourceInfoRefreshError() {
        synchronized(lock) { error }?.let { throw it }
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod = synchronized(lock) {
        val attachment = timeshiftControls?.newAttachment()?.also { it.periodUid = id.periodUid }
        val created = TvheadendLiveMediaPeriod(
            allocator = allocator,
            timeshiftControls = attachment,
            onUnsupportedStream = onUnsupportedStream,
            workerDispatcher = workerDispatcher,
            callbackSchedulerFactory = callbackSchedulerFactory,
            onRelease = { old -> synchronized(lock) { if (period === old) period = null } },
        )
        if (released || id.periodUid !== epoch) {
            created.interrupt()
        } else {
            period?.interrupt()
            period = created
            tracksDelivered = false
            subscription?.let(created::bind)
            if (error != null) created.terminal(subscription)
            postDrainLocked()
        }
        created
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        (mediaPeriod as TvheadendLiveMediaPeriod).release()
    }

    override fun releaseSourceInternal() {
        val (job, handle, ownerScope) = synchronized(lock) {
            if (released) return
            released = true
            pending.clear()
            pendingBytes = 0
            period?.interrupt()
            Triple(opening, subscription.also { subscription = null }, scope)
        }
        job?.cancel()
        ownerScope.launch {
            try {
                withContext(NonCancellable) {
                    job?.join()
                    handle?.close()
                }
            } finally {
                ownerScope.cancel()
            }
        }
    }

    private fun acceptOrdered(owner: Any, event: SubscriptionEvent) {
        synchronized(lock) {
            if (released || error != null || preparation !== owner) return
            when (event) {
                is SubscriptionEvent.Stopped -> {
                    if (!stopped) replaceEpochLocked()
                    stopped = true
                    started = false
                    return
                }
                is SubscriptionEvent.Started -> {
                    if (started) replaceEpochLocked()
                    started = true
                    stopped = false
                }
                is SubscriptionEvent.Packet, is SubscriptionEvent.Dropped -> if (!started) return
                is SubscriptionEvent.Terminated -> Unit
                // Interruption observations have no successor segment provenance. Only Started
                // can reopen observation ingress; terminal events retain their ordered route.
                else -> if (stopped) return
            }
            enqueueLocked(event)
            drainLocked()
        }
    }

    private fun acceptTracks(owner: Any, tracks: SubscriptionTracks) {
        synchronized(lock) {
            if (released || error != null || preparation !== owner) return
            this.tracks = tracks
            drainLocked()
        }
    }

    private fun enqueueLocked(event: SubscriptionEvent) {
        val bytes = (event as? SubscriptionEvent.Packet)?.payload?.size?.toLong() ?: 0L
        check(pending.size < MAX_PENDING_EVENTS && bytes <= MAX_PENDING_BYTES - pendingBytes) {
            "Live restart handoff capacity exceeded"
        }
        pending.addLast(event)
        pendingBytes += bytes
    }

    private fun drainLocked() {
        val current = period ?: return
        if (!tracksDelivered) {
            val validated = tracks ?: return
            current.tracksReady(validated)
            tracksDelivered = true
        }
        while (pending.isNotEmpty()) {
            val event = pending.removeFirst()
            pendingBytes -= (event as? SubscriptionEvent.Packet)?.payload?.size?.toLong() ?: 0L
            current.acceptOrdered(event)
        }
    }

    private fun postDrainLocked() {
        if (drainPosted) return
        drainPosted = true
        val owner = preparation
        scope.launch {
            synchronized(lock) {
                if (preparation !== owner) return@synchronized
                drainPosted = false
                if (!released && error == null) {
                    try { drainLocked() } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) { failLocked() }
                }
            }
        }
    }

    private fun replaceEpochLocked() {
        period?.interrupt()
        period = null
        tracks = null
        tracksDelivered = false
        pending.clear()
        pendingBytes = 0
        epoch = Any()
        if (refreshPosted) return
        refreshPosted = true
        val owner = preparation
        if (handler?.post {
            synchronized(lock) {
                if (preparation !== owner) return@synchronized
                refreshPosted = false
                if (!released) refreshSourceInfo(restartTimeline(mediaItem, epoch))
            }
        } != true) failLocked()
    }

    private fun failLocked() {
        if (released) return
        error = IOException("Live subscription failed")
        period?.terminal(subscription)
    }

    private companion object {
        const val MAX_PENDING_EVENTS = 2_048
        const val MAX_PENDING_BYTES = 16L * 1024L * 1024L
    }
}

/** Media3 resolves a disappearing period against this surviving window at its fixed default. */
internal fun restartTimeline(mediaItem: MediaItem, epoch: Any): Timeline = object : ForwardingTimeline(
    SinglePeriodTimeline(
        C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET,
        0L, 0L, false, true, true, null, mediaItem, MediaItem.LiveConfiguration.Builder().build(),
    ),
) {
    override fun getPeriod(periodIndex: Int, period: Timeline.Period, setIds: Boolean): Timeline.Period =
        super.getPeriod(periodIndex, period, setIds).also { if (setIds) it.uid = epoch }

    override fun getUidOfPeriod(periodIndex: Int): Any {
        check(periodIndex == 0) { "Unavailable live period" }
        return epoch
    }

    override fun getIndexOfPeriod(uid: Any): Int = if (uid === epoch) 0 else C.INDEX_UNSET
}
