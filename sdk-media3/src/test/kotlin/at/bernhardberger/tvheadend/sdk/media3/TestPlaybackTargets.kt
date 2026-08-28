@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrProgressResult
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.RecordingFile
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import kotlin.time.Duration

internal class TestCoordinatorLiveTarget(
    var current: Boolean = true,
    private val openTarget: suspend (
        SubscriptionEventConsumer,
        SubscriptionOptions,
    ) -> SubscriptionOpenResult = { _, _ -> SubscriptionOpenResult.NotReady },
) : CoordinatorLiveTarget {
    override val isCurrent: Boolean
        get() = current

    override suspend fun open(
        consumer: SubscriptionEventConsumer,
        options: SubscriptionOptions,
    ): SubscriptionOpenResult = openTarget(consumer, options)
}

internal class TestCoordinatorRecordingTarget(
    override val startedGrowing: Boolean = false,
    var admissionState: CoordinatorRecordingAdmission = CoordinatorRecordingAdmission.Completed(
        resumePosition = null,
        progressCapability = RecordingProgressCapability.SUPPORTED,
    ),
    var recordingResult: RecordingFileResult<RecordingFile> =
        RecordingFileResult.Failed(RecordingFileFailure.NOT_SUPPORTED),
    var growingResult: RecordingFileResult<GrowingRecordingFileLease> =
        RecordingFileResult.Failed(RecordingFileFailure.NOT_SUPPORTED),
    var reportResult: DvrProgressResult = DvrProgressResult.Accepted,
) : CoordinatorRecordingTarget {
    var bindGrowingAction: () -> RecordingFileResult<GrowingRecordingFileLease> = { growingResult }
    val reports: MutableList<Pair<GrowingRecordingFileLease?, DvrPlaybackProgress>> = mutableListOf()

    override val admission: CoordinatorRecordingAdmission
        get() = admissionState

    override suspend fun openRecording(): RecordingFileResult<RecordingFile> = recordingResult

    override fun bindGrowingRecording(): RecordingFileResult<GrowingRecordingFileLease> =
        bindGrowingAction()

    override suspend fun reportProgress(
        growingLease: GrowingRecordingFileLease?,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult {
        reports += growingLease to progress
        return reportResult
    }
}

internal fun completedCoordinatorAdmission(
    resumePosition: Duration? = null,
    progressCapability: RecordingProgressCapability = RecordingProgressCapability.SUPPORTED,
): CoordinatorRecordingAdmission = CoordinatorRecordingAdmission.Completed(
    resumePosition,
    progressCapability,
)
