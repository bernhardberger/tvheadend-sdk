package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.AutorecRule
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSnapshot
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
}
