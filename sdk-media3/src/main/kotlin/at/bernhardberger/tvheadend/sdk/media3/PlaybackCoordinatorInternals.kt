@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.os.Handler
import android.os.Looper
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackExit
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrProgressResult
import at.bernhardberger.tvheadend.sdk.core.PlaybackBinding
import at.bernhardberger.tvheadend.sdk.core.RecordingPlaybackAdmission
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.RecordingFile
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileOpener
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

internal interface CoordinatorLiveTarget {
    val isCurrent: Boolean

    suspend fun open(
        consumer: SubscriptionEventConsumer,
        options: SubscriptionOptions,
    ): SubscriptionOpenResult
}

internal class BoundCoordinatorLiveTarget(
    private val binding: PlaybackBinding.Live,
) : CoordinatorLiveTarget {
    override val isCurrent: Boolean
        get() = binding.isCurrent

    override suspend fun open(
        consumer: SubscriptionEventConsumer,
        options: SubscriptionOptions,
    ): SubscriptionOpenResult = binding.open(consumer, options)

    override fun toString(): String = "CoordinatorLiveTarget(<redacted>)"
}

internal interface CoordinatorRecordingTarget : RecordingFileOpener {
    val startedGrowing: Boolean

    val admission: CoordinatorRecordingAdmission

    fun bindGrowingRecording(): RecordingFileResult<GrowingRecordingFileLease>

