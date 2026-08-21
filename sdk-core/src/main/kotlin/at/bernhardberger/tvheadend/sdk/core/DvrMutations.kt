package at.bernhardberger.tvheadend.sdk.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val DVR_MUTATION_U32_MAX: Long = 0xffff_ffffL

/** Programme or explicit wall-clock selection used to schedule one recording. */
public sealed interface DvrSchedule {
    /** Schedules the programme identified by [eventId]. */
    public data class Programme(
        public val eventId: EventId,
    ) : DvrSchedule {
        override fun toString(): String = "DvrSchedule.Programme(<redacted>)"
    }

    /** Schedules one channel from [start] through [stop]. */
    public data class ExplicitTime(
        public val channelId: ChannelId,
        public val start: Instant,
        public val stop: Instant,
    ) : DvrSchedule {
        init {
            require(stop >= start) { "DvrSchedule stop must not precede start" }
        }

        override fun toString(): String = "DvrSchedule.ExplicitTime(<redacted>)"
    }
}

/** Complete request for scheduling one DVR entry. */
public data class DvrScheduleRequest(
    public val schedule: DvrSchedule,
    public val configId: DvrConfigId? = null,
    public val language: String? = null,
    public val title: String? = null,
    public val subtitle: String? = null,
    public val summary: String? = null,
    public val description: String? = null,
    public val ageRating: Long? = null,
) {
    init {
        ageRating?.requireDvrMutationU32("DvrScheduleRequest ageRating")
    }

    override fun toString(): String = "DvrScheduleRequest(<redacted>)"
}

/** Partial DVR-entry change; null fields are omitted from the command. */
public data class DvrEntryUpdate(
    public val channelId: ChannelId? = null,
    public val configId: DvrConfigId? = null,
    public val title: String? = null,
    public val subtitle: String? = null,
    public val summary: String? = null,
    public val description: String? = null,
    public val language: String? = null,
    public val comment: String? = null,
    public val enabled: Boolean? = null,
    public val start: Instant? = null,
    public val stop: Instant? = null,
    public val startExtraMinutes: Long? = null,
    public val stopExtraMinutes: Long? = null,
    public val retentionDays: Long? = null,
    public val removalDays: Long? = null,
    public val priority: Long? = null,
    public val ageRating: Long? = null,
) {
    init {
        if (start != null && stop != null) {
            require(stop >= start) { "DvrEntryUpdate stop must not precede start" }
        }
        retentionDays?.requireDvrMutationU32("DvrEntryUpdate retentionDays")
        removalDays?.requireDvrMutationU32("DvrEntryUpdate removalDays")
        priority?.requireDvrMutationU32("DvrEntryUpdate priority")
        ageRating?.requireDvrMutationU32("DvrEntryUpdate ageRating")
    }

    override fun toString(): String = "DvrEntryUpdate(<redacted>)"
}

/** Channel selection used by automatic and time-based recording rules. */
public sealed interface RecordingRuleChannel {
    /** Limits the rule to [channelId]. */
    public data class SpecificChannel(
        public val channelId: ChannelId,
    ) : RecordingRuleChannel {
        override fun toString(): String = "RecordingRuleChannel.SpecificChannel(<redacted>)"
    }

    /** Applies the rule to every visible channel. */
    public data object AllChannels : RecordingRuleChannel
}

