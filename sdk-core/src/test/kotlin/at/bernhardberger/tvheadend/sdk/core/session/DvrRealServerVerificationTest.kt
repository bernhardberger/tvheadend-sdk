@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.AutorecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrConfigurationsState
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpaceState
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrEntryUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrMutationResult
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackExit
import at.bernhardberger.tvheadend.sdk.core.DvrProgressPolicy
import at.bernhardberger.tvheadend.sdk.core.DvrProgressResult
import at.bernhardberger.tvheadend.sdk.core.DvrRepository
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSnapshot
import at.bernhardberger.tvheadend.sdk.core.DvrSchedule
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.PlaybackBindingResult
import at.bernhardberger.tvheadend.sdk.core.RecordingRuleChannel
import at.bernhardberger.tvheadend.sdk.core.ServerAuthentication
import at.bernhardberger.tvheadend.sdk.core.ServerProfile
import at.bernhardberger.tvheadend.sdk.core.SessionCommandResult
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleId
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleUpdate
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
@EnabledIfEnvironmentVariable(named = CREDENTIALS_ENV, matches = ".+")
internal class DvrRealServerVerificationTest {
    @Test
    fun `live HTSP DVR workflows round trip with proven write access`() = runBlocking {
        val credentials = liveCredentials()
        val marker = "sdk-p35-${UUID.randomUUID().toString().take(8)}"
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
            assertTrue(ready is SessionState.Ready, "Live DVR verification never reached ready")
            val capabilities = (ready as SessionState.Ready).capabilities
            assertEquals(
                CapabilityAccess.ALLOWED,
                capabilities.dvrWrite,
                "Write access was not positively proven",
            )
            credentials.forbidLeak(capabilities.toString())
            credentials.forbidLeak(ready.toString())

            val dvr = session.dvrRepository
            val currentSession = requireNotNull(readyObservation.currentSession)
            assertTrue(
                readyObservation.dvrState is DvrRepositoryState.Current,
                "DVR snapshot was not current after ready",
            )
            withTimeout(2.minutes) {
                session.observation.first { observation ->
                    observation.dvrConfigurationsState is DvrConfigurationsState.Current
                }
            }
            withTimeout(2.minutes) {
                session.observation.first { observation ->
                    observation.dvrDiskSpaceState is DvrDiskSpaceState.Current
                }
            }
            val enrichedObservation = session.observation.value
            credentials.forbidLeak(enrichedObservation.dvrState.toString())
            credentials.forbidLeak(enrichedObservation.dvrConfigurationsState.toString())
            credentials.forbidLeak(enrichedObservation.dvrDiskSpaceState.toString())
            val disk =
                (enrichedObservation.dvrDiskSpaceState as DvrDiskSpaceState.Current).diskSpace
            assertTrue(disk.totalBytes >= 0L, "Disk total was not a signed counter")
            assertTrue(disk.freeBytes >= 0L, "Disk free was not a signed counter")

            val channelId =
                (session.observation.value.channelState as ChannelRepositoryState.Current)
                    .catalog.channels.firstOrNull()?.id
            assertTrue(channelId != null, "Live DVR verification published no channel")
            val configId =
                (enrichedObservation.dvrConfigurationsState as DvrConfigurationsState.Current)
                    .configurations.firstOrNull()?.id
            val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)

            val scheduledId = dvr.scheduleEntry(
                currentSession,
                DvrScheduleRequest(
                    schedule = DvrSchedule.ExplicitTime(
                        channelId = requireNotNull(channelId),
                        start = now + 1.days,
                        stop = now + 1.days + 5.minutes,
                    ),
                    configId = configId,
                    title = marker,
                ),
            ).confirmedEntry(createdEntries)
            val scheduled = requireNotNull(
                session.observation.value.dvrEntry(scheduledId),
            )
            assertEquals(DvrEntryState.SCHEDULED, scheduled.state)
            credentials.forbidLeak(scheduled.toString())

            dvr.updateEntry(
                currentSession,
                scheduledId,
                DvrEntryUpdate(comment = marker),
            ).requireConfirmed()
            withTimeout(2.minutes) {
                session.observation.first { observation ->
                    observation.dvrEntry(scheduledId)?.comment == marker
                }
            }
            val updated = requireNotNull(
                session.observation.value.dvrEntry(scheduledId),
            )
            assertEquals(marker, updated.comment)

            dvr.cancelEntry(currentSession, scheduledId).requireConfirmed()
            val cancelled = withTimeout(2.minutes) {
                session.observation.first { observation ->
                    val entry = observation.dvrEntry(scheduledId)
                    entry == null ||
                        (entry.state != DvrEntryState.SCHEDULED &&
                            entry.state != DvrEntryState.RECORDING)
                }.dvrEntry(scheduledId)
            }
            if (cancelled == null) {
                createdEntries.remove(scheduledId)
            }

