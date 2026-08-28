@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrRecordingFile
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayRecordingFile
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayRecordingFileStat
import at.bernhardberger.tvheadend.sdk.core.gateway.ProtocolGateway
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.MAX_RECORDING_READ_BYTES
import at.bernhardberger.tvheadend.sdk.playback.RECORDING_END_OF_INPUT
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface GrowingMetadataValidation {
    public class Valid(
        internal val state: DvrRepositoryState.Current,
        internal val completed: Boolean,
    ) : GrowingMetadataValidation

    public class Failed(
        internal val failure: RecordingFileFailure,
    ) : GrowingMetadataValidation
}

internal class GrowingRecordingMetadataTracker(
    private val metadata: SessionMetadata,
    private val generation: GatewayGeneration,
    private val recordingId: DvrEntryId,
    private val playbackTarget: PlaybackRecordingTarget? = null,
) {
    private val lock = Any()
    internal val states: StateFlow<SessionObservation> = metadata.observation

    private var identity: GrowingRecordingIdentity? = null
    private var incarnation: DvrEntryIncarnation? = playbackTarget?.incarnation
    private var maximumFileSizeBytes: Long? = null
    private var maximumDataSizeBytes: Long? = null
    private var maximumTransportExtentBytes: Long = 0L
    private var completionObserved: Boolean = false
    private var terminalFailure: RecordingFileFailure? = null

    internal fun validate(): GrowingMetadataValidation = synchronized(lock) {
        terminalFailure?.let { failure ->
            return@synchronized GrowingMetadataValidation.Failed(failure)
        }
        validateCurrent().also { validation ->
            if (validation is GrowingMetadataValidation.Failed) {
                terminalFailure = validation.failure
            }
        }
    }

    internal fun validateTransportSize(sizeBytes: Long): RecordingFileFailure? = synchronized(lock) {
        terminalFailure?.let { failure -> return@synchronized failure }
        if (sizeBytes < 0L || sizeBytes < maximumTransportExtentBytes) {
            terminalFailure = RecordingFileFailure.FILE_UNAVAILABLE
            return@synchronized RecordingFileFailure.FILE_UNAVAILABLE
        }
        maximumTransportExtentBytes = sizeBytes
        null
    }

    internal fun observeTransportExtent(extentBytes: Long): RecordingFileFailure? = synchronized(lock) {
        terminalFailure?.let { failure -> return@synchronized failure }
        if (extentBytes < 0L) {
            terminalFailure = RecordingFileFailure.FILE_UNAVAILABLE
            return@synchronized RecordingFileFailure.FILE_UNAVAILABLE
        }
        maximumTransportExtentBytes = maxOf(maximumTransportExtentBytes, extentBytes)
        null
    }

    internal fun fenceFailure(failure: RecordingFileFailure): RecordingFileFailure = synchronized(lock) {
        terminalFailure?.let { retained -> return@synchronized retained }
        if (
            failure == RecordingFileFailure.CONNECTION_CHANGED ||
            failure == RecordingFileFailure.FILE_UNAVAILABLE
        ) {
            terminalFailure = failure
        }
        failure
    }

    private fun validateCurrent(): GrowingMetadataValidation {
        val current = playbackTarget?.let { target ->
            when (val lookup = metadata.currentPlaybackRecording(target)) {
                PlaybackRecordingLookup.ObservationExpired ->
                    return GrowingMetadataValidation.Failed(RecordingFileFailure.CONNECTION_CHANGED)
                PlaybackRecordingLookup.TargetUnavailable ->
                    return GrowingMetadataValidation.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
                is PlaybackRecordingLookup.Current -> CurrentDvrEntryLookup.Current(
                    state = lookup.state,
                    entry = lookup.entry,
                    matchCount = 1,
                    incarnation = lookup.target.incarnation,
                )
            }
        } ?: when (val lookup = metadata.currentDvrEntry(generation, recordingId)) {
            CurrentDvrEntryLookup.GenerationLost ->
                return GrowingMetadataValidation.Failed(RecordingFileFailure.CONNECTION_CHANGED)
            CurrentDvrEntryLookup.NotCurrent ->
                return GrowingMetadataValidation.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
            is CurrentDvrEntryLookup.Current -> lookup
        }
        if (current.matchCount != 1) {
            return GrowingMetadataValidation.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        }
        val entry = current.entry
            ?: return GrowingMetadataValidation.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        val candidateIncarnation = current.incarnation
            ?: return GrowingMetadataValidation.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        val establishedIncarnation = incarnation
        if (establishedIncarnation == null) {
            incarnation = candidateIncarnation
        } else if (establishedIncarnation !== candidateIncarnation) {
            return GrowingMetadataValidation.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        }
        val candidateIdentity = entry.growingIdentity()
            ?: return GrowingMetadataValidation.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        val establishedIdentity = identity
        if (establishedIdentity == null) {
            identity = candidateIdentity
        } else if (establishedIdentity != candidateIdentity) {
            return GrowingMetadataValidation.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        }

        // DVR counters validate their own stream; open-handle stat remains byte authority.
        val fileSize = entry.files?.singleOrNull()?.sizeBytes
        if (fileSize != null && (fileSize < 0L || fileSize < (maximumFileSizeBytes ?: 0L))) {
            return GrowingMetadataValidation.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        }
        val dataSize = entry.dataSizeBytes
        if (dataSize != null && (dataSize < 0L || dataSize < (maximumDataSizeBytes ?: 0L))) {
            return GrowingMetadataValidation.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        }
        if (fileSize != null) maximumFileSizeBytes = fileSize
        if (dataSize != null) maximumDataSizeBytes = dataSize

        val completed = when (entry.state) {
            DvrEntryState.RECORDING -> false
            DvrEntryState.COMPLETED -> true
            DvrEntryState.SCHEDULED,
            DvrEntryState.MISSED,
            DvrEntryState.INVALID,
            DvrEntryState.RECORDING_ERROR,
            DvrEntryState.COMPLETED_ERROR,
            DvrEntryState.FILE_MISSING,
            DvrEntryState.UNKNOWN,
            null,
            -> return GrowingMetadataValidation.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        }
        if (completionObserved && !completed) {
            return GrowingMetadataValidation.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        }
        completionObserved = completionObserved || completed
        return GrowingMetadataValidation.Valid(current.state, completed)
    }
}

