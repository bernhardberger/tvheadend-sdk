package at.bernhardberger.tvheadend.sdk.core

import java.util.Collections
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.time.Duration
import kotlin.time.Instant

private const val DVR_U32_MAX: Long = 0xffff_ffffL

/** Stable TVHeadend automatic-recording rule identifier. */
@JvmInline
public value class AutorecRuleId(public val value: String) {
    override fun toString(): String = "AutorecRuleId(<redacted>)"
}

/** Stable TVHeadend time-based recording rule identifier. */
@JvmInline
public value class TimerecRuleId(public val value: String) {
    override fun toString(): String = "TimerecRuleId(<redacted>)"
}

/** Stable TVHeadend DVR configuration identifier. */
@JvmInline
public value class DvrConfigId(public val value: String) {
    override fun toString(): String = "DvrConfigId(<redacted>)"
}

/** One visible DVR configuration. */
public data class DvrConfiguration(
    public val id: DvrConfigId,
    public val name: String,
    public val comment: String,
) {
    override fun toString(): String = "DvrConfiguration(<redacted>)"
}

/** Recording-storage counters for the selected server. */
public data class DvrDiskSpace(
    public val freeBytes: Long,
    public val usedBytes: Long?,
    public val totalBytes: Long,
) {
    override fun toString(): String =
        "DvrDiskSpace(freeBytes=$freeBytes, usedBytes=$usedBytes, totalBytes=$totalBytes)"
}

/** Safe recording lifecycle observation. */
public enum class DvrEntryState {
    SCHEDULED,
    RECORDING,
    COMPLETED,
    MISSED,
    INVALID,
    RECORDING_ERROR,
    COMPLETED_ERROR,
    FILE_MISSING,
    UNKNOWN,
}

/** Safe subscription-failure observation attached to a recording. */
public enum class DvrSubscriptionError {
    NO_FREE_ADAPTER,
    SCRAMBLED,
    BAD_SIGNAL,
    TUNING_FAILED,
    SUBSCRIPTION_OVERRIDDEN,
    MUX_NOT_ENABLED,
    INVALID_TARGET,
    NO_SERVICE,
    INVALID_SERVICE,
    USER_ACCESS,
    USER_LIMIT,
    WEAK_STREAM,
    NO_DISK_SPACE,
    UNKNOWN,
}

/** One bounded recording-file observation. */
public data class DvrRecordingFile(
    public val fileId: Long?,
    public val path: String?,
    public val start: Instant?,
    public val stop: Instant?,
    public val sizeBytes: Long?,
) {
    init {
        fileId?.let { requireDvrU32("DvrRecordingFile fileId", it) }
        if (start != null && stop != null) {
            require(stop >= start) { "DvrRecordingFile stop must not precede start" }
        }
    }

    override fun toString(): String = "DvrRecordingFile(<redacted>)"
}

