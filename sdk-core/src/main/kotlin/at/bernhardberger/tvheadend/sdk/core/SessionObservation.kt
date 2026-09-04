package at.bernhardberger.tvheadend.sdk.core

import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant

/** One immutable, atomically published view of session lifecycle and retained metadata. */
public class SessionObservation private constructor(
    public val sessionState: SessionState,
    public val channelState: ChannelRepositoryState,
    public val epgState: EpgRepositoryState,
    public val dvrState: DvrRepositoryState,
    public val dvrConfigurationsState: DvrConfigurationsState,
    public val dvrDiskSpaceState: DvrDiskSpaceState,
    public val recordingProgressCapability: RecordingProgressCapability,
    public val currentSession: CurrentSessionObservation?,
) {
    /** Current or retained channel catalog available for display, without copying. */
    public val channelCatalogForDisplay: ChannelCatalog?
        get() = channelState.channelCatalogForDisplay

    /** Provenance and synchronization state of [channelCatalogForDisplay]. */
    public val channelCatalogAuthority: RetainedMetadataAuthority
        get() = channelState.channelCatalogAuthority

    /** Current or retained EPG snapshot available for display, without copying. */
    public val epgSnapshotForDisplay: EpgSnapshot?
        get() = epgState.epgSnapshotForDisplay

    /** Provenance and synchronization state of [epgSnapshotForDisplay]. */
    public val epgSnapshotAuthority: RetainedMetadataAuthority
        get() = epgState.epgSnapshotAuthority

    /** Current or retained DVR snapshot available for display, without copying. */
    public val dvrSnapshotForDisplay: DvrSnapshot?
        get() = dvrState.dvrSnapshotForDisplay

    /** Provenance and synchronization state of [dvrSnapshotForDisplay]. */
    public val dvrSnapshotAuthority: RetainedMetadataAuthority
        get() = dvrState.dvrSnapshotAuthority

    /** Selects exactly one channel from this observation's current or retained catalog. */
    public fun channel(id: ChannelId): Channel? =
        channelCatalogForDisplay?.channels?.singleOrNull { channel -> channel.id == id }

    /** Selects channels in catalog order from this observation's current or retained catalog. */
    public fun channels(ids: Set<ChannelId>): List<Channel> {
        if (ids.isEmpty()) return emptyList()
        val selected = channelCatalogForDisplay?.channels?.filter { channel -> channel.id in ids }
            .orEmpty()
        return Collections.unmodifiableList(selected)
    }

    /** Selects exactly one event from this observation's current or retained EPG snapshot. */
    public fun event(id: EventId): EpgEvent? =
        epgSnapshotForDisplay?.events?.singleOrNull { event -> event.id == id }

    /**
     * Selects the first retained event active at [at] using a closed start and open stop boundary.
     */
    public fun eventAt(channelId: ChannelId, at: Instant): EpgEvent? =
        epgSnapshotForDisplay?.events?.firstOrNull { event ->
            event.channelId == channelId && event.start <= at && at < event.stop
        }

    /**
     * Selects the event after the event active at [at], or the earliest future event in a gap.
     *
     * An authoritative in-snapshot `nextEventId` wins when it names a same-channel event that does
     * not overlap the active event. Otherwise timing and then event ID provide deterministic order.
     */
    public fun nextEvent(channelId: ChannelId, at: Instant): EpgEvent? {
        val snapshot = epgSnapshotForDisplay ?: return null
        val active = snapshot.events.firstOrNull { event ->
            event.channelId == channelId && event.start <= at && at < event.stop
        }
        if (active != null) {
            val linked = active.nextEventId?.let { id ->
                snapshot.events.singleOrNull { event -> event.id == id }
            }
            if (linked?.channelId == channelId && linked.start >= active.stop) return linked
        }
        val boundary = active?.stop ?: at
        return snapshot.events.asSequence()
            .filter { event -> event.channelId == channelId && event !== active && event.start >= boundary }
            .minWithOrNull(compareBy<EpgEvent>({ event -> event.start }, { event -> event.stop }, { event -> event.id.value }))
    }

    /** Selects coverage paired with this observation's exact immutable EPG event snapshot. */
    public fun coverage(channelId: ChannelId): EpgCoverage? =
        epgSnapshotForDisplay?.coverages?.singleOrNull { coverage ->
            coverage.channelId == channelId
        }

    /** Selects exactly one DVR entry from this observation's current or retained DVR snapshot. */
    public fun dvrEntry(id: DvrEntryId): DvrEntry? =
        dvrSnapshotForDisplay?.entries?.singleOrNull { entry -> entry.id == id }

    /** Selects the unique DVR entry related to [eventId] within this aggregate observation. */
    public fun dvrEntryForEvent(eventId: EventId): DvrEntry? {
        val event = event(eventId) ?: return null
        return dvrSnapshotForDisplay?.entries?.singleOrNull { entry ->
            entry.eventId == eventId || event.dvrEntryId == entry.id
        }
    }

    /** Selects the unique EPG event related to [entryId] within this aggregate observation. */
    public fun epgEventForDvrEntry(entryId: DvrEntryId): EpgEvent? {
        val entry = dvrEntry(entryId) ?: return null
        return epgSnapshotForDisplay?.events?.singleOrNull { event ->
            event.dvrEntryId == entryId || entry.eventId == event.id
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SessionObservation &&
            sessionState == other.sessionState &&
            channelState == other.channelState &&
            epgState == other.epgState &&
            dvrState == other.dvrState &&
            dvrConfigurationsState == other.dvrConfigurationsState &&
            dvrDiskSpaceState == other.dvrDiskSpaceState &&
            recordingProgressCapability == other.recordingProgressCapability &&
            currentSession === other.currentSession

    override fun hashCode(): Int {
        var result = sessionState.hashCode()
        result = 31 * result + channelState.hashCode()
        result = 31 * result + epgState.hashCode()
        result = 31 * result + dvrState.hashCode()
        result = 31 * result + dvrConfigurationsState.hashCode()
        result = 31 * result + dvrDiskSpaceState.hashCode()
        result = 31 * result + recordingProgressCapability.hashCode()
        result = 31 * result + (currentSession?.let(System::identityHashCode) ?: 0)
        return result
    }

    override fun toString(): String = "SessionObservation(<redacted>)"

    public companion object {
        /**
         * Creates a standalone immutable observation for consumer and fake tests.
         *
         * Each call represents independent connection-generation authority. Production metadata
         * republication preserves its owner-managed generation identity instead.
         */
        public fun create(
            sessionState: SessionState = SessionState.Disconnected,
            channelState: ChannelRepositoryState = ChannelRepositoryState.Empty,
            epgState: EpgRepositoryState = EpgRepositoryState.Empty,
            dvrState: DvrRepositoryState = DvrRepositoryState.Empty,
            dvrConfigurationsState: DvrConfigurationsState = DvrConfigurationsState.Unknown,
            dvrDiskSpaceState: DvrDiskSpaceState = DvrDiskSpaceState.Unknown,
            recordingProgressCapability: RecordingProgressCapability = RecordingProgressCapability.UNKNOWN,
        ): SessionObservation {
            val standaloneOwner = Any()
            return createOwned(
                sessionState = sessionState,
                channelState = channelState,
                epgState = epgState,
                dvrState = dvrState,
                dvrConfigurationsState = dvrConfigurationsState,
                dvrDiskSpaceState = dvrDiskSpaceState,
                recordingProgressCapability = recordingProgressCapability,
                owner = standaloneOwner,
                generation = standaloneOwner,
                generationIdentity = SessionGenerationIdentity.create(),
                previousCurrent = null,
            )
        }

        internal fun createOwned(
            sessionState: SessionState,
            channelState: ChannelRepositoryState,
            epgState: EpgRepositoryState,
            dvrState: DvrRepositoryState,
            dvrConfigurationsState: DvrConfigurationsState,
            dvrDiskSpaceState: DvrDiskSpaceState,
            recordingProgressCapability: RecordingProgressCapability,
            owner: Any,
            generation: Any?,
            generationIdentity: SessionGenerationIdentity?,
            previousCurrent: CurrentSessionObservation?,
        ): SessionObservation {
            val current = if (
                generation != null &&
                sessionState is SessionState.Ready &&
                channelState is ChannelRepositoryState.Current &&
                epgState is EpgRepositoryState.Current &&
                dvrState is DvrRepositoryState.Current
            ) {
                previousCurrent?.takeIf { capability ->
                    capability.owner === owner && capability.generation === generation
                } ?: CurrentSessionObservation.create(
                    owner = owner,
                    generation = generation,
                    generationIdentity = requireNotNull(generationIdentity),
                )
            } else {
                null
            }
            return SessionObservation(
                sessionState = sessionState,
                channelState = channelState,
                epgState = epgState,
                dvrState = dvrState,
                dvrConfigurationsState = dvrConfigurationsState,
                dvrDiskSpaceState = dvrDiskSpaceState,
                recordingProgressCapability = recordingProgressCapability,
                currentSession = current,
            )
        }
    }
}

/** Opaque process-local identity for one connection-generation authority. */
public class SessionGenerationIdentity private constructor() {
    override fun toString(): String = "SessionGenerationIdentity(<redacted>)"

    internal companion object {
        @JvmSynthetic
        internal fun create(): SessionGenerationIdentity = SessionGenerationIdentity()
    }
}

/** Opaque proof that one aggregate observation is current for its owning session generation. */
public class CurrentSessionObservation private constructor(
    internal val owner: Any,
    internal val generation: Any,
    /** Process-local identity shared by proofs issued under the same connection authority. */
    public val generationIdentity: SessionGenerationIdentity,
) {
    override fun toString(): String = "CurrentSessionObservation(<redacted>)"

    internal companion object {
        @JvmSynthetic
        internal fun create(
            owner: Any,
            generation: Any,
            generationIdentity: SessionGenerationIdentity,
        ): CurrentSessionObservation = CurrentSessionObservation(owner, generation, generationIdentity)
    }
}

internal class SessionObservationStore {
    private val lock = Any()
    private val owner = Any()
    private val mutableObservation = MutableStateFlow(SessionObservation.create())
    private var readyGeneration: Any? = null
    private var readyGenerationIdentity: SessionGenerationIdentity? = null

    internal val observation: StateFlow<SessionObservation> = mutableObservation.asStateFlow()

    internal fun resolve(
        capability: CurrentSessionObservation,
        expectedGeneration: Any,
    ): Any? = synchronized(lock) {
        expectedGeneration.takeIf { generation ->
            readyGeneration === generation &&
                capability.owner === owner &&
                capability.generation === generation &&
                mutableObservation.value.currentSession === capability
        }
    }

    internal fun currentObservation(
        capability: CurrentSessionObservation,
        expectedGeneration: Any,
    ): SessionObservation? = synchronized(lock) {
        mutableObservation.value.takeIf { observation ->
            readyGeneration === expectedGeneration &&
                capability.owner === owner &&
                capability.generation === expectedGeneration &&
                observation.currentSession === capability
        }
    }

    internal fun publishSessionState(
        state: SessionState,
        progressCapability: RecordingProgressCapability,
        generation: Any?,
    ) {
        synchronized(lock) {
            check(state !is SessionState.Ready || generation != null) {
                "A ready session observation requires an internal generation"
            }
            if (state is SessionState.Ready) {
                if (readyGeneration !== generation) {
                    readyGenerationIdentity = SessionGenerationIdentity.create()
                }
                readyGeneration = generation
            } else {
                readyGeneration = null
                readyGenerationIdentity = null
            }
            publishLocked(
                sessionState = state,
                recordingProgressCapability = progressCapability,
            )
        }
    }

    internal fun publishMetadata(
        channelState: ChannelRepositoryState,
        epgState: EpgRepositoryState,
        dvrState: DvrRepositoryState,
        configurationsState: DvrConfigurationsState,
        diskSpaceState: DvrDiskSpaceState,
    ) {
        synchronized(lock) {
            publishLocked(
                channelState = channelState,
                epgState = epgState,
                dvrState = dvrState,
                dvrConfigurationsState = configurationsState,
                dvrDiskSpaceState = diskSpaceState,
            )
        }
    }

    private fun publishLocked(
        sessionState: SessionState = mutableObservation.value.sessionState,
        channelState: ChannelRepositoryState = mutableObservation.value.channelState,
        epgState: EpgRepositoryState = mutableObservation.value.epgState,
        dvrState: DvrRepositoryState = mutableObservation.value.dvrState,
        dvrConfigurationsState: DvrConfigurationsState =
            mutableObservation.value.dvrConfigurationsState,
        dvrDiskSpaceState: DvrDiskSpaceState = mutableObservation.value.dvrDiskSpaceState,
        recordingProgressCapability: RecordingProgressCapability =
            mutableObservation.value.recordingProgressCapability,
    ) {
        val previous = mutableObservation.value
        mutableObservation.value = SessionObservation.createOwned(
            sessionState = sessionState,
            channelState = channelState,
            epgState = epgState,
            dvrState = dvrState,
            dvrConfigurationsState = dvrConfigurationsState,
            dvrDiskSpaceState = dvrDiskSpaceState,
            recordingProgressCapability = recordingProgressCapability,
            owner = owner,
            generation = readyGeneration,
            generationIdentity = readyGenerationIdentity,
            previousCurrent = previous.currentSession,
        )
    }
}
