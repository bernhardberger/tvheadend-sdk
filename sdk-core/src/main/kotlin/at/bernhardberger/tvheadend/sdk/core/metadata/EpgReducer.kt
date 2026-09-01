package at.bernhardberger.tvheadend.sdk.core.metadata

import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
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
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgUpdate
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import java.util.Collections
import kotlin.time.Instant

@ConsistentCopyVisibility
internal data class ReducedEpgEvent private constructor(
    internal val id: EventId,
    internal val channelId: ChannelId?,
    internal val start: Instant?,
    internal val stop: Instant?,
    internal val title: String?,
    internal val subtitle: String?,
    internal val summary: String?,
    internal val description: String?,
    internal val genre: String?,
    internal val categories: List<String>?,
    internal val keywords: List<String>?,
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
    override fun toString(): String = "ReducedEpgEvent(<redacted>)"

    internal fun merge(update: GatewayEpgUpdate): ReducedEpgEvent? {
        val candidate = ReducedEpgEvent(
            id = id,
            channelId = update.channelId ?: channelId,
            start = update.start ?: start,
            stop = update.stop ?: stop,
            title = update.title ?: title,
            subtitle = update.subtitle ?: subtitle,
            summary = update.summary ?: summary,
            description = update.description ?: description,
            genre = update.genre ?: genre,
            categories = update.categories?.toImmutableList() ?: categories,
            keywords = update.keywords?.toImmutableList() ?: keywords,
            seriesLinkUri = update.seriesLinkUri ?: seriesLinkUri,
            episodeUri = update.episodeUri ?: episodeUri,
            contentType = update.contentType ?: contentType,
            ageRating = update.ageRating ?: ageRating,
            ratingLabel = update.ratingLabel ?: ratingLabel,
            ratingIcon = update.ratingIcon ?: ratingIcon,
            ratingAuthority = update.ratingAuthority ?: ratingAuthority,
            ratingCountry = update.ratingCountry ?: ratingCountry,
            starRating = update.starRating ?: starRating,
            copyrightYear = update.copyrightYear ?: copyrightYear,
            firstAired = update.firstAired ?: firstAired,
            isNew = update.isNew ?: isNew,
            seasonNumber = update.seasonNumber ?: seasonNumber,
            seasonCount = update.seasonCount ?: seasonCount,
            episodeNumber = update.episodeNumber ?: episodeNumber,
            episodeCount = update.episodeCount ?: episodeCount,
            partNumber = update.partNumber ?: partNumber,
            partCount = update.partCount ?: partCount,
            episodeOnscreen = update.episodeOnscreen ?: episodeOnscreen,
            episodeId = update.episodeId ?: episodeId,
            seriesLinkId = update.seriesLinkId ?: seriesLinkId,
            image = update.image ?: image,
            dvrEntryId = update.dvrEntryId ?: dvrEntryId,
            nextEventId = update.nextEventId ?: nextEventId,
        )
        return candidate.takeUnless(ReducedEpgEvent::hasInvalidTiming)
    }

    internal fun replaceFromQuery(event: GatewayEpgQueryEvent): ReducedEpgEvent? =
        fromQuery(
            event = event,
            genre = genre,
            episodeId = episodeId,
            seriesLinkId = seriesLinkId,
        )

    internal fun toPublicOrNull(): EpgEvent? {
        val start = start ?: return null
        val stop = stop ?: return null
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
            seriesLinkUri = seriesLinkUri,
            episodeUri = episodeUri,
            contentType = contentType,
            rating = ratingOrNull(),
            copyrightYear = copyrightYear,
            firstAired = firstAired,
            isNew = isNew,
            episode = episodeOrNull(),
            image = image,
            dvrEntryId = dvrEntryId,
            nextEventId = nextEventId,
        )
    }

    private fun hasInvalidTiming(): Boolean = start != null && stop != null && stop < start

    internal fun shouldRetain(from: Instant, to: Instant): Boolean {
        val start = start
        val stop = stop
        if (start != null && stop != null) return stop >= from && start <= to
        if (start != null) return start <= to
        if (stop != null) return stop >= from
        return false
    }

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
        internal fun fromAdd(event: GatewayEpgEvent): ReducedEpgEvent? {
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

        internal fun fromUpdate(update: GatewayEpgUpdate): ReducedEpgEvent? = ReducedEpgEvent(
            id = update.id,
            channelId = null,
            start = null,
            stop = null,
            title = null,
            subtitle = null,
            summary = null,
            description = null,
            genre = null,
            categories = null,
            keywords = null,
            seriesLinkUri = null,
            episodeUri = null,
            contentType = null,
            ageRating = null,
            ratingLabel = null,
            ratingIcon = null,
            ratingAuthority = null,
            ratingCountry = null,
            starRating = null,
            copyrightYear = null,
            firstAired = null,
            isNew = null,
            seasonNumber = null,
            seasonCount = null,
            episodeNumber = null,
            episodeCount = null,
            partNumber = null,
            partCount = null,
            episodeOnscreen = null,
            episodeId = null,
            seriesLinkId = null,
            image = null,
            dvrEntryId = null,
            nextEventId = null,
        ).merge(update)

        internal fun fromQuery(
            event: GatewayEpgQueryEvent,
            genre: String? = null,
            episodeId: EpgEpisodeId? = null,
            seriesLinkId: EpgSeriesLinkId? = null,
        ): ReducedEpgEvent? {
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
                genre = genre,
                categories = event.categories?.toImmutableList(),
                keywords = event.keywords?.toImmutableList(),
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
                episodeId = episodeId,
                seriesLinkId = seriesLinkId,
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

internal class EpgReducer(
    private val maximumRetainedEvents: Int = EpgCoveragePolicy.create().maximumRetainedEvents,
) {
    private val events = linkedMapOf<EventId, ReducedEpgEvent>()
    private val channelIds = linkedSetOf<ChannelId>()
    private val queriedToByChannel = linkedMapOf<ChannelId, Instant>()
    // Mutation revisions exist only while an older in-flight query can still observe them.
    private val activeQueries = linkedSetOf<EpgQueryFence>()
    private val eventAuthorityRevisions = linkedMapOf<EventId, Long>()
    private val channelAuthorityRevisions = linkedMapOf<ChannelId, Long>()
    private var authorityRevision = 0L
    private var queriesInvalidAfterRevision: Long? = null

    init {
        require(maximumRetainedEvents > 0) { "EPG retained-event limit must be positive" }
    }

    internal fun clear() {
        events.clear()
        channelIds.clear()
        queriedToByChannel.clear()
        activeQueries.clear()
        eventAuthorityRevisions.clear()
        channelAuthorityRevisions.clear()
        authorityRevision = 0L
        queriesInvalidAfterRevision = null
    }

    internal fun accept(event: MetadataEvent) {
        when (event) {
            is MetadataEvent.ChannelAdded -> {
                recordChannelAuthority(event.channel.id)
                channelIds.add(event.channel.id)
            }
            is MetadataEvent.ChannelUpdated -> {
                channelIds.add(event.channel.id)
            }
            is MetadataEvent.ChannelDeleted -> {
                recordChannelAuthority(event.channelId)
                removeChannel(event.channelId)
            }
            is MetadataEvent.EventAdded -> {
                recordEventAuthority(event.event.id)
                acceptAdd(event.event)
            }
            is MetadataEvent.EventUpdated -> {
                recordEventAuthority(event.event.id)
                acceptUpdate(event.event)
            }
            is MetadataEvent.EventDeleted -> {
                recordEventAuthority(event.eventId)
                events.remove(event.eventId)
            }
            is MetadataEvent.TagAdded,
            is MetadataEvent.TagUpdated,
            is MetadataEvent.TagDeleted,
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

    internal fun reconcileChannels(validChannelIds: Collection<ChannelId>) {
        val valid = validChannelIds.toSet()
        channelIds.clear()
        channelIds.addAll(validChannelIds)
        events.entries.removeIf { entry ->
            entry.value.toPublicOrNull() == null ||
                entry.value.channelId?.let { it !in valid } == true
        }
        queriedToByChannel.keys.retainAll(valid)
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
            queriedToByChannel[channelId] = queriedTo
        }
    }

    internal fun acceptSuccessfulQuery(
        query: EpgQueryFence,
        queriedTo: Instant,
        queriedEvents: List<GatewayEpgQueryEvent>,
    ): Boolean {
        if (!activeQueries.remove(query)) return false
        return try {
            if (
                query.channelId !in channelIds ||
                queriesInvalidAfterRevision
                    ?.let { revision -> revision > query.authorityRevision } == true ||
                channelAuthorityRevisions[query.channelId]
                    ?.let { revision -> revision > query.authorityRevision } == true
            ) {
                false
            } else {
                val stagedEvents = linkedMapOf<EventId, ReducedEpgEvent>()
                var stagedNewEventCount = 0
                for (event in queriedEvents) {
                    if (event.channelId != null && event.channelId != query.channelId) continue
                    if (
                        eventAuthorityRevisions[event.id]
                            ?.let { revision -> revision > query.authorityRevision } == true
                    ) {
                        continue
                    }
                    val candidate = (stagedEvents[event.id] ?: events[event.id])
                        ?.replaceFromQuery(event)
                        ?: ReducedEpgEvent.fromQuery(event)
                    if (candidate != null) {
                        if (event.id !in events && event.id !in stagedEvents) {
                            if (events.size + stagedNewEventCount >= maximumRetainedEvents) {
                                return false
                            }
                            stagedNewEventCount += 1
                        }
                        stagedEvents[event.id] = candidate
                    }
                }
                stagedEvents.forEach { (eventId, event) -> events[eventId] = event }
                recordSuccessfulQuery(query.channelId, queriedTo)
                true
            }
        } finally {
            pruneQueryAuthority()
        }
    }

    internal fun retainOverlapping(from: Instant, to: Instant) {
        require(to >= from) { "EPG retention stop must not precede start" }
        events.entries.removeIf { entry -> !entry.value.shouldRetain(from, to) }
    }

    internal fun snapshot(): EpgSnapshot {
        val visibleEvents = events.values.mapNotNull(ReducedEpgEvent::toPublicOrNull)
            .filter { event -> event.channelId == null || event.channelId in channelIds }
        val coverages = channelIds.map { channelId ->
            coverage(channelId, visibleEvents)
        }
        return EpgSnapshot.create(visibleEvents, coverages)
    }

    private fun acceptAdd(event: GatewayEpgEvent) {
        ReducedEpgEvent.fromAdd(event)?.let { candidate ->
            if (event.id !in events && events.size >= maximumRetainedEvents) {
                invalidateActiveQueriesAfterCapacityDrop()
                return
            }
            events[event.id] = candidate
        }
    }

    private fun acceptUpdate(update: GatewayEpgUpdate) {
        val current = events[update.id]
        val candidate = if (current == null) {
            ReducedEpgEvent.fromUpdate(update)
        } else {
            current.merge(update)
        } ?: return
        if (current == null && candidate.start == null && candidate.stop == null) return
        if (current == null && events.size >= maximumRetainedEvents) {
            invalidateActiveQueriesAfterCapacityDrop()
            return
        }
        events[update.id] = candidate
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
        queriedToByChannel.remove(channelId)
        events.entries.removeIf { entry -> entry.value.channelId == channelId }
    }

    private fun coverage(channelId: ChannelId, visibleEvents: List<EpgEvent>): EpgCoverage {
        val channelEvents = visibleEvents.filter { event -> event.channelId == channelId }
        val queriedTo = queriedToByChannel[channelId]
        if (channelEvents.isEmpty()) {
            return EpgCoverage.empty(channelId, queriedTo)
        }
        return EpgCoverage.create(
            channelId = channelId,
            coveredFrom = channelEvents.minOf(EpgEvent::start),
            coveredTo = channelEvents.maxOf(EpgEvent::stop),
            queriedTo = queriedTo,
        )
    }
}

private fun <T> Collection<T>.toImmutableList(): List<T> =
    Collections.unmodifiableList(ArrayList(this))