/** Immutable DVR entry metadata. */
@ConsistentCopyVisibility
public data class DvrEntry private constructor(
    public val id: DvrEntryId,
    public val uuid: String?,
    public val enabled: Boolean?,
    public val channelId: ChannelId?,
    public val channelName: String?,
    public val eventId: EventId?,
    public val autorecRuleId: AutorecRuleId?,
    public val timerecRuleId: TimerecRuleId?,
    public val start: Instant?,
    public val stop: Instant?,
    public val startExtraMinutes: Long?,
    public val stopExtraMinutes: Long?,
    public val retentionDays: Long?,
    public val removalDays: Long?,
    public val priority: Long?,
    public val contentType: Long?,
    public val rating: EpgRating?,
    public val playCount: Long?,
    public val playPosition: Duration?,
    public val episode: EpgEpisode?,
    public val title: String?,
    public val description: String?,
    public val summary: String?,
    public val subtitle: String?,
    public val owner: String?,
    public val creator: String?,
    public val comment: String?,
    public val image: String?,
    public val fanartImage: String?,
    public val copyrightYear: Long?,
    public val files: List<DvrRecordingFile>?,
    public val path: String?,
    public val configId: DvrConfigId?,
    public val duplicate: Long?,
    public val state: DvrEntryState?,
    public val subscriptionError: DvrSubscriptionError?,
    public val streamErrors: Long?,
    public val dataErrors: Long?,
    public val dataSizeBytes: Long?,
) {
    override fun toString(): String = "DvrEntry(<redacted>)"

    public companion object {
        /** Creates an entry while validating wire domains and defensively copying files. */
        public fun create(
            id: DvrEntryId,
            uuid: String? = null,
            enabled: Boolean? = null,
            channelId: ChannelId? = null,
            channelName: String? = null,
            eventId: EventId? = null,
            autorecRuleId: AutorecRuleId? = null,
            timerecRuleId: TimerecRuleId? = null,
            start: Instant? = null,
            stop: Instant? = null,
            startExtraMinutes: Long? = null,
            stopExtraMinutes: Long? = null,
            retentionDays: Long? = null,
            removalDays: Long? = null,
            priority: Long? = null,
            contentType: Long? = null,
            rating: EpgRating? = null,
            playCount: Long? = null,
            playPosition: Duration? = null,
            episode: EpgEpisode? = null,
            title: String? = null,
            description: String? = null,
            summary: String? = null,
            subtitle: String? = null,
            owner: String? = null,
            creator: String? = null,
            comment: String? = null,
            image: String? = null,
            fanartImage: String? = null,
            copyrightYear: Long? = null,
            files: List<DvrRecordingFile>? = null,
            path: String? = null,
            configId: DvrConfigId? = null,
            duplicate: Long? = null,
            state: DvrEntryState? = null,
            subscriptionError: DvrSubscriptionError? = null,
            streamErrors: Long? = null,
            dataErrors: Long? = null,
            dataSizeBytes: Long? = null,
        ): DvrEntry {
            if (start != null && stop != null) {
                require(stop >= start) { "DvrEntry stop must not precede start" }
            }
            retentionDays?.let { requireDvrU32("DvrEntry retentionDays", it) }
            removalDays?.let { requireDvrU32("DvrEntry removalDays", it) }
            priority?.let { requireDvrU32("DvrEntry priority", it) }
            contentType?.let { requireDvrU32("DvrEntry contentType", it) }
            playCount?.let { requireDvrU32("DvrEntry playCount", it) }
            playPosition?.let {
                require(!it.isNegative()) { "DvrEntry playPosition must not be negative" }
            }
            copyrightYear?.let { requireDvrU32("DvrEntry copyrightYear", it) }
            duplicate?.let { requireDvrU32("DvrEntry duplicate", it) }
            streamErrors?.let { requireDvrU32("DvrEntry streamErrors", it) }
            dataErrors?.let { requireDvrU32("DvrEntry dataErrors", it) }
            return DvrEntry(
                id = id,
                uuid = uuid,
                enabled = enabled,
                channelId = channelId,
                channelName = channelName,
                eventId = eventId,
                autorecRuleId = autorecRuleId,
                timerecRuleId = timerecRuleId,
                start = start,
                stop = stop,
                startExtraMinutes = startExtraMinutes,
                stopExtraMinutes = stopExtraMinutes,
                retentionDays = retentionDays,
                removalDays = removalDays,
                priority = priority,
                contentType = contentType,
                rating = rating,
                playCount = playCount,
                playPosition = playPosition,
                episode = episode,
                title = title,
                description = description,
                summary = summary,
                subtitle = subtitle,
                owner = owner,
                creator = creator,
                comment = comment,
                image = image,
                fanartImage = fanartImage,
                copyrightYear = copyrightYear,
                files = files?.toDvrImmutableList(),
                path = path,
                configId = configId,
                duplicate = duplicate,
                state = state,
                subscriptionError = subscriptionError,
                streamErrors = streamErrors,
                dataErrors = dataErrors,
                dataSizeBytes = dataSizeBytes,
            )
        }
    }
}