internal data class GrowingRecordingIdentity(
    val entryUuid: String?,
    val entryPath: String?,
    val fileId: Long?,
    val filePath: String?,
    val fileStart: Instant?,
) {
    override fun toString(): String = "GrowingRecordingIdentity(<redacted>)"
}

internal fun DvrEntry.growingIdentity(): GrowingRecordingIdentity? {
    if (uuid != null && uuid.isBlank()) return null
    if (path != null && path.isBlank()) return null
    val file = files?.singleOrNull() ?: return null
    if (file.path != null && file.path.isBlank()) return null
    if (file.fileId == null && file.path == null) return null
    return GrowingRecordingIdentity(
        entryUuid = uuid,
        entryPath = path,
        fileId = file.fileId,
        filePath = file.path,
        fileStart = file.start,
    )
}

internal fun DvrEntry.preservesGrowingContinuity(next: DvrEntry): Boolean {
    val currentIdentity = growingIdentity() ?: return false
    if (next.growingIdentity() != currentIdentity) return false
    if (state !in GROWING_DVR_STATES || next.state !in GROWING_DVR_STATES) return false
    if (state == DvrEntryState.COMPLETED && next.state != DvrEntryState.COMPLETED) return false
    val currentFileSize = files?.singleOrNull()?.sizeBytes
    val nextFileSize = next.files?.singleOrNull()?.sizeBytes
    if (nextFileSize != null && (nextFileSize < 0L || currentFileSize != null && nextFileSize < currentFileSize)) {
        return false
    }
    val nextDataSize = next.dataSizeBytes
    if (nextDataSize != null &&
        (nextDataSize < 0L || dataSizeBytes != null && nextDataSize < dataSizeBytes)
    ) {
        return false
    }
    return true
}