    suspend fun reportProgress(
        growingLease: GrowingRecordingFileLease?,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult
}

internal class BoundCoordinatorRecordingTarget(
    private val binding: PlaybackBinding.Recording,
) : CoordinatorRecordingTarget {
    override val startedGrowing: Boolean
        get() = binding.startedGrowing

    override val admission: CoordinatorRecordingAdmission
        get() = binding.admission.toCoordinatorAdmission()

    override suspend fun openRecording(): RecordingFileResult<RecordingFile> =
        binding.openRecording()

    override fun bindGrowingRecording(): RecordingFileResult<GrowingRecordingFileLease> =
        binding.bindGrowingRecording()

    override suspend fun reportProgress(
        growingLease: GrowingRecordingFileLease?,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult = binding.reportProgress(growingLease, progress)

    override fun toString(): String = "CoordinatorRecordingTarget(<redacted>)"
}

internal sealed interface CoordinatorRecordingAdmission {
    data class Completed(
        val resumePosition: Duration?,
        val progressCapability: at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability,
    ) : CoordinatorRecordingAdmission

    data class GrowingStartOverOnly(
        val progressCapability: at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability,
    ) : CoordinatorRecordingAdmission

    data object GrowingDeferred : CoordinatorRecordingAdmission

    data object TargetUnavailable : CoordinatorRecordingAdmission

    data object ObservationExpired : CoordinatorRecordingAdmission
}

private fun RecordingPlaybackAdmission.toCoordinatorAdmission(): CoordinatorRecordingAdmission =
    when (this) {
        is RecordingPlaybackAdmission.Completed -> CoordinatorRecordingAdmission.Completed(
            resumePosition,
            progressCapability,
        )
        is RecordingPlaybackAdmission.GrowingStartOverOnly ->
            CoordinatorRecordingAdmission.GrowingStartOverOnly(progressCapability)
        RecordingPlaybackAdmission.GrowingDeferred -> CoordinatorRecordingAdmission.GrowingDeferred
        RecordingPlaybackAdmission.TargetUnavailable ->
            CoordinatorRecordingAdmission.TargetUnavailable
        RecordingPlaybackAdmission.ObservationExpired ->
            CoordinatorRecordingAdmission.ObservationExpired
    }

internal class PlayerOperationTicket {
    private val state = AtomicReference(OperationState.QUEUED)
    private val cancellationAction = AtomicReference<(() -> Unit)?>(null)

    fun cancel(): Boolean {
        if (!state.compareAndSet(OperationState.QUEUED, OperationState.CANCELLED)) return false
        cancellationAction.get()?.invoke()
        return true
    }

    fun claim(): Boolean = state.compareAndSet(OperationState.QUEUED, OperationState.CLAIMED)

    fun complete() {
        check(state.compareAndSet(OperationState.CLAIMED, OperationState.COMPLETED))
    }

    fun failBeforeClaim(): Boolean =
        state.compareAndSet(OperationState.QUEUED, OperationState.COMPLETED)

    fun isCancelled(): Boolean = state.get() == OperationState.CANCELLED

    fun onCancellation(action: () -> Unit) {
        val once = AtomicBoolean()
        val guarded = { if (once.compareAndSet(false, true)) action() }
        cancellationAction.set(guarded)
        if (isCancelled()) guarded()
    }

    private enum class OperationState {
        QUEUED,
        CLAIMED,
        COMPLETED,
        CANCELLED,
    }
}

internal interface CoordinatorLooper {
    fun post(runnable: Runnable): Boolean

    fun remove(runnable: Runnable)

    fun isCurrent(): Boolean
}

internal class HandlerCoordinatorLooper(looper: Looper) : CoordinatorLooper {
    private val handler = Handler(looper)

    override fun post(runnable: Runnable): Boolean = handler.post(runnable)

    override fun remove(runnable: Runnable) {
        handler.removeCallbacks(runnable)
    }

    override fun isCurrent(): Boolean = Looper.myLooper() === handler.looper
}

internal sealed interface LooperOperationResult<out T> {
    data class Success<T>(val value: T) : LooperOperationResult<T>

    data object Cancelled : LooperOperationResult<Nothing>

    data object Unavailable : LooperOperationResult<Nothing>
}

internal class PlayerLooperExecutor(
    private val looper: CoordinatorLooper,
) {
    suspend fun <T> execute(
        ticket: PlayerOperationTicket,
        operation: () -> T,
    ): LooperOperationResult<T> = suspendCancellableCoroutine { continuation ->
        val resumed = AtomicBoolean()
        fun complete(result: LooperOperationResult<T>) {
            if (resumed.compareAndSet(false, true)) continuation.resume(result)
        }

        val runnable = Runnable {
            if (!ticket.claim()) {
                complete(LooperOperationResult.Cancelled)
                return@Runnable
            }
            val result = try {
                LooperOperationResult.Success(operation())
            } catch (_: Exception) {
                LooperOperationResult.Unavailable
            }
            ticket.complete()
            complete(result)
        }
        ticket.onCancellation {
            looper.remove(runnable)
            complete(LooperOperationResult.Cancelled)
        }
        continuation.invokeOnCancellation { ticket.cancel() }
        if (looper.isCurrent()) {
            runnable.run()
        } else if (!looper.post(runnable)) {
            when {
                ticket.failBeforeClaim() -> complete(LooperOperationResult.Unavailable)
                ticket.isCancelled() -> complete(LooperOperationResult.Cancelled)
            }
        }
    }
}

internal class PlaybackTargetToken {
    private val active = AtomicBoolean(true)

    fun isActive(): Boolean = active.get()

    fun retire() {
        active.set(false)
    }
}

internal class GrowingFinalitySignal {
    private val proven = AtomicBoolean()

    fun prove() {
        proven.set(true)
    }

    fun isProven(): Boolean = proven.get()
}

internal data class PlaybackPlayerSnapshot(
    val position: Duration,
    val duration: Duration?,
    val playbackState: Int,
    val failed: Boolean,
)

internal data class PlaybackPlayerEvent(
    val token: PlaybackTargetToken,
    val snapshot: PlaybackPlayerSnapshot,
    val paused: Boolean = false,
    val terminalExit: DvrPlaybackExit? = null,
    val recoveryReason: PlaybackRecoveryReason? = null,
    val growingFinalEndProven: Boolean = false,
)

internal class PlaybackPlayerEventAccumulator {
    private val lock = Any()
    private var pending: PlaybackPlayerEvent? = null
    val signal: Channel<Unit> = Channel(Channel.CONFLATED)

    fun publish(event: PlaybackPlayerEvent) {
        if (!event.token.isActive()) return
        synchronized(lock) {
            if (!event.token.isActive()) return
            val previous = pending
            pending = if (previous?.token === event.token) {
                event.copy(
                    paused = previous.paused || event.paused,
                    terminalExit = previous.terminalExit ?: event.terminalExit,
                    recoveryReason = previous.recoveryReason ?: event.recoveryReason,
                    growingFinalEndProven =
                        previous.growingFinalEndProven || event.growingFinalEndProven,
                )
            } else {
                event
            }
        }
        signal.trySend(Unit)
    }

    fun take(): PlaybackPlayerEvent? = synchronized(lock) {
        pending.also { pending = null }
    }

    fun retire(token: PlaybackTargetToken) {
        synchronized(lock) {
            if (pending?.token === token) pending = null
        }
    }

    fun discard() {
        synchronized(lock) { pending = null }
    }
}

internal class PlaybackReportEpoch(
    private val targetIsValid: () -> Boolean = { true },
) {
    private val active = AtomicBoolean(true)

    fun isValid(): Boolean {
        if (!active.get()) return false
        if (targetIsValid()) return true
        active.set(false)
        return false
    }

    fun invalidate() {
        active.set(false)
    }
}

internal data class PendingPlaybackProgress(
    val epoch: PlaybackReportEpoch,
    val target: CoordinatorRecordingTarget,
    val growingLease: GrowingRecordingFileLease?,
    val progress: DvrPlaybackProgress,
    val terminal: Boolean,
)

internal class PlaybackProgressMailbox {
    private val lock = Any()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var pending: PendingPlaybackProgress? = null
    private var sealed = false

    fun offer(report: PendingPlaybackProgress) {
        synchronized(lock) {
            if (sealed || !report.epoch.isValid()) return
            val previous = pending
            pending = when {
                previous == null -> report
                previous.epoch === report.epoch -> report.copy(
                    progress = report.progress.copy(
                        markWatched = previous.progress.markWatched || report.progress.markWatched,
                    ),
                    terminal = previous.terminal || report.terminal,
                )
                previous.terminal -> previous
                else -> report
            }
        }
        signal.trySend(Unit)
    }

    fun discardInvalid() {
        synchronized(lock) {
            if (pending?.epoch?.isValid() == false) pending = null
        }
    }

    fun seal() {
        synchronized(lock) { sealed = true }
        signal.trySend(Unit)
    }

    fun discard() {
        synchronized(lock) {
            pending = null
            sealed = true
        }
        signal.trySend(Unit)
    }

    suspend fun next(): PendingPlaybackProgress? {
        while (true) {
            synchronized(lock) {
                pending?.let { report ->
                    pending = null
                    return report
                }
                if (sealed) return null
            }
            signal.receive()
        }
    }
}

internal interface PlaybackCoordinatorTimeSource {
    fun now(): Instant

    suspend fun wait(duration: Duration)
}

internal data object SystemPlaybackCoordinatorTimeSource : PlaybackCoordinatorTimeSource {
    override fun now(): Instant = Clock.System.now()

    override suspend fun wait(duration: Duration) {
        delay(duration)
    }
}
