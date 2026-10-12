package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrRecordingFile
import at.bernhardberger.tvheadend.sdk.core.DvrRecordingStream
import at.bernhardberger.tvheadend.sdk.core.DvrSnapshot
import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType

/** Recording metadata for application fakes, including an audio-only file without playback. */
@ExperimentalTvheadendApi
public object DvrRecordingFixture {
    /** Creates completed recordings with configurable per-file stream observations. */
    public fun create(
        videoEntryId: DvrEntryId = DvrEntryId(1),
        audioEntryId: DvrEntryId = DvrEntryId(2),
        videoStreams: List<DvrRecordingStream>? = listOf(
            DvrRecordingStream(SubscriptionStreamType.H264, width = 1920, height = 1080),
            DvrRecordingStream(SubscriptionStreamType.AAC, language = "eng"),
        ),
        audioStreams: List<DvrRecordingStream>? = listOf(
            DvrRecordingStream(SubscriptionStreamType.MPEG2_AUDIO, language = "eng"),
        ),
    ): DvrSnapshot = DvrSnapshot.create(
        entries = listOf(
            recording(videoEntryId, videoStreams),
            recording(audioEntryId, audioStreams),
        ),
    )

    private fun recording(id: DvrEntryId, streams: List<DvrRecordingStream>?): DvrEntry = DvrEntry.create(
        id = id,
        state = DvrEntryState.COMPLETED,
        files = listOf(DvrRecordingFile.create(fileId = 1, streams = streams)),
    )
}
