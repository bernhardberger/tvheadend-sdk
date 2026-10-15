package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.time.DayOfWeek
import java.time.LocalTime
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

/**
 * Complete request for scheduling one DVR entry.
 * An [DvrSchedule.ExplicitTime] schedule requires a non-empty [title]; whitespace is permitted.
 */
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
        require(schedule !is DvrSchedule.ExplicitTime || !title.isNullOrEmpty()) {
            "Explicit-time DVR scheduling requires a non-empty title"
        }
        ageRating?.requireDvrMutationU32("DvrScheduleRequest ageRating")
    }

    override fun toString(): String = "DvrScheduleRequest(<redacted>)"
}

/** Partial DVR-entry change; null fields are omitted except for scheduled-entry [configId] preservation. */
public data class DvrEntryUpdate(
    public val channelId: ChannelId? = null,
    /**
     * Configuration UUID to select, always resolved to a unique enabled, account-visible name,
     * even for an unobserved or non-scheduled entry. Null preserves only a last-observed scheduled
     * entry's configuration by resolving its observed UUID. Otherwise the field is omitted;
     * an unobserved entry that is scheduled server-side may move to the default configuration.
     * Missing or ambiguous configs fail with `OBSERVATION_EXPIRED` before sending the update.
     * This persists until an explicit visible, unambiguous config is selected or the server-side
     * config changes; refreshing alone does not clear it.
     * A server-side ACL config list can still redirect the name to the default configuration;
     * a rename between lookup and update can do the same. See [DvrRepository.updateEntry].
     */
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
    public val startExtraMinutes: Duration? = null,
    public val stopExtraMinutes: Duration? = null,
    public val retentionDays: Long? = null,
    public val removalDays: Long? = null,
    /** Priority to set; [DvrPriority.UNKNOWN] is rejected with [IllegalArgumentException]. */
    public val priority: DvrPriority? = null,
    public val ageRating: Long? = null,
) {
    init {
        if (start != null && stop != null) {
            require(stop >= start) { "DvrEntryUpdate stop must not precede start" }
        }
        retentionDays?.requireDvrMutationU32("DvrEntryUpdate retentionDays")
        removalDays?.requireDvrMutationU32("DvrEntryUpdate removalDays")
        startExtraMinutes?.requireDvrExtraMinutes()
        stopExtraMinutes?.requireDvrExtraMinutes()
        ageRating?.requireDvrMutationU32("DvrEntryUpdate ageRating")
        require(priority != DvrPriority.UNKNOWN) { "DVR priority must be known" }
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
    /** Policy to set; [DuplicateDetection.UNKNOWN] is rejected with [IllegalArgumentException]. */
    public val duplicateDetection: DuplicateDetection? = null,
    public val maximumRecordingCount: Long? = null,
    /** Selection to set; [BroadcastType.UNKNOWN] is rejected with [IllegalArgumentException]. */
    public val broadcastType: BroadcastType? = null,
    public val startExtraMinutes: Duration? = null,
    public val stopExtraMinutes: Duration? = null,
    public val seriesLinkUri: String? = null,
    public val approximateStartMinutesSinceMidnight: AutorecTime? = null,
    public val startMinutesSinceMidnight: AutorecTime? = null,
    public val startWindowEndMinutesSinceMidnight: AutorecTime? = null,
    public val enabled: Boolean? = null,
    public val retentionDays: Long? = null,
    public val removalDays: Long? = null,
    /** Priority to set; [DvrPriority.UNKNOWN] is rejected with [IllegalArgumentException]. */
    public val priority: DvrPriority? = null,
    public val name: String? = null,
    public val comment: String? = null,
    public val directory: String? = null,
    public val configId: DvrConfigId? = null,
    public val daysOfWeekMask: Set<DayOfWeek>? = null,
) {
    init {
        validateAutorecMutation(
            minDuration = minDuration,
            maxDuration = maxDuration,
            maximumRecordingCount = maximumRecordingCount,
            retentionDays = retentionDays,
            removalDays = removalDays,
        )
        startExtraMinutes?.requireDvrExtraMinutes()
        stopExtraMinutes?.requireDvrExtraMinutes()
        require(priority != DvrPriority.UNKNOWN) { "DVR priority must be known" }
        require(duplicateDetection != DuplicateDetection.UNKNOWN) { "DVR duplicate policy must be known" }
        require(broadcastType != BroadcastType.UNKNOWN) { "DVR broadcast selection must be known" }
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
    /** Policy to set; [DuplicateDetection.UNKNOWN] is rejected with [IllegalArgumentException]. */
    public val duplicateDetection: DuplicateDetection? = null,
    public val maximumRecordingCount: Long? = null,
    /** Selection to set; [BroadcastType.UNKNOWN] is rejected with [IllegalArgumentException]. */
    public val broadcastType: BroadcastType? = null,
    public val startExtraMinutes: Duration? = null,
    public val stopExtraMinutes: Duration? = null,
    public val seriesLinkUri: String? = null,
    public val startMinutesSinceMidnight: AutorecTime? = null,
    public val startWindowEndMinutesSinceMidnight: AutorecTime? = null,
    public val enabled: Boolean? = null,
    public val retentionDays: Long? = null,
    public val removalDays: Long? = null,
    /** Priority to set; [DvrPriority.UNKNOWN] is rejected with [IllegalArgumentException]. */
    public val priority: DvrPriority? = null,
    public val name: String? = null,
    public val comment: String? = null,
    public val directory: String? = null,
    public val title: String? = null,
    public val configId: DvrConfigId? = null,
    public val daysOfWeekMask: Set<DayOfWeek>? = null,
) {
    init {
        validateAutorecMutation(
            minDuration = minDuration,
            maxDuration = maxDuration,
            maximumRecordingCount = maximumRecordingCount,
            retentionDays = retentionDays,
            removalDays = removalDays,
        )
        startExtraMinutes?.requireDvrExtraMinutes()
        stopExtraMinutes?.requireDvrExtraMinutes()
        require(priority != DvrPriority.UNKNOWN) { "DVR priority must be known" }
        require(duplicateDetection != DuplicateDetection.UNKNOWN) { "DVR duplicate policy must be known" }
        require(broadcastType != BroadcastType.UNKNOWN) { "DVR broadcast selection must be known" }
    }

    override fun toString(): String = "AutorecRuleUpdate(<redacted>)"
}

/** Complete request for creating a time-based rule; local times must have whole-minute precision. */
public data class TimerecRuleCreate(
    public val title: String,
    public val channel: RecordingRuleChannel? = null,
    public val startMinutesSinceMidnight: LocalTime? = null,
    public val stopMinutesSinceMidnight: LocalTime? = null,
    public val enabled: Boolean? = null,
    public val retentionDays: Long? = null,
    public val removalDays: Long? = null,
    /** Priority to set; [DvrPriority.UNKNOWN] is rejected with [IllegalArgumentException]. */
    public val priority: DvrPriority? = null,
    public val name: String? = null,
    public val comment: String? = null,
    public val directory: String? = null,
    public val configId: DvrConfigId? = null,
    public val daysOfWeekMask: Set<DayOfWeek>? = null,
) {
    init {
        validateTimerecMutation(
            startMinutesSinceMidnight,
            stopMinutesSinceMidnight,
            retentionDays,
            removalDays,
        )
        require(priority != DvrPriority.UNKNOWN) { "DVR priority must be known" }
    }

    override fun toString(): String = "TimerecRuleCreate(<redacted>)"
}

/** Partial time-based rule change; null fields are omitted, local times must have whole-minute precision. */
public data class TimerecRuleUpdate(
    public val channel: RecordingRuleChannel? = null,
    public val startMinutesSinceMidnight: LocalTime? = null,
    public val stopMinutesSinceMidnight: LocalTime? = null,
    public val enabled: Boolean? = null,
    public val retentionDays: Long? = null,
    public val removalDays: Long? = null,
    /** Priority to set; [DvrPriority.UNKNOWN] is rejected with [IllegalArgumentException]. */
    public val priority: DvrPriority? = null,
    public val name: String? = null,
    public val comment: String? = null,
    public val directory: String? = null,
    public val title: String? = null,
    public val configId: DvrConfigId? = null,
    public val daysOfWeekMask: Set<DayOfWeek>? = null,
) {
    init {
        validateTimerecMutation(
            startMinutesSinceMidnight,
            stopMinutesSinceMidnight,
            retentionDays,
            removalDays,
        )
        require(priority != DvrPriority.UNKNOWN) { "DVR priority must be known" }
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

    /**
     * The mutation failed with a safe classification. Access denial means recorder permission is
     * missing; unsupported means the server cannot perform this command. A malformed reply or
     * lost confirmation may follow server acceptance: never blindly retry a mutation.
     *
     * [mayHaveApplied] is false only when the command was never sent or explicitly refused.
     * It is true whenever the command may have reached the server, including failure after
     * acceptance while registering or awaiting authoritative metadata confirmation.
     * Generation retirement reports [TvheadendFailure.CONNECTION_CHANGED]. Shutdown or revoked
     * admission reports [TvheadendFailure.NOT_READY] before dispatch on the latest observation;
     * older observations still report [TvheadendFailure.OBSERVATION_EXPIRED]. It reports CONNECTION_CHANGED after
     * dispatch. This classification does not override explicit refusal evidence in [mayHaveApplied].
     */
    public class Failed private constructor(
        private val displayMessage: String?,
        public val failure: TvheadendFailure,
        public val mayHaveApplied: Boolean,
    ) : DvrMutationResult<Nothing> {
        public constructor(failure: TvheadendFailure, mayHaveApplied: Boolean) : this(null, failure, mayHaveApplied)

        /**
         * Untrusted, display-only server text, sanitized and bounded to 256 UTF-16 code units.
         * Only the first 1024 UTF-16 code units are processed; neither bound splits surrogate pairs.
         * Control, format and line/paragraph separator code points are replaced with spaces.
         * Tvheadend translates fixed catalogue errors into the connection/account language;
         * never match this text for control flow. Null unless [failure] is SERVER_REJECTED.
         * Never include it in logs or diagnostics. Equality and hashing ignore this message.
         */
        @at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
        public val serverMessage: String? get() = displayMessage

        /** Creates a failure with optional display-only text, including for consumer fakes. */
        @at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
        public constructor(
            failure: TvheadendFailure,
            mayHaveApplied: Boolean,
            serverMessage: String?,
        ) : this(
            if (failure == TvheadendFailure.SERVER_REJECTED) {
                sanitizeDvrServerMessage(serverMessage)
            } else {
                null
            },
            failure,
            mayHaveApplied,
        )

        override fun equals(other: Any?): Boolean =
            other is Failed && failure == other.failure && mayHaveApplied == other.mayHaveApplied

        override fun hashCode(): Int = 31 * failure.hashCode() + mayHaveApplied.hashCode()

        override fun toString(): String =
            "DvrMutationResult.Failed(failure=$failure, mayHaveApplied=$mayHaveApplied)"
    }
}

private fun sanitizeDvrServerMessage(message: String?): String? {
    val prefix = (message ?: return null).boundedUtf16Prefix(1024)
    val normalized = java.text.Normalizer.normalize(prefix, java.text.Normalizer.Form.NFC)
    val sanitized = buildString {
        var offset = 0
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            val type = Character.getType(codePoint)
            if (Character.isISOControl(codePoint) || type == Character.FORMAT.toInt() ||
                type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
            ) {
                append(' ')
            } else {
                appendCodePoint(codePoint)
            }
            offset += Character.charCount(codePoint)
        }
    }.trim()
    return sanitized.boundedUtf16Prefix(256).trim().takeIf { it.isNotEmpty() }
}

private fun String.boundedUtf16Prefix(limit: Int): String {
    val end = if (length > limit && this[limit - 1].isHighSurrogate() && this[limit].isLowSurrogate()) {
        limit - 1
    } else {
        minOf(limit, length)
    }
    return take(end)
}

internal interface DvrMutationCommands {
    public suspend fun scheduleEntry(
        generation: GatewayGeneration,
        request: DvrScheduleRequest,
    ): DvrMutationResult<DvrEntryId>

    public suspend fun updateEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
        update: DvrEntryUpdate,
    ): DvrMutationResult<Unit>

    public suspend fun stopEntry(generation: GatewayGeneration, id: DvrEntryId): DvrMutationResult<Unit>

    public suspend fun cancelEntry(generation: GatewayGeneration, id: DvrEntryId): DvrMutationResult<Unit>

    public suspend fun deleteEntry(generation: GatewayGeneration, id: DvrEntryId): DvrMutationResult<Unit>

    public suspend fun createAutorecRule(
        generation: GatewayGeneration,
        request: AutorecRuleCreate,
    ): DvrMutationResult<AutorecRuleId>

    public suspend fun updateAutorecRule(
        generation: GatewayGeneration,
        id: AutorecRuleId,
        update: AutorecRuleUpdate,
    ): DvrMutationResult<Unit>

    public suspend fun deleteAutorecRule(
        generation: GatewayGeneration,
        id: AutorecRuleId,
    ): DvrMutationResult<Unit>

    public suspend fun createTimerecRule(
        generation: GatewayGeneration,
        request: TimerecRuleCreate,
    ): DvrMutationResult<TimerecRuleId>

    public suspend fun updateTimerecRule(
        generation: GatewayGeneration,
        id: TimerecRuleId,
        update: TimerecRuleUpdate,
    ): DvrMutationResult<Unit>

    public suspend fun deleteTimerecRule(
        generation: GatewayGeneration,
        id: TimerecRuleId,
    ): DvrMutationResult<Unit>

    data object None : DvrMutationCommands {
        override suspend fun scheduleEntry(
            generation: GatewayGeneration,
            request: DvrScheduleRequest,
        ): DvrMutationResult<DvrEntryId> =
            DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)

        override suspend fun updateEntry(
            generation: GatewayGeneration,
            id: DvrEntryId,
            update: DvrEntryUpdate,
        ): DvrMutationResult<Unit> = DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)

        override suspend fun stopEntry(
            generation: GatewayGeneration,
            id: DvrEntryId,
        ): DvrMutationResult<Unit> =
            DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)

        override suspend fun cancelEntry(
            generation: GatewayGeneration,
            id: DvrEntryId,
        ): DvrMutationResult<Unit> =
            DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)

        override suspend fun deleteEntry(
            generation: GatewayGeneration,
            id: DvrEntryId,
        ): DvrMutationResult<Unit> =
            DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)

        override suspend fun createAutorecRule(
            generation: GatewayGeneration,
            request: AutorecRuleCreate,
        ): DvrMutationResult<AutorecRuleId> =
            DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)

        override suspend fun updateAutorecRule(
            generation: GatewayGeneration,
            id: AutorecRuleId,
            update: AutorecRuleUpdate,
        ): DvrMutationResult<Unit> = DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)

        override suspend fun deleteAutorecRule(
            generation: GatewayGeneration,
            id: AutorecRuleId,
        ): DvrMutationResult<Unit> =
            DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)

        override suspend fun createTimerecRule(
            generation: GatewayGeneration,
            request: TimerecRuleCreate,
        ): DvrMutationResult<TimerecRuleId> =
            DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)

        override suspend fun updateTimerecRule(
            generation: GatewayGeneration,
            id: TimerecRuleId,
            update: TimerecRuleUpdate,
        ): DvrMutationResult<Unit> = DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)

        override suspend fun deleteTimerecRule(
            generation: GatewayGeneration,
            id: TimerecRuleId,
        ): DvrMutationResult<Unit> =
            DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)
    }
}

