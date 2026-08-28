package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.AutorecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrConfigurationsState
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrMutationResult
import at.bernhardberger.tvheadend.sdk.core.DvrRepository
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSchedule
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.RecordingRuleChannel
import at.bernhardberger.tvheadend.sdk.core.ServerAuthentication
import at.bernhardberger.tvheadend.sdk.core.ServerProfile
import at.bernhardberger.tvheadend.sdk.core.SessionCommandResult
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleId
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import at.bernhardberger.tvheadend.sdk.core.createTvheadendSession
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

@Tag("live-dvr")
@Timeout(value = 20, unit = TimeUnit.MINUTES)
@EnabledIfEnvironmentVariable(named = NODVR_CREDENTIALS_ENV, matches = ".+")
internal class DvrRealServerDenialVerificationTest {
    @Test
    fun `live HTSP DVR writes stay denied without write proof`() = runBlocking {
        val credentials = liveDenialCredentials()
        val marker = "sdk-p35-deny-${UUID.randomUUID().toString().take(8)}"
        val session = createTvheadendSession()
        val createdEntries = linkedSetOf<DvrEntryId>()
        val createdAutorec = linkedSetOf<AutorecRuleId>()
        val createdTimerec = linkedSetOf<TimerecRuleId>()
        var failed = false
        try {
            assertEquals(SessionCommandResult.STARTED, session.connect(credentials.profile))
            val readyObservation = withTimeout(12.minutes) {
                session.observation.first { observation ->
                    observation.sessionState is SessionState.Ready ||
                        observation.sessionState is SessionState.Unavailable
                }
            }
            val ready = readyObservation.sessionState
            assertTrue(ready is SessionState.Ready, "Denial verification never reached ready")
            val capabilities = (ready as SessionState.Ready).capabilities
            assertEquals(
                CapabilityAccess.DENIED,
                capabilities.dvrWrite,
                "Write access was not negatively proven",
            )
            credentials.forbidLeak(capabilities.toString())
            credentials.forbidLeak(ready.toString())
            val currentSession = requireNotNull(session.observation.value.currentSession)

            val dvr = session.dvrRepository
            withTimeout(2.minutes) {
                session.observation.first { observation ->
                    observation.dvrConfigurationsState == DvrConfigurationsState.Denied
                }
            }
            assertEquals(
                DvrConfigurationsState.Denied,
                session.observation.value.dvrConfigurationsState,
                "Configuration retrieval did not prove recorder denial",
            )
            credentials.forbidLeak(session.observation.value.dvrConfigurationsState.toString())
            credentials.forbidLeak(session.observation.value.dvrState.toString())

            val channelId =
                (session.observation.value.channelState as ChannelRepositoryState.Current)
                    .catalog.channels.firstOrNull()?.id
            assertTrue(channelId != null, "Denial verification published no channel")
            val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)

            dvr.scheduleEntry(
                currentSession,
                DvrScheduleRequest(
                    schedule = DvrSchedule.ExplicitTime(
                        channelId = requireNotNull(channelId),
                        start = now + 1.days,
                        stop = now + 1.days + 5.minutes,
                    ),
                    title = marker,
                ),
            ).remembered(createdEntries).requireAccessDenied()
            assertTrue(
                currentDenialDvrSnapshot(session).entries.none { entry -> entry.title == marker },
                "Denied schedule published a recording",
            )

            dvr.createAutorecRule(
                currentSession,
                AutorecRuleCreate(
                    title = marker,
                    channel = RecordingRuleChannel.AllChannels,
                    enabled = false,
                    name = marker,
                    comment = marker,
                ),
            ).remembered(createdAutorec).requireAccessDenied()
            assertTrue(
                currentDenialDvrSnapshot(session).autorecRules.none { rule ->
                    rule.name == marker || rule.title == marker
                },
                "Denied autorec create published a rule",
            )

            val afterDenials = session.observation.value.sessionState
            assertTrue(afterDenials is SessionState.Ready, "Session left ready after denied writes")
            assertEquals(
                CapabilityAccess.DENIED,
                (afterDenials as SessionState.Ready).capabilities.dvrWrite,
                "Denied write proof was lost after rejected mutations",
            )
        } catch (error: Throwable) {
            failed = true
            throw error
        } finally {
            try {
                withContext(NonCancellable) {
                    try {
                        cleanupDeniedLeftovers(session, createdEntries, createdAutorec, createdTimerec)
                    } finally {
                        session.shutdown()
                    }
                }
            } catch (cleanupError: Throwable) {
                if (cleanupError is CancellationException) throw cleanupError
                if (!failed) throw cleanupError
            }
        }
    }
}

