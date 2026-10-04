package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.DvrChange
import at.bernhardberger.tvheadend.sdk.core.DvrChangeKind
import at.bernhardberger.tvheadend.sdk.core.DvrChangeOrigin
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrEntryUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrMutationKind
import at.bernhardberger.tvheadend.sdk.core.DvrMutationResult
import at.bernhardberger.tvheadend.sdk.core.DvrSchedule
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrEntry
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrRecordingFile
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrUpdateProvenance
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
internal class DvrChangesTest {
    @Test
    fun `resolved transitions emit only lifecycle changes including first error and abort`() = runTest {
        data class Case(
            val before: DvrEntryState?,
            val after: DvrEntryState,
            val failure: GatewayDvrFailure = GatewayDvrFailure.NONE,
            val expected: DvrChangeKind?,
        )
        val cases = listOf(
            Case(null, DvrEntryState.SCHEDULED, expected = DvrChangeKind.SCHEDULED),
            Case(null, DvrEntryState.RECORDING, expected = DvrChangeKind.RECORDING_STARTED),
            Case(null, DvrEntryState.COMPLETED, expected = null),
            Case(null, DvrEntryState.COMPLETED, GatewayDvrFailure.PRESENT, null),
            Case(DvrEntryState.SCHEDULED, DvrEntryState.RECORDING, expected = DvrChangeKind.RECORDING_STARTED),
            Case(DvrEntryState.RECORDING, DvrEntryState.COMPLETED, expected = DvrChangeKind.RECORDING_COMPLETED),
            Case(DvrEntryState.RECORDING_ERROR, DvrEntryState.COMPLETED, expected = DvrChangeKind.RECORDING_COMPLETED),
            Case(DvrEntryState.RECORDING, DvrEntryState.RECORDING, GatewayDvrFailure.PRESENT, DvrChangeKind.RECORDING_FAILED),
            Case(DvrEntryState.RECORDING, DvrEntryState.COMPLETED, GatewayDvrFailure.PRESENT, DvrChangeKind.RECORDING_FAILED),
            Case(DvrEntryState.COMPLETED, DvrEntryState.COMPLETED, GatewayDvrFailure.FILE_MISSING, null),
            Case(DvrEntryState.RECORDING_ERROR, DvrEntryState.COMPLETED, GatewayDvrFailure.PRESENT, null),
            Case(DvrEntryState.COMPLETED_ERROR, DvrEntryState.COMPLETED, GatewayDvrFailure.FILE_MISSING, null),
            Case(DvrEntryState.RECORDING, DvrEntryState.COMPLETED, GatewayDvrFailure.ABORTED, DvrChangeKind.RECORDING_ABORTED),
            Case(DvrEntryState.RECORDING_ERROR, DvrEntryState.COMPLETED, GatewayDvrFailure.ABORTED, DvrChangeKind.RECORDING_ABORTED),
            Case(DvrEntryState.SCHEDULED, DvrEntryState.MISSED, expected = DvrChangeKind.MISSED),
            Case(DvrEntryState.RECORDING, DvrEntryState.RECORDING, expected = null),
            Case(DvrEntryState.RECORDING, DvrEntryState.SCHEDULED, expected = null),
            Case(DvrEntryState.RECORDING, DvrEntryState.INVALID, expected = null),
            Case(DvrEntryState.INVALID, DvrEntryState.RECORDING, expected = null),
        )
        for (case in cases) {
            val fixture = Fixture()
            case.before?.let { fixture.accept(MetadataEvent.DvrEntryAdded(fixture.generation, entry(state = it))) }
            fixture.ready()
            val changes = collect(fixture)
            val current = entry(state = case.after, failure = case.failure)
            fixture.accept(if (case.before == null) MetadataEvent.DvrEntryAdded(fixture.generation, current)
                else MetadataEvent.DvrEntryUpdated(fixture.generation, current, GatewayDvrUpdateProvenance.FULL))
            runCurrent()
            assertEquals(listOfNotNull(case.expected), changes.map { it.kind }, case.toString())
            changes.singleOrNull()?.let {
                assertSame(fixture.metadata.observation.value.currentSession!!.generationIdentity, it.generationIdentity)
                assertEquals(DvrChangeOrigin.External, it.origin)
                assertEquals(case.before == null, it.previous == null)
                if (case.failure == GatewayDvrFailure.ABORTED) {
                    assertEquals(DvrEntryState.COMPLETED_ERROR, it.current!!.state)
                }
            }
        }
    }

