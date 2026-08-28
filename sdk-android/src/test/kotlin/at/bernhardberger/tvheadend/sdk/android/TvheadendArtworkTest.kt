package at.bernhardberger.tvheadend.sdk.android

import at.bernhardberger.tvheadend.sdk.core.ArtworkContent
import at.bernhardberger.tvheadend.sdk.core.ArtworkFailure
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoadResult
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoader
import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSnapshot
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import coil3.decode.DataSource
import coil3.fetch.SourceFetchResult
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class TvheadendArtworkTest {
    @Test
    fun `model accepts only HTSP image cache selectors and redacts rendering`() {
        val loader = FakeArtworkLoader()
        val currentSession = currentSession()

        val direct = TvheadendArtwork.create(loader, currentSession, "imagecache/73")
        val legacy = TvheadendArtwork.create(loader, currentSession, "/imagecache/74")
        val duplicate = TvheadendArtwork.create(loader, currentSession, "imagecache/73")
        val fromSession = TvheadendArtwork.create(
            sessionWith(loader),
            currentSession,
            "imagecache/75",
        )

        assertNotNull(direct)
        assertNotNull(legacy)
        assertNotNull(fromSession)
        assertEquals(direct, duplicate)
        assertEquals(direct.hashCode(), duplicate.hashCode())
        assertNotEquals(direct, legacy)
        assertNotEquals(
            direct,
            TvheadendArtwork.create(FakeArtworkLoader(), currentSession, "imagecache/73"),
        )
        assertNotEquals(direct, TvheadendArtwork.create(loader, currentSession(), "imagecache/73"))
        assertEquals("TvheadendArtwork(<redacted>)", direct.toString())
        listOf(
            null,
            "",
            "imagecache/",
            "imagecache/0",
            "imagecache/-1",
            "imagecache/1/2",
            "//imagecache/1",
            "https://private-host/imagecache/1",
        ).forEach { source ->
            assertNull(TvheadendArtwork.create(loader, currentSession, source))
        }
        assertFalse(direct.toString().contains("73"))
    }

    @Test
    fun `fetcher returns encoded authenticated bytes to Coil without a cache key`() = runTest {
        val payload = byteArrayOf(1, 2, 3, 4)
        val loader = FakeArtworkLoader(
            ArtworkLoadResult.Available(ArtworkContent.create(payload)),
        )
        payload.fill(9)
        val currentSession = currentSession()
        val artwork = requireNotNull(
            TvheadendArtwork.create(loader, currentSession, "imagecache/91"),
        )

        val result = TvheadendArtworkFetcher(artwork).fetch() as SourceFetchResult

        assertSame(DataSource.NETWORK, result.dataSource)
        assertNull(result.mimeType)
        try {
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), result.source.source().readByteArray())
        } finally {
            result.source.close()
        }
        assertEquals(ArtworkId(91), loader.lastId)
        assertSame(currentSession, loader.lastCurrentSession)
        assertNotNull(createTvheadendArtworkFetcherFactory())
    }

    @Test
    fun `delayed fetch retains its originating observation after replacement`() {
        val loader = FakeArtworkLoader(
            ArtworkLoadResult.Unavailable(ArtworkFailure.OBSERVATION_EXPIRED),
        )
        val original = currentSession()
        val artwork = requireNotNull(
            TvheadendArtwork.create(loader, original, "imagecache/92"),
        )
        val replacement = currentSession()
        assertNotEquals(original, replacement)

        val failure = assertThrows(IOException::class.java) {
            runTest { TvheadendArtworkFetcher(artwork).fetch() }
        }

        assertEquals("TVHeadend artwork load failed: OBSERVATION_EXPIRED", failure.message)
        assertSame(original, loader.lastCurrentSession)
    }

    @Test
    fun `typed load failures become safe Coil errors and cancellation propagates`() {
        val loader = FakeArtworkLoader(
            ArtworkLoadResult.Unavailable(ArtworkFailure.ACCESS_DENIED),
        )
        val artwork = requireNotNull(
            TvheadendArtwork.create(loader, currentSession(), "imagecache/27"),
        )

        val failure = assertThrows(IOException::class.java) {
            runTest { TvheadendArtworkFetcher(artwork).fetch() }
        }
        assertEquals("TVHeadend artwork load failed: ACCESS_DENIED", failure.message)
        assertFalse(failure.toString().contains("imagecache"))
        assertFalse(failure.toString().contains("27"))

        val cancellation = CancellationException("private cancellation")
        loader.cancellation = cancellation
        var caught: CancellationException? = null
        try {
            runTest { TvheadendArtworkFetcher(artwork).fetch() }
        } catch (cancelled: CancellationException) {
            caught = cancelled
        }
        assertSame(cancellation, caught)
    }

    private fun currentSession(): CurrentSessionObservation = requireNotNull(
        SessionObservation.create(
            sessionState = SessionState.Ready(
                ServerCapabilities.create(CapabilityAccess.UNKNOWN, CapabilityAccess.UNKNOWN),
            ),
            channelState = ChannelRepositoryState.Current(ChannelCatalog.create()),
            epgState = EpgRepositoryState.Current(EpgSnapshot.create()),
            dvrState = DvrRepositoryState.Current(DvrSnapshot.create()),
        ).currentSession,
    )
}

@Suppress("UNCHECKED_CAST")
private fun sessionWith(loader: ArtworkLoader): TvheadendSession = Proxy.newProxyInstance(
    TvheadendSession::class.java.classLoader,
    arrayOf(TvheadendSession::class.java),
) { _, method, _ ->
    if (method.name == "getArtwork") loader else error("Unexpected session call")
} as TvheadendSession

private class FakeArtworkLoader(
    internal var result: ArtworkLoadResult =
        ArtworkLoadResult.Unavailable(ArtworkFailure.FILE_UNAVAILABLE),
) : ArtworkLoader {
    internal var lastId: ArtworkId? = null
    internal var lastCurrentSession: CurrentSessionObservation? = null
    internal var cancellation: CancellationException? = null

    override suspend fun loadArtwork(
        currentSession: CurrentSessionObservation,
        artworkId: ArtworkId,
    ): ArtworkLoadResult {
        cancellation?.let { throw it }
        lastCurrentSession = currentSession
        lastId = artworkId
        return result
    }
}
