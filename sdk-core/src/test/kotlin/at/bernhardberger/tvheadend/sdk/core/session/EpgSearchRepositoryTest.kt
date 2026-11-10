@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgSearchRequest
import at.bernhardberger.tvheadend.sdk.core.EpgSearchResult
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.gateway.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.time.Instant

internal class EpgSearchRepositoryTest {
    @Test
    fun `pre cancelled search throws before returning an expired observation`() = runTest {
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        publishReady(metadata, generation)
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        metadata.bindGeneration(GatewayGeneration())
        val outcome = CompletableDeferred<Throwable?>()

        val caller = async {
            currentCoroutineContext().cancel()
            outcome.complete(runCatching {
                metadata.epgRepository.search(currentSession, EpgSearchRequest.create("query"))
            }.exceptionOrNull())
        }
        caller.join()
        assertTrue(outcome.await() is CancellationException)
    }

    @Test
    fun `search returns only remote immutable results without mutating EPG state`() = runTest {
        val calls = mutableListOf<Pair<GatewayGeneration, EpgSearchRequest>>()
        var gatewayResult: GatewayResult<List<GatewayEpgQueryEvent>> = GatewayResult.Ok(
            listOf(
                GatewayEpgQueryEvent(
                    id = EventId(2),
                    start = Instant.fromEpochSeconds(20),
                    stop = Instant.fromEpochSeconds(30),
                    title = "private-remote-title",
                ),
            ),
        )
        val commands = EpgSearchCommands { generation, request ->
            calls += generation to request
            gatewayResult
        }
        val metadata = PhaseOneSessionMetadata(searchCommands = commands)
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(
            MetadataEvent.EventAdded(
                generation,
                GatewayEpgEvent(
                    id = EventId(1),
                    channelId = null,
                    start = Instant.fromEpochSeconds(0),
                    stop = Instant.fromEpochSeconds(10),
                    title = "private-cached-title",
                ),
            ),
        )
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        publishReady(metadata, generation)
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val stateBeforeSearch = metadata.observation.value.epgState
        val request = EpgSearchRequest.create("private-query")

        val result = metadata.epgRepository.search(currentSession, request)
            as EpgSearchResult.Available

        assertEquals(listOf(2L), result.events.map { it.id.value })
        assertSame(currentSession, result.originatingSession)
        assertFalse(result.toString().contains("private"))
        assertThrows(UnsupportedOperationException::class.java) {
            (result.events as MutableList<*>).clear()
        }

        gatewayResult = GatewayResult.Ok(emptyList())
        val emptyResult = metadata.epgRepository.search(currentSession, request)
            as EpgSearchResult.Available
        assertTrue(emptyResult.events.isEmpty())
        assertSame(currentSession, emptyResult.originatingSession)
        gatewayResult = GatewayResult.Timeout
        assertEquals(
            EpgSearchResult.Failed(TvheadendFailure.TIMEOUT),
            metadata.epgRepository.search(currentSession, request),
        )
        assertEquals(List(3) { generation to request }, calls)
        assertSame(stateBeforeSearch, metadata.observation.value.epgState)
        assertEquals(
            listOf(1L),
            (metadata.observation.value.epgState as EpgRepositoryState.Current)
                .snapshot
                .events
                .map { it.id.value },
        )
        metadata.clearAllState()
        assertEquals(null, metadata.observation.value.currentSession)
        assertSame(currentSession, result.originatingSession)
    }