internal fun Duration.toDvrMutationSeconds(): Long = inWholeSeconds

private fun validateAutorecMutation(
    minDuration: Duration?,
    maxDuration: Duration?,
    maximumRecordingCount: Long?,
    retentionDays: Long?,
    removalDays: Long?,
) {
    minDuration?.requireDvrMutationDuration("Autorec minDuration")
    maxDuration?.requireDvrMutationDuration("Autorec maxDuration")
    maximumRecordingCount?.requireDvrMutationU32("Autorec maximumRecordingCount")
    retentionDays?.requireDvrMutationU32("Autorec retentionDays")
    removalDays?.requireDvrMutationU32("Autorec removalDays")
}

private fun validateTimerecMutation(
    startMinutesSinceMidnight: LocalTime?,
    stopMinutesSinceMidnight: LocalTime?,
    retentionDays: Long?,
    removalDays: Long?,
) {
    startMinutesSinceMidnight?.requireDvrWholeMinute()
    stopMinutesSinceMidnight?.requireDvrWholeMinute()
    retentionDays?.requireDvrMutationU32("Timerec retentionDays")
    removalDays?.requireDvrMutationU32("Timerec removalDays")
}

private fun Duration.requireDvrMutationDuration(name: String) {
    require(isFinite() && !isNegative()) { "$name must be a finite non-negative duration" }
    require(this == inWholeSeconds.seconds) { "$name must use whole seconds" }
    inWholeSeconds.requireDvrMutationU32(name)
}

private fun Long.requireDvrMutationU32(name: String) {
    require(this in 0L..DVR_MUTATION_U32_MAX) { "$name must be an unsigned 32-bit value" }
}
