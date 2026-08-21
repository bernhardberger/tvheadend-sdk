package at.bernhardberger.tvheadend.sdk.core.gateway

import kotlin.time.Instant

internal typealias ChannelId = at.bernhardberger.tvheadend.sdk.core.ChannelId
internal typealias DvrEntryId = at.bernhardberger.tvheadend.sdk.core.DvrEntryId
internal typealias EpgEpisodeId = at.bernhardberger.tvheadend.sdk.core.EpgEpisodeId
internal typealias EpgSeriesLinkId = at.bernhardberger.tvheadend.sdk.core.EpgSeriesLinkId
internal typealias EventId = at.bernhardberger.tvheadend.sdk.core.EventId
internal typealias TagId = at.bernhardberger.tvheadend.sdk.core.ChannelTagId

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

    public class Deferred(
        override val generation: GatewayGeneration,
        internal val kind: DeferredMetadataKind,
    ) : MetadataEvent {
        override fun toString(): String = "MetadataEvent.Deferred(<redacted>)"
    }
}

internal enum class DeferredMetadataKind {
    DVR_ADDED,
    DVR_UPDATED,
    DVR_DELETED,
    AUTOREC_ADDED,
    AUTOREC_UPDATED,
    AUTOREC_DELETED,
    TIMEREC_ADDED,
    TIMEREC_UPDATED,
    TIMEREC_DELETED,
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
