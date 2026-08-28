@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId as GatewayChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrEntryId as GatewayDvrEntryId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrEntry
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrRecordingFile
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrUpdateProvenance
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.session.GenerationBoundGrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.core.session.PhaseOneSessionMetadata
import at.bernhardberger.tvheadend.sdk.core.session.PlaybackRecordingTarget
import at.bernhardberger.tvheadend.sdk.core.session.SessionCapabilitiesSnapshot
import at.bernhardberger.tvheadend.sdk.core.session.SessionChildren
import at.bernhardberger.tvheadend.sdk.core.session.SessionMetadata
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.RecordingFile
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class PlaybackBindingTest {
    @Test
    fun `binding rejects stale foreign and unavailable target authority`() {
        val fixture = BindingFixture().apply { publishReady(GatewayGeneration()) }
        val foreign = BindingFixture().apply { publishReady(GatewayGeneration()) }
            .currentSession

        listOf(CurrentSessionObservation(Any(), Any()), foreign).forEach { proof ->
            assertSame(
                PlaybackBindingResult.ObservationExpired,
                fixture.factory.bindLive(proof, ChannelId(1)),
            )
            assertSame(
                PlaybackBindingResult.ObservationExpired,
                fixture.factory.bindRecording(proof, DvrEntryId(7)),
            )
        }
        assertSame(
            PlaybackBindingResult.TargetUnavailable,
            fixture.factory.bindLive(fixture.currentSession, ChannelId(2)),
        )
        assertSame(
            PlaybackBindingResult.TargetUnavailable,
            fixture.factory.bindRecording(fixture.currentSession, DvrEntryId(8)),
        )
    }

    @Test
    fun `delayed live open never follows a colliding target into a new generation`() =
        kotlinx.coroutines.test.runTest {
            val fixture = BindingFixture()
            val generationA = GatewayGeneration()
            fixture.publishReady(generationA)
            val binding = fixture.factory
                .bindLive(fixture.currentSession, ChannelId(1))
                .requireBound()

            binding.open(SubscriptionEventConsumer {}, SubscriptionOptions())
            assertEquals(listOf(generationA), fixture.children.liveGenerations)

            fixture.publishReady(GatewayGeneration())

            assertFalse(binding.isCurrent)
            assertSame(
                SubscriptionOpenResult.NotReady,
                binding.open(SubscriptionEventConsumer {}, SubscriptionOptions()),
            )
            assertEquals(listOf(generationA), fixture.children.liveGenerations)
        }

    @Test
    fun `completed open progress and cutpoints stay on their originating generation`() =
        kotlinx.coroutines.test.runTest {
            val fixture = BindingFixture()
            val generationA = GatewayGeneration()
            fixture.publishReady(generationA)
            val binding = fixture.factory
                .bindRecording(fixture.currentSession, DvrEntryId(7))
                .requireBound()
            val progress = DvrPlaybackProgress.checkpoint(12.seconds)

            assertTrue(binding.openRecording() is RecordingFileResult.Ok)
            assertSame(DvrProgressResult.Accepted, binding.reportProgress(null, progress))
            assertSame(DvrCutpointsResult.NotSupported, binding.cutpoints())
            assertEquals(listOf(generationA), fixture.children.recordingGenerations)
            assertEquals(listOf(generationA), fixture.metadata.progressGenerations)
            assertEquals(listOf(generationA), fixture.metadata.cutpointGenerations)

            fixture.publishReady(GatewayGeneration())

            assertSame(RecordingPlaybackAdmission.ObservationExpired, binding.admission)
            assertSame(
                at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure.CONNECTION_CHANGED,
                (binding.openRecording() as RecordingFileResult.Failed).failure,
            )
            assertSame(
                DvrProgressResult.ObservationExpired,
                binding.reportProgress(null, progress),
            )
            assertSame(DvrCutpointsResult.ObservationExpired, binding.cutpoints())
            assertEquals(listOf(generationA), fixture.children.recordingGenerations)
            assertEquals(listOf(generationA), fixture.metadata.progressGenerations)
            assertEquals(listOf(generationA), fixture.metadata.cutpointGenerations)
        }

    @Test
    fun `completed playback remains admitted without progress support and starts over`() =
        kotlinx.coroutines.test.runTest {
            listOf(
                RecordingProgressCapability.UNKNOWN to DvrProgressResult.NotReady,
                RecordingProgressCapability.UNSUPPORTED to DvrProgressResult.NotSupported,
            ).forEach { (capability, expectedProgress) ->
                val fixture = BindingFixture().apply {
                    publishReady(GatewayGeneration(), capability)
                }
                val binding = fixture.factory
                    .bindRecording(fixture.currentSession, DvrEntryId(7))
                    .requireBound()
                val admission = binding.admission as RecordingPlaybackAdmission.Completed

                assertNull(admission.resumePosition)
                assertSame(capability, admission.progressCapability)
                assertTrue(binding.openRecording() is RecordingFileResult.Ok)
                assertSame(
                    expectedProgress,
                    binding.reportProgress(null, DvrPlaybackProgress.checkpoint(12.seconds)),
                )
                assertTrue(fixture.metadata.progressGenerations.isEmpty())
            }
        }

    @Test
    fun `completed binding permanently rejects a same generation recording reincarnation`() =
        kotlinx.coroutines.test.runTest {
            val fixture = BindingFixture()
            val generation = GatewayGeneration()
            fixture.publishReady(generation)
            val currentSession = fixture.currentSession
            val binding = fixture.factory
                .bindRecording(currentSession, DvrEntryId(7))
                .requireBound()

            fixture.updateCompletedProgress(generation, 48.seconds)
            assertSame(currentSession, fixture.currentSession)
            assertEquals(
                48.seconds,
                (binding.admission as RecordingPlaybackAdmission.Completed).resumePosition,
            )

            fixture.replaceCompletedRecording(generation)
            assertSame(currentSession, fixture.currentSession)
            assertSame(RecordingPlaybackAdmission.TargetUnavailable, binding.admission)
            assertSame(
                at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure.FILE_UNAVAILABLE,
                (binding.openRecording() as RecordingFileResult.Failed).failure,
            )
            assertSame(
                DvrProgressResult.NotReady,
                binding.reportProgress(null, DvrPlaybackProgress.checkpoint(12.seconds)),
            )
            assertSame(DvrCutpointsResult.NotReady, binding.cutpoints())
            assertTrue(fixture.children.recordingGenerations.isEmpty())
            assertTrue(fixture.metadata.progressGenerations.isEmpty())
            assertTrue(fixture.metadata.cutpointGenerations.isEmpty())
        }

    @Test
    fun `completed binding survives ordinary null and multi file metadata updates`() =
        kotlinx.coroutines.test.runTest {
            val shapes = listOf(
                null to null,
                null to listOf(
                    recordingFile(fileId = 1, path = "/part-1.mkv"),
                    recordingFile(fileId = 2, path = "/part-2.mkv"),
                ),
            )
            shapes.forEach { (path, files) ->
                val fixture = BindingFixture()
                val generation = GatewayGeneration()
                fixture.publishReady(
                    generation,
                    recordingPath = path,
                    recordingFiles = files,
                )
                val currentSession = fixture.currentSession
                val binding = fixture.factory
                    .bindRecording(currentSession, DvrEntryId(7))
                    .requireBound()

                fixture.updateCompletedProgress(
                    generation,
                    position = 48.seconds,
                    path = path,
                    files = files,
                )

                assertSame(currentSession, fixture.currentSession)
                assertEquals(
                    48.seconds,
                    (binding.admission as RecordingPlaybackAdmission.Completed).resumePosition,
                )
                assertTrue(binding.openRecording() is RecordingFileResult.Ok)
                assertSame(
                    DvrProgressResult.Accepted,
                    binding.reportProgress(null, DvrPlaybackProgress.checkpoint(12.seconds)),
                )
                assertSame(DvrCutpointsResult.NotSupported, binding.cutpoints())
                assertEquals(1, fixture.children.recordingGenerations.size)
                assertEquals(1, fixture.metadata.progressGenerations.size)
                assertEquals(1, fixture.metadata.cutpointGenerations.size)

                fixture.updateCompletedProgress(
                    generation,
                    position = 49.seconds,
                    path = path,
                    files = files,
                    uuid = "replacement-recording",
                )
                assertSame(RecordingPlaybackAdmission.TargetUnavailable, binding.admission)
                assertSame(
                    at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure.FILE_UNAVAILABLE,
                    (binding.openRecording() as RecordingFileResult.Failed).failure,
                )
                assertSame(
                    DvrProgressResult.NotReady,
                    binding.reportProgress(null, DvrPlaybackProgress.checkpoint(13.seconds)),
                )
                assertSame(DvrCutpointsResult.NotReady, binding.cutpoints())
                assertEquals(1, fixture.children.recordingGenerations.size)
                assertEquals(1, fixture.metadata.progressGenerations.size)
                assertEquals(1, fixture.metadata.cutpointGenerations.size)
            }
        }

    @Test
    fun `completed binding never launders known identity through omitted metadata`() =
        kotlinx.coroutines.test.runTest {
            val fixture = BindingFixture()
            val generation = GatewayGeneration()
            fixture.publishReady(generation)
            val binding = fixture.factory
                .bindRecording(fixture.currentSession, DvrEntryId(7))
                .requireBound()

            fixture.updateCompletedProgress(
                generation,
                position = 48.seconds,
                path = null,
                files = null,
                uuid = null,
            )
            assertTrue(binding.admission is RecordingPlaybackAdmission.Completed)

            fixture.updateCompletedProgress(
                generation,
                position = 49.seconds,
                path = "/replacement.mkv",
                files = listOf(recordingFile(fileId = 2, path = "/replacement.mkv")),
                uuid = "replacement-recording",
            )
            assertSame(RecordingPlaybackAdmission.TargetUnavailable, binding.admission)
            assertSame(
                at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure.FILE_UNAVAILABLE,
                (binding.openRecording() as RecordingFileResult.Failed).failure,
            )
            assertSame(
                DvrProgressResult.NotReady,
                binding.reportProgress(null, DvrPlaybackProgress.checkpoint(13.seconds)),
            )
            assertSame(DvrCutpointsResult.NotReady, binding.cutpoints())
            assertTrue(fixture.children.recordingGenerations.isEmpty())
            assertTrue(fixture.metadata.progressGenerations.isEmpty())
            assertTrue(fixture.metadata.cutpointGenerations.isEmpty())
        }

    @Test
    fun `completed binding retains identity learned after an initially unknown target`() =
        kotlinx.coroutines.test.runTest {
            val fixture = BindingFixture()
            val generation = GatewayGeneration()
            fixture.publishReady(
                generation,
                recordingUuid = null,
                recordingPath = null,
                recordingFiles = null,
            )
            val binding = fixture.factory
                .bindRecording(fixture.currentSession, DvrEntryId(7))
                .requireBound()

            fixture.updateCompletedProgress(
                generation,
                position = 48.seconds,
                path = "/learned.mkv",
                files = listOf(recordingFile(fileId = 1, path = "/learned.mkv")),
                uuid = "learned-recording",
            )
            fixture.updateCompletedProgress(
                generation,
                position = 49.seconds,
                path = null,
                files = null,
                uuid = null,
            )
            assertTrue(binding.admission is RecordingPlaybackAdmission.Completed)

            fixture.updateCompletedProgress(
                generation,
                position = 50.seconds,
                path = "/replacement.mkv",
                files = listOf(recordingFile(fileId = 2, path = "/replacement.mkv")),
                uuid = "replacement-recording",
            )
            assertSame(RecordingPlaybackAdmission.TargetUnavailable, binding.admission)
        }

    @Test
    fun `growing progress rejects a lease from another target or generation`() =
        kotlinx.coroutines.test.runTest {
            val fixture = BindingFixture()
            val generation = GatewayGeneration()
            fixture.publishReady(generation, recordingState = DvrEntryState.RECORDING)
            val binding = fixture.factory
                .bindRecording(fixture.currentSession, DvrEntryId(7))
                .requireBound()
            val progress = DvrPlaybackProgress.checkpoint(12.seconds)

            listOf(
                TestGrowingLease(generation, DvrEntryId(8)),
                TestGrowingLease(GatewayGeneration(), DvrEntryId(7)),
                PublicGrowingLease,
            ).forEach { lease ->
                assertSame(DvrProgressResult.NotReady, binding.reportProgress(lease, progress))
            }
            assertTrue(fixture.metadata.progressLeases.isEmpty())

            val exactLease = TestGrowingLease(generation, DvrEntryId(7))
            assertSame(DvrProgressResult.Accepted, binding.reportProgress(exactLease, progress))
            assertEquals(listOf(exactLease), fixture.metadata.progressLeases)
        }

    @Test
    fun `binding identities and admissions redact target details`() {
        val fixture = BindingFixture().apply { publishReady(GatewayGeneration()) }
        val live = fixture.factory.bindLive(fixture.currentSession, ChannelId(1)).requireBound()
        val recording = fixture.factory
            .bindRecording(fixture.currentSession, DvrEntryId(7))
            .requireBound()

        assertEquals("PlaybackBinding.Live(<redacted>)", live.toString())
        assertEquals("PlaybackBinding.Recording(<redacted>)", recording.toString())
        assertEquals(
            "RecordingPlaybackAdmission.Completed(<redacted>)",
            recording.admission.toString(),
        )
    }
}

