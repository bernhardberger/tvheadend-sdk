package at.bernhardberger.tvheadend.sdk.core.gateway

import kotlin.time.Duration
import kotlin.time.Instant

internal typealias AutorecRuleId = at.bernhardberger.tvheadend.sdk.core.AutorecRuleId
internal typealias ChannelId = at.bernhardberger.tvheadend.sdk.core.ChannelId
internal typealias DvrConfigId = at.bernhardberger.tvheadend.sdk.core.DvrConfigId
internal typealias DvrEntryId = at.bernhardberger.tvheadend.sdk.core.DvrEntryId
internal typealias DvrEntryState = at.bernhardberger.tvheadend.sdk.core.DvrEntryState
internal typealias DvrSubscriptionError = at.bernhardberger.tvheadend.sdk.core.DvrSubscriptionError

internal enum class GatewayDvrFailure {
    NONE,
    FILE_MISSING,
    PRESENT,
}

internal enum class GatewayDvrUpdateProvenance {
    FULL,
    STATS_ONLY,
}
internal typealias EpgEpisodeId = at.bernhardberger.tvheadend.sdk.core.EpgEpisodeId
internal typealias EpgSeriesLinkId = at.bernhardberger.tvheadend.sdk.core.EpgSeriesLinkId
internal typealias EventId = at.bernhardberger.tvheadend.sdk.core.EventId
internal typealias TagId = at.bernhardberger.tvheadend.sdk.core.ChannelTagId
internal typealias TimerecRuleId = at.bernhardberger.tvheadend.sdk.core.TimerecRuleId

internal sealed interface MetadataEvent {
    public val generation: GatewayGeneration

    public class ChannelAdded(
        override val generation: GatewayGeneration,
        internal val channel: GatewayChannelMetadata,
    ) : MetadataEvent {
        override fun toString(): String = "MetadataEvent.ChannelAdded(<redacted>)"
    }

    public class ChannelUpdated(
        override val generation: GatewayGeneration,
        internal val channel: GatewayChannelMetadata,
    ) : MetadataEvent {
        override fun toString(): String = "MetadataEvent.ChannelUpdated(<redacted>)"
    }

    public class ChannelDeleted(
        override val generation: GatewayGeneration,
        internal val channelId: ChannelId,
    ) : MetadataEvent {
        override fun toString(): String = "MetadataEvent.ChannelDeleted(<redacted>)"
    }

    public class TagAdded(
        override val generation: GatewayGeneration,
        internal val tag: GatewayTagMetadata,
    ) : MetadataEvent {
        override fun toString(): String = "MetadataEvent.TagAdded(<redacted>)"
    }

    public class TagUpdated(
        override val generation: GatewayGeneration,
        internal val tag: GatewayTagMetadata,
    ) : MetadataEvent {
        override fun toString(): String = "MetadataEvent.TagUpdated(<redacted>)"
    }

    public class TagDeleted(
        override val generation: GatewayGeneration,
        internal val tagId: TagId,
    ) : MetadataEvent {
        override fun toString(): String = "MetadataEvent.TagDeleted(<redacted>)"
    }

    public class EventAdded(
        override val generation: GatewayGeneration,
        internal val event: GatewayEpgEvent,
    ) : MetadataEvent {
        override fun toString(): String = "MetadataEvent.EventAdded(<redacted>)"
    }

    public class EventUpdated(
        override val generation: GatewayGeneration,
        internal val event: GatewayEpgUpdate,
    ) : MetadataEvent {
        override fun toString(): String = "MetadataEvent.EventUpdated(<redacted>)"
    }

    public class EventDeleted(
        override val generation: GatewayGeneration,
        internal val eventId: EventId,
    ) : MetadataEvent {
        override fun toString(): String = "MetadataEvent.EventDeleted(<redacted>)"
    }

    public class InitialSyncCompleted(
        override val generation: GatewayGeneration,
    ) : MetadataEvent {
        override fun toString(): String = "MetadataEvent.InitialSyncCompleted(<redacted>)"
    }

    public class DvrEntryAdded(
        override val generation: GatewayGeneration,
        internal val entry: GatewayDvrEntry,
    ) : MetadataEvent {
        override fun toString(): String = "MetadataEvent.DvrEntryAdded(<redacted>)"
    }

