@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
)

package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId as GatewayChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrEntryId as GatewayDvrEntryId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrEntry
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrRecordingFile
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrStats
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
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class PlaybackBindingTest {
    @ParameterizedTest
    @ValueSource(strings = ["RECORDING", "RECORDING_ERROR"])
    fun `stats shrink invalidates a recording playback target`(stateName: String) {
        val state = dvrState(stateName)
        val generation = GatewayGeneration()
        val fixture = BindingFixture().apply {
            publishReady(generation, recordingState = state)
        }
        fun stats(size: Long) = fixture.metadata.acceptMetadata(
            MetadataEvent.DvrEntryStatsUpdated(
                generation,
                GatewayDvrStats(DvrEntryId(7), "colliding-recording", state, dataSizeBytes = size),
            ),
        )
        stats(100L)
        val binding = fixture.factory.bindRecording(fixture.currentSession, DvrEntryId(7)).requireBound()
        assertTrue(binding.admission is RecordingPlaybackAdmission.Growing)

        stats(50L)
        assertSame(RecordingPlaybackAdmission.TargetUnavailable, binding.admission)
        stats(100L)
        assertSame(RecordingPlaybackAdmission.TargetUnavailable, binding.admission)
    }

    @Test
    fun `binding rejects stale foreign and unavailable target authority`() {
        val fixture = BindingFixture().apply { publishReady(GatewayGeneration()) }
        val foreign = BindingFixture().apply { publishReady(GatewayGeneration()) }
            .currentSession

        listOf(
            CurrentSessionObservation.create(Any(), Any(), SessionGenerationIdentity.create()),
            foreign,
        ).forEach { proof ->
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
            assertEquals(DvrCutpointsResult.Failed(TvheadendFailure.NOT_SUPPORTED), binding.cutpoints())
            assertEquals(listOf(generationA), fixture.children.recordingGenerations)
            assertEquals(listOf(generationA), fixture.metadata.progressGenerations)
            assertEquals(listOf(generationA), fixture.metadata.cutpointGenerations)

            fixture.publishReady(GatewayGeneration())

            assertSame(RecordingPlaybackAdmission.ObservationExpired, binding.admission)
            assertSame(
                at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure.CONNECTION_CHANGED,
                (binding.openRecording() as RecordingFileResult.Failed).failure,
            )
            assertEquals(
                DvrProgressResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, mayHaveApplied = false),
                binding.reportProgress(null, progress),
            )
            assertEquals(DvrCutpointsResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED), binding.cutpoints())
            assertEquals(listOf(generationA), fixture.children.recordingGenerations)
            assertEquals(listOf(generationA), fixture.metadata.progressGenerations)
            assertEquals(listOf(generationA), fixture.metadata.cutpointGenerations)
        }

    @Test
    fun `recording binding declares the played file's first video frame duration while current`() {
        val fixture = BindingFixture()
        val generation = GatewayGeneration()
        // Tvheadend plays the last file; earlier files never decide.
        fun files(played: List<DvrRecordingStream>?) = listOf(
            recordingFile(fileId = 1, streams = listOf(
                DvrRecordingStream(SubscriptionStreamType.H264, frameDuration = 20.milliseconds),
            )),
            recordingFile(fileId = 2, streams = played),
        )
        fixture.publishReady(
            generation,
            recordingFiles = files(listOf(
                DvrRecordingStream(SubscriptionStreamType.AAC, frameDuration = 21.milliseconds),
                DvrRecordingStream(SubscriptionStreamType.MPEG2_VIDEO, frameDuration = 40.milliseconds),
                DvrRecordingStream(SubscriptionStreamType.H265, frameDuration = 20.milliseconds),
            )),
        )
        val binding = fixture.factory.bindRecording(fixture.currentSession, DvrEntryId(7)).requireBound()

        assertEquals(40.milliseconds, binding.declaredVideoFrameDuration)
        listOf(
            listOf(DvrRecordingStream(SubscriptionStreamType.H264, frameDuration = Duration.ZERO)),
            listOf(
                DvrRecordingStream(SubscriptionStreamType.H264),
                DvrRecordingStream(SubscriptionStreamType.H265, frameDuration = 20.milliseconds),
            ),
            listOf(DvrRecordingStream(SubscriptionStreamType.AAC, frameDuration = 21.milliseconds)),
            null,
        ).forEach { streams ->
            fixture.updateCompletedProgress(generation, 37.seconds, files = files(streams))
            assertTrue(binding.admission is RecordingPlaybackAdmission.Completed)
            assertNull(binding.declaredVideoFrameDuration)
        }

        fixture.updateCompletedProgress(generation, 37.seconds, files = files(listOf(
            DvrRecordingStream(SubscriptionStreamType.H264, frameDuration = 40.milliseconds),
        )))
        assertEquals(40.milliseconds, binding.declaredVideoFrameDuration)
        fixture.publishReady(GatewayGeneration())
        assertSame(RecordingPlaybackAdmission.ObservationExpired, binding.admission)
        assertNull(binding.declaredVideoFrameDuration)
    }

    @Test
    fun `completed playback remains admitted without progress support and starts over`() =
        kotlinx.coroutines.test.runTest {
            listOf(
                RecordingProgressCapability.UNKNOWN to
                    DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false),
                RecordingProgressCapability.UNSUPPORTED to
                    DvrProgressResult.Failed(TvheadendFailure.NOT_SUPPORTED, mayHaveApplied = false),
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
                assertEquals(
                    expectedProgress,
                    binding.reportProgress(null, DvrPlaybackProgress.checkpoint(12.seconds)),
                )
                assertTrue(fixture.metadata.progressGenerations.isEmpty())
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["COMPLETED", "COMPLETED_ERROR", "MISSED"])
    fun `completed binding permanently rejects a same generation recording reincarnation`(stateName: String) =
        kotlinx.coroutines.test.runTest {
            val state = dvrState(stateName)
            val fixture = BindingFixture()
            val generation = GatewayGeneration()
            fixture.publishReady(generation, recordingState = state)
            val currentSession = fixture.currentSession
            val binding = fixture.factory
                .bindRecording(currentSession, DvrEntryId(7))
                .requireBound()

            fixture.updateCompletedProgress(generation, 48.seconds, state = state)
            assertSame(currentSession, fixture.currentSession)
            assertEquals(
                48.seconds.takeUnless { state == DvrEntryState.MISSED },
                (binding.admission as RecordingPlaybackAdmission.Completed).resumePosition,
            )

            fixture.replaceCompletedRecording(generation, state)
            assertSame(currentSession, fixture.currentSession)
            assertSame(RecordingPlaybackAdmission.TargetUnavailable, binding.admission)
            assertSame(
                at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure.FILE_UNAVAILABLE,
                (binding.openRecording() as RecordingFileResult.Failed).failure,
            )
            assertEquals(
                DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false),
                binding.reportProgress(null, DvrPlaybackProgress.checkpoint(12.seconds)),
            )
            assertEquals(DvrCutpointsResult.Failed(TvheadendFailure.NOT_READY), binding.cutpoints())
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
                assertEquals(DvrCutpointsResult.Failed(TvheadendFailure.NOT_SUPPORTED), binding.cutpoints())
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
                    at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure.FILE_UNAVAILABLE,
                    (binding.openRecording() as RecordingFileResult.Failed).failure,
                )
                assertEquals(
                    DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false),
                    binding.reportProgress(null, DvrPlaybackProgress.checkpoint(13.seconds)),
                )
                assertEquals(DvrCutpointsResult.Failed(TvheadendFailure.NOT_READY), binding.cutpoints())
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
                at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure.FILE_UNAVAILABLE,
                (binding.openRecording() as RecordingFileResult.Failed).failure,
            )
            assertEquals(
                DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false),
                binding.reportProgress(null, DvrPlaybackProgress.checkpoint(13.seconds)),
            )
            assertEquals(DvrCutpointsResult.Failed(TvheadendFailure.NOT_READY), binding.cutpoints())
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
    fun `growing admission carries saved progress only when progress is supported and positive`() =
        kotlinx.coroutines.test.runTest {
            listOf(
                RecordingProgressCapability.SUPPORTED to 37.seconds,
                RecordingProgressCapability.UNKNOWN to null,
                RecordingProgressCapability.UNSUPPORTED to null,
            ).forEach { (capability, expected) ->
                val generation = GatewayGeneration()
                val fixture = BindingFixture().apply {
                    publishReady(generation, capability, recordingState = DvrEntryState.RECORDING)
                }
                val binding = fixture.factory
                    .bindRecording(fixture.currentSession, DvrEntryId(7))
                    .requireBound()
                val admission = binding.admission as RecordingPlaybackAdmission.Growing

                assertEquals(expected, admission.resumePosition, "$capability")
                assertSame(capability, admission.progressCapability)

                fixture.metadata.acceptMetadata(
                    MetadataEvent.DvrEntryUpdated(
                        generation,
                        recordingEntry(state = DvrEntryState.RECORDING, playPosition = kotlin.time.Duration.ZERO),
                    ),
                )
                assertNull(
                    (binding.admission as RecordingPlaybackAdmission.Growing).resumePosition,
                    "$capability",
                )
            }
        }

    @Test
    fun `ended recordings with a failure play only when they left a file`() =
        kotlinx.coroutines.test.runTest {
            data class Case(
                val name: String,
                val state: DvrEntryState,
                val files: List<GatewayDvrRecordingFile>?,
                val dataSizeBytes: Long?,
                val playable: Boolean,
                val failure: GatewayDvrFailure? = null,
            )
            listOf(
                Case("failed with file", DvrEntryState.COMPLETED_ERROR, listOf(recordingFile()), 100L, true),
                Case("failed with unknown size", DvrEntryState.COMPLETED_ERROR, listOf(recordingFile()), null, true),
                Case(
                    "failure reported on completion",
                    DvrEntryState.COMPLETED,
                    listOf(recordingFile()),
                    100L,
                    true,
                    GatewayDvrFailure.PRESENT,
                ),
                Case("failed with empty data", DvrEntryState.COMPLETED_ERROR, listOf(recordingFile()), 0L, false),
                Case("failed without files", DvrEntryState.COMPLETED_ERROR, null, 100L, false),
                Case("failed with empty files", DvrEntryState.COMPLETED_ERROR, emptyList(), 100L, false),
                Case("missed with file", DvrEntryState.MISSED, listOf(recordingFile()), 100L, true),
                Case("missed without files", DvrEntryState.MISSED, null, null, false),
                Case(
                    "file missing",
                    DvrEntryState.COMPLETED,
                    listOf(recordingFile()),
                    100L,
                    false,
                    GatewayDvrFailure.FILE_MISSING,
                ),
            ).forEach { case ->
                val fixture = BindingFixture().apply {
                    publishReady(
                        GatewayGeneration(),
                        recordingState = case.state,
                        recordingFiles = case.files,
                        recordingFailure = case.failure,
                        recordingDataSizeBytes = case.dataSizeBytes,
                    )
                }
                val binding = fixture.factory
                    .bindRecording(fixture.currentSession, DvrEntryId(7))
                    .requireBound()

                assertFalse(binding.startedGrowing, case.name)
                if (case.playable) {
                    val admission = binding.admission as? RecordingPlaybackAdmission.Completed
                    val expectedResume = if (case.state == DvrEntryState.MISSED) null else 37.seconds
                    assertTrue(admission != null, case.name)
                    assertEquals(expectedResume, admission?.resumePosition, case.name)
                    assertTrue(binding.openRecording() is RecordingFileResult.Ok, case.name)
                    assertEquals(1, fixture.children.recordingGenerations.size, case.name)
                } else {
                    assertSame(RecordingPlaybackAdmission.TargetUnavailable, binding.admission, case.name)
                    assertTrue(binding.openRecording() is RecordingFileResult.Failed, case.name)
                    assertTrue(fixture.children.recordingGenerations.isEmpty(), case.name)
                }
            }
        }

    @Test
    fun `in place updates of an ended recording follow its file and incarnation`() {
        data class Step(val entry: GatewayDvrEntry, val playable: Boolean)
        fun ended(
            state: DvrEntryState,
            failure: GatewayDvrFailure? = null,
            files: List<GatewayDvrRecordingFile>? = listOf(recordingFile()),
        ) = recordingEntry(state = state, failure = failure, files = files, dataSizeBytes = 100L)
        listOf(
            "failed then file missing" to listOf(
                Step(ended(DvrEntryState.COMPLETED_ERROR), true),
                Step(ended(DvrEntryState.COMPLETED, GatewayDvrFailure.FILE_MISSING), false),
            ),
            "failed updated again" to listOf(
                Step(ended(DvrEntryState.COMPLETED_ERROR), true),
                Step(ended(DvrEntryState.COMPLETED_ERROR), true),
            ),
            // Losing the file ends the incarnation; restoring it never revives the binding.
            "missed loses then regains its file" to listOf(
                Step(ended(DvrEntryState.MISSED), true),
                Step(ended(DvrEntryState.MISSED, files = emptyList()), false),
                Step(ended(DvrEntryState.MISSED), false),
            ),
            "completed then failed on the same file" to listOf(
                Step(ended(DvrEntryState.COMPLETED), true),
                Step(ended(DvrEntryState.COMPLETED, GatewayDvrFailure.PRESENT), true),
            ),
            "growing then missed with its file" to listOf(
                Step(ended(DvrEntryState.RECORDING), true),
                Step(ended(DvrEntryState.MISSED), true),
            ),
        ).forEach { (name, steps) ->
            val generation = GatewayGeneration()
            val fixture = BindingFixture().apply {
                publishReady(
                    generation,
                    recordingState = steps.first().entry.state!!,
                    recordingDataSizeBytes = 100L,
                )
            }
            val binding = fixture.factory
                .bindRecording(fixture.currentSession, DvrEntryId(7))
                .requireBound()
            steps.forEachIndexed { index, step ->
                if (index > 0) {
                    fixture.metadata.acceptMetadata(MetadataEvent.DvrEntryUpdated(generation, step.entry))
                }
                val admission = binding.admission
                if (step.playable) {
                    val expected = if (index == 0 && step.entry.state == DvrEntryState.RECORDING) {
                        RecordingPlaybackAdmission.Growing::class
                    } else {
                        RecordingPlaybackAdmission.Completed::class
                    }
                    assertEquals(expected, admission::class, "$name step $index")
                } else {
                    assertSame(RecordingPlaybackAdmission.TargetUnavailable, admission, "$name step $index")
                }
            }
        }
    }

    @Test
    fun `recording error is admitted like an active recording`() {
        listOf(
            "/recording.ts" to RecordingPlaybackAdmission.Growing::class,
            "/recording.mkv" to RecordingPlaybackAdmission.GrowingDeferred::class,
            " " to RecordingPlaybackAdmission.TargetUnavailable::class,
        ).forEach { (path, expected) ->
            val fixture = BindingFixture().apply {
                publishReady(
                    GatewayGeneration(),
                    recordingState = DvrEntryState.RECORDING_ERROR,
                    recordingPath = path,
                    recordingFiles = listOf(recordingFile(path = path)),
                )
            }
            val binding = fixture.factory
                .bindRecording(fixture.currentSession, DvrEntryId(7))
                .requireBound()

            assertTrue(binding.startedGrowing, path)
            assertEquals(expected, binding.admission::class, path)
        }
    }

    @Test
    fun `growing playback that ends with a failure continues as a completed recording`() {
        listOf(DvrEntryState.RECORDING, DvrEntryState.RECORDING_ERROR).forEach { startState ->
            val generation = GatewayGeneration()
            val fixture = BindingFixture().apply {
                publishReady(generation, recordingState = startState, recordingDataSizeBytes = 100L)
            }
            val binding = fixture.factory
                .bindRecording(fixture.currentSession, DvrEntryId(7))
                .requireBound()
            assertTrue(binding.admission is RecordingPlaybackAdmission.Growing, "$startState")

            fixture.metadata.acceptMetadata(
                MetadataEvent.DvrEntryUpdated(
                    generation,
                    recordingEntry(
                        state = DvrEntryState.COMPLETED,
                        failure = GatewayDvrFailure.PRESENT,
                        dataSizeBytes = 100L,
                    ),
                ),
            )

            assertTrue(binding.startedGrowing, "$startState")
            assertTrue(binding.admission is RecordingPlaybackAdmission.Completed, "$startState")
        }
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
                assertEquals(
                    DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false),
                    binding.reportProgress(lease, progress),
                )
            }
            assertTrue(fixture.metadata.progressLeases.isEmpty())

            val exactLease = TestGrowingLease(generation, DvrEntryId(7))
            assertSame(DvrProgressResult.Accepted, binding.reportProgress(exactLease, progress))
            assertEquals(listOf(exactLease), fixture.metadata.progressLeases)
        }

    @Test
    fun `binding admission equals the observation predicate for the same observation`() {
        data class Case(
            val name: String,
            val state: DvrEntryState,
            val path: String,
            val expected: KClass<*>,
        )
        listOf(
            Case("growing", DvrEntryState.RECORDING, "/recording.ts", RecordingPlaybackAdmission.Growing::class),
            Case(
                "growing error",
                DvrEntryState.RECORDING_ERROR,
                "/recording.TS",
                RecordingPlaybackAdmission.Growing::class,
            ),
            Case(
                "deferred",
                DvrEntryState.RECORDING,
                "/recording.mkv",
                RecordingPlaybackAdmission.GrowingDeferred::class,
            ),
            Case("completed", DvrEntryState.COMPLETED, "/recording.ts", RecordingPlaybackAdmission.Completed::class),
            Case(
                "blank path",
                DvrEntryState.RECORDING_ERROR,
                " ",
                RecordingPlaybackAdmission.TargetUnavailable::class,
            ),
        ).forEach { case ->
            RecordingProgressCapability.entries.forEach { capability ->
                val label = "${case.name} / $capability"
                val fixture = BindingFixture().apply {
                    publishReady(
                        GatewayGeneration(),
                        capability,
                        recordingState = case.state,
                        recordingPath = case.path,
                        recordingFiles = listOf(recordingFile(path = case.path)),
                    )
                }
                val binding = fixture.factory
                    .bindRecording(fixture.currentSession, DvrEntryId(7))
                    .requireBound()
                val observation = fixture.metadata.observation.value
                val entry = requireNotNull(observation.dvrEntry(DvrEntryId(7)))
                val predicate = observation.recordingPlaybackAdmission(entry)

                assertEquals(case.expected, predicate::class, label)
                assertSameAdmission(predicate, binding.admission, label)
            }
        }
    }

    private fun assertSameAdmission(
        expected: RecordingPlaybackAdmission,
        actual: RecordingPlaybackAdmission,
        label: String,
    ) {
        assertEquals(expected::class, actual::class, label)
        when (expected) {
            is RecordingPlaybackAdmission.Completed -> {
                actual as RecordingPlaybackAdmission.Completed
                assertEquals(expected.resumePosition, actual.resumePosition, label)
                assertSame(expected.progressCapability, actual.progressCapability, label)
            }
            is RecordingPlaybackAdmission.Growing -> {
                actual as RecordingPlaybackAdmission.Growing
                assertEquals(expected.resumePosition, actual.resumePosition, label)
                assertSame(expected.progressCapability, actual.progressCapability, label)
            }
            else -> assertSame(expected, actual, label)
        }
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
        val growing = BindingFixture().apply {
            publishReady(GatewayGeneration(), recordingState = DvrEntryState.RECORDING)
        }
        assertEquals(
            "RecordingPlaybackAdmission.Growing(<redacted>)",
            growing.factory
                .bindRecording(growing.currentSession, DvrEntryId(7))
                .requireBound()
                .admission
                .toString(),
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
        recordingFailure: GatewayDvrFailure? = null,
        recordingDataSizeBytes: Long? = null,
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
                    failure = recordingFailure,
                    dataSizeBytes = recordingDataSizeBytes,
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
        state: DvrEntryState = DvrEntryState.COMPLETED,
    ) {
        metadata.acceptMetadata(
            MetadataEvent.DvrEntryUpdated(
                generation,
                recordingEntry(
                    state = state,
                    playPosition = position,
                    uuid = uuid,
                    path = path,
                    files = files,
                ),
            ),
        )
    }

    fun replaceCompletedRecording(generation: GatewayGeneration, state: DvrEntryState = DvrEntryState.COMPLETED) {
        metadata.acceptMetadata(MetadataEvent.DvrEntryDeleted(generation, GatewayDvrEntryId(7)))
        metadata.acceptMetadata(
            MetadataEvent.DvrEntryAdded(
                generation,
                recordingEntry(
                    state = state,
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
    failure: GatewayDvrFailure? = null,
    dataSizeBytes: Long? = null,
): GatewayDvrEntry = GatewayDvrEntry(
    id = GatewayDvrEntryId(7),
    uuid = uuid,
    playPosition = playPosition,
    files = files,
    path = path,
    state = state,
    failure = failure,
    dataSizeBytes = dataSizeBytes,
)

private fun recordingFile(
    fileId: Long = 1,
    path: String? = "/recording.ts",
    streams: List<DvrRecordingStream>? = null,
): GatewayDvrRecordingFile = GatewayDvrRecordingFile(
    fileId = fileId,
    path = path,
    start = null,
    stop = null,
    sizeBytes = 1,
    streams = streams,
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
        return DvrCutpointsResult.Failed(TvheadendFailure.NOT_SUPPORTED)
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

private fun dvrState(name: String): DvrEntryState =
    listOf(
        DvrEntryState.RECORDING,
        DvrEntryState.RECORDING_ERROR,
        DvrEntryState.COMPLETED,
        DvrEntryState.COMPLETED_ERROR,
        DvrEntryState.MISSED,
    ).single { state -> state.toString() == name }
