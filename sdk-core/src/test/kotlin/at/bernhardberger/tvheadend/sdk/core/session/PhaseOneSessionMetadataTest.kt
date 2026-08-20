package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayServerFacts
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class PhaseOneSessionMetadataTest {
    @Test
    fun `only the bound generation completes the sync fence and publishes capabilities`() = runTest {
        val metadata = PhaseOneSessionMetadata()
        val stale = GatewayGeneration()
        val current = GatewayGeneration()
        metadata.resetWorkingStateRetainingPublishedSnapshot()
        metadata.bindGeneration(current)
        metadata.applyDvrAccess(stale, true)
        metadata.applyDvrAccess(current, false)
        metadata.publishServerFacts(stale, serverFacts(streaming = false))
        metadata.publishServerFacts(current, serverFacts(streaming = true))
        val awaiting = async { metadata.awaitChannelsAndTagsCurrent(current) }
        runCurrent()

        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(stale))
        runCurrent()
        assertFalse(awaiting.isCompleted)

        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(current))
        runCurrent()
        assertTrue(awaiting.isCompleted)
        assertEquals(CapabilityAccess.ALLOWED, metadata.capabilities(current).streaming)
        assertEquals(CapabilityAccess.DENIED, metadata.capabilities(current).dvrWrite)
    }

    private fun serverFacts(streaming: Boolean): GatewayServerFacts = GatewayServerFacts(
        serverName = null,
        serverVersion = null,
        webRoot = null,
        language = null,
        serverCapabilities = null,
        apiVersion = null,
        admin = null,
        streaming = streaming,
        dvr = null,
        failedDvr = null,
        anonymous = null,
        limitAll = null,
        limitDvr = null,
        limitStreaming = null,
        uiLevel = null,
        uiLanguage = null,
    )
}