            val programme = currentEvents(session).firstOrNull { event ->
                event.channelId != null &&
                    event.dvrEntryId == null &&
                    event.start >= now + 30.minutes
            }
            assertTrue(programme != null, "Programme schedule had no future event")
            val programmeId = dvr.scheduleEntry(
                currentSession,
                DvrScheduleRequest(
                    schedule = DvrSchedule.Programme(requireNotNull(programme).id),
                    configId = configId,
                    title = marker,
                ),
            ).confirmedEntry(createdEntries)
            assertTrue(
                session.observation.value.dvrEntry(programmeId) != null,
                "Programme schedule was not published",
            )
            dvr.deleteEntry(currentSession, programmeId).requireConfirmed()
            createdEntries.remove(programmeId)
            withTimeout(2.minutes) {
                session.observation.first { observation -> observation.dvrEntry(programmeId) == null }
            }

            val autorecId = dvr.createAutorecRule(
                currentSession,
                AutorecRuleCreate(
                    title = marker,
                    channel = RecordingRuleChannel.AllChannels,
                    enabled = false,
                    name = marker,
                    comment = marker,
                    configId = configId,
                ),
            ).confirmedAutorec(createdAutorec)
            assertTrue(
                currentDvrSnapshot(session).autorecRules.any { rule -> rule.id == autorecId },
                "Autorec create was not published",
            )
            dvr.updateAutorecRule(
                currentSession,
                autorecId,
                AutorecRuleUpdate(comment = "$marker-updated", enabled = false),
            ).requireConfirmed()
            withTimeout(2.minutes) {
                session.observation.first { observation ->
                    observation.currentDvrSnapshotOrNull()?.autorecRules
                        ?.firstOrNull { rule -> rule.id == autorecId }?.comment == "$marker-updated"
                }
            }
            assertEquals(
                "$marker-updated",
                currentDvrSnapshot(session).autorecRules.first { rule -> rule.id == autorecId }.comment,
            )
            dvr.deleteAutorecRule(currentSession, autorecId).requireConfirmed()
            createdAutorec.remove(autorecId)
            withTimeout(2.minutes) {
                session.observation.first { observation ->
                    observation.currentDvrSnapshotOrNull()?.autorecRules
                        ?.none { rule -> rule.id == autorecId } == true
                }
            }

            val timerecId = dvr.createTimerecRule(
                currentSession,
                TimerecRuleCreate(
                    title = marker,
                    channel = RecordingRuleChannel.SpecificChannel(requireNotNull(channelId)),
                    startMinutesSinceMidnight = 23 * 60 + 50,
                    stopMinutesSinceMidnight = 23 * 60 + 55,
                    enabled = false,
                    name = marker,
                    comment = marker,
                    configId = configId,
                ),
            ).confirmedTimerec(createdTimerec)
            assertTrue(
                currentDvrSnapshot(session).timerecRules.any { rule -> rule.id == timerecId },
                "Timerec create was not published",
            )
            dvr.updateTimerecRule(
                currentSession,
                timerecId,
                TimerecRuleUpdate(comment = "$marker-updated", enabled = false),
            ).requireConfirmed()
            withTimeout(2.minutes) {
                session.observation.first { observation ->
                    observation.currentDvrSnapshotOrNull()?.timerecRules
                        ?.firstOrNull { rule -> rule.id == timerecId }?.comment == "$marker-updated"
                }
            }
            assertEquals(
                "$marker-updated",
                currentDvrSnapshot(session).timerecRules.first { rule -> rule.id == timerecId }.comment,
            )
            dvr.deleteTimerecRule(currentSession, timerecId).requireConfirmed()
            createdTimerec.remove(timerecId)
            withTimeout(2.minutes) {
                session.observation.first { observation ->
                    observation.currentDvrSnapshotOrNull()?.timerecRules
                        ?.none { rule -> rule.id == timerecId } == true
                }
            }

            val liveStart = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
            val liveId = dvr.scheduleEntry(
                currentSession,
                DvrScheduleRequest(
                    schedule = DvrSchedule.ExplicitTime(
                        channelId = requireNotNull(channelId),
                        start = liveStart,
                        stop = liveStart + 75.seconds,
                    ),
                    configId = configId,
                    title = marker,
                ),
            ).confirmedEntry(createdEntries)
            val recording = withTimeout(45.seconds) {
                session.observation.first { observation ->
                    observation.dvrEntry(liveId)?.state == DvrEntryState.RECORDING
                }.dvrEntry(liveId)
            }
            assertTrue(recording != null, "Live recording did not start")
            dvr.stopEntry(currentSession, liveId).requireConfirmed()
            val stopped = requireNotNull(
                withTimeout(2.minutes) {
                    session.observation.first { observation ->
                        val entry = observation.dvrEntry(liveId)
                        entry != null && entry.state != DvrEntryState.RECORDING
                    }.dvrEntry(liveId)
                },
            )
            credentials.forbidLeak(DvrProgressPolicy().resumeOffer(stopped).toString())
            val playback = session.bindRecordingPlayback(currentSession, liveId)
            assertTrue(playback is PlaybackBindingResult.Bound)
            val closeProgress = (playback as PlaybackBindingResult.Bound).binding.reportProgress(
                growingLease = null,
                progress = DvrProgressPolicy().terminalProgress(
                    position = 15.seconds,
                    duration = 75.seconds,
                    state = stopped.state,
                    exit = DvrPlaybackExit.ORDERLY,
                ),
            )
            assertEquals(DvrProgressResult.Accepted, closeProgress)
            credentials.forbidLeak(closeProgress.toString())
            dvr.deleteEntry(currentSession, liveId).requireConfirmed()
            createdEntries.remove(liveId)
            withTimeout(2.minutes) {
                session.observation.first { observation -> observation.dvrEntry(liveId) == null }
            }