private class BindingFixture {
    val metadata = CapturingBindingMetadata()
    val children = CapturingBindingChildren()
    val factory = SessionPlaybackBindingFactory(metadata, children)

    val currentSession: CurrentSessionObservation
        get() = requireNotNull(metadata.observation.value.currentSession)

    fun publishReady(
        generation: GatewayGeneration,
        progressCapability: RecordingProgressCapability = RecordingProgressCapability.SUPPORTED,
        recordingState: DvrEntryState = DvrEntryState.COMPLETED,
        recordingUuid: String? = "colliding-recording",
        recordingPath: String? = "/recording.ts",
        recordingFiles: List<GatewayDvrRecordingFile>? = listOf(recordingFile()),
    ) {
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(
            MetadataEvent.ChannelAdded(
                generation,
                GatewayChannelMetadata(
                    id = GatewayChannelId(1),
                    name = null,
                    uuid = null,
                    number = null,
                    numberMinor = null,
                    icon = null,
                    currentEventId = null,
                    nextEventId = null,
                    services = null,
                    tagIds = null,
                ),
            ),
        )
        metadata.acceptMetadata(
            MetadataEvent.DvrEntryAdded(
                generation,
                recordingEntry(
                    state = recordingState,
                    uuid = recordingUuid,
                    path = recordingPath,
                    files = recordingFiles,
                ),
            ),
        )
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        metadata.publishSessionState(
            state = SessionState.Ready(
                ServerCapabilities.create(
                    streaming = CapabilityAccess.ALLOWED,
                    dvrWrite = CapabilityAccess.ALLOWED,
                ),
            ),
            progressCapability = progressCapability,
            generation = generation,
        )
    }

