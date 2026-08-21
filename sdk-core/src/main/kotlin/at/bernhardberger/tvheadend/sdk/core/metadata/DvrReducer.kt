package at.bernhardberger.tvheadend.sdk.core.metadata

import at.bernhardberger.tvheadend.sdk.core.AutorecRule
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrRecordingFile
import at.bernhardberger.tvheadend.sdk.core.DvrSnapshot
import at.bernhardberger.tvheadend.sdk.core.EpgEpisode
import at.bernhardberger.tvheadend.sdk.core.EpgRating
import at.bernhardberger.tvheadend.sdk.core.TimerecRule
import at.bernhardberger.tvheadend.sdk.core.gateway.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrConfigId
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrSubscriptionError
import at.bernhardberger.tvheadend.sdk.core.gateway.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayAutorecRule
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrEntry
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrRecordingFile
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayTimerecRule
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.TimerecRuleId
import java.util.Collections
import kotlin.time.Duration
import kotlin.time.Instant

@ConsistentCopyVisibility
internal data class ReducedDvrEntry private constructor(
    internal val id: DvrEntryId,
    internal val uuid: String?,
    internal val enabled: Boolean?,
    internal val channelId: ChannelId?,
    internal val channelName: String?,
    internal val eventId: EventId?,
    internal val autorecRuleId: AutorecRuleId?,
    internal val timerecRuleId: TimerecRuleId?,
    internal val start: Instant?,
    internal val stop: Instant?,
    internal val startExtraMinutes: Long?,
    internal val stopExtraMinutes: Long?,
    internal val retentionDays: Long?,
    internal val removalDays: Long?,
    internal val priority: Long?,
    internal val contentType: Long?,
    internal val ageRating: Long?,
    internal val ratingLabel: String?,
    internal val ratingIcon: String?,
    internal val ratingAuthority: String?,
    internal val ratingCountry: String?,
    internal val playCount: Long?,
    internal val playPosition: Duration?,
    internal val seasonNumber: Long?,
    internal val episodeNumber: Long?,
    internal val episodeCount: Long?,
    internal val partNumber: Long?,
    internal val partCount: Long?,
    internal val title: String?,
    internal val description: String?,
    internal val summary: String?,
    internal val subtitle: String?,
    internal val owner: String?,
    internal val creator: String?,
    internal val comment: String?,
    internal val image: String?,
    internal val fanartImage: String?,
    internal val copyrightYear: Long?,
    internal val files: List<GatewayDvrRecordingFile>?,
    internal val path: String?,
    internal val configId: DvrConfigId?,
    internal val duplicate: Long?,
    internal val state: DvrEntryState?,
    internal val failure: GatewayDvrFailure?,
    internal val subscriptionError: DvrSubscriptionError?,
    internal val streamErrors: Long?,
    internal val dataErrors: Long?,
    internal val dataSizeBytes: Long?,
) {
    override fun toString(): String = "ReducedDvrEntry(<redacted>)"

    internal fun mergeFromAdd(entry: GatewayDvrEntry): ReducedDvrEntry? = copyMerged(
        entry = entry,
        resetBrowseScalars = true,
    )

    internal fun mergeFromUpdate(entry: GatewayDvrEntry): ReducedDvrEntry? = copyMerged(
        entry = entry,
        resetBrowseScalars = false,
    )

    internal fun toPublicOrNull(): DvrEntry? {
        if (hasInvalidTiming()) return null
        return DvrEntry.create(
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
            rating = ratingOrNull(),
            playCount = playCount,
            playPosition = playPosition,
            episode = episodeOrNull(),
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
            files = files?.mapNotNull(GatewayDvrRecordingFile::toPublicOrNull),
            path = path,
            configId = configId,
            duplicate = duplicate,
            state = resolvedState(),
            subscriptionError = subscriptionError,
            streamErrors = streamErrors,
            dataErrors = dataErrors,
            dataSizeBytes = dataSizeBytes,
        )
    }

    private fun copyMerged(entry: GatewayDvrEntry, resetBrowseScalars: Boolean): ReducedDvrEntry? {
        val candidate = ReducedDvrEntry(
            id = id,
            uuid = entry.uuid ?: uuid,
            enabled = if (resetBrowseScalars) entry.enabled else entry.enabled ?: enabled,
            channelId = entry.channelId ?: channelId,
            channelName = entry.channelName ?: channelName,
            eventId = entry.eventId ?: eventId,
            autorecRuleId = entry.autorecRuleId ?: autorecRuleId,
            timerecRuleId = entry.timerecRuleId ?: timerecRuleId,
            start = entry.start ?: start,
            stop = entry.stop ?: stop,
            startExtraMinutes = if (resetBrowseScalars) {
                entry.startExtraMinutes
            } else {
                entry.startExtraMinutes ?: startExtraMinutes
            },
            stopExtraMinutes = if (resetBrowseScalars) {
                entry.stopExtraMinutes
            } else {
                entry.stopExtraMinutes ?: stopExtraMinutes
            },
            retentionDays = entry.retentionDays ?: retentionDays,
            removalDays = entry.removalDays ?: removalDays,
            priority = entry.priority ?: priority,
            contentType = if (resetBrowseScalars) entry.contentType else entry.contentType ?: contentType,
            ageRating = entry.ageRating ?: ageRating,
            ratingLabel = entry.ratingLabel ?: ratingLabel,
            ratingIcon = entry.ratingIcon ?: ratingIcon,
            ratingAuthority = entry.ratingAuthority ?: ratingAuthority,
            ratingCountry = entry.ratingCountry ?: ratingCountry,
            playCount = entry.playCount ?: playCount,
            playPosition = entry.playPosition ?: playPosition,
            seasonNumber = entry.seasonNumber ?: seasonNumber,
            episodeNumber = entry.episodeNumber ?: episodeNumber,
            episodeCount = entry.episodeCount ?: episodeCount,
            partNumber = entry.partNumber ?: partNumber,
            partCount = entry.partCount ?: partCount,
            title = entry.title ?: title,
            description = entry.description ?: description,
            summary = entry.summary ?: summary,
            subtitle = entry.subtitle ?: subtitle,
            owner = entry.owner ?: owner,
            creator = entry.creator ?: creator,
            comment = entry.comment ?: comment,
            image = entry.image ?: image,
            fanartImage = entry.fanartImage ?: fanartImage,
            copyrightYear = entry.copyrightYear ?: copyrightYear,
            files = entry.files?.toImmutableList() ?: files,
            path = entry.path ?: path,
            configId = entry.configId ?: configId,
            duplicate = entry.duplicate ?: duplicate,
            state = entry.state ?: state,
            failure = entry.failure ?: failure,
            subscriptionError = if (resetBrowseScalars) {
                entry.subscriptionError
            } else {
                entry.subscriptionError ?: subscriptionError
            },
            streamErrors = entry.streamErrors ?: streamErrors,
            dataErrors = entry.dataErrors ?: dataErrors,
            dataSizeBytes = entry.dataSizeBytes ?: dataSizeBytes,
        )
        return candidate.takeUnless(ReducedDvrEntry::hasInvalidTiming)
    }

    private fun hasInvalidTiming(): Boolean = start != null && stop != null && stop < start

    private fun resolvedState(): DvrEntryState? = when (failure) {
        GatewayDvrFailure.FILE_MISSING -> DvrEntryState.FILE_MISSING
        GatewayDvrFailure.PRESENT -> when (state) {
            DvrEntryState.RECORDING -> DvrEntryState.RECORDING_ERROR
            DvrEntryState.COMPLETED -> DvrEntryState.COMPLETED_ERROR
            null -> DvrEntryState.UNKNOWN
            else -> state
        }
        GatewayDvrFailure.NONE, null -> state
    }

    private fun ratingOrNull(): EpgRating? {
        if (
            ageRating == null &&
            ratingLabel == null &&
            ratingIcon == null &&
            ratingAuthority == null &&
            ratingCountry == null
        ) {
            return null
        }
        return EpgRating(
            age = ageRating,
            label = ratingLabel,
            icon = ratingIcon,
            authority = ratingAuthority,
            country = ratingCountry,
            stars = null,
        )
    }

    private fun episodeOrNull(): EpgEpisode? {
        if (
            seasonNumber == null &&
            episodeNumber == null &&
            episodeCount == null &&
            partNumber == null &&
            partCount == null
        ) {
            return null
        }
        return EpgEpisode(
            id = null,
            seriesLinkId = null,
            seasonNumber = seasonNumber,
            seasonCount = null,
            episodeNumber = episodeNumber,
            episodeCount = episodeCount,
            partNumber = partNumber,
            partCount = partCount,
            onscreen = null,
        )
    }

    internal companion object {
        internal fun fromAdd(entry: GatewayDvrEntry): ReducedDvrEntry? = ReducedDvrEntry(
            id = entry.id,
            uuid = entry.uuid,
            enabled = entry.enabled,
            channelId = entry.channelId,
            channelName = entry.channelName,
            eventId = entry.eventId,
            autorecRuleId = entry.autorecRuleId,
            timerecRuleId = entry.timerecRuleId,
            start = entry.start,
            stop = entry.stop,
            startExtraMinutes = entry.startExtraMinutes,
            stopExtraMinutes = entry.stopExtraMinutes,
            retentionDays = entry.retentionDays,
            removalDays = entry.removalDays,
            priority = entry.priority,
            contentType = entry.contentType,
            ageRating = entry.ageRating,
            ratingLabel = entry.ratingLabel,
            ratingIcon = entry.ratingIcon,
            ratingAuthority = entry.ratingAuthority,
            ratingCountry = entry.ratingCountry,
            playCount = entry.playCount,
            playPosition = entry.playPosition,
            seasonNumber = entry.seasonNumber,
            episodeNumber = entry.episodeNumber,
            episodeCount = entry.episodeCount,
            partNumber = entry.partNumber,
            partCount = entry.partCount,
            title = entry.title,
            description = entry.description,
            summary = entry.summary,
            subtitle = entry.subtitle,
            owner = entry.owner,
            creator = entry.creator,
            comment = entry.comment,
            image = entry.image,
            fanartImage = entry.fanartImage,
            copyrightYear = entry.copyrightYear,
            files = entry.files?.toImmutableList(),
            path = entry.path,
            configId = entry.configId,
            duplicate = entry.duplicate,
            state = entry.state,
            failure = entry.failure,
            subscriptionError = entry.subscriptionError,
            streamErrors = entry.streamErrors,
            dataErrors = entry.dataErrors,
            dataSizeBytes = entry.dataSizeBytes,
        ).takeUnless(ReducedDvrEntry::hasInvalidTiming)

        internal fun fromUpdate(entry: GatewayDvrEntry): ReducedDvrEntry? = fromAdd(entry)
    }
}

