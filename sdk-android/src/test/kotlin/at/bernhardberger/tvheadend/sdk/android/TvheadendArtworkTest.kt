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
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrRepository
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSnapshot
import at.bernhardberger.tvheadend.sdk.core.EpgRepository
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.PlaybackBinding
import at.bernhardberger.tvheadend.sdk.core.PlaybackBindingResult
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.ServerProfile
import at.bernhardberger.tvheadend.sdk.core.SessionCommandResult
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import coil3.ComponentRegistry
import coil3.decode.DataSource
import coil3.fetch.SourceFetchResult
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class TvheadendArtworkTest {
    @Test
    fun `model accepts only HTSP image cache selectors and redacts rendering`() {
        val loader = FakeArtworkLoader()
        val observation = currentObservation()
        val currentSession = requireNotNull(observation.currentSession)
        val session = sessionWith(loader, MutableStateFlow(observation))

        val direct = TvheadendArtwork.create(session, currentSession, "imagecache/73")
        val legacy = TvheadendArtwork.create(session, currentSession, "/imagecache/74")
        val duplicate = TvheadendArtwork.create(session, currentSession, "imagecache/73")

        assertNotNull(direct)
        assertNotNull(legacy)
        assertEquals(direct, duplicate)
        assertEquals(direct.hashCode(), duplicate.hashCode())
        assertNotEquals(direct, legacy)
        assertNotEquals(
            direct,
            TvheadendArtwork.create(
                sessionWith(FakeArtworkLoader(), MutableStateFlow(observation)),
                currentSession,
                "imagecache/73",
            ),
        )
        assertNotEquals(
            direct,
            TvheadendArtwork.create(
                session,
                requireNotNull(currentObservation().currentSession),
                "imagecache/73",
            ),
        )
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
            assertNull(TvheadendArtwork.create(session, currentSession, source))
        }
        assertFalse(direct.toString().contains("73"))
    }

    @Test
    fun `component registration provides generation scoped current only memory keys`() {
        val loader = FakeArtworkLoader()
        val initialObservation = currentObservation()
        val observations = MutableStateFlow(initialObservation)
        val session = sessionWith(loader, observations)
        val initial = requireNotNull(initialObservation.currentSession)
        val first = requireNotNull(
            TvheadendArtwork.create(session, initial, "imagecache/2147483647"),
        )
        val duplicate = requireNotNull(
            TvheadendArtwork.create(session, initial, "/imagecache/2147483647"),
        )
        val otherArtwork = requireNotNull(
            TvheadendArtwork.create(session, initial, "imagecache/2147483646"),
        )

        val firstKey = requireNotNull(first.memoryCacheKey())
        assertEquals(firstKey, duplicate.memoryCacheKey())
        assertNotEquals(firstKey, otherArtwork.memoryCacheKey())
        assertTrue(firstKey.matches(Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
        assertFalse(firstKey.contains("2147483647"))

        val replacementObservation = currentObservation()
        observations.value = replacementObservation
        val replacement = requireNotNull(
            TvheadendArtwork.create(
                session,
                requireNotNull(replacementObservation.currentSession),
                "imagecache/2147483647",
            ),
        )
        assertNull(first.memoryCacheKey())
        assertNotEquals(firstKey, replacement.memoryCacheKey())

        val registry = ComponentRegistry.Builder().addTvheadendArtwork().build()
        assertEquals(1, registry.keyers.size)
        assertEquals(1, registry.fetcherFactories.size)
    }

    @Test
    fun `fetcher returns stream only authenticated bytes without persistent cache identity`() = runTest {
        val payload = byteArrayOf(1, 2, 3, 4)
        val loader = FakeArtworkLoader(
            ArtworkLoadResult.Available(ArtworkContent.create(payload)),
        )
        payload.fill(9)
        val observation = currentObservation()
        val currentSession = requireNotNull(observation.currentSession)
        val session = sessionWith(loader, MutableStateFlow(observation))
        val artwork = requireNotNull(
            TvheadendArtwork.create(session, currentSession, "imagecache/91"),
        )

        val result = TvheadendArtworkFetcher(artwork).fetch() as SourceFetchResult

        assertSame(DataSource.NETWORK, result.dataSource)
        assertNull(result.mimeType)
        assertNull(result.source.fileOrNull())
        val temporaryFile = result.source.file()
        assertTrue(FileSystem.SYSTEM.exists(temporaryFile))
        try {
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), result.source.source().readByteArray())
        } finally {
            result.source.close()
        }
        assertFalse(FileSystem.SYSTEM.exists(temporaryFile))
        assertEquals(ArtworkId(91), loader.lastId)
        assertSame(currentSession, loader.lastCurrentSession)
    }

    @Test
    fun `delayed fetch retains its originating observation after replacement`() {
        val loader = FakeArtworkLoader(
            ArtworkLoadResult.Unavailable(ArtworkFailure.OBSERVATION_EXPIRED),
        )
        val initialObservation = currentObservation()
        val observations = MutableStateFlow(initialObservation)
        val session = sessionWith(loader, observations)
        val original = requireNotNull(initialObservation.currentSession)
        val artwork = requireNotNull(
            TvheadendArtwork.create(session, original, "imagecache/92"),
        )
        val replacementObservation = currentObservation()
        observations.value = replacementObservation
        val replacement = requireNotNull(replacementObservation.currentSession)
        assertNotEquals(original, replacement)

        val failure = assertThrows(TvheadendArtworkLoadException::class.java) {
            runTest { TvheadendArtworkFetcher(artwork).fetch() }
        }

        assertSame(ArtworkFailure.OBSERVATION_EXPIRED, failure.failure)
        assertEquals("TVHeadend artwork load failed", failure.message)
        assertSame(original, loader.lastCurrentSession)
    }

    @Test
    fun `typed load failures become safe Coil errors and cancellation propagates`() {
        val loader = FakeArtworkLoader(
            ArtworkLoadResult.Unavailable(ArtworkFailure.ACCESS_DENIED),
        )
        val observation = currentObservation()
        val session = sessionWith(loader, MutableStateFlow(observation))
        val artwork = requireNotNull(
            TvheadendArtwork.create(
                session,
                requireNotNull(observation.currentSession),
                "imagecache/27",
            ),
        )

        val failure = assertThrows(TvheadendArtworkLoadException::class.java) {
            runTest { TvheadendArtworkFetcher(artwork).fetch() }
        }
        assertSame(ArtworkFailure.ACCESS_DENIED, failure.failure)
        assertEquals("TVHeadend artwork load failed", failure.message)
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

    private fun currentObservation(): SessionObservation = SessionObservation.create(
        sessionState = SessionState.Ready(
            ServerCapabilities.create(CapabilityAccess.UNKNOWN, CapabilityAccess.UNKNOWN),
        ),
        channelState = ChannelRepositoryState.Current(ChannelCatalog.create()),
        epgState = EpgRepositoryState.Current(EpgSnapshot.create()),
        dvrState = DvrRepositoryState.Current(DvrSnapshot.create()),
    )
}

private fun sessionWith(
    loader: ArtworkLoader,
    observation: StateFlow<SessionObservation>,
): TvheadendSession = FakeArtworkSession(loader, observation)

private class FakeArtworkSession(
    override val artwork: ArtworkLoader,
    override val observation: StateFlow<SessionObservation>,
) : TvheadendSession {
    override val epgRepository: EpgRepository get() = unsupported()
    override val dvrRepository: DvrRepository get() = unsupported()

    override fun bindLivePlayback(
        currentSession: CurrentSessionObservation,
        channelId: ChannelId,
    ): PlaybackBindingResult<PlaybackBinding.Live> = unsupported()

    override fun bindRecordingPlayback(
        currentSession: CurrentSessionObservation,
        recordingId: DvrEntryId,
    ): PlaybackBindingResult<PlaybackBinding.Recording> = unsupported()

    override suspend fun connect(profile: ServerProfile): SessionCommandResult = unsupported()

    override suspend fun retry(): SessionCommandResult = unsupported()

    override suspend fun disconnect(): Unit = unsupported()

    override suspend fun shutdown(): Unit = unsupported()

    private fun <T> unsupported(): T = error("Unexpected session call")
}

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