            val afterWrites = session.observation.value.sessionState
            assertTrue(afterWrites is SessionState.Ready, "Session left ready after DVR writes")
            assertEquals(
                CapabilityAccess.ALLOWED,
                (afterWrites as SessionState.Ready).capabilities.dvrWrite,
                "Write proof was lost after successful mutations",
            )
        } catch (error: Throwable) {
            failed = true
            throw error
        } finally {
            try {
                withContext(NonCancellable) {
                    cleanup(session, createdEntries, createdAutorec, createdTimerec)
                }
            } catch (cleanupError: Throwable) {
                if (cleanupError is CancellationException) throw cleanupError
                if (!failed) throw cleanupError
            } finally {
                session.shutdown()
            }
        }
    }
}

private const val CREDENTIALS_ENV: String = "TVHEADEND_SOAK_CREDENTIALS_FILE"

private class LiveCredentials(
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

private fun liveCredentials(): LiveCredentials {
    val path = System.getenv(CREDENTIALS_ENV)
    assertTrue(!path.isNullOrBlank(), "DVR credentials are not provisioned")
    val file = File(requireNotNull(path))
    assertTrue(file.isFile, "DVR credentials are not provisioned")
    val text = try {
        file.readText()
    } catch (_: Exception) {
        throw AssertionError("DVR credentials could not be read")
    }
    return try {
        val host = jsonString(text, "host")
        val username = jsonString(text, "username")
        val password = jsonString(text, "password")
        LiveCredentials(
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
        throw AssertionError("DVR credentials are invalid")
    }
}

private fun currentEvents(session: TvheadendSession): List<EpgEvent> {
    val current = session.observation.value.epgState as? EpgRepositoryState.Current
    assertTrue(current != null, "EPG snapshot was not current")
    return requireNotNull(current).snapshot.events
}

private fun currentDvrSnapshot(session: TvheadendSession): DvrSnapshot =
    requireNotNull(session.observation.value.currentDvrSnapshotOrNull())

private fun SessionObservation.currentDvrSnapshotOrNull(): DvrSnapshot? =
    (dvrState as? DvrRepositoryState.Current)?.snapshot

private fun DvrMutationResult<DvrEntryId>.confirmedEntry(
    created: MutableSet<DvrEntryId>,
): DvrEntryId = remembered(created).requireConfirmed()

private fun DvrMutationResult<AutorecRuleId>.confirmedAutorec(
    created: MutableSet<AutorecRuleId>,
): AutorecRuleId = remembered(created).requireConfirmed()

private fun DvrMutationResult<TimerecRuleId>.confirmedTimerec(
    created: MutableSet<TimerecRuleId>,
): TimerecRuleId = remembered(created).requireConfirmed()

private fun <T> DvrMutationResult<T>.remembered(created: MutableSet<T>): DvrMutationResult<T> {
    when (this) {
        is DvrMutationResult.Confirmed -> created += value
        is DvrMutationResult.AcceptedButUnconfirmed -> created += value
        else -> Unit
    }
    return this
}

private fun <T> DvrMutationResult<T>.requireConfirmed(): T {
    return when (this) {
        is DvrMutationResult.Confirmed -> {
            assertEquals(
                "DvrMutationResult.Confirmed(<redacted>)",
                toString(),
                "DVR mutation rendering was not redacted",
            )
            value
        }
        is DvrMutationResult.AcceptedButUnconfirmed ->
            throw AssertionError("DVR mutation was not stream-confirmed")
        else -> throw AssertionError("DVR mutation failed")
    }
}

private suspend fun cleanup(
    session: TvheadendSession,
    entries: Set<DvrEntryId>,
    autorec: Set<AutorecRuleId>,
    timerec: Set<TimerecRuleId>,
) {
    val dvr: DvrRepository = session.dvrRepository
    val currentSession = requireNotNull(session.observation.value.currentSession)
    var leftover = false
    entries.forEach { id ->
        val existing = session.observation.value.dvrEntry(id)
        if (existing?.state == DvrEntryState.RECORDING) {
            dvr.stopEntry(currentSession, id)
        }
        dvr.deleteEntry(currentSession, id)
        if (session.observation.value.dvrEntry(id) != null) {
            leftover = true
        }
    }
    autorec.forEach { id ->
        dvr.deleteAutorecRule(currentSession, id)
        if (currentDvrSnapshot(session).autorecRules.any { rule -> rule.id == id }) {
            leftover = true
        }
    }
    timerec.forEach { id ->
        dvr.deleteTimerecRule(currentSession, id)
        if (currentDvrSnapshot(session).timerecRules.any { rule -> rule.id == id }) {
            leftover = true
        }
    }
    assertFalse(leftover, "Live DVR verification left created objects")
}

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
