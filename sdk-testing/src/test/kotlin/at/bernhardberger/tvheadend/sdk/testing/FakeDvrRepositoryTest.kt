package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.AutorecRule
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.DvrConfigId
import at.bernhardberger.tvheadend.sdk.core.DvrConfiguration
import at.bernhardberger.tvheadend.sdk.core.DvrConfigurationsState
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpace
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpaceState
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrMutationResult
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSchedule
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.DvrSnapshot
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.TimerecRule
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

internal class FakeDvrRepositoryTest {
    @Test
    fun `fake scripts only complete public repository states`() = runTest {
        val entry = DvrEntry.create(id = DvrEntryId(8), title = "entry")
        val autorec = AutorecRule.create(id = AutorecRuleId("auto"))
        val timerec = TimerecRule.create(id = TimerecRuleId("time"))
        val snapshot = DvrSnapshot.create(listOf(entry), listOf(autorec), listOf(timerec))
        val repository = FakeDvrRepository(DvrRepositoryState.Synchronizing(snapshot))

        assertSame(snapshot.entries, repository.entries.value)
        assertSame(entry, repository.entry(DvrEntryId(8)).first())
        assertSame(autorec, repository.autorecRule(AutorecRuleId("auto")).first())
        assertSame(timerec, repository.timerecRule(TimerecRuleId("time")).first())

        repository.setState(DvrRepositoryState.Current(DvrSnapshot.create()))

        assertEquals(emptyList<DvrEntry>(), repository.entries.value)
        assertEquals(null, repository.entry(DvrEntryId(8)).first())
        assertEquals(null, repository.autorecRule(AutorecRuleId("auto")).first())
        assertEquals(DvrRepositoryState.Current(DvrSnapshot.create()), repository.state.value)
    }

    @Test
    fun `fake scripts configuration and disk freshness independently`() = runTest {
        val configuration = DvrConfiguration(DvrConfigId("config"), "Default", "")
        val diskSpace = DvrDiskSpace(4, 1, 5)
        val repository = FakeDvrRepository(
            initialConfigurationsState = DvrConfigurationsState.Synchronizing.create(listOf(configuration)),
            initialDiskSpaceState = DvrDiskSpaceState.Synchronizing(diskSpace),
        )

        assertSame(configuration, repository.configuration(DvrConfigId("config")).first())
        assertEquals(diskSpace, repository.diskSpace.value)

        repository.setConfigurationsState(DvrConfigurationsState.Denied)
        repository.setDiskSpaceState(DvrDiskSpaceState.Unknown)
        assertEquals(emptyList<DvrConfiguration>(), repository.configurations.value)
        assertEquals(null, repository.configuration(DvrConfigId("config")).first())
        assertEquals(null, repository.diskSpace.value)
        assertEquals(DvrConfigurationsState.Denied, repository.configurationsState.value)
    }

    @Test
    fun `fake scripts typed mutation outcomes without changing repository state`() = runTest {
        val repository = FakeDvrRepository()
        val scheduled = DvrMutationResult.Confirmed(DvrEntryId(7))
        val stopped = DvrMutationResult.AcceptedButUnconfirmed(Unit)
        repository.scheduleEntryResult = scheduled
        repository.stopEntryResult = stopped

        assertSame(
            scheduled,
            repository.scheduleEntry(
                DvrScheduleRequest(DvrSchedule.Programme(EventId(1))),
            ),
        )
        assertSame(stopped, repository.stopEntry(DvrEntryId(7)))
        assertEquals(DvrRepositoryState.Empty, repository.state.value)
    }
}
