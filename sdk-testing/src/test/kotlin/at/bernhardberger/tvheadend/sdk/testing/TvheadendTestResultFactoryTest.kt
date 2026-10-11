package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.RecordingPlaybackAdmission
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.core.TvheadendTestResultFactory
import at.bernhardberger.tvheadend.sdk.core.TvheadendTestingApi
import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
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
}
