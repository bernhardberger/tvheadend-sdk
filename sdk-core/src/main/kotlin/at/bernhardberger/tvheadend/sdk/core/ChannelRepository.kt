package at.bernhardberger.tvheadend.sdk.core

import java.util.Collections
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
        ): Channel = Channel(
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

/** Immutable channel-tag metadata. */
@ConsistentCopyVisibility
public data class ChannelTag private constructor(
    public val id: ChannelTagId,
    public val name: String?,
    public val uuid: String?,
    public val index: Long?,
    public val icon: String?,
    public val titledIcon: Long?,
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
            titledIcon: Long? = null,
            channelIds: List<ChannelId>? = null,
        ): ChannelTag = ChannelTag(
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

/** Freshness and synchronization state of a [ChannelRepository]. */
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

/** Observable channel and tag metadata for the selected server profile. */
public interface ChannelRepository {
    /** Authoritative catalog freshness and content. */
    public val state: StateFlow<ChannelRepositoryState>

    /** Channels from the current or retained stale catalog. */
    public val channels: StateFlow<List<Channel>>

    /** Channel tags from the current or retained stale catalog. */
    public val tags: StateFlow<List<ChannelTag>>

    /** Observes one channel from the current or retained stale catalog. */
    public fun channel(id: ChannelId): Flow<Channel?>
}

internal abstract class StateBackedChannelRepository : ChannelRepository {
    final override val channels: StateFlow<List<Channel>> by lazy {
        MappedStateFlow(state, ChannelRepositoryState::channels)
    }
    final override val tags: StateFlow<List<ChannelTag>> by lazy {
        MappedStateFlow(state, ChannelRepositoryState::tags)
    }

    final override fun channel(id: ChannelId): Flow<Channel?> =
        channels.map { channels -> channels.firstOrNull { channel -> channel.id == id } }
            .distinctUntilChanged()
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class, InternalCoroutinesApi::class)
private class MappedStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {
    override val value: R
        get() = transform(source.value)

    override val replayCache: List<R>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        var previous: Any? = UnsetState
        source.collect { value ->
            val mapped = transform(value)
            if (previous === UnsetState || previous != mapped) {
                previous = mapped
                collector.emit(mapped)
            }
        }
    }

    private data object UnsetState
}

private fun ChannelRepositoryState.catalogOrNull(): ChannelCatalog? = when (this) {
    ChannelRepositoryState.Empty -> null
    is ChannelRepositoryState.Synchronizing -> staleCatalog
    is ChannelRepositoryState.Current -> catalog
    is ChannelRepositoryState.Stale -> catalog
}

private fun ChannelRepositoryState.channels(): List<Channel> = catalogOrNull()?.channels.orEmpty()

private fun ChannelRepositoryState.tags(): List<ChannelTag> = catalogOrNull()?.tags.orEmpty()

private fun <T> Collection<T>.toImmutableList(): List<T> =
    Collections.unmodifiableList(ArrayList(this))
