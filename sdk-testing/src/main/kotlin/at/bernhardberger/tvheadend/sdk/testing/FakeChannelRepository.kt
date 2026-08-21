package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.Channel
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.ChannelRepository
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.ChannelTag
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Mutable channel repository for application and SDK consumer tests. */
public class FakeChannelRepository(
    initialState: ChannelRepositoryState = ChannelRepositoryState.Empty,
) : ChannelRepository {
    private val mutableState = MutableStateFlow(initialState)

    override val state: StateFlow<ChannelRepositoryState> = mutableState.asStateFlow()
    override val channels: StateFlow<List<Channel>> =
        MappedStateFlow(state, ChannelRepositoryState::channels)
    override val tags: StateFlow<List<ChannelTag>> =
        MappedStateFlow(state, ChannelRepositoryState::tags)

    /** Publishes one complete repository transition. */
    public fun setState(state: ChannelRepositoryState) {
        mutableState.value = state
    }

    override fun channel(id: ChannelId): Flow<Channel?> =
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

private fun ChannelRepositoryState.catalogOrNull() = when (this) {
    ChannelRepositoryState.Empty -> null
    is ChannelRepositoryState.Synchronizing -> staleCatalog
    is ChannelRepositoryState.Current -> catalog
    is ChannelRepositoryState.Stale -> catalog
}

private fun ChannelRepositoryState.channels(): List<Channel> = catalogOrNull()?.channels.orEmpty()

private fun ChannelRepositoryState.tags(): List<ChannelTag> = catalogOrNull()?.tags.orEmpty()
