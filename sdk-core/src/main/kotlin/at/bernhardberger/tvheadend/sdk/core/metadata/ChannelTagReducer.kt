package at.bernhardberger.tvheadend.sdk.core.metadata

import at.bernhardberger.tvheadend.sdk.core.Channel
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelService
import at.bernhardberger.tvheadend.sdk.core.ChannelTag
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelService
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayTagMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.TagId
import java.util.Collections

@ConsistentCopyVisibility
internal data class ReducedChannel private constructor(
    internal val id: ChannelId,
    internal val name: String?,
    internal val uuid: String?,
    internal val number: Long?,
    internal val numberMinor: Long?,
    internal val icon: String?,
    internal val currentEventId: EventId?,
    internal val nextEventId: EventId?,
    internal val services: List<ReducedChannelService>?,
    internal val tagIds: List<TagId>?,
) {
    override fun toString(): String = "ReducedChannel(<redacted>)"

    internal fun merge(
        metadata: GatewayChannelMetadata,
        resetEventLinks: Boolean,
    ): ReducedChannel = ReducedChannel(
        id = id,
        name = metadata.name ?: name,
        uuid = metadata.uuid ?: uuid,
        number = metadata.number ?: number,
        numberMinor = metadata.numberMinor ?: numberMinor,
        icon = metadata.icon ?: icon,
        currentEventId = if (resetEventLinks) {
            metadata.currentEventId
        } else {
            metadata.currentEventId ?: currentEventId
        },
        nextEventId = if (resetEventLinks) {
            metadata.nextEventId
        } else {
            metadata.nextEventId ?: nextEventId
        },
        services = metadata.services?.map(GatewayChannelService::toReduced)?.toImmutableList()
            ?: services,
        tagIds = metadata.tagIds?.toImmutableList() ?: tagIds,
    )

    internal fun removeTag(tagId: TagId): ReducedChannel {
        val current = tagIds ?: return this
        val filtered = current.filterNot { it == tagId }
        return if (filtered == current) this else withTagIds(filtered)
    }

    internal fun retainTags(validTagIds: Set<TagId>): ReducedChannel {
        val current = tagIds ?: return this
        val filtered = current.filter(validTagIds::contains)
        return if (filtered == current) this else withTagIds(filtered)
    }

    internal fun clearEvent(eventId: EventId): ReducedChannel {
        if (currentEventId != eventId && nextEventId != eventId) {
            return this
        }
        return ReducedChannel(
            id = id,
            name = name,
            uuid = uuid,
            number = number,
            numberMinor = numberMinor,
            icon = icon,
            currentEventId = currentEventId.takeUnless { it == eventId },
            nextEventId = nextEventId.takeUnless { it == eventId },
            services = services,
            tagIds = tagIds,
        )
    }

    private fun withTagIds(tagIds: List<TagId>): ReducedChannel = ReducedChannel(
        id = id,
        name = name,
        uuid = uuid,
        number = number,
        numberMinor = numberMinor,
        icon = icon,
        currentEventId = currentEventId,
        nextEventId = nextEventId,
        services = services,
        tagIds = tagIds.toImmutableList(),
    )

    internal companion object {
        internal fun create(metadata: GatewayChannelMetadata): ReducedChannel = ReducedChannel(
            id = metadata.id,
            name = metadata.name,
            uuid = metadata.uuid,
            number = metadata.number,
            numberMinor = metadata.numberMinor,
            icon = metadata.icon,
            currentEventId = metadata.currentEventId,
            nextEventId = metadata.nextEventId,
            services = metadata.services?.map(GatewayChannelService::toReduced)?.toImmutableList(),
            tagIds = metadata.tagIds?.toImmutableList(),
        )
    }
}

internal data class ReducedChannelService(
    internal val name: String,
    internal val type: String,
    internal val content: Long,
    internal val conditionalAccessId: Long?,
    internal val conditionalAccessName: String?,
    internal val providerName: String?,
) {
    override fun toString(): String = "ReducedChannelService(<redacted>)"
}

@ConsistentCopyVisibility
internal data class ReducedChannelTag private constructor(
    internal val id: TagId,
    internal val name: String?,
    internal val uuid: String?,
    internal val index: Long?,
    internal val icon: String?,
    internal val titledIcon: Boolean?,
    internal val channelIds: List<ChannelId>?,
) {
    override fun toString(): String = "ReducedChannelTag(<redacted>)"

    internal fun merge(metadata: GatewayTagMetadata): ReducedChannelTag = ReducedChannelTag(
        id = id,
        name = metadata.name ?: name,
        uuid = metadata.uuid ?: uuid,
        index = metadata.index ?: index,
        icon = metadata.icon ?: icon,
        titledIcon = metadata.titledIcon ?: titledIcon,
        channelIds = metadata.channelIds?.toImmutableList() ?: channelIds,
    )

    internal fun removeChannel(channelId: ChannelId): ReducedChannelTag {
        val current = channelIds ?: return this
        val filtered = current.filterNot { it == channelId }
        return if (filtered == current) this else withChannelIds(filtered)
    }

    internal fun retainChannels(validChannelIds: Set<ChannelId>): ReducedChannelTag {
        val current = channelIds ?: return this
        val filtered = current.filter(validChannelIds::contains)
        return if (filtered == current) this else withChannelIds(filtered)
    }

    private fun withChannelIds(channelIds: List<ChannelId>): ReducedChannelTag = ReducedChannelTag(
        id = id,
        name = name,
        uuid = uuid,
        index = index,
        icon = icon,
        titledIcon = titledIcon,
        channelIds = channelIds.toImmutableList(),
    )

    internal companion object {
        internal fun create(metadata: GatewayTagMetadata): ReducedChannelTag = ReducedChannelTag(
            id = metadata.id,
            name = metadata.name,
            uuid = metadata.uuid,
            index = metadata.index,
            icon = metadata.icon,
            titledIcon = metadata.titledIcon,
            channelIds = metadata.channelIds?.toImmutableList(),
        )
    }
}

