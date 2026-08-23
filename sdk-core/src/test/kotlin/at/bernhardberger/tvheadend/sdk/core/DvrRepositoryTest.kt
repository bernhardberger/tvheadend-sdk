package at.bernhardberger.tvheadend.sdk.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    fun `all projections derive from one freshness state`() = runTest {
        val repository = TestDvrRepository()
        val entry = DvrEntry.create(id = DvrEntryId(1), title = "one")
        val autorec = AutorecRule.create(id = AutorecRuleId("auto"), title = "auto")
        val timerec = TimerecRule.create(id = TimerecRuleId("time"), title = "time")
        val snapshot = DvrSnapshot.create(listOf(entry), listOf(autorec), listOf(timerec))

        assertEquals(DvrRepositoryState.Empty, repository.state.value)
        assertEquals(emptyList<DvrEntry>(), repository.entries.value)

        repository.set(DvrRepositoryState.Synchronizing(snapshot))
        assertSame(snapshot.entries, repository.entries.value)
        assertSame(entry, repository.entry(DvrEntryId(1)).first())
        assertSame(autorec, repository.autorecRule(AutorecRuleId("auto")).first())
        assertSame(timerec, repository.timerecRule(TimerecRuleId("time")).first())

        repository.set(DvrRepositoryState.Current(DvrSnapshot.create()))
        assertEquals(emptyList<DvrEntry>(), repository.entries.value)
        assertEquals(null, repository.entry(DvrEntryId(1)).first())
        assertEquals(null, repository.autorecRule(AutorecRuleId("auto")).first())
        assertEquals(null, repository.timerecRule(TimerecRuleId("time")).first())
    }

    @Test
    fun `configuration and disk projections derive from independent freshness states`() = runTest {
        val repository = TestDvrRepository()
        val configuration = DvrConfiguration(DvrConfigId("private-config"), "Default", "comment")
        val diskSpace = DvrDiskSpace(freeBytes = 10, usedBytes = 4, totalBytes = 14)

        assertEquals(DvrConfigurationsState.Unknown, repository.configurationsState.value)
        assertEquals(emptyList<DvrConfiguration>(), repository.configurations.value)
        assertEquals(DvrDiskSpaceState.Unknown, repository.diskSpaceState.value)
        assertEquals(null, repository.diskSpace.value)

        repository.setConfigurations(DvrConfigurationsState.Synchronizing.create(listOf(configuration)))
        repository.setDiskSpace(DvrDiskSpaceState.Synchronizing(diskSpace))
        assertSame(configuration, repository.configuration(DvrConfigId("private-config")).first())
        assertEquals(diskSpace, repository.diskSpace.value)
        assertFalse(
            repository.configurations.value.toString().contains("private"),
            "Configuration rendering exposed a configuration identifier",
        )

        repository.setConfigurations(DvrConfigurationsState.Denied)
        repository.setDiskSpace(DvrDiskSpaceState.Unknown)
        assertEquals(emptyList<DvrConfiguration>(), repository.configurations.value)
        assertEquals(null, repository.configuration(DvrConfigId("private-config")).first())
        assertEquals(null, repository.diskSpace.value)

        val source = mutableListOf(configuration)
        val current = DvrConfigurationsState.Current.create(source)
        source.clear()
        assertEquals(listOf(configuration), current.configurations)
        assertThrows(UnsupportedOperationException::class.java) {
            (current.configurations as MutableList<DvrConfiguration>).clear()
        }
    }

    @Test
    fun `progress reports stay command-only and do not change repository state`() = runTest {
        val accepted = DvrProgressResult.Accepted
        val commands = object : DvrProgressCommands {
            override suspend fun reportProgress(
                id: DvrEntryId,
                progress: DvrPlaybackProgress,
            ): DvrProgressResult = accepted
        }
        val repository = TestDvrRepository(progressCommands = commands)
        val snapshot = DvrSnapshot.create(listOf(DvrEntry.create(DvrEntryId(1))))
        repository.set(DvrRepositoryState.Current(snapshot))

        assertSame(
            accepted,
            repository.reportProgress(DvrEntryId(1), DvrPlaybackProgress.checkpoint(30.seconds)),
        )
        assertSame(snapshot, (repository.state.value as DvrRepositoryState.Current).snapshot)
        assertSame(
            DvrProgressResult.NotReady,
            TestDvrRepository().reportProgress(
                DvrEntryId(1),
                DvrPlaybackProgress.checkpoint(30.seconds),
            ),
        )
    }

    @Test
    fun `cutpoint queries stay command only and default to not ready`() = runTest {
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
            override suspend fun getCutpoints(id: DvrEntryId): DvrCutpointsResult = expected
        }
        val repository = TestDvrRepository(cutpointCommands = commands)
        val snapshot = DvrSnapshot.create(listOf(DvrEntry.create(DvrEntryId(1))))
        repository.set(DvrRepositoryState.Current(snapshot))

        assertSame(expected, repository.cutpoints(DvrEntryId(1)))
        assertSame(snapshot, (repository.state.value as DvrRepositoryState.Current).snapshot)
        assertSame(DvrCutpointsResult.NotReady, TestDvrRepository().cutpoints(DvrEntryId(1)))
    }

    private class TestDvrRepository(
        progressCommands: DvrProgressCommands = DvrProgressCommands.None,
        cutpointCommands: DvrCutpointCommands = DvrCutpointCommands.None,
    ) : StateBackedDvrRepository(
        progressCommands = progressCommands,
        cutpointCommands = cutpointCommands,
    ) {
        private val mutableState = MutableStateFlow<DvrRepositoryState>(DvrRepositoryState.Empty)
        private val mutableConfigurations =
            MutableStateFlow<DvrConfigurationsState>(DvrConfigurationsState.Unknown)
        private val mutableDiskSpace = MutableStateFlow<DvrDiskSpaceState>(DvrDiskSpaceState.Unknown)
        override val state: StateFlow<DvrRepositoryState> = mutableState.asStateFlow()
        override val configurationsState: StateFlow<DvrConfigurationsState> =
            mutableConfigurations.asStateFlow()
        override val diskSpaceState: StateFlow<DvrDiskSpaceState> = mutableDiskSpace.asStateFlow()

        fun set(state: DvrRepositoryState) {
            mutableState.value = state
        }

        fun setConfigurations(state: DvrConfigurationsState) {
            mutableConfigurations.value = state
        }

        fun setDiskSpace(state: DvrDiskSpaceState) {
            mutableDiskSpace.value = state
        }
    }

    private fun instant(seconds: Long): Instant = Instant.fromEpochSeconds(seconds)
}
