@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.metadata

import at.bernhardberger.tvheadend.sdk.core.ContentCategory
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrRecordingStream
import at.bernhardberger.tvheadend.sdk.core.DvrSubscriptionError
import at.bernhardberger.tvheadend.sdk.core.gateway.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrConfigId
import at.bernhardberger.tvheadend.sdk.core.gateway.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayAutorecRule
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrEntry
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrRecordingFile
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrStats
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayTimerecRule
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.TimerecRuleId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal class DvrReducerTest {
    private val generation = GatewayGeneration()

    @Test
    fun `DVR episode grouping and ownership clear on full replacement and accept explicit updates`() {
        val reducer = DvrReducer()
        val id = DvrEntryId(1)
        reducer.accept(MetadataEvent.DvrEntryAdded(
            generation, GatewayDvrEntry(
                id, seasonCount = 8, episodeOnscreen = "S2 E3", owner = "test-owner", creator = "test-creator",
            ),
        ))
        val retained = reducer.snapshot().entries.single()
        assertEquals(8L, retained.episode?.seasonCount)
        assertEquals("S2 E3", retained.episode?.onscreen)
        assertEquals("test-owner", retained.owner)
        assertEquals("test-creator", retained.creator)
        assertEquals("DvrEntry(<redacted>)", retained.toString())
        reducer.accept(MetadataEvent.DvrEntryUpdated(generation, GatewayDvrEntry(id)))
        assertEquals(null, reducer.snapshot().entries.single().episode)
        assertEquals(null, reducer.snapshot().entries.single().owner)
        assertEquals(null, reducer.snapshot().entries.single().creator)
        reducer.accept(MetadataEvent.DvrEntryUpdated(
            generation, GatewayDvrEntry(id, seasonCount = 0, episodeOnscreen = "", owner = "", creator = "updated"),
        ))
        val updated = reducer.snapshot().entries.single()
        assertEquals(0L, updated.episode?.seasonCount)
        assertEquals("", updated.episode?.onscreen)
        assertEquals("", updated.owner)
        assertEquals("updated", updated.creator)
        assertEquals(8L, retained.episode?.seasonCount)
        // Either newly mapped field alone must produce an episode grouping.
        reducer.accept(MetadataEvent.DvrEntryAdded(generation, GatewayDvrEntry(DvrEntryId(2), seasonCount = 3)))
        reducer.accept(MetadataEvent.DvrEntryUpdated(
            generation, GatewayDvrEntry(DvrEntryId(3), episodeOnscreen = "Special"),
        ))
        assertEquals(3L, reducer.snapshot().entries[1].episode?.seasonCount)
        assertEquals("Special", reducer.snapshot().entries[2].episode?.onscreen)
    }

    @Test
    fun `rule ownership and timerec removal follow full replacement semantics`() {
        val reducer = DvrReducer()
        val autoId = AutorecRuleId("auto")
        val timeId = TimerecRuleId("time")
        reducer.accept(MetadataEvent.AutorecRuleAdded(
            generation, GatewayAutorecRule(autoId, owner = "test-owner", creator = "test-creator"),
        ))
        reducer.accept(MetadataEvent.TimerecRuleAdded(
            generation, GatewayTimerecRule(
                timeId, removalDays = 0xffff_ffffL, owner = "test-owner", creator = "test-creator",
            ),
        ))
        val retained = reducer.snapshot()
        assertEquals("test-owner", retained.autorecRules.single().owner)
        assertEquals("test-creator", retained.autorecRules.single().creator)
        assertEquals("test-owner", retained.timerecRules.single().owner)
        assertEquals("test-creator", retained.timerecRules.single().creator)
        assertEquals(0xffff_ffffL, retained.timerecRules.single().removalDays)
        assertEquals("AutorecRule(<redacted>)", retained.autorecRules.single().toString())
        assertEquals("TimerecRule(<redacted>)", retained.timerecRules.single().toString())
        reducer.accept(MetadataEvent.AutorecRuleUpdated(
            generation, GatewayAutorecRule(autoId, owner = "updated-owner", creator = "updated-creator"),
        ))
        reducer.accept(MetadataEvent.TimerecRuleUpdated(
            generation, GatewayTimerecRule(timeId, removalDays = 0, owner = "updated-owner", creator = "updated-creator"),
        ))
        assertEquals("updated-owner", reducer.snapshot().autorecRules.single().owner)
        assertEquals("updated-creator", reducer.snapshot().autorecRules.single().creator)
        assertEquals("updated-owner", reducer.snapshot().timerecRules.single().owner)
        assertEquals("updated-creator", reducer.snapshot().timerecRules.single().creator)
        assertEquals(0L, reducer.snapshot().timerecRules.single().removalDays)
        reducer.accept(MetadataEvent.AutorecRuleUpdated(generation, GatewayAutorecRule(autoId)))
        reducer.accept(MetadataEvent.TimerecRuleUpdated(generation, GatewayTimerecRule(timeId)))
        assertEquals(null, reducer.snapshot().autorecRules.single().owner)
        assertEquals(null, reducer.snapshot().autorecRules.single().creator)
        assertEquals(null, reducer.snapshot().timerecRules.single().owner)
        assertEquals(null, reducer.snapshot().timerecRules.single().creator)
        assertEquals(null, reducer.snapshot().timerecRules.single().removalDays)
        assertEquals(0xffff_ffffL, retained.timerecRules.single().removalDays)
    }

    @Test
    fun `recording streams survive stats but full replacement clears omitted files`() {
        val reducer = DvrReducer()
        val stream = DvrRecordingStream(width = 1920, height = 1080)
        val input = mutableListOf(stream)
        val file = GatewayDvrRecordingFile(1, null, null, null, null, input)
        input.clear()
        assertEquals(listOf(stream), file.streams)
        assertThrows(UnsupportedOperationException::class.java) {
            (file.streams as MutableList<DvrRecordingStream>).clear()
        }
        reducer.accept(MetadataEvent.DvrEntryAdded(
            generation, GatewayDvrEntry(id = DvrEntryId(1), files = listOf(file), state = DvrEntryState.RECORDING),
        ))
        val retained = reducer.snapshot()
        reducer.accept(MetadataEvent.DvrEntryStatsUpdated(
            generation, GatewayDvrStats(DvrEntryId(1), null, DvrEntryState.COMPLETED),
        ))
        assertEquals(listOf(stream), reducer.snapshot().entries.single().files?.single()?.streams)
        reducer.accept(MetadataEvent.DvrEntryUpdated(generation, GatewayDvrEntry(DvrEntryId(1))))
        assertEquals(null, reducer.snapshot().entries.single().files)
        assertThrows(UnsupportedOperationException::class.java) {
            (retained.entries.single().files?.single()?.streams as MutableList<DvrRecordingStream>).clear()
        }
        for (replacement in listOf(null, emptyList(), listOf(DvrRecordingStream()))) {
            reducer.accept(MetadataEvent.DvrEntryUpdated(
                generation,
                GatewayDvrEntry(
                    id = DvrEntryId(1),
                    files = listOf(GatewayDvrRecordingFile(1, null, null, null, null, replacement)),
                ),
            ))
            assertEquals(replacement, reducer.snapshot().entries.single().files?.single()?.streams)
        }
        assertEquals(listOf(stream), retained.entries.single().files?.single()?.streams)
        assertEquals(DvrEntryState.RECORDING, retained.entries.single().state)
    }

    @Test
    fun `snapshot reconstruction is shared until accepted DVR mutation or clear`() {
        val reducer = DvrReducer()
        reducer.accept(MetadataEvent.DvrEntryAdded(generation, entry(id = 1, title = "old")))
        val retained = reducer.snapshot()
        repeat(1_000) { index ->
            reducer.accept(MetadataEvent.EventDeleted(generation, EventId(index.toLong() + 1)))
            assertSame(retained, reducer.snapshot())
        }
        assertFalse(reducer.accept(MetadataEvent.DvrEntryUpdated(
            generation, entry(id = 1, start = 20, stop = 10),
        )))
        assertSame(retained, reducer.snapshot())
        reducer.accept(MetadataEvent.DvrEntryAdded(generation, entry(id = 1, title = "new")))
        assertNotSame(retained, reducer.snapshot())
        assertEquals("old", retained.entries.single().title)
        assertEquals("new", reducer.snapshot().entries.single().title)
        reducer.clear()
        assertEquals(emptyList<Any>(), reducer.snapshot().entries)
    }

    @Test
    fun `dvr content type decodes as the major category Tvheadend sends for recordings`() {
        val reducer = DvrReducer()
        reducer.accept(MetadataEvent.DvrEntryAdded(generation, entry(id = 1, contentType = 6)))
        reducer.accept(MetadataEvent.DvrEntryAdded(generation, entry(id = 2, contentType = 0)))

        val entries = reducer.snapshot().entries.associateBy { it.id.value }
        assertEquals(ContentCategory.MUSIC_BALLET_DANCE, entries.getValue(1).contentGenre?.category)
        assertNull(entries.getValue(1).contentGenre?.subgenre)
        assertNull(entries.getValue(2).contentGenre)
    }

    @Test
    fun `dvr replacement clears browse scalars while stats patch touches only stats`() {
        val reducer = DvrReducer()
        reducer.accept(
            MetadataEvent.DvrEntryAdded(
                generation,
                entry(
                    id = 1,
                    enabled = true,
                    contentType = 8,
                    startExtraMinutes = 2,
                    stopExtraMinutes = 3,
                    subscriptionError = DvrSubscriptionError.SCRAMBLED,
                    title = "old",
                    channelName = "private-channel",
                ),
            ),
        )
        reducer.accept(
            MetadataEvent.DvrEntryStatsUpdated(
                generation,
                GatewayDvrStats(
                    id = DvrEntryId(1),
                    uuid = null,
                    state = DvrEntryState.COMPLETED,
                    streamErrors = 1,
                    dataErrors = 2,
                    dataSizeBytes = 3,
                ),
            ),
        )

        var snapshot = reducer.snapshot().entries.single()
        assertEquals("old", snapshot.title)
        assertEquals(true, snapshot.enabled)
        assertEquals(ContentCategory.SOCIAL_POLITICAL_ECONOMICS, snapshot.contentGenre?.category)
        assertEquals(120.seconds, snapshot.startExtraMinutes)
        assertEquals(180.seconds, snapshot.stopExtraMinutes)
        assertEquals(null, snapshot.subscriptionError)
        assertEquals("private-channel", snapshot.channelName)

        reducer.accept(MetadataEvent.DvrEntryAdded(generation, entry(id = 1, title = "replaced")))

        snapshot = reducer.snapshot().entries.single()
        assertEquals("replaced", snapshot.title)
        assertEquals(null, snapshot.channelName)
        assertEquals(null, snapshot.enabled)
        assertEquals(null, snapshot.contentGenre)
        assertEquals(null, snapshot.startExtraMinutes)
        assertEquals(null, snapshot.stopExtraMinutes)
        assertEquals(null, snapshot.subscriptionError)
    }

    @Test
    fun `unknown updates remain visible drafts and invalid timing keeps prior state`() {
        val reducer = DvrReducer()
        reducer.accept(
            MetadataEvent.DvrEntryUpdated(
                generation,
                entry(id = 10, title = "draft"),
            ),
        )
        assertEquals("draft", reducer.snapshot().entries.single().title)

        reducer.accept(
            MetadataEvent.DvrEntryAdded(
                generation,
                entry(id = 11, start = 10, stop = 20, title = "valid"),
            ),
        )
        reducer.accept(
            MetadataEvent.DvrEntryUpdated(
                generation,
                entry(id = 11, start = 30, stop = 10, title = "invalid"),
            ),
        )
        val retained = reducer.snapshot().entries.single { it.id == DvrEntryId(11) }
        assertEquals("valid", retained.title)
        assertEquals(instant(10), retained.start)
        assertEquals(instant(20), retained.stop)
    }

    @Test
    fun `deletes remove unknown and known entries without scanning other families`() {
        val reducer = DvrReducer()
        reducer.accept(MetadataEvent.DvrEntryAdded(generation, entry(id = 1, title = "keep")))
        reducer.accept(MetadataEvent.DvrEntryAdded(generation, entry(id = 2, title = "drop")))
        reducer.accept(
            MetadataEvent.AutorecRuleAdded(generation, autorec("auto", title = "auto-title")),
        )
        reducer.accept(
            MetadataEvent.TimerecRuleAdded(generation, timerec("time", title = "time-title")),
        )

        reducer.accept(MetadataEvent.DvrEntryDeleted(generation, DvrEntryId(99)))
        reducer.accept(MetadataEvent.DvrEntryDeleted(generation, DvrEntryId(2)))

        val snapshot = reducer.snapshot()
        assertEquals(listOf(1L), snapshot.entries.map { it.id.value })
        assertEquals(listOf("auto"), snapshot.autorecRules.map { it.id.value })
        assertEquals(listOf("time"), snapshot.timerecRules.map { it.id.value })
    }

    @Test
    fun `full rule updates clear optional fields and retain all-channel timerec rules`() {
        val reducer = DvrReducer()
        reducer.accept(
            MetadataEvent.AutorecRuleAdded(
                generation,
                GatewayAutorecRule(
                    id = AutorecRuleId("auto"),
                    enabled = true,
                    maxDuration = 60.seconds,
                    minDuration = 0.seconds,
                    comment = "old-comment",
                    title = "old-title",
                    fullText = true,
                    mergeText = true,
                    name = "old-name",
                    directory = "old-directory",
                    owner = "old-owner",
                    creator = "old-creator",
                    channelId = ChannelId(1),
                    seriesLinkUri = "old-series",
                    configId = DvrConfigId("old-config"),
                ),
            ),
        )
        reducer.accept(
            MetadataEvent.TimerecRuleAdded(
                generation,
                GatewayTimerecRule(
                    id = TimerecRuleId("time"),
                    enabled = true,
                    name = "old-name",
                    title = "old-title",
                    channelId = ChannelId(1),
                    startMinutesSinceMidnight = 60,
                    stopMinutesSinceMidnight = 120,
                    directory = "old-directory",
                    owner = "old-owner",
                    creator = "old-creator",
                    configId = DvrConfigId("old-config"),
                    comment = "old-comment",
                ),
            ),
        )
        reducer.accept(
            MetadataEvent.AutorecRuleUpdated(
                generation,
                GatewayAutorecRule(
                    id = AutorecRuleId("auto"),
                    enabled = false,
                    maxDuration = 120.seconds,
                    minDuration = 0.seconds,
                    retentionDays = 1,
                    removalDays = 2,
                    daysOfWeekMask = 127,
                    approximateStartMinutesSinceMidnight = -1,
                    startMinutesSinceMidnight = -1,
                    startWindowEndMinutesSinceMidnight = -1,
                    priority = 3,
                    startExtraMinutes = 4,
                    stopExtraMinutes = 5,
                    duplicateDetection = 6,
                    maximumRecordingCount = 7,
                    broadcastType = 8,
                ),
            ),
        )
        reducer.accept(
            MetadataEvent.TimerecRuleUpdated(
                generation,
                GatewayTimerecRule(
                    id = TimerecRuleId("time"),
                    enabled = false,
                    daysOfWeekMask = 127,
                    priority = 1,
                    retentionDays = 2,
                ),
            ),
        )

        val snapshot = reducer.snapshot()
        val autorec = snapshot.autorecRules.single()
        assertEquals(false, autorec.enabled)
        assertEquals(120.seconds, autorec.maxDuration)
        assertEquals(null, autorec.comment)
        assertEquals(null, autorec.title)
        assertEquals(null, autorec.fullText)
        assertEquals(null, autorec.mergeText)
        assertEquals(null, autorec.name)
        assertEquals(null, autorec.directory)
        assertEquals(null, autorec.owner)
        assertEquals(null, autorec.creator)
        assertEquals(null, autorec.channelId)
        assertEquals(null, autorec.seriesLinkUri)
        assertEquals(null, autorec.configId)

        val timerec = snapshot.timerecRules.single()
        assertEquals(false, timerec.enabled)
        assertEquals(null, timerec.name)
        assertEquals(null, timerec.title)
        assertEquals(null, timerec.channelId)
        assertEquals(null, timerec.startMinutesSinceMidnight)
        assertEquals(null, timerec.stopMinutesSinceMidnight)
        assertEquals(null, timerec.directory)
        assertEquals(null, timerec.owner)
        assertEquals(null, timerec.creator)
        assertEquals(null, timerec.configId)
        assertEquals(null, timerec.comment)
    }

    @Test
    fun `snapshots retain insertion order are immutable and redacted`() {
        val reducer = DvrReducer()
        val files = mutableListOf(
            GatewayDvrRecordingFile(1, "/private/file.ts", instant(1), instant(2), 3),
        )
        reducer.accept(MetadataEvent.DvrEntryAdded(generation, entry(id = 2, title = "second")))
        reducer.accept(
            MetadataEvent.DvrEntryAdded(
                generation,
                entry(id = 1, title = "private-title", files = files),
            ),
        )
        reducer.accept(MetadataEvent.DvrEntryDeleted(generation, DvrEntryId(2)))
        reducer.accept(MetadataEvent.DvrEntryAdded(generation, entry(id = 2, title = "readded")))
        files.clear()

        val snapshot = reducer.snapshot()
        assertEquals(listOf(1L, 2L), snapshot.entries.map { it.id.value })
        assertEquals("/private/file.ts", snapshot.entries.first().files?.single()?.path)
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.entries as MutableList<*>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.entries.first().files as MutableList<*>).clear()
        }
        assertFalse(snapshot.toString().contains("private"), "DVR snapshot rendering exposed metadata")
        assertFalse(snapshot.toString().contains("/private"), "DVR snapshot rendering exposed a path")
    }

    @Test
    fun `full and stats DVR messages clear omitted errors within their field sets`() {
        val reducer = DvrReducer()
        reducer.accept(
            MetadataEvent.DvrEntryAdded(
                generation,
                GatewayDvrEntry(
                    id = DvrEntryId(1),
                    state = DvrEntryState.RECORDING,
                    failure = GatewayDvrFailure.PRESENT,
                    subscriptionError = DvrSubscriptionError.SCRAMBLED,
                ),
            ),
        )
        assertEquals(DvrEntryState.RECORDING_ERROR, reducer.snapshot().entries.single().state)

        reducer.accept(
            MetadataEvent.DvrEntryUpdated(
                generation,
                GatewayDvrEntry(
                    id = DvrEntryId(1),
                    state = DvrEntryState.RECORDING,
                ),
            ),
        )
        var entry = reducer.snapshot().entries.single()
        assertEquals(DvrEntryState.RECORDING, entry.state)
        assertEquals(null, entry.subscriptionError)

        reducer.accept(
            MetadataEvent.DvrEntryStatsUpdated(
                generation,
                GatewayDvrStats(
                    id = DvrEntryId(1),
                    uuid = null,
                    state = DvrEntryState.RECORDING,
                    failure = GatewayDvrFailure.PRESENT,
                    subscriptionError = DvrSubscriptionError.INVALID_TARGET,
                ),
            ),
        )
        entry = reducer.snapshot().entries.single()
        assertEquals(DvrEntryState.RECORDING_ERROR, entry.state)
        assertEquals(DvrSubscriptionError.INVALID_TARGET, entry.subscriptionError)

        reducer.accept(
            MetadataEvent.DvrEntryStatsUpdated(
                generation,
                GatewayDvrStats(DvrEntryId(1), null, DvrEntryState.COMPLETED),
            ),
        )
        entry = reducer.snapshot().entries.single()
        assertEquals(DvrEntryState.COMPLETED, entry.state)
        assertEquals(null, entry.subscriptionError)

        reducer.accept(
            MetadataEvent.DvrEntryUpdated(
                generation,
                GatewayDvrEntry(id = DvrEntryId(1), state = DvrEntryState.COMPLETED),
            ),
        )
        entry = reducer.snapshot().entries.single()
        assertEquals(DvrEntryState.COMPLETED, entry.state)
        assertEquals(null, entry.subscriptionError)
    }

    @Test
    fun `empty collections replace files and channel absence does not evict recordings`() {
        val reducer = DvrReducer()
        reducer.accept(
            MetadataEvent.DvrEntryAdded(
                generation,
                entry(
                    id = 1,
                    channelId = 9,
                    files = listOf(GatewayDvrRecordingFile(1, null, null, null, null)),
                ),
            ),
        )
        reducer.accept(
            MetadataEvent.DvrEntryUpdated(
                generation,
                entry(id = 1, files = emptyList()),
            ),
        )

        val entry = reducer.snapshot().entries.single()
        assertEquals(null, entry.channelId)
        assertEquals(emptyList<Any>(), entry.files)
    }

    @Test
    fun `active recording keeps saved play position across stats but full omission clears it`() {
        val reducer = DvrReducer()
        reducer.accept(
            MetadataEvent.DvrEntryAdded(
                generation,
                GatewayDvrEntry(
                    id = DvrEntryId(1),
                    playPosition = 90.seconds,
                    state = DvrEntryState.RECORDING,
                ),
            ),
        )
        assertEquals(90.seconds, reducer.snapshot().entries.single().playPosition)

        reducer.accept(
            MetadataEvent.DvrEntryStatsUpdated(
                generation,
                GatewayDvrStats(DvrEntryId(1), null, DvrEntryState.RECORDING, dataSizeBytes = 4_096),
            ),
        )
        assertEquals(90.seconds, reducer.snapshot().entries.single().playPosition)

        reducer.accept(
            MetadataEvent.DvrEntryUpdated(
                generation,
                GatewayDvrEntry(id = DvrEntryId(1), playPosition = 120.seconds, state = DvrEntryState.RECORDING),
            ),
        )
        val active = reducer.snapshot().entries.single()
        assertEquals(DvrEntryState.RECORDING, active.state)
        assertEquals(120.seconds, active.playPosition)

        reducer.accept(
            MetadataEvent.DvrEntryUpdated(
                generation,
                GatewayDvrEntry(id = DvrEntryId(1), state = DvrEntryState.COMPLETED),
            ),
        )
        val completed = reducer.snapshot().entries.single()
        assertEquals(DvrEntryState.COMPLETED, completed.state)
        assertEquals(null, completed.playPosition)
    }

    private fun entry(
        id: Long,
        enabled: Boolean? = null,
        channelId: Long? = null,
        channelName: String? = null,
        start: Long? = null,
        stop: Long? = null,
        startExtraMinutes: Long? = null,
        stopExtraMinutes: Long? = null,
        contentType: Long? = null,
        subscriptionError: DvrSubscriptionError? = null,
        title: String? = null,
        files: List<GatewayDvrRecordingFile>? = null,
    ): GatewayDvrEntry = GatewayDvrEntry(
        id = DvrEntryId(id),
        enabled = enabled,
        channelId = channelId?.let(::ChannelId),
        channelName = channelName,
        eventId = EventId(id).takeIf { channelId != null },
        start = start?.let(::instant),
        stop = stop?.let(::instant),
        startExtraMinutes = startExtraMinutes,
        stopExtraMinutes = stopExtraMinutes,
        contentType = contentType,
        playPosition = 5.seconds.takeIf { title != null },
        title = title,
        files = files,
        configId = DvrConfigId("config").takeIf { title != null },
        state = DvrEntryState.COMPLETED.takeIf { title != null },
        subscriptionError = subscriptionError,
    )

    private fun autorec(
        id: String,
        enabled: Boolean = true,
        title: String? = null,
        comment: String? = null,
    ): GatewayAutorecRule = GatewayAutorecRule(
        id = AutorecRuleId(id),
        enabled = enabled,
        maxDuration = 60.seconds,
        minDuration = 0.seconds,
        title = title,
        comment = comment,
    )

    private fun timerec(
        id: String,
        enabled: Boolean = true,
        title: String = "title",
        comment: String? = null,
    ): GatewayTimerecRule = GatewayTimerecRule(
        id = TimerecRuleId(id),
        enabled = enabled,
        name = "name",
        title = title,
        channelId = ChannelId(1),
        startMinutesSinceMidnight = 60,
        stopMinutesSinceMidnight = 120,
        comment = comment,
    )

    private fun instant(seconds: Long): Instant = Instant.fromEpochSeconds(seconds)
}
