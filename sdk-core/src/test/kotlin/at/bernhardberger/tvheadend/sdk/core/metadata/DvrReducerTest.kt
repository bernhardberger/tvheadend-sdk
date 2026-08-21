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
    fun `dvr add null-baselines browse scalars while update preserves omissions`() {
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
                entry(id = 1, title = "new"),
            ),
        )

        var snapshot = reducer.snapshot().entries.single()
        assertEquals("new", snapshot.title)
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
        reducer.accept(MetadataEvent.DvrEntryUpdated(generation, entry(id = 10, title = "draft")))
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
    fun `autorec and timerec add replace while update preserves omissions`() {
        val reducer = DvrReducer()
        reducer.accept(
            MetadataEvent.AutorecRuleAdded(
                generation,
                autorec("auto", enabled = true, title = "old-auto", comment = "keep-auto"),
            ),
        )
        reducer.accept(
            MetadataEvent.TimerecRuleAdded(
                generation,
                timerec("time", enabled = true, title = "old-time", comment = "keep-time"),
            ),
        )
        reducer.accept(
            MetadataEvent.AutorecRuleUpdated(generation, GatewayAutorecRule(AutorecRuleId("auto"), title = "new-auto")),
        )
        reducer.accept(
            MetadataEvent.TimerecRuleUpdated(generation, GatewayTimerecRule(TimerecRuleId("time"), title = "new-time")),
        )

        var snapshot = reducer.snapshot()
        assertEquals("new-auto", snapshot.autorecRules.single().title)
        assertEquals(true, snapshot.autorecRules.single().enabled)
        assertEquals("keep-auto", snapshot.autorecRules.single().comment)
        assertEquals("new-time", snapshot.timerecRules.single().title)
        assertEquals(true, snapshot.timerecRules.single().enabled)
        assertEquals("keep-time", snapshot.timerecRules.single().comment)

        reducer.accept(
            MetadataEvent.AutorecRuleAdded(generation, autorec("auto", enabled = false, title = "replaced-auto")),
        )
        reducer.accept(
            MetadataEvent.TimerecRuleAdded(generation, timerec("time", enabled = false, title = "replaced-time")),
        )
        snapshot = reducer.snapshot()
        assertEquals(false, snapshot.autorecRules.single().enabled)
        assertEquals("replaced-auto", snapshot.autorecRules.single().title)
        assertEquals(null, snapshot.autorecRules.single().comment)
        assertEquals(false, snapshot.timerecRules.single().enabled)
        assertEquals("replaced-time", snapshot.timerecRules.single().title)
        assertEquals(null, snapshot.timerecRules.single().comment)
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
    fun `error observations derive public state and error-only updates keep the base`() {
        val reducer = DvrReducer()
        reducer.accept(
            MetadataEvent.DvrEntryAdded(
                generation,
                GatewayDvrEntry(
                    id = DvrEntryId(1),
                    state = DvrEntryState.RECORDING,
                    failure = GatewayDvrFailure.PRESENT,
                ),
            ),
        )
        assertEquals(DvrEntryState.RECORDING_ERROR, reducer.snapshot().entries.single().state)

        reducer.accept(
            MetadataEvent.DvrEntryUpdated(
                generation,
                GatewayDvrEntry(
                    id = DvrEntryId(1),
                    state = DvrEntryState.COMPLETED,
                    failure = GatewayDvrFailure.FILE_MISSING,
                ),
            ),
        )
        assertEquals(DvrEntryState.FILE_MISSING, reducer.snapshot().entries.single().state)

        reducer.accept(
            MetadataEvent.DvrEntryUpdated(
                generation,
                GatewayDvrEntry(id = DvrEntryId(1), failure = GatewayDvrFailure.NONE),
            ),
        )
        assertEquals(DvrEntryState.COMPLETED, reducer.snapshot().entries.single().state)

        reducer.accept(
            MetadataEvent.DvrEntryUpdated(
                generation,
                GatewayDvrEntry(id = DvrEntryId(1), failure = GatewayDvrFailure.PRESENT),
            ),
        )
        assertEquals(DvrEntryState.COMPLETED_ERROR, reducer.snapshot().entries.single().state)
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
