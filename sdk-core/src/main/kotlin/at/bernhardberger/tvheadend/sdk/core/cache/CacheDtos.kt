@file:OptIn(ExperimentalSerializationApi::class)

package at.bernhardberger.tvheadend.sdk.core.cache

import at.bernhardberger.tvheadend.sdk.core.Channel
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.ChannelService
import at.bernhardberger.tvheadend.sdk.core.ChannelTag
import at.bernhardberger.tvheadend.sdk.core.ChannelTagId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.EpgCoverage
import at.bernhardberger.tvheadend.sdk.core.EpgEpisode
import at.bernhardberger.tvheadend.sdk.core.EpgEpisodeId
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EpgRating
import at.bernhardberger.tvheadend.sdk.core.EpgSeriesLinkId
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.EventId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.time.Instant

/** Schema version stamped into every persisted [CatalogEnvelopeDto]. */
internal const val CATALOG_SCHEMA_VERSION: Int = 1

/** Schema version stamped into every persisted [EpgEnvelopeDto]. */
internal const val EPG_SCHEMA_VERSION: Int = 1

@Serializable
internal data class CatalogEnvelopeDto(
    @ProtoNumber(1) val schemaVersion: Int,
    @ProtoNumber(2) val storedAtEpochMillis: Long,
    @ProtoNumber(3) val payload: ChannelCatalogDto,
)

@Serializable
internal data class EpgEnvelopeDto(
    @ProtoNumber(1) val schemaVersion: Int,
    @ProtoNumber(2) val storedAtEpochMillis: Long,
    @ProtoNumber(3) val payload: EpgSnapshotDto,
)

@Serializable
internal data class ChannelCatalogDto(
    @ProtoNumber(1) val channels: List<ChannelDto>,
    @ProtoNumber(2) val tags: List<ChannelTagDto>,
)

@Serializable
internal data class ChannelDto(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val name: String? = null,
    @ProtoNumber(3) val uuid: String? = null,
    @ProtoNumber(4) val number: Long? = null,
    @ProtoNumber(5) val numberMinor: Long? = null,
    @ProtoNumber(6) val icon: String? = null,
    @ProtoNumber(7) val currentEventId: Long? = null,
    @ProtoNumber(8) val nextEventId: Long? = null,
    @ProtoNumber(9) val services: List<ChannelServiceDto>? = null,
    @ProtoNumber(10) val tagIds: List<Long>? = null,
)

@Serializable
internal data class ChannelServiceDto(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val type: String,
    @ProtoNumber(3) val content: Long,
    @ProtoNumber(4) val conditionalAccessId: Long? = null,
    @ProtoNumber(5) val conditionalAccessName: String? = null,
    @ProtoNumber(6) val providerName: String? = null,
)

@Serializable
internal data class ChannelTagDto(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val name: String? = null,
    @ProtoNumber(3) val uuid: String? = null,
    @ProtoNumber(4) val index: Long? = null,
    @ProtoNumber(5) val icon: String? = null,
    @ProtoNumber(6) val titledIcon: Boolean? = null,
    @ProtoNumber(7) val channelIds: List<Long>? = null,
)

@Serializable
internal data class EpgSnapshotDto(
    @ProtoNumber(1) val events: List<EpgEventDto>,
    @ProtoNumber(2) val coverages: List<EpgCoverageDto>,
)

@Serializable
internal data class EpgEventDto(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val channelId: Long? = null,
    @ProtoNumber(3) val start: Long,
    @ProtoNumber(4) val stop: Long,
    @ProtoNumber(5) val title: String? = null,
    @ProtoNumber(6) val subtitle: String? = null,
    @ProtoNumber(7) val summary: String? = null,
    @ProtoNumber(8) val description: String? = null,
    @ProtoNumber(9) val genre: String? = null,
    @ProtoNumber(10) val categories: List<String>? = null,
    @ProtoNumber(11) val keywords: List<String>? = null,
    @ProtoNumber(12) val seriesLinkUri: String? = null,
    @ProtoNumber(13) val episodeUri: String? = null,
    @ProtoNumber(14) val contentType: Long? = null,
    @ProtoNumber(15) val rating: EpgRatingDto? = null,
    @ProtoNumber(16) val copyrightYear: Long? = null,
    @ProtoNumber(17) val firstAired: Long? = null,
    @ProtoNumber(18) val isNew: Boolean? = null,
    @ProtoNumber(19) val episode: EpgEpisodeDto? = null,
    @ProtoNumber(20) val image: String? = null,
    @ProtoNumber(21) val dvrEntryId: Long? = null,
    @ProtoNumber(22) val nextEventId: Long? = null,
)