/** Immutable automatic-recording rule metadata. */
@ConsistentCopyVisibility
public data class AutorecRule private constructor(
    public val id: AutorecRuleId,
    public val enabled: Boolean?,
    public val maxDuration: Duration?,
    public val minDuration: Duration?,
    public val retentionDays: Long?,
    public val removalDays: Long?,
    public val daysOfWeekMask: Long?,
    public val approximateStartMinutesSinceMidnight: Int?,
    public val startMinutesSinceMidnight: Int?,
    public val startWindowEndMinutesSinceMidnight: Int?,
    public val priority: Long?,
    public val startExtraMinutes: Long?,
    public val stopExtraMinutes: Long?,
    public val duplicateDetection: Long?,
    public val maximumRecordingCount: Long?,
    public val broadcastType: Long?,
    public val comment: String?,
    public val title: String?,
    public val fullText: Boolean?,
    public val mergeText: Boolean?,
    public val name: String?,
    public val directory: String?,
    public val owner: String?,
    public val creator: String?,
    public val channelId: ChannelId?,
    public val seriesLinkUri: String?,
    public val configId: DvrConfigId?,
) {
    override fun toString(): String = "AutorecRule(<redacted>)"

    public companion object {
        /** Creates an automatic-recording rule while validating wire domains. */
        public fun create(
            id: AutorecRuleId,
            enabled: Boolean? = null,
            maxDuration: Duration? = null,
            minDuration: Duration? = null,
            retentionDays: Long? = null,
            removalDays: Long? = null,
            daysOfWeekMask: Long? = null,
            approximateStartMinutesSinceMidnight: Int? = null,
            startMinutesSinceMidnight: Int? = null,
            startWindowEndMinutesSinceMidnight: Int? = null,
            priority: Long? = null,
            startExtraMinutes: Long? = null,
            stopExtraMinutes: Long? = null,
            duplicateDetection: Long? = null,
            maximumRecordingCount: Long? = null,
            broadcastType: Long? = null,
            comment: String? = null,
            title: String? = null,
            fullText: Boolean? = null,
            mergeText: Boolean? = null,
            name: String? = null,
            directory: String? = null,
            owner: String? = null,
            creator: String? = null,
            channelId: ChannelId? = null,
            seriesLinkUri: String? = null,
            configId: DvrConfigId? = null,
        ): AutorecRule {
            maxDuration?.let { require(!it.isNegative()) { "AutorecRule maxDuration must not be negative" } }
            minDuration?.let { require(!it.isNegative()) { "AutorecRule minDuration must not be negative" } }
            retentionDays?.let { requireDvrU32("AutorecRule retentionDays", it) }
            removalDays?.let { requireDvrU32("AutorecRule removalDays", it) }
            daysOfWeekMask?.let { requireDvrU32("AutorecRule daysOfWeekMask", it) }
            priority?.let { requireDvrU32("AutorecRule priority", it) }
            duplicateDetection?.let { requireDvrU32("AutorecRule duplicateDetection", it) }
            maximumRecordingCount?.let { requireDvrU32("AutorecRule maximumRecordingCount", it) }
            broadcastType?.let { requireDvrU32("AutorecRule broadcastType", it) }
            return AutorecRule(
                id = id,
                enabled = enabled,
                maxDuration = maxDuration,
                minDuration = minDuration,
                retentionDays = retentionDays,
                removalDays = removalDays,
                daysOfWeekMask = daysOfWeekMask,
                approximateStartMinutesSinceMidnight = approximateStartMinutesSinceMidnight,
                startMinutesSinceMidnight = startMinutesSinceMidnight,
                startWindowEndMinutesSinceMidnight = startWindowEndMinutesSinceMidnight,
                priority = priority,
                startExtraMinutes = startExtraMinutes,
                stopExtraMinutes = stopExtraMinutes,
                duplicateDetection = duplicateDetection,
                maximumRecordingCount = maximumRecordingCount,
                broadcastType = broadcastType,
                comment = comment,
                title = title,
                fullText = fullText,
                mergeText = mergeText,
                name = name,
                directory = directory,
                owner = owner,
                creator = creator,
                channelId = channelId,
                seriesLinkUri = seriesLinkUri,
                configId = configId,
            )
        }
    }
}

