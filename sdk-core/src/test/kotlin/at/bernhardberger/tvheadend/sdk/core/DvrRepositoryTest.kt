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
import org.junit.jupiter.api.Test
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

    private class TestDvrRepository : StateBackedDvrRepository() {
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
