@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.ServerAuthentication
import at.bernhardberger.tvheadend.sdk.core.ServerProfile
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.gateway.htsp.HtspProtocolGateway
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

@Tag("live-soak")
@Timeout(value = 35, unit = TimeUnit.MINUTES)
@EnabledIfEnvironmentVariable(named = CREDENTIALS_ENV, matches = ".+")
internal class EpgSoakVerificationTest {
    @Test
    fun `live HTSP warmup then accelerated drain keeps known coverage`() = runBlocking {
        val profile = soakProfile()
        val clock = AdjustableClock(Instant.fromEpochSeconds(Clock.System.now().epochSeconds))
        val gateway = HtspProtocolGateway(Dispatchers.IO)
        val metadata = PhaseOneSessionMetadata()
        val owner = ConnectionOwner(
            gateway = gateway,
            metadata = metadata,
            children = PlaybackSessionChildren(
                gateway = gateway,
                metadata = metadata,
                dispatcher = Dispatchers.Default,
                clock = clock,
            ),
            defaultDispatcher = Dispatchers.Default,
            backoff = ExponentialReconnectBackoff(nextJitter = { 0.5 }),
        )

        try {
            owner.connect(profile)
            val connected = withTimeout(12.minutes) {
                owner.state.first { state ->
                    state is SessionState.Ready || state is SessionState.Unavailable
                }
            }
            assertTrue(connected is SessionState.Ready, "Live EPG soak never reached ready")
            val origin = clock.now()
            val warmed = currentSnapshot(owner)
            assertTrue(warmed.coverages.isNotEmpty(), "Live EPG soak published no channel coverage")
            assertTrue(
                warmed.coverages.all { coverage -> coverage.queriedTo != null },
                "Live EPG soak reached ready without a successful query horizon",
            )
            val warmedChannels = warmed.coverages.map { coverage -> coverage.channelId }.toSet()
            val queriedBefore = warmed.coverages.associate { coverage ->
                coverage.channelId to requireNotNull(coverage.queriedTo)
            }
            val drainCutoff = origin + 6.hours
            val drainCandidates = warmed.events.filter { event -> event.stop < drainCutoff }
                .map { event -> event.id }
                .toSet()
            assertTrue(
                drainCandidates.isNotEmpty(),
                "Accelerated drain had no events that should leave retention",
            )

            val lostHorizon = AtomicBoolean(false)
            val monitor = launch(start = CoroutineStart.UNDISPATCHED) {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    owner.state.collect { state ->
                        if (state !is SessionState.Ready) lostHorizon.set(true)
                    }
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    owner.epgRepository.state.collect { state ->
                        val current = state as? EpgRepositoryState.Current
                        val coverages = current?.snapshot?.coverages.orEmpty()
                        val missingHorizon = warmedChannels.any { channelId ->
                            coverages.firstOrNull { coverage -> coverage.channelId == channelId }
                                ?.knownTo == null
                        }
                        if (current == null || missingHorizon) {
                            lostHorizon.set(true)
                        }
                    }
                }
            }
            try {
                delay(5.minutes)
                clock.advance(12.hours)
                delay(10.minutes + 2.seconds)
            } finally {
                monitor.cancelAndJoin()
            }

            assertFalse(lostHorizon.get(), "Live EPG soak published a transient unknown horizon")
            assertTrue(owner.state.value is SessionState.Ready, "Live EPG soak left ready after drain")
            val drained = currentSnapshot(owner)
            val drainedChannels = drained.coverages.map { coverage -> coverage.channelId }.toSet()
            assertTrue(
                drainedChannels.containsAll(warmedChannels),
                "Channel coverage disappeared after accelerated drain",
            )
            assertTrue(
                drained.events.none { event -> event.id in drainCandidates },
                "Events that fell outside retention remained after accelerated drain",
            )
            assertTrue(
                drained.coverages.none { coverage -> coverage.isEmpty && coverage.knownTo == null },
                "Retained coverage drain produced an unknown EPG horizon",
            )
            drained.coverages.forEach { coverage ->
                val previous = queriedBefore[coverage.channelId] ?: return@forEach
                val current = coverage.queriedTo
                assertTrue(
                    current != null && current >= previous,
                    "Successful queried horizon receded after accelerated drain",
                )
            }
        } finally {
            owner.shutdown()
        }
    }
}

private const val CREDENTIALS_ENV: String = "TVHEADEND_SOAK_CREDENTIALS_FILE"

private fun soakProfile(): ServerProfile {
    val path = System.getenv(CREDENTIALS_ENV)
    assertTrue(!path.isNullOrBlank(), "Soak credentials are not provisioned")
    val file = File(requireNotNull(path))
    assertTrue(file.isFile, "Soak credentials are not provisioned")
    val text = try {
        file.readText()
    } catch (_: Exception) {
        throw AssertionError("Soak credentials could not be read")
    }
    return try {
        ServerProfile(
            host = jsonString(text, "host"),
            port = jsonInt(text, "htsp_port"),
            authentication = ServerAuthentication.Password(
                username = jsonString(text, "username"),
                password = jsonString(text, "password"),
            ),
        )
    } catch (_: Exception) {
        throw AssertionError("Soak credentials are invalid")
    }
}

private fun currentSnapshot(owner: ConnectionOwner) =
    (owner.epgRepository.state.value as EpgRepositoryState.Current).snapshot

private fun jsonString(text: String, name: String): String {
    val match = Regex(""""$name"\s*:\s*"((?:\\.|[^"\\])*)"""").find(text)
        ?: error("missing field")
    return match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
}

private fun jsonInt(text: String, name: String): Int {
    val match = Regex(""""$name"\s*:\s*(-?\d+)""").find(text)
        ?: error("missing field")
    return match.groupValues[1].toInt()
}

private class AdjustableClock(
    initial: Instant,
) : Clock {
    private val lock = Any()
    private var current = initial

    override fun now(): Instant = synchronized(lock) { current }

    fun advance(delta: Duration) {
        synchronized(lock) { current += delta }
    }
}
