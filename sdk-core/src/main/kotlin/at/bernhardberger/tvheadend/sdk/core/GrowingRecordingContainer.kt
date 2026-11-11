package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi

/** Identifies formats supported for active [RecordingPlaybackAdmission.Growing] recordings. */
@ExperimentalTvheadendApi
public enum class GrowingRecordingContainer {
    /** Reads active `.ts` recordings. */
    MPEG_TS,
    /** Reads active `.mkv` recordings. */
    MATROSKA,
}