    public class DvrEntryUpdated(
        override val generation: GatewayGeneration,
        internal val entry: GatewayDvrEntry,
        internal val provenance: GatewayDvrUpdateProvenance,
    ) : MetadataEvent {
        override fun toString(): String = "MetadataEvent.DvrEntryUpdated(<redacted>)"
    }

    public class DvrEntryDeleted(
        override val generation: GatewayGeneration,
        internal val entryId: DvrEntryId,
    ) : MetadataEvent {
        override fun toString(): String = "MetadataEvent.DvrEntryDeleted(<redacted>)"
    }

    public class AutorecRuleAdded(
        override val generation: GatewayGeneration,
        internal val rule: GatewayAutorecRule,
    ) : MetadataEvent {
        override fun toString(): String = "MetadataEvent.AutorecRuleAdded(<redacted>)"
    }

    public class AutorecRuleUpdated(
        override val generation: GatewayGeneration,
        internal val rule: GatewayAutorecRule,
    ) : MetadataEvent {
        override fun toString(): String = "MetadataEvent.AutorecRuleUpdated(<redacted>)"
    }

    public class AutorecRuleDeleted(
        override val generation: GatewayGeneration,
        internal val ruleId: AutorecRuleId,
    ) : MetadataEvent {
        override fun toString(): String = "MetadataEvent.AutorecRuleDeleted(<redacted>)"
    }

    public class TimerecRuleAdded(
        override val generation: GatewayGeneration,
        internal val rule: GatewayTimerecRule,
    ) : MetadataEvent {
        override fun toString(): String = "MetadataEvent.TimerecRuleAdded(<redacted>)"
    }

    public class TimerecRuleUpdated(
        override val generation: GatewayGeneration,
        internal val rule: GatewayTimerecRule,
    ) : MetadataEvent {
        override fun toString(): String = "MetadataEvent.TimerecRuleUpdated(<redacted>)"
    }

    public class TimerecRuleDeleted(
        override val generation: GatewayGeneration,
        internal val ruleId: TimerecRuleId,
    ) : MetadataEvent {
        override fun toString(): String = "MetadataEvent.TimerecRuleDeleted(<redacted>)"
    }
}

internal class GatewayEpgEvent(
    internal val id: EventId,
    internal val channelId: ChannelId? = null,
    internal val start: Instant,
    internal val stop: Instant,
    internal val title: String? = null,
    internal val subtitle: String? = null,
    internal val summary: String? = null,
    internal val description: String? = null,
    internal val genre: String? = null,
    categories: List<String>? = null,
    keywords: List<String>? = null,
    internal val seriesLinkUri: String? = null,
    internal val episodeUri: String? = null,
    internal val contentType: Long? = null,
    internal val ageRating: Long? = null,
    internal val ratingLabel: String? = null,
    internal val ratingIcon: String? = null,
    internal val ratingAuthority: String? = null,
    internal val ratingCountry: String? = null,
    internal val starRating: Long? = null,
    internal val copyrightYear: Long? = null,
    internal val firstAired: Instant? = null,
    internal val isNew: Boolean? = null,
    internal val seasonNumber: Long? = null,
    internal val seasonCount: Long? = null,
    internal val episodeNumber: Long? = null,
    internal val episodeCount: Long? = null,
    internal val partNumber: Long? = null,
    internal val partCount: Long? = null,
    internal val episodeOnscreen: String? = null,
    internal val episodeId: EpgEpisodeId? = null,
    internal val seriesLinkId: EpgSeriesLinkId? = null,
    internal val image: String? = null,
    internal val dvrEntryId: DvrEntryId? = null,
    internal val nextEventId: EventId? = null,
) {
    internal val categories: List<String>? = categories?.toList()
    internal val keywords: List<String>? = keywords?.toList()

    override fun toString(): String = "GatewayEpgEvent(<redacted>)"
}