/** Immutable time-based recording rule metadata. */
@ConsistentCopyVisibility
public data class TimerecRule private constructor(
    public val id: TimerecRuleId,
    public val enabled: Boolean?,
    public val name: String?,
    public val title: String?,
    public val channelId: ChannelId?,
    public val startMinutesSinceMidnight: Int?,
    public val stopMinutesSinceMidnight: Int?,
    public val daysOfWeekMask: Long?,
    public val priority: Long?,
    public val retentionDays: Long?,
    public val directory: String?,
    public val owner: String?,
    public val creator: String?,
    public val configId: DvrConfigId?,
    public val comment: String?,
) {
    override fun toString(): String = "TimerecRule(<redacted>)"

    public companion object {
        /** Creates a time-based recording rule while validating wire domains. */
        public fun create(
            id: TimerecRuleId,
            enabled: Boolean? = null,
            name: String? = null,
            title: String? = null,
            channelId: ChannelId? = null,
            startMinutesSinceMidnight: Int? = null,
            stopMinutesSinceMidnight: Int? = null,
            daysOfWeekMask: Long? = null,
            priority: Long? = null,
            retentionDays: Long? = null,
            directory: String? = null,
            owner: String? = null,
            creator: String? = null,
            configId: DvrConfigId? = null,
            comment: String? = null,
        ): TimerecRule {
            startMinutesSinceMidnight?.let {
                require(it in 0..1_440) { "TimerecRule startMinutesSinceMidnight must be between 0 and 1440" }
            }
            stopMinutesSinceMidnight?.let {
                require(it in 0..1_440) { "TimerecRule stopMinutesSinceMidnight must be between 0 and 1440" }
            }
            daysOfWeekMask?.let { requireDvrU32("TimerecRule daysOfWeekMask", it) }
            priority?.let { requireDvrU32("TimerecRule priority", it) }
            retentionDays?.let { requireDvrU32("TimerecRule retentionDays", it) }
            return TimerecRule(
                id = id,
                enabled = enabled,
                name = name,
                title = title,
                channelId = channelId,
                startMinutesSinceMidnight = startMinutesSinceMidnight,
                stopMinutesSinceMidnight = stopMinutesSinceMidnight,
                daysOfWeekMask = daysOfWeekMask,
                priority = priority,
                retentionDays = retentionDays,
                directory = directory,
                owner = owner,
                creator = creator,
                configId = configId,
                comment = comment,
            )
        }
    }
}

/** One immutable DVR entry and rule snapshot. */
@ConsistentCopyVisibility
public data class DvrSnapshot private constructor(
    public val entries: List<DvrEntry>,
    public val autorecRules: List<AutorecRule>,
    public val timerecRules: List<TimerecRule>,
) {
    override fun toString(): String = "DvrSnapshot(<redacted>)"

    public companion object {
        /** Creates a snapshot while defensively copying all entity lists. */
        public fun create(
            entries: List<DvrEntry> = emptyList(),
            autorecRules: List<AutorecRule> = emptyList(),
            timerecRules: List<TimerecRule> = emptyList(),
        ): DvrSnapshot = DvrSnapshot(
            entries = entries.toDvrImmutableList(),
            autorecRules = autorecRules.toDvrImmutableList(),
            timerecRules = timerecRules.toDvrImmutableList(),
        )
    }
}

/** Freshness and synchronization state of a [DvrRepository]. */
public sealed interface DvrRepositoryState {
    /** No DVR snapshot has been synchronized. */
    public data object Empty : DvrRepositoryState

    /** A new DVR snapshot is synchronizing, optionally retaining prior data. */
    public data class Synchronizing(
        public val staleSnapshot: DvrSnapshot?,
    ) : DvrRepositoryState {
        override fun toString(): String = "DvrRepositoryState.Synchronizing(<redacted>)"
    }

