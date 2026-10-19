package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.RecordingPlaybackAdmission
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.TvheadendTestResultFactory
import at.bernhardberger.tvheadend.sdk.core.TvheadendTestingApi
import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(TvheadendTestingApi::class, ExperimentalTvheadendApi::class)
internal class TvheadendTestResultFactoryTest {
    @Test
    fun `public admission factories preserve scripted resume positions`() {
        listOf(null, 42.seconds).forEach { resume ->
            val completed: RecordingPlaybackAdmission.Completed = TvheadendTestResultFactory.completedRecordingAdmission(
                resume, RecordingProgressCapability.SUPPORTED,
            )
            val growing: RecordingPlaybackAdmission.Growing = TvheadendTestResultFactory.growingRecordingAdmission(
                resume, RecordingProgressCapability.SUPPORTED,
            )

            assertEquals(resume, completed.resumePosition)
            assertEquals(resume, growing.resumePosition)
            assertSame(RecordingProgressCapability.SUPPORTED, completed.progressCapability)
            assertSame(RecordingProgressCapability.SUPPORTED, growing.progressCapability)
            assertEquals("RecordingPlaybackAdmission.Completed(<redacted>)", completed.toString())
            assertEquals("RecordingPlaybackAdmission.Growing(<redacted>)", growing.toString())
        }
    }

    @Test
    fun `public admission factories preserve unknown and unsupported progress capability`() {
        listOf(RecordingProgressCapability.UNKNOWN, RecordingProgressCapability.UNSUPPORTED).forEach { capability ->
            val completed = TvheadendTestResultFactory.completedRecordingAdmission(null, capability)
            val growing = TvheadendTestResultFactory.growingRecordingAdmission(null, capability)

            assertSame(capability, completed.progressCapability)
            assertSame(capability, growing.progressCapability)
            assertEquals(null, completed.resumePosition)
            assertEquals(null, growing.resumePosition)
        }
    }

    @Test
    fun `recording file factory carries a write-only server path`() {
        val observation = SessionObservation.create(recordingProgressCapability = RecordingProgressCapability.SUPPORTED)
        fun admission(path: String) = observation.recordingPlaybackAdmission(
            DvrEntry.create(
                id = DvrEntryId(1),
                state = DvrEntryState.RECORDING,
                files = listOf(TvheadendTestResultFactory.recordingFile(serverPath = path)),
            ),
        )

        assertTrue(admission("/private/recordings/show.ts") is RecordingPlaybackAdmission.Growing)
        assertSame(RecordingPlaybackAdmission.GrowingDeferred, admission("/private/recordings/show.mkv"))
        assertSame(RecordingPlaybackAdmission.TargetUnavailable, admission(" "))

        val start = Instant.fromEpochSeconds(10)
        val stop = Instant.fromEpochSeconds(20)
        val file = TvheadendTestResultFactory.recordingFile(
            fileId = 3, serverPath = "/private/recordings/show.ts", start = start, stop = stop, sizeBytes = 99,
        )
        assertEquals(3L, file.fileId)
        assertEquals(start, file.start)
        assertEquals(stop, file.stop)
        assertEquals(99L, file.sizeBytes)
        assertEquals("DvrRecordingFile(<redacted>)", file.toString())
        assertFalse(file.toString().contains("private"))
        assertFalse(DvrEntry.create(id = DvrEntryId(1), files = listOf(file)).toString().contains("private"))
    }
}
