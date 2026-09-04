package at.bernhardberger.tvheadend.sdk.core

import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
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

/**
 * Bounds explicit EPG acquisition and retained future programme metadata.
 *
 * The default remains 24 hours with at most 100,000 retained events. Configured horizons use
 * whole-second precision and range from 24 hours through seven days, inclusive. Retained-event
 * limits range from 1 through 250,000, inclusive. When the event limit is full, asynchronous
 * additions are ignored and a query requiring more capacity is rejected atomically without
 * advancing its coverage boundary; time-window retention or server deletion frees capacity.
 */
public class EpgCoveragePolicy private constructor(
    public val futureHorizon: Duration,
    public val maximumRetainedEvents: Int,
) {
    init {
        require(futureHorizon.isFinite()) {
            "EpgCoveragePolicy futureHorizon must be finite"
        }
        require(futureHorizon == futureHorizon.inWholeSeconds.seconds) {
            "EpgCoveragePolicy futureHorizon must use whole seconds"
        }
        require(futureHorizon in EPG_COVERAGE_MINIMUM_HORIZON..EPG_COVERAGE_MAXIMUM_HORIZON) {
            "EpgCoveragePolicy futureHorizon must be between 24 hours and 7 days"
        }
        require(maximumRetainedEvents in 1..EPG_COVERAGE_MAXIMUM_RETAINED_EVENTS) {
            "EpgCoveragePolicy maximumRetainedEvents must be between 1 and 250000"
        }
    }

    override fun toString(): String =
        "EpgCoveragePolicy(futureHorizon=$futureHorizon, " +
            "maximumRetainedEvents=$maximumRetainedEvents)"

    public companion object {
        /** Creates a bounded policy from a typed Kotlin duration. */
        public fun create(
            futureHorizon: Duration = EPG_COVERAGE_MINIMUM_HORIZON,
        ): EpgCoveragePolicy = EpgCoveragePolicy(
            futureHorizon,
            EPG_COVERAGE_DEFAULT_MAXIMUM_RETAINED_EVENTS,
        )

        /** Creates a bounded policy with an explicit retained-event limit. */
        public fun create(
            futureHorizon: Duration,
            maximumRetainedEvents: Int,
        ): EpgCoveragePolicy = EpgCoveragePolicy(futureHorizon, maximumRetainedEvents)

        /** Creates a bounded policy for Java callers using whole hours. */
        @JvmStatic
        public fun createFromHours(futureHorizonHours: Long): EpgCoveragePolicy =
            create(futureHorizonHours.hours)

        /** Creates a bounded policy with an explicit retained-event limit for Java callers. */
        @JvmStatic
        public fun createFromHours(
            futureHorizonHours: Long,
            maximumRetainedEvents: Int,
        ): EpgCoveragePolicy = create(futureHorizonHours.hours, maximumRetainedEvents)
    }
}

/** Freshness and synchronization state of programme-guide metadata. */
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

/** Returns this state's current or retained EPG snapshot for display without copying it. */
public val EpgRepositoryState.epgSnapshotForDisplay: EpgSnapshot?
    get() = when (this) {
        EpgRepositoryState.Empty -> null
        is EpgRepositoryState.Synchronizing -> staleSnapshot
        is EpgRepositoryState.Current -> snapshot
        is EpgRepositoryState.Stale -> snapshot
    }

/** Describes the provenance and synchronization state of [epgSnapshotForDisplay]. */
public val EpgRepositoryState.epgSnapshotAuthority: RetainedMetadataAuthority
    get() = when (this) {
        EpgRepositoryState.Empty -> RetainedMetadataAuthority.ABSENT
        is EpgRepositoryState.Synchronizing -> if (staleSnapshot == null) {
            RetainedMetadataAuthority.SYNCHRONIZING_WITHOUT_RETAINED_DATA
        } else {
            RetainedMetadataAuthority.SYNCHRONIZING_WITH_RETAINED_DATA
        }
        is EpgRepositoryState.Current -> RetainedMetadataAuthority.CURRENT
        is EpgRepositoryState.Stale -> RetainedMetadataAuthority.STALE
    }

