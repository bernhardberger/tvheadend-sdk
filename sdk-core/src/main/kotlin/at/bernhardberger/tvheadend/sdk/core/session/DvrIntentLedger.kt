package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.DvrChangeKind
import at.bernhardberger.tvheadend.sdk.core.DvrChangeOrigin
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrMutationKind
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Independent of command confirmations. Lock order: metadata/coordinator, then ledger. */
internal class DvrIntentLedger(private val clock: Clock = Clock.System) {
    private val lock = Any()
    private var generation: GatewayGeneration? = null
    private val intents = mutableListOf<Intent>()

    internal fun bind(generation: GatewayGeneration?) = synchronized(lock) {
        this.generation = generation
        intents.clear()
    }

    internal fun record(
        generation: GatewayGeneration,
        kind: DvrMutationKind,
        entryId: DvrEntryId? = null,
        eventId: EventId? = null,
    ): Intent? = synchronized(lock) {
        prune()
        if (this.generation !== generation || kind == DvrMutationKind.UPDATE ||
            (entryId == null && eventId == null)) return@synchronized null
        Intent(kind, entryId, eventId).also { intents += it }
    }

    internal fun accepted(intent: Intent?) = synchronized(lock) {
        if (intent != null && intent in intents) intent.acceptedAt = clock.now()
    }

    internal fun failed(intent: Intent?) = synchronized(lock) {
        intents.remove(intent)
        Unit
    }

    /** A terminal recording move gives STOP priority over newer matching commands. */
    internal fun takeStop(generation: GatewayGeneration, entry: DvrEntry): DvrChangeOrigin? = synchronized(lock) {
        prune()
        if (this.generation !== generation) return@synchronized null
        val intent = intents.lastOrNull { it.kind == DvrMutationKind.STOP && it.entryId == entry.id }
            ?: return@synchronized null
        intents.remove(intent)
        DvrChangeOrigin.ThisClient(intent.kind)
    }

    internal fun origin(
        generation: GatewayGeneration,
        entry: DvrEntry,
        kind: DvrChangeKind,
    ): DvrChangeOrigin = synchronized(lock) {
        prune()
        if (this.generation !== generation) return@synchronized DvrChangeOrigin.External
        val intent = intents.lastOrNull { intent ->
            val targetMatches = intent.entryId == entry.id ||
                (intent.entryId == null && intent.eventId != null && intent.eventId == entry.eventId)
            targetMatches && when (intent.kind) {
                DvrMutationKind.SCHEDULE -> kind == DvrChangeKind.SCHEDULED || kind == DvrChangeKind.RECORDING_STARTED
                DvrMutationKind.STOP -> kind in terminalRecordingChanges
                DvrMutationKind.CANCEL -> kind == DvrChangeKind.REMOVED || kind in terminalRecordingChanges
                DvrMutationKind.DELETE -> kind == DvrChangeKind.REMOVED || kind in terminalRecordingChanges
                DvrMutationKind.UPDATE -> false
            }
        } ?: return@synchronized DvrChangeOrigin.External
        when {
            intent.kind == DvrMutationKind.SCHEDULE && kind == DvrChangeKind.SCHEDULED -> intent.entryId = entry.id
            intent.kind == DvrMutationKind.DELETE && kind != DvrChangeKind.REMOVED -> Unit
            else -> intents.remove(intent)
        }
        DvrChangeOrigin.ThisClient(intent.kind)
    }

    private fun prune() {
        val now = clock.now()
        intents.removeAll { it.acceptedAt?.let { accepted -> now - accepted >= 60.seconds } == true }
    }

    internal class Intent(
        internal val kind: DvrMutationKind,
        internal var entryId: DvrEntryId?,
        internal val eventId: EventId?,
        internal var acceptedAt: Instant? = null,
    )
}

private val terminalRecordingChanges = setOf(
    DvrChangeKind.RECORDING_COMPLETED,
    DvrChangeKind.RECORDING_STOPPED,
    DvrChangeKind.RECORDING_ABORTED,
    DvrChangeKind.RECORDING_FAILED,
)
