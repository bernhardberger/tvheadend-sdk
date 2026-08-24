package at.bernhardberger.tvheadend.sdk.core.metadata

import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrSubscriptionError
import at.bernhardberger.tvheadend.sdk.core.gateway.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrConfigId
import at.bernhardberger.tvheadend.sdk.core.gateway.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayAutorecRule
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrEntry
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrRecordingFile
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrUpdateProvenance
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayTimerecRule
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.TimerecRuleId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal class DvrReducerTest {
    private val generation = GatewayGeneration()

    @Test
    fun `dvr add null-baselines browse scalars while stats update preserves omissions`() {
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
            MetadataEvent.DvrEntryUpdated(
                generation,
                GatewayDvrEntry(
                    id = DvrEntryId(1),
                    state = DvrEntryState.COMPLETED,
                    streamErrors = 1,
                    dataErrors = 2,
                    dataSizeBytes = 3,
                ),
                GatewayDvrUpdateProvenance.STATS_ONLY,
            ),
        )

        var snapshot = reducer.snapshot().entries.single()
        assertEquals("old", snapshot.title)
        assertEquals(true, snapshot.enabled)
        assertEquals(8L, snapshot.contentType)
        assertEquals(2L, snapshot.startExtraMinutes)
        assertEquals(3L, snapshot.stopExtraMinutes)
        assertEquals(DvrSubscriptionError.SCRAMBLED, snapshot.subscriptionError)
        assertEquals("private-channel", snapshot.channelName)

        reducer.accept(MetadataEvent.DvrEntryAdded(generation, entry(id = 1, title = "replaced")))

        snapshot = reducer.snapshot().entries.single()
        assertEquals("replaced", snapshot.title)
        assertEquals("private-channel", snapshot.channelName)
        assertEquals(null, snapshot.enabled)
        assertEquals(null, snapshot.contentType)
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
                GatewayDvrUpdateProvenance.FULL,
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
                GatewayDvrUpdateProvenance.FULL,
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
    fun `full DVR updates clear omitted errors while stats updates preserve them`() {
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
                GatewayDvrUpdateProvenance.FULL,
            ),
        )
        var entry = reducer.snapshot().entries.single()
        assertEquals(DvrEntryState.RECORDING, entry.state)
        assertEquals(null, entry.subscriptionError)

        reducer.accept(
            MetadataEvent.DvrEntryUpdated(
                generation,
                GatewayDvrEntry(
                    id = DvrEntryId(1),
                    failure = GatewayDvrFailure.PRESENT,
                    subscriptionError = DvrSubscriptionError.INVALID_TARGET,
                ),
                GatewayDvrUpdateProvenance.STATS_ONLY,
            ),
        )
        entry = reducer.snapshot().entries.single()
        assertEquals(DvrEntryState.RECORDING_ERROR, entry.state)
        assertEquals(DvrSubscriptionError.INVALID_TARGET, entry.subscriptionError)

        reducer.accept(
            MetadataEvent.DvrEntryUpdated(
                generation,
                GatewayDvrEntry(id = DvrEntryId(1), state = DvrEntryState.COMPLETED),
                GatewayDvrUpdateProvenance.STATS_ONLY,
            ),
        )
        entry = reducer.snapshot().entries.single()
        assertEquals(DvrEntryState.COMPLETED_ERROR, entry.state)
        assertEquals(DvrSubscriptionError.INVALID_TARGET, entry.subscriptionError)

        reducer.accept(
            MetadataEvent.DvrEntryUpdated(
                generation,
                GatewayDvrEntry(id = DvrEntryId(1), state = DvrEntryState.COMPLETED),
                GatewayDvrUpdateProvenance.FULL,
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
                GatewayDvrUpdateProvenance.FULL,
            ),
        )

        val entry = reducer.snapshot().entries.single()
        assertEquals(9L, entry.channelId?.value)
        assertEquals(emptyList<Any>(), entry.files)
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
