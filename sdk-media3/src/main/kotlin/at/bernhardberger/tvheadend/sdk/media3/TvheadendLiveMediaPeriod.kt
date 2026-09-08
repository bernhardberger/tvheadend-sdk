@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
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
import at.bernhardberger.tvheadend.sdk.playback.SkipOutcome
import at.bernhardberger.tvheadend.sdk.playback.StreamIndex
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTracks
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal class TvheadendLiveMediaPeriod(
    private val allocator: Allocator,
    private val timeshiftControls: LiveTimeshiftControlBridge.Attachment? = null,
    private val onUnsupportedStream: (SubscriptionStreamType) -> Unit,
    workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val callbackSchedulerFactory: () -> CoordinatorLooper = {
        HandlerCoordinatorLooper(checkNotNull(Looper.myLooper()))
    },
    private val onRelease: (TvheadendLiveMediaPeriod) -> Unit = {},
) : MediaPeriod, SubscriptionEventConsumer {
    private val lock = Any()
    private val rootJob = SupervisorJob()
    private val scope = CoroutineScope(rootJob + workerDispatcher)
    private val adapters = linkedMapOf<StreamIndex, ReaderBinding>()
    private val unsupportedStreams = mutableSetOf<StreamIndex>()
    private val excludedStreams = mutableSetOf<StreamIndex>()
    private val outputs = mutableListOf<QueueExtractorOutput>()
    private var callback: MediaPeriod.Callback? = null
    private var playbackHandler: CoordinatorLooper? = null
    private var alternativeFormatsWait: Job? = null
    private var alternativeFormatsExpired = false
    private var prepared = false
    private var preparationPosted = false
    private var subscriptionOpened = false
    private var tracksInitialized = false
    @Volatile private var released = false
    @Volatile private var interrupted = false
    private var sampleOriginUs: Long? = null
    private val preOriginEvents = ArrayDeque<SubscriptionEvent>()
    private var preOriginBytes = 0L
    @Volatile private var cleanEndOfStream = false
    @Volatile private var prepareError: IOException? = null
    private var trackGroups = TrackGroupArray.EMPTY
    private var seekDiscontinuityPending = false

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        synchronized(lock) {
            check(this.callback == null) { "Media period is already prepared" }
            this.callback = callback
            playbackHandler = callbackSchedulerFactory()
        }
        maybeFinishPreparation()
    }

    internal fun bind(subscription: ActiveSubscription) {
        synchronized(lock) {
            if (released || interrupted) return
            subscriptionOpened = true
            timeshiftControls?.bind(subscription)
            maybeFinishPreparation()
        }
    }

    internal fun terminal(subscription: ActiveSubscription?) {
        synchronized(lock) {
            if (!cleanEndOfStream) failPeriod()
            subscription?.let { timeshiftControls?.terminal(it) }
        }
    }

    internal fun interrupt() {
        synchronized(lock) {
            interrupted = true
            clearPreOriginEvents()
            alternativeFormatsWait?.cancel()
            timeshiftControls?.detach()
        }
    }

    override fun maybeThrowPrepareError() {
        prepareError?.let { throw it }
    }

    override fun getTrackGroups(): TrackGroupArray = synchronized(lock) { trackGroups }

    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long = synchronized(lock) {
        outputs.forEach { it.enabled = false }
        for (index in selections.indices) {
            val selection = selections[index]
            if (selection == null) {
                streams[index] = null
                continue
            }
            check(selection.length() == 1 && selection.getIndexInTrackGroup(0) == 0) {
                "Selected Media3 track index is unavailable"
            }
            // Media3 overrides can carry an equal group from an earlier period.
            val output = outputs.firstOrNull { it.trackGroup == selection.trackGroup }
                ?: error("Selected Media3 track is unavailable")
            output.enabled = true
            if (!mayRetainStreamFlags[index] || (streams[index] as? QueueSampleStream)?.queue !== output.queue) {
                output.queue.seekTo(positionUs, true)
                streams[index] = QueueSampleStream(output.queue, lock, { cleanEndOfStream }, ::currentError,
                    { interrupted || released || seekDiscontinuityPending })
                streamResetFlags[index] = true
            }
        }
        positionUs
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        synchronized(lock) {
            outputs.forEach { it.queue.discardTo(positionUs, toKeyframe, it.enabled) }
        }
    }

    override fun readDiscontinuity(): Long = synchronized(lock) {
        if (!seekDiscontinuityPending || interrupted || released || prepareError != null) return C.TIME_UNSET
        val selected = outputs.filter { it.enabled }
        val required = selected.filter { it.trackType == C.TRACK_TYPE_AUDIO || it.trackType == C.TRACK_TYPE_VIDEO }
            .ifEmpty { selected }
        if (required.isEmpty() || required.any { it.queue.firstTimestampUs == Long.MIN_VALUE }) return C.TIME_UNSET
        // SampleQueue reset requires a keyframe. Reader arrival alone cannot establish this point.
        val positionUs = required.minOf { it.queue.firstTimestampUs }
        seekDiscontinuityPending = false
        timeshiftControls?.playbackDiscontinuity()
        positionUs
    }

    override fun seekToUs(positionUs: Long): Long = positionUs

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long = positionUs

    override fun getBufferedPositionUs(): Long = synchronized(lock) {
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
        timestamps.min()
    }

    override fun getNextLoadPositionUs(): Long = getBufferedPositionUs()

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean = false

    override fun isLoading(): Boolean = !cleanEndOfStream && prepareError == null && !released && !interrupted

    override fun reevaluateBuffer(positionUs: Long): Unit = Unit

    override suspend fun accept(event: SubscriptionEvent) = acceptOrdered(event)

    internal fun acceptOrdered(event: SubscriptionEvent): Unit = synchronized(lock) {
        if (released || interrupted) return
        if (event is SubscriptionEvent.Stopped) {
            interrupt()
            return
        }
        val terminalEvent = event is SubscriptionEvent.Terminated
        try {
            try {
                if (!consumeEvent(event, terminalEvent)) return
            } finally {
                if (terminalEvent) timeshiftControls?.accept(event)
            }
            // Only an active period contributes mapping; packets must reach a supported reader.
            if (!terminalEvent) timeshiftControls?.accept(event)
        } catch (cancellation: CancellationException) {
            failPeriod()
            throw cancellation
        } catch (failure: Exception) {
            failPeriod()
            throw failure
        }
    }

    private fun consumeEvent(event: SubscriptionEvent, terminalEvent: Boolean): Boolean = synchronized(lock) {
        if (released || interrupted || prepareError != null || cleanEndOfStream) return false
        when (event) {
            is SubscriptionEvent.Packet -> {
                if (adapterFor(event.streamIndex) == null) return false
                check(event.payload.size <= MAX_PACKET_BYTES) { "Media3 packet size limit reached" }
                if (sampleOriginUs == null) {
                    val origin = event.presentationTimeUs?.takeUnless { it == C.TIME_UNSET || it == Long.MIN_VALUE }
                    if (origin == null) {
                        retainPreOriginEvent(event)
                        return false
                    }
                    sampleOriginUs = origin
                    outputs.forEach { it.queue.setSampleOffsetUs(Math.negateExact(origin)) }
                    timeshiftControls?.sampleOrigin(origin)
                    // Readers may need untimed parameter sets before the first timestamped access
                    // unit. Replay only after every queue has the same established sample offset.
                    while (preOriginEvents.isNotEmpty()) {
                        when (val retained = preOriginEvents.removeFirst()) {
                            is SubscriptionEvent.Packet -> {
                                preOriginBytes -= retained.payload.size
                                consumePacket(retained)
                            }
                            else -> currentAdapters().forEach { it.adapter.accept(retained) }
                        }
                    }
                }
                consumePacket(event)
            }
            is SubscriptionEvent.Skipped,
            is SubscriptionEvent.Dropped,
            is SubscriptionEvent.Stopped,
            is SubscriptionEvent.Terminated,
            -> {
                if (event is SubscriptionEvent.Skipped && event.outcome == SkipOutcome.ACCEPTED) {
                    outputs.forEach { it.queue.reset() }
                    clearPreOriginEvents()
                    seekDiscontinuityPending = true
                }
                if (terminalEvent) clearPreOriginEvents()
                if (sampleOriginUs == null && preOriginEvents.isNotEmpty()) {
                    // Keep discontinuity controls in their original position among retained bytes.
                    retainPreOriginEvent(event)
                } else {
                    currentAdapters().forEach { it.adapter.accept(event) }
                }
                if (terminalEvent) {
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
        // Readers have returned: omitted outputs can now be retired without resetting a
        // SampleQueue from inside its own upstream-format callback.
        maybeFinishPreparation()
        true
    }

    private fun adapterFor(index: StreamIndex): ReaderBinding? = synchronized(lock) {
        check(adapters.isNotEmpty()) { "Subscription packet arrived before validated tracks" }
        adapters[index] ?: if (index in unsupportedStreams || index in excludedStreams) {
            null
        } else {
            error("Subscription packet referenced an unavailable stream")
        }
    }

    /** Called under the period lock, including during pre-origin replay. */
    private fun consumePacket(packet: SubscriptionEvent.Packet) {
        val adapter = adapterFor(packet.streamIndex) ?: return
        check(allocator.totalBytesAllocated < MAX_ALLOCATED_BYTES) { "Media3 sample buffer limit reached" }
        check(adapter.output.queue.writeIndex - adapter.output.queue.readIndex < MAX_BUFFERED_SAMPLES) {
            "Media3 sample buffer limit reached"
        }
        adapter.adapter.accept(packet)
    }

    private fun retainPreOriginEvent(event: SubscriptionEvent) {
        val bytes = (event as? SubscriptionEvent.Packet)?.payload?.size?.toLong() ?: 0L
        check(preOriginEvents.size < MAX_PRE_ORIGIN_EVENTS && bytes <= MAX_PRE_ORIGIN_BYTES - preOriginBytes) {
            "Media3 timestamp prefix limit reached"
        }
        preOriginEvents.addLast(event)
        preOriginBytes += bytes
    }

    private fun clearPreOriginEvents() {
        preOriginEvents.clear()
        preOriginBytes = 0L
    }

    private fun currentAdapters(): List<ReaderBinding> = synchronized(lock) {
        check(adapters.isNotEmpty()) { "Subscription packet arrived before validated tracks" }
        adapters.values.toList()
    }

    override fun tracksReady(tracks: SubscriptionTracks) {
        synchronized(lock) {
            if (released || interrupted) return
            check(adapters.isEmpty()) { "Media3 tracks are already initialized" }
            var nextTrackId = 0
            tracks.streams.forEach { stream ->
                when (val result = createElementaryStreamReader(stream)) {
                    is ReaderResult.Supported -> {
                        val output = QueueExtractorOutput(allocator)
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
            timeshiftControls?.tracksReady(tracks)
        }
        maybeFinishPreparation()
    }

    internal fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            clearPreOriginEvents()
            alternativeFormatsWait?.cancel()
            outputs.forEach { it.queue.release() }
        }
        timeshiftControls?.detach()
        scope.cancel()
        onRelease(this)
    }

    private fun maybeFinishPreparation() {
        val completion = synchronized(lock) {
            if (
                released || interrupted || callback == null || prepared || preparationPosted || !subscriptionOpened || !tracksInitialized ||
                !hasRequiredFormats() || prepareError != null
            ) {
                null
            } else if (outputs.any { it.format == null } && !alternativeFormatsExpired) {
                // Healthy language/codec alternatives can initialize after the first A/V pair.
                // Bound discovery without adding delay once every supported format is known.
                if (alternativeFormatsWait == null) {
                    alternativeFormatsWait = scope.launch {
                        delay(1.seconds)
                        synchronized(lock) {
                            alternativeFormatsExpired = true
                            maybeFinishPreparation()
                        }
                    }
                }
                null
            } else {
                if (!alternativeFormatsExpired) alternativeFormatsWait?.cancel()
                alternativeFormatsWait = null
                preparationPosted = true
                val excluded = adapters.filterValues { it.output.format == null }
                excluded.forEach { (index, binding) ->
                    excludedStreams += index
                    adapters.remove(index)
                    outputs.remove(binding.output)
                    binding.output.queue.release()
                }
                outputs.forEach { it.freezeFormat() }
                trackGroups = TrackGroupArray(*outputs.map { checkNotNull(it.trackGroup) }.toTypedArray())
                callback
            }
        }
        completion?.let { periodCallback ->
            val posted = playbackHandler?.post {
                val deliver = synchronized(lock) {
                    if (released || interrupted || prepareError != null || !subscriptionOpened) {
                        preparationPosted = false
                        false
                    } else {
                        prepared = true
                        true
                    }
                }
                if (deliver) periodCallback.onPrepared(this)
            }
            if (posted != true) failPeriod()
        }
    }

    private fun hasRequiredFormats(): Boolean {
        if (outputs.none { it.format != null }) return false
        return listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO).all { type ->
            outputs.none { it.trackType == type } || outputs.any { it.trackType == type && it.format != null }
        }
    }

    private fun failPeriod() {
        synchronized(lock) {
            clearPreOriginEvents()
            if (!released && prepareError == null) prepareError = IOException("Live subscription preparation failed")
        }
    }

    private fun currentError(): IOException? = prepareError

    private class ReaderBinding(
        val adapter: SubscriptionElementaryStreamAdapter,
        val output: QueueExtractorOutput,
    )

    private companion object {
        const val MAX_BUFFERED_SAMPLES = 4_096
        const val MAX_PRE_ORIGIN_EVENTS = 256
        const val MAX_PRE_ORIGIN_BYTES = 4L * 1024L * 1024L
        const val MAX_PACKET_BYTES = 1024 * 1024
        const val MAX_ALLOCATED_BYTES = 64 * 1024 * 1024
    }
}

private class QueueExtractorOutput(
    allocator: Allocator,
) : ExtractorOutput {
    internal val queue: SampleQueue = SampleQueue.createWithoutDrm(allocator)
    internal var format: Format? = null
        private set
    internal var trackGroup: TrackGroup? = null
        private set
    internal var enabled: Boolean = false
    internal var trackType: Int = C.TRACK_TYPE_UNKNOWN
        private set
    private var formatFrozen = false

    init {
        queue.setUpstreamFormatChangeListener { newFormat ->
            if (!formatFrozen) {
                format = newFormat
                if (trackGroup == null) trackGroup = TrackGroup(newFormat)
            }
        }
    }

    internal fun freezeFormat() {
        formatFrozen = true
    }

    override fun track(id: Int, type: Int): TrackOutput {
        trackType = type
        return queue
    }
    override fun endTracks(): Unit = Unit
    override fun seekMap(seekMap: SeekMap): Unit = Unit
}

private class QueueSampleStream(
    val queue: SampleQueue,
    private val lock: Any,
    private val loadingFinished: () -> Boolean,
    private val sourceError: () -> IOException?,
    private val invalidated: () -> Boolean,
) : SampleStream {
    override fun isReady(): Boolean = synchronized(lock) { !invalidated() && queue.isReady(loadingFinished()) }
    override fun maybeThrowError(): Unit = synchronized(lock) {
        sourceError()?.let { throw it }
        queue.maybeThrowError()
    }
    override fun readData(holder: FormatHolder, buffer: androidx.media3.decoder.DecoderInputBuffer, readFlags: Int): Int =
        synchronized(lock) {
            if (invalidated()) C.RESULT_NOTHING_READ else queue.read(holder, buffer, readFlags, loadingFinished())
        }

    override fun skipData(positionUs: Long): Int =
        synchronized(lock) {
            if (invalidated()) 0 else queue.getSkipCount(positionUs, loadingFinished()).also(queue::skip)
        }
}
