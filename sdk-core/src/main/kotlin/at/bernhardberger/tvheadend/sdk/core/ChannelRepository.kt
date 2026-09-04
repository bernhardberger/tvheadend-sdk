package at.bernhardberger.tvheadend.sdk.core

import java.util.Collections

private const val U32_MAX: Long = 0xffff_ffffL

/** Stable TVHeadend channel identifier. */
@JvmInline
public value class ChannelId(public val value: Long) {
    init {
        require(value in 0L..U32_MAX) { "ChannelId must be an unsigned 32-bit value" }
    }

    override fun toString(): String = "ChannelId(<redacted>)"
}

/** Stable TVHeadend channel-tag identifier. */
@JvmInline
public value class ChannelTagId(public val value: Long) {
    init {
        require(value in 0L..U32_MAX) { "ChannelTagId must be an unsigned 32-bit value" }
    }

    override fun toString(): String = "ChannelTagId(<redacted>)"
}

/** Stable TVHeadend programme-event identifier. */
@JvmInline
public value class EventId(public val value: Long) {
    init {
        require(value in 0L..U32_MAX) { "EventId must be an unsigned 32-bit value" }
    }

    override fun toString(): String = "EventId(<redacted>)"
}

/** Service metadata retained for a channel. */
public data class ChannelService(
    public val name: String,
    public val type: String,
    public val content: Long,
    public val conditionalAccessId: Long?,
    public val conditionalAccessName: String?,
    public val providerName: String?,
) {
    init {
        requireUnsignedU32("ChannelService content", content)
        conditionalAccessId?.let { requireUnsignedU32("ChannelService conditionalAccessId", it) }
    }

    override fun toString(): String = "ChannelService(<redacted>)"
}

/** Immutable channel metadata. */
@ConsistentCopyVisibility
public data class Channel private constructor(
    public val id: ChannelId,
    public val name: String?,
    public val uuid: String?,
    public val number: Long?,
    public val numberMinor: Long?,
    public val icon: String?,
    public val currentEventId: EventId?,
    public val nextEventId: EventId?,
    public val services: List<ChannelService>?,
    public val tagIds: List<ChannelTagId>?,
) {
    override fun toString(): String = "Channel(<redacted>)"

    public companion object {
        /** Creates a channel while defensively copying its collections. */
        public fun create(
            id: ChannelId,
            name: String? = null,
            uuid: String? = null,
            number: Long? = null,
            numberMinor: Long? = null,
            icon: String? = null,
            currentEventId: EventId? = null,
            nextEventId: EventId? = null,
            services: List<ChannelService>? = null,
            tagIds: List<ChannelTagId>? = null,
        ): Channel {
            number?.let { requireUnsignedU32("Channel number", it) }
            numberMinor?.let { requireUnsignedU32("Channel numberMinor", it) }
            return Channel(
                id = id,
                name = name,
                uuid = uuid,
                number = number,
                numberMinor = numberMinor,
                icon = icon,
                currentEventId = currentEventId,
                nextEventId = nextEventId,
                services = services?.toImmutableList(),
                tagIds = tagIds?.toImmutableList(),
            )
        }
    }
}

/** Immutable channel-tag metadata. */
@ConsistentCopyVisibility
public data class ChannelTag private constructor(
    public val id: ChannelTagId,
    public val name: String?,
    public val uuid: String?,
    public val index: Long?,
    public val icon: String?,
    public val titledIcon: Boolean?,
    public val channelIds: List<ChannelId>?,
) {
    override fun toString(): String = "ChannelTag(<redacted>)"

    public companion object {
        /** Creates a channel tag while defensively copying its channel identifiers. */
        public fun create(
            id: ChannelTagId,
            name: String? = null,
            uuid: String? = null,
            index: Long? = null,
            icon: String? = null,
            titledIcon: Boolean? = null,
            channelIds: List<ChannelId>? = null,
        ): ChannelTag {
            index?.let { requireUnsignedU32("ChannelTag index", it) }
            return ChannelTag(
                id = id,
                name = name,
                uuid = uuid,
                index = index,
                icon = icon,
                titledIcon = titledIcon,
                channelIds = channelIds?.toImmutableList(),
            )
        }
    }
}

/** One immutable channel and tag catalog. */
@ConsistentCopyVisibility
public data class ChannelCatalog private constructor(
    public val channels: List<Channel>,
    public val tags: List<ChannelTag>,
) {
    override fun toString(): String = "ChannelCatalog(<redacted>)"

    public companion object {
        /** Creates a catalog while defensively copying both entity lists. */
        public fun create(
            channels: List<Channel> = emptyList(),
            tags: List<ChannelTag> = emptyList(),
        ): ChannelCatalog = ChannelCatalog(
            channels = channels.toImmutableList(),
            tags = tags.toImmutableList(),
        )
    }
}

/** Freshness and synchronization state of channel and channel-tag metadata. */
public sealed interface ChannelRepositoryState {
    /** No catalog has been synchronized. */
    public data object Empty : ChannelRepositoryState

    /** A new catalog is synchronizing, optionally while a prior catalog remains available. */
    public data class Synchronizing(
        public val staleCatalog: ChannelCatalog?,
    ) : ChannelRepositoryState {
        override fun toString(): String = "ChannelRepositoryState.Synchronizing(<redacted>)"
    }

    /** The catalog is current for the active connection generation. */
    public data class Current(
        public val catalog: ChannelCatalog,
    ) : ChannelRepositoryState {
        override fun toString(): String = "ChannelRepositoryState.Current(<redacted>)"
    }

    /** The retained catalog belongs to an inactive connection generation. */
    public data class Stale(
        public val catalog: ChannelCatalog,
    ) : ChannelRepositoryState {
        override fun toString(): String = "ChannelRepositoryState.Stale(<redacted>)"
    }
}

/** Returns this state's current or retained catalog for display without copying it. */
public val ChannelRepositoryState.channelCatalogForDisplay: ChannelCatalog?
    get() = when (this) {
        ChannelRepositoryState.Empty -> null
        is ChannelRepositoryState.Synchronizing -> staleCatalog
        is ChannelRepositoryState.Current -> catalog
        is ChannelRepositoryState.Stale -> catalog
    }

/** Describes the provenance and synchronization state of [channelCatalogForDisplay]. */
public val ChannelRepositoryState.channelCatalogAuthority: RetainedMetadataAuthority
    get() = when (this) {
        ChannelRepositoryState.Empty -> RetainedMetadataAuthority.ABSENT
        is ChannelRepositoryState.Synchronizing -> if (staleCatalog == null) {
            RetainedMetadataAuthority.SYNCHRONIZING_WITHOUT_RETAINED_DATA
        } else {
            RetainedMetadataAuthority.SYNCHRONIZING_WITH_RETAINED_DATA
        }
        is ChannelRepositoryState.Current -> RetainedMetadataAuthority.CURRENT
        is ChannelRepositoryState.Stale -> RetainedMetadataAuthority.STALE
    }

private fun requireUnsignedU32(name: String, value: Long) {
    require(value in 0L..U32_MAX) { "$name must be an unsigned 32-bit value" }
}

private fun <T> Collection<T>.toImmutableList(): List<T> =
    Collections.unmodifiableList(ArrayList(this))