    @Test
    fun `search maps gateway failures and rejects expired observations before dispatch`() = runTest {
        var result: GatewayResult<List<GatewayEpgQueryEvent>> = GatewayResult.ServerRejected()
        var calls = 0
        val metadata = PhaseOneSessionMetadata(
            searchCommands = EpgSearchCommands { _, _ ->
                calls += 1
                result
            },
        )
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        publishReady(metadata, generation)
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val request = EpgSearchRequest.create("query")
        val mappings = listOf(
            GatewayResult.ServerRejected() to EpgSearchResult.Failed(TvheadendFailure.SERVER_REJECTED),
            GatewayResult.MalformedReply to EpgSearchResult.Failed(TvheadendFailure.MALFORMED_RESPONSE),
            GatewayResult.AccessDenied to EpgSearchResult.Failed(TvheadendFailure.ACCESS_DENIED),
            GatewayResult.ConnectionLimit to EpgSearchResult.Failed(TvheadendFailure.CONNECTION_LIMIT),
            GatewayResult.Timeout to EpgSearchResult.Failed(TvheadendFailure.TIMEOUT),
            GatewayResult.TransportUnavailable to EpgSearchResult.Failed(TvheadendFailure.TRANSPORT_UNAVAILABLE),
            GatewayResult.NotSupported to EpgSearchResult.Failed(TvheadendFailure.NOT_SUPPORTED),
        )

        mappings.forEach { (source, expected) ->
            result = source
            assertEquals(expected, metadata.epgRepository.search(currentSession, request))
        }
        assertEquals(mappings.size, calls)

        result = GatewayResult.Ok(
            listOf(
                GatewayEpgQueryEvent(
                    id = EventId(1),
                    start = Instant.fromEpochSeconds(2),
                    stop = Instant.fromEpochSeconds(1),
                ),
            ),
        )
        assertEquals(
            EpgSearchResult.Failed(TvheadendFailure.SERVER_REJECTED),
            metadata.epgRepository.search(currentSession, request),
        )

        metadata.bindGeneration(GatewayGeneration())
        assertEquals(
            EpgSearchResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED),
            metadata.epgRepository.search(currentSession, request),
        )
        assertEquals(mappings.size + 1, calls)
    }

    enum class MidflightTransition(val replace: Boolean, val retire: Boolean, val expected: TvheadendFailure) {
        REPLACEMENT(replace = true, retire = false, TvheadendFailure.CONNECTION_CHANGED),
        REPLACEMENT_THEN_RETIREMENT(replace = true, retire = true, TvheadendFailure.CONNECTION_CHANGED),
        RETIREMENT_ONLY(replace = false, retire = true, TvheadendFailure.TRANSPORT_UNAVAILABLE),
    }

    @ParameterizedTest
    @EnumSource(MidflightTransition::class)
    fun `search classifies midflight replacement and retirement`(transition: MidflightTransition) = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val metadata = PhaseOneSessionMetadata(
            searchCommands = EpgSearchCommands { _, _ ->
                entered.complete(Unit)
                release.await()
                GatewayResult.TransportUnavailable
            },
        )
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        publishReady(metadata, generation)
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val request = EpgSearchRequest.create("query")

        val search = async { metadata.epgRepository.search(currentSession, request) }
        entered.await()
        if (transition.replace) metadata.bindGeneration(GatewayGeneration())
        if (transition.retire) metadata.clearAllState()
        release.complete(Unit)
        assertEquals(EpgSearchResult.Failed(transition.expected), search.await(), "$transition")
    }

    @Test
    fun `caller cancellation takes precedence over a midflight generation replacement`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val metadata = PhaseOneSessionMetadata(
            searchCommands = EpgSearchCommands { _, _ ->
                entered.complete(Unit)
                try {
                    release.await()
                } catch (_: CancellationException) {
                    // Exercise the repository's post-command cancellation check.
                }
                GatewayResult.Ok(emptyList())
            },
        )
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        publishReady(metadata, generation)
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        val cancellation = CancellationException("private cancellation")

        val outcome = CompletableDeferred<Result<EpgSearchResult>>()
        val search = async {
            outcome.complete(runCatching { metadata.epgRepository.search(currentSession, EpgSearchRequest.create("query")) })
        }
        entered.await()
        metadata.bindGeneration(GatewayGeneration())
        search.cancel(cancellation)
        release.complete(Unit)

        search.join()
        val propagated = outcome.await().exceptionOrNull()
        assertTrue(propagated is CancellationException)
        requireNotNull(propagated)
        assertEquals(cancellation.message, propagated.message)
        assertTrue(propagated === cancellation || propagated.cause === cancellation)
    }

    @Test
    fun `search fences a generation replacement during result conversion`() = runTest {
        val replacement = GatewayGeneration()
        lateinit var metadata: PhaseOneSessionMetadata
        val events = TriggeringList(
            element = GatewayEpgQueryEvent(
                id = EventId(1),
                start = Instant.fromEpochSeconds(1),
                stop = Instant.fromEpochSeconds(2),
            ),
            onRead = {
                metadata.bindGeneration(replacement)
                metadata.clearAllState()
            },
        )
        metadata = PhaseOneSessionMetadata(
            searchCommands = EpgSearchCommands { _, _ -> GatewayResult.Ok(events) },
        )
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        publishReady(metadata, generation)
        val currentSession = requireNotNull(metadata.observation.value.currentSession)

        assertEquals(
            EpgSearchResult.Failed(TvheadendFailure.CONNECTION_CHANGED),
            metadata.epgRepository.search(currentSession, EpgSearchRequest.create("query")),
        )
    }

    @Test
    fun `caller cancellation during result conversion cannot settle a search result`() = runTest {
        val cancellation = CancellationException("private conversion cancellation")
        lateinit var searchJob: Job
        val events = TriggeringList(
            element = GatewayEpgQueryEvent(
                id = EventId(1),
                start = Instant.fromEpochSeconds(1),
                stop = Instant.fromEpochSeconds(2),
            ),
            onRead = { searchJob.cancel(cancellation) },
        )
        val metadata = PhaseOneSessionMetadata(
            searchCommands = EpgSearchCommands { _, _ -> GatewayResult.Ok(events) },
        )
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        publishReady(metadata, generation)
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        var settled: EpgSearchResult? = null
        var caught: CancellationException? = null

        val search = async {
            searchJob = currentCoroutineContext().job
            try {
                settled = metadata.epgRepository.search(
                    currentSession,
                    EpgSearchRequest.create("query"),
                )
            } catch (failure: CancellationException) {
                caught = failure
                throw failure
            }
        }
        try {
            search.await()
        } catch (_: CancellationException) {
            // Assert the exception observed inside the API boundary below.
        }

        assertEquals(null, settled)
        val propagated = requireNotNull(caught)
        assertEquals(cancellation.message, propagated.message)
        assertTrue(propagated === cancellation || propagated.cause === cancellation)
    }

    private fun publishReady(
        metadata: PhaseOneSessionMetadata,
        generation: GatewayGeneration,
    ) {
        metadata.publishSessionState(
            state = SessionState.Ready(
                ServerCapabilities.create(CapabilityAccess.UNKNOWN, CapabilityAccess.UNKNOWN),
            ),
            progressCapability = RecordingProgressCapability.UNKNOWN,
            generation = generation,
        )
    }

    private class TriggeringList<T>(
        private val element: T,
        private val onRead: () -> Unit,
    ) : AbstractList<T>() {
        private var triggered = false

        override val size: Int = 1

        override fun get(index: Int): T {
            require(index == 0)
            if (!triggered) {
                triggered = true
                onRead()
            }
            return element
        }
    }
}
