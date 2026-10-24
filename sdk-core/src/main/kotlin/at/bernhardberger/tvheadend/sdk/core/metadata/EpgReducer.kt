@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.metadata

import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ContentGenre
import at.bernhardberger.tvheadend.sdk.core.EpgCredit
import at.bernhardberger.tvheadend.sdk.core.EpgCoverage
import at.bernhardberger.tvheadend.sdk.core.EpgCoveragePolicy
import at.bernhardberger.tvheadend.sdk.core.EpgEpisode
import at.bernhardberger.tvheadend.sdk.core.EpgEpisodeId
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EpgRating
import at.bernhardberger.tvheadend.sdk.core.EpgSeriesLinkId
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import java.util.Collections
import java.util.PriorityQueue
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@ConsistentCopyVisibility
internal data class ReducedEpgEvent private constructor(
    internal val id: EventId,
    internal val channelId: ChannelId?,
    internal val start: Instant,
    internal val stop: Instant,
    internal val title: String?,
    internal val subtitle: String?,
    internal val summary: String?,
    internal val description: String?,
    internal val genre: String?,
    internal val categories: List<String>?,
    internal val keywords: List<String>?,
    internal val credits: List<EpgCredit>?,
    internal val seriesLinkUri: String?,
    internal val episodeUri: String?,
    internal val contentType: Long?,
    internal val ageRating: Long?,
    internal val ratingLabel: String?,
    internal val ratingIcon: String?,
    internal val ratingAuthority: String?,
    internal val ratingCountry: String?,
    internal val starRating: Long?,
    internal val copyrightYear: Long?,
    internal val firstAired: Instant?,
    internal val isNew: Boolean?,
    internal val seasonNumber: Long?,
    internal val seasonCount: Long?,
    internal val episodeNumber: Long?,
    internal val episodeCount: Long?,
    internal val partNumber: Long?,
    internal val partCount: Long?,
    internal val episodeOnscreen: String?,
    internal val episodeId: EpgEpisodeId?,
    internal val seriesLinkId: EpgSeriesLinkId?,
    internal val image: String?,
    internal val dvrEntryId: DvrEntryId?,
    internal val nextEventId: EventId?,
) {
    // Reducer-owned immutable events can share their public projection across snapshots.
    // A metadata update creates a new ReducedEpgEvent, leaving retained snapshots untouched.
    private var publicEvent: EpgEvent? = null

    override fun toString(): String = "ReducedEpgEvent(<redacted>)"

    internal fun toPublicOrNull(): EpgEvent? {
        publicEvent?.let { return it }
        if (stop < start) return null
        return EpgEvent.create(
            id = id,
            channelId = channelId,
            start = start,
            stop = stop,
            title = title,
            subtitle = subtitle,
            summary = summary,
            description = description,
            genre = genre,
            categories = categories,
            keywords = keywords,
            credits = credits,
            seriesLinkUri = seriesLinkUri,
            episodeUri = episodeUri,
            contentGenre = ContentGenre.fromContentType(contentType),
            rating = ratingOrNull(),
            copyrightYear = copyrightYear,
            firstAired = firstAired,
            isNew = isNew,
            episode = episodeOrNull(),
            image = ArtworkId.parse(image),
            dvrEntryId = dvrEntryId,
            nextEventId = nextEventId,
        ).also { publicEvent = it }
    }

    internal fun shouldRetain(from: Instant, to: Instant): Boolean = stop >= from && start <= to

    private fun ratingOrNull(): EpgRating? {
        if (
            ageRating == null &&
            ratingLabel == null &&
            ratingIcon == null &&
            ratingAuthority == null &&
            ratingCountry == null &&
            starRating == null
        ) {
            return null
        }
        return EpgRating(
            age = ageRating,
            label = ratingLabel,
            icon = ratingIcon,
            authority = ratingAuthority,
            country = ratingCountry,
            stars = starRating,
        )
    }

    private fun episodeOrNull(): EpgEpisode? {
        if (
            episodeId == null &&
            seriesLinkId == null &&
            seasonNumber == null &&
            seasonCount == null &&
            episodeNumber == null &&
            episodeCount == null &&
            partNumber == null &&
            partCount == null &&
            episodeOnscreen == null
        ) {
            return null
        }
        return EpgEpisode(
            id = episodeId,
            seriesLinkId = seriesLinkId,
            seasonNumber = seasonNumber,
            seasonCount = seasonCount,
            episodeNumber = episodeNumber,
            episodeCount = episodeCount,
            partNumber = partNumber,
            partCount = partCount,
            onscreen = episodeOnscreen,
        )
    }

    internal companion object {
        internal fun fromEvent(event: GatewayEpgEvent): ReducedEpgEvent? {
            if (event.stop < event.start) return null
            return ReducedEpgEvent(
                id = event.id,
                channelId = event.channelId,
                start = event.start,
                stop = event.stop,
                title = event.title,
                subtitle = event.subtitle,
                summary = event.summary,
                description = event.description,
                genre = event.genre,
                categories = event.categories?.toImmutableList(),
                keywords = event.keywords?.toImmutableList(),
                credits = event.credits?.toImmutableList(),
                seriesLinkUri = event.seriesLinkUri,
                episodeUri = event.episodeUri,
                contentType = event.contentType,
                ageRating = event.ageRating,
                ratingLabel = event.ratingLabel,
                ratingIcon = event.ratingIcon,
                ratingAuthority = event.ratingAuthority,
                ratingCountry = event.ratingCountry,
                starRating = event.starRating,
                copyrightYear = event.copyrightYear,
                firstAired = event.firstAired,
                isNew = event.isNew,
                seasonNumber = event.seasonNumber,
                seasonCount = event.seasonCount,
                episodeNumber = event.episodeNumber,
                episodeCount = event.episodeCount,
                partNumber = event.partNumber,
                partCount = event.partCount,
                episodeOnscreen = event.episodeOnscreen,
                episodeId = event.episodeId,
                seriesLinkId = event.seriesLinkId,
                image = event.image,
                dvrEntryId = event.dvrEntryId,
                nextEventId = event.nextEventId,
            )
        }

        internal fun fromQuery(event: GatewayEpgQueryEvent): ReducedEpgEvent? {
            if (event.stop < event.start) return null
            return ReducedEpgEvent(
                id = event.id,
                channelId = event.channelId,
                start = event.start,
                stop = event.stop,
                title = event.title,
                subtitle = event.subtitle,
                summary = event.summary,
                description = event.description,
                genre = null,
                categories = event.categories?.toImmutableList(),
                keywords = event.keywords?.toImmutableList(),
                credits = event.credits?.toImmutableList(),
                seriesLinkUri = event.seriesLinkUri,
                episodeUri = event.episodeUri,
                contentType = event.contentType,
                ageRating = event.ageRating,
                ratingLabel = event.ratingLabel,
                ratingIcon = event.ratingIcon,
                ratingAuthority = event.ratingAuthority,
                ratingCountry = event.ratingCountry,
                starRating = event.starRating,
                copyrightYear = event.copyrightYear,
                firstAired = event.firstAired,
                isNew = event.isNew,
                seasonNumber = event.seasonNumber,
                seasonCount = event.seasonCount,
                episodeNumber = event.episodeNumber,
                episodeCount = event.episodeCount,
                partNumber = event.partNumber,
                partCount = event.partCount,
                episodeOnscreen = event.episodeOnscreen,
                episodeId = null,
                seriesLinkId = null,
                image = event.image,
                dvrEntryId = event.dvrEntryId,
                nextEventId = event.nextEventId,
            )
        }
    }
}

