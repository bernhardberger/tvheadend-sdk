package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EpgCoverage
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EpgRepository
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EventId
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Mutable EPG repository for application and SDK consumer tests. */
public class FakeEpgRepository(
    initialState: EpgRepositoryState = EpgRepositoryState.Empty,
) : EpgRepository {
    private val mutableState = MutableStateFlow(initialState)

    override val state: StateFlow<EpgRepositoryState> = mutableState.asStateFlow()
    override val events: StateFlow<List<EpgEvent>> =
        MappedFakeEpgStateFlow(state, EpgRepositoryState::events)

    /** Publishes one complete repository transition. */
    public fun setState(state: EpgRepositoryState) {
        mutableState.value = state
    }

    override fun event(id: EventId): Flow<EpgEvent?> =
        events.map { events -> events.firstOrNull { event -> event.id == id } }
            .distinctUntilChanged()

    override fun events(channelId: ChannelId): Flow<List<EpgEvent>> =
        events.map { events -> events.filter { event -> event.channelId == channelId } }
            .distinctUntilChanged()

    override fun coverage(channelId: ChannelId): Flow<EpgCoverage?> =
        state.map { state ->
            state.snapshotOrNull()?.coverages?.firstOrNull { coverage ->
                coverage.channelId == channelId
            }
        }.distinctUntilChanged()
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class, InternalCoroutinesApi::class)
private class MappedFakeEpgStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {
    override val value: R
        get() = transform(source.value)

    override val replayCache: List<R>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        var previous: Any? = UnsetFakeEpgState
        source.collect { value ->
            val mapped = transform(value)
            if (previous === UnsetFakeEpgState || previous != mapped) {
                previous = mapped
                collector.emit(mapped)
            }
        }
    }

    private data object UnsetFakeEpgState
}

private fun EpgRepositoryState.snapshotOrNull() = when (this) {
    EpgRepositoryState.Empty -> null
    is EpgRepositoryState.Synchronizing -> staleSnapshot
    is EpgRepositoryState.Current -> snapshot
    is EpgRepositoryState.Stale -> snapshot
}

private fun EpgRepositoryState.events(): List<EpgEvent> = snapshotOrNull()?.events.orEmpty()
