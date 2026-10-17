@file:OptIn(ExperimentalTvheadendApi::class, TvheadendTestingApi::class)

package at.bernhardberger.tvheadend.sdk.android

import at.bernhardberger.tvheadend.sdk.core.ArtworkContent
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoadResult
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoader
import at.bernhardberger.tvheadend.sdk.core.CachedArtworkReference
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
import at.bernhardberger.tvheadend.sdk.core.SessionCache
import at.bernhardberger.tvheadend.sdk.core.SessionCommandResult
import at.bernhardberger.tvheadend.sdk.core.SessionGenerationTestAuthority
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import at.bernhardberger.tvheadend.sdk.core.TvheadendTestResultFactory
import at.bernhardberger.tvheadend.sdk.core.TvheadendTestingApi
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class TvheadendArtworkTest {
    @Test
    fun `model identity follows session observation and artwork ID and redacts rendering`() {
        val loader = FakeArtworkLoader()
        val observation = currentObservation()
        val currentSession = requireNotNull(observation.currentSession)
        val session = sessionWith(loader, MutableStateFlow(observation))

        val direct = TvheadendArtwork.create(session, currentSession, ArtworkId(73))
        val other = TvheadendArtwork.create(session, currentSession, ArtworkId(74))
        val duplicate = TvheadendArtwork.create(session, currentSession, ArtworkId(73))

        assertEquals(direct, duplicate)
        assertEquals(direct.hashCode(), duplicate.hashCode())
        assertNotEquals(direct, other)
        assertNotEquals(
            direct,
            TvheadendArtwork.create(
                sessionWith(FakeArtworkLoader(), MutableStateFlow(observation)),
                currentSession,
                ArtworkId(73),
            ),
        )
        assertNotEquals(
            direct,
            TvheadendArtwork.create(
                session,
                requireNotNull(currentObservation().currentSession),
                ArtworkId(73),
            ),
        )
        assertEquals("TvheadendArtwork(<redacted>)", direct.toString())
        assertFalse(direct.toString().contains("73"))
    }

    @Test
    fun `component registration provides generation scoped keys without persistence`() {
        val loader = FakeArtworkLoader()
        val initialObservation = currentObservation()
        val observations = MutableStateFlow(initialObservation)
        val session = sessionWith(loader, observations)
        val initial = requireNotNull(initialObservation.currentSession)
        val first = TvheadendArtwork.create(session, initial, ArtworkId(2147483647))
        val duplicate = TvheadendArtwork.create(session, initial, ArtworkId(2147483647))
        val otherArtwork = TvheadendArtwork.create(session, initial, ArtworkId(2147483646))

        val firstKey = requireNotNull(first.memoryCacheKey())
        assertEquals(firstKey, duplicate.memoryCacheKey())
        assertNotEquals(firstKey, otherArtwork.memoryCacheKey())
        assertTrue(firstKey.matches(Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
        assertFalse(firstKey.contains("2147483647"))

        val replacementObservation = currentObservation()
        observations.value = replacementObservation
        val replacement = TvheadendArtwork.create(
            session,
            requireNotNull(replacementObservation.currentSession),
            ArtworkId(2147483647),
        )
        assertEquals(firstKey, first.memoryCacheKey())
        assertNotEquals(firstKey, replacement.memoryCacheKey())

        val registry = ComponentRegistry.Builder().addTvheadendArtwork().build()
        assertEquals(1, registry.keyers.size)
        assertEquals(1, registry.fetcherFactories.size)
    }

    @Test
    fun `persistent keys survive replacement owners and stale fetch still fails`() {
        val firstObservation = currentObservation()
        val observations = MutableStateFlow(firstObservation)
        val loader = FakeArtworkLoader().apply { persistentKey = "opaque-cache-key" }
        val first = TvheadendArtwork.create(
            sessionWith(loader, observations), requireNotNull(firstObservation.currentSession), ArtworkId(1),
        )
        val nextObservation = currentObservation()
        val restarted = TvheadendArtwork.create(
            sessionWith(FakeArtworkLoader().apply { persistentKey = "opaque-cache-key" }, MutableStateFlow(nextObservation)),
            requireNotNull(nextObservation.currentSession), ArtworkId(1),
        )
        assertEquals(first.memoryCacheKey(), restarted.memoryCacheKey())
        observations.value = nextObservation
        loader.persistentKey = "replacement-profile-key"
        assertEquals(first.memoryCacheKey(), restarted.memoryCacheKey())
        val failure = assertThrows(TvheadendArtworkLoadException::class.java) {
            runTest { TvheadendArtworkFetcher(first).fetch() }
        }
        assertEquals(TvheadendFailure.OBSERVATION_EXPIRED, failure.failure)
        assertNull(loader.lastId)
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
        val artwork = TvheadendArtwork.create(session, currentSession, ArtworkId(91))

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
    fun `cached models read only the captured cache reference before ready`() = runTest {
        val reference = TvheadendTestResultFactory.cachedArtwork(ServerProfile("fixture"), ArtworkId(5))
        val loader = FakeArtworkLoader().apply {
            cachedReference = reference
            cachedResult = ArtworkLoadResult.Available(ArtworkContent.create(byteArrayOf(5, 6)))
        }
        val observed = profileObservation(ServerProfile("fixture"))
        val session = sessionWith(loader, MutableStateFlow(observed))
        val uncached = sessionWith(FakeArtworkLoader(), MutableStateFlow(observed))
        assertNull(TvheadendArtwork.cached(uncached, observed, ArtworkId(5)))
        val artwork = requireNotNull(TvheadendArtwork.cached(session, observed, ArtworkId(5)))
        assertSame(observed, loader.lastObservation)
        assertEquals(artwork, TvheadendArtwork.cached(session, observed, ArtworkId(5)))
        assertEquals(reference.cacheKey, artwork.memoryCacheKey())
        assertEquals("TvheadendArtwork(<redacted>)", artwork.toString())

        val result = TvheadendArtworkFetcher(artwork).fetch() as SourceFetchResult
        try {
            assertSame(DataSource.DISK, result.dataSource)
            assertArrayEquals(byteArrayOf(5, 6), result.source.source().readByteArray())
        } finally {
            result.source.close()
        }
        assertEquals(reference, loader.lastReference)
        assertNull(loader.lastCurrentSession)

        loader.cachedResult = ArtworkLoadResult.Unavailable(TvheadendFailure.NOT_READY)
        val failure = runCatching { TvheadendArtworkFetcher(artwork).fetch() }.exceptionOrNull()
        assertSame(TvheadendFailure.NOT_READY, (failure as TvheadendArtworkLoadException).failure)
    }

    @Test
    fun `cached models never map another profile's artwork ids to the active profile`() = runTest {
        val active = ServerProfile("active.invalid")
        val loader = FakeArtworkLoader().apply {
            cachedReference = TvheadendTestResultFactory.cachedArtwork(active, ArtworkId(5))
        }
        val session = sessionWith(loader, MutableStateFlow(SessionObservation.create()))
        val previous = profileObservation(ServerProfile("previous.invalid"))

        assertNull(TvheadendArtwork.cached(session, previous, ArtworkId(5)))
        assertNull(TvheadendArtwork.cached(session, SessionObservation.create(), ArtworkId(5)))
        val current = requireNotNull(TvheadendArtwork.cached(session, profileObservation(active), ArtworkId(5)))
        assertEquals(loader.cachedReference?.cacheKey, current.memoryCacheKey())
        val later = requireNotNull(TvheadendArtwork.cached(session, profileObservation(active), ArtworkId(5)))
        assertEquals(current, later)
        assertEquals(current.hashCode(), later.hashCode())
    }

    @Test
    fun `delayed fetch retains its originating observation after replacement`() {
        val loader = FakeArtworkLoader(
            ArtworkLoadResult.Unavailable(TvheadendFailure.OBSERVATION_EXPIRED),
        )
        val initialObservation = currentObservation()
        val observations = MutableStateFlow(initialObservation)
        val session = sessionWith(loader, observations)
        val original = requireNotNull(initialObservation.currentSession)
        val artwork = TvheadendArtwork.create(session, original, ArtworkId(92))
        val replacementObservation = currentObservation()
        observations.value = replacementObservation
        val replacement = requireNotNull(replacementObservation.currentSession)
        assertNotEquals(original, replacement)

        val failure = assertThrows(TvheadendArtworkLoadException::class.java) {
            runTest { TvheadendArtworkFetcher(artwork).fetch() }
        }

        assertSame(TvheadendFailure.OBSERVATION_EXPIRED, failure.failure)
        assertEquals("Tvheadend artwork load failed", failure.message)
        assertNull(loader.lastCurrentSession)
    }

    @Test
    fun `typed load failures become safe Coil errors and cancellation propagates`() {
        val loader = FakeArtworkLoader(
            ArtworkLoadResult.Unavailable(TvheadendFailure.ACCESS_DENIED),
        )
        val observation = currentObservation()
        val session = sessionWith(loader, MutableStateFlow(observation))
        val artwork = TvheadendArtwork.create(
            session,
            requireNotNull(observation.currentSession),
            ArtworkId(27),
        )

        val failure = assertThrows(TvheadendArtworkLoadException::class.java) {
            runTest { TvheadendArtworkFetcher(artwork).fetch() }
        }
        assertSame(TvheadendFailure.ACCESS_DENIED, failure.failure)
        assertEquals("Tvheadend artwork load failed", failure.message)
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
    override val cache: SessionCache get() = unsupported()

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

private fun profileObservation(profile: ServerProfile): SessionObservation =
    SessionGenerationTestAuthority().apply { publish(SessionObservation.create(), profile) }.observation.value

private class FakeArtworkLoader(
    internal var result: ArtworkLoadResult =
        ArtworkLoadResult.Unavailable(TvheadendFailure.FILE_UNAVAILABLE),
) : ArtworkLoader {
    internal var lastId: ArtworkId? = null
    internal var lastCurrentSession: CurrentSessionObservation? = null
    internal var cancellation: CancellationException? = null
    internal var persistentKey: String? = null
    internal var cachedReference: CachedArtworkReference? = null
    internal var cachedResult: ArtworkLoadResult = ArtworkLoadResult.Unavailable(TvheadendFailure.NOT_READY)
    internal var lastReference: CachedArtworkReference? = null

    internal var lastObservation: SessionObservation? = null

    // Like a real session: only the active profile's observations resolve.
    override fun cachedArtwork(observation: SessionObservation, artworkId: ArtworkId): CachedArtworkReference? {
        lastObservation = observation
        return TvheadendTestResultFactory.cachedArtwork(observation, artworkId)
            ?.takeIf { reference -> reference == cachedReference }
    }

    override suspend fun loadCachedArtwork(reference: CachedArtworkReference): ArtworkLoadResult {
        lastReference = reference
        return cachedResult
    }

    override fun cacheKey(currentSession: CurrentSessionObservation, artworkId: ArtworkId): String? = persistentKey

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