/** Complete request for creating an automatic-recording rule. */
public data class AutorecRuleCreate(
    public val title: String,
    public val channel: RecordingRuleChannel? = null,
    public val minDuration: Duration? = null,
    public val maxDuration: Duration? = null,
    public val fullText: Boolean? = null,
    public val mergeText: Boolean? = null,
    public val duplicateDetection: Long? = null,
    public val maximumRecordingCount: Long? = null,
    public val broadcastType: Long? = null,
    public val startExtraMinutes: Long? = null,
    public val stopExtraMinutes: Long? = null,
    public val seriesLinkUri: String? = null,
    public val approximateStartMinutesSinceMidnight: Int? = null,
    public val startMinutesSinceMidnight: Int? = null,
    public val startWindowEndMinutesSinceMidnight: Int? = null,
    public val enabled: Boolean? = null,
    public val retentionDays: Long? = null,
    public val removalDays: Long? = null,
    public val priority: Long? = null,
    public val name: String? = null,
    public val comment: String? = null,
    public val directory: String? = null,
    public val configId: DvrConfigId? = null,
    public val daysOfWeekMask: Long? = null,
) {
    init {
        validateAutorecMutation(
            minDuration = minDuration,
            maxDuration = maxDuration,
            duplicateDetection = duplicateDetection,
            maximumRecordingCount = maximumRecordingCount,
            broadcastType = broadcastType,
            approximateStartMinutesSinceMidnight = approximateStartMinutesSinceMidnight,
            startMinutesSinceMidnight = startMinutesSinceMidnight,
            startWindowEndMinutesSinceMidnight = startWindowEndMinutesSinceMidnight,
            retentionDays = retentionDays,
            removalDays = removalDays,
            priority = priority,
            daysOfWeekMask = daysOfWeekMask,
        )
    }

    override fun toString(): String = "AutorecRuleCreate(<redacted>)"
}

/** Partial automatic-recording-rule change; null fields are omitted from the command. */
public data class AutorecRuleUpdate(
    public val channel: RecordingRuleChannel? = null,
    public val minDuration: Duration? = null,
    public val maxDuration: Duration? = null,
    public val fullText: Boolean? = null,
    public val mergeText: Boolean? = null,
    public val duplicateDetection: Long? = null,
    public val maximumRecordingCount: Long? = null,
    public val broadcastType: Long? = null,
    public val startExtraMinutes: Long? = null,
    public val stopExtraMinutes: Long? = null,
    public val seriesLinkUri: String? = null,
    public val startMinutesSinceMidnight: Int? = null,
    public val startWindowEndMinutesSinceMidnight: Int? = null,
    public val enabled: Boolean? = null,
    public val retentionDays: Long? = null,
    public val removalDays: Long? = null,
    public val priority: Long? = null,
    public val name: String? = null,
    public val comment: String? = null,
    public val directory: String? = null,
    public val title: String? = null,
    public val configId: DvrConfigId? = null,
    public val daysOfWeekMask: Long? = null,
) {
    init {
        validateAutorecMutation(
            minDuration = minDuration,
            maxDuration = maxDuration,
            duplicateDetection = duplicateDetection,
            maximumRecordingCount = maximumRecordingCount,
            broadcastType = broadcastType,
            approximateStartMinutesSinceMidnight = null,
            startMinutesSinceMidnight = startMinutesSinceMidnight,
            startWindowEndMinutesSinceMidnight = startWindowEndMinutesSinceMidnight,
            retentionDays = retentionDays,
            removalDays = removalDays,
            priority = priority,
            daysOfWeekMask = daysOfWeekMask,
        )
    }

    override fun toString(): String = "AutorecRuleUpdate(<redacted>)"
}

/** Complete request for creating a time-based recording rule. */
public data class TimerecRuleCreate(
    public val title: String,
    public val channel: RecordingRuleChannel? = null,
    public val startMinutesSinceMidnight: Int? = null,
    public val stopMinutesSinceMidnight: Int? = null,
    public val enabled: Boolean? = null,
    public val retentionDays: Long? = null,
    public val removalDays: Long? = null,
    public val priority: Long? = null,
    public val name: String? = null,
    public val comment: String? = null,
    public val directory: String? = null,
    public val configId: DvrConfigId? = null,
    public val daysOfWeekMask: Long? = null,
) {
    init {
        validateTimerecMutation(
            startMinutesSinceMidnight,
            stopMinutesSinceMidnight,
            retentionDays,
            removalDays,
            priority,
            daysOfWeekMask,
        )
    }

    override fun toString(): String = "TimerecRuleCreate(<redacted>)"
}