    fun updateCompletedProgress(
        generation: GatewayGeneration,
        position: kotlin.time.Duration,
        path: String? = "/recording.ts",
        files: List<GatewayDvrRecordingFile>? = listOf(recordingFile()),
        uuid: String? = "colliding-recording",
    ) {
        metadata.acceptMetadata(
            MetadataEvent.DvrEntryUpdated(
                generation,
                recordingEntry(
                    state = DvrEntryState.COMPLETED,
                    playPosition = position,
                    uuid = uuid,
                    path = path,
                    files = files,
                ),
                GatewayDvrUpdateProvenance.FULL,
            ),
        )
    }

    fun replaceCompletedRecording(generation: GatewayGeneration) {
        metadata.acceptMetadata(MetadataEvent.DvrEntryDeleted(generation, GatewayDvrEntryId(7)))
        metadata.acceptMetadata(
            MetadataEvent.DvrEntryAdded(
                generation,
                recordingEntry(
                    state = DvrEntryState.COMPLETED,
                    uuid = "replacement-recording",
                    fileId = 2,
                    path = "/replacement.ts",
                    files = listOf(recordingFile(fileId = 2, path = "/replacement.ts")),
                ),
            ),
        )
    }
}

private fun recordingEntry(
    state: DvrEntryState,
    playPosition: kotlin.time.Duration = 37.seconds,
    uuid: String? = "colliding-recording",
    fileId: Long = 1,
    path: String? = "/recording.ts",
    files: List<GatewayDvrRecordingFile>? = listOf(recordingFile(fileId, path)),
): GatewayDvrEntry = GatewayDvrEntry(
    id = GatewayDvrEntryId(7),
    uuid = uuid,
    playPosition = playPosition,
    files = files,
    path = path,
    state = state,
)