private const val NODVR_CREDENTIALS_ENV: String = "TVHEADEND_SOAK_NODVR_CREDENTIALS_FILE"

private class LiveDenialCredentials(
    val profile: ServerProfile,
    private val host: String,
    private val username: String,
    private val password: String,
) {
    fun forbidLeak(text: String) {
        assertFalse(
            text.contains(host) || text.contains(username) || text.contains(password),
            "DVR rendering leaked a credential field",
        )
    }
}

private fun liveDenialCredentials(): LiveDenialCredentials {
    val path = System.getenv(NODVR_CREDENTIALS_ENV)
    assertTrue(!path.isNullOrBlank(), "DVR denial credentials are not provisioned")
    val file = File(requireNotNull(path))
    assertTrue(file.isFile, "DVR denial credentials are not provisioned")
    val text = try {
        file.readText()
    } catch (_: Exception) {
        throw AssertionError("DVR denial credentials could not be read")
    }
    return try {
        val host = jsonString(text, "host")
        val username = jsonString(text, "username")
        val password = jsonString(text, "password")
        LiveDenialCredentials(
            profile = ServerProfile(
                host = host,
                port = jsonInt(text, "htsp_port"),
                authentication = ServerAuthentication.Password(
                    username = username,
                    password = password,
                ),
            ),
            host = host,
            username = username,
            password = password,
        )
    } catch (_: Exception) {
        throw AssertionError("DVR denial credentials are invalid")
    }
}

private fun <T> DvrMutationResult<T>.remembered(created: MutableSet<T>): DvrMutationResult<T> {
    when (this) {
        is DvrMutationResult.Confirmed -> created += value
        is DvrMutationResult.AcceptedButUnconfirmed -> created += value
        else -> Unit
    }
    return this
}

private fun DvrMutationResult<*>.requireAccessDenied() {
    when (this) {
        DvrMutationResult.AccessDenied -> Unit
        is DvrMutationResult.Confirmed,
        is DvrMutationResult.AcceptedButUnconfirmed,
        -> throw AssertionError("DVR mutation was accepted without write proof")
        else -> throw AssertionError("DVR mutation did not prove access denial")
    }
}

private suspend fun cleanupDeniedLeftovers(
    session: TvheadendSession,
    entries: Set<DvrEntryId>,
    autorec: Set<AutorecRuleId>,
    timerec: Set<TimerecRuleId>,
) {
    val dvr: DvrRepository = session.dvrRepository
    val currentSession = requireNotNull(session.observation.value.currentSession)
    var leftover = false
    entries.forEach { id ->
        val existing = currentDenialDvrSnapshot(session).entries.firstOrNull { entry -> entry.id == id }
        if (existing?.state == DvrEntryState.RECORDING) {
            dvr.stopEntry(currentSession, id)
        }
        dvr.deleteEntry(currentSession, id)
        if (currentDenialDvrSnapshot(session).entries.any { entry -> entry.id == id }) {
            leftover = true
        }
    }
    autorec.forEach { id ->
        dvr.deleteAutorecRule(currentSession, id)
        if (currentDenialDvrSnapshot(session).autorecRules.any { rule -> rule.id == id }) {
            leftover = true
        }
    }
    timerec.forEach { id ->
        dvr.deleteTimerecRule(currentSession, id)
        if (currentDenialDvrSnapshot(session).timerecRules.any { rule -> rule.id == id }) {
            leftover = true
        }
    }
    assertFalse(leftover, "Denial verification left created objects")
}

private fun currentDenialDvrSnapshot(session: TvheadendSession) =
    (session.observation.value.dvrState as DvrRepositoryState.Current).snapshot

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