/** Partial time-based recording-rule change; null fields are omitted from the command. */
public data class TimerecRuleUpdate(
    public val channel: RecordingRuleChannel? = null,
    public val startMinutesSinceMidnight: Int? = null,
    public val stopMinutesSinceMidnight: Int? = null,
    public val enabled: Boolean? = null,
    public val retentionDays: Long? = null,
    public val removalDays: Long? = null,
    public val priority: Long? = null,
    public val name: String? = null,
    public val comment: String? = null,
    public val directory: String? = null,
    public val title: String? = null,
    public val configId: DvrConfigId? = null,
    public val daysOfWeekMask: Long? = null,
) {
    init {
        validateTimerecMutation(
            startMinutesSinceMidnight,
            stopMinutesSinceMidnight,
            retentionDays,
            removalDays,
            priority,
            daysOfWeekMask,
        )
    }

    override fun toString(): String = "TimerecRuleUpdate(<redacted>)"
}

/** Typed outcome of one generation-bound DVR mutation. */
public sealed interface DvrMutationResult<out T> {
    /** The command was accepted and matching authoritative metadata was published. */
    public class Confirmed<out T>(public val value: T) : DvrMutationResult<T> {
        override fun toString(): String = "DvrMutationResult.Confirmed(<redacted>)"
    }

    /** The command was accepted, but no matching metadata arrived before the confirmation deadline. */
    public class AcceptedButUnconfirmed<out T>(public val value: T) : DvrMutationResult<T> {
        override fun toString(): String = "DvrMutationResult.AcceptedButUnconfirmed(<redacted>)"
    }

    /** The session has not admitted mutations for a synchronized generation. */
    public data object NotReady : DvrMutationResult<Nothing>

    /** The server rejected the command without a safe detailed reason. */
    public data object ServerRejected : DvrMutationResult<Nothing>

    /** The authenticated session lacks recorder permission. */
    public data object AccessDenied : DvrMutationResult<Nothing>

    /** The server refused another concurrent operation. */
    public data object ConnectionLimit : DvrMutationResult<Nothing>

    /** The command was not accepted before its protocol deadline. */
    public data object Timeout : DvrMutationResult<Nothing>

    /** The bound transport generation is unavailable. */
    public data object TransportUnavailable : DvrMutationResult<Nothing>

    /** The connected server does not support the command. */
    public data object NotSupported : DvrMutationResult<Nothing>
}

internal interface DvrMutationCommands {
    public suspend fun scheduleEntry(request: DvrScheduleRequest): DvrMutationResult<DvrEntryId>

    public suspend fun updateEntry(
        id: DvrEntryId,
        update: DvrEntryUpdate,
    ): DvrMutationResult<Unit>

    public suspend fun stopEntry(id: DvrEntryId): DvrMutationResult<Unit>

    public suspend fun cancelEntry(id: DvrEntryId): DvrMutationResult<Unit>

    public suspend fun deleteEntry(id: DvrEntryId): DvrMutationResult<Unit>

    public suspend fun createAutorecRule(
        request: AutorecRuleCreate,
    ): DvrMutationResult<AutorecRuleId>

    public suspend fun updateAutorecRule(
        id: AutorecRuleId,
        update: AutorecRuleUpdate,
    ): DvrMutationResult<Unit>

    public suspend fun deleteAutorecRule(id: AutorecRuleId): DvrMutationResult<Unit>

    public suspend fun createTimerecRule(
        request: TimerecRuleCreate,
    ): DvrMutationResult<TimerecRuleId>

    public suspend fun updateTimerecRule(
        id: TimerecRuleId,
        update: TimerecRuleUpdate,
    ): DvrMutationResult<Unit>

    public suspend fun deleteTimerecRule(id: TimerecRuleId): DvrMutationResult<Unit>

    data object None : DvrMutationCommands {
        override suspend fun scheduleEntry(request: DvrScheduleRequest): DvrMutationResult<DvrEntryId> =
            DvrMutationResult.NotReady

        override suspend fun updateEntry(
            id: DvrEntryId,
            update: DvrEntryUpdate,
        ): DvrMutationResult<Unit> = DvrMutationResult.NotReady

        override suspend fun stopEntry(id: DvrEntryId): DvrMutationResult<Unit> =
            DvrMutationResult.NotReady

        override suspend fun cancelEntry(id: DvrEntryId): DvrMutationResult<Unit> =
            DvrMutationResult.NotReady