    @Test
    fun `external stop uses file end then server time with sixty second tolerance`() = runTest {
        for ((fileEnd, serverNow, expected) in listOf(
            Triple(900L, 1100L, DvrChangeKind.RECORDING_STOPPED),
            Triple(1000L, 900L, DvrChangeKind.RECORDING_COMPLETED),
            Triple(940L, 900L, DvrChangeKind.RECORDING_COMPLETED),
            Triple(939L, 1100L, DvrChangeKind.RECORDING_STOPPED),
            Triple(null, 939L, DvrChangeKind.RECORDING_STOPPED),
            Triple(null, 940L, DvrChangeKind.RECORDING_COMPLETED),
            Triple(null, 999L, DvrChangeKind.RECORDING_COMPLETED),
            Triple(null, 1000L, DvrChangeKind.RECORDING_COMPLETED),
            Triple(null, null, DvrChangeKind.RECORDING_COMPLETED),
        )) {
            val fixture = Fixture(serverTime = serverNow?.let(Instant::fromEpochSeconds))
            fixture.accept(MetadataEvent.DvrEntryAdded(fixture.generation, entry(state = DvrEntryState.RECORDING)))
            fixture.ready()
            val changes = collect(fixture)
            fixture.accept(MetadataEvent.DvrEntryUpdated(fixture.generation, entry(
                state = DvrEntryState.COMPLETED,
                files = fileEnd?.let { listOf(GatewayDvrRecordingFile(null, null, null, Instant.fromEpochSeconds(it), null)) },
            ), GatewayDvrUpdateProvenance.FULL))
            runCurrent()
            assertEquals(expected, changes.single().kind)
        }
    }

    @Test
    fun `initial sync reconnect unknown events stats and same state full updates stay silent`() = runTest {
        val fixture = Fixture()
        val changes = collect(fixture)
        fixture.accept(MetadataEvent.DvrEntryAdded(fixture.generation, entry()))
        fixture.ready()
        fixture.accept(MetadataEvent.DvrEntryUpdated(fixture.generation, entry(), GatewayDvrUpdateProvenance.FULL))
        fixture.accept(MetadataEvent.DvrEntryUpdated(fixture.generation,
            entry(state = DvrEntryState.RECORDING), GatewayDvrUpdateProvenance.STATS_ONLY))
        fixture.accept(MetadataEvent.DvrEntryUpdated(fixture.generation,
            entry(id = 99, state = DvrEntryState.RECORDING), GatewayDvrUpdateProvenance.FULL))
        fixture.accept(MetadataEvent.DvrEntryDeleted(fixture.generation, DvrEntryId(100)))
        val oldGeneration = fixture.generation
        fixture.generation = GatewayGeneration()
        fixture.metadata.bindGeneration(fixture.generation)
        fixture.accept(MetadataEvent.DvrEntryAdded(fixture.generation, entry(state = DvrEntryState.RECORDING)))
        fixture.ready()
        fixture.accept(MetadataEvent.DvrEntryAdded(oldGeneration, entry(id = 88)))
        runCurrent()
        assertTrue(changes.isEmpty())
        fixture.accept(MetadataEvent.DvrEntryDeleted(fixture.generation, DvrEntryId(7)))
        runCurrent()
        val removed = changes.single()
        assertEquals(DvrChangeKind.REMOVED, removed.kind)
        assertEquals(DvrEntryState.RECORDING, removed.previous!!.state)
        assertNull(removed.current)
        assertSame(fixture.metadata.observation.value.currentSession!!.generationIdentity, removed.generationIdentity)
        assertTrue(collect(fixture).isEmpty(), "A later subscriber must not see replay")
    }