internal class GatewayEpgUpdate(
    internal val id: EventId,
    internal val channelId: ChannelId? = null,
    internal val start: Instant? = null,
    internal val stop: Instant? = null,
    internal val title: String? = null,
    internal val subtitle: String? = null,
    internal val summary: String? = null,
    internal val description: String? = null,
    internal val genre: String? = null,
    categories: List<String>? = null,
    keywords: List<String>? = null,
    internal val seriesLinkUri: String? = null,
    internal val episodeUri: String? = null,
    internal val contentType: Long? = null,
    internal val ageRating: Long? = null,
    internal val ratingLabel: String? = null,
    internal val ratingIcon: String? = null,
    internal val ratingAuthority: String? = null,
    internal val ratingCountry: String? = null,
    internal val starRating: Long? = null,
    internal val copyrightYear: Long? = null,
    internal val firstAired: Instant? = null,
    internal val isNew: Boolean? = null,
    internal val seasonNumber: Long? = null,
    internal val seasonCount: Long? = null,
    internal val episodeNumber: Long? = null,
    internal val episodeCount: Long? = null,
    internal val partNumber: Long? = null,
    internal val partCount: Long? = null,
    internal val episodeOnscreen: String? = null,
    internal val episodeId: EpgEpisodeId? = null,
    internal val seriesLinkId: EpgSeriesLinkId? = null,
    internal val image: String? = null,
    internal val dvrEntryId: DvrEntryId? = null,
    internal val nextEventId: EventId? = null,
) {
    internal val categories: List<String>? = categories?.toList()
    internal val keywords: List<String>? = keywords?.toList()

    override fun toString(): String = "GatewayEpgUpdate(<redacted>)"
}

internal class GatewayEpgQueryEvent(
    internal val id: EventId,
    internal val channelId: ChannelId? = null,
    internal val start: Instant,
    internal val stop: Instant,
    internal val title: String? = null,
    internal val subtitle: String? = null,
    internal val summary: String? = null,
    internal val description: String? = null,
    categories: List<String>? = null,
    keywords: List<String>? = null,
    internal val seriesLinkUri: String? = null,
    internal val episodeUri: String? = null,
    internal val contentType: Long? = null,
    internal val ageRating: Long? = null,
    internal val ratingLabel: String? = null,
    internal val ratingIcon: String? = null,
    internal val ratingAuthority: String? = null,
    internal val ratingCountry: String? = null,
    internal val starRating: Long? = null,
    internal val copyrightYear: Long? = null,
    internal val firstAired: Instant? = null,
    internal val isNew: Boolean? = null,
    internal val seasonNumber: Long? = null,
    internal val seasonCount: Long? = null,
    internal val episodeNumber: Long? = null,
    internal val episodeCount: Long? = null,
    internal val partNumber: Long? = null,
    internal val partCount: Long? = null,
    internal val episodeOnscreen: String? = null,
    internal val image: String? = null,
    internal val dvrEntryId: DvrEntryId? = null,
    internal val nextEventId: EventId? = null,
) {
    internal val categories: List<String>? = categories?.toList()
    internal val keywords: List<String>? = keywords?.toList()

    override fun toString(): String = "GatewayEpgQueryEvent(<redacted>)"
}

internal class GatewayChannelMetadata(
    internal val id: ChannelId,
    internal val name: String?,
    internal val uuid: String?,
    internal val number: Long?,
    internal val numberMinor: Long?,
    internal val icon: String?,
    internal val currentEventId: EventId?,
    internal val nextEventId: EventId?,
    services: List<GatewayChannelService>?,
    tagIds: List<TagId>?,
) {
    internal val services: List<GatewayChannelService>? = services?.toList()
    internal val tagIds: List<TagId>? = tagIds?.toList()

    override fun toString(): String = "GatewayChannelMetadata(<redacted>)"
}

internal class GatewayChannelService(
    internal val name: String,
    internal val type: String,
    internal val content: Long,
    internal val conditionalAccessId: Long?,
    internal val conditionalAccessName: String?,
    internal val providerName: String?,
) {
    override fun toString(): String = "GatewayChannelService(<redacted>)"
}

internal class GatewayTagMetadata(
    internal val id: TagId,
    internal val name: String?,
    internal val uuid: String?,
    internal val index: Long?,
    internal val icon: String?,
    internal val titledIcon: Boolean?,
    channelIds: List<ChannelId>?,
) {
    internal val channelIds: List<ChannelId>? = channelIds?.toList()

    override fun toString(): String = "GatewayTagMetadata(<redacted>)"
}