internal class ChannelTagReducer {
    private val channels = linkedMapOf<ChannelId, ReducedChannel>()
    private val tags = linkedMapOf<TagId, ReducedChannelTag>()

    internal fun clear() {
        channels.clear()
        tags.clear()
    }

    internal fun accept(event: MetadataEvent) {
        when (event) {
            is MetadataEvent.ChannelAdded -> mergeChannel(event.channel, resetEventLinks = true)
            is MetadataEvent.ChannelUpdated -> mergeChannel(event.channel, resetEventLinks = false)
            is MetadataEvent.ChannelDeleted -> deleteChannel(event.channelId)
            is MetadataEvent.TagAdded -> mergeTag(event.tag)
            is MetadataEvent.TagUpdated -> mergeTag(event.tag)
            is MetadataEvent.TagDeleted -> deleteTag(event.tagId)
            is MetadataEvent.EventDeleted -> clearEvent(event.eventId)
            is MetadataEvent.EventAdded,
            is MetadataEvent.EventUpdated,
            is MetadataEvent.InitialSyncCompleted,
            is MetadataEvent.DvrEntryAdded,
            is MetadataEvent.DvrEntryUpdated,
            is MetadataEvent.DvrEntryDeleted,
            is MetadataEvent.AutorecRuleAdded,
            is MetadataEvent.AutorecRuleUpdated,
            is MetadataEvent.AutorecRuleDeleted,
            is MetadataEvent.TimerecRuleAdded,
            is MetadataEvent.TimerecRuleUpdated,
            is MetadataEvent.TimerecRuleDeleted,
            -> Unit
        }
    }

    internal fun reconcileReferences() {
        val currentChannelIds = channels.keys.toSet()
        val currentTagIds = tags.keys.toSet()
        channels.entries.forEach { entry ->
            entry.setValue(entry.value.retainTags(currentTagIds))
        }
        tags.entries.forEach { entry ->
            entry.setValue(entry.value.retainChannels(currentChannelIds))
        }
    }

    internal fun snapshot(): ChannelCatalog = ChannelCatalog.create(
        channels = channels.values.map(ReducedChannel::toPublic),
        tags = tags.values.map(ReducedChannelTag::toPublic),
    )

    private fun mergeChannel(metadata: GatewayChannelMetadata, resetEventLinks: Boolean) {
        channels[metadata.id] = channels[metadata.id]?.merge(metadata, resetEventLinks)
            ?: ReducedChannel.create(metadata)
    }

    private fun mergeTag(metadata: GatewayTagMetadata) {
        tags[metadata.id] = tags[metadata.id]?.merge(metadata) ?: ReducedChannelTag.create(metadata)
    }

    private fun deleteChannel(channelId: ChannelId) {
        channels.remove(channelId)
        tags.entries.forEach { entry ->
            entry.setValue(entry.value.removeChannel(channelId))
        }
    }

    private fun deleteTag(tagId: TagId) {
        tags.remove(tagId)
        channels.entries.forEach { entry ->
            entry.setValue(entry.value.removeTag(tagId))
        }
    }

    private fun clearEvent(eventId: EventId) {
        channels.entries.forEach { entry ->
            entry.setValue(entry.value.clearEvent(eventId))
        }
    }
}

private fun GatewayChannelService.toReduced(): ReducedChannelService = ReducedChannelService(
    name = name,
    type = type,
    content = content,
    conditionalAccessId = conditionalAccessId,
    conditionalAccessName = conditionalAccessName,
    providerName = providerName,
)

private fun ReducedChannel.toPublic(): Channel = Channel.create(
    id = id,
    name = name,
    uuid = uuid,
    number = number,
    numberMinor = numberMinor,
    icon = icon,
    currentEventId = currentEventId,
    nextEventId = nextEventId,
    services = services?.map(ReducedChannelService::toPublic),
    tagIds = tagIds,
)

private fun ReducedChannelService.toPublic(): ChannelService = ChannelService(
    name = name,
    type = type,
    content = content,
    conditionalAccessId = conditionalAccessId,
    conditionalAccessName = conditionalAccessName,
    providerName = providerName,
)

private fun ReducedChannelTag.toPublic(): ChannelTag = ChannelTag.create(
    id = id,
    name = name,
    uuid = uuid,
    index = index,
    icon = icon,
    titledIcon = titledIcon,
    channelIds = channelIds,
)

private fun <T> Collection<T>.toImmutableList(): List<T> =
    Collections.unmodifiableList(ArrayList(this))