    @Test
    fun `schedule stop cancel delete attribute metadata before command response without changing confirmation`() = runTest {
        for (kind in listOf(DvrMutationKind.SCHEDULE, DvrMutationKind.STOP, DvrMutationKind.CANCEL, DvrMutationKind.DELETE)) {
            val fixture = Fixture()
            if (kind != DvrMutationKind.SCHEDULE) {
                fixture.accept(MetadataEvent.DvrEntryAdded(fixture.generation, entry(state = DvrEntryState.RECORDING)))
            }
            fixture.ready()
            val changes = collect(fixture)
            fixture.gateway.scheduleBehavior = { generation, _ ->
                fixture.accept(MetadataEvent.DvrEntryAdded(generation, entry(id = 99, eventId = 99)))
                fixture.accept(MetadataEvent.DvrEntryAdded(generation, entry()))
                GatewayResult.Ok(DvrEntryId(7))
            }
            fixture.gateway.stopBehavior = { generation, _ ->
                fixture.accept(MetadataEvent.DvrEntryUpdated(generation,
                    entry(state = DvrEntryState.COMPLETED, failure = GatewayDvrFailure.PRESENT), GatewayDvrUpdateProvenance.FULL))
                GatewayResult.Ok(Unit)
            }
            fixture.gateway.cancelBehavior = { generation, _ ->
                fixture.accept(MetadataEvent.DvrEntryUpdated(generation,
                    entry(state = DvrEntryState.COMPLETED, failure = GatewayDvrFailure.ABORTED), GatewayDvrUpdateProvenance.FULL))
                GatewayResult.Ok(Unit)
            }
            fixture.gateway.deleteBehavior = { generation, _ ->
                fixture.accept(MetadataEvent.DvrEntryDeleted(generation, DvrEntryId(7)))
                GatewayResult.Ok(Unit)
            }
            val result = when (kind) {
                DvrMutationKind.SCHEDULE -> fixture.coordinator.scheduleEntry(fixture.generation, DvrScheduleRequest(DvrSchedule.Programme(EventId(1))))
                DvrMutationKind.STOP -> fixture.coordinator.stopEntry(fixture.generation, DvrEntryId(7))
                DvrMutationKind.CANCEL -> fixture.coordinator.cancelEntry(fixture.generation, DvrEntryId(7))
                DvrMutationKind.DELETE -> fixture.coordinator.deleteEntry(fixture.generation, DvrEntryId(7))
                else -> error("Not a tested mutation")
            }
            assertTrue(result is DvrMutationResult.Confirmed)
            runCurrent()
            val local = changes.last()
            assertEquals(DvrChangeOrigin.ThisClient(kind), local.origin)
            assertEquals(when (kind) {
                DvrMutationKind.SCHEDULE -> DvrChangeKind.SCHEDULED
                DvrMutationKind.STOP -> DvrChangeKind.RECORDING_STOPPED
                DvrMutationKind.CANCEL -> DvrChangeKind.RECORDING_ABORTED
                else -> DvrChangeKind.REMOVED
            }, local.kind)
            if (kind == DvrMutationKind.SCHEDULE) assertEquals(DvrChangeOrigin.External, changes.first().origin)
        }
    }

    @Test
    fun `stop intent survives stats confirmation update and active recording error`() = runTest {
        val fixture = Fixture()
        fixture.accept(MetadataEvent.DvrEntryAdded(fixture.generation, entry(state = DvrEntryState.RECORDING)))
        fixture.ready()
        val changes = collect(fixture)
        fixture.gateway.stopBehavior = { generation, _ ->
            fixture.accept(MetadataEvent.DvrEntryUpdated(generation,
                entry(state = DvrEntryState.RECORDING), GatewayDvrUpdateProvenance.STATS_ONLY))
            GatewayResult.Ok(Unit)
        }
        fixture.coordinator.stopEntry(fixture.generation, DvrEntryId(7))
        fixture.gateway.updateBehavior = { generation, _, _ ->
            fixture.accept(MetadataEvent.DvrEntryUpdated(generation,
                entry(state = DvrEntryState.RECORDING), GatewayDvrUpdateProvenance.FULL))
            GatewayResult.Ok(Unit)
        }
        assertTrue(fixture.coordinator.updateEntry(fixture.generation, DvrEntryId(7), DvrEntryUpdate()) is DvrMutationResult.Confirmed)
        fixture.accept(MetadataEvent.DvrEntryUpdated(fixture.generation,
            entry(state = DvrEntryState.RECORDING, failure = GatewayDvrFailure.PRESENT), GatewayDvrUpdateProvenance.FULL))
        fixture.accept(MetadataEvent.DvrEntryUpdated(fixture.generation,
            entry(state = DvrEntryState.COMPLETED, failure = GatewayDvrFailure.PRESENT), GatewayDvrUpdateProvenance.FULL))
        runCurrent()
        assertEquals(listOf(DvrChangeKind.RECORDING_FAILED, DvrChangeKind.RECORDING_STOPPED), changes.map { it.kind })
        assertEquals(DvrChangeOrigin.External, changes.first().origin)
        assertEquals(DvrChangeOrigin.ThisClient(DvrMutationKind.STOP), changes.last().origin)
    }