internal class GatewayDvrEntry(
    internal val id: DvrEntryId,
    internal val uuid: String? = null,
    internal val enabled: Boolean? = null,
    internal val channelId: ChannelId? = null,
    internal val channelName: String? = null,
    internal val eventId: EventId? = null,
    internal val autorecRuleId: AutorecRuleId? = null,
    internal val timerecRuleId: TimerecRuleId? = null,
    internal val start: Instant? = null,
    internal val stop: Instant? = null,
    internal val startExtraMinutes: Long? = null,
    internal val stopExtraMinutes: Long? = null,
    internal val retentionDays: Long? = null,
    internal val removalDays: Long? = null,
    internal val priority: Long? = null,
    internal val contentType: Long? = null,
    internal val ageRating: Long? = null,
    internal val ratingLabel: String? = null,
    internal val ratingIcon: String? = null,
    internal val ratingAuthority: String? = null,
    internal val ratingCountry: String? = null,
    internal val playCount: Long? = null,
    internal val playPosition: Duration? = null,
    internal val seasonNumber: Long? = null,
    internal val episodeNumber: Long? = null,
    internal val episodeCount: Long? = null,
    internal val partNumber: Long? = null,
    internal val partCount: Long? = null,
    internal val title: String? = null,
    internal val description: String? = null,
    internal val summary: String? = null,
    internal val subtitle: String? = null,
    internal val owner: String? = null,
    internal val creator: String? = null,
    internal val comment: String? = null,
    internal val image: String? = null,
    internal val fanartImage: String? = null,
    internal val copyrightYear: Long? = null,
    files: List<GatewayDvrRecordingFile>? = null,
    internal val path: String? = null,
    internal val configId: DvrConfigId? = null,
    internal val duplicate: Long? = null,
    internal val state: DvrEntryState? = null,
    internal val failure: GatewayDvrFailure? = null,
    internal val subscriptionError: DvrSubscriptionError? = null,
    internal val streamErrors: Long? = null,
    internal val dataErrors: Long? = null,
    internal val dataSizeBytes: Long? = null,
) {
    internal val files: List<GatewayDvrRecordingFile>? = files?.toList()

    override fun toString(): String = "GatewayDvrEntry(<redacted>)"
}

internal class GatewayDvrRecordingFile(
    internal val fileId: Long?,
    internal val path: String?,
    internal val start: Instant?,
    internal val stop: Instant?,
    internal val sizeBytes: Long?,
) {
    override fun toString(): String = "GatewayDvrRecordingFile(<redacted>)"
}

internal class GatewayAutorecRule(
    internal val id: AutorecRuleId,
    internal val enabled: Boolean? = null,
    internal val maxDuration: Duration? = null,
    internal val minDuration: Duration? = null,
    internal val retentionDays: Long? = null,
    internal val removalDays: Long? = null,
    internal val daysOfWeekMask: Long? = null,
    internal val approximateStartMinutesSinceMidnight: Int? = null,
    internal val startMinutesSinceMidnight: Int? = null,
    internal val startWindowEndMinutesSinceMidnight: Int? = null,
    internal val priority: Long? = null,
    internal val startExtraMinutes: Long? = null,
    internal val stopExtraMinutes: Long? = null,
    internal val duplicateDetection: Long? = null,
    internal val maximumRecordingCount: Long? = null,
    internal val broadcastType: Long? = null,
    internal val comment: String? = null,
    internal val title: String? = null,
    internal val fullText: Boolean? = null,
    internal val mergeText: Boolean? = null,
    internal val name: String? = null,
    internal val directory: String? = null,
    internal val owner: String? = null,
    internal val creator: String? = null,
    internal val channelId: ChannelId? = null,
    internal val seriesLinkUri: String? = null,
    internal val configId: DvrConfigId? = null,
) {
    override fun toString(): String = "GatewayAutorecRule(<redacted>)"
}

internal class GatewayTimerecRule(
    internal val id: TimerecRuleId,
    internal val enabled: Boolean? = null,
    internal val name: String? = null,
    internal val title: String? = null,
    internal val channelId: ChannelId? = null,
    internal val startMinutesSinceMidnight: Int? = null,
    internal val stopMinutesSinceMidnight: Int? = null,
    internal val daysOfWeekMask: Long? = null,
    internal val priority: Long? = null,
    internal val retentionDays: Long? = null,
    internal val directory: String? = null,
    internal val owner: String? = null,
    internal val creator: String? = null,
    internal val configId: DvrConfigId? = null,
    internal val comment: String? = null,
) {
    override fun toString(): String = "GatewayTimerecRule(<redacted>)"
}
