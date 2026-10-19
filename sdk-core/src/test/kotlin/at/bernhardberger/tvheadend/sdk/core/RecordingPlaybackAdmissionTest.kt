@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
    TvheadendTestingApi::class,
)

package at.bernhardberger.tvheadend.sdk.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class RecordingPlaybackAdmissionTest {
    private enum class Outcome { GROWING, GROWING_DEFERRED, COMPLETED, TARGET_UNAVAILABLE }

    private class Case(
        val name: String,
        val state: DvrEntryState?,
        val files: List<DvrRecordingFile>?,
        val outcome: Outcome,
        val dataSizeBytes: Long? = null,
    )

    private fun file(path: String) = TvheadendTestResultFactory.recordingFile(fileId = 1, serverPath = path)

    private val cases = listOf(
        Case("recording ts", DvrEntryState.RECORDING, listOf(file("/rec/a.ts")), Outcome.GROWING),
        Case("recording error ts", DvrEntryState.RECORDING_ERROR, listOf(file("/rec/a.ts")), Outcome.GROWING),
        Case("recording upper-case ts", DvrEntryState.RECORDING, listOf(file("/rec/A.TS")), Outcome.GROWING),
        Case("recording mkv", DvrEntryState.RECORDING, listOf(file("/rec/a.mkv")), Outcome.GROWING_DEFERRED),
        Case(
            "recording error mkv",
            DvrEntryState.RECORDING_ERROR,
            listOf(file("/rec/a.mkv")),
            Outcome.GROWING_DEFERRED,
        ),
        Case("recording blank path", DvrEntryState.RECORDING, listOf(file("  ")), Outcome.TARGET_UNAVAILABLE),
        Case("recording error blank path", DvrEntryState.RECORDING_ERROR, listOf(file("")), Outcome.TARGET_UNAVAILABLE),
        Case(
            "recording error without path",
            DvrEntryState.RECORDING_ERROR,
            listOf(DvrRecordingFile.create(fileId = 1)),
            Outcome.TARGET_UNAVAILABLE,
        ),
        Case(
            "recording two files",
            DvrEntryState.RECORDING,
            listOf(file("/rec/a.ts"), file("/rec/b.ts")),
            Outcome.TARGET_UNAVAILABLE,
        ),
        Case("recording without files", DvrEntryState.RECORDING, null, Outcome.TARGET_UNAVAILABLE),
        Case("recording empty files", DvrEntryState.RECORDING, emptyList(), Outcome.TARGET_UNAVAILABLE),
        Case("completed", DvrEntryState.COMPLETED, listOf(file("/rec/a.mkv")), Outcome.COMPLETED),
        Case("completed without files", DvrEntryState.COMPLETED, null, Outcome.COMPLETED),
        Case("failed with file", DvrEntryState.COMPLETED_ERROR, listOf(file("/rec/a.ts")), Outcome.COMPLETED, 100L),
        Case("failed with unknown size", DvrEntryState.COMPLETED_ERROR, listOf(file("/rec/a.ts")), Outcome.COMPLETED),
        Case(
            "failed with empty data",
            DvrEntryState.COMPLETED_ERROR,
            listOf(file("/rec/a.ts")),
            Outcome.TARGET_UNAVAILABLE,
            0L,
        ),
        Case("failed without files", DvrEntryState.COMPLETED_ERROR, null, Outcome.TARGET_UNAVAILABLE, 100L),
        Case("failed with empty files", DvrEntryState.COMPLETED_ERROR, emptyList(), Outcome.TARGET_UNAVAILABLE, 100L),
        Case("missed with file", DvrEntryState.MISSED, listOf(file("/rec/a.ts")), Outcome.COMPLETED, 100L),
        Case(
            "missed with empty data",
            DvrEntryState.MISSED,
            listOf(file("/rec/a.ts")),
            Outcome.TARGET_UNAVAILABLE,
            0L,
        ),
        Case("missed without files", DvrEntryState.MISSED, null, Outcome.TARGET_UNAVAILABLE),
        Case("scheduled", DvrEntryState.SCHEDULED, listOf(file("/rec/a.ts")), Outcome.TARGET_UNAVAILABLE),
        Case("unknown state", null, listOf(file("/rec/a.ts")), Outcome.TARGET_UNAVAILABLE),
    )

    @Test
    fun `admission covers every state and file shape`() {
        RecordingProgressCapability.entries.forEach { capability ->
            val observation = SessionObservation.create(recordingProgressCapability = capability)
            cases.forEach { case ->
                val label = "${case.name} / $capability"
                val admission = observation.recordingPlaybackAdmission(entry(case))
                when (case.outcome) {
                    Outcome.GROWING -> {
                        val growing = admission as RecordingPlaybackAdmission.Growing
                        assertSame(capability, growing.progressCapability, label)
                        assertEquals(expectedResume(case.state, capability), growing.resumePosition, label)
                    }
                    Outcome.COMPLETED -> {
                        val completed = admission as RecordingPlaybackAdmission.Completed
                        assertSame(capability, completed.progressCapability, label)
                        assertEquals(expectedResume(case.state, capability), completed.resumePosition, label)
                    }
                    Outcome.GROWING_DEFERRED ->
                        assertSame(RecordingPlaybackAdmission.GrowingDeferred, admission, label)
                    Outcome.TARGET_UNAVAILABLE ->
                        assertSame(RecordingPlaybackAdmission.TargetUnavailable, admission, label)
                }
            }
        }
    }

    @Test
    fun `missed entries never carry a resume position`() {
        val observation = SessionObservation.create(recordingProgressCapability = RecordingProgressCapability.SUPPORTED)
        val admission = observation.recordingPlaybackAdmission(
            entry(Case("missed", DvrEntryState.MISSED, listOf(file("/rec/a.ts")), Outcome.COMPLETED, 100L)),
        )

        assertNull((admission as RecordingPlaybackAdmission.Completed).resumePosition)
    }

    @Test
    fun `resume position needs supported progress and a positive saved position`() {
        listOf(DvrEntryState.RECORDING, DvrEntryState.COMPLETED).forEach { state ->
            listOf(null, Duration.ZERO).forEach { position ->
                val observation = SessionObservation.create(
                    recordingProgressCapability = RecordingProgressCapability.SUPPORTED,
                )
                val admission = observation.recordingPlaybackAdmission(
                    entry(Case("position", state, listOf(file("/rec/a.ts")), Outcome.GROWING), position),
                )

                assertNull(resumeOf(admission), "$state $position")
            }
        }
    }

    @Test
    fun `admission never reports an expired observation`() {
        val observation = SessionObservation.create(recordingProgressCapability = RecordingProgressCapability.SUPPORTED)

        cases.forEach { case ->
            val admission = observation.recordingPlaybackAdmission(entry(case))
            assertTrue(admission !== RecordingPlaybackAdmission.ObservationExpired, case.name)
        }
    }

    @Test
    fun `entry path is the fallback when the single file has none`() {
        val observation = SessionObservation.create(recordingProgressCapability = RecordingProgressCapability.SUPPORTED)
        val withoutFilePath = listOf(DvrRecordingFile.create(fileId = 1))

        listOf(
            "/rec/a.ts" to RecordingPlaybackAdmission.Growing::class,
            "/rec/a.mkv" to RecordingPlaybackAdmission.GrowingDeferred::class,
            " " to RecordingPlaybackAdmission.TargetUnavailable::class,
        ).forEach { (entryPath, expected) ->
            listOf(DvrEntryState.RECORDING, DvrEntryState.RECORDING_ERROR).forEach { state ->
                val admission = observation.recordingPlaybackAdmission(
                    entryWithPath(state, withoutFilePath, entryPath),
                )
                assertEquals(expected, admission::class, "$state $entryPath")
            }
        }
        // A file path wins over the entry path.
        val admission = observation.recordingPlaybackAdmission(
            entryWithPath(DvrEntryState.RECORDING, listOf(file("/rec/a.mkv")), "/rec/a.ts"),
        )
        assertSame(RecordingPlaybackAdmission.GrowingDeferred, admission)
    }

    private fun entryWithPath(state: DvrEntryState, files: List<DvrRecordingFile>, path: String): DvrEntry =
        DvrEntry.create(
            id = DvrEntryId(7), uuid = null, enabled = null, channelId = null, channelName = null,
            eventId = null, autorecRuleId = null, timerecRuleId = null, start = null, stop = null,
            startExtraMinutes = null, stopExtraMinutes = null, retentionDays = null, removalDays = null,
            priority = null, contentGenre = null, rating = null, playCount = null, playPosition = null,
            episode = null, title = null, description = null, summary = null, subtitle = null,
            owner = null, creator = null, comment = null, image = null, fanartImage = null,
            copyrightYear = null, files = files, path = path, configId = null, duplicate = null,
            state = state, subscriptionError = null, streamErrors = null, dataErrors = null,
            dataSizeBytes = null,
        )

    private fun entry(case: Case, playPosition: Duration? = SAVED_POSITION): DvrEntry = DvrEntry.create(
        id = DvrEntryId(7),
        state = case.state,
        files = case.files,
        dataSizeBytes = case.dataSizeBytes,
        playPosition = playPosition,
    )

    private fun expectedResume(state: DvrEntryState?, capability: RecordingProgressCapability): Duration? =
        SAVED_POSITION.takeIf { capability == RecordingProgressCapability.SUPPORTED && state != DvrEntryState.MISSED }

    private fun resumeOf(admission: RecordingPlaybackAdmission): Duration? = when (admission) {
        is RecordingPlaybackAdmission.Growing -> admission.resumePosition
        is RecordingPlaybackAdmission.Completed -> admission.resumePosition
        else -> null
    }

    private companion object {
        val SAVED_POSITION = 37.seconds
    }
}
