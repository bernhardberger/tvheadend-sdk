package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.util.Collections
import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
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

/** Safe recording lifecycle observation. Consumers must retain a fallback for future values. */
public class DvrEntryState private constructor(private val label: String) {
    override fun toString(): String = label

    /** Known lifecycle observations. */
    public companion object {
        /** Scheduled for recording. */
        @JvmField
        public val SCHEDULED: DvrEntryState = DvrEntryState("SCHEDULED")
        /** Recording is in progress. */
        @JvmField
        public val RECORDING: DvrEntryState = DvrEntryState("RECORDING")
        /** Recording completed. */
        @JvmField
        public val COMPLETED: DvrEntryState = DvrEntryState("COMPLETED")
        /** Recording was missed. */
        @JvmField
        public val MISSED: DvrEntryState = DvrEntryState("MISSED")
        /** The recording entry is invalid. */
        @JvmField
        public val INVALID: DvrEntryState = DvrEntryState("INVALID")
        /** Recording is in progress with an error. */
        @JvmField
        public val RECORDING_ERROR: DvrEntryState = DvrEntryState("RECORDING_ERROR")
        /** Recording completed with an error. */
        @JvmField
        public val COMPLETED_ERROR: DvrEntryState = DvrEntryState("COMPLETED_ERROR")
        /** The recording file is missing. */
        @JvmField
        public val FILE_MISSING: DvrEntryState = DvrEntryState("FILE_MISSING")
        /** An unrecognized wire state. */
        @JvmField
        public val UNKNOWN: DvrEntryState = DvrEntryState("UNKNOWN")
    }
}

/** Safe recording subscription failure. Consumers must retain a fallback for future values. */
public class DvrSubscriptionError private constructor(private val label: String) {
    override fun toString(): String = label

    /** Known recording subscription failures. */
    public companion object {
        /** No tuner is available. */
        @JvmField
        public val NO_FREE_ADAPTER: DvrSubscriptionError = DvrSubscriptionError("NO_FREE_ADAPTER")
        /** The service is scrambled. */
        @JvmField
        public val SCRAMBLED: DvrSubscriptionError = DvrSubscriptionError("SCRAMBLED")
        /** The input signal is bad. */
        @JvmField
        public val BAD_SIGNAL: DvrSubscriptionError = DvrSubscriptionError("BAD_SIGNAL")
        /** Tuning failed. */
        @JvmField
        public val TUNING_FAILED: DvrSubscriptionError = DvrSubscriptionError("TUNING_FAILED")
        /** Another subscription took precedence. */
        @JvmField
        public val SUBSCRIPTION_OVERRIDDEN: DvrSubscriptionError = DvrSubscriptionError("SUBSCRIPTION_OVERRIDDEN")
        /** The multiplex is disabled. */
        @JvmField
        public val MUX_NOT_ENABLED: DvrSubscriptionError = DvrSubscriptionError("MUX_NOT_ENABLED")
        /** The target is invalid. */
        @JvmField
        public val INVALID_TARGET: DvrSubscriptionError = DvrSubscriptionError("INVALID_TARGET")
        /** No service is available. */
        @JvmField
        public val NO_SERVICE: DvrSubscriptionError = DvrSubscriptionError("NO_SERVICE")
        /** The service is invalid. */
        @JvmField
        public val INVALID_SERVICE: DvrSubscriptionError = DvrSubscriptionError("INVALID_SERVICE")
        /** The user lacks access. */
        @JvmField
        public val USER_ACCESS: DvrSubscriptionError = DvrSubscriptionError("USER_ACCESS")
        /** The user's limit was reached. */
        @JvmField
        public val USER_LIMIT: DvrSubscriptionError = DvrSubscriptionError("USER_LIMIT")
        /** The stream is weak. */
        @JvmField
        public val WEAK_STREAM: DvrSubscriptionError = DvrSubscriptionError("WEAK_STREAM")
        /** Recording storage is full. */
        @JvmField
        public val NO_DISK_SPACE: DvrSubscriptionError = DvrSubscriptionError("NO_DISK_SPACE")
        /** An unrecognized wire error. */
        @JvmField
        public val UNKNOWN: DvrSubscriptionError = DvrSubscriptionError("UNKNOWN")
    }
}