    /** The DVR snapshot is current for the active connection generation. */
    public data class Current(
        public val snapshot: DvrSnapshot,
    ) : DvrRepositoryState {
        override fun toString(): String = "DvrRepositoryState.Current(<redacted>)"
    }

    /** The retained DVR snapshot belongs to an inactive connection generation. */
    public data class Stale(
        public val snapshot: DvrSnapshot,
    ) : DvrRepositoryState {
        override fun toString(): String = "DvrRepositoryState.Stale(<redacted>)"
    }
}

/** Freshness of retrieved DVR configurations. */
public sealed interface DvrConfigurationsState {
    /** No configuration snapshot has been proven. */
    public data object Unknown : DvrConfigurationsState

    /** Configurations are refreshing, optionally retaining prior data. */
    @ConsistentCopyVisibility
    public data class Synchronizing private constructor(
        public val staleConfigurations: List<DvrConfiguration>?,
    ) : DvrConfigurationsState {
        override fun toString(): String = "DvrConfigurationsState.Synchronizing(<redacted>)"

        public companion object {
            /** Creates a synchronizing state while defensively copying retained configurations. */
            public fun create(
                staleConfigurations: List<DvrConfiguration>?,
            ): Synchronizing = Synchronizing(staleConfigurations?.toDvrImmutableList())
        }
    }

    /** Configurations are current for the active connection generation. */
    @ConsistentCopyVisibility
    public data class Current private constructor(
        public val configurations: List<DvrConfiguration>,
    ) : DvrConfigurationsState {
        override fun toString(): String = "DvrConfigurationsState.Current(<redacted>)"

        public companion object {
            /** Creates a current state while defensively copying configurations. */
            public fun create(configurations: List<DvrConfiguration>): Current =
                Current(configurations.toDvrImmutableList())
        }
    }

    /** Retained configurations are available but not current for this generation. */
    @ConsistentCopyVisibility
    public data class Stale private constructor(
        public val configurations: List<DvrConfiguration>,
    ) : DvrConfigurationsState {
        override fun toString(): String = "DvrConfigurationsState.Stale(<redacted>)"

        public companion object {
            /** Creates a stale state while defensively copying configurations. */
            public fun create(configurations: List<DvrConfiguration>): Stale =
                Stale(configurations.toDvrImmutableList())
        }
    }

    /** Configuration retrieval proved recorder access is denied. */
    public data object Denied : DvrConfigurationsState
}

/** Freshness of retrieved recording-storage counters. */
public sealed interface DvrDiskSpaceState {
    /** No disk-space snapshot has been proven. */
    public data object Unknown : DvrDiskSpaceState

    /** Disk space is refreshing, optionally retaining prior data. */
    public data class Synchronizing(
        public val staleDiskSpace: DvrDiskSpace?,
    ) : DvrDiskSpaceState {
        override fun toString(): String = "DvrDiskSpaceState.Synchronizing(<redacted>)"
    }

    /** Disk space is current for the active connection generation. */
    public data class Current(
        public val diskSpace: DvrDiskSpace,
    ) : DvrDiskSpaceState {
        override fun toString(): String = "DvrDiskSpaceState.Current(<redacted>)"
    }

    /** Retained disk space is available but not current for this generation. */
    public data class Stale(
        public val diskSpace: DvrDiskSpace,
    ) : DvrDiskSpaceState {
        override fun toString(): String = "DvrDiskSpaceState.Stale(<redacted>)"
    }
}

/** Observable DVR entries and recording rules for the selected server profile. */
public interface DvrRepository {
    /** Authoritative DVR freshness and content. */
    public val state: StateFlow<DvrRepositoryState>

    /** Entries from the current or retained stale snapshot. */
    public val entries: StateFlow<List<DvrEntry>>

    /** Automatic-recording rules from the current or retained stale snapshot. */
    public val autorecRules: StateFlow<List<AutorecRule>>

    /** Time-based recording rules from the current or retained stale snapshot. */
    public val timerecRules: StateFlow<List<TimerecRule>>

    /** Authoritative DVR configuration freshness. */
    public val configurationsState: StateFlow<DvrConfigurationsState>

