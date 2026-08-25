@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrEntry
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrRecordingFile
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrUpdateProvenance
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayRecordingFileStat
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.playback.RECORDING_END_OF_INPUT
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GrowingRecordingFileReaderTest {
    @Test
    fun `temporary end waits for stat growth and returns appended bytes`() = runTest {
        val fixture = GrowingFixture(readCounts = listOf(0, 3), statSizeBytes = 3L)
        val destination = ByteArray(4)

        assertOk(3, fixture.reader.read(destination, 0, destination.size))

        assertEquals(listOf(0L, 0L), fixture.transport.readPositions)
        assertEquals(listOf(0L), fixture.transport.statStartNanos)
        assertEquals(listOf<Byte>(1, 1, 1, 0), destination.toList())
    }

    @Test
    fun `one stat advance permits one immediate reread before throttled polling resumes`() = runTest {
        val fixture = GrowingFixture(readCounts = listOf(0, 0, 2), statSizeBytes = 2L)

        assertOk(2, fixture.reader.read(ByteArray(4), 0, 4))

        assertEquals(listOf(0L, 0L, 0L), fixture.transport.readPositions)
        assertEquals(
            listOf(0L, STAT_INTERVAL_NANOS_FOR_TEST),
            fixture.transport.statStartNanos,
            "An empty immediate reread must return to the two-per-second stat window",
        )
    }

    @Test
    fun `short reads advance the absolute position without requiring another stat`() = runTest {
        val fixture = GrowingFixture(readCounts = listOf(0, 1, 2), statSizeBytes = 3L)

        assertOk(1, fixture.reader.read(ByteArray(3), 0, 3))
        assertOk(2, fixture.reader.read(ByteArray(3), 0, 3))

        assertEquals(listOf(0L, 0L, 1L), fixture.transport.readPositions)
        assertEquals(listOf(0L), fixture.transport.statStartNanos)
    }

    @Test
    fun `omitted size remains temporary and reaches the monotonic timeout without a busy loop`() =
        runTest {
            val fixture = GrowingFixture(
                readCounts = listOf(0),
                statSizeBytes = null,
                statModifiedAtSeconds = null,
            )

            assertFailed(
                RecordingFileFailure.TIMEOUT,
                fixture.reader.read(ByteArray(1), 0, 1),
            )
            assertEquals(60, fixture.transport.statStartNanos.size)
            assertEquals(0L, fixture.transport.statStartNanos.first())
            assertEquals(29_500_000_000L, fixture.transport.statStartNanos.last())
            assertTrue(
                fixture.transport.statStartNanos.zipWithNext().all { (first, second) ->
                    second - first >= STAT_INTERVAL_NANOS_FOR_TEST
                },
            )

            val calls = fixture.transport.statStartNanos.size
            assertFailed(
                RecordingFileFailure.TIMEOUT,
                fixture.reader.read(ByteArray(1), 0, 1),
            )
            assertEquals(calls, fixture.transport.statStartNanos.size, "Timeout must be terminal")
        }

    @Test
    fun `caller idle time before the first empty read does not consume the timeout`() = runTest {
        val runtime = ManualGrowingRuntime()
        val fixture = GrowingFixture(
            runtime = runtime,
            readCounts = listOf(0, 1),
            statSizeBytes = 1L,
        )
        runtime.now = 31_000_000_000L

        assertOk(1, fixture.reader.read(ByteArray(1), 0, 1))
        assertEquals(listOf(31_000_000_000L), fixture.transport.statStartNanos)
    }

    @Test
    fun `stat and reread cannot complete beyond the no progress deadline`() = runTest {
        val statRuntime = ManualGrowingRuntime()
        val lateStat = GrowingFixture(
            runtime = statRuntime,
            readCounts = listOf(0),
            statSizeBytes = 1L,
        )
        lateStat.transport.beforeStatResult = { statRuntime.now = NO_PROGRESS_TIMEOUT_NANOS_FOR_TEST }

        assertFailed(
            RecordingFileFailure.TIMEOUT,
            lateStat.reader.read(ByteArray(1), 0, 1),
        )
        assertEquals(1, lateStat.transport.statStartNanos.size)

        val readRuntime = ManualGrowingRuntime()
        val lateRead = GrowingFixture(
            runtime = readRuntime,
            readCounts = listOf(0, 1),
            statSizeBytes = 1L,
        )
        lateRead.transport.beforeStatResult = {
            readRuntime.now = NO_PROGRESS_TIMEOUT_NANOS_FOR_TEST - 1L
        }
        lateRead.transport.beforeReadResult = {
            if (lateRead.transport.readPositions.size == 2) {
                readRuntime.now = NO_PROGRESS_TIMEOUT_NANOS_FOR_TEST
            }
        }

        assertFailed(
            RecordingFileFailure.TIMEOUT,
            lateRead.reader.read(ByteArray(1), 0, 1),
        )
        assertEquals(listOf(0L, 0L), lateRead.transport.readPositions)
    }

    @Test
    fun `a suspended stat is bounded by the remaining no progress deadline`() = runTest {
        val fixture = GrowingFixture(readCounts = listOf(0), statSizeBytes = null)
        fixture.transport.suspendStat = true

        assertFailed(
            RecordingFileFailure.TIMEOUT,
            fixture.reader.read(ByteArray(1), 0, 1),
        )
    }

    @Test
    fun `completion wakes validation but final stat still observes the polling interval`() = runTest {
        val runtime = ManualGrowingRuntime()
        val fixture = GrowingFixture(
            runtime = runtime,
            readCounts = listOf(0, 3),
            statSizeBytes = 0L,
        )
        runtime.steps += WaitStep(advanceNanos = 0L) { fixture.update(state = DvrEntryState.COMPLETED) }
        fixture.transport.statResults += listOf(
            GatewayRecordingFileStat(0L, 1L),
            GatewayRecordingFileStat(3L, 2L),
        )

        assertOk(3, fixture.reader.read(ByteArray(3), 0, 3))
        assertEquals(listOf(0L, STAT_INTERVAL_NANOS_FOR_TEST), fixture.transport.statStartNanos)
        assertOk(RECORDING_END_OF_INPUT, fixture.reader.read(ByteArray(1), 0, 1))
        assertEquals(2, fixture.transport.readPositions.size)
    }

    @Test
    fun `completed recording with omitted final size ends only on the next empty read`() = runTest {
        val fixture = GrowingFixture(
            state = DvrEntryState.COMPLETED,
            readCounts = listOf(0),
            statSizeBytes = null,
            statModifiedAtSeconds = null,
            openSizeBytes = null,
        )

        assertOk(RECORDING_END_OF_INPUT, fixture.reader.read(ByteArray(1), 0, 1))
        assertEquals(1, fixture.transport.statStartNanos.size)
        assertEquals(1, fixture.transport.readPositions.size)
    }

    @Test
    fun `known completed size must be drained and an early empty read fails closed`() = runTest {
        val fixture = GrowingFixture(
            state = DvrEntryState.COMPLETED,
            readCounts = listOf(0),
            statSizeBytes = 4L,
            openSizeBytes = 4L,
        )

        assertFailed(
            RecordingFileFailure.FILE_UNAVAILABLE,
            fixture.reader.read(ByteArray(4), 0, 4),
        )
    }

    @Test
    fun `stat shrink negative size and partial fields fail closed`() = runTest {
        listOf(
            GatewayRecordingFileStat(9L, 1L) to 10L,
            GatewayRecordingFileStat(-1L, 1L) to 0L,
            GatewayRecordingFileStat(null, 1L) to 0L,
        ).forEach { (stat, openSize) ->
            val fixture = GrowingFixture(
                readCounts = listOf(0),
                statSizeBytes = stat.sizeBytes,
                statModifiedAtSeconds = stat.modifiedAtUnixSeconds,
                openSizeBytes = openSize,
            )
            assertFailed(
                RecordingFileFailure.FILE_UNAVAILABLE,
                fixture.reader.read(ByteArray(1), 0, 1),
            )
        }
    }

    @Test
    fun `decreasing DVR progress is rejected before another stat`() = runTest {
        val runtime = ManualGrowingRuntime()
        val fixture = GrowingFixture(
            runtime = runtime,
            fileSizeBytes = 10L,
            dataSizeBytes = 10L,
            openSizeBytes = 10L,
            readCounts = listOf(0, 0),
            statSizeBytes = 10L,
        )
        runtime.steps += WaitStep(advanceNanos = 0L) {
            fixture.update(fileSizeBytes = 9L, dataSizeBytes = 9L)
        }

        assertFailed(
            RecordingFileFailure.FILE_UNAVAILABLE,
            fixture.reader.read(ByteArray(1), 0, 1),
        )
        assertEquals(1, fixture.transport.statStartNanos.size)
    }

    @Test
    fun `same entry rollover and clone replacement are not followed`() = runTest {
        val rolloverRuntime = ManualGrowingRuntime()
        val rollover = GrowingFixture(
            runtime = rolloverRuntime,
            readCounts = listOf(0),
            statSizeBytes = 0L,
        )
        rolloverRuntime.steps += WaitStep(advanceNanos = 0L) {
            rollover.update(
                files = listOf(
                    recordingFile(id = 11L, path = "/recording.ts", sizeBytes = 0L),
                    recordingFile(id = 12L, path = "/rollover.ts", sizeBytes = 0L),
                ),
            )
        }
        assertFailed(
            RecordingFileFailure.FILE_UNAVAILABLE,
            rollover.reader.read(ByteArray(1), 0, 1),
        )

        val cloneRuntime = ManualGrowingRuntime()
        val clone = GrowingFixture(
            runtime = cloneRuntime,
            readCounts = listOf(0),
            statSizeBytes = 0L,
        )
        cloneRuntime.steps += WaitStep(advanceNanos = 0L) { clone.replaceWithClone() }
        assertFailed(
            RecordingFileFailure.FILE_UNAVAILABLE,
            clone.reader.read(ByteArray(1), 0, 1),
        )

        val reincarnationRuntime = ManualGrowingRuntime()
        val reincarnation = GrowingFixture(
            runtime = reincarnationRuntime,
            readCounts = listOf(0),
            statSizeBytes = 0L,
        )
        reincarnationRuntime.steps += WaitStep(advanceNanos = 0L) {
            reincarnation.replaceWithIdenticalIncarnation()
        }
        assertFailed(
            RecordingFileFailure.FILE_UNAVAILABLE,
            reincarnation.reader.read(ByteArray(1), 0, 1),
        )
    }

    @Test
    fun `physical file identity drift fails closed`() = runTest {
        val runtime = ManualGrowingRuntime()
        val fixture = GrowingFixture(
            runtime = runtime,
            readCounts = listOf(0),
            statSizeBytes = 0L,
        )
        runtime.steps += WaitStep(advanceNanos = 0L) {
            fixture.update(
                files = listOf(recordingFile(id = 12L, path = "/replacement.ts", sizeBytes = 0L)),
            )
        }

        assertFailed(
            RecordingFileFailure.FILE_UNAVAILABLE,
            fixture.reader.read(ByteArray(1), 0, 1),
        )
    }

    @Test
    fun `transient metadata violations during stat remain visible`() = runTest {
        val identity = GrowingFixture(readCounts = listOf(0), statSizeBytes = 0L)
        identity.transport.beforeStatResult = {
            identity.update(
                files = listOf(recordingFile(id = 12L, path = "/replacement.ts", sizeBytes = 0L)),
            )
            identity.update()
        }
        assertFailed(
            RecordingFileFailure.FILE_UNAVAILABLE,
            identity.reader.read(ByteArray(1), 0, 1),
        )

        val multipleFiles = GrowingFixture(readCounts = listOf(0), statSizeBytes = 0L)
        multipleFiles.transport.beforeStatResult = {
            multipleFiles.update(
                files = listOf(
                    recordingFile(id = 11L, path = "/recording.ts", sizeBytes = 0L),
                    recordingFile(id = 12L, path = "/rollover.ts", sizeBytes = 0L),
                ),
            )
            multipleFiles.update()
        }
        assertFailed(
            RecordingFileFailure.FILE_UNAVAILABLE,
            multipleFiles.reader.read(ByteArray(1), 0, 1),
        )

        val shrink = GrowingFixture(
            fileSizeBytes = 10L,
            readCounts = listOf(0),
            statSizeBytes = 10L,
        )
        shrink.transport.beforeStatResult = {
            shrink.update(fileSizeBytes = 9L)
            shrink.update(fileSizeBytes = 10L)
        }
        assertFailed(
            RecordingFileFailure.FILE_UNAVAILABLE,
            shrink.reader.read(ByteArray(1), 0, 1),
        )

        val lifecycle = GrowingFixture(
            state = DvrEntryState.COMPLETED,
            readCounts = emptyList(),
            statSizeBytes = 0L,
        )
        lifecycle.transport.beforeStatResult = {
            lifecycle.update(state = DvrEntryState.RECORDING)
            lifecycle.update(state = DvrEntryState.COMPLETED)
        }
        assertFailed(
            RecordingFileFailure.FILE_UNAVAILABLE,
            lifecycle.reader.read(ByteArray(1), 0, 1),
        )
    }

    @Test
    fun `reader refuses an initial physical file without an identity proof`() = runTest {
        val fixture = GrowingFixture(readCounts = listOf(1), statSizeBytes = 1L)
        fixture.update(
            files = listOf(recordingFile(id = null, path = null, sizeBytes = 0L)),
        )

        assertFailed(
            RecordingFileFailure.FILE_UNAVAILABLE,
            fixture.reader.read(ByteArray(1), 0, 1),
        )
        assertTrue(fixture.transport.readPositions.isEmpty())
    }

    @Test
    fun `generation loss is distinct from file disappearance`() = runTest {
        val runtime = ManualGrowingRuntime()
        val fixture = GrowingFixture(
            runtime = runtime,
            readCounts = listOf(0),
            statSizeBytes = 0L,
        )
        runtime.steps += WaitStep(advanceNanos = 0L) {
            fixture.metadata.resetWorkingStateRetainingPublishedSnapshot()
        }

        assertFailed(
            RecordingFileFailure.CONNECTION_CHANGED,
            fixture.reader.read(ByteArray(1), 0, 1),
        )
    }

    @Test
    fun `completion is irreversible while a reader is active`() = runTest {
        val runtime = ManualGrowingRuntime()
        val fixture = GrowingFixture(
            runtime = runtime,
            readCounts = listOf(0),
            statSizeBytes = 0L,
        )
        runtime.steps += WaitStep(advanceNanos = 0L) {
            fixture.update(state = DvrEntryState.COMPLETED)
        }
        runtime.steps += WaitStep(advanceNanos = 0L) {
            fixture.update(state = DvrEntryState.RECORDING)
        }

        assertFailed(
            RecordingFileFailure.FILE_UNAVAILABLE,
            fixture.reader.read(ByteArray(1), 0, 1),
        )
    }

    @Test
    fun `cancellation aborts an active boundary wait`() = runTest {
        val fixture = GrowingFixture(
            runtime = CancellingGrowingRuntime,
            readCounts = listOf(0),
            statSizeBytes = 0L,
        )
        val reading = async { fixture.reader.read(ByteArray(1), 0, 1) }
        runCurrent()

        reading.cancelAndJoin()

        assertTrue(reading.isCancelled)
        assertEquals(1, fixture.transport.statStartNanos.size)
    }

    @Test
    fun `cancellation aborts read and stat RPCs`() = runTest {
        val readingFixture = GrowingFixture(readCounts = listOf(1), statSizeBytes = 1L)
        readingFixture.transport.suspendRead = true
        val reading = async { readingFixture.reader.read(ByteArray(1), 0, 1) }
        runCurrent()
        reading.cancelAndJoin()
        assertTrue(reading.isCancelled)

        val statingFixture = GrowingFixture(readCounts = listOf(0), statSizeBytes = 0L)
        statingFixture.transport.suspendStat = true
        val stating = async { statingFixture.reader.read(ByteArray(1), 0, 1) }
        runCurrent()
        stating.cancelAndJoin()
        assertTrue(stating.isCancelled)
    }

    @Test
    fun `pending thread interrupt between reads is preserved and prevents the next RPC`() {
        val fixture = GrowingFixture(readCounts = listOf(1, 1), statSizeBytes = 2L)
        val failure = AtomicReference<Throwable>()
        val interruptPreserved = AtomicBoolean(false)
        val thread = Thread {
            try {
                runBlocking {
                    assertOk(1, fixture.reader.read(ByteArray(1), 0, 1))
                    Thread.currentThread().interrupt()
                    fixture.reader.read(ByteArray(1), 0, 1)
                }
            } catch (caught: Throwable) {
                failure.set(caught)
                interruptPreserved.set(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
        }

        thread.start()
        thread.join(5_000L)

        assertTrue(!thread.isAlive, "Interrupted reader thread did not terminate")
        assertInstanceOf(InterruptedException::class.java, failure.get())
        assertTrue(interruptPreserved.get(), "Reader cleared the pending interrupt")
        assertEquals(listOf(0L), fixture.transport.readPositions)
    }

    @Test
    fun `close is idempotent and releases the transport once`() = runTest {
        val fixture = GrowingFixture()

        assertOk(Unit, fixture.reader.close())
        assertOk(Unit, fixture.reader.close())
        assertEquals(1, fixture.transport.closeCount)
        assertFailed(
            RecordingFileFailure.FILE_UNAVAILABLE,
            fixture.reader.read(ByteArray(1), 0, 1),
        )
    }

    @Test
    fun `close completes cleanup before propagating cancellation`() = runTest {
        val fixture = GrowingFixture()
        val gate = CompletableDeferred<Unit>()
        fixture.transport.closeGate = gate
        val closing = async { fixture.reader.close() }
        runCurrent()

        closing.cancel()
        runCurrent()
        assertFalse(closing.isCompleted)
        gate.complete(Unit)
        runCurrent()

        assertTrue(closing.isCancelled)
        assertEquals(1, fixture.transport.closeCount)
    }
}

private class GrowingFixture(
    internal val runtime: GrowingRecordingRuntime = ManualGrowingRuntime(),
    state: DvrEntryState = DvrEntryState.RECORDING,
    fileSizeBytes: Long? = 0L,
    dataSizeBytes: Long? = fileSizeBytes,
    openSizeBytes: Long? = fileSizeBytes,
    readCounts: List<Int> = emptyList(),
    statSizeBytes: Long? = fileSizeBytes,
    statModifiedAtSeconds: Long? = statSizeBytes?.let { 1L },
) {
    internal val generation = GatewayGeneration()
    internal val metadata = PhaseOneSessionMetadata()
    internal val transport = ScriptedGrowingTransport(runtime, readCounts, statSizeBytes, statModifiedAtSeconds)
    internal val reader: CoreGrowingRecordingFileReader

    init {
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(
            MetadataEvent.DvrEntryAdded(
                generation,
                entry(state = state, fileSizeBytes = fileSizeBytes, dataSizeBytes = dataSizeBytes),
            ),
        )
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        reader = CoreGrowingRecordingFileReader(
            transport = transport,
            metadata = GrowingRecordingMetadataTracker(metadata, generation, RECORDING_ID),
            position = 0L,
            openSizeBytes = openSizeBytes,
            runtime = runtime,
        )
    }

    internal fun update(
        state: DvrEntryState = DvrEntryState.RECORDING,
        fileSizeBytes: Long? = 0L,
        dataSizeBytes: Long? = fileSizeBytes,
        files: List<GatewayDvrRecordingFile> = listOf(
            recordingFile(id = 11L, path = "/recording.ts", sizeBytes = fileSizeBytes),
        ),
    ) {
        metadata.acceptMetadata(
            MetadataEvent.DvrEntryUpdated(
                generation,
                entry(
                    state = state,
                    fileSizeBytes = fileSizeBytes,
                    dataSizeBytes = dataSizeBytes,
                    files = files,
                ),
                GatewayDvrUpdateProvenance.FULL,
            ),
        )
    }

    internal fun replaceWithClone() {
        metadata.acceptMetadata(MetadataEvent.DvrEntryDeleted(generation, RECORDING_ID))
        metadata.acceptMetadata(
            MetadataEvent.DvrEntryAdded(
                generation,
                entry(id = DvrEntryId(8L), state = DvrEntryState.RECORDING),
            ),
        )
    }

    internal fun replaceWithIdenticalIncarnation() {
        metadata.acceptMetadata(MetadataEvent.DvrEntryDeleted(generation, RECORDING_ID))
        metadata.acceptMetadata(
            MetadataEvent.DvrEntryAdded(
                generation,
                entry(id = RECORDING_ID, state = DvrEntryState.RECORDING),
            ),
        )
    }
}

private class ScriptedGrowingTransport(
    private val runtime: GrowingRecordingRuntime,
    readCounts: List<Int>,
    statSizeBytes: Long?,
    statModifiedAtSeconds: Long?,
) : GrowingRecordingTransport {
    private val readResults = readCounts.toMutableList()
    private val defaultStat = GatewayRecordingFileStat(statSizeBytes, statModifiedAtSeconds)
    internal val statResults = ArrayList<GatewayRecordingFileStat>()
    internal val readPositions = ArrayList<Long>()
    internal val statStartNanos = ArrayList<Long>()
    internal var closeCount = 0
    internal var suspendRead = false
    internal var suspendStat = false
    internal var closeGate: CompletableDeferred<Unit>? = null
    internal var beforeReadResult: (() -> Unit)? = null
    internal var beforeStatResult: (() -> Unit)? = null

    override suspend fun read(
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): RecordingFileResult<Int> {
        readPositions += position
        if (suspendRead) awaitCancellation()
        val count = if (readResults.isEmpty()) 0 else readResults.removeAt(0)
        if (count in 1..length) destination.fill(1, destinationOffset, destinationOffset + count)
        beforeReadResult?.invoke()
        return RecordingFileResult.Ok(count)
    }

    override suspend fun stat(): RecordingFileResult<GatewayRecordingFileStat> {
        statStartNanos += runtime.nowNanos()
        if (suspendStat) awaitCancellation()
        beforeStatResult?.invoke()
        return RecordingFileResult.Ok(
            if (statResults.isEmpty()) defaultStat else statResults.removeAt(0),
        )
    }

    override suspend fun close(): RecordingFileResult<Unit> {
        closeCount += 1
        closeGate?.await()
        return RecordingFileResult.Ok(Unit)
    }
}

private class ManualGrowingRuntime : GrowingRecordingRuntime {
    internal var now: Long = 0L
    internal val steps = ArrayList<WaitStep>()

    override fun nowNanos(): Long = now

    override suspend fun awaitStateUpdate(
        states: kotlinx.coroutines.flow.StateFlow<DvrRepositoryState>,
        observed: DvrRepositoryState,
        waitNanos: Long,
    ) {
        val step = if (steps.isEmpty()) null else steps.removeAt(0)
        if (step == null) {
            now += waitNanos
        } else {
            now += minOf(waitNanos, step.advanceNanos)
            step.action()
        }
    }
}

private data class WaitStep(
    val advanceNanos: Long,
    val action: () -> Unit,
)

private data object CancellingGrowingRuntime : GrowingRecordingRuntime {
    override fun nowNanos(): Long = 0L

    override suspend fun awaitStateUpdate(
        states: kotlinx.coroutines.flow.StateFlow<DvrRepositoryState>,
        observed: DvrRepositoryState,
        waitNanos: Long,
    ): Unit = awaitCancellation()
}

private fun entry(
    id: DvrEntryId = RECORDING_ID,
    state: DvrEntryState,
    fileSizeBytes: Long? = 0L,
    dataSizeBytes: Long? = fileSizeBytes,
    files: List<GatewayDvrRecordingFile> = listOf(
        recordingFile(id = 11L, path = "/recording.ts", sizeBytes = fileSizeBytes),
    ),
): GatewayDvrEntry = GatewayDvrEntry(
    id = id,
    uuid = "recording-uuid",
    files = files,
    path = "/recording.ts",
    state = state,
    dataSizeBytes = dataSizeBytes,
)

private fun recordingFile(
    id: Long?,
    path: String?,
    sizeBytes: Long?,
): GatewayDvrRecordingFile = GatewayDvrRecordingFile(
    fileId = id,
    path = path,
    start = Instant.fromEpochSeconds(1L),
    stop = null,
    sizeBytes = sizeBytes,
)

private fun <T> assertOk(expected: T, actual: RecordingFileResult<T>) {
    assertTrue(actual is RecordingFileResult.Ok, "Expected success but was $actual")
    assertEquals(expected, (actual as RecordingFileResult.Ok).value)
}

private fun assertFailed(expected: RecordingFileFailure, actual: RecordingFileResult<*>) {
    assertTrue(actual is RecordingFileResult.Failed, "Expected failure but was $actual")
    assertSame(expected, (actual as RecordingFileResult.Failed).failure)
}

private val RECORDING_ID = DvrEntryId(7L)
private const val STAT_INTERVAL_NANOS_FOR_TEST = 500_000_000L
private const val NO_PROGRESS_TIMEOUT_NANOS_FOR_TEST = 30_000_000_000L