internal class CompletedPlaybackIdentity private constructor(
    private val uuid: String?,
    private val entryPath: String?,
    private val fileIds: List<Long>?,
    private val filePaths: List<String>?,
    private val fileStarts: List<Instant>?,
) {
    internal fun isCompatibleWith(next: CompletedPlaybackIdentity): Boolean =
        uuid.matchesKnown(next.uuid) &&
            entryPath.matchesKnown(next.entryPath) &&
            fileIds.matchesKnown(next.fileIds) &&
            filePaths.matchesKnown(next.filePaths) &&
            fileStarts.matchesKnown(next.fileStarts)

    internal fun mergedWith(next: CompletedPlaybackIdentity): CompletedPlaybackIdentity =
        CompletedPlaybackIdentity(
            uuid = uuid ?: next.uuid,
            entryPath = entryPath ?: next.entryPath,
            fileIds = fileIds ?: next.fileIds,
            filePaths = filePaths ?: next.filePaths,
            fileStarts = fileStarts ?: next.fileStarts,
        )

    override fun toString(): String = "CompletedPlaybackIdentity(<redacted>)"

    internal companion object {
        internal fun create(entry: DvrEntry): CompletedPlaybackIdentity? {
            if (entry.state != DvrEntryState.COMPLETED) return null
            return CompletedPlaybackIdentity(
                uuid = entry.uuid?.takeUnless(String::isBlank),
                entryPath = entry.path?.takeUnless(String::isBlank),
                fileIds = entry.files.knownFileValues { file -> file.fileId },
                filePaths = entry.files.knownFileValues { file ->
                    file.path?.takeUnless(String::isBlank)
                },
                fileStarts = entry.files.knownFileValues { file -> file.start },
            )
        }
    }
}

private fun <T> T?.matchesKnown(next: T?): Boolean =
    this == null || next == null || this == next

private fun <T : Any> List<DvrRecordingFile>?.knownFileValues(
    value: (DvrRecordingFile) -> T?,
): List<T>? {
    val files = this ?: return null
    return buildList(files.size) {
        files.forEach { file -> add(value(file) ?: return null) }
    }
}

internal interface GrowingRecordingTransport {
    public suspend fun read(
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): RecordingFileResult<Int>

    public suspend fun stat(): RecordingFileResult<GatewayRecordingFileStat>

    public suspend fun close(): RecordingFileResult<Unit>
}

internal class GatewayGrowingRecordingTransport(
    private val gateway: ProtocolGateway,
    private val generation: GatewayGeneration,
    private val file: GatewayRecordingFile,
) : GrowingRecordingTransport {
    private val closed = AtomicBoolean(false)

    override suspend fun read(
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): RecordingFileResult<Int> {
        if (closed.get()) return CLOSED_GROWING_HANDLE
        return gateway.readRecordingFile(
            generation,
            file,
            position,
            destination,
            destinationOffset,
            length,
        ).toRecordingFileResult { count -> count }
    }

    override suspend fun stat(): RecordingFileResult<GatewayRecordingFileStat> {
        if (closed.get()) return CLOSED_GROWING_HANDLE
        return gateway.statRecordingFile(generation, file).toRecordingFileResult { stat -> stat }
    }

    override suspend fun close(): RecordingFileResult<Unit> {
        if (!closed.compareAndSet(false, true)) return RecordingFileResult.Ok(Unit)
        return gateway.closeRecordingFile(generation, file).toRecordingFileResult {}
    }
}

internal interface GrowingRecordingRuntime {
    public fun nowNanos(): Long

    public suspend fun awaitStateUpdate(
        states: StateFlow<SessionObservation>,
        observed: DvrRepositoryState,
        waitNanos: Long,
    )
}

