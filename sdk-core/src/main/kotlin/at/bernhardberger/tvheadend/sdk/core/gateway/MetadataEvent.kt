package at.bernhardberger.tvheadend.sdk.core.gateway

internal typealias ChannelId = at.bernhardberger.tvheadend.sdk.core.ChannelId
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
    EPG_ADDED,
    EPG_UPDATED,
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
    internal val titledIcon: Long?,
    channelIds: List<ChannelId>?,
) {
    internal val channelIds: List<ChannelId>? = channelIds?.toList()

    override fun toString(): String = "GatewayTagMetadata(<redacted>)"
}
