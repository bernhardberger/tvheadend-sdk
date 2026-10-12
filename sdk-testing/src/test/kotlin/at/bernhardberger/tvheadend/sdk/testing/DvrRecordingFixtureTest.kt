@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrRecordingStream
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class DvrRecordingFixtureTest {
    @Test
    fun `recording fixtures publish audio-only and configurable stream snapshots without playback`() {
        val fake = FakeTvheadendSession()
        val snapshot = DvrRecordingFixture.create()
        fake.publish(SessionObservation.create(dvrState = DvrRepositoryState.Current(snapshot)))
        val published = (fake.observation.value.dvrState as DvrRepositoryState.Current).snapshot
        val audio = published.entries.last().files!!.single().streams!!.single()
        assertEquals(SubscriptionStreamType.MPEG2_AUDIO, audio.type)
        assertNull(audio.width)
        assertNull(audio.height)
        val streams = mutableListOf(DvrRecordingStream())
        val custom = DvrRecordingFixture.create(
            videoEntryId = DvrEntryId(3), audioEntryId = DvrEntryId(4),
            videoStreams = null, audioStreams = streams,
        )
        streams.clear()
        assertEquals(listOf(DvrEntryId(3), DvrEntryId(4)), custom.entries.map { it.id })
        assertNull(custom.entries.first().files!!.single().streams)
        assertEquals(listOf(DvrRecordingStream()), custom.entries.last().files!!.single().streams)
    }
}
