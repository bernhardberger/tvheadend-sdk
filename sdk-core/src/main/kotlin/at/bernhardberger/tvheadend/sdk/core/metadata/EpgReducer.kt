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
                return acceptEvent(event.event, retainFrom) || historyChanged || carryoverChanged
            }
            is MetadataEvent.EventUpdated -> {
                recordEventAuthority(event.event.id)
                val carryoverChanged = removeCarryover(event.event.id)
                return acceptEvent(event.event, retainFrom) || historyChanged || carryoverChanged
            }
            is MetadataEvent.EventDeleted -> {
                cachedSnapshot = null
                recordEventAuthority(event.eventId)
                carryover.remove(event.eventId)
                currentEventByChannel.entries.removeIf { it.value == event.eventId }
                val removed = events.remove(event.eventId)
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
    }

    /**
     * Installs display-only [previous] programmes once live data first publishes for a
     * generation. Only channel-attributed events for known channels that start after the
     * initial synchronisation proved their channel through [provenThrough], and that overlap the
     * retention window, are kept; [snapshot] hides the rest wherever live coverage reaches.
     */
    internal fun installCarryover(
        previous: Collection<EpgEvent>,
        provenThrough: Map<ChannelId, Instant>,
        retainFrom: Long = Long.MIN_VALUE,
        retainTo: Long = Long.MAX_VALUE,
    ) {
        carryover.clear()
        cachedSnapshot = null
        for (event in previous) {
            val channelId = event.channelId ?: continue
            if (channelId !in channelIds || event.id in events) continue
            if (provenThrough[channelId]?.let { event.start <= it } == true) continue
            if (event.stop.epochSeconds < retainFrom || event.start.epochSeconds > retainTo) continue
            carryover[event.id] = event
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
                stagedEvents.forEach { (eventId, event) ->
                    removeCarryover(eventId)
                    supersedeHistory(event)
                    if (event.stop.epochSeconds < retainFrom) {
                        if (events.remove(eventId) != null) cachedSnapshot = null
                    } else if (events[eventId] != event) {
                        cachedSnapshot = null
                        events[eventId] = event
                    }
                }
                recordSuccessfulQuery(query.channelId, queriedTo)
                EpgQueryAcceptance.APPLIED
            }
        } finally {
            pruneQueryAuthority()
        }
    }

    internal fun retainOverlapping(from: Instant, to: Instant) {
        require(to >= from) { "EPG retention stop must not precede start" }
        if (events.entries.removeIf { entry -> !entry.value.shouldRetain(from, to) }) {
            cachedSnapshot = null
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
        if (carryover.isNotEmpty()) appendCarryover(visibleEvents, boundsByChannel)
        return EpgSnapshot.create(
            visibleEvents, coverages,
            history.values.mapNotNull { it.toPublicOrNull() }.sortedWith(
                compareBy({ it.start }, { it.stop }, { it.id.value }),
            ),
        ).also { cachedSnapshot = it }
    }

    private class CoverageBounds(var from: Instant, var to: Instant)

    /**
     * Live wins: a carryover event survives only while its id is not live and it starts at or
     * after its channel's retained live events and after its queried boundary. Capacity is
     * whatever live events leave free, evicting the latest-starting carryover first.
     */
    private fun appendCarryover(visibleEvents: MutableList<EpgEvent>, boundsByChannel: Map<ChannelId, CoverageBounds>) {
        carryover.values.removeIf { event ->
            val channelId = event.channelId
            channelId !in channelIds || event.id in events ||
                boundsByChannel[channelId]?.let { event.start < it.to } == true ||
                queriedToByChannel[channelId]?.let { event.start <= it } == true
        }
        val excess = carryover.size - (maximumRetainedEvents - events.size).coerceAtLeast(0)
        if (excess > 0) {
            carryover.values
                .sortedWith(compareByDescending<EpgEvent> { it.start }.thenByDescending { it.id.value })
                .take(excess)
                .forEach { carryover.remove(it.id) }
        }
        visibleEvents.addAll(carryover.values)
    }

    private fun removeCarryover(eventId: EventId): Boolean {
        if (carryover.remove(eventId) == null) return false
        cachedSnapshot = null
        return true
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
        events.entries.removeIf { entry -> entry.value.channelId == channelId }
        history.entries.removeIf { entry -> entry.value.channelId == channelId }
    }

}

private fun <T> Collection<T>.toImmutableList(): List<T> =
    Collections.unmodifiableList(ArrayList(this))
