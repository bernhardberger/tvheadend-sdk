@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleQueue
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput
import at.bernhardberger.tvheadend.sdk.playback.ActiveSubscription
import at.bernhardberger.tvheadend.sdk.playback.StreamIndex
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionState
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTracks
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal class TvheadendLiveMediaPeriod(
    private val target: CoordinatorLiveTarget,
    private val options: SubscriptionOptions,
    private val allocator: Allocator,
    private val timeshiftControls: LiveTimeshiftControlBridge.Attachment? = null,
    private val onUnsupportedStream: (SubscriptionStreamType) -> Unit,
) : MediaPeriod, SubscriptionEventConsumer {
    private val lock = Any()
    private val rootJob = SupervisorJob()
    private val scope = CoroutineScope(rootJob + Dispatchers.IO)
    private val adapters = linkedMapOf<StreamIndex, ReaderBinding>()
    private val unsupportedStreams = mutableSetOf<StreamIndex>()
    private val outputs = mutableListOf<QueueExtractorOutput>()
    private var callback: MediaPeriod.Callback? = null
    private var playbackHandler: Handler? = null
    private var activeSubscription: ActiveSubscription? = null
    private var openJob: Job? = null
    private var prepared = false
    private var preparationPosted = false
    private var subscriptionOpened = false
    private var tracksInitialized = false
    @Volatile private var released = false
    @Volatile private var cleanEndOfStream = false
    @Volatile private var prepareError: IOException? = null
    private var trackGroups = TrackGroupArray.EMPTY

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        synchronized(lock) {
            check(this.callback == null) { "Media period is already prepared" }
            this.callback = callback
            playbackHandler = Handler(checkNotNull(Looper.myLooper()))
        }
        openJob = scope.launch {
            try {
                when (
                    val result = openSubscription()
                ) {
                    is SubscriptionOpenResult.Opened -> {
                        synchronized(lock) {
                            activeSubscription = result.subscription
                            subscriptionOpened = true
                        }
                        timeshiftControls?.bind(result.subscription)
                        scope.launch {
                            result.subscription.state.first { state ->
                                state is SubscriptionState.Terminal
                            }
                            timeshiftControls?.terminal(result.subscription)
                        }
                        maybeFinishPreparation()
                    }
                    SubscriptionOpenResult.NotReady,
                    SubscriptionOpenResult.IdExhausted,
                    SubscriptionOpenResult.ProfileUnavailable,
                    is SubscriptionOpenResult.Failed,
                    -> failPeriod()
                }
            } catch (cancellation: CancellationException) {
                if (!released) failPeriod()
                throw cancellation
            } catch (_: Exception) {
                failPeriod()
            }
        }
    }

    internal suspend fun openSubscription(): SubscriptionOpenResult = target.open(
        this,
        options,
    )

    override fun maybeThrowPrepareError() {
        prepareError?.let { throw it }
    }

    override fun getTrackGroups(): TrackGroupArray = trackGroups

    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        outputs.forEach { it.enabled = false }
        for (index in selections.indices) {
            val selection = selections[index]
            if (selection == null) {
                streams[index] = null
                continue
            }
            val output = outputs.firstOrNull { it.trackGroup === selection.trackGroup }
                ?: error("Selected Media3 track is unavailable")
            output.enabled = true
            if (streams[index] == null || !mayRetainStreamFlags[index]) {
                output.queue.seekTo(positionUs, true)
                streams[index] = QueueSampleStream(output.queue, { cleanEndOfStream }, ::currentError)
                streamResetFlags[index] = true
            }
        }
        return positionUs
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        outputs.forEach { it.queue.discardTo(positionUs, toKeyframe, it.enabled) }
    }

    override fun readDiscontinuity(): Long = C.TIME_UNSET

    override fun seekToUs(positionUs: Long): Long = positionUs

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long = positionUs

    override fun getBufferedPositionUs(): Long {
        if (cleanEndOfStream) return C.TIME_END_OF_SOURCE
        val selected = outputs.filter { it.enabled }.ifEmpty { outputs }
        val audioVideo = selected.filter { output ->
            val type = MimeTypes.getTrackType(output.format?.sampleMimeType)
            type == C.TRACK_TYPE_AUDIO || type == C.TRACK_TYPE_VIDEO
        }
        val required = audioVideo.ifEmpty { selected }
        if (required.isEmpty()) return 0L
        val timestamps = required.map { it.queue.largestQueuedTimestampUs }
        if (timestamps.any { it == Long.MIN_VALUE }) return 0L
        return timestamps.min()
    }

    override fun getNextLoadPositionUs(): Long = getBufferedPositionUs()

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean = false

    override fun isLoading(): Boolean = !cleanEndOfStream && prepareError == null && !released

    override fun reevaluateBuffer(positionUs: Long): Unit = Unit

    override suspend fun accept(event: SubscriptionEvent) {
        try {
            when (event) {
                is SubscriptionEvent.Packet -> {
                    val adapter = adapterFor(event.streamIndex) ?: return
                    check(event.payload.size <= MAX_PACKET_BYTES) { "Media3 packet size limit reached" }
                    check(allocator.totalBytesAllocated < MAX_ALLOCATED_BYTES) {
                        "Media3 sample buffer limit reached"
                    }
                    check(adapter.output.queue.writeIndex - adapter.output.queue.readIndex < MAX_BUFFERED_SAMPLES) {
                        "Media3 sample buffer limit reached"
                    }
                    adapter.adapter.accept(event)
                }
                is SubscriptionEvent.Skipped,
                is SubscriptionEvent.Dropped,
                is SubscriptionEvent.Stopped,
                is SubscriptionEvent.Terminated,
                -> {
                    currentAdapters().forEach { it.adapter.accept(event) }
                    if (event is SubscriptionEvent.Stopped || event is SubscriptionEvent.Terminated) {
                        cleanEndOfStream = true
                        if (!prepared) failPeriod()
                    }
                }
                is SubscriptionEvent.Started,
                is SubscriptionEvent.Status,
                is SubscriptionEvent.Grace,
                is SubscriptionEvent.Speed,
                is SubscriptionEvent.Timeshift,
                is SubscriptionEvent.Queue,
                is SubscriptionEvent.Signal,
                is SubscriptionEvent.Descramble,
                -> Unit
            }
            timeshiftControls?.accept(event)
        } catch (cancellation: CancellationException) {
            failPeriod()
            throw cancellation
        } catch (failure: Exception) {
            failPeriod()
            throw failure
        }
    }

    private fun adapterFor(index: StreamIndex): ReaderBinding? = synchronized(lock) {
        check(adapters.isNotEmpty()) { "Subscription packet arrived before validated tracks" }
        adapters[index] ?: if (index in unsupportedStreams) {
            null
        } else {
            error("Subscription packet referenced an unavailable stream")
        }
    }

    private fun currentAdapters(): List<ReaderBinding> = synchronized(lock) {
        check(adapters.isNotEmpty()) { "Subscription packet arrived before validated tracks" }
        adapters.values.toList()
    }

    override fun tracksReady(tracks: SubscriptionTracks) {
        synchronized(lock) {
            check(adapters.isEmpty()) { "Media3 tracks are already initialized" }
            var nextTrackId = 0
            tracks.streams.forEach { stream ->
                when (val result = createElementaryStreamReader(stream)) {
                    is ReaderResult.Supported -> {
                        val output = QueueExtractorOutput(allocator, ::maybeFinishPreparation)
                        val subtitleOutput = if (stream.type == SubscriptionStreamType.DVB_SUBTITLE) {
                            SubtitleTranscodingExtractorOutput(output, DefaultSubtitleParserFactory())
                        } else {
                            null
                        }
                        outputs += output
                        adapters[stream.index] = ReaderBinding(
                            SubscriptionElementaryStreamAdapter(
                                reader = result.reader,
                                output = subtitleOutput ?: output,
                                firstTrackId = nextTrackId,
                                onDiscontinuity = subtitleOutput?.let { it::resetSubtitleParsers },
                            ),
                            output,
                        )
                        nextTrackId += 1
                    }
                    ReaderResult.Unsupported -> {
                        unsupportedStreams += stream.index
                        onUnsupportedStream(stream.type)
                    }
                }
            }
            check(adapters.isNotEmpty()) { "Subscription contains no supported Media3 streams" }
            tracksInitialized = true
        }
        maybeFinishPreparation()
    }

    internal fun release() {
        val job = synchronized(lock) {
            if (released) return
            released = true
            openJob
        }
        timeshiftControls?.detach()
        job?.cancel()
        scope.launch {
            try {
                withContext(NonCancellable) {
                    job?.join()
                    activeSubscription?.close()
                }
            } finally {
                val releaseQueues = Runnable {
                    outputs.forEach { it.queue.release() }
                    scope.cancel()
                }
                if (playbackHandler?.post(releaseQueues) != true) {
                    releaseQueues.run()
                }
            }
        }
    }

    private fun maybeFinishPreparation() {
        val completion = synchronized(lock) {
            if (
                released || prepared || preparationPosted || !subscriptionOpened || !tracksInitialized ||
                outputs.isEmpty() || outputs.any { it.format == null } || prepareError != null
            ) {
                null
            } else {
                preparationPosted = true
                trackGroups = TrackGroupArray(*outputs.map { checkNotNull(it.trackGroup) }.toTypedArray())
                callback
            }
        }
        completion?.let { periodCallback ->
            playbackHandler?.post {
                val deliver = synchronized(lock) {
                    if (released || prepareError != null || !subscriptionOpened) {
                        preparationPosted = false
                        false
                    } else {
                        prepared = true
                        true
                    }
                }
                if (deliver) periodCallback.onPrepared(this)
            }
        }
    }

    private fun failPeriod() {
        synchronized(lock) {
            if (prepareError == null) prepareError = IOException("Live subscription preparation failed")
        }
    }

    private fun currentError(): IOException? = prepareError

    private class ReaderBinding(
        val adapter: SubscriptionElementaryStreamAdapter,
        val output: QueueExtractorOutput,
    )

    private companion object {
        const val MAX_BUFFERED_SAMPLES = 4_096
        const val MAX_PACKET_BYTES = 1024 * 1024
        const val MAX_ALLOCATED_BYTES = 64 * 1024 * 1024
    }
}