    @Test
    fun `failed command drops intent before a later matching event`() = runTest {
        val fixture = Fixture()
        fixture.ready()
        val changes = collect(fixture)
        fixture.gateway.scheduleBehavior = { _, _ -> GatewayResult.AccessDenied }
        assertSame(DvrMutationResult.AccessDenied, fixture.coordinator.scheduleEntry(
            fixture.generation, DvrScheduleRequest(DvrSchedule.Programme(EventId(1)))))
        fixture.accept(MetadataEvent.DvrEntryAdded(fixture.generation, entry()))
        runCurrent()
        assertEquals(DvrChangeOrigin.External, changes.single().origin)
    }

    @Test
    fun `intent expiry starts on acceptance and rebind or admission stop clears pending origins`() = runTest {
        var now = Instant.fromEpochSeconds(0)
        val ledger = DvrIntentLedger(object : Clock { override fun now(): Instant = now })
        val fixture = Fixture(ledger = ledger)
        fixture.accept(MetadataEvent.DvrEntryAdded(fixture.generation, entry(state = DvrEntryState.RECORDING)))
        fixture.ready()
        val entry = fixture.metadata.observation.value.dvrSnapshotForDisplay!!.entries.single()
        fun record() = ledger.record(fixture.generation, DvrMutationKind.STOP, entryId = entry.id)
        fun origin() = ledger.origin(fixture.generation, entry, DvrChangeKind.RECORDING_COMPLETED)
        val pending = record()
        now += 90.seconds
        ledger.accepted(pending)
        now += 59.seconds
        assertEquals(DvrChangeOrigin.ThisClient(DvrMutationKind.STOP), origin())
        ledger.accepted(record())
        now += 60.seconds
        assertEquals(DvrChangeOrigin.External, origin())
        record()
        fixture.coordinator.bindGeneration(GatewayGeneration())
        assertEquals(DvrChangeOrigin.External, origin())
        fixture.coordinator.bindGeneration(fixture.generation)
        record()
        fixture.coordinator.stopAdmission()
        assertEquals(DvrChangeOrigin.External, origin())
    }

    @Test
    fun `edit without a lifecycle change cannot attribute a later natural start`() = runTest {
        val fixture = Fixture()
        fixture.accept(MetadataEvent.DvrEntryAdded(fixture.generation, entry()))
        fixture.ready()
        val changes = collect(fixture)
        fixture.gateway.updateBehavior = { generation, _, _ ->
            fixture.accept(MetadataEvent.DvrEntryUpdated(generation, entry(), GatewayDvrUpdateProvenance.FULL))
            GatewayResult.Ok(Unit)
        }
        assertTrue(fixture.coordinator.updateEntry(fixture.generation, DvrEntryId(7), DvrEntryUpdate()) is DvrMutationResult.Confirmed)
        fixture.accept(MetadataEvent.DvrEntryUpdated(fixture.generation,
            entry(state = DvrEntryState.RECORDING), GatewayDvrUpdateProvenance.FULL))
        runCurrent()
        assertEquals(DvrChangeKind.RECORDING_STARTED, changes.single().kind)
        assertEquals(DvrChangeOrigin.External, changes.single().origin)
    }