@Serializable
internal data class EpgRatingDto(
    @ProtoNumber(1) val age: Long? = null,
    @ProtoNumber(2) val label: String? = null,
    @ProtoNumber(3) val icon: String? = null,
    @ProtoNumber(4) val authority: String? = null,
    @ProtoNumber(5) val country: String? = null,
    @ProtoNumber(6) val stars: Long? = null,
)

@Serializable
internal data class EpgEpisodeDto(
    @ProtoNumber(1) val id: Long? = null,
    @ProtoNumber(2) val seriesLinkId: Long? = null,
    @ProtoNumber(3) val seasonNumber: Long? = null,
    @ProtoNumber(4) val seasonCount: Long? = null,
    @ProtoNumber(5) val episodeNumber: Long? = null,
    @ProtoNumber(6) val episodeCount: Long? = null,
    @ProtoNumber(7) val partNumber: Long? = null,
    @ProtoNumber(8) val partCount: Long? = null,
    @ProtoNumber(9) val onscreen: String? = null,
)

@Serializable
internal data class EpgCoverageDto(
    @ProtoNumber(1) val channelId: Long,
    @ProtoNumber(2) val coveredFrom: Long,
    @ProtoNumber(3) val coveredTo: Long,
    @ProtoNumber(4) val queriedTo: Long? = null,
)

internal fun ChannelService.toDto(): ChannelServiceDto = ChannelServiceDto(
    name = name,
    type = type,
    content = content,
    conditionalAccessId = conditionalAccessId,
    conditionalAccessName = conditionalAccessName,
    providerName = providerName,
)

internal fun ChannelServiceDto.toModel(): ChannelService = ChannelService(
    name = name,
    type = type,
    content = content,
    conditionalAccessId = conditionalAccessId,
    conditionalAccessName = conditionalAccessName,
    providerName = providerName,
)

internal fun Channel.toDto(): ChannelDto = ChannelDto(
    id = id.value,
    name = name,
    uuid = uuid,
    number = number,
    numberMinor = numberMinor,
    icon = icon,
    currentEventId = currentEventId?.value,
    nextEventId = nextEventId?.value,
    services = services?.map { service -> service.toDto() },
    tagIds = tagIds?.map { tagId -> tagId.value },
)

internal fun ChannelDto.toModel(): Channel = Channel.create(
    id = ChannelId(id),
    name = name,
    uuid = uuid,
    number = number,
    numberMinor = numberMinor,
    icon = icon,
    currentEventId = currentEventId?.let(::EventId),
    nextEventId = nextEventId?.let(::EventId),
    services = services?.map { service -> service.toModel() },
    tagIds = tagIds?.map { tagId -> ChannelTagId(tagId) },
)

internal fun ChannelTag.toDto(): ChannelTagDto = ChannelTagDto(
    id = id.value,
    name = name,
    uuid = uuid,
    index = index,
    icon = icon,
    titledIcon = titledIcon,
    channelIds = channelIds?.map { channelId -> channelId.value },
)

internal fun ChannelTagDto.toModel(): ChannelTag = ChannelTag.create(
    id = ChannelTagId(id),
    name = name,
    uuid = uuid,
    index = index,
    icon = icon,
    titledIcon = titledIcon,
    channelIds = channelIds?.map { channelId -> ChannelId(channelId) },
)

internal fun ChannelCatalog.toDto(): ChannelCatalogDto = ChannelCatalogDto(
    channels = channels.map { channel -> channel.toDto() },
    tags = tags.map { tag -> tag.toDto() },
)

internal fun ChannelCatalogDto.toModel(): ChannelCatalog = ChannelCatalog.create(
    channels = channels.map { channel -> channel.toModel() },
    tags = tags.map { tag -> tag.toModel() },
)

