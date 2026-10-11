package at.bernhardberger.tvheadend.sdk.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class OpenClassificationTest {
    @Test
    fun `session failure kinds have distinct safe labels`() {
        assertLabels(listOf(
            SessionFailureKind.AUTHENTICATION_REJECTED to "AUTHENTICATION_REJECTED",
            SessionFailureKind.PERMISSION_DENIED to "PERMISSION_DENIED",
            SessionFailureKind.SERVER_UNREACHABLE to "SERVER_UNREACHABLE",
            SessionFailureKind.NETWORK_UNAVAILABLE to "NETWORK_UNAVAILABLE",
            SessionFailureKind.INCOMPATIBLE_SERVER to "INCOMPATIBLE_SERVER",
            SessionFailureKind.SERVER_UPGRADE_REQUIRED to "SERVER_UPGRADE_REQUIRED",
            SessionFailureKind.METADATA_OVERFLOW to "METADATA_OVERFLOW",
            SessionFailureKind.NO_CHANNELS to "NO_CHANNELS",
            SessionFailureKind.TRANSPORT_UNAVAILABLE to "TRANSPORT_UNAVAILABLE",
            SessionFailureKind.SYNCHRONIZATION_FAILED to "SYNCHRONIZATION_FAILED",
            SessionFailureKind.UNEXPECTED_FAILURE to "UNEXPECTED_FAILURE",
        ))
    }

    @Test
    fun `DVR change kinds have distinct safe labels`() {
        assertLabels(listOf(
            DvrChangeKind.SCHEDULED to "SCHEDULED",
            DvrChangeKind.RECORDING_STARTED to "RECORDING_STARTED",
            DvrChangeKind.RECORDING_COMPLETED to "RECORDING_COMPLETED",
            DvrChangeKind.RECORDING_STOPPED to "RECORDING_STOPPED",
            DvrChangeKind.RECORDING_ABORTED to "RECORDING_ABORTED",
            DvrChangeKind.RECORDING_FAILED to "RECORDING_FAILED",
            DvrChangeKind.MISSED to "MISSED",
            DvrChangeKind.REMOVED to "REMOVED",
        ))
    }

    @Test
    fun `DVR cutpoint actions have distinct safe labels`() {
        assertLabels(listOf(
            DvrCutpointAction.CUT to "CUT",
            DvrCutpointAction.MUTE to "MUTE",
            DvrCutpointAction.SCENE_MARKER to "SCENE_MARKER",
            DvrCutpointAction.COMMERCIAL_BREAK to "COMMERCIAL_BREAK",
            DvrCutpointAction.UNKNOWN to "UNKNOWN",
        ))
    }

    private fun <T : Any> assertLabels(cases: List<Pair<T, String>>) {
        assertEquals(cases.size, cases.map { it.first }.toSet().size)
        assertEquals(cases.size, cases.map { it.second }.toSet().size)
        cases.forEach { (value, label) -> assertEquals(label, value.toString()) }
    }
}