internal class EpgQueryFence(
    internal val channelId: ChannelId,
    internal val authorityRevision: Long,
) {
    override fun toString(): String = "EpgQueryFence(<redacted>)"
}

internal enum class EpgQueryAcceptance {
    APPLIED,
    CAPACITY_REJECTED,
    STALE,
}

/**
 * Time anchors for making room at the retained-event limit: programmes that ended by
 * [endedBefore] yield first, and no channel loses a programme starting by [protectedThrough]
 * (or by its bound in [requestBounds], from explicit coverage requests) to eviction.
 */
internal class EpgCapacityWindow(
    internal val endedBefore: Instant,
    internal val protectedThrough: Instant,
    internal val requestBounds: Map<ChannelId, Instant> = emptyMap(),
) {
    override fun toString(): String = "EpgCapacityWindow(<redacted>)"
}

private const val RECENT_COVERAGE_REQUESTS = 8

/**
 * Explicit coverage requests of one generation, which protect their channels through the
 * requested end against eviction: those of the most recent calls (a batch is one call; an entry
 * leaves only when newer calls displace it) and those of every pending call. Its own lock keeps
 * callers off the metadata lock, so coverage requests and their cancellation never wait for a
 * query publication.
 */
internal class EpgCoverageRequests {
    private val lock = Any()
    private var owner: Any? = null
    private val recent = ArrayDeque<Request>()
    private val pending = HashSet<Request>()

    // Identity matters: equal calls are separate entries.
    private class Request(val channelIds: List<ChannelId>, val through: Instant) {
        override fun toString(): String = "EpgCoverageRequest(<redacted>)"
    }

    /** Forgets every request and accepts new ones only for [owner]. */
    internal fun reset(owner: Any?): Unit = synchronized(lock) {
        this.owner = owner
        recent.clear()
        pending.clear()
    }

    /**
     * Records one call protecting [channelIds] through [through] and returns its handle for
     * [release], or null when [owner] is not current.
     */
    internal fun remember(owner: Any, channelIds: Collection<ChannelId>, through: Instant): Any? =
        synchronized(lock) {
            if (this.owner !== owner || channelIds.isEmpty()) return null
            val request = Request(channelIds.toList(), through)
            recent.addLast(request)
            if (recent.size > RECENT_COVERAGE_REQUESTS) recent.removeFirst()
            pending += request
            request
        }

    /** Ends the pending protection of [request]; it stays protected while among the recent calls. */
    internal fun release(owner: Any, request: Any): Unit = synchronized(lock) {
        if (this.owner === owner) pending.remove(request)
    }

    /** The furthest protected request end per channel. */
    internal fun bounds(): Map<ChannelId, Instant> = synchronized(lock) {
        if (recent.isEmpty() && pending.isEmpty()) return emptyMap()
        val bounds = HashMap<ChannelId, Instant>()
        (recent.asSequence() + pending.asSequence()).forEach { request ->
            request.channelIds.forEach { channelId -> bounds.merge(channelId, request.through, ::maxOf) }
        }
        bounds
    }

    override fun toString(): String = "EpgCoverageRequests(<redacted>)"
}

// A channel whose far end eviction cut proves nothing past [ceiling], one second before the
// earliest evicted start (query coverage is inclusive), until a query reaches [evictedThrough],
// the latest evicted start.
private class EpgProofCap(var ceiling: Instant, var evictedThrough: Instant)

