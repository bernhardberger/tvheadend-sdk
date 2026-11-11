@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.extractor.BinarySearchSeeker
import androidx.media3.extractor.Extractor
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease

/** Container-specific parsing and seek evidence for the shared growing-recording path. */
internal interface GrowingContainerAdapter<E : GrowingRecordingExtent> {
    val deliveryUnitBytes: Int
    val seekValidatedVideoMimeTypes: Set<String>
    val needsFormatlessTrackFilter: Boolean get() = true
    val supportsObservedIndex: Boolean get() = true
    fun createExtractor(): Extractor
    fun alignIndexPosition(position: Long): Long
    fun createExtentProbe(lease: GrowingRecordingFileLease): GrowingRecordingExtentProbe<E>
    fun isContinuous(previous: E, next: E): Boolean
    fun createBinarySearchSeeker(extent: E): BinarySearchSeeker
}

/** A measured finite byte window; container-specific timestamp origins remain in the adapter. */
internal interface GrowingRecordingExtent {
    val sizeBytes: Long
    val durationUs: Long
    val isFinal: Boolean
}

/** Owns independent readers for bounded, non-blocking-on-growth extent samples. */
internal interface GrowingRecordingExtentProbe<E : GrowingRecordingExtent> {
    suspend fun sample(): E?
    suspend fun close()
}