@ConsistentCopyVisibility
internal data class ReducedAutorecRule private constructor(
    internal val id: AutorecRuleId,
    internal val enabled: Boolean?,
    internal val maxDuration: Duration?,
    internal val minDuration: Duration?,
    internal val retentionDays: Long?,
    internal val removalDays: Long?,
    internal val daysOfWeekMask: Long?,
    internal val approximateStartMinutesSinceMidnight: Int?,
    internal val startMinutesSinceMidnight: Int?,
    internal val startWindowEndMinutesSinceMidnight: Int?,
    internal val priority: Long?,
    internal val startExtraMinutes: Long?,
    internal val stopExtraMinutes: Long?,
    internal val duplicateDetection: Long?,
    internal val maximumRecordingCount: Long?,
    internal val broadcastType: Long?,
    internal val comment: String?,
    internal val title: String?,
    internal val fullText: Boolean?,
    internal val mergeText: Boolean?,
    internal val name: String?,
    internal val directory: String?,
    internal val owner: String?,
    internal val creator: String?,
    internal val channelId: ChannelId?,
    internal val seriesLinkUri: String?,
    internal val configId: DvrConfigId?,
) {
    override fun toString(): String = "ReducedAutorecRule(<redacted>)"

    internal fun replace(rule: GatewayAutorecRule): ReducedAutorecRule = fromAdd(rule)

    internal fun merge(rule: GatewayAutorecRule): ReducedAutorecRule = ReducedAutorecRule(
        id = id,
        enabled = rule.enabled ?: enabled,
        maxDuration = rule.maxDuration ?: maxDuration,
        minDuration = rule.minDuration ?: minDuration,
        retentionDays = rule.retentionDays ?: retentionDays,
        removalDays = rule.removalDays ?: removalDays,
        daysOfWeekMask = rule.daysOfWeekMask ?: daysOfWeekMask,
        approximateStartMinutesSinceMidnight =
            rule.approximateStartMinutesSinceMidnight ?: approximateStartMinutesSinceMidnight,
        startMinutesSinceMidnight = rule.startMinutesSinceMidnight ?: startMinutesSinceMidnight,
        startWindowEndMinutesSinceMidnight =
            rule.startWindowEndMinutesSinceMidnight ?: startWindowEndMinutesSinceMidnight,
        priority = rule.priority ?: priority,
        startExtraMinutes = rule.startExtraMinutes ?: startExtraMinutes,
        stopExtraMinutes = rule.stopExtraMinutes ?: stopExtraMinutes,
        duplicateDetection = rule.duplicateDetection ?: duplicateDetection,
        maximumRecordingCount = rule.maximumRecordingCount ?: maximumRecordingCount,
        broadcastType = rule.broadcastType ?: broadcastType,
        comment = rule.comment ?: comment,
        title = rule.title ?: title,
        fullText = rule.fullText ?: fullText,
        mergeText = rule.mergeText ?: mergeText,
        name = rule.name ?: name,
        directory = rule.directory ?: directory,
        owner = rule.owner ?: owner,
        creator = rule.creator ?: creator,
        channelId = rule.channelId ?: channelId,
        seriesLinkUri = rule.seriesLinkUri ?: seriesLinkUri,
        configId = rule.configId ?: configId,
    )

    internal fun toPublic(): AutorecRule = AutorecRule.create(
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

    internal companion object {
        internal fun fromAdd(rule: GatewayAutorecRule): ReducedAutorecRule = ReducedAutorecRule(
            id = rule.id,
            enabled = rule.enabled,
            maxDuration = rule.maxDuration,
            minDuration = rule.minDuration,
            retentionDays = rule.retentionDays,
            removalDays = rule.removalDays,
            daysOfWeekMask = rule.daysOfWeekMask,
            approximateStartMinutesSinceMidnight = rule.approximateStartMinutesSinceMidnight,
            startMinutesSinceMidnight = rule.startMinutesSinceMidnight,
            startWindowEndMinutesSinceMidnight = rule.startWindowEndMinutesSinceMidnight,
            priority = rule.priority,
            startExtraMinutes = rule.startExtraMinutes,
            stopExtraMinutes = rule.stopExtraMinutes,
            duplicateDetection = rule.duplicateDetection,
            maximumRecordingCount = rule.maximumRecordingCount,
            broadcastType = rule.broadcastType,
            comment = rule.comment,
            title = rule.title,
            fullText = rule.fullText,
            mergeText = rule.mergeText,
            name = rule.name,
            directory = rule.directory,
            owner = rule.owner,
            creator = rule.creator,
            channelId = rule.channelId,
            seriesLinkUri = rule.seriesLinkUri,
            configId = rule.configId,
        )

        internal fun fromUpdate(rule: GatewayAutorecRule): ReducedAutorecRule = fromAdd(rule)
    }
}

@ConsistentCopyVisibility
internal data class ReducedTimerecRule private constructor(
    internal val id: TimerecRuleId,
    internal val enabled: Boolean?,
    internal val name: String?,
    internal val title: String?,
    internal val channelId: ChannelId?,
    internal val startMinutesSinceMidnight: Int?,
    internal val stopMinutesSinceMidnight: Int?,
    internal val daysOfWeekMask: Long?,
    internal val priority: Long?,
    internal val retentionDays: Long?,
    internal val directory: String?,
    internal val owner: String?,
    internal val creator: String?,
    internal val configId: DvrConfigId?,
    internal val comment: String?,
) {
    override fun toString(): String = "ReducedTimerecRule(<redacted>)"

    internal fun replace(rule: GatewayTimerecRule): ReducedTimerecRule = fromAdd(rule)

    internal fun merge(rule: GatewayTimerecRule): ReducedTimerecRule = ReducedTimerecRule(
        id = id,
        enabled = rule.enabled ?: enabled,
        name = rule.name ?: name,
        title = rule.title ?: title,
        channelId = rule.channelId ?: channelId,
        startMinutesSinceMidnight = rule.startMinutesSinceMidnight ?: startMinutesSinceMidnight,
        stopMinutesSinceMidnight = rule.stopMinutesSinceMidnight ?: stopMinutesSinceMidnight,
        daysOfWeekMask = rule.daysOfWeekMask ?: daysOfWeekMask,
        priority = rule.priority ?: priority,
        retentionDays = rule.retentionDays ?: retentionDays,
        directory = rule.directory ?: directory,
        owner = rule.owner ?: owner,
        creator = rule.creator ?: creator,
        configId = rule.configId ?: configId,
        comment = rule.comment ?: comment,
    )

    internal fun toPublicOrNull(): TimerecRule? {
        if (hasInvalidMinutes()) return null
        return TimerecRule.create(
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

    private fun hasInvalidMinutes(): Boolean =
        startMinutesSinceMidnight.outOfDay() || stopMinutesSinceMidnight.outOfDay()

    internal companion object {
        internal fun fromAdd(rule: GatewayTimerecRule): ReducedTimerecRule = ReducedTimerecRule(
            id = rule.id,
            enabled = rule.enabled,
            name = rule.name,
            title = rule.title,
            channelId = rule.channelId,
            startMinutesSinceMidnight = rule.startMinutesSinceMidnight,
            stopMinutesSinceMidnight = rule.stopMinutesSinceMidnight,
            daysOfWeekMask = rule.daysOfWeekMask,
            priority = rule.priority,
            retentionDays = rule.retentionDays,
            directory = rule.directory,
            owner = rule.owner,
            creator = rule.creator,
            configId = rule.configId,
            comment = rule.comment,
        )

        internal fun fromUpdate(rule: GatewayTimerecRule): ReducedTimerecRule? =
            fromAdd(rule).takeUnless(ReducedTimerecRule::hasInvalidMinutes)
    }
}

internal class DvrReducer {
    private val entries = linkedMapOf<DvrEntryId, ReducedDvrEntry>()
    private val autorecRules = linkedMapOf<AutorecRuleId, ReducedAutorecRule>()
    private val timerecRules = linkedMapOf<TimerecRuleId, ReducedTimerecRule>()

    internal fun clear() {
        entries.clear()
        autorecRules.clear()
        timerecRules.clear()
    }

    internal fun accept(event: MetadataEvent) {
        when (event) {
            is MetadataEvent.DvrEntryAdded -> acceptEntryAdd(event.entry)
            is MetadataEvent.DvrEntryUpdated -> acceptEntryUpdate(event.entry)
            is MetadataEvent.DvrEntryDeleted -> entries.remove(event.entryId)
            is MetadataEvent.AutorecRuleAdded -> acceptAutorecAdd(event.rule)
            is MetadataEvent.AutorecRuleUpdated -> acceptAutorecUpdate(event.rule)
            is MetadataEvent.AutorecRuleDeleted -> autorecRules.remove(event.ruleId)
            is MetadataEvent.TimerecRuleAdded -> acceptTimerecAdd(event.rule)
            is MetadataEvent.TimerecRuleUpdated -> acceptTimerecUpdate(event.rule)
            is MetadataEvent.TimerecRuleDeleted -> timerecRules.remove(event.ruleId)
            is MetadataEvent.ChannelAdded,
            is MetadataEvent.ChannelUpdated,
            is MetadataEvent.ChannelDeleted,
            is MetadataEvent.TagAdded,
            is MetadataEvent.TagUpdated,
            is MetadataEvent.TagDeleted,
            is MetadataEvent.EventAdded,
            is MetadataEvent.EventUpdated,
            is MetadataEvent.EventDeleted,
            is MetadataEvent.InitialSyncCompleted,
            -> Unit
        }
    }

    internal fun snapshot(): DvrSnapshot = DvrSnapshot.create(
        entries = entries.values.mapNotNull(ReducedDvrEntry::toPublicOrNull),
        autorecRules = autorecRules.values.map(ReducedAutorecRule::toPublic),
        timerecRules = timerecRules.values.mapNotNull(ReducedTimerecRule::toPublicOrNull),
    )

    private fun acceptEntryAdd(entry: GatewayDvrEntry) {
        val current = entries[entry.id]
        val candidate = if (current == null) {
            ReducedDvrEntry.fromAdd(entry)
        } else {
            current.mergeFromAdd(entry)
        } ?: return
        entries[entry.id] = candidate
    }

    private fun acceptEntryUpdate(entry: GatewayDvrEntry) {
        val current = entries[entry.id]
        val candidate = if (current == null) {
            ReducedDvrEntry.fromUpdate(entry)
        } else {
            current.mergeFromUpdate(entry)
        } ?: return
        entries[entry.id] = candidate
    }

    private fun acceptAutorecAdd(rule: GatewayAutorecRule) {
        autorecRules[rule.id] = autorecRules[rule.id]?.replace(rule) ?: ReducedAutorecRule.fromAdd(rule)
    }

    private fun acceptAutorecUpdate(rule: GatewayAutorecRule) {
        autorecRules[rule.id] = autorecRules[rule.id]?.merge(rule) ?: ReducedAutorecRule.fromUpdate(rule)
    }

    private fun acceptTimerecAdd(rule: GatewayTimerecRule) {
        val candidate = ReducedTimerecRule.fromAdd(rule)
        if (candidate.toPublicOrNull() == null) return
        timerecRules[rule.id] = candidate
    }

    private fun acceptTimerecUpdate(rule: GatewayTimerecRule) {
        val current = timerecRules[rule.id]
        val candidate = if (current == null) {
            ReducedTimerecRule.fromUpdate(rule)
        } else {
            current.merge(rule).takeUnless { it.toPublicOrNull() == null }
        } ?: return
        timerecRules[rule.id] = candidate
    }
}

private fun GatewayDvrRecordingFile.toPublicOrNull(): DvrRecordingFile? {
    if (start != null && stop != null && stop < start) return null
    return DvrRecordingFile(
        fileId = fileId,
        path = path,
        start = start,
        stop = stop,
        sizeBytes = sizeBytes,
    )
}

private fun Int?.outOfDay(): Boolean = this != null && this !in 0..1_440

private fun <T> Collection<T>.toImmutableList(): List<T> =
    Collections.unmodifiableList(ArrayList(this))