private fun recordingFile(
    fileId: Long = 1,
    path: String? = "/recording.ts",
): GatewayDvrRecordingFile = GatewayDvrRecordingFile(
    fileId = fileId,
    path = path,
    start = null,
    stop = null,
    sizeBytes = 1,
)

private class CapturingBindingMetadata(
    private val delegate: PhaseOneSessionMetadata = PhaseOneSessionMetadata(),
) : SessionMetadata by delegate {
    val progressGenerations = mutableListOf<GatewayGeneration>()
    val progressLeases = mutableListOf<GrowingRecordingFileLease>()
    val cutpointGenerations = mutableListOf<GatewayGeneration>()

    override suspend fun reportProgress(
        target: PlaybackRecordingTarget,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult {
        progressGenerations += target.generation
        return DvrProgressResult.Accepted
    }

    override suspend fun reportProgress(
        lease: GrowingRecordingFileLease,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult {
        progressLeases += lease
        return DvrProgressResult.Accepted
    }

    override suspend fun getCutpoints(
        target: PlaybackRecordingTarget,
    ): DvrCutpointsResult {
        cutpointGenerations += target.generation
        return DvrCutpointsResult.NotSupported
    }
}

private class CapturingBindingChildren : SessionChildren {
    val liveGenerations = mutableListOf<GatewayGeneration>()
    val recordingGenerations = mutableListOf<GatewayGeneration>()

    override suspend fun open(
        generation: GatewayGeneration,
        channelId: SubscriptionChannelId,
        consumer: SubscriptionEventConsumer,
        options: SubscriptionOptions,
    ): SubscriptionOpenResult {
        liveGenerations += generation
        return SubscriptionOpenResult.NotReady
    }

    override suspend fun openRecording(
        target: PlaybackRecordingTarget,
    ): RecordingFileResult<RecordingFile> {
        recordingGenerations += target.generation
        return RecordingFileResult.Ok(BindingRecordingFile)
    }

    override fun bindGrowingRecording(
        target: PlaybackRecordingTarget,
    ): RecordingFileResult<GrowingRecordingFileLease> =
        error("Growing playback is outside this completed-recording fixture")

    override fun bindGeneration(generation: GatewayGeneration) = Unit

    override fun startLiveAdmission(
        generation: GatewayGeneration,
        streamingAccess: CapabilityAccess,
    ): Boolean = true

    override fun stopAdmission() = Unit

    override fun prepareBackgroundEnrichment(
        generation: GatewayGeneration,
        onDvrCapabilitiesChanged: suspend (SessionCapabilitiesSnapshot) -> Unit,
    ): Boolean = true

    override suspend fun cancelAndJoinBackgroundEnrichment() = Unit

    override suspend fun closeAndJoinSubscriptions() = Unit
}

private data object BindingRecordingFile : RecordingFile {
    override val sizeBytes: Long = 1L

    override suspend fun seek(position: Long): RecordingFileResult<Long> =
        RecordingFileResult.Ok(position)

    override suspend fun read(
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): RecordingFileResult<Int> = RecordingFileResult.Ok(0)

    override suspend fun close(): RecordingFileResult<Unit> = RecordingFileResult.Ok(Unit)
}

private class TestGrowingLease(
    override val boundGeneration: GatewayGeneration,
    override val boundRecordingId: DvrEntryId,
) : GenerationBoundGrowingRecordingFileLease {
    override val isCurrent: Boolean = true

    override fun isProgressBindingCurrent(): Boolean = true

    override suspend fun open(
        position: Long,
    ): RecordingFileResult<at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader> =
        error("Opening is outside this progress-binding test")
}

private data object PublicGrowingLease : GrowingRecordingFileLease {
    override val isCurrent: Boolean = true

    override suspend fun open(
        position: Long,
    ): RecordingFileResult<at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader> =
        error("Opening is outside this progress-binding test")
}

private fun <T : PlaybackBinding> PlaybackBindingResult<T>.requireBound(): T =
    (this as PlaybackBindingResult.Bound).binding
