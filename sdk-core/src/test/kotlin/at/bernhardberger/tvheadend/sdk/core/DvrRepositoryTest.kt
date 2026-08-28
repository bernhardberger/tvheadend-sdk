@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal class DvrRepositoryTest {
    @Test
    fun `models validate wire domains timing and immutable collections`() {
        val files = mutableListOf(
            DvrRecordingFile(
                fileId = 1,
                path = "/private/recording.ts",
                start = instant(10),
                stop = instant(20),
                sizeBytes = 4,
            ),
        )
        val entry = DvrEntry.create(
            id = DvrEntryId(0xffff_ffffL),
            uuid = "private-uuid",
            enabled = true,
            channelId = ChannelId(1),
            channelName = "private-channel",
            eventId = EventId(2),
            autorecRuleId = AutorecRuleId("private-autorec"),
            timerecRuleId = TimerecRuleId("private-timerec"),
            start = instant(10),
            stop = instant(20),
            startExtraMinutes = 3,
            stopExtraMinutes = 4,
            contentType = 5,
            rating = EpgRating(12, "private-rating", null, null, null, null),
            playCount = 1,
            playPosition = 30.seconds,
            episode = EpgEpisode(null, null, 1, null, 2, 3, null, null, null),
            title = "private-title",
            files = files,
            path = "/private/path.ts",
            configId = DvrConfigId("private-config"),
            state = DvrEntryState.SCHEDULED,
            subscriptionError = DvrSubscriptionError.SCRAMBLED,
        )
        files.clear()

        assertEquals(1, entry.files?.size)
        assertEquals("/private/recording.ts", entry.files?.single()?.path)
        assertThrows(UnsupportedOperationException::class.java) {
            (entry.files as MutableList<DvrRecordingFile>).clear()
        }
        assertThrows(IllegalArgumentException::class.java) {
            DvrEntry.create(DvrEntryId(1), start = instant(20), stop = instant(19))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DvrRecordingFile(fileId = -1, path = null, start = null, stop = null, sizeBytes = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimerecRule.create(TimerecRuleId("rule"), startMinutesSinceMidnight = 1_441)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutorecRule.create(AutorecRuleId("rule"), retentionDays = -1)
        }
        assertFalse(
            listOf(entry, entry.files?.single(), entry.rating, entry.episode).joinToString()
                .contains("private"),
            "DVR model rendering exposed recording metadata",
        )
        assertFalse(entry.toString().contains("/private"), "DVR model rendering exposed a path")
    }

    @Test
    fun `mutation requests validate protocol domains and redact schedule metadata`() {
        val requests = listOf(
            DvrSchedule.Programme(EventId(1)),
            DvrSchedule.ExplicitTime(ChannelId(2), instant(-3), instant(4)),
            DvrScheduleRequest(
                schedule = DvrSchedule.Programme(EventId(1)),
                configId = DvrConfigId("private-config"),
                title = "private-title",
                description = "private-description",
                ageRating = 12,
            ),
            DvrEntryUpdate(title = "private-title", start = instant(-3), stop = instant(4)),
            RecordingRuleChannel.SpecificChannel(ChannelId(2)),
            AutorecRuleCreate(
                title = "private-title",
                minDuration = 60.seconds,
                configId = DvrConfigId("private-config"),
            ),
            AutorecRuleUpdate(title = "private-title", maxDuration = 120.seconds),
            TimerecRuleCreate(title = "private-title", startMinutesSinceMidnight = 60),
            TimerecRuleUpdate(title = "private-title", stopMinutesSinceMidnight = 120),
        )

        assertTrue(requests.all { request -> request.toString().contains("<redacted>") })
        assertFalse(requests.joinToString().contains("private"))
        assertThrows(IllegalArgumentException::class.java) {
            DvrSchedule.ExplicitTime(ChannelId(1), instant(2), instant(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DvrScheduleRequest(DvrSchedule.Programme(EventId(1)), ageRating = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DvrEntryUpdate(start = instant(2), stop = instant(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DvrEntryUpdate(retentionDays = 0x1_0000_0000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutorecRuleCreate(title = "title", minDuration = 500.milliseconds)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutorecRuleUpdate(startMinutesSinceMidnight = 1_441)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimerecRuleCreate(title = "title", stopMinutesSinceMidnight = -1)
        }
    }

    @Test
    fun `confirmed mutation results redact returned identifiers`() {
        val confirmed = DvrMutationResult.Confirmed(DvrEntryId(7))
        val accepted = DvrMutationResult.AcceptedButUnconfirmed(AutorecRuleId("private-id"))

        assertEquals("DvrMutationResult.Confirmed(<redacted>)", confirmed.toString())
        assertEquals("DvrMutationResult.AcceptedButUnconfirmed(<redacted>)", accepted.toString())
        assertFalse(listOf(confirmed, accepted).joinToString().contains("private"))
    }

    @Test
    fun `progress reports stay command-only and do not change repository state`() = runTest {
        val accepted = DvrProgressResult.Accepted
        val currentSession = currentSession()
        val generation = GatewayGeneration()
        var reportedLease: GrowingRecordingFileLease? = null
        val commands = object : DvrProgressCommands {
            override suspend fun reportProgress(
                generation: GatewayGeneration,
                id: DvrEntryId,
                progress: DvrPlaybackProgress,
            ): DvrProgressResult = accepted

            override suspend fun reportProgress(
                lease: GrowingRecordingFileLease,
                progress: DvrPlaybackProgress,
            ): DvrProgressResult {
                reportedLease = lease
                return accepted
            }
        }
        val repository = TestDvrRepository(
            progressCommands = commands,
            currentSession = currentSession,
            generation = generation,
        )
        assertSame(
            accepted,
            repository.reportProgress(
                currentSession,
                DvrEntryId(1),
                DvrPlaybackProgress.checkpoint(30.seconds),
            ),
        )
        val lease = TestGrowingRecordingLease()
        assertSame(
            accepted,
            repository.reportProgress(lease, DvrPlaybackProgress.checkpoint(30.seconds)),
        )
        assertSame(lease, reportedLease)
        assertSame(
            DvrProgressResult.NotReady,
            TestDvrRepository(currentSession = currentSession).reportProgress(
                currentSession,
                DvrEntryId(1),
                DvrPlaybackProgress.checkpoint(30.seconds),
            ),
        )
    }

    @Test
    fun `cutpoint queries stay command only and default to not ready`() = runTest {
        val currentSession = currentSession()
        val expected = DvrCutpointsResult.Available.create(
            listOf(
                DvrCutpoint(
                    1.seconds,
                    2.seconds,
                    DvrCutpointAction.COMMERCIAL_BREAK,
                ),
            ),
        )
        val commands = object : DvrCutpointCommands {
            override suspend fun getCutpoints(
                generation: GatewayGeneration,
                id: DvrEntryId,
            ): DvrCutpointsResult = expected
        }
        val repository = TestDvrRepository(
            cutpointCommands = commands,
            currentSession = currentSession,
        )
        assertSame(expected, repository.cutpoints(currentSession, DvrEntryId(1)))
        assertSame(
            DvrCutpointsResult.NotReady,
            TestDvrRepository(currentSession = currentSession).cutpoints(
                currentSession,
                DvrEntryId(1),
            ),
        )
    }

    private class TestDvrRepository(
        progressCommands: DvrProgressCommands = DvrProgressCommands.None,
        cutpointCommands: DvrCutpointCommands = DvrCutpointCommands.None,
        currentSession: CurrentSessionObservation? = null,
        generation: GatewayGeneration = GatewayGeneration(),
    ) : CommandBackedDvrRepository(
        progressCommands = progressCommands,
        cutpointCommands = cutpointCommands,
        resolveGeneration = { capability -> generation.takeIf { capability === currentSession } },
    )

    private fun currentSession(): CurrentSessionObservation = requireNotNull(
        SessionObservation.create(
            sessionState = SessionState.Ready(
                ServerCapabilities.create(CapabilityAccess.UNKNOWN, CapabilityAccess.UNKNOWN),
            ),
            channelState = ChannelRepositoryState.Current(ChannelCatalog.create()),
            epgState = EpgRepositoryState.Current(EpgSnapshot.create()),
            dvrState = DvrRepositoryState.Current(DvrSnapshot.create()),
        ).currentSession,
    )

    private fun instant(seconds: Long): Instant = Instant.fromEpochSeconds(seconds)
}

private class TestGrowingRecordingLease : GrowingRecordingFileLease {
    override val isCurrent: Boolean = true

    override suspend fun open(position: Long): RecordingFileResult<GrowingRecordingFileReader> =
        error("Test lease does not open recording files")
}