private class SystemGrowingRecordingRuntime : GrowingRecordingRuntime {
    private val origin = TimeSource.Monotonic.markNow()

    override fun nowNanos(): Long = origin.elapsedNow().inWholeNanoseconds

    override suspend fun awaitStateUpdate(
        states: StateFlow<SessionObservation>,
        observed: DvrRepositoryState,
        waitNanos: Long,
    ) {
        if (waitNanos <= 0L) return
        withTimeoutOrNull(waitNanos.nanoseconds) {
            states.first { observation -> observation.dvrState !== observed }
        }
    }
}

internal class CoreGrowingRecordingFileReader(
    private val transport: GrowingRecordingTransport,
    private val metadata: GrowingRecordingMetadataTracker,
    position: Long,
    openSizeBytes: Long?,
    private val runtime: GrowingRecordingRuntime = SystemGrowingRecordingRuntime(),
) : GrowingRecordingFileReader {
    private var position: Long = position
    private var maximumProvenSizeBytes: Long = maxOf(position, openSizeBytes ?: position)
    private var noProgressSinceNanos: Long? = null
    private var lastStatStartNanos: Long? = null
    private var finalStatCompleted: Boolean = false
    private var finalSizeBytes: Long? = null
    private var endOfInput: Boolean = false
    private var closed: Boolean = false
    private var terminalFailure: RecordingFileFailure? = null

    override suspend fun read(
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): RecordingFileResult<Int> {
        require(destinationOffset in 0..destination.size) {
            "Destination offset must lie inside the destination array"
        }
        require(length in 0..(destination.size - destinationOffset)) {
            "Read window must lie inside the destination array"
        }
        require(length <= MAX_RECORDING_READ_BYTES) {
            "Growing recording read must not exceed $MAX_RECORDING_READ_BYTES bytes"
        }
        ensureReadActive()
        if (closed) return failed(RecordingFileFailure.FILE_UNAVAILABLE)
        terminalFailure?.let { failure -> return failed(failure) }
        if (length == 0) return RecordingFileResult.Ok(0)
        if (endOfInput) return RecordingFileResult.Ok(RECORDING_END_OF_INPUT)

        var observed = when (val validation = metadata.validate()) {
            is GrowingMetadataValidation.Failed -> return terminate(validation.failure)
            is GrowingMetadataValidation.Valid -> validation
        }
        if (observed.completed && finalStatCompleted) {
            finalSizeBytes?.let { finalSize ->
                if (position == finalSize) return finishEndOfInput()
                if (position > finalSize) return terminate(RecordingFileFailure.FILE_UNAVAILABLE)
            }
        }
        if (!observed.completed || finalStatCompleted) {
            when (val firstRead = readOnce(destination, destinationOffset, length)) {
                is ReadAttempt.Bytes -> return RecordingFileResult.Ok(firstRead.count)
                is ReadAttempt.Failed -> return terminate(firstRead.failure)
                ReadAttempt.Empty -> Unit
            }
            observed = when (val validation = metadata.validate()) {
                is GrowingMetadataValidation.Failed -> return terminate(validation.failure)
                is GrowingMetadataValidation.Valid -> validation
            }
            if (observed.completed && finalStatCompleted) {
                return finalEmptyRead()
            }
        }

        while (true) {
            observed = when (val ready = awaitStatWindow(observed)) {
                is GrowingMetadataValidation.Failed -> return terminate(ready.failure)
                is GrowingMetadataValidation.Valid -> ready
            }
            val completedBeforeStat = observed.completed
            ensureReadActive()
            lastStatStartNanos = runtime.nowNanos()
            val statResult = withinNoProgressBudget { transport.stat() }
                ?: return terminate(RecordingFileFailure.TIMEOUT)
            val stat = when (statResult) {
                is RecordingFileResult.Failed -> return terminate(statResult.failure)
                is RecordingFileResult.Ok -> statResult.value
            }
            ensureReadActive()
            observed = when (val validation = metadata.validate()) {
                is GrowingMetadataValidation.Failed -> return terminate(validation.failure)
                is GrowingMetadataValidation.Valid -> validation
            }
            val statSize = when (val validation = validateStat(stat)) {
                is StatValidation.Failed -> return terminate(validation.failure)
                is StatValidation.Valid -> validation.sizeBytes
            }
            val isFinalStat = completedBeforeStat && observed.completed
            if (isFinalStat) {
                finalStatCompleted = true
                finalSizeBytes = statSize
            }
            if (isNoProgressTimedOut()) return terminate(RecordingFileFailure.TIMEOUT)

            // Each throttled stat proving bytes beyond the cursor permits one retry.
            val shouldReadAgain = statSize?.let { size -> size > position } == true ||
                (isFinalStat && statSize == null)
            if (shouldReadAgain) {
                when (val reread = readOnce(destination, destinationOffset, length)) {
                    is ReadAttempt.Bytes -> return RecordingFileResult.Ok(reread.count)
                    is ReadAttempt.Failed -> return terminate(reread.failure)
                    ReadAttempt.Empty -> Unit
                }
                observed = when (val validation = metadata.validate()) {
                    is GrowingMetadataValidation.Failed -> return terminate(validation.failure)
                    is GrowingMetadataValidation.Valid -> validation
                }
                if (isFinalStat) return finalEmptyRead()
            } else if (isFinalStat) {
                return if (statSize == position) finishEndOfInput()
                else terminate(RecordingFileFailure.FILE_UNAVAILABLE)
            }
        }
    }

    override suspend fun close(): RecordingFileResult<Unit> {
        if (closed) return RecordingFileResult.Ok(Unit)
        closed = true
        val result = withContext(NonCancellable) { transport.close() }
        coroutineContext.ensureActive()
        return result
    }

    private suspend fun readOnce(
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): ReadAttempt {
        ensureReadActive()
        val readResult = withinNoProgressBudget {
            transport.read(position, destination, destinationOffset, length)
        } ?: return ReadAttempt.Failed(RecordingFileFailure.TIMEOUT)
        val count = when (readResult) {
            is RecordingFileResult.Failed -> return ReadAttempt.Failed(readResult.failure)
            is RecordingFileResult.Ok -> readResult.value
        }
        ensureReadActive()
        if (count !in 0..length) return ReadAttempt.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        if (count == 0 && noProgressSinceNanos == null) {
            noProgressSinceNanos = runtime.nowNanos()
        }
        val validation = metadata.validate()
        if (validation is GrowingMetadataValidation.Failed) {
            return ReadAttempt.Failed(validation.failure)
        }
        if (isNoProgressTimedOut()) return ReadAttempt.Failed(RecordingFileFailure.TIMEOUT)
        if (count == 0) return ReadAttempt.Empty
        if (position > Long.MAX_VALUE - count) {
            return ReadAttempt.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        }
        val nextPosition = position + count
        val finalSize = finalSizeBytes
        if (finalStatCompleted && finalSize != null && nextPosition > finalSize) {
            return ReadAttempt.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        }
        metadata.observeTransportExtent(nextPosition)?.let { failure ->
            return ReadAttempt.Failed(failure)
        }
        position = nextPosition
        maximumProvenSizeBytes = maxOf(maximumProvenSizeBytes, position)
        noProgressSinceNanos = null
        return ReadAttempt.Bytes(count)
    }

    private suspend fun awaitStatWindow(
        initial: GrowingMetadataValidation.Valid,
    ): GrowingMetadataValidation {
        var observed = initial
        while (true) {
            ensureReadActive()
            val now = runtime.nowNanos()
            val noProgressSince = noProgressSinceNanos
            if (noProgressSince != null && now - noProgressSince >= NO_PROGRESS_TIMEOUT_NANOS) {
                return GrowingMetadataValidation.Failed(RecordingFileFailure.TIMEOUT)
            }
            val allowedAt = lastStatStartNanos?.let { last -> last + STAT_INTERVAL_NANOS } ?: now
            if (now >= allowedAt) return observed
            val wakeAt = noProgressSince?.let { since ->
                minOf(allowedAt, since + NO_PROGRESS_TIMEOUT_NANOS)
            } ?: allowedAt
            runtime.awaitStateUpdate(metadata.states, observed.state, wakeAt - now)
            observed = when (val validation = metadata.validate()) {
                is GrowingMetadataValidation.Failed -> return validation
                is GrowingMetadataValidation.Valid -> validation
            }
        }
    }

    private fun validateStat(stat: GatewayRecordingFileStat): StatValidation {
        val size = stat.sizeBytes
        val modified = stat.modifiedAtUnixSeconds
        if ((size == null) != (modified == null)) {
            return StatValidation.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        }
        if (size != null && (size < 0L || size < maximumProvenSizeBytes)) {
            return StatValidation.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        }
        if (size != null) {
            metadata.validateTransportSize(size)?.let { failure ->
                return StatValidation.Failed(failure)
            }
        }
        if (size != null) maximumProvenSizeBytes = size
        return StatValidation.Valid(size)
    }

    private fun finalEmptyRead(): RecordingFileResult<Int> {
        val finalSize = finalSizeBytes
        return when {
            !finalStatCompleted -> terminate(RecordingFileFailure.FILE_UNAVAILABLE)
            finalSize == null -> finishEndOfInput()
            finalSize == position -> finishEndOfInput()
            else -> terminate(RecordingFileFailure.FILE_UNAVAILABLE)
        }
    }

    private fun finishEndOfInput(): RecordingFileResult<Int> {
        endOfInput = true
        return RecordingFileResult.Ok(RECORDING_END_OF_INPUT)
    }

    private fun terminate(failure: RecordingFileFailure): RecordingFileResult.Failed {
        val fenced = metadata.fenceFailure(failure)
        terminalFailure = fenced
        return failed(fenced)
    }

    private fun isNoProgressTimedOut(): Boolean = noProgressSinceNanos?.let { since ->
        runtime.nowNanos() - since >= NO_PROGRESS_TIMEOUT_NANOS
    } ?: false

    private suspend fun <T : Any> withinNoProgressBudget(operation: suspend () -> T): T? {
        val since = noProgressSinceNanos ?: return operation()
        val elapsed = runtime.nowNanos() - since
        if (elapsed >= NO_PROGRESS_TIMEOUT_NANOS) return null
        val result = withTimeoutOrNull((NO_PROGRESS_TIMEOUT_NANOS - elapsed).nanoseconds) {
            operation()
        }
        return result?.takeUnless { isNoProgressTimedOut() }
    }
}

private sealed interface ReadAttempt {
    public class Bytes(internal val count: Int) : ReadAttempt

    public data object Empty : ReadAttempt

    public class Failed(internal val failure: RecordingFileFailure) : ReadAttempt
}

private sealed interface StatValidation {
    public class Valid(internal val sizeBytes: Long?) : StatValidation

    public class Failed(internal val failure: RecordingFileFailure) : StatValidation
}

private suspend fun ensureReadActive() {
    coroutineContext.ensureActive()
    if (Thread.currentThread().isInterrupted) {
        throw InterruptedException("Growing recording read interrupted")
    }
}

private fun failed(failure: RecordingFileFailure): RecordingFileResult.Failed =
    RecordingFileResult.Failed(failure)

private val CLOSED_GROWING_HANDLE = failed(RecordingFileFailure.FILE_UNAVAILABLE)
private const val STAT_INTERVAL_NANOS = 500_000_000L
private const val NO_PROGRESS_TIMEOUT_NANOS = 30_000_000_000L
private val GROWING_DVR_STATES = setOf(DvrEntryState.RECORDING, DvrEntryState.COMPLETED)
