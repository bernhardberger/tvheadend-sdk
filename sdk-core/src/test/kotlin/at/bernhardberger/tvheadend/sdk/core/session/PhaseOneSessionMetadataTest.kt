package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageRequestResult
import at.bernhardberger.tvheadend.sdk.core.Channel
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.ChannelService
import at.bernhardberger.tvheadend.sdk.core.DvrConfigId
import at.bernhardberger.tvheadend.sdk.core.DvrConfiguration
import at.bernhardberger.tvheadend.sdk.core.DvrConfigurationsState
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpace
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpaceState
import at.bernhardberger.tvheadend.sdk.core.DvrEntryUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrMutationResult
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.gateway.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelService
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrEntry
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrUpdateProvenance
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayServerFacts
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayTagMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.TagId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
internal class PhaseOneSessionMetadataTest {
    @Test
    fun `mutation confirmation is delivered only after authoritative DVR publication`() = runTest {
        val gateway = MutationGateway()
        val coordinator = DvrMutationCoordinator(gateway)
        val metadata = PhaseOneSessionMetadata(
            mutationCommands = coordinator,
            onDvrMetadataAccepted = coordinator::acceptMetadata,
        )
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        coordinator.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.DvrEntryAdded(generation, dvrEntry(1, "old")))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        coordinator.startAdmission(generation)
        gateway.updateBehavior = { _, _, _ -> GatewayResult.Ok(Unit) }

        val result = async {
            val mutation = metadata.dvrRepository.updateEntry(
                DvrEntryId(1),
                DvrEntryUpdate(title = "new"),
            )
            assertEquals("new", metadata.currentDvrSnapshot().entries.single().title)
            mutation
        }
        runCurrent()
        assertFalse(result.isCompleted)

        metadata.acceptMetadata(
            MetadataEvent.DvrEntryUpdated(
                generation,
                dvrEntry(1, "new"),
                GatewayDvrUpdateProvenance.FULL,
            ),
        )
        runCurrent()

