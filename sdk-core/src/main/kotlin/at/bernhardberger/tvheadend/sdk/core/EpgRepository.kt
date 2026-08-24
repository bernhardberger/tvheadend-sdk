package at.bernhardberger.tvheadend.sdk.core

import java.util.Collections
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

private const val EPG_U32_MAX: Long = 0xffff_ffffL

/** Stable TVHeadend EPG episode identifier. */
@JvmInline
public value class EpgEpisodeId(public val value: Long) {
    init {
        requireEpgU32("EpgEpisodeId", value)
    }

    override fun toString(): String = "EpgEpisodeId(<redacted>)"
}

/** Stable TVHeadend EPG series-link identifier. */
@JvmInline
public value class EpgSeriesLinkId(public val value: Long) {
    init {
        requireEpgU32("EpgSeriesLinkId", value)
    }

    override fun toString(): String = "EpgSeriesLinkId(<redacted>)"
}

/** Stable TVHeadend DVR entry identifier referenced by EPG metadata. */
@JvmInline
public value class DvrEntryId(public val value: Long) {
    init {
        requireEpgU32("DvrEntryId", value)
    }

    override fun toString(): String = "DvrEntryId(<redacted>)"
}

/** Content-rating observations attached to one EPG event. */
public data class EpgRating(
    public val age: Long?,
    public val label: String?,
    public val icon: String?,
    public val authority: String?,
    public val country: String?,
    public val stars: Long?,
) {
    init {
        age?.let { requireEpgU32("EpgRating age", it) }
        stars?.let { requireEpgU32("EpgRating stars", it) }
    }

    override fun toString(): String = "EpgRating(<redacted>)"
}

/** Episode, season, and part numbering attached to one EPG event. */
public data class EpgEpisode(
    public val id: EpgEpisodeId?,
    public val seriesLinkId: EpgSeriesLinkId?,
    public val seasonNumber: Long?,
    public val seasonCount: Long?,
    public val episodeNumber: Long?,
    public val episodeCount: Long?,
    public val partNumber: Long?,
    public val partCount: Long?,
    public val onscreen: String?,
) {
    init {
        listOfNotNull(
            seasonNumber,
            seasonCount,
            episodeNumber,
            episodeCount,
            partNumber,
            partCount,
        ).forEach { requireEpgU32("EpgEpisode number", it) }
    }

    override fun toString(): String = "EpgEpisode(<redacted>)"
}

/** Immutable programme metadata with wall-clock timing. */
@ConsistentCopyVisibility
public data class EpgEvent private constructor(
    public val id: EventId,
    public val channelId: ChannelId?,
    public val start: Instant,
    public val stop: Instant,
    public val title: String?,
    public val subtitle: String?,
    public val summary: String?,
    public val description: String?,
    public val genre: String?,
    public val categories: List<String>?,
    public val keywords: List<String>?,
    public val seriesLinkUri: String?,
    public val episodeUri: String?,
    public val contentType: Long?,
    public val rating: EpgRating?,
    public val copyrightYear: Long?,
    public val firstAired: Instant?,
    public val isNew: Boolean?,
    public val episode: EpgEpisode?,
    public val image: String?,
    public val dvrEntryId: DvrEntryId?,
    public val nextEventId: EventId?,
) {
    override fun toString(): String = "EpgEvent(<redacted>)"

    public companion object {
        /** Creates an event while validating timing and defensively copying collections. */
        public fun create(
            id: EventId,
            channelId: ChannelId? = null,
            start: Instant,
            stop: Instant,
            title: String? = null,
            subtitle: String? = null,
            summary: String? = null,
            description: String? = null,
            genre: String? = null,
            categories: List<String>? = null,
            keywords: List<String>? = null,
            seriesLinkUri: String? = null,
            episodeUri: String? = null,
            contentType: Long? = null,
            rating: EpgRating? = null,
            copyrightYear: Long? = null,
            firstAired: Instant? = null,
            isNew: Boolean? = null,
            episode: EpgEpisode? = null,
            image: String? = null,
            dvrEntryId: DvrEntryId? = null,
            nextEventId: EventId? = null,
        ): EpgEvent {
            require(stop >= start) { "EpgEvent stop must not precede start" }
            contentType?.let { requireEpgU32("EpgEvent contentType", it) }
            copyrightYear?.let { requireEpgU32("EpgEvent copyrightYear", it) }
            return EpgEvent(
                id = id,
                channelId = channelId,
                start = start,
                stop = stop,
                title = title,
                subtitle = subtitle,
                summary = summary,
                description = description,
                genre = genre,
                categories = categories?.toEpgImmutableList(),
                keywords = keywords?.toEpgImmutableList(),
                seriesLinkUri = seriesLinkUri,
                episodeUri = episodeUri,
                contentType = contentType,
                rating = rating,
                copyrightYear = copyrightYear,
                firstAired = firstAired,
                isNew = isNew,
                episode = episode,
                image = image,
                dvrEntryId = dvrEntryId,
                nextEventId = nextEventId,
            )
        }
    }
}