/** One bounded recording-file observation. */
@ConsistentCopyVisibility
public data class DvrRecordingFile internal constructor(
    public val fileId: Long?,
    internal val path: String?,
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

    public companion object {
        /** Creates a recording-file observation without a server-private path. */
        public fun create(
            fileId: Long? = null,
            start: Instant? = null,
            stop: Instant? = null,
            sizeBytes: Long? = null,
        ): DvrRecordingFile = DvrRecordingFile(fileId, null, start, stop, sizeBytes)
    }
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
    public val startExtraMinutes: Duration?,
    public val stopExtraMinutes: Duration?,
    public val retentionDays: Long?,
    public val removalDays: Long?,
    public val priority: DvrPriority?,
    public val contentGenre: ContentGenre?,
    public val rating: EpgRating?,
    public val playCount: Long?,
    public val playPosition: Duration?,
    public val episode: EpgEpisode?,
    public val title: String?,
    public val description: String?,
    public val summary: String?,
    public val subtitle: String?,
    internal val owner: String?,
    internal val creator: String?,
    public val comment: String?,
    public val image: ArtworkId?,
    public val fanartImage: ArtworkId?,
    public val copyrightYear: Long?,
    public val files: List<DvrRecordingFile>?,
    internal val path: String?,
    public val configId: DvrConfigId?,
    public val duplicate: Boolean?,
    public val state: DvrEntryState?,
    public val subscriptionError: DvrSubscriptionError?,
    public val streamErrors: Long?,
    public val dataErrors: Long?,
    public val dataSizeBytes: Long?,
    internal val suppliedFolder: List<String>?,
) {
    /**
     * Common folder components relative to the server's DVR storage root, for display and grouping only.
     * This is not a file-system path. An empty list denotes the storage root.
     *
     * Uses the common directory prefix of all files, or the entry's directory when there are no files.
     * When neither the entry nor any file has a server path, uses the folder supplied to [create].
     * Returns `null` if a required path is unknown or any component is `.` or `..`.
     * The returned list is unmodifiable. Treat its contents as private metadata, not diagnostics.
     */
    public val folder: List<String>?
        get() {
            if (path == null && files.orEmpty().all { it.path == null }) return suppliedFolder
            val paths = if (files.isNullOrEmpty()) listOf(path) else files.map { it.path }
            val directories = paths.map { filePath ->
                val components = filePath?.split('/')?.filter { it.isNotEmpty() } ?: return null
                if (components.any { it == "." || it == ".." }) return null
                components.dropLast(1)
            }
            val common = directories.reduce { prefix, directory ->
                prefix.zip(directory).takeWhile { (left, right) -> left == right }.map { it.first }
            }
            return common.toDvrImmutableList()
        }

    override fun toString(): String = "DvrEntry(<redacted>)"

    public companion object {
        /**
         * Creates an entry while validating wire domains and defensively copying files and [folder].
         * [folder] is optional display/grouping data used only without server paths; `null` means unknown.
         * Empty, `.` and `..` components are rejected; an empty list denotes the storage root.
         */
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
            startExtraMinutes: Duration? = null,
            stopExtraMinutes: Duration? = null,
            retentionDays: Long? = null,
            removalDays: Long? = null,
            priority: DvrPriority? = null,
            contentGenre: ContentGenre? = null,
            rating: EpgRating? = null,
            playCount: Long? = null,
            playPosition: Duration? = null,
            episode: EpgEpisode? = null,
            title: String? = null,
            description: String? = null,
            summary: String? = null,
            subtitle: String? = null,
            comment: String? = null,
            image: ArtworkId? = null,
            fanartImage: ArtworkId? = null,
            copyrightYear: Long? = null,
            files: List<DvrRecordingFile>? = null,
            configId: DvrConfigId? = null,
            duplicate: Boolean? = null,
            state: DvrEntryState? = null,
            subscriptionError: DvrSubscriptionError? = null,
            streamErrors: Long? = null,
            dataErrors: Long? = null,
            dataSizeBytes: Long? = null,
            folder: List<String>? = null,
        ): DvrEntry = create(
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
            contentGenre = contentGenre,
            rating = rating,
            playCount = playCount,
            playPosition = playPosition,
            episode = episode,
            title = title,
            description = description,
            summary = summary,
            subtitle = subtitle,
            comment = comment,
            image = image,
            fanartImage = fanartImage,
            copyrightYear = copyrightYear,
            files = files,
            configId = configId,
            duplicate = duplicate,
            state = state,
            subscriptionError = subscriptionError,
            streamErrors = streamErrors,
            dataErrors = dataErrors,
            dataSizeBytes = dataSizeBytes,
            owner = null,
            creator = null,
            path = null,
        ).copy(
            suppliedFolder = folder?.toDvrImmutableList()?.also { components ->
                require(components.none { it.isEmpty() || it == "." || it == ".." }) {
                    "DvrEntry folder contains invalid components"
                }
            },
        )

        internal fun create(
            id: DvrEntryId,
            uuid: String?,
            enabled: Boolean?,
            channelId: ChannelId?,
            channelName: String?,
            eventId: EventId?,
            autorecRuleId: AutorecRuleId?,
            timerecRuleId: TimerecRuleId?,
            start: Instant?,
            stop: Instant?,
            startExtraMinutes: Duration?,
            stopExtraMinutes: Duration?,
            retentionDays: Long?,
            removalDays: Long?,
            priority: DvrPriority?,
            contentGenre: ContentGenre?,
            rating: EpgRating?,
            playCount: Long?,
            playPosition: Duration?,
            episode: EpgEpisode?,
            title: String?,
            description: String?,
            summary: String?,
            subtitle: String?,
            owner: String?,
            creator: String?,
            comment: String?,
            image: ArtworkId?,
            fanartImage: ArtworkId?,
            copyrightYear: Long?,
            files: List<DvrRecordingFile>?,
            path: String?,
            configId: DvrConfigId?,
            duplicate: Boolean?,
            state: DvrEntryState?,
            subscriptionError: DvrSubscriptionError?,
            streamErrors: Long?,
            dataErrors: Long?,
            dataSizeBytes: Long?,
        ): DvrEntry {
            if (start != null && stop != null) {
                require(stop >= start) { "DvrEntry stop must not precede start" }
            }
            retentionDays?.let { requireDvrU32("DvrEntry retentionDays", it) }
            removalDays?.let { requireDvrU32("DvrEntry removalDays", it) }
            startExtraMinutes?.requireDvrExtraMinutes()
            stopExtraMinutes?.requireDvrExtraMinutes()
            playCount?.let { requireDvrU32("DvrEntry playCount", it) }
            playPosition?.let {
                require(!it.isNegative()) { "DvrEntry playPosition must not be negative" }
            }
            copyrightYear?.let { requireDvrU32("DvrEntry copyrightYear", it) }
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
                contentGenre = contentGenre,
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
                suppliedFolder = null,
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
    public val daysOfWeekMask: Set<DayOfWeek>?,
    public val approximateStartMinutesSinceMidnight: AutorecTime?,
    public val startMinutesSinceMidnight: AutorecTime?,
    public val startWindowEndMinutesSinceMidnight: AutorecTime?,
    public val priority: DvrPriority?,
    public val startExtraMinutes: Duration?,
    public val stopExtraMinutes: Duration?,
    public val duplicateDetection: DuplicateDetection?,
    public val maximumRecordingCount: Long?,
    public val broadcastType: BroadcastType?,
    public val comment: String?,
    public val title: String?,
    public val fullText: Boolean?,
    public val mergeText: Boolean?,
    public val name: String?,
    public val directory: String?,
    internal val owner: String?,
    internal val creator: String?,
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
            daysOfWeekMask: Set<DayOfWeek>? = null,
            approximateStartMinutesSinceMidnight: AutorecTime? = null,
            startMinutesSinceMidnight: AutorecTime? = null,
            startWindowEndMinutesSinceMidnight: AutorecTime? = null,
            priority: DvrPriority? = null,
            startExtraMinutes: Duration? = null,
            stopExtraMinutes: Duration? = null,
            duplicateDetection: DuplicateDetection? = null,
            maximumRecordingCount: Long? = null,
            broadcastType: BroadcastType? = null,
            comment: String? = null,
            title: String? = null,
            fullText: Boolean? = null,
            mergeText: Boolean? = null,
            name: String? = null,
            directory: String? = null,
            channelId: ChannelId? = null,
            seriesLinkUri: String? = null,
            configId: DvrConfigId? = null,
        ): AutorecRule = create(
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
            channelId = channelId,
            seriesLinkUri = seriesLinkUri,
            configId = configId,
            owner = null,
            creator = null,
        )

        internal fun create(
            id: AutorecRuleId,
            enabled: Boolean? = null,
            maxDuration: Duration? = null,
            minDuration: Duration? = null,
            retentionDays: Long? = null,
            removalDays: Long? = null,
            daysOfWeekMask: Set<DayOfWeek>? = null,
            approximateStartMinutesSinceMidnight: AutorecTime? = null,
            startMinutesSinceMidnight: AutorecTime? = null,
            startWindowEndMinutesSinceMidnight: AutorecTime? = null,
            priority: DvrPriority? = null,
            startExtraMinutes: Duration? = null,
            stopExtraMinutes: Duration? = null,
            duplicateDetection: DuplicateDetection? = null,
            maximumRecordingCount: Long? = null,
            broadcastType: BroadcastType? = null,
            comment: String? = null,
            title: String? = null,
            fullText: Boolean? = null,
            mergeText: Boolean? = null,
            name: String? = null,
            directory: String? = null,
            owner: String?,
            creator: String? = null,
            channelId: ChannelId? = null,
            seriesLinkUri: String? = null,
            configId: DvrConfigId? = null,
        ): AutorecRule {
            maxDuration?.let { require(!it.isNegative()) { "AutorecRule maxDuration must not be negative" } }
            minDuration?.let { require(!it.isNegative()) { "AutorecRule minDuration must not be negative" } }
            retentionDays?.let { requireDvrU32("AutorecRule retentionDays", it) }
            removalDays?.let { requireDvrU32("AutorecRule removalDays", it) }
            startExtraMinutes?.requireDvrExtraMinutes()
            stopExtraMinutes?.requireDvrExtraMinutes()
            maximumRecordingCount?.let { requireDvrU32("AutorecRule maximumRecordingCount", it) }
            return AutorecRule(
                id = id,
                enabled = enabled,
                maxDuration = maxDuration,
                minDuration = minDuration,
                retentionDays = retentionDays,
                removalDays = removalDays,
                daysOfWeekMask = daysOfWeekMask?.toDvrImmutableDays(),
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
    public val startMinutesSinceMidnight: LocalTime?,
    public val stopMinutesSinceMidnight: LocalTime?,
    public val daysOfWeekMask: Set<DayOfWeek>?,
    public val priority: DvrPriority?,
    public val retentionDays: Long?,
    public val directory: String?,
    internal val owner: String?,
    internal val creator: String?,
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
            startMinutesSinceMidnight: LocalTime? = null,
            stopMinutesSinceMidnight: LocalTime? = null,
            daysOfWeekMask: Set<DayOfWeek>? = null,
            priority: DvrPriority? = null,
            retentionDays: Long? = null,
            directory: String? = null,
            configId: DvrConfigId? = null,
            comment: String? = null,
        ): TimerecRule = create(
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
            configId = configId,
            comment = comment,
            owner = null,
            creator = null,
        )

        internal fun create(
            id: TimerecRuleId,
            enabled: Boolean? = null,
            name: String? = null,
            title: String? = null,
            channelId: ChannelId? = null,
            startMinutesSinceMidnight: LocalTime? = null,
            stopMinutesSinceMidnight: LocalTime? = null,
            daysOfWeekMask: Set<DayOfWeek>? = null,
            priority: DvrPriority? = null,
            retentionDays: Long? = null,
            directory: String? = null,
            owner: String?,
            creator: String? = null,
            configId: DvrConfigId? = null,
            comment: String? = null,
        ): TimerecRule {
            startMinutesSinceMidnight?.requireDvrWholeMinute()
            stopMinutesSinceMidnight?.requireDvrWholeMinute()
            retentionDays?.let { requireDvrU32("TimerecRule retentionDays", it) }
            return TimerecRule(
                id = id,
                enabled = enabled,
                name = name,
                title = title,
                channelId = channelId,
                startMinutesSinceMidnight = startMinutesSinceMidnight,
                stopMinutesSinceMidnight = stopMinutesSinceMidnight,
                daysOfWeekMask = daysOfWeekMask?.toDvrImmutableDays(),
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

/** Freshness and synchronization state of DVR entries and recording rules. */
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

/** Returns this state's current or retained DVR snapshot for display without copying it. */
public val DvrRepositoryState.dvrSnapshotForDisplay: DvrSnapshot?
    get() = when (this) {
        DvrRepositoryState.Empty -> null
        is DvrRepositoryState.Synchronizing -> staleSnapshot
        is DvrRepositoryState.Current -> snapshot
        is DvrRepositoryState.Stale -> snapshot
    }

/** Describes the provenance and synchronization state of [dvrSnapshotForDisplay]. */
public val DvrRepositoryState.dvrSnapshotAuthority: RetainedMetadataAuthority
    get() = when (this) {
        DvrRepositoryState.Empty -> RetainedMetadataAuthority.ABSENT
        is DvrRepositoryState.Synchronizing -> if (staleSnapshot == null) {
            RetainedMetadataAuthority.SYNCHRONIZING_WITHOUT_RETAINED_DATA
        } else {
            RetainedMetadataAuthority.SYNCHRONIZING_WITH_RETAINED_DATA
        }
        is DvrRepositoryState.Current -> RetainedMetadataAuthority.CURRENT
        is DvrRepositoryState.Stale -> RetainedMetadataAuthority.STALE
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

/** DVR commands for the selected server profile. */
public interface DvrRepository {
    /**
     * Hot, best-effort lifecycle changes after initial synchronization, without replay.
     * Slow collectors may lose older changes (buffer 64); observation snapshots remain authoritative.
     * Initial snapshots and reconnect resynchronization never produce changes.
     */
    public val changes: Flow<DvrChange>

    /** Schedules one DVR entry and waits for authoritative stream confirmation. */
    public suspend fun scheduleEntry(
        currentSession: CurrentSessionObservation,
        request: DvrScheduleRequest,
    ): DvrMutationResult<DvrEntryId>

    /** Changes one DVR entry and waits for authoritative stream confirmation. */
    public suspend fun updateEntry(
        currentSession: CurrentSessionObservation,
        id: DvrEntryId,
        update: DvrEntryUpdate,
    ): DvrMutationResult<Unit>

    /** Stops one recording and waits for authoritative stream confirmation. */
    public suspend fun stopEntry(
        currentSession: CurrentSessionObservation,
        id: DvrEntryId,
    ): DvrMutationResult<Unit>

    /** Cancels one DVR entry and waits for authoritative stream confirmation. */
    public suspend fun cancelEntry(
        currentSession: CurrentSessionObservation,
        id: DvrEntryId,
    ): DvrMutationResult<Unit>

    /**
     * Deletes one DVR entry and waits for entry removal or a full file-missing update.
     *
     * Server log retention may keep a deleted recording's entry after its file is removed, so a
     * [DvrMutationResult.Confirmed] result can leave the entry observable as
     * [DvrEntryState.FILE_MISSING]. Deleting an entry that is already file-missing may confirm on
     * the next full update for that entry.
     */
    public suspend fun deleteEntry(
        currentSession: CurrentSessionObservation,
        id: DvrEntryId,
    ): DvrMutationResult<Unit>

    /** Creates one automatic-recording rule and waits for authoritative stream confirmation. */
    public suspend fun createAutorecRule(
        currentSession: CurrentSessionObservation,
        request: AutorecRuleCreate,
    ): DvrMutationResult<AutorecRuleId>

    /** Changes one automatic-recording rule and waits for authoritative stream confirmation. */
    public suspend fun updateAutorecRule(
        currentSession: CurrentSessionObservation,
        id: AutorecRuleId,
        update: AutorecRuleUpdate,
    ): DvrMutationResult<Unit>

    /** Deletes one automatic-recording rule and waits for authoritative stream confirmation. */
    public suspend fun deleteAutorecRule(
        currentSession: CurrentSessionObservation,
        id: AutorecRuleId,
    ): DvrMutationResult<Unit>

    /** Creates one time-based recording rule and waits for authoritative stream confirmation. */
    public suspend fun createTimerecRule(
        currentSession: CurrentSessionObservation,
        request: TimerecRuleCreate,
    ): DvrMutationResult<TimerecRuleId>

    /** Changes one time-based recording rule and waits for authoritative stream confirmation. */
    public suspend fun updateTimerecRule(
        currentSession: CurrentSessionObservation,
        id: TimerecRuleId,
        update: TimerecRuleUpdate,
    ): DvrMutationResult<Unit>

    /** Deletes one time-based recording rule and waits for authoritative stream confirmation. */
    public suspend fun deleteTimerecRule(
        currentSession: CurrentSessionObservation,
        id: TimerecRuleId,
    ): DvrMutationResult<Unit>

}

internal abstract class CommandBackedDvrRepository(
    private val mutations: DvrMutationCommands = DvrMutationCommands.None,
    private val resolveGeneration: (CurrentSessionObservation) -> GatewayGeneration? = { null },
) : DvrRepository {
    final override suspend fun scheduleEntry(
        currentSession: CurrentSessionObservation,
        request: DvrScheduleRequest,
    ): DvrMutationResult<DvrEntryId> = resolveGeneration(currentSession)?.let { generation ->
        mutations.scheduleEntry(generation, request)
    } ?: DvrMutationResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, mayHaveApplied = false)

    final override suspend fun updateEntry(
        currentSession: CurrentSessionObservation,
        id: DvrEntryId,
        update: DvrEntryUpdate,
    ): DvrMutationResult<Unit> = resolveGeneration(currentSession)?.let { generation ->
        mutations.updateEntry(generation, id, update)
    } ?: DvrMutationResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, mayHaveApplied = false)

    final override suspend fun stopEntry(
        currentSession: CurrentSessionObservation,
        id: DvrEntryId,
    ): DvrMutationResult<Unit> = resolveGeneration(currentSession)?.let { generation ->
        mutations.stopEntry(generation, id)
    } ?: DvrMutationResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, mayHaveApplied = false)

    final override suspend fun cancelEntry(
        currentSession: CurrentSessionObservation,
        id: DvrEntryId,
    ): DvrMutationResult<Unit> = resolveGeneration(currentSession)?.let { generation ->
        mutations.cancelEntry(generation, id)
    } ?: DvrMutationResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, mayHaveApplied = false)

    final override suspend fun deleteEntry(
        currentSession: CurrentSessionObservation,
        id: DvrEntryId,
    ): DvrMutationResult<Unit> = resolveGeneration(currentSession)?.let { generation ->
        mutations.deleteEntry(generation, id)
    } ?: DvrMutationResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, mayHaveApplied = false)

    final override suspend fun createAutorecRule(
        currentSession: CurrentSessionObservation,
        request: AutorecRuleCreate,
    ): DvrMutationResult<AutorecRuleId> = resolveGeneration(currentSession)?.let { generation ->
        mutations.createAutorecRule(generation, request)
    } ?: DvrMutationResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, mayHaveApplied = false)

    final override suspend fun updateAutorecRule(
        currentSession: CurrentSessionObservation,
        id: AutorecRuleId,
        update: AutorecRuleUpdate,
    ): DvrMutationResult<Unit> = resolveGeneration(currentSession)?.let { generation ->
        mutations.updateAutorecRule(generation, id, update)
    } ?: DvrMutationResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, mayHaveApplied = false)

    final override suspend fun deleteAutorecRule(
        currentSession: CurrentSessionObservation,
        id: AutorecRuleId,
    ): DvrMutationResult<Unit> = resolveGeneration(currentSession)?.let { generation ->
        mutations.deleteAutorecRule(generation, id)
    } ?: DvrMutationResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, mayHaveApplied = false)

    final override suspend fun createTimerecRule(
        currentSession: CurrentSessionObservation,
        request: TimerecRuleCreate,
    ): DvrMutationResult<TimerecRuleId> = resolveGeneration(currentSession)?.let { generation ->
        mutations.createTimerecRule(generation, request)
    } ?: DvrMutationResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, mayHaveApplied = false)

    final override suspend fun updateTimerecRule(
        currentSession: CurrentSessionObservation,
        id: TimerecRuleId,
        update: TimerecRuleUpdate,
    ): DvrMutationResult<Unit> = resolveGeneration(currentSession)?.let { generation ->
        mutations.updateTimerecRule(generation, id, update)
    } ?: DvrMutationResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, mayHaveApplied = false)

    final override suspend fun deleteTimerecRule(
        currentSession: CurrentSessionObservation,
        id: TimerecRuleId,
    ): DvrMutationResult<Unit> = resolveGeneration(currentSession)?.let { generation ->
        mutations.deleteTimerecRule(generation, id)
    } ?: DvrMutationResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, mayHaveApplied = false)

}

private fun requireDvrU32(name: String, value: Long) {
    require(value in 0L..DVR_U32_MAX) { "$name must be an unsigned 32-bit value" }
}

private fun <T> Collection<T>.toDvrImmutableList(): List<T> =
    Collections.unmodifiableList(ArrayList(this))