    /** Configurations from the current or retained stale snapshot. */
    public val configurations: StateFlow<List<DvrConfiguration>>

    /** Authoritative recording-storage freshness. */
    public val diskSpaceState: StateFlow<DvrDiskSpaceState>

    /** Disk space from the current or retained stale snapshot. */
    public val diskSpace: StateFlow<DvrDiskSpace?>

    /** Observes one entry from the current or retained stale snapshot. */
    public fun entry(id: DvrEntryId): Flow<DvrEntry?>

    /** Observes one automatic-recording rule from the current or retained stale snapshot. */
    public fun autorecRule(id: AutorecRuleId): Flow<AutorecRule?>

    /** Observes one time-based recording rule from the current or retained stale snapshot. */
    public fun timerecRule(id: TimerecRuleId): Flow<TimerecRule?>

    /** Observes one configuration from the current or retained stale snapshot. */
    public fun configuration(id: DvrConfigId): Flow<DvrConfiguration?>

    /** Schedules one DVR entry and waits for authoritative stream confirmation. */
    public suspend fun scheduleEntry(request: DvrScheduleRequest): DvrMutationResult<DvrEntryId>

    /** Changes one DVR entry and waits for authoritative stream confirmation. */
    public suspend fun updateEntry(
        id: DvrEntryId,
        update: DvrEntryUpdate,
    ): DvrMutationResult<Unit>

    /** Stops one recording and waits for authoritative stream confirmation. */
    public suspend fun stopEntry(id: DvrEntryId): DvrMutationResult<Unit>

    /** Cancels one DVR entry and waits for authoritative stream confirmation. */
    public suspend fun cancelEntry(id: DvrEntryId): DvrMutationResult<Unit>

    /** Deletes one DVR entry and waits for authoritative stream confirmation. */
    public suspend fun deleteEntry(id: DvrEntryId): DvrMutationResult<Unit>

    /** Creates one automatic-recording rule and waits for authoritative stream confirmation. */
    public suspend fun createAutorecRule(
        request: AutorecRuleCreate,
    ): DvrMutationResult<AutorecRuleId>

    /** Changes one automatic-recording rule and waits for authoritative stream confirmation. */
    public suspend fun updateAutorecRule(
        id: AutorecRuleId,
        update: AutorecRuleUpdate,
    ): DvrMutationResult<Unit>

    /** Deletes one automatic-recording rule and waits for authoritative stream confirmation. */
    public suspend fun deleteAutorecRule(id: AutorecRuleId): DvrMutationResult<Unit>

    /** Creates one time-based recording rule and waits for authoritative stream confirmation. */
    public suspend fun createTimerecRule(
        request: TimerecRuleCreate,
    ): DvrMutationResult<TimerecRuleId>

    /** Changes one time-based recording rule and waits for authoritative stream confirmation. */
    public suspend fun updateTimerecRule(
        id: TimerecRuleId,
        update: TimerecRuleUpdate,
    ): DvrMutationResult<Unit>

    /** Deletes one time-based recording rule and waits for authoritative stream confirmation. */
    public suspend fun deleteTimerecRule(id: TimerecRuleId): DvrMutationResult<Unit>