private class EpgEvictionPlan(
    val eventIds: Set<EventId>,
    val cuts: Map<ChannelId, EpgProofCap>,
)

private class EpgTailCandidate(val event: ReducedEpgEvent, val distance: Duration)

private val earliestEndFirst = compareBy<ReducedEpgEvent>({ it.stop }, { it.start }, { it.id.value })

// Per channel this is latest start first, so a channel only ever loses its far end.
private val farthestTailFirst = compareByDescending<EpgTailCandidate> { it.distance }
    .thenByDescending { it.event.start }
    .thenByDescending { it.event.id.value }

// Keeps the [limit] offered items that come first in [order] without sorting them all.
private class FirstInOrder<T>(private val limit: Int, private val order: Comparator<T>) {
    private val kept = PriorityQueue(maxOf(1, limit), order.reversed())

    fun offer(item: T) {
        when {
            limit <= 0 -> Unit
            kept.size < limit -> kept += item
            order.compare(item, kept.peek()) < 0 -> {
                kept.poll()
                kept += item
            }
        }
    }

    /** The first [count] kept items, in no particular order. */
    fun first(count: Int): Collection<T> {
        while (kept.size > count) kept.poll()
        return kept
    }
}

internal class EpgReducer(
    private val maximumRetainedEvents: Int = EpgCoveragePolicy.create().maximumRetainedEvents,
) {
    private val events = linkedMapOf<EventId, ReducedEpgEvent>()
    private val history = linkedMapOf<EventId, ReducedEpgEvent>()
    private val currentEventByChannel = linkedMapOf<ChannelId, EventId>()
    private val channelIds = linkedSetOf<ChannelId>()
    private val queriedToByChannel = linkedMapOf<ChannelId, Instant>()
    // Display-only programmes from the previous publication of the same profile. They are shown
    // only beyond live coverage, never counted as live coverage or capacity, and once live data
    // shadows them they are discarded for good.
    private val carryover = linkedMapOf<EventId, EpgEvent>()
    // Lower bound of each channel's earliest carryover start; a live end at or before it cannot
    // reach any carryover. Removals elsewhere may leave it low, which only costs one rescan.
    private val carryoverEarliestStart = hashMapOf<ChannelId, Instant>()
    // Ids this generation's server traffic named before installation (including while awaiting a
    // late seed) without leaving a live copy, such as deletions; their previous publication is
    // outdated. Replaced at installation
    // and reset to release its table. Bounded like the query authority ledger: a seed holds at
    // most maximumRetainedEvents events, so overflowing ids conservatively disable installation.
    private var carryoverExcluded = HashSet<EventId>()
    private var carryoverDisabled = false
    private var carryoverInstalled = false
    // While a generation that published without a seed waits for a late restored guide: per
    // channel, the latest live end seen since publication; carryover starting before it is gone.
    // The peak live count bounds what capacity would have evicted from an on-time install.
    private var carryoverAwaitingSeed = false
    private val carryoverReachedBefore = hashMapOf<ChannelId, Instant>()
    private var carryoverPeakLiveCount = 0
    // Mutation revisions exist only while an older in-flight query can still observe them.
    private val activeQueries = linkedSetOf<EpgQueryFence>()
    private val eventAuthorityRevisions = linkedMapOf<EventId, Long>()
    private val channelAuthorityRevisions = linkedMapOf<ChannelId, Long>()
    private var authorityRevision = 0L
    private var queriesInvalidAfterRevision: Long? = null
    // The last published snapshot stays valid until retained events, channels or query
    // coverage change, so unrelated metadata traffic republishes the identical instance.
    private var cachedSnapshot: EpgSnapshot? = null
    // Proofs cut back by far-end eviction; neither published coverage nor a completed-sync proof
    // reaches past a cap until a query proves the evicted range again.
    private val proofCaps = hashMapOf<ChannelId, EpgProofCap>()
    // Making room evicts down to this low-water mark, so one plan serves many admissions.
    private val evictionLowWater = maximumRetainedEvents - maxOf(1, maximumRetainedEvents / 100)
    internal var lastEventRefusedForCapacity: Boolean = false
        private set

    init {
        require(maximumRetainedEvents > 0) { "EPG retained-event limit must be positive" }
    }

    internal fun clear() {
        cachedSnapshot = null
        lastEventRefusedForCapacity = false
        events.clear()
        history.clear()
        currentEventByChannel.clear()
        channelIds.clear()
        queriedToByChannel.clear()
        carryover.clear()
        carryoverEarliestStart.clear()
        carryoverExcluded = HashSet()
        carryoverDisabled = false
        carryoverInstalled = false
        carryoverAwaitingSeed = false
        carryoverReachedBefore.clear()
        carryoverPeakLiveCount = 0
        activeQueries.clear()
        eventAuthorityRevisions.clear()
        channelAuthorityRevisions.clear()
        authorityRevision = 0L
        queriesInvalidAfterRevision = null
        proofCaps.clear()
    }

    /** The latest instant any proof for [channelId] may reach after far-end eviction, if capped. */
    internal fun proofCeiling(channelId: ChannelId): Instant? = proofCaps[channelId]?.ceiling

    /** Returns whether this reducer accepted the event (including no-ops) or pruned display history. */
    internal fun accept(
        event: MetadataEvent,
        serverNow: Instant? = null,
        retainFrom: Long = Long.MIN_VALUE,
        capacity: (() -> EpgCapacityWindow)? = null,
    ): Boolean {
        lastEventRefusedForCapacity = false
        val previousHistorySize = history.size
        serverNow?.let(::pruneHistory)
        val historyChanged = history.size != previousHistorySize
        when (event) {
            is MetadataEvent.ChannelAdded -> {
                currentEventByChannel.remove(event.channel.id)
                event.channel.currentEventId?.let { currentEventByChannel[event.channel.id] = it }
                recordChannelAuthority(event.channel.id)
                if (channelIds.add(event.channel.id)) cachedSnapshot = null
            }
            is MetadataEvent.ChannelUpdated -> {
                currentEventByChannel.remove(event.channel.id)
                event.channel.currentEventId?.let { currentEventByChannel[event.channel.id] = it }
                if (channelIds.add(event.channel.id)) cachedSnapshot = null
            }
            is MetadataEvent.ChannelNowNextUpdated -> {
                if (event.channelId !in channelIds) return historyChanged
                currentEventByChannel.remove(event.channelId)
                event.currentEventId?.let { currentEventByChannel[event.channelId] = it }
            }
            is MetadataEvent.ChannelDeleted -> {
                cachedSnapshot = null
                recordChannelAuthority(event.channelId)
                removeChannel(event.channelId)
            }
            is MetadataEvent.EventAdded -> {
                recordEventAuthority(event.event.id)
                val carryoverChanged = removeCarryover(event.event.id)
                val accepted = acceptEvent(event.event, retainFrom, capacity)
                excludeFromCarryover(event.event.id)
                return accepted || historyChanged || carryoverChanged
            }
            is MetadataEvent.EventUpdated -> {
                recordEventAuthority(event.event.id)
                val carryoverChanged = removeCarryover(event.event.id)
                val accepted = acceptEvent(event.event, retainFrom, capacity)
                excludeFromCarryover(event.event.id)
                return accepted || historyChanged || carryoverChanged
            }
            is MetadataEvent.EventDeleted -> {
                cachedSnapshot = null
                recordEventAuthority(event.eventId)
                currentEventByChannel.entries.removeIf { it.value == event.eventId }
                removeCarryover(event.eventId)
                val removed = events.remove(event.eventId)
                excludeFromCarryover(event.eventId)
                if (removed != null && serverNow != null) {
                    val programme = removed.toPublicOrNull()
                    // channelUpdate can prove the next programme is current before the
                    // whole-second getSysTime estimate reaches the exact stop boundary.
                    val currentStart = currentEventByChannel[removed.channelId]
                        ?.let(events::get)?.takeIf { it.channelId == removed.channelId }?.start
                    val ended = programme != null && (programme.stop <= serverNow ||
                        currentStart?.let { programme.stop <= it } == true)
                    if (programme != null && programme.start < programme.stop &&
                        ended && programme.stop > serverNow - 6.hours &&
                        programme.channelId in channelIds &&
                        events.values.none { overlaps(it, removed) }
                    ) {
                        history[event.eventId] = removed
                        trimHistory()
                    }
                }
            }
            is MetadataEvent.TagAdded,
            is MetadataEvent.TagUpdated,
            is MetadataEvent.TagDeleted,
            is MetadataEvent.InitialSyncCompleted,
            is MetadataEvent.DvrEntryAdded,
            is MetadataEvent.DvrEntryUpdated,
            is MetadataEvent.DvrEntryStatsUpdated,
            is MetadataEvent.DvrEntryDeleted,
            is MetadataEvent.AutorecRuleAdded,
            is MetadataEvent.AutorecRuleUpdated,
            is MetadataEvent.AutorecRuleDeleted,
            is MetadataEvent.TimerecRuleAdded,
            is MetadataEvent.TimerecRuleUpdated,
            is MetadataEvent.TimerecRuleDeleted,
            -> return historyChanged
        }
        return true
    }

    internal fun reconcileChannels(validChannelIds: Collection<ChannelId>) {
        cachedSnapshot = null
        val valid = validChannelIds.toSet()
        channelIds.clear()
        channelIds.addAll(validChannelIds)
        currentEventByChannel.keys.retainAll(valid)
        events.entries.removeIf { entry ->
            entry.value.toPublicOrNull() == null ||
                entry.value.channelId?.let { it !in valid } == true
        }
        history.entries.removeIf { it.value.channelId !in valid }
        queriedToByChannel.keys.retainAll(valid)
        proofCaps.keys.retainAll(valid)
        carryover.values.removeIf { it.channelId !in valid }
        carryoverEarliestStart.keys.retainAll(valid)
        carryoverReachedBefore.keys.retainAll(valid)
    }

    /**
     * Installs display-only [previous] programmes once live data first publishes for a
     * generation. Only events of known channels that initial synchronisation proved through
     * [provenThrough] are kept, when they start after that proof, at or after the channel's live
     * events, outside ids the server already named, and overlap the retention window. Nothing is
     * installed when too many named ids overflowed their bound. Later reduction discards
     * carryover for good wherever live data reaches.
     *
     * With [awaitLateSeed], nothing is installed yet: named ids and live reach stay tracked until
     * [installLateCarryover] receives the restored guide or [releaseCarryoverTracking] ends the wait.
     */
    internal fun installCarryover(
        previous: Collection<EpgEvent>,
        provenThrough: Map<ChannelId, Instant>,
        retainFrom: Long = Long.MIN_VALUE,
        retainTo: Long = Long.MAX_VALUE,
        awaitLateSeed: Boolean = false,
    ) {
        carryover.clear()
        carryoverEarliestStart.clear()
        cachedSnapshot = null
        val liveEnds = liveEnds()
        if (awaitLateSeed) {
            carryoverAwaitingSeed = true
            carryoverReachedBefore.putAll(liveEnds)
            carryoverPeakLiveCount = events.size
            return
        }
        if (!carryoverDisabled) {
            for (event in previous) {
                val channelId = event.channelId ?: continue
                val proven = provenThrough[channelId] ?: continue
                if (channelId !in channelIds || event.id in events || event.id in carryoverExcluded) continue
                if (event.start <= proven || liveEnds[channelId]?.let { event.start < it } == true) continue
                if (queriedToByChannel[channelId]?.let { event.start <= it } == true) continue
                if (event.stop.epochSeconds < retainFrom || event.start.epochSeconds > retainTo) continue
                carryover[event.id] = event
                if (carryoverEarliestStart[channelId]?.let { event.start >= it } != true) {
                    carryoverEarliestStart[channelId] = event.start
                }
            }
        }
        releaseCarryoverTracking()
        trimCarryover()
    }

    /**
     * Installs [previous] as the seed a generation waited for after publishing without one, under
     * the same rules as at publication; live data since then shadows it as if it had been installed
     * on time. Returns whether visible carryover changed; does nothing unless waiting.
     *
     * Capacity evicts carryover for good, but which events an on-time install would have lost is
     * not recorded. So the seed is refused for this generation unless every live count since
     * publication left room for all of [previous], where an on-time install never evicted any.
     */
    internal fun installLateCarryover(
        previous: Collection<EpgEvent>,
        provenThrough: Map<ChannelId, Instant>,
        retainFrom: Long = Long.MIN_VALUE,
        retainTo: Long = Long.MAX_VALUE,
    ): Boolean {
        if (!carryoverAwaitingSeed) return false
        if (carryoverPeakLiveCount.toLong() + previous.size > maximumRetainedEvents) carryoverDisabled = true
        installCarryover(previous, provenThrough, retainFrom, retainTo)
        return carryover.isNotEmpty()
    }

    /** Ends a post-publication wait for a late seed, if any; tracking before publication continues. */
    internal fun releaseLateSeedWait() {
        if (carryoverAwaitingSeed) releaseCarryoverTracking()
    }

    /** Stops tracking named ids and live reach for carryover installation in this generation. */
    internal fun releaseCarryoverTracking() {
        carryoverExcluded = HashSet()
        carryoverReachedBefore.clear()
        carryoverAwaitingSeed = false
        carryoverPeakLiveCount = 0
        carryoverInstalled = true
    }

    // Named ids are tracked from binding until the generation's seed decision.
    private val carryoverTracking: Boolean get() = !carryoverInstalled && !carryoverDisabled

    private fun recordCarryoverLiveCount() {
        if (carryoverAwaitingSeed && events.size > carryoverPeakLiveCount) carryoverPeakLiveCount = events.size
    }

    private fun liveEnds(): HashMap<ChannelId, Instant> {
        val liveEnds = HashMap<ChannelId, Instant>(channelIds.size * 2)
        carryoverReachedBefore.forEach { (channelId, reached) ->
            if (channelId in channelIds) liveEnds[channelId] = reached
        }
        for (reduced in events.values) {
            val channelId = reduced.channelId ?: continue
            if (liveEnds[channelId]?.let { reduced.stop <= it } != true) liveEnds[channelId] = reduced.stop
        }
        return liveEnds
    }

    private fun recordCarryoverReach(channelId: ChannelId?, liveEnd: Instant) {
        if (!carryoverAwaitingSeed || channelId == null || channelId !in channelIds) return
        if (carryoverReachedBefore[channelId]?.let { liveEnd <= it } != true) {
            carryoverReachedBefore[channelId] = liveEnd
        }
    }

    internal fun beginQuery(channelId: ChannelId): EpgQueryFence? {
        if (channelId !in channelIds) return null
        return EpgQueryFence(channelId, authorityRevision).also { query ->
            activeQueries += query
        }
    }

    internal fun abandonQuery(query: EpgQueryFence) {
        if (activeQueries.remove(query)) pruneQueryAuthority()
    }

    internal fun recordSuccessfulQuery(channelId: ChannelId, queriedTo: Instant) {
        if (channelId !in channelIds) return
        val previous = queriedToByChannel[channelId]
        if (previous == null || queriedTo > previous) {
            cachedSnapshot = null
            queriedToByChannel[channelId] = queriedTo
            dropCarryoverBefore(channelId, queriedTo, inclusive = true)
        }
    }

    internal fun acceptSuccessfulQuery(
        query: EpgQueryFence,
        queriedTo: Instant,
        queriedEvents: List<GatewayEpgQueryEvent>,
        retainFrom: Long = Long.MIN_VALUE,
        capacity: (() -> EpgCapacityWindow)? = null,
    ): EpgQueryAcceptance {
        if (!activeQueries.remove(query)) return EpgQueryAcceptance.STALE
        return try {
            if (
                query.channelId !in channelIds ||
                queriesInvalidAfterRevision
                    ?.let { revision -> revision > query.authorityRevision } == true ||
                channelAuthorityRevisions[query.channelId]
                    ?.let { revision -> revision > query.authorityRevision } == true
            ) {
                EpgQueryAcceptance.STALE
            } else {
                val stagedEvents = linkedMapOf<EventId, ReducedEpgEvent>()
                for (event in queriedEvents) {
                    if (event.channelId != null && event.channelId != query.channelId) continue
                    if (
                        eventAuthorityRevisions[event.id]
                            ?.let { revision -> revision > query.authorityRevision } == true
                    ) {
                        continue
                    }
                    val candidate = ReducedEpgEvent.fromQuery(event)
                    if (candidate != null) {
                        stagedEvents[event.id] = candidate
                    }
                }
                // Check the final set, not response order: replacements can release capacity.
                var retainedCount = events.size
                stagedEvents.forEach { (eventId, event) ->
                    if (eventId in events) retainedCount -= 1
                    if (event.stop.epochSeconds >= retainFrom) retainedCount += 1
                }
                // Plan room over the would-be retained set before mutating, so a query that only
                // protected events would overflow is rejected atomically.
                val eviction = if (retainedCount <= maximumRetainedEvents) {
                    null
                } else {
                    val window = capacity ?: return EpgQueryAcceptance.CAPACITY_REJECTED
                    val pool = events.values.asSequence().filter { event -> event.id !in stagedEvents } +
                        stagedEvents.values.asSequence().filter { event -> event.stop.epochSeconds >= retainFrom }
                    planEviction(pool, retainedCount - maximumRetainedEvents, retainedCount - evictionLowWater, window())
                        ?: return EpgQueryAcceptance.CAPACITY_REJECTED
                }
                eviction?.let(::evictEvents)
                var liveEnd: Instant? = null
                stagedEvents.forEach { (eventId, event) ->
                    removeCarryover(eventId)
                    supersedeHistory(event)
                    if (event.stop.epochSeconds < retainFrom) {
                        if (events.remove(eventId) != null) cachedSnapshot = null
                    } else {
                        // An evicted reply event still reached its start: carryover there stays gone.
                        if (eviction?.eventIds?.contains(eventId) != true && events[eventId] != event) {
                            cachedSnapshot = null
                            events[eventId] = event
                        }
                        if (event.channelId != null && liveEnd?.let { event.stop <= it } != true) liveEnd = event.stop
                    }
                    excludeFromCarryover(eventId)
                }
                recordCarryoverLiveCount()
                liveEnd?.let { end ->
                    dropCarryoverBefore(query.channelId, end, inclusive = false)
                    recordCarryoverReach(query.channelId, end)
                }
                recordSuccessfulQuery(query.channelId, queriedTo)
                liftProofCap(query.channelId, queriedTo)
                eviction?.let { plan -> pullBackCoverage(plan.cuts) }
                trimCarryover()
                EpgQueryAcceptance.APPLIED
            }
        } finally {
            pruneQueryAuthority()
        }
    }

    internal fun retainOverlapping(from: Instant, to: Instant) {
        require(to >= from) { "EPG retention stop must not precede start" }
        if (!carryoverTracking) {
            if (events.values.removeIf { event -> !event.shouldRetain(from, to) }) cachedSnapshot = null
        } else {
            // A pending seed must not revive an id live data held.
            val pruned = events.values.filterNot { event -> event.shouldRetain(from, to) }
            if (pruned.isNotEmpty()) cachedSnapshot = null
            pruned.forEach { event -> events.remove(event.id) }
            pruned.forEach { event -> excludeFromCarryover(event.id) }
        }
        if (carryover.values.removeIf { event -> event.stop < from || event.start > to }) {
            cachedSnapshot = null
        }
    }

    internal fun pruneHistory(serverNow: Instant) {
        if (history.entries.removeIf { (_, event) ->
                event.stop <= serverNow - 6.hours
            }
        ) cachedSnapshot = null
    }

    private fun trimHistory() {
        while (history.size > maximumRetainedEvents) {
            val oldest = history.values.minWith(compareBy({ it.stop }, { it.id.value }))
            history.remove(oldest.id)
        }
    }

    private fun overlaps(left: ReducedEpgEvent, right: ReducedEpgEvent): Boolean =
        left.channelId != null && left.channelId == right.channelId &&
            left.start < right.stop && right.start < left.stop

    private fun supersedeHistory(event: ReducedEpgEvent): Boolean {
        val changed = history.entries.removeIf { it.key == event.id || overlaps(it.value, event) }
        if (changed) {
            cachedSnapshot = null
        }
        return changed
    }

    internal fun snapshot(): EpgSnapshot {
        cachedSnapshot?.let { return it }
        val visibleEvents = ArrayList<EpgEvent>(events.size)
        val boundsByChannel = HashMap<ChannelId, CoverageBounds>(channelIds.size * 2)
        for (reduced in events.values) {
            val event = reduced.toPublicOrNull() ?: continue
            val channelId = event.channelId
            if (channelId != null) {
                if (channelId !in channelIds) continue
                val bounds = boundsByChannel.getOrPut(channelId) { CoverageBounds(event.start, event.stop) }
                if (event.start < bounds.from) bounds.from = event.start
                if (event.stop > bounds.to) bounds.to = event.stop
            }
            visibleEvents.add(event)
        }
        val coverages = channelIds.map { channelId ->
            // Programmes admitted past a cut are shown, but only a query lifts the cap.
            val ceiling = proofCaps[channelId]?.ceiling
            val queriedTo = queriedToByChannel[channelId]?.let { queried -> ceiling?.let { minOf(it, queried) } ?: queried }
            val bounds = boundsByChannel[channelId]
            if (bounds == null || ceiling != null && bounds.from > ceiling) {
                EpgCoverage.empty(channelId, queriedTo)
            } else {
                EpgCoverage.create(
                    channelId = channelId,
                    coveredFrom = bounds.from,
                    coveredTo = ceiling?.let { minOf(it, bounds.to) } ?: bounds.to,
                    queriedTo = queriedTo,
                )
            }
        }
        visibleEvents.addAll(carryover.values)
        return EpgSnapshot.create(
            visibleEvents, coverages,
            history.values.mapNotNull { it.toPublicOrNull() }.sortedWith(
                compareBy({ it.start }, { it.stop }, { it.id.value }),
            ),
        ).also { cachedSnapshot = it }
    }

    private class CoverageBounds(var from: Instant, var to: Instant)

    // Live wins, irreversibly and independently of publication batching: once live data names an
    // id or reaches a start time, the carryover there is gone even if live data later recedes.
    private fun removeCarryover(eventId: EventId): Boolean {
        if (carryover.remove(eventId) == null) return false
        cachedSnapshot = null
        return true
    }

    private fun excludeFromCarryover(eventId: EventId) {
        // A retained live copy already shadows the id at installation; remember the rest.
        if (carryoverInstalled || carryoverDisabled || eventId in events || eventId in carryoverExcluded) return
        if (carryoverExcluded.size >= maximumRetainedEvents) {
            // Forgetting an exclusion could revive a deleted programme; skip installation instead.
            carryoverDisabled = true
            carryoverExcluded = HashSet()
        } else {
            carryoverExcluded += eventId
        }
    }

    private fun dropCarryoverBefore(channelId: ChannelId?, bound: Instant, inclusive: Boolean) {
        if (channelId == null) return
        val earliest = carryoverEarliestStart[channelId] ?: return
        if (earliest > bound || !inclusive && earliest == bound) return
        var remaining: Instant? = null
        if (carryover.values.removeIf { event ->
                if (event.channelId != channelId) return@removeIf false
                val reached = event.start < bound || inclusive && event.start == bound
                if (!reached && remaining?.let { event.start >= it } != true) remaining = event.start
                reached
            }
        ) cachedSnapshot = null
        remaining?.let { carryoverEarliestStart[channelId] = it } ?: carryoverEarliestStart.remove(channelId)
    }

    // Carryover only uses capacity live events leave free, evicting the latest start first.
    private fun trimCarryover() {
        val excess = carryover.size - (maximumRetainedEvents - events.size).coerceAtLeast(0)
        if (excess <= 0) return
        cachedSnapshot = null
        val latestFirst = compareByDescending<EpgEvent> { it.start }.thenByDescending { it.id.value }
        if (excess == 1) {
            carryover.remove(carryover.values.minWith(latestFirst).id)
        } else {
            carryover.values.sortedWith(latestFirst).take(excess).forEach { carryover.remove(it.id) }
        }
    }

    private fun acceptEvent(event: GatewayEpgEvent, retainFrom: Long, capacity: (() -> EpgCapacityWindow)?): Boolean {
        val candidate = ReducedEpgEvent.fromEvent(event) ?: return false
        val historyChanged = supersedeHistory(candidate)
        if (candidate.stop.epochSeconds < retainFrom) {
            val removed = events.remove(event.id) != null
            if (removed) cachedSnapshot = null
            return removed || historyChanged
        }
        if (event.id !in events && events.size >= maximumRetainedEvents) {
            val eviction = capacity?.let { window ->
                planEviction(
                    events.values.asSequence() + candidate,
                    events.size + 1 - maximumRetainedEvents,
                    events.size + 1 - evictionLowWater,
                    window(),
                )
            }
            if (eviction == null) {
                lastEventRefusedForCapacity = true
                if (carryoverAwaitingSeed) carryoverPeakLiveCount = maximumRetainedEvents
                invalidateActiveQueriesAfterCapacityDrop()
                return historyChanged
            }
            evictEvents(eviction)
            pullBackCoverage(eviction.cuts)
            if (candidate.id in eviction.eventIds) {
                // The newcomer is itself the farthest far end: admitted and evicted at once.
                dropCarryoverBefore(candidate.channelId, candidate.stop, inclusive = false)
                recordCarryoverReach(candidate.channelId, candidate.stop)
                return true
            }
        }
        // Identical full entities still supersede history without rebuilding an unchanged snapshot.
        if (events[event.id] != candidate) {
            cachedSnapshot = null
            events[event.id] = candidate
        }
        recordCarryoverLiveCount()
        dropCarryoverBefore(candidate.channelId, candidate.stop, inclusive = false)
        recordCarryoverReach(candidate.channelId, candidate.stop)
        trimCarryover()
        return true
    }

    /**
     * Plans room in [pool], the would-be retained set, without mutating it: at least [needed] and
     * up to [wanted] evictions. Ended programmes go first, earliest end first. Then programmes
     * starting beyond their channel's protected window, farthest beyond it first (ties: latest
     * start, then id), so each channel only loses its far end. Returns null when protected
     * programmes alone leave too little room.
     */
    private fun planEviction(
        pool: Sequence<ReducedEpgEvent>,
        needed: Int,
        wanted: Int,
        window: EpgCapacityWindow,
    ): EpgEvictionPlan? {
        val ended = FirstInOrder(wanted, earliestEndFirst)
        val tails = FirstInOrder(wanted, farthestTailFirst)
        for (event in pool) {
            if (event.stop <= window.endedBefore) {
                ended.offer(event)
                continue
            }
            val requestBound = event.channelId?.let(window.requestBounds::get)
            val bound = requestBound?.let { maxOf(it, window.protectedThrough) } ?: window.protectedThrough
            if (event.start > bound) tails.offer(EpgTailCandidate(event, event.start - bound))
        }
        val endedFirst = ended.first(wanted)
        val tailFirst = tails.first(wanted - endedFirst.size)
        if (endedFirst.size + tailFirst.size < needed) return null
        val evicted = HashSet<EventId>(endedFirst.size + tailFirst.size)
        endedFirst.forEach { event -> evicted += event.id }
        val cuts = HashMap<ChannelId, EpgProofCap>()
        tailFirst.forEach { candidate ->
            val event = candidate.event
            evicted += event.id
            val channelId = event.channelId ?: return@forEach
            val ceiling = event.start - 1.seconds
            val cut = cuts.getOrPut(channelId) { EpgProofCap(ceiling, event.start) }
            if (ceiling < cut.ceiling) cut.ceiling = ceiling
            if (event.start > cut.evictedThrough) cut.evictedThrough = event.start
        }
        return EpgEvictionPlan(evicted, cuts)
    }

    private fun evictEvents(plan: EpgEvictionPlan) {
        plan.eventIds.forEach { eventId ->
            if (events.remove(eventId) != null) {
                cachedSnapshot = null
                // A pending seed must not revive what live data held.
                excludeFromCarryover(eventId)
            }
        }
    }

    // Coverage is a proof: a channel cut at its far end proves nothing from the cut on, and an
    // older in-flight query for it may have skipped newer copies of what was evicted, so it
    // must not re-advance the channel.
    private fun pullBackCoverage(cuts: Map<ChannelId, EpgProofCap>) {
        cuts.forEach { (channelId, cut) ->
            cachedSnapshot = null
            recordChannelAuthority(channelId)
            val cap = proofCaps.getOrPut(channelId) { cut }
            if (cut.ceiling < cap.ceiling) cap.ceiling = cut.ceiling
            if (cut.evictedThrough > cap.evictedThrough) cap.evictedThrough = cut.evictedThrough
            if (queriedToByChannel[channelId]?.let { it > cap.ceiling } == true) queriedToByChannel[channelId] = cap.ceiling
        }
    }

    // A query through [queriedTo] proves the channel again up to there; evicted programmes
    // beyond it stay unproven, as do later admissions past the cap.
    private fun liftProofCap(channelId: ChannelId, queriedTo: Instant) {
        val cap = proofCaps[channelId] ?: return
        if (queriedTo <= cap.ceiling) return
        cachedSnapshot = null
        if (queriedTo >= cap.evictedThrough) proofCaps.remove(channelId) else cap.ceiling = queriedTo
    }

    private fun invalidateActiveQueriesAfterCapacityDrop() {
        if (activeQueries.isNotEmpty()) queriesInvalidAfterRevision = authorityRevision
    }

    private fun recordEventAuthority(eventId: EventId) {
        if (activeQueries.isEmpty()) return
        authorityRevision += 1
        if (
            eventId in eventAuthorityRevisions ||
            eventAuthorityRevisions.size < maximumRetainedEvents
        ) {
            eventAuthorityRevisions[eventId] = authorityRevision
        } else {
            queriesInvalidAfterRevision = authorityRevision
        }
    }

    private fun recordChannelAuthority(channelId: ChannelId) {
        if (activeQueries.isEmpty()) return
        authorityRevision += 1
        if (
            channelId in channelAuthorityRevisions ||
            channelAuthorityRevisions.size < maximumRetainedEvents
        ) {
            channelAuthorityRevisions[channelId] = authorityRevision
        } else {
            queriesInvalidAfterRevision = authorityRevision
        }
    }

    private fun pruneQueryAuthority() {
        val oldestActiveRevision = activeQueries.minOfOrNull(EpgQueryFence::authorityRevision)
        if (oldestActiveRevision == null) {
            eventAuthorityRevisions.clear()
            channelAuthorityRevisions.clear()
            authorityRevision = 0L
            queriesInvalidAfterRevision = null
        } else {
            eventAuthorityRevisions.entries.removeIf { entry ->
                entry.value <= oldestActiveRevision
            }
            channelAuthorityRevisions.entries.removeIf { entry ->
                entry.value <= oldestActiveRevision
            }
            if (
                queriesInvalidAfterRevision
                    ?.let { revision -> revision <= oldestActiveRevision } == true
            ) {
                queriesInvalidAfterRevision = null
            }
        }
    }

    private fun removeChannel(channelId: ChannelId) {
        channelIds.remove(channelId)
        currentEventByChannel.remove(channelId)
        queriedToByChannel.remove(channelId)
        proofCaps.remove(channelId)
        carryover.values.removeIf { event -> event.channelId == channelId }
        carryoverEarliestStart.remove(channelId)
        carryoverReachedBefore.remove(channelId)
        if (!carryoverTracking) {
            events.entries.removeIf { entry -> entry.value.channelId == channelId }
        } else {
            // A pending seed may hold these ids on another channel; the server named them here.
            val removed = events.values.filter { event -> event.channelId == channelId }.map { event -> event.id }
            removed.forEach(events::remove)
            removed.forEach(::excludeFromCarryover)
        }
        history.entries.removeIf { entry -> entry.value.channelId == channelId }
    }

}

private fun <T> Collection<T>.toImmutableList(): List<T> =
    Collections.unmodifiableList(ArrayList(this))
