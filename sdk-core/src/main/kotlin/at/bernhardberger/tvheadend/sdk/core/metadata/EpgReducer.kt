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
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours

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
    private var carryoverAwaitingSeed = false
    private val carryoverReachedBefore = hashMapOf<ChannelId, Instant>()
    // Mutation revisions exist only while an older in-flight query can still observe them.
    private val activeQueries = linkedSetOf<EpgQueryFence>()
    private val eventAuthorityRevisions = linkedMapOf<EventId, Long>()
    private val channelAuthorityRevisions = linkedMapOf<ChannelId, Long>()
    private var authorityRevision = 0L
    private var queriesInvalidAfterRevision: Long? = null
    // The last published snapshot stays valid until retained events, channels or query
    // coverage change, so unrelated metadata traffic republishes the identical instance.
    private var cachedSnapshot: EpgSnapshot? = null
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
        activeQueries.clear()
        eventAuthorityRevisions.clear()
        channelAuthorityRevisions.clear()
        authorityRevision = 0L
        queriesInvalidAfterRevision = null
    }

    /** Returns whether this reducer accepted the event (including no-ops) or pruned display history. */
    internal fun accept(event: MetadataEvent, serverNow: Instant? = null, retainFrom: Long = Long.MIN_VALUE): Boolean {
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
                val accepted = acceptEvent(event.event, retainFrom)
                excludeFromCarryover(event.event.id)
                return accepted || historyChanged || carryoverChanged
            }
            is MetadataEvent.EventUpdated -> {
                recordEventAuthority(event.event.id)
                val carryoverChanged = removeCarryover(event.event.id)
                val accepted = acceptEvent(event.event, retainFrom)
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
     */
    internal fun installLateCarryover(
        previous: Collection<EpgEvent>,
        provenThrough: Map<ChannelId, Instant>,
        retainFrom: Long = Long.MIN_VALUE,
        retainTo: Long = Long.MAX_VALUE,
    ): Boolean {
        if (!carryoverAwaitingSeed) return false
        installCarryover(previous, provenThrough, retainFrom, retainTo)
        return carryover.isNotEmpty()
    }

    /** Stops tracking named ids and live reach for carryover installation in this generation. */
    internal fun releaseCarryoverTracking() {
        carryoverExcluded = HashSet()
        carryoverReachedBefore.clear()
        carryoverAwaitingSeed = false
        carryoverInstalled = true
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
                if (retainedCount > maximumRetainedEvents) return EpgQueryAcceptance.CAPACITY_REJECTED
                var liveEnd: Instant? = null
                stagedEvents.forEach { (eventId, event) ->
                    removeCarryover(eventId)
                    supersedeHistory(event)
                    if (event.stop.epochSeconds < retainFrom) {
                        if (events.remove(eventId) != null) cachedSnapshot = null
                    } else {
                        if (events[eventId] != event) {
                            cachedSnapshot = null
                            events[eventId] = event
                        }
                        if (event.channelId != null && liveEnd?.let { event.stop <= it } != true) liveEnd = event.stop
                    }
                    excludeFromCarryover(eventId)
                }
                liveEnd?.let { end ->
                    dropCarryoverBefore(query.channelId, end, inclusive = false)
                    recordCarryoverReach(query.channelId, end)
                }
                recordSuccessfulQuery(query.channelId, queriedTo)
                trimCarryover()
                EpgQueryAcceptance.APPLIED
            }
        } finally {
            pruneQueryAuthority()
        }
    }

    internal fun retainOverlapping(from: Instant, to: Instant) {
        require(to >= from) { "EPG retention stop must not precede start" }
        val pruned = events.values.filterNot { event -> event.shouldRetain(from, to) }
        if (pruned.isNotEmpty()) {
            cachedSnapshot = null
            pruned.forEach { event ->
                events.remove(event.id)
                // A late seed must not revive an id live data held since publication.
                if (carryoverAwaitingSeed) excludeFromCarryover(event.id)
            }
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
            val queriedTo = queriedToByChannel[channelId]
            when (val bounds = boundsByChannel[channelId]) {
                null -> EpgCoverage.empty(channelId, queriedTo)
                else -> EpgCoverage.create(
                    channelId = channelId,
                    coveredFrom = bounds.from,
                    coveredTo = bounds.to,
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

    private fun acceptEvent(event: GatewayEpgEvent, retainFrom: Long): Boolean {
        val candidate = ReducedEpgEvent.fromEvent(event) ?: return false
        val historyChanged = supersedeHistory(candidate)
        if (candidate.stop.epochSeconds < retainFrom) {
            val removed = events.remove(event.id) != null
            if (removed) cachedSnapshot = null
            return removed || historyChanged
        }
        if (event.id !in events && events.size >= maximumRetainedEvents) {
            lastEventRefusedForCapacity = true
            invalidateActiveQueriesAfterCapacityDrop()
            return historyChanged
        }
        // Identical full entities still supersede history without rebuilding an unchanged snapshot.
        if (events[event.id] != candidate) {
            cachedSnapshot = null
            events[event.id] = candidate
        }
        dropCarryoverBefore(candidate.channelId, candidate.stop, inclusive = false)
        recordCarryoverReach(candidate.channelId, candidate.stop)
        trimCarryover()
        return true
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
        carryover.values.removeIf { event -> event.channelId == channelId }
        carryoverEarliestStart.remove(channelId)
        carryoverReachedBefore.remove(channelId)
        events.entries.removeIf { entry -> entry.value.channelId == channelId }
        history.entries.removeIf { entry -> entry.value.channelId == channelId }
    }

}

private fun <T> Collection<T>.toImmutableList(): List<T> =
    Collections.unmodifiableList(ArrayList(this))