    @Test
    fun `failure emits once despite recovery and resets after removal or rebind`() = runTest {
        val fixture = Fixture()
        fixture.accept(MetadataEvent.DvrEntryAdded(fixture.generation, entry(state = DvrEntryState.RECORDING)))
        fixture.ready()
        val changes = collect(fixture)
        fun update(failure: GatewayDvrFailure) = fixture.accept(MetadataEvent.DvrEntryUpdated(fixture.generation,
            entry(state = DvrEntryState.RECORDING, failure = failure), GatewayDvrUpdateProvenance.FULL))
        repeat(2) {
            update(GatewayDvrFailure.PRESENT)
            update(GatewayDvrFailure.NONE)
        }
        runCurrent()
        assertEquals(listOf(DvrChangeKind.RECORDING_FAILED), changes.map { it.kind })
        fixture.accept(MetadataEvent.DvrEntryDeleted(fixture.generation, DvrEntryId(7)))
        fixture.accept(MetadataEvent.DvrEntryAdded(fixture.generation, entry(state = DvrEntryState.RECORDING)))
        update(GatewayDvrFailure.PRESENT)
        runCurrent()
        assertEquals(2, changes.count { it.kind == DvrChangeKind.RECORDING_FAILED })
        fixture.generation = GatewayGeneration()
        fixture.metadata.bindGeneration(fixture.generation)
        fixture.accept(MetadataEvent.DvrEntryAdded(fixture.generation, entry(state = DvrEntryState.RECORDING)))
        fixture.ready()
        update(GatewayDvrFailure.PRESENT)
        runCurrent()
        assertEquals(3, changes.count { it.kind == DvrChangeKind.RECORDING_FAILED })
    }

    @Test
    fun `delete of a running recording attributes both abort and removal`() = runTest {
        val fixture = Fixture()
        fixture.accept(MetadataEvent.DvrEntryAdded(fixture.generation, entry(state = DvrEntryState.RECORDING)))
        fixture.ready()
        val changes = collect(fixture)
        fixture.gateway.deleteBehavior = { generation, _ ->
            fixture.accept(MetadataEvent.DvrEntryUpdated(generation,
                entry(state = DvrEntryState.COMPLETED, failure = GatewayDvrFailure.ABORTED), GatewayDvrUpdateProvenance.FULL))
            fixture.accept(MetadataEvent.DvrEntryDeleted(generation, DvrEntryId(7)))
            GatewayResult.Ok(Unit)
        }
        assertTrue(fixture.coordinator.deleteEntry(fixture.generation, DvrEntryId(7)) is DvrMutationResult.Confirmed)
        runCurrent()
        assertEquals(listOf(DvrChangeKind.RECORDING_ABORTED, DvrChangeKind.REMOVED), changes.map { it.kind })
        assertTrue(changes.all { it.origin == DvrChangeOrigin.ThisClient(DvrMutationKind.DELETE) })
    }

    @Test
    fun `programme schedule binds its add to entry id and attributes immediate start once`() = runTest {
        for (addedState in listOf(DvrEntryState.SCHEDULED, DvrEntryState.RECORDING)) {
            val fixture = Fixture()
            fixture.ready()
            val changes = collect(fixture)
            fixture.gateway.scheduleBehavior = { generation, _ ->
                fixture.accept(MetadataEvent.DvrEntryAdded(generation, entry(state = addedState)))
                if (addedState == DvrEntryState.SCHEDULED) {
                    fixture.accept(MetadataEvent.DvrEntryAdded(generation, entry(id = 99)))
                    fixture.accept(MetadataEvent.DvrEntryUpdated(generation,
                        entry(state = DvrEntryState.RECORDING), GatewayDvrUpdateProvenance.FULL))
                }
                GatewayResult.Ok(DvrEntryId(7))
            }
            assertTrue(fixture.coordinator.scheduleEntry(fixture.generation,
                DvrScheduleRequest(DvrSchedule.Programme(EventId(1)))) is DvrMutationResult.Confirmed)
            runCurrent()
            val local = changes.filter { it.current?.id == DvrEntryId(7) }
            assertEquals(if (addedState == DvrEntryState.SCHEDULED)
                listOf(DvrChangeKind.SCHEDULED, DvrChangeKind.RECORDING_STARTED)
                else listOf(DvrChangeKind.RECORDING_STARTED), local.map { it.kind })
            assertTrue(local.all { it.origin == DvrChangeOrigin.ThisClient(DvrMutationKind.SCHEDULE) })
            changes.singleOrNull { it.current?.id == DvrEntryId(99) }?.let {
                assertEquals(DvrChangeOrigin.External, it.origin)
            }
        }
    }