/** Settled outcome of acquiring EPG coverage for one exact session observation. */
public sealed interface EpgCoverageAcquisitionResult {
    /** Coverage settled with retained programme data in this exact immutable observation. */
    public class CoveredWithData(
        public val observation: SessionObservation,
    ) : EpgCoverageAcquisitionResult {
        override fun toString(): String = "EpgCoverageAcquisitionResult.CoveredWithData(<redacted>)"
    }

    /** A successful query proved coverage without retaining programme data. */
    public class CoveredEmpty(
        public val observation: SessionObservation,
    ) : EpgCoverageAcquisitionResult {
        override fun toString(): String = "EpgCoverageAcquisitionResult.CoveredEmpty(<redacted>)"
    }

    /** The channel, configured future window, or server query capability is not eligible. */
    public data object Ineligible : EpgCoverageAcquisitionResult

    /** The originating observation is no longer current for its owning session. */
    public data object ObservationExpired : EpgCoverageAcquisitionResult
}

/**
 * Immutable filters for one explicit server-backed EPG text search.
 *
 * [contentType] is an unsigned eight-bit genre code. TVHeadend servers using HTSP before version
 * 6 reinterpret values `0..15` as legacy major categories, so only those inputs are distinct on
 * such servers. Durations use finite whole seconds within the server's signed 32-bit storage.
 */
@ConsistentCopyVisibility
public data class EpgSearchRequest private constructor(
    public val query: String,
    public val fullText: Boolean,
    public val channelId: ChannelId?,
    public val tagId: ChannelTagId?,
    public val contentType: Long?,
    public val language: String?,
    public val minimumDuration: Duration?,
    public val maximumDuration: Duration?,
) {
    init {
        contentType?.let {
            require(it in 0L..EPG_CONTENT_TYPE_MAX) {
                "EpgSearchRequest contentType must be an unsigned 8-bit value"
            }
        }
        minimumDuration?.requireEpgSearchDuration("minimumDuration")
        maximumDuration?.requireEpgSearchDuration("maximumDuration")
        require(minimumDuration == null || maximumDuration == null || minimumDuration <= maximumDuration) {
            "EpgSearchRequest minimumDuration must not exceed maximumDuration"
        }
    }

    override fun toString(): String = "EpgSearchRequest(<redacted>)"

    public companion object {
        /** Creates one validated search request from typed Kotlin filter values. */
        public fun create(
            query: String,
            fullText: Boolean = false,
            channelId: ChannelId? = null,
            tagId: ChannelTagId? = null,
            contentType: Long? = null,
            language: String? = null,
            minimumDuration: Duration? = null,
            maximumDuration: Duration? = null,
        ): EpgSearchRequest = EpgSearchRequest(
            query = query,
            fullText = fullText,
            channelId = channelId,
            tagId = tagId,
            contentType = contentType,
            language = language,
            minimumDuration = minimumDuration,
            maximumDuration = maximumDuration,
        )

        /** Creates one validated request for Java callers using numeric IDs and whole seconds. */
        @JvmStatic
        public fun createFromSeconds(
            query: String,
            fullText: Boolean,
            channelId: Long?,
            tagId: Long?,
            contentType: Long?,
            language: String?,
            minimumDurationSeconds: Long?,
            maximumDurationSeconds: Long?,
        ): EpgSearchRequest = create(
            query = query,
            fullText = fullText,
            channelId = channelId?.let(::ChannelId),
            tagId = tagId?.let(::ChannelTagId),
            contentType = contentType,
            language = language,
            minimumDuration = minimumDurationSeconds?.seconds,
            maximumDuration = maximumDurationSeconds?.seconds,
        )
    }
}

