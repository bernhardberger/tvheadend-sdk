package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.AutorecRule
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrConfigId
import at.bernhardberger.tvheadend.sdk.core.DvrConfiguration
import at.bernhardberger.tvheadend.sdk.core.DvrConfigurationsState
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpace
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpaceState
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrMutationResult
import at.bernhardberger.tvheadend.sdk.core.DvrRepository
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.TimerecRule
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleId
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleUpdate
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Mutable DVR repository for application and SDK consumer tests. */
public class FakeDvrRepository @JvmOverloads constructor(
    initialState: DvrRepositoryState = DvrRepositoryState.Empty,
    initialConfigurationsState: DvrConfigurationsState = DvrConfigurationsState.Unknown,
    initialDiskSpaceState: DvrDiskSpaceState = DvrDiskSpaceState.Unknown,
) : DvrRepository {
    private val mutableState = MutableStateFlow(initialState)
    private val mutableConfigurationsState = MutableStateFlow(initialConfigurationsState)
    private val mutableDiskSpaceState = MutableStateFlow(initialDiskSpaceState)

    override val state: StateFlow<DvrRepositoryState> = mutableState.asStateFlow()
    override val entries: StateFlow<List<DvrEntry>> =
        MappedFakeDvrStateFlow(state, DvrRepositoryState::entries)
    override val autorecRules: StateFlow<List<AutorecRule>> =
        MappedFakeDvrStateFlow(state, DvrRepositoryState::autorecRules)
    override val timerecRules: StateFlow<List<TimerecRule>> =
        MappedFakeDvrStateFlow(state, DvrRepositoryState::timerecRules)
    override val configurationsState: StateFlow<DvrConfigurationsState> =
        mutableConfigurationsState.asStateFlow()
    override val configurations: StateFlow<List<DvrConfiguration>> =
        MappedFakeDvrStateFlow(configurationsState, DvrConfigurationsState::configurations)
    override val diskSpaceState: StateFlow<DvrDiskSpaceState> = mutableDiskSpaceState.asStateFlow()
    override val diskSpace: StateFlow<DvrDiskSpace?> =
        MappedFakeDvrStateFlow(diskSpaceState, DvrDiskSpaceState::diskSpace)

    /** Scripted outcome returned by [scheduleEntry]. */
    public var scheduleEntryResult: DvrMutationResult<DvrEntryId> = DvrMutationResult.NotReady

    /** Scripted outcome returned by [updateEntry]. */
    public var updateEntryResult: DvrMutationResult<Unit> = DvrMutationResult.NotReady

    /** Scripted outcome returned by [stopEntry]. */
    public var stopEntryResult: DvrMutationResult<Unit> = DvrMutationResult.NotReady

    /** Scripted outcome returned by [cancelEntry]. */
    public var cancelEntryResult: DvrMutationResult<Unit> = DvrMutationResult.NotReady

    /** Scripted outcome returned by [deleteEntry]. */
    public var deleteEntryResult: DvrMutationResult<Unit> = DvrMutationResult.NotReady

    /** Scripted outcome returned by [createAutorecRule]. */
    public var createAutorecRuleResult: DvrMutationResult<AutorecRuleId> = DvrMutationResult.NotReady

    /** Scripted outcome returned by [updateAutorecRule]. */
    public var updateAutorecRuleResult: DvrMutationResult<Unit> = DvrMutationResult.NotReady

    /** Scripted outcome returned by [deleteAutorecRule]. */
    public var deleteAutorecRuleResult: DvrMutationResult<Unit> = DvrMutationResult.NotReady

    /** Scripted outcome returned by [createTimerecRule]. */
    public var createTimerecRuleResult: DvrMutationResult<TimerecRuleId> = DvrMutationResult.NotReady

    /** Scripted outcome returned by [updateTimerecRule]. */
    public var updateTimerecRuleResult: DvrMutationResult<Unit> = DvrMutationResult.NotReady

    /** Scripted outcome returned by [deleteTimerecRule]. */
    public var deleteTimerecRuleResult: DvrMutationResult<Unit> = DvrMutationResult.NotReady

    /** Publishes one complete repository transition. */
    public fun setState(state: DvrRepositoryState) {
        mutableState.value = state
    }

    /** Publishes one complete configuration-freshness transition. */
    public fun setConfigurationsState(state: DvrConfigurationsState) {
        mutableConfigurationsState.value = state
    }

    /** Publishes one complete disk-space-freshness transition. */
    public fun setDiskSpaceState(state: DvrDiskSpaceState) {
        mutableDiskSpaceState.value = state
    }

    override fun entry(id: DvrEntryId): Flow<DvrEntry?> =
        entries.map { entries -> entries.firstOrNull { entry -> entry.id == id } }
            .distinctUntilChanged()

    override fun autorecRule(id: AutorecRuleId): Flow<AutorecRule?> =
        autorecRules.map { rules -> rules.firstOrNull { rule -> rule.id == id } }
            .distinctUntilChanged()

    override fun timerecRule(id: TimerecRuleId): Flow<TimerecRule?> =
        timerecRules.map { rules -> rules.firstOrNull { rule -> rule.id == id } }
            .distinctUntilChanged()

    override fun configuration(id: DvrConfigId): Flow<DvrConfiguration?> =
        configurations.map { configurations ->
            configurations.firstOrNull { configuration -> configuration.id == id }
        }.distinctUntilChanged()

    override suspend fun scheduleEntry(request: DvrScheduleRequest): DvrMutationResult<DvrEntryId> =
        scheduleEntryResult

    override suspend fun updateEntry(
        id: DvrEntryId,
        update: DvrEntryUpdate,
    ): DvrMutationResult<Unit> = updateEntryResult

    override suspend fun stopEntry(id: DvrEntryId): DvrMutationResult<Unit> = stopEntryResult

    override suspend fun cancelEntry(id: DvrEntryId): DvrMutationResult<Unit> = cancelEntryResult

    override suspend fun deleteEntry(id: DvrEntryId): DvrMutationResult<Unit> = deleteEntryResult

    override suspend fun createAutorecRule(
        request: AutorecRuleCreate,
    ): DvrMutationResult<AutorecRuleId> = createAutorecRuleResult

    override suspend fun updateAutorecRule(
        id: AutorecRuleId,
        update: AutorecRuleUpdate,
    ): DvrMutationResult<Unit> = updateAutorecRuleResult

    override suspend fun deleteAutorecRule(id: AutorecRuleId): DvrMutationResult<Unit> =
        deleteAutorecRuleResult

    override suspend fun createTimerecRule(
        request: TimerecRuleCreate,
    ): DvrMutationResult<TimerecRuleId> = createTimerecRuleResult

    override suspend fun updateTimerecRule(
        id: TimerecRuleId,
        update: TimerecRuleUpdate,
    ): DvrMutationResult<Unit> = updateTimerecRuleResult

    override suspend fun deleteTimerecRule(id: TimerecRuleId): DvrMutationResult<Unit> =
        deleteTimerecRuleResult
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class, InternalCoroutinesApi::class)
private class MappedFakeDvrStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {
    override val value: R
        get() = transform(source.value)