    @Test
    fun `silent error transition does not consume a cancel intent`() = runTest {
        val ledger = DvrIntentLedger()
        val fixture = Fixture(ledger = ledger)
        fixture.accept(MetadataEvent.DvrEntryAdded(fixture.generation, entry(state = DvrEntryState.RECORDING_ERROR)))
        fixture.ready()
        ledger.record(fixture.generation, DvrMutationKind.CANCEL, entryId = DvrEntryId(7))
        val changes = collect(fixture)
        fixture.accept(MetadataEvent.DvrEntryUpdated(fixture.generation,
            entry(state = DvrEntryState.COMPLETED, failure = GatewayDvrFailure.PRESENT), GatewayDvrUpdateProvenance.FULL))
        fixture.accept(MetadataEvent.DvrEntryDeleted(fixture.generation, DvrEntryId(7)))
        runCurrent()
        assertEquals(DvrChangeKind.REMOVED, changes.single().kind)
        assertEquals(DvrChangeOrigin.ThisClient(DvrMutationKind.CANCEL), changes.single().origin)
    }

    @Test
    fun `terminal move consumes stop before a newer cancel intent`() = runTest {
        val ledger = DvrIntentLedger()
        val fixture = Fixture(ledger = ledger)
        fixture.accept(MetadataEvent.DvrEntryAdded(fixture.generation, entry(state = DvrEntryState.RECORDING)))
        fixture.ready()
        val changes = collect(fixture)
        ledger.accepted(ledger.record(fixture.generation, DvrMutationKind.STOP, entryId = DvrEntryId(7)))
        ledger.accepted(ledger.record(fixture.generation, DvrMutationKind.CANCEL, entryId = DvrEntryId(7)))
        fixture.accept(MetadataEvent.DvrEntryUpdated(fixture.generation,
            entry(state = DvrEntryState.COMPLETED, failure = GatewayDvrFailure.ABORTED), GatewayDvrUpdateProvenance.FULL))
        runCurrent()
        assertEquals(DvrChangeKind.RECORDING_STOPPED, changes.single().kind)
        assertEquals(DvrChangeOrigin.ThisClient(DvrMutationKind.STOP), changes.single().origin)
        val current = changes.single().current!!
        assertNull(ledger.takeStop(fixture.generation, current))
    }

    @Test
    fun `schedule retained after add expires before later start`() = runTest {
        var now = Instant.fromEpochSeconds(0)
        val ledger = DvrIntentLedger(object : Clock { override fun now(): Instant = now })
        val fixture = Fixture(ledger = ledger)
        fixture.ready()
        val changes = collect(fixture)
        fixture.gateway.scheduleBehavior = { generation, _ ->
            fixture.accept(MetadataEvent.DvrEntryAdded(generation, entry()))
            GatewayResult.Ok(DvrEntryId(7))
        }
        assertTrue(fixture.coordinator.scheduleEntry(fixture.generation,
            DvrScheduleRequest(DvrSchedule.Programme(EventId(1)))) is DvrMutationResult.Confirmed)
        now += 60.seconds
        fixture.accept(MetadataEvent.DvrEntryUpdated(fixture.generation,
            entry(state = DvrEntryState.RECORDING), GatewayDvrUpdateProvenance.FULL))
        runCurrent()
        assertEquals(DvrChangeOrigin.ThisClient(DvrMutationKind.SCHEDULE), changes.first().origin)
        assertEquals(DvrChangeOrigin.External, changes.last().origin)
    }

    @Test
    fun `event before deferred failure keeps attribution but later start is external`() = runTest {
        val fixture = Fixture()
        fixture.ready()
        val changes = collect(fixture)
        val response = CompletableDeferred<GatewayResult<DvrEntryId>>()
        fixture.gateway.scheduleBehavior = { _, _ -> response.await() }
        val command = async { fixture.coordinator.scheduleEntry(fixture.generation,
            DvrScheduleRequest(DvrSchedule.Programme(EventId(1)))) }
        runCurrent()
        fixture.accept(MetadataEvent.DvrEntryAdded(fixture.generation, entry()))
        runCurrent()
        assertEquals(DvrChangeOrigin.ThisClient(DvrMutationKind.SCHEDULE), changes.single().origin)
        response.complete(GatewayResult.AccessDenied)
        assertSame(DvrMutationResult.AccessDenied, command.await())
        fixture.accept(MetadataEvent.DvrEntryUpdated(fixture.generation,
            entry(state = DvrEntryState.RECORDING), GatewayDvrUpdateProvenance.FULL))
        runCurrent()
        assertEquals(DvrChangeOrigin.External, changes.last().origin)
        assertEquals(DvrChangeOrigin.ThisClient(DvrMutationKind.SCHEDULE), changes.first().origin)
    }