/** Actual retained and successfully queried EPG horizon for one channel. */
@ConsistentCopyVisibility
public data class EpgCoverage private constructor(
    public val channelId: ChannelId,
    public val coveredFrom: Instant,
    public val coveredTo: Instant,
    public val queriedTo: Instant?,
) {
    /** Whether actual retained coverage is represented by an inverted interval. */
    public val isEmpty: Boolean
        get() = coveredFrom > coveredTo

    /** Furthest point proven by retained events or a successful query. */
    public val knownTo: Instant?
        get() = when {
            isEmpty -> queriedTo
            queriedTo == null -> coveredTo
            else -> maxOf(coveredTo, queriedTo)
        }

    override fun toString(): String = "EpgCoverage(<redacted>)"

    public companion object {
        /** Creates non-empty actual coverage for one channel. */
        public fun create(
            channelId: ChannelId,
            coveredFrom: Instant,
            coveredTo: Instant,
            queriedTo: Instant? = null,
        ): EpgCoverage {
            require(coveredTo >= coveredFrom) { "EpgCoverage stop must not precede start" }
            return EpgCoverage(channelId, coveredFrom, coveredTo, queriedTo)
        }

        /** Creates canonical empty actual coverage as an inverted interval. */
        public fun empty(
            channelId: ChannelId,
            queriedTo: Instant? = null,
        ): EpgCoverage = EpgCoverage(
            channelId = channelId,
            coveredFrom = Instant.DISTANT_FUTURE,
            coveredTo = Instant.DISTANT_PAST,
            queriedTo = queriedTo,
        )
    }
}

/** One immutable EPG event and per-channel coverage snapshot. */
@ConsistentCopyVisibility
public data class EpgSnapshot private constructor(
    public val events: List<EpgEvent>,
    public val coverages: List<EpgCoverage>,
) {
    override fun toString(): String = "EpgSnapshot(<redacted>)"

    public companion object {
        /** Creates a snapshot while defensively copying both lists. */
        public fun create(
            events: List<EpgEvent> = emptyList(),
            coverages: List<EpgCoverage> = emptyList(),
        ): EpgSnapshot = EpgSnapshot(
            events = events.toEpgImmutableList(),
            coverages = coverages.toEpgImmutableList(),
        )
    }
}

/** Freshness and synchronization state of an [EpgRepository]. */
public sealed interface EpgRepositoryState {
    /** No EPG snapshot has been synchronized. */
    public data object Empty : EpgRepositoryState

    /** A new EPG snapshot is synchronizing, optionally retaining prior data. */
    public data class Synchronizing(
        public val staleSnapshot: EpgSnapshot?,
    ) : EpgRepositoryState {
        override fun toString(): String = "EpgRepositoryState.Synchronizing(<redacted>)"
    }

    /** The EPG snapshot is current for the active connection generation. */
    public data class Current(
        public val snapshot: EpgSnapshot,
    ) : EpgRepositoryState {
        override fun toString(): String = "EpgRepositoryState.Current(<redacted>)"
    }