    override val replayCache: List<R>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        var previous: Any? = UnsetFakeDvrState
        source.collect { value ->
            val mapped = transform(value)
            if (previous === UnsetFakeDvrState || previous != mapped) {
                previous = mapped
                collector.emit(mapped)
            }
        }
    }

    private data object UnsetFakeDvrState
}

private fun DvrRepositoryState.snapshotOrNull() = when (this) {
    DvrRepositoryState.Empty -> null
    is DvrRepositoryState.Synchronizing -> staleSnapshot
    is DvrRepositoryState.Current -> snapshot
    is DvrRepositoryState.Stale -> snapshot
}

private fun DvrRepositoryState.entries(): List<DvrEntry> = snapshotOrNull()?.entries.orEmpty()

private fun DvrRepositoryState.autorecRules(): List<AutorecRule> =
    snapshotOrNull()?.autorecRules.orEmpty()

private fun DvrRepositoryState.timerecRules(): List<TimerecRule> =
    snapshotOrNull()?.timerecRules.orEmpty()

private fun DvrConfigurationsState.configurations(): List<DvrConfiguration> = when (this) {
    DvrConfigurationsState.Unknown,
    DvrConfigurationsState.Denied,
    -> emptyList()
    is DvrConfigurationsState.Synchronizing -> staleConfigurations.orEmpty()
    is DvrConfigurationsState.Current -> configurations
    is DvrConfigurationsState.Stale -> configurations
}

private fun DvrDiskSpaceState.diskSpace(): DvrDiskSpace? = when (this) {
    DvrDiskSpaceState.Unknown -> null
    is DvrDiskSpaceState.Synchronizing -> staleDiskSpace
    is DvrDiskSpaceState.Current -> diskSpace
    is DvrDiskSpaceState.Stale -> diskSpace
}