    @Test
    fun `cancelling an unaccepted command removes intent`() = runTest {
        val fixture = Fixture()
        fixture.ready()
        val changes = collect(fixture)
        val response = CompletableDeferred<GatewayResult<DvrEntryId>>()
        fixture.gateway.scheduleBehavior = { _, _ -> response.await() }
        val command = launch { fixture.coordinator.scheduleEntry(fixture.generation,
            DvrScheduleRequest(DvrSchedule.Programme(EventId(1)))) }
        runCurrent()
        command.cancelAndJoin()
        fixture.accept(MetadataEvent.DvrEntryAdded(fixture.generation, entry()))
        runCurrent()
        assertEquals(DvrChangeOrigin.External, changes.single().origin)
    }

    @Test
    fun `late accepted response after rebind cannot restore intent`() = runTest {
        val fixture = Fixture()
        fixture.ready()
        val changes = collect(fixture)
        val response = CompletableDeferred<GatewayResult<DvrEntryId>>()
        fixture.gateway.scheduleBehavior = { _, _ -> withContext(NonCancellable) { response.await() } }
        val command = async { fixture.coordinator.scheduleEntry(fixture.generation,
            DvrScheduleRequest(DvrSchedule.Programme(EventId(1)))) }
        runCurrent()
        fixture.generation = GatewayGeneration()
        fixture.coordinator.bindGeneration(fixture.generation)
        fixture.coordinator.startAdmission(fixture.generation)
        fixture.metadata.bindGeneration(fixture.generation)
        fixture.ready()
        response.complete(GatewayResult.Ok(DvrEntryId(7)))
        assertSame(DvrMutationResult.TransportUnavailable, command.await())
        fixture.accept(MetadataEvent.DvrEntryAdded(fixture.generation, entry()))
        runCurrent()
        assertEquals(DvrChangeOrigin.External, changes.single().origin)
    }

    private fun TestScope.collect(fixture: Fixture): MutableList<DvrChange> = mutableListOf<DvrChange>().also { changes ->
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { fixture.metadata.dvrRepository.changes.collect { changes += it } }
    }

    private class Fixture(
        serverTime: Instant? = Instant.fromEpochSeconds(1000),
        ledger: DvrIntentLedger = DvrIntentLedger(),
    ) {
        var generation = GatewayGeneration()
        val gateway = MutationGateway()
        val coordinator = DvrMutationCoordinator(gateway, intents = ledger)
        val metadata = PhaseOneSessionMetadata(
            onDvrMetadataAccepted = coordinator::acceptMetadata,
            estimatedServerTime = { serverTime },
            dvrIntents = ledger,
        )
        init {
            coordinator.bindGeneration(generation)
            coordinator.startAdmission(generation)
            metadata.bindGeneration(generation)
        }
        fun accept(event: MetadataEvent) = metadata.acceptMetadata(event)
        fun ready() {
            accept(MetadataEvent.InitialSyncCompleted(generation))
            metadata.publishSessionState(
                SessionState.Ready(ServerCapabilities.create(CapabilityAccess.UNKNOWN, CapabilityAccess.UNKNOWN)),
                RecordingProgressCapability.UNKNOWN,
                generation,
            )
        }
    }

    private fun entry(
        id: Long = 7,
        eventId: Long = 1,
        state: DvrEntryState = DvrEntryState.SCHEDULED,
        failure: GatewayDvrFailure = GatewayDvrFailure.NONE,
        files: List<GatewayDvrRecordingFile>? = null,
    ) = GatewayDvrEntry(
        id = DvrEntryId(id), eventId = EventId(eventId), state = state, failure = failure,
        start = Instant.fromEpochSeconds(100), stop = Instant.fromEpochSeconds(1000), files = files,
    )
}