        override suspend fun deleteEntry(id: DvrEntryId): DvrMutationResult<Unit> =
            DvrMutationResult.NotReady

        override suspend fun createAutorecRule(
            request: AutorecRuleCreate,
        ): DvrMutationResult<AutorecRuleId> = DvrMutationResult.NotReady

        override suspend fun updateAutorecRule(
            id: AutorecRuleId,
            update: AutorecRuleUpdate,
        ): DvrMutationResult<Unit> = DvrMutationResult.NotReady

        override suspend fun deleteAutorecRule(id: AutorecRuleId): DvrMutationResult<Unit> =
            DvrMutationResult.NotReady

        override suspend fun createTimerecRule(
            request: TimerecRuleCreate,
        ): DvrMutationResult<TimerecRuleId> = DvrMutationResult.NotReady

        override suspend fun updateTimerecRule(
            id: TimerecRuleId,
            update: TimerecRuleUpdate,
        ): DvrMutationResult<Unit> = DvrMutationResult.NotReady

        override suspend fun deleteTimerecRule(id: TimerecRuleId): DvrMutationResult<Unit> =
            DvrMutationResult.NotReady
    }
}

internal fun Duration.toDvrMutationSeconds(): Long = inWholeSeconds

private fun validateAutorecMutation(
    minDuration: Duration?,
    maxDuration: Duration?,
    duplicateDetection: Long?,
    maximumRecordingCount: Long?,
    broadcastType: Long?,
    approximateStartMinutesSinceMidnight: Int?,
    startMinutesSinceMidnight: Int?,
    startWindowEndMinutesSinceMidnight: Int?,
    retentionDays: Long?,
    removalDays: Long?,
    priority: Long?,
    daysOfWeekMask: Long?,
) {
    minDuration?.requireDvrMutationDuration("Autorec minDuration")
    maxDuration?.requireDvrMutationDuration("Autorec maxDuration")
    duplicateDetection?.requireDvrMutationU32("Autorec duplicateDetection")
    maximumRecordingCount?.requireDvrMutationU32("Autorec maximumRecordingCount")
    broadcastType?.requireDvrMutationU32("Autorec broadcastType")
    approximateStartMinutesSinceMidnight?.requireDvrMutationMinute("Autorec approximateStart")
    startMinutesSinceMidnight?.requireDvrMutationMinute("Autorec start")
    startWindowEndMinutesSinceMidnight?.requireDvrMutationMinute("Autorec startWindowEnd")
    retentionDays?.requireDvrMutationU32("Autorec retentionDays")
    removalDays?.requireDvrMutationU32("Autorec removalDays")
    priority?.requireDvrMutationU32("Autorec priority")
    daysOfWeekMask?.requireDvrMutationU32("Autorec daysOfWeekMask")
}

private fun validateTimerecMutation(
    startMinutesSinceMidnight: Int?,
    stopMinutesSinceMidnight: Int?,
    retentionDays: Long?,
    removalDays: Long?,
    priority: Long?,
    daysOfWeekMask: Long?,
) {
    startMinutesSinceMidnight?.requireDvrMutationMinute("Timerec start")
    stopMinutesSinceMidnight?.requireDvrMutationMinute("Timerec stop")
    retentionDays?.requireDvrMutationU32("Timerec retentionDays")
    removalDays?.requireDvrMutationU32("Timerec removalDays")
    priority?.requireDvrMutationU32("Timerec priority")
    daysOfWeekMask?.requireDvrMutationU32("Timerec daysOfWeekMask")
}

private fun Duration.requireDvrMutationDuration(name: String) {
    require(isFinite() && !isNegative()) { "$name must be a finite non-negative duration" }
    require(this == inWholeSeconds.seconds) { "$name must use whole seconds" }
    inWholeSeconds.requireDvrMutationU32(name)
}

private fun Int.requireDvrMutationMinute(name: String) {
    require(this in 0..1_440) { "$name must be between 0 and 1440" }
}

private fun Long.requireDvrMutationU32(name: String) {
    require(this in 0L..DVR_MUTATION_U32_MAX) { "$name must be an unsigned 32-bit value" }
}
