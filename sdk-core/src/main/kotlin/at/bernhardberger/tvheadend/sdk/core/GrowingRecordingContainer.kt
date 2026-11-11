package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi

/** Selects the container adapter for [RecordingPlaybackAdmission.Growing]. */
@ExperimentalTvheadendApi
public enum class GrowingRecordingContainer {
    /** Reads MPEG transport streams. */
    MPEG_TS,
    /** Reads Matroska streams. */
    MATROSKA,
}
