@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.os.Handler
import android.os.Looper
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackExit
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

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
        if (!looper.post(runnable)) {
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

internal class ReportingGateEpoch {
    private val valid = AtomicBoolean(true)

    fun isValid(): Boolean = valid.get()

    fun invalidate() {
        valid.set(false)
    }
}

internal class PlaybackReportEpoch(
    private val gate: ReportingGateEpoch,
    private val targetIsValid: () -> Boolean = { true },
) {
    private val active = AtomicBoolean(true)

    fun isValid(): Boolean = active.get() && gate.isValid() && targetIsValid()

    fun invalidate() {
        active.set(false)
    }
}

internal enum class GrowingRecordingObservation {
    RECORDING,
    COMPLETED,
    INVALID,
}

internal class GrowingRecordingFence private constructor(
    private val recordingId: DvrEntryId,
    private val states: StateFlow<DvrRepositoryState>,
    private val identity: CoordinatorGrowingIdentity,
    initialFileSizeBytes: Long?,
    initialDataSizeBytes: Long?,
) {
    private var valid = true
    private var completionObserved = false
    private var maximumFileSizeBytes = initialFileSizeBytes
    private var maximumDataSizeBytes = initialDataSizeBytes

    fun observe(): GrowingRecordingObservation = synchronized(this) {
        if (!valid) return@synchronized GrowingRecordingObservation.INVALID
        val current = states.value as? DvrRepositoryState.Current ?: return@synchronized invalidate()
        val entry = current.snapshot.entries.singleOrNull { candidate -> candidate.id == recordingId }
            ?: return@synchronized invalidate()
        if (entry.coordinatorGrowingIdentity() != identity) return@synchronized invalidate()

        val fileSize = entry.files?.singleOrNull()?.sizeBytes
        if (!observeCounter(fileSize, maximumFileSizeBytes)) return@synchronized invalidate()
        val dataSize = entry.dataSizeBytes
        if (!observeCounter(dataSize, maximumDataSizeBytes)) return@synchronized invalidate()
        if (fileSize != null) maximumFileSizeBytes = fileSize
        if (dataSize != null) maximumDataSizeBytes = dataSize

        when (entry.state) {
            DvrEntryState.RECORDING -> if (completionObserved) {
                invalidate()
            } else {
                GrowingRecordingObservation.RECORDING
            }
            DvrEntryState.COMPLETED -> {
                completionObserved = true
                GrowingRecordingObservation.COMPLETED
            }
            DvrEntryState.SCHEDULED,
            DvrEntryState.MISSED,
            DvrEntryState.INVALID,
            DvrEntryState.RECORDING_ERROR,
            DvrEntryState.COMPLETED_ERROR,
            DvrEntryState.FILE_MISSING,
            DvrEntryState.UNKNOWN,
            null,
            -> invalidate()
        }
    }

    override fun toString(): String = "GrowingRecordingFence(<redacted>)"

    private fun observeCounter(value: Long?, maximum: Long?): Boolean =
        value == null || value >= 0L && (maximum == null || value >= maximum)

    private fun invalidate(): GrowingRecordingObservation {
        valid = false
        return GrowingRecordingObservation.INVALID
    }

    companion object {
        fun create(
            entry: DvrEntry,
            states: StateFlow<DvrRepositoryState>,
        ): GrowingRecordingFence? {
            if (entry.state != DvrEntryState.RECORDING) return null
            val identity = entry.coordinatorGrowingIdentity() ?: return null
            val fileSize = entry.files?.singleOrNull()?.sizeBytes
            if (fileSize != null && fileSize < 0L) return null
            val dataSize = entry.dataSizeBytes
            if (dataSize != null && dataSize < 0L) return null
            return GrowingRecordingFence(
                recordingId = entry.id,
                states = states,
                identity = identity,
                initialFileSizeBytes = fileSize,
                initialDataSizeBytes = dataSize,
            )
        }
    }
}

private data class CoordinatorGrowingIdentity(
    val entryUuid: String?,
    val entryPath: String?,
    val fileId: Long?,
    val filePath: String?,
    val fileStart: Instant?,
) {
    override fun toString(): String = "CoordinatorGrowingIdentity(<redacted>)"
}

private fun DvrEntry.coordinatorGrowingIdentity(): CoordinatorGrowingIdentity? {
    if (uuid?.isBlank() == true) return null
    if (path?.isBlank() == true) return null
    val file = files?.singleOrNull() ?: return null
    if (file.path?.isBlank() == true) return null
    if (file.fileId == null && file.path == null) return null
    return CoordinatorGrowingIdentity(
        entryUuid = uuid,
        entryPath = path,
        fileId = file.fileId,
        filePath = file.path,
        fileStart = file.start,
    )
}

internal data class PendingPlaybackProgress(
    val epoch: PlaybackReportEpoch,
    val recordingId: DvrEntryId,
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
