@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.core.cache.CacheNamespace
import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi

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
    @ExperimentalTvheadendApi public val channelRefreshTimes: MetadataRefreshTimes,
    @ExperimentalTvheadendApi public val epgRefreshTimes: MetadataRefreshTimes,
    @ExperimentalTvheadendApi public val dvrRefreshTimes: MetadataRefreshTimes,
    queryTimes: Map<ChannelId, Instant>,
    private val ownAccountUsername: String?,
    /** Opaque identity of the profile whose data this observation contains, if any. */
    internal val profileIdentity: CacheNamespace?,
) {
    /** Last locally accepted successful query per channel; absence means unknown. */
    @ExperimentalTvheadendApi
    public val epgLastQueriedAt: Map<ChannelId, Instant> =
        Collections.unmodifiableMap(LinkedHashMap(queryTimes))

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
        epgSnapshotForDisplay?.eventsById?.get(id)

    /**
     * Display-only lookup using closed start and open stop boundaries. Live schedule wins over
     * bounded, already-received history. A result never authorizes recording or other actions.
     */
    public fun eventAt(channelId: ChannelId, at: Instant): EpgEvent? =
        epgSnapshotForDisplay?.eventsByChannel?.get(channelId)?.firstOrNull { event ->
            event.start <= at && at < event.stop
        }
            ?: epgSnapshotForDisplay?.historyByChannel?.get(channelId)?.firstOrNull { event ->
                event.start <= at && at < event.stop
            }

    /**
     * Selects the event after the event active at [at], or the earliest future event in a gap.
     *
     * An authoritative in-snapshot `nextEventId` wins when it names a same-channel event that does
     * not overlap the active event. Otherwise timing and then event ID provide deterministic order.
     */
    public fun nextEvent(channelId: ChannelId, at: Instant): EpgEvent? {
        val snapshot = epgSnapshotForDisplay ?: return null
        val channelEvents = snapshot.eventsByChannel[channelId] ?: return null
        val active = channelEvents.firstOrNull { event ->
            event.start <= at && at < event.stop
        }
        if (active != null) {
            val linked = active.nextEventId?.let { id ->
                snapshot.eventsById[id]
            }
            if (linked?.channelId == channelId && linked.start >= active.stop) return linked
        }
        val boundary = active?.stop ?: at
        return channelEvents.asSequence()
            .filter { event -> event !== active && event.start >= boundary }
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

    /**
     * Selects the highest-ranked linked DVR entry: own account, recording (including recording
     * error), scheduled, event's DVR link, latest stop, then lowest entry ID, in that order.
     * Ownership uses exact comparison with the normalized session username; anonymous sessions
     * and absent owners receive no ownership preference. Unknown stops rank last.
     * Tvheadend's event DVR link is only the first visible broadcast match, not an authority
     * (`src/htsp_server.c`, event serialization). Multiple entries per broadcast are normal.
     * Returns null for an unknown or ambiguous event ID, when no linked entry exists,
     * or when matched entries are ambiguous because they contain duplicate DVR entry IDs.
     */
    public fun dvrEntryForEvent(eventId: EventId): DvrEntry? =
        event(eventId)?.let { rankedDvrEntriesForEvent(it).firstOrNull() }

    /**
     * Ranks entries for a held [event], including query results and historical programmes absent
     * from this observation's EPG snapshot, using the same order as the event-ID selector.
     * Only explicit links participate: entry event ID or the held event's DVR entry ID.
     * The held event's [EpgEvent.dvrEntryId] is used as given and may be stale. Event and DVR entry
     * IDs are server-scoped: the held event must come from this observation's server profile, so
     * drop held events on profile switch.
     * Returns null when no entries match or matched DVR entry IDs are ambiguous.
     */
    @ExperimentalTvheadendApi
    public fun dvrEntryForEvent(event: EpgEvent): DvrEntry? = rankedDvrEntriesForEvent(event).firstOrNull()

    /**
     * Returns the event-ID `dvrEntryForEvent` selection and the remaining linked scheduled or
     * recording entries, including recording errors, in the same rank order. Null has the same
     * meaning as for the event-ID `dvrEntryForEvent` overload. Only explicit event/entry links participate, not time-window matches.
     */
    @ExperimentalTvheadendApi
    public fun dvrEntriesForEvent(eventId: EventId): DvrEventEntries? =
        event(eventId)?.let { dvrEntriesForEvent(it) }

    /**
     * Returns the held-event selection plus the remaining active entries in the same rank order.
     * The [event] is used as given without an EPG snapshot lookup; its [EpgEvent.dvrEntryId] may
     * be stale. The held event must come from this observation's server profile because event and
     * DVR entry IDs are server-scoped. Matching uses only explicit links. Returns null when no
     * entries match or matched DVR entry IDs are ambiguous, just like the held-event
     * `dvrEntryForEvent` overload.
     */
    @ExperimentalTvheadendApi
    public fun dvrEntriesForEvent(event: EpgEvent): DvrEventEntries? {
        val ranked = rankedDvrEntriesForEvent(event)
        val selected = ranked.firstOrNull() ?: return null
        return DvrEventEntries.create(selected, ranked.drop(1).filter { entry ->
            entry.state == DvrEntryState.SCHEDULED || entry.state == DvrEntryState.RECORDING ||
                entry.state == DvrEntryState.RECORDING_ERROR
        })
    }

    private fun rankedDvrEntriesForEvent(event: EpgEvent): List<DvrEntry> {
        val entries = dvrSnapshotForDisplay?.entries ?: return emptyList()
        val matched = entries.filter { entry ->
            entry.eventId == event.id || event.dvrEntryId == entry.id
        }
        if (matched.map { it.id }.toSet().size != matched.size) return emptyList()
        val ranking = compareByDescending<DvrEntry> { entry ->
            ownAccountUsername != null && entry.owner == ownAccountUsername
        }.thenBy { entry ->
            when (entry.state) {
                DvrEntryState.RECORDING, DvrEntryState.RECORDING_ERROR -> 0
                DvrEntryState.SCHEDULED -> 1
                else -> 2
            }
        }.thenByDescending { entry -> entry.id == event.dvrEntryId }
            .thenByDescending { entry -> entry.stop }
            .thenBy { entry -> entry.id.value }
        return matched.sortedWith(ranking)
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
            channelRefreshTimes == other.channelRefreshTimes &&
            epgRefreshTimes == other.epgRefreshTimes &&
            dvrRefreshTimes == other.dvrRefreshTimes &&
            epgLastQueriedAt == other.epgLastQueriedAt &&
            profileIdentity == other.profileIdentity &&
            currentSession === other.currentSession

    override fun hashCode(): Int {
        var result = sessionState.hashCode()
        result = 31 * result + channelState.hashCode()
        result = 31 * result + epgState.hashCode()
        result = 31 * result + dvrState.hashCode()
        result = 31 * result + dvrConfigurationsState.hashCode()
        result = 31 * result + dvrDiskSpaceState.hashCode()
        result = 31 * result + recordingProgressCapability.hashCode()
        result = 31 * result + channelRefreshTimes.hashCode()
        result = 31 * result + epgRefreshTimes.hashCode()
        result = 31 * result + dvrRefreshTimes.hashCode()
        result = 31 * result + epgLastQueriedAt.hashCode()
        result = 31 * result + profileIdentity.hashCode()
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
        ): SessionObservation = create(
            channelRefreshTimes = MetadataRefreshTimes(),
            sessionState = sessionState,
            channelState = channelState,
            epgState = epgState,
            dvrState = dvrState,
            dvrConfigurationsState = dvrConfigurationsState,
            dvrDiskSpaceState = dvrDiskSpaceState,
            recordingProgressCapability = recordingProgressCapability,
        )

        /** Creates an immutable observation with explicit local refresh bookkeeping. */
        @ExperimentalTvheadendApi
        public fun create(
            channelRefreshTimes: MetadataRefreshTimes,
            epgRefreshTimes: MetadataRefreshTimes = MetadataRefreshTimes(),
            dvrRefreshTimes: MetadataRefreshTimes = MetadataRefreshTimes(),
            epgLastQueriedAt: Map<ChannelId, Instant> = emptyMap(),
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
                channelRefreshTimes = channelRefreshTimes,
                epgRefreshTimes = epgRefreshTimes,
                dvrRefreshTimes = dvrRefreshTimes,
                epgLastQueriedAt = epgLastQueriedAt,
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
            channelRefreshTimes: MetadataRefreshTimes = MetadataRefreshTimes(),
            epgRefreshTimes: MetadataRefreshTimes = MetadataRefreshTimes(),
            dvrRefreshTimes: MetadataRefreshTimes = MetadataRefreshTimes(),
            epgLastQueriedAt: Map<ChannelId, Instant> = emptyMap(),
            ownAccountUsername: String? = null,
            profileIdentity: CacheNamespace? = null,
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
                channelRefreshTimes = channelRefreshTimes,
                epgRefreshTimes = epgRefreshTimes,
                dvrRefreshTimes = dvrRefreshTimes,
                queryTimes = epgLastQueriedAt,
                ownAccountUsername = ownAccountUsername,
                profileIdentity = profileIdentity,
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
    private var ownAccountUsername: String? = null

    // Account changes require a reset that clears DVR state before publishing new metadata.
    // Username is excluded from equality, so MutableStateFlow may retain an equal old instance;
    // the reset prevents carrying that instance's account into a new account's DVR snapshot.
    // Same-profile reconnects retain both the account and the published snapshot.
    internal fun selectAccount(username: String?) = synchronized(lock) {
        ownAccountUsername = username
    }

    // Every publication carries the identity of the profile whose data it contains, so
    // profile-bound lookups (saved artwork) never resolve one profile's ids under another. The
    // owner keeps it atomic with the data: a full clear drops it under the metadata lock before
    // publishing the emptied data, a new profile's identity is selected before that profile's
    // worker exists, and same-profile reconnects retain both. A publication between dropping the
    // identity and publishing the emptied data is intentionally safe: it pairs the old data with
    // no identity, and a null identity resolves nothing. Equality includes the identity,
    // so a MutableStateFlow never keeps an equal instance carrying a stale one.
    private var profileIdentity: CacheNamespace? = null

    /** Selects the identity for subsequent publications and republishes when it changed. */
    internal fun selectProfile(identity: CacheNamespace?) = synchronized(lock) {
        if (profileIdentity == identity) return@synchronized
        profileIdentity = identity
        publishLocked()
    }

    /** Drops the identity without publishing; the caller's full clear publishes next. */
    internal fun clearProfile() = synchronized(lock) {
        profileIdentity = null
    }

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
        channelRefreshTimes: MetadataRefreshTimes = MetadataRefreshTimes(),
        epgRefreshTimes: MetadataRefreshTimes = MetadataRefreshTimes(),
        dvrRefreshTimes: MetadataRefreshTimes = MetadataRefreshTimes(),
        epgLastQueriedAt: Map<ChannelId, Instant> = emptyMap(),
    ) {
        synchronized(lock) {
            publishLocked(
                channelState = channelState,
                epgState = epgState,
                dvrState = dvrState,
                dvrConfigurationsState = configurationsState,
                dvrDiskSpaceState = diskSpaceState,
                channelRefreshTimes = channelRefreshTimes,
                epgRefreshTimes = epgRefreshTimes,
                dvrRefreshTimes = dvrRefreshTimes,
                epgLastQueriedAt = epgLastQueriedAt,
            )
        }
    }

    internal fun publishObservation(
        observation: SessionObservation,
        generation: Any?,
    ) {
        synchronized(lock) {
            check(observation.sessionState !is SessionState.Ready || generation != null) {
                "A ready session observation requires an internal generation"
            }
            if (observation.sessionState is SessionState.Ready) {
                if (readyGeneration !== generation) {
                    readyGenerationIdentity = SessionGenerationIdentity.create()
                }
                readyGeneration = generation
            } else {
                readyGeneration = null
                readyGenerationIdentity = null
            }
            publishLocked(
                sessionState = observation.sessionState,
                channelState = observation.channelState,
                epgState = observation.epgState,
                dvrState = observation.dvrState,
                dvrConfigurationsState = observation.dvrConfigurationsState,
                dvrDiskSpaceState = observation.dvrDiskSpaceState,
                recordingProgressCapability = observation.recordingProgressCapability,
                channelRefreshTimes = observation.channelRefreshTimes,
                epgRefreshTimes = observation.epgRefreshTimes,
                dvrRefreshTimes = observation.dvrRefreshTimes,
                epgLastQueriedAt = observation.epgLastQueriedAt,
            )
        }
    }

    /** Publishes [observation] together with [profileIdentity] as one atomic replacement. */
    internal fun publishObservation(
        observation: SessionObservation,
        generation: Any?,
        profileIdentity: CacheNamespace?,
    ) {
        synchronized(lock) {
            this.profileIdentity = profileIdentity
            publishObservation(observation, generation)
        }
    }

    private fun publishLocked(
        channelRefreshTimes: MetadataRefreshTimes = mutableObservation.value.channelRefreshTimes,
        epgRefreshTimes: MetadataRefreshTimes = mutableObservation.value.epgRefreshTimes,
        dvrRefreshTimes: MetadataRefreshTimes = mutableObservation.value.dvrRefreshTimes,
        epgLastQueriedAt: Map<ChannelId, Instant> = mutableObservation.value.epgLastQueriedAt,
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
            ownAccountUsername = ownAccountUsername,
            profileIdentity = profileIdentity,
            channelRefreshTimes = channelRefreshTimes,
            epgRefreshTimes = epgRefreshTimes,
            dvrRefreshTimes = dvrRefreshTimes,
            epgLastQueriedAt = epgLastQueriedAt,
        )
    }
}