    /** The retained EPG snapshot belongs to an inactive connection generation. */
    public data class Stale(
        public val snapshot: EpgSnapshot,
    ) : EpgRepositoryState {
        override fun toString(): String = "EpgRepositoryState.Stale(<redacted>)"
    }
}

/** Immediate outcome of requesting prioritized EPG coverage. */
public enum class EpgCoverageRequestResult {
    /** The current snapshot already covers the requested whole-second boundary. */
    SATISFIED,

    /** One deduplicated priority hint was accepted or promoted for background work. */
    ACCEPTED,

    /** The channel, bounded future window, or server query capability is not eligible. */
    INELIGIBLE,

    /** No current generation-owned EPG worker can accept the request. A later Ready may be retried. */
    GENERATION_LOST,
}

/** Observable EPG metadata and coverage for the selected server profile. */
public interface EpgRepository {
    /** Authoritative EPG freshness and content. */
    public val state: StateFlow<EpgRepositoryState>

    /** Events from the current or retained stale snapshot. */
    public val events: StateFlow<List<EpgEvent>>

    /** Observes one event from the current or retained stale snapshot. */
    public fun event(id: EventId): Flow<EpgEvent?>

    /** Observes events for one channel in retained server order. */
    public fun events(channelId: ChannelId): Flow<List<EpgEvent>>

    /** Observes actual and queried coverage for one channel. */
    public fun coverage(channelId: ChannelId): Flow<EpgCoverage?>

    /**
     * Prioritizes one channel through [through] without waiting for a query to complete.
     *
     * The boundary is floored to the protocol's whole-second precision. An uncovered boundary at
     * or before the current second, or more than 24 hours ahead, is ineligible. Priority never
     * bypasses channel cooldown, repeated requests deduplicate, and ordinary catalog work keeps a
     * fair share of each batch.
     */
    public fun requestCoverage(
        channelId: ChannelId,
        through: Instant,
    ): EpgCoverageRequestResult
}

internal abstract class StateBackedEpgRepository : EpgRepository {
    final override val events: StateFlow<List<EpgEvent>> by lazy {
        MappedEpgStateFlow(state, EpgRepositoryState::events)
    }

    final override fun event(id: EventId): Flow<EpgEvent?> =
        events.map { events -> events.firstOrNull { event -> event.id == id } }
            .distinctUntilChanged()

    final override fun events(channelId: ChannelId): Flow<List<EpgEvent>> =
        events.map { events -> events.filter { event -> event.channelId == channelId } }
            .distinctUntilChanged()

    final override fun coverage(channelId: ChannelId): Flow<EpgCoverage?> =
        state.map { state ->
            state.snapshotOrNull()?.coverages?.firstOrNull { coverage ->
                coverage.channelId == channelId
            }
        }.distinctUntilChanged()
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class, InternalCoroutinesApi::class)
private class MappedEpgStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {
    override val value: R
        get() = transform(source.value)

    override val replayCache: List<R>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        var previous: Any? = UnsetEpgState
        source.collect { value ->
            val mapped = transform(value)
            if (previous === UnsetEpgState || previous != mapped) {
                previous = mapped
                collector.emit(mapped)
            }
        }
    }

    private data object UnsetEpgState
}

private fun EpgRepositoryState.snapshotOrNull(): EpgSnapshot? = when (this) {
    EpgRepositoryState.Empty -> null
    is EpgRepositoryState.Synchronizing -> staleSnapshot
    is EpgRepositoryState.Current -> snapshot
    is EpgRepositoryState.Stale -> snapshot
}

private fun EpgRepositoryState.events(): List<EpgEvent> = snapshotOrNull()?.events.orEmpty()

private fun requireEpgU32(name: String, value: Long) {
    require(value in 0L..EPG_U32_MAX) { "$name must be an unsigned 32-bit value" }
}

private fun <T> Collection<T>.toEpgImmutableList(): List<T> =
    Collections.unmodifiableList(ArrayList(this))