        assertTrue(result.await() is DvrMutationResult.Confirmed)
    }

    @Test
    fun `reducer-rejected DVR metadata cannot confirm a mutation`() = runTest {
        val gateway = MutationGateway()
        val coordinator = DvrMutationCoordinator(gateway)
        val metadata = PhaseOneSessionMetadata(
            mutationCommands = coordinator,
            onDvrMetadataAccepted = coordinator::acceptMetadata,
        )
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        coordinator.bindGeneration(generation)
        metadata.acceptMetadata(
            MetadataEvent.DvrEntryAdded(generation, dvrEntry(1, "old", start = 0, stop = 10)),
        )
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        coordinator.startAdmission(generation)
        gateway.updateBehavior = { _, _, _ -> GatewayResult.Ok(Unit) }

        val result = async {
            metadata.dvrRepository.updateEntry(DvrEntryId(1), DvrEntryUpdate(title = "new"))
        }
        runCurrent()
        metadata.acceptMetadata(
            MetadataEvent.DvrEntryUpdated(
                generation,
                dvrEntry(1, "invalid", start = 20, stop = 10),
                GatewayDvrUpdateProvenance.FULL,
            ),
        )
        runCurrent()

        assertFalse(result.isCompleted)
        assertEquals("old", metadata.currentDvrSnapshot().entries.single().title)

        metadata.acceptMetadata(
            MetadataEvent.DvrEntryUpdated(
                generation,
                dvrEntry(1, "new"),
                GatewayDvrUpdateProvenance.FULL,
            ),
        )
        runCurrent()
        assertTrue(result.await() is DvrMutationResult.Confirmed)
    }

    @Test
    fun `matching fence publishes the complete working catalog before readiness`() = runTest {
        val metadata = PhaseOneSessionMetadata()
        val stale = GatewayGeneration()
        val current = GatewayGeneration()
        metadata.resetWorkingStateRetainingPublishedSnapshot()
        metadata.bindGeneration(current)
        metadata.applyDvrAccess(stale, true)
        metadata.applyDvrAccess(current, false)
        metadata.publishServerFacts(stale, serverFacts(streaming = false))
        metadata.publishServerFacts(current, serverFacts(streaming = true))
        val awaiting = async {
            metadata.awaitMetadataCurrent(current)
            assertTrue(
                metadata.channelsAndTags.value is ChannelRepositoryState.Current,
                "The metadata fence completed before catalog publication",
            )
        }
        runCurrent()

        metadata.acceptMetadata(MetadataEvent.ChannelAdded(stale, channel(id = 99)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(stale, epgEvent(99, 99, 0, 10)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(stale))
        runCurrent()
        assertFalse(awaiting.isCompleted)
        assertEquals(
            ChannelRepositoryState.Synchronizing(staleCatalog = null),
            metadata.channelsAndTags.value,
        )

        metadata.acceptMetadata(MetadataEvent.ChannelAdded(current, channel(id = 1, name = "one")))
        metadata.acceptMetadata(MetadataEvent.TagAdded(current, tag(id = 2, channelIds = listOf(1))))
        metadata.acceptMetadata(MetadataEvent.EventAdded(current, epgEvent(4, 1, -1, 10)))
        metadata.acceptMetadata(MetadataEvent.DvrEntryAdded(current, dvrEntry(8, "one")))
        assertTrue(metadata.channelsAndTags.value is ChannelRepositoryState.Synchronizing)
        assertTrue(metadata.epgRepository.state.value is EpgRepositoryState.Synchronizing)
        assertTrue(metadata.dvrRepository.state.value is DvrRepositoryState.Synchronizing)

        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(current))
        runCurrent()
        assertTrue(awaiting.isCompleted)
        val snapshot = metadata.currentSnapshot()
        assertEquals(listOf(1L), snapshot.channels.map { it.id.value })
        assertEquals(listOf(2L), snapshot.tags.map { it.id.value })
        val epg = metadata.currentEpgSnapshot()
        assertEquals(listOf(4L), epg.events.map { it.id.value })
        assertEquals(listOf(8L), metadata.currentDvrSnapshot().entries.map { it.id.value })
        assertEquals(Instant.fromEpochSeconds(-1), epg.coverages.single().coveredFrom)
        assertEquals(Instant.fromEpochSeconds(10), epg.coverages.single().coveredTo)
        assertEquals(CapabilityAccess.ALLOWED, metadata.capabilities(current).streaming)
        assertEquals(CapabilityAccess.DENIED, metadata.capabilities(current).dvrWrite)
        assertEquals(CapabilityAccess.UNKNOWN, metadata.capabilities(current).protocolDvr)
    }

    @Test
    fun `server facts project every observation while dvr write stays independently latched`() = runTest {
        val metadata = PhaseOneSessionMetadata()
        val stale = GatewayGeneration()
        val current = GatewayGeneration()
        val features = mutableListOf("htsp", "satip")
        metadata.bindGeneration(current)
        metadata.applyDvrAccess(current, false)
        metadata.publishServerFacts(
            stale,
            serverFacts(
                streaming = false,
                dvr = false,
                serverName = "stale-server",
            ),
        )
        metadata.publishServerFacts(
            current,
            serverFacts(
                streaming = true,
                dvr = true,
                failedDvr = false,
                admin = true,
                anonymous = false,
                apiVersion = 42,
                limitAll = 0,
                limitDvr = 9,
                limitStreaming = 8,
                uiLevel = 0,
                serverCapabilities = features,
                serverName = "private-server",
                serverVersion = "private-version",
                webRoot = "/secret",
                language = "eng",
                uiLanguage = "deu",
            ),
        )
        features.clear()
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(current))
        metadata.awaitMetadataCurrent(current)

        val capabilities = metadata.capabilities(current)
        assertEquals(
            ServerCapabilities.create(
                streaming = CapabilityAccess.ALLOWED,
                dvrWrite = CapabilityAccess.DENIED,
                protocolDvr = CapabilityAccess.ALLOWED,
                failedDvr = CapabilityAccess.DENIED,
                admin = CapabilityAccess.ALLOWED,
                anonymous = CapabilityAccess.DENIED,
                apiVersion = 42,
                allLimit = 0,
                dvrLimit = 9,
                streamingLimit = 8,
                uiLevel = 0,
                features = listOf("htsp", "satip"),
                serverName = "private-server",
                serverVersion = "private-version",
                webRoot = "/secret",
                language = "eng",
                uiLanguage = "deu",
            ),
            capabilities,
        )
        assertEquals(listOf("htsp", "satip"), capabilities.features)
        assertThrows(UnsupportedOperationException::class.java) {
            (capabilities.features as MutableList<String>).clear()
        }
        assertFalse(
            capabilities.toString().contains("private"),
            "Capability rendering exposed server identity",
        )
        assertFalse(
            capabilities.toString().contains("/secret"),
            "Capability rendering exposed a path",
        )
    }

    @Test
    fun `dvr write latch uses later proof and ignores non proof failures`() {
        val metadata = PhaseOneSessionMetadata()
        val stale = GatewayGeneration()
        val current = GatewayGeneration()
        val configuration = DvrConfiguration(DvrConfigId("private-config"), "Default", "comment")
        metadata.bindGeneration(current)
        metadata.applyDvrAccess(current, null)
        metadata.publishServerFacts(current, serverFacts(streaming = true))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(current))

        assertEquals(CapabilityAccess.UNKNOWN, metadata.capabilities(current).dvrWrite)
        assertTrue(
            metadata.dvrRepository.configurationsState.value is DvrConfigurationsState.Synchronizing,
        )

        metadata.applyDvrConfigurations(current, GatewayResult.Timeout)
        metadata.applyDvrConfigurations(current, GatewayResult.ServerRejected)
        metadata.applyDvrConfigurations(current, GatewayResult.ConnectionLimit)
        metadata.applyDvrConfigurations(current, GatewayResult.TransportUnavailable)
        metadata.applyDvrConfigurations(current, GatewayResult.NotSupported)
        metadata.applyDvrConfigurations(stale, GatewayResult.Ok(listOf(configuration)))
        assertEquals(CapabilityAccess.UNKNOWN, metadata.capabilities(current).dvrWrite)
        assertEquals(DvrConfigurationsState.Unknown, metadata.dvrRepository.configurationsState.value)

        metadata.applyDvrAccess(current, false)
        assertEquals(CapabilityAccess.DENIED, metadata.capabilities(current).dvrWrite)
        metadata.applyDvrConfigurations(current, GatewayResult.Ok(listOf(configuration)))
        assertEquals(CapabilityAccess.ALLOWED, metadata.capabilities(current).dvrWrite)
        val currentConfigs =
            metadata.dvrRepository.configurationsState.value as DvrConfigurationsState.Current
        assertEquals(listOf(configuration), currentConfigs.configurations)
        assertThrows(UnsupportedOperationException::class.java) {
            (currentConfigs.configurations as MutableList<DvrConfiguration>).clear()
        }
        assertFalse(
            currentConfigs.toString().contains("private"),
            "Configuration rendering exposed a configuration identifier",
        )

        metadata.applyDvrConfigurations(current, GatewayResult.AccessDenied)
        assertEquals(CapabilityAccess.DENIED, metadata.capabilities(current).dvrWrite)
        assertEquals(DvrConfigurationsState.Denied, metadata.dvrRepository.configurationsState.value)
        assertEquals(emptyList<DvrConfiguration>(), metadata.dvrRepository.configurations.value)
    }

    @Test
    fun `disk space and configurations retain stale snapshots across reconnect`() {
        val metadata = PhaseOneSessionMetadata()
        val first = GatewayGeneration()
        val second = GatewayGeneration()
        val configuration = DvrConfiguration(DvrConfigId("config"), "Default", "")
        val diskSpace = DvrDiskSpace(freeBytes = 8, usedBytes = null, totalBytes = 16)
        metadata.bindGeneration(first)
        metadata.applyDvrAccess(first, true)
        metadata.publishServerFacts(first, serverFacts(streaming = true))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(first))
        metadata.applyDvrConfigurations(first, GatewayResult.Ok(listOf(configuration)))
        metadata.applyDvrDiskSpace(first, GatewayResult.Ok(diskSpace))

        metadata.resetWorkingStateRetainingPublishedSnapshot()
        assertEquals(
            listOf(configuration),
            (metadata.dvrRepository.configurationsState.value as DvrConfigurationsState.Stale)
                .configurations,
        )
        assertEquals(
            diskSpace,
            (metadata.dvrRepository.diskSpaceState.value as DvrDiskSpaceState.Stale).diskSpace,
        )

        metadata.bindGeneration(second)
        assertEquals(
            listOf(configuration),
            (metadata.dvrRepository.configurationsState.value as DvrConfigurationsState.Synchronizing)
                .staleConfigurations,
        )
        assertEquals(
            diskSpace,
            (metadata.dvrRepository.diskSpaceState.value as DvrDiskSpaceState.Synchronizing)
                .staleDiskSpace,
        )

        metadata.applyDvrDiskSpace(first, GatewayResult.Ok(DvrDiskSpace(1, 1, 2)))
        assertEquals(
            diskSpace,
            (metadata.dvrRepository.diskSpaceState.value as DvrDiskSpaceState.Synchronizing)
                .staleDiskSpace,
        )
        metadata.applyDvrDiskSpace(second, GatewayResult.Timeout)
        assertEquals(
            DvrDiskSpaceState.Stale(diskSpace),
            metadata.dvrRepository.diskSpaceState.value,
        )
        assertEquals(diskSpace, metadata.dvrRepository.diskSpace.value)
        metadata.applyDvrDiskSpace(second, GatewayResult.Ok(DvrDiskSpace(3, 1, 4)))
        assertEquals(
            DvrDiskSpace(3, 1, 4),
            (metadata.dvrRepository.diskSpaceState.value as DvrDiskSpaceState.Current).diskSpace,
        )

        metadata.clearAllState()
        assertEquals(DvrConfigurationsState.Unknown, metadata.dvrRepository.configurationsState.value)
        assertEquals(DvrDiskSpaceState.Unknown, metadata.dvrRepository.diskSpaceState.value)
    }

    @Test
    fun `partial updates preserve omissions and empty collections replace prior values`() {
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(
            MetadataEvent.ChannelAdded(
                generation,
                channel(
                    id = 1,
                    name = "name",
                    uuid = "uuid",
                    number = 7,
                    numberMinor = 8,
                    icon = "icon",
                    currentEventId = 4,
                    nextEventId = 5,
                    services = listOf(service("service")),
                    tagIds = listOf(2),
                ),
            ),
        )
        metadata.acceptMetadata(
            MetadataEvent.TagAdded(
                generation,
                tag(
                    id = 2,
                    name = "tag",
                    uuid = "tag-uuid",
                    index = 9,
                    icon = "tag-icon",
                    titledIcon = true,
                    channelIds = listOf(1),
                ),
            ),
        )
        metadata.acceptMetadata(
            MetadataEvent.ChannelUpdated(
                generation,
                channel(id = 1, name = "", number = 0),
            ),
        )
        metadata.acceptMetadata(
            MetadataEvent.TagUpdated(
                generation,
                tag(id = 2, name = "", index = 0),
            ),
        )
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))

        var snapshot = metadata.currentSnapshot()
        var channel = snapshot.channels.single()
        var tag = snapshot.tags.single()
        assertEquals("", channel.name)
        assertEquals("uuid", channel.uuid)
        assertEquals(0L, channel.number)
        assertEquals(8L, channel.numberMinor)
        assertEquals("icon", channel.icon)
        assertEquals(4L, channel.currentEventId?.value)
        assertEquals(5L, channel.nextEventId?.value)
        assertEquals("service", channel.services?.single()?.name)
        assertEquals(listOf(2L), channel.tagIds?.map { it.value })
        assertEquals("", tag.name)
        assertEquals("tag-uuid", tag.uuid)
        assertEquals(0L, tag.index)
        assertEquals(true, tag.titledIcon)
        assertEquals(listOf(1L), tag.channelIds?.map { it.value })

        metadata.acceptMetadata(
            MetadataEvent.ChannelUpdated(
                generation,
                channel(id = 1, services = emptyList(), tagIds = emptyList()),
            ),
        )
        metadata.acceptMetadata(
            MetadataEvent.TagUpdated(generation, tag(id = 2, channelIds = emptyList())),
        )
        snapshot = metadata.currentSnapshot()
        channel = snapshot.channels.single()
        tag = snapshot.tags.single()
        assertEquals(emptyList<Any>(), channel.services)
        assertEquals(emptyList<Any>(), channel.tagIds)
        assertEquals(emptyList<Any>(), tag.channelIds)
        assertEquals(4L, channel.currentEventId?.value)
        assertEquals(5L, channel.nextEventId?.value)

        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id = 1)))
        metadata.acceptMetadata(MetadataEvent.TagAdded(generation, tag(id = 2)))
        snapshot = metadata.currentSnapshot()
        channel = snapshot.channels.single()
        tag = snapshot.tags.single()
        assertEquals(null, channel.currentEventId)
        assertEquals(null, channel.nextEventId)
        assertEquals("", channel.name)
        assertEquals(0L, channel.number)
        assertEquals(emptyList<Any>(), channel.services)
        assertEquals(emptyList<Any>(), channel.tagIds)
        assertEquals("", tag.name)
        assertEquals(0L, tag.index)
        assertEquals(emptyList<Any>(), tag.channelIds)
    }

    @Test
    fun `entity deletes clean opposite references and event delete clears every channel link`() {
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(
            MetadataEvent.ChannelAdded(
                generation,
                channel(
                    id = 1,
                    currentEventId = 4,
                    nextEventId = 4,
                    tagIds = listOf(10, 20, 20),
                ),
            ),
        )
        metadata.acceptMetadata(
            MetadataEvent.ChannelAdded(
                generation,
                channel(id = 2, currentEventId = 4, nextEventId = 5, tagIds = listOf(20)),
            ),
        )
        metadata.acceptMetadata(
            MetadataEvent.TagAdded(generation, tag(id = 10, channelIds = listOf(1, 2))),
        )
        metadata.acceptMetadata(
            MetadataEvent.TagAdded(generation, tag(id = 20, channelIds = listOf(1, 2))),
        )
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))

        metadata.acceptMetadata(
            MetadataEvent.TagUpdated(generation, tag(id = 10, channelIds = listOf(99, 1, 1, 2))),
        )
        metadata.acceptMetadata(MetadataEvent.ChannelDeleted(generation, ChannelId(99)))
        metadata.acceptMetadata(MetadataEvent.ChannelDeleted(generation, ChannelId(2)))
        metadata.acceptMetadata(MetadataEvent.TagDeleted(generation, TagId(20)))
        metadata.acceptMetadata(MetadataEvent.EventDeleted(generation, EventId(4)))

        val snapshot = metadata.currentSnapshot()
        val channel = snapshot.channels.single()
        val tag = snapshot.tags.single()
        assertEquals(1L, channel.id.value)
        assertEquals(listOf(10L), channel.tagIds?.map { it.value })
        assertEquals(null, channel.currentEventId)
        assertEquals(null, channel.nextEventId)
        assertEquals(10L, tag.id.value)
        assertEquals(listOf(1L, 1L), tag.channelIds?.map { it.value })
        assertEquals(emptyList<Any>(), metadata.currentEpgSnapshot().events)
    }

    @Test
    fun `reconnect exposes stale data and replaces it with only the new synchronization set`() {
        val metadata = PhaseOneSessionMetadata()
        val first = GatewayGeneration()
        val second = GatewayGeneration()
        metadata.bindGeneration(first)
        metadata.acceptMetadata(
            MetadataEvent.ChannelAdded(
                first,
                channel(id = 1, name = "old", icon = "old-icon", tagIds = listOf(10)),
            ),
        )
        metadata.acceptMetadata(
            MetadataEvent.ChannelAdded(first, channel(id = 2, name = "evicted", tagIds = listOf(10))),
        )
        metadata.acceptMetadata(
            MetadataEvent.TagAdded(first, tag(id = 10, channelIds = listOf(1, 2))),
        )
        metadata.acceptMetadata(MetadataEvent.EventAdded(first, epgEvent(4, 1, 10, 20, "old-event")))
        metadata.acceptMetadata(MetadataEvent.DvrEntryAdded(first, dvrEntry(8, "old-dvr")))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(first))
        val staleSnapshot = metadata.currentSnapshot()
        val staleEpgSnapshot = metadata.currentEpgSnapshot()
        val staleDvrSnapshot = metadata.currentDvrSnapshot()

        metadata.resetWorkingStateRetainingPublishedSnapshot()
        val staleState = metadata.channelsAndTags.value as ChannelRepositoryState.Stale
        assertSame(staleSnapshot, staleState.catalog)
        assertSame(
            staleEpgSnapshot,
            (metadata.epgRepository.state.value as EpgRepositoryState.Stale).snapshot,
        )
        assertSame(
            staleDvrSnapshot,
            (metadata.dvrRepository.state.value as DvrRepositoryState.Stale).snapshot,
        )
        metadata.bindGeneration(second)
        val synchronizing = metadata.channelsAndTags.value as ChannelRepositoryState.Synchronizing
        assertSame(staleSnapshot, synchronizing.staleCatalog)
        assertSame(
            staleEpgSnapshot,
            (metadata.epgRepository.state.value as EpgRepositoryState.Synchronizing).staleSnapshot,
        )
        assertSame(
            staleDvrSnapshot,
            (metadata.dvrRepository.state.value as DvrRepositoryState.Synchronizing).staleSnapshot,
        )

        metadata.acceptMetadata(
            MetadataEvent.ChannelUpdated(
                second,
                channel(id = 1, name = "new", tagIds = listOf(10, 11)),
            ),
        )
        metadata.acceptMetadata(
            MetadataEvent.TagAdded(second, tag(id = 11, channelIds = listOf(1, 2))),
        )
        metadata.acceptMetadata(MetadataEvent.EventAdded(second, epgEvent(6, 1, 30, 40, "new-event")))
        metadata.acceptMetadata(MetadataEvent.DvrEntryAdded(second, dvrEntry(9, "new-dvr")))
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(first, channel(id = 3, name = "stale")))
        metadata.acceptMetadata(MetadataEvent.EventAdded(first, epgEvent(7, 1, 50, 60, "stale-event")))
        metadata.acceptMetadata(MetadataEvent.DvrEntryAdded(first, dvrEntry(10, "stale-dvr")))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(first))
        assertSame(staleSnapshot, synchronizing.staleCatalog)
        assertEquals(listOf(1L, 2L), staleSnapshot.channels.map { it.id.value })

        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(second))
        val current = metadata.currentSnapshot()
        val channel = current.channels.single()
        val tag = current.tags.single()
        assertEquals(1L, channel.id.value)
        assertEquals("new", channel.name)
        assertEquals(null, channel.icon)
        assertEquals(listOf(11L), channel.tagIds?.map { it.value })
        assertEquals(11L, tag.id.value)
        assertEquals(listOf(1L), tag.channelIds?.map { it.value })
        assertEquals(listOf(1L, 2L), staleSnapshot.channels.map { it.id.value })
        assertEquals(listOf(6L), metadata.currentEpgSnapshot().events.map { it.id.value })
        assertEquals(listOf(4L), staleEpgSnapshot.events.map { it.id.value })
        assertEquals(listOf(9L), metadata.currentDvrSnapshot().entries.map { it.id.value })
        assertEquals(listOf(8L), staleDvrSnapshot.entries.map { it.id.value })
    }

    @Test
    fun `coverage requester is current generation bound and cleared by identity or reset`() {
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        val channelId = ChannelId(7)
        val through = Instant.fromEpochSeconds(20_000)
        var observed: Pair<ChannelId, Instant>? = null
        val requester = EpgCoverageRequester { requestedChannelId, requestedThrough ->
            observed = requestedChannelId to requestedThrough
            EpgCoverageRequestResult.ACCEPTED
        }

        assertEquals(
            EpgCoverageRequestResult.GENERATION_LOST,
            metadata.epgRepository.requestCoverage(channelId, through),
        )
        metadata.bindGeneration(generation)
        assertFalse(metadata.bindEpgCoverageRequester(generation, requester))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        assertTrue(metadata.bindEpgCoverageRequester(generation, requester))
        assertEquals(
            EpgCoverageRequestResult.ACCEPTED,
            metadata.epgRepository.requestCoverage(channelId, through),
        )
        assertEquals(channelId to through, observed)

        metadata.clearEpgCoverageRequester(
            generation,
            EpgCoverageRequester { _, _ -> EpgCoverageRequestResult.INELIGIBLE },
        )
        assertEquals(
            EpgCoverageRequestResult.ACCEPTED,
            metadata.epgRepository.requestCoverage(channelId, through),
        )
        metadata.clearEpgCoverageRequester(generation, requester)
        assertEquals(
            EpgCoverageRequestResult.GENERATION_LOST,
            metadata.epgRepository.requestCoverage(channelId, through),
        )

        assertTrue(metadata.bindEpgCoverageRequester(generation, requester))
        metadata.resetWorkingStateRetainingPublishedSnapshot()
        assertEquals(
            EpgCoverageRequestResult.GENERATION_LOST,
            metadata.epgRepository.requestCoverage(channelId, through),
        )
    }

    @Test
    fun `coverage and retention mutations are generation fenced and preserve successful horizon`() {
        val metadata = PhaseOneSessionMetadata()
        val stale = GatewayGeneration()
        val current = GatewayGeneration()
        metadata.bindGeneration(current)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(current, channel(id = 1)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(current, epgEvent(1, 1, 0, 10)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(current, epgEvent(2, 1, 20, 30)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(current))

        metadata.recordSuccessfulEpgQuery(stale, ChannelId(1), Instant.fromEpochSeconds(100))
        metadata.retainEpgEvents(stale, Instant.fromEpochSeconds(20), Instant.fromEpochSeconds(10))
        metadata.recordSuccessfulEpgQuery(current, ChannelId(1), Instant.fromEpochSeconds(50))
        metadata.recordSuccessfulEpgQuery(current, ChannelId(1), Instant.fromEpochSeconds(40))
        metadata.retainEpgEvents(current, Instant.fromEpochSeconds(10), Instant.fromEpochSeconds(20))

        var snapshot = metadata.currentEpgSnapshot()
        assertEquals(listOf(1L, 2L), snapshot.events.map { it.id.value })
        var coverage = snapshot.coverages.single()
        assertEquals(Instant.fromEpochSeconds(0), coverage.coveredFrom)
        assertEquals(Instant.fromEpochSeconds(30), coverage.coveredTo)
        assertEquals(Instant.fromEpochSeconds(50), coverage.queriedTo)

        metadata.retainEpgEvents(current, Instant.fromEpochSeconds(11), Instant.fromEpochSeconds(19))
        snapshot = metadata.currentEpgSnapshot()
        coverage = snapshot.coverages.single()
        assertEquals(emptyList<Any>(), snapshot.events)
        assertTrue(coverage.isEmpty)
        assertEquals(Instant.fromEpochSeconds(50), coverage.knownTo)
    }

    @Test
    fun `successful query applies events and horizon together only to a current channel lifetime`() {
        val metadata = PhaseOneSessionMetadata()
        val stale = GatewayGeneration()
        val current = GatewayGeneration()
        metadata.bindGeneration(current)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(current, channel(id = 1)))
        assertNull(metadata.beginEpgQuery(current, ChannelId(1)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(current))
        val original = metadata.epgRepository.state.value
        val event = GatewayEpgQueryEvent(
            id = EventId(10),
            channelId = ChannelId(1),
            start = Instant.fromEpochSeconds(10),
            stop = Instant.fromEpochSeconds(20),
            title = "private-title",
        )

        assertNull(metadata.beginEpgQuery(stale, ChannelId(1)))
        assertSame(original, metadata.epgRepository.state.value)

        val currentQuery = requireNotNull(metadata.beginEpgQuery(current, ChannelId(1)))
        metadata.acceptMetadata(MetadataEvent.ChannelUpdated(current, channel(id = 1)))
        metadata.applySuccessfulEpgQuery(
            current,
            currentQuery,
            Instant.fromEpochSeconds(100),
            listOf(event),
        )
        var snapshot = metadata.currentEpgSnapshot(current)
        assertEquals(listOf(10L), snapshot?.events?.map { it.id.value })
        assertEquals(Instant.fromEpochSeconds(100), snapshot?.coverages?.single()?.queriedTo)

        val overtakenQuery = requireNotNull(metadata.beginEpgQuery(current, ChannelId(1)))
        metadata.acceptMetadata(MetadataEvent.ChannelDeleted(current, ChannelId(1)))
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(current, channel(id = 1)))
        metadata.applySuccessfulEpgQuery(
            current,
            overtakenQuery,
            Instant.fromEpochSeconds(200),
            listOf(event),
        )
        snapshot = metadata.currentEpgSnapshot(current)
        assertEquals(emptyList<Any>(), snapshot?.events)
        assertTrue(requireNotNull(snapshot).coverages.single().isEmpty)
        assertNull(snapshot.coverages.single().queriedTo)
        assertFalse(
            metadata.epgRepository.state.value.toString().contains("private"),
            "Query publication rendering exposed programme data",
        )
    }

    @Test
    fun `published snapshots retain insertion order are immutable conflated and redacted`() {
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        val mutableServices = mutableListOf(service("private-service"), service("private-service"))
        val mutableTags = mutableListOf(7L, 7L)
        val firstChannel = channel(
            id = 2,
            name = "private-channel",
            services = mutableServices,
            tagIds = mutableTags,
        )
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, firstChannel))
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id = 1)))
        metadata.acceptMetadata(MetadataEvent.ChannelUpdated(generation, channel(id = 2)))
        metadata.acceptMetadata(MetadataEvent.ChannelDeleted(generation, ChannelId(1)))
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id = 1)))
        metadata.acceptMetadata(
            MetadataEvent.TagAdded(generation, tag(id = 7, name = "private-tag", channelIds = listOf(2, 2))),
        )
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        mutableServices.clear()
        mutableTags.clear()

        val state = metadata.channelsAndTags.value as ChannelRepositoryState.Current
        val snapshot = state.catalog
        assertEquals(listOf(2L, 1L), snapshot.channels.map { it.id.value })
        assertEquals(2, snapshot.channels.first().services?.size)
        assertEquals(listOf(7L, 7L), snapshot.channels.first().tagIds?.map { it.value })
        assertEquals(listOf(2L, 2L), snapshot.tags.single().channelIds?.map { it.value })
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.channels as MutableList<Channel>).add(snapshot.channels.first())
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.channels.first().services as MutableList<ChannelService>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.channels.first().tagIds as MutableList<TagId>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.tags.single().channelIds as MutableList<ChannelId>).clear()
        }

        metadata.acceptMetadata(MetadataEvent.ChannelUpdated(generation, channel(id = 2)))
        metadata.acceptMetadata(
            MetadataEvent.DvrEntryUpdated(
                generation,
                dvrEntry(8, "private-dvr"),
                GatewayDvrUpdateProvenance.FULL,
            ),
        )
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        metadata.acceptMetadata(MetadataEvent.ChannelDeleted(GatewayGeneration(), ChannelId(2)))
        assertSame(state, metadata.channelsAndTags.value)
        metadata.resetWorkingStateRetainingPublishedSnapshot()
        val stale = metadata.channelsAndTags.value as ChannelRepositoryState.Stale
        assertSame(snapshot, stale.catalog)

        val rendering = listOf(
            state,
            snapshot,
            snapshot.channels.first(),
            snapshot.channels.first().services?.first(),
            snapshot.tags.first(),
            metadata.dvrRepository.state.value,
        ).joinToString()
        assertFalse(rendering.contains("private"), "Catalog rendering exposed metadata")
    }

    @Test
    fun `reset invalidates old waiters and empty synchronization is current`() = runTest {
        val metadata = PhaseOneSessionMetadata()
        val first = GatewayGeneration()
        metadata.bindGeneration(first)
        val retired = async { metadata.awaitMetadataCurrent(first) }
        runCurrent()

        metadata.resetWorkingStateRetainingPublishedSnapshot()
        runCurrent()
        assertTrue(retired.isCancelled, "Reset did not invalidate the retired generation fence")

        val second = GatewayGeneration()
        metadata.bindGeneration(second)
        val raced = async {
            runCatching { metadata.awaitMetadataCurrent(second) }
        }
        runCurrent()
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(second))
        metadata.resetWorkingStateRetainingPublishedSnapshot()
        runCurrent()
        assertTrue(raced.await().isFailure, "A retired generation passed its post-fence check")

        val third = GatewayGeneration()
        metadata.bindGeneration(third)
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(third))
        metadata.awaitMetadataCurrent(third)
        val snapshot = metadata.currentSnapshot()
        assertTrue(snapshot.channels.isEmpty(), "Empty synchronized channels were not current")
        assertTrue(snapshot.tags.isEmpty(), "Empty synchronized tags were not current")
        assertTrue(metadata.currentEpgSnapshot().events.isEmpty(), "Empty synchronized EPG was not current")
        assertTrue(metadata.currentDvrSnapshot().entries.isEmpty(), "Empty synchronized DVR was not current")
    }

    private fun PhaseOneSessionMetadata.currentSnapshot() =
        (channelsAndTags.value as ChannelRepositoryState.Current).catalog

    private fun PhaseOneSessionMetadata.currentEpgSnapshot() =
        (epgRepository.state.value as EpgRepositoryState.Current).snapshot

    private fun PhaseOneSessionMetadata.currentDvrSnapshot() =
        (dvrRepository.state.value as DvrRepositoryState.Current).snapshot

    private fun dvrEntry(
        id: Long,
        title: String? = null,
        start: Long? = null,
        stop: Long? = null,
    ): GatewayDvrEntry = GatewayDvrEntry(
        id = DvrEntryId(id),
        title = title,
        start = start?.let(Instant::fromEpochSeconds),
        stop = stop?.let(Instant::fromEpochSeconds),
    )

    private fun channel(
        id: Long,
        name: String? = null,
        uuid: String? = null,
        number: Long? = null,
        numberMinor: Long? = null,
        icon: String? = null,
        currentEventId: Long? = null,
        nextEventId: Long? = null,
        services: List<GatewayChannelService>? = null,
        tagIds: List<Long>? = null,
    ): GatewayChannelMetadata = GatewayChannelMetadata(
        id = ChannelId(id),
        name = name,
        uuid = uuid,
        number = number,
        numberMinor = numberMinor,
        icon = icon,
        currentEventId = currentEventId?.let(::EventId),
        nextEventId = nextEventId?.let(::EventId),
        services = services,
        tagIds = tagIds?.map(::TagId),
    )

    private fun tag(
        id: Long,
        name: String? = null,
        uuid: String? = null,
        index: Long? = null,
        icon: String? = null,
        titledIcon: Boolean? = null,
        channelIds: List<Long>? = null,
    ): GatewayTagMetadata = GatewayTagMetadata(
        id = TagId(id),
        name = name,
        uuid = uuid,
        index = index,
        icon = icon,
        titledIcon = titledIcon,
        channelIds = channelIds?.map(::ChannelId),
    )

    private fun epgEvent(
        id: Long,
        channelId: Long?,
        start: Long,
        stop: Long,
        title: String? = null,
    ): GatewayEpgEvent = GatewayEpgEvent(
        id = EventId(id),
        channelId = channelId?.let(::ChannelId),
        start = Instant.fromEpochSeconds(start),
        stop = Instant.fromEpochSeconds(stop),
        title = title,
    )

    private fun service(name: String): GatewayChannelService = GatewayChannelService(
        name = name,
        type = "type",
        content = 1,
        conditionalAccessId = 2,
        conditionalAccessName = "private-ca",
        providerName = "private-provider",
    )

    private fun serverFacts(
        streaming: Boolean? = null,
        dvr: Boolean? = null,
        failedDvr: Boolean? = null,
        admin: Boolean? = null,
        anonymous: Boolean? = null,
        apiVersion: Int? = null,
        limitAll: Int? = null,
        limitDvr: Int? = null,
        limitStreaming: Int? = null,
        uiLevel: Int? = null,
        serverCapabilities: List<String>? = null,
        serverName: String? = null,
        serverVersion: String? = null,
        webRoot: String? = null,
        language: String? = null,
        uiLanguage: String? = null,
    ): GatewayServerFacts = GatewayServerFacts(
        serverName = serverName,
        serverVersion = serverVersion,
        webRoot = webRoot,
        language = language,
        serverCapabilities = serverCapabilities,
        apiVersion = apiVersion,
        admin = admin,
        streaming = streaming,
        dvr = dvr,
        failedDvr = failedDvr,
        anonymous = anonymous,
        limitAll = limitAll,
        limitDvr = limitDvr,
        limitStreaming = limitStreaming,
        uiLevel = uiLevel,
        uiLanguage = uiLanguage,
    )
}