internal fun EpgRating.toDto(): EpgRatingDto = EpgRatingDto(
    age = age,
    label = label,
    icon = icon,
    authority = authority,
    country = country,
    stars = stars,
)

internal fun EpgRatingDto.toModel(): EpgRating = EpgRating(
    age = age,
    label = label,
    icon = icon,
    authority = authority,
    country = country,
    stars = stars,
)

internal fun EpgEpisode.toDto(): EpgEpisodeDto = EpgEpisodeDto(
    id = id?.value,
    seriesLinkId = seriesLinkId?.value,
    seasonNumber = seasonNumber,
    seasonCount = seasonCount,
    episodeNumber = episodeNumber,
    episodeCount = episodeCount,
    partNumber = partNumber,
    partCount = partCount,
    onscreen = onscreen,
)

internal fun EpgEpisodeDto.toModel(): EpgEpisode = EpgEpisode(
    id = id?.let(::EpgEpisodeId),
    seriesLinkId = seriesLinkId?.let(::EpgSeriesLinkId),
    seasonNumber = seasonNumber,
    seasonCount = seasonCount,
    episodeNumber = episodeNumber,
    episodeCount = episodeCount,
    partNumber = partNumber,
    partCount = partCount,
    onscreen = onscreen,
)

internal fun EpgEvent.toDto(): EpgEventDto = EpgEventDto(
    id = id.value,
    channelId = channelId?.value,
    start = start.toEpochMilliseconds(),
    stop = stop.toEpochMilliseconds(),
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
    rating = rating?.toDto(),
    copyrightYear = copyrightYear,
    firstAired = firstAired?.toEpochMilliseconds(),
    isNew = isNew,
    episode = episode?.toDto(),
    image = image,
    dvrEntryId = dvrEntryId?.value,
    nextEventId = nextEventId?.value,
)

internal fun EpgEventDto.toModel(): EpgEvent = EpgEvent.create(
    id = EventId(id),
    channelId = channelId?.let(::ChannelId),
    start = Instant.fromEpochMilliseconds(start),
    stop = Instant.fromEpochMilliseconds(stop),
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
    rating = rating?.toModel(),
    copyrightYear = copyrightYear,
    firstAired = firstAired?.let(Instant::fromEpochMilliseconds),
    isNew = isNew,
    episode = episode?.toModel(),
    image = image,
    dvrEntryId = dvrEntryId?.let(::DvrEntryId),
    nextEventId = nextEventId?.let(::EventId),
)

/**
 * Maps [EpgCoverage] to [EpgCoverageDto], preserving [EpgCoverage.empty]'s inverted interval.
 */
internal fun EpgCoverage.toDto(): EpgCoverageDto = EpgCoverageDto(
    channelId = channelId.value,
    coveredFrom = coveredFrom.toEpochMilliseconds(),
    coveredTo = coveredTo.toEpochMilliseconds(),
    queriedTo = queriedTo?.toEpochMilliseconds(),
)

/**
 * Maps [EpgCoverageDto] back to [EpgCoverage].
 *
 * An inverted interval (`coveredFrom > coveredTo`) round-trips through [EpgCoverage.empty]
 * because [EpgCoverage.create] rejects it.
 */
internal fun EpgCoverageDto.toModel(): EpgCoverage {
    val channel = ChannelId(channelId)
    val queried = queriedTo?.let(Instant::fromEpochMilliseconds)
    return if (coveredFrom > coveredTo) {
        EpgCoverage.empty(channel, queried)
    } else {
        EpgCoverage.create(
            channelId = channel,
            coveredFrom = Instant.fromEpochMilliseconds(coveredFrom),
            coveredTo = Instant.fromEpochMilliseconds(coveredTo),
            queriedTo = queried,
        )
    }
}

internal fun EpgSnapshot.toDto(): EpgSnapshotDto = EpgSnapshotDto(
    events = events.map { event -> event.toDto() },
    coverages = coverages.map { coverage -> coverage.toDto() },
)

internal fun EpgSnapshotDto.toModel(): EpgSnapshot = EpgSnapshot.create(
    events = events.map { event -> event.toModel() },
    coverages = coverages.map { coverage -> coverage.toModel() },
)