    /** Reports playback progress without mutating the authoritative DVR snapshot. */
    public suspend fun reportProgress(
        id: DvrEntryId,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult
}

internal abstract class StateBackedDvrRepository(
    private val mutations: DvrMutationCommands = DvrMutationCommands.None,
    private val progressCommands: DvrProgressCommands = DvrProgressCommands.None,
) : DvrRepository {
    final override val entries: StateFlow<List<DvrEntry>> by lazy {
        MappedDvrStateFlow(state, DvrRepositoryState::entries)
    }
    final override val autorecRules: StateFlow<List<AutorecRule>> by lazy {
        MappedDvrStateFlow(state, DvrRepositoryState::autorecRules)
    }
    final override val timerecRules: StateFlow<List<TimerecRule>> by lazy {
        MappedDvrStateFlow(state, DvrRepositoryState::timerecRules)
    }
    final override val configurations: StateFlow<List<DvrConfiguration>> by lazy {
        MappedDvrStateFlow(configurationsState, DvrConfigurationsState::configurations)
    }
    final override val diskSpace: StateFlow<DvrDiskSpace?> by lazy {
        MappedDvrStateFlow(diskSpaceState, DvrDiskSpaceState::diskSpace)
    }

    final override fun entry(id: DvrEntryId): Flow<DvrEntry?> =
        entries.map { entries -> entries.firstOrNull { entry -> entry.id == id } }
            .distinctUntilChanged()

    final override fun autorecRule(id: AutorecRuleId): Flow<AutorecRule?> =
        autorecRules.map { rules -> rules.firstOrNull { rule -> rule.id == id } }
            .distinctUntilChanged()

    final override fun timerecRule(id: TimerecRuleId): Flow<TimerecRule?> =
        timerecRules.map { rules -> rules.firstOrNull { rule -> rule.id == id } }
            .distinctUntilChanged()

    final override fun configuration(id: DvrConfigId): Flow<DvrConfiguration?> =
        configurations.map { configurations ->
            configurations.firstOrNull { configuration -> configuration.id == id }
        }.distinctUntilChanged()

    final override suspend fun scheduleEntry(
        request: DvrScheduleRequest,
    ): DvrMutationResult<DvrEntryId> = mutations.scheduleEntry(request)

    final override suspend fun updateEntry(
        id: DvrEntryId,
        update: DvrEntryUpdate,
    ): DvrMutationResult<Unit> = mutations.updateEntry(id, update)

    final override suspend fun stopEntry(id: DvrEntryId): DvrMutationResult<Unit> =
        mutations.stopEntry(id)

    final override suspend fun cancelEntry(id: DvrEntryId): DvrMutationResult<Unit> =
        mutations.cancelEntry(id)

    final override suspend fun deleteEntry(id: DvrEntryId): DvrMutationResult<Unit> =
        mutations.deleteEntry(id)

    final override suspend fun createAutorecRule(
        request: AutorecRuleCreate,
    ): DvrMutationResult<AutorecRuleId> = mutations.createAutorecRule(request)

    final override suspend fun updateAutorecRule(
        id: AutorecRuleId,
        update: AutorecRuleUpdate,
    ): DvrMutationResult<Unit> = mutations.updateAutorecRule(id, update)

    final override suspend fun deleteAutorecRule(id: AutorecRuleId): DvrMutationResult<Unit> =
        mutations.deleteAutorecRule(id)

    final override suspend fun createTimerecRule(
        request: TimerecRuleCreate,
    ): DvrMutationResult<TimerecRuleId> = mutations.createTimerecRule(request)

    final override suspend fun updateTimerecRule(
        id: TimerecRuleId,
        update: TimerecRuleUpdate,
    ): DvrMutationResult<Unit> = mutations.updateTimerecRule(id, update)

    final override suspend fun deleteTimerecRule(id: TimerecRuleId): DvrMutationResult<Unit> =
        mutations.deleteTimerecRule(id)

    final override suspend fun reportProgress(
        id: DvrEntryId,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult = progressCommands.reportProgress(id, progress)
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class, InternalCoroutinesApi::class)
private class MappedDvrStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {
    override val value: R
        get() = transform(source.value)

    override val replayCache: List<R>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        var previous: Any? = UnsetDvrState
        source.collect { value ->
            val mapped = transform(value)
            if (previous === UnsetDvrState || previous != mapped) {
                previous = mapped
                collector.emit(mapped)
            }
        }
    }

    private data object UnsetDvrState
}

private fun DvrRepositoryState.snapshotOrNull(): DvrSnapshot? = when (this) {
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

private fun requireDvrU32(name: String, value: Long) {
    require(value in 0L..DVR_U32_MAX) { "$name must be an unsigned 32-bit value" }
}

private fun <T> Collection<T>.toDvrImmutableList(): List<T> =
    Collections.unmodifiableList(ArrayList(this))