private class QueueExtractorOutput(
    allocator: Allocator,
    onFormat: () -> Unit,
) : ExtractorOutput {
    internal val queue: SampleQueue = SampleQueue.createWithoutDrm(allocator)
    internal var format: Format? = null
        private set
    internal var trackGroup: TrackGroup? = null
        private set
    internal var enabled: Boolean = false

    init {
        queue.setUpstreamFormatChangeListener { newFormat ->
            format = newFormat
            if (trackGroup == null) trackGroup = TrackGroup(newFormat)
            onFormat()
        }
    }

    override fun track(id: Int, type: Int): TrackOutput = queue
    override fun endTracks(): Unit = Unit
    override fun seekMap(seekMap: SeekMap): Unit = Unit
}

private class QueueSampleStream(
    private val queue: SampleQueue,
    private val loadingFinished: () -> Boolean,
    private val sourceError: () -> IOException?,
) : SampleStream {
    override fun isReady(): Boolean = queue.isReady(loadingFinished())
    override fun maybeThrowError() {
        sourceError()?.let { throw it }
        queue.maybeThrowError()
    }
    override fun readData(holder: FormatHolder, buffer: androidx.media3.decoder.DecoderInputBuffer, readFlags: Int): Int =
        queue.read(holder, buffer, readFlags, loadingFinished())

    override fun skipData(positionUs: Long): Int = queue.getSkipCount(positionUs, loadingFinished()).also(queue::skip)
}