/** Settled outcome of one explicit EPG search for an exact session observation. */
public sealed interface EpgSearchResult {
    /** The server returned this immutable finite event list, which may be empty. */
    public class Available private constructor(
        public val events: List<EpgEvent>,
        /**
         * Exact proof that authorized this operation.
         *
         * Provenance does not guarantee that the proof is still current when the result is read.
         */
        public val originatingSession: CurrentSessionObservation,
    ) : EpgSearchResult {
        override fun toString(): String = "EpgSearchResult.Available(<redacted>)"

        public companion object {
            /** Creates a successful result while defensively copying the event list. */
            public fun create(
                events: List<EpgEvent>,
                originatingSession: CurrentSessionObservation,
            ): Available = Available(
                events = events.toEpgImmutableList(),
                originatingSession = originatingSession,
            )
        }
    }

    /** The originating observation was already retired before dispatch. */
    public data object ObservationExpired : EpgSearchResult

    /**
     * The server rejected the query or returned an unusable search payload.
     *
     * TVHeadend also uses its generic rejection path for inaccessible channel or tag filters.
     */
    public data object InvalidQuery : EpgSearchResult

    /** The protocol explicitly reported that the session lacks EPG search permission. */
    public data object AccessDenied : EpgSearchResult

    /** The server refused another concurrent operation. */
    public data object ConnectionLimit : EpgSearchResult

    /** The search did not settle before its protocol deadline. */
    public data object Timeout : EpgSearchResult

    /** The bound transport generation became unavailable. */
    public data object TransportUnavailable : EpgSearchResult

    /** A different session generation became current while the search was in flight. */
    public data object ConnectionChanged : EpgSearchResult

    /** The connected server does not support the requested search filters. */
    public data object NotSupported : EpgSearchResult
}

/** Programme-guide commands for the selected server profile. */
public interface EpgRepository {
    /**
     * Searches the server's EPG for [request] without reading or mutating retained coverage.
     *
     * The request is dispatched only while [currentSession] belongs to this repository's current
     * generation. A generation replacement during the round trip is reported distinctly, and
     * cancellation remains owned by the caller.
     */
    public suspend fun search(
        currentSession: CurrentSessionObservation,
        request: EpgSearchRequest,
    ): EpgSearchResult {
        currentCoroutineContext().ensureActive()
        return EpgSearchResult.NotSupported
    }

    /**
     * Acquires settled coverage for one channel through [through].
     *
     * The boundary is floored to the protocol's whole-second precision. An uncovered boundary at
     * or before the current second, or beyond the session's configured [EpgCoveragePolicy], is
     * ineligible. Priority never bypasses channel cooldown, repeated requests deduplicate, and
     * ordinary catalog work keeps a fair share of each batch. Cancellation remains owned by the
     * caller.
     */
    public suspend fun acquireCoverage(
        currentSession: CurrentSessionObservation,
        channelId: ChannelId,
        through: Instant,
    ): EpgCoverageAcquisitionResult
}

private fun requireEpgU32(name: String, value: Long) {
    require(value in 0L..EPG_U32_MAX) { "$name must be an unsigned 32-bit value" }
}

private fun Duration.requireEpgSearchDuration(name: String) {
    require(isFinite() && this >= Duration.ZERO) { "EpgSearchRequest $name must be finite and non-negative" }
    require(this == inWholeSeconds.seconds) { "EpgSearchRequest $name must use whole seconds" }
    require(inWholeSeconds <= EPG_SEARCH_DURATION_MAX_SECONDS) {
        "EpgSearchRequest $name must not exceed $EPG_SEARCH_DURATION_MAX_SECONDS seconds"
    }
}

private fun <T> Collection<T>.toEpgImmutableList(): List<T> =
    Collections.unmodifiableList(ArrayList(this))

private const val EPG_CONTENT_TYPE_MAX: Long = 0xff
private const val EPG_SEARCH_DURATION_MAX_SECONDS: Long = 2_147_483_647
private val EPG_COVERAGE_MINIMUM_HORIZON: Duration = 24.hours
private val EPG_COVERAGE_MAXIMUM_HORIZON: Duration = 7.days
private const val EPG_COVERAGE_DEFAULT_MAXIMUM_RETAINED_EVENTS = 100_000
private const val EPG_COVERAGE_MAXIMUM_RETAINED_EVENTS = 250_000
