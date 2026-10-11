package at.bernhardberger.tvheadend.sdk.core

import java.time.DayOfWeek
import java.time.LocalTime
import java.util.Collections
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Automatic-recording time selection. TVHeadend encodes any time as -1; negative values and values
 * at least 1440 are read as [AnyTime]. When creating a rule, TVHeadend sets both start and window end
 * to any time if either is any time.
 */
public sealed interface AutorecTime {
    /** Matches any time of day; encoded as -1, not midnight or an omitted field. */
    public data object AnyTime : AutorecTime

    /** A local wall-clock time with whole-minute precision. */
    public data class At(public val time: LocalTime) : AutorecTime {
        init {
            time.requireDvrWholeMinute()
        }
    }
}

/**
 * DVR priority. Unknown server values map to [UNKNOWN]. Consumers must retain a fallback
 * when comparing this open set. Reading a priority does not set it on an update request.
 */
public class DvrPriority private constructor(private val label: String, internal val wireValue: Long?) {
    override fun toString(): String = label

    public companion object {
        /** Highest priority. */
        @JvmField public val IMPORTANT: DvrPriority = DvrPriority("IMPORTANT", 0)
        /** High priority. */
        @JvmField public val HIGH: DvrPriority = DvrPriority("HIGH", 1)
        /** Normal priority. */
        @JvmField public val NORMAL: DvrPriority = DvrPriority("NORMAL", 2)
        /** Low priority. */
        @JvmField public val LOW: DvrPriority = DvrPriority("LOW", 3)
        /** Lowest priority. */
        @JvmField public val UNIMPORTANT: DvrPriority = DvrPriority("UNIMPORTANT", 4)
        /** Server's unset priority. */
        @JvmField public val NOT_SET: DvrPriority = DvrPriority("NOT_SET", 5)
        /** Use the recording profile's priority. */
        @JvmField public val DEFAULT: DvrPriority = DvrPriority("DEFAULT", 6)
        /**
         * Unrecognized server value. Has no wire value; create and update requests reject it
         * with [IllegalArgumentException].
         */
        @JvmField public val UNKNOWN: DvrPriority = DvrPriority("UNKNOWN", null)

        internal fun fromWire(value: Long): DvrPriority = when (value) {
            0L -> IMPORTANT
            1L -> HIGH
            2L -> NORMAL
            3L -> LOW
            4L -> UNIMPORTANT
            5L -> NOT_SET
            6L -> DEFAULT
            else -> UNKNOWN
        }
    }
}

/**
 * Automatic-recording duplicate policy. Unknown server values map to [UNKNOWN].
 * Consumers must retain a fallback when comparing this open set. Update requests omit
 * this setting unless the caller explicitly supplies it.
 */
public class DuplicateDetection private constructor(private val label: String, internal val wireValue: Long?) {
    override fun toString(): String = label

    public companion object {
        /** Record all broadcasts. */
        @JvmField public val ALL: DuplicateDetection = DuplicateDetection("ALL", 0)
        /** Different episode number across all rules. */
        @JvmField public val DIFFERENT_EPISODE_NUMBER: DuplicateDetection =
            DuplicateDetection("DIFFERENT_EPISODE_NUMBER", 1)
        /** Different subtitle across all rules. */
        @JvmField public val DIFFERENT_SUBTITLE: DuplicateDetection = DuplicateDetection("DIFFERENT_SUBTITLE", 2)
        /** Different description across all rules. */
        @JvmField public val DIFFERENT_DESCRIPTION: DuplicateDetection = DuplicateDetection("DIFFERENT_DESCRIPTION", 3)
        /** At most once per week across all rules. */
        @JvmField public val ONCE_PER_WEEK: DuplicateDetection = DuplicateDetection("ONCE_PER_WEEK", 4)
        /** At most once per day across all rules. */
        @JvmField public val ONCE_PER_DAY: DuplicateDetection = DuplicateDetection("ONCE_PER_DAY", 5)
        /** Different episode number within this rule. */
        @JvmField public val LOCAL_DIFFERENT_EPISODE_NUMBER: DuplicateDetection =
            DuplicateDetection("LOCAL_DIFFERENT_EPISODE_NUMBER", 6)
        /** Different title within this rule. */
        @JvmField public val LOCAL_DIFFERENT_TITLE: DuplicateDetection = DuplicateDetection("LOCAL_DIFFERENT_TITLE", 7)
        /** Different subtitle within this rule. */
        @JvmField public val LOCAL_DIFFERENT_SUBTITLE: DuplicateDetection =
            DuplicateDetection("LOCAL_DIFFERENT_SUBTITLE", 8)
        /** Different description within this rule. */
        @JvmField public val LOCAL_DIFFERENT_DESCRIPTION: DuplicateDetection =
            DuplicateDetection("LOCAL_DIFFERENT_DESCRIPTION", 9)
        /** At most once per week within this rule. */
        @JvmField public val LOCAL_ONCE_PER_WEEK: DuplicateDetection = DuplicateDetection("LOCAL_ONCE_PER_WEEK", 10)
        /** At most once per day within this rule. */
        @JvmField public val LOCAL_ONCE_PER_DAY: DuplicateDetection = DuplicateDetection("LOCAL_ONCE_PER_DAY", 11)
        /** At most once per month across all rules. */
        @JvmField public val ONCE_PER_MONTH: DuplicateDetection = DuplicateDetection("ONCE_PER_MONTH", 12)
        /** At most once per month within this rule. */
        @JvmField public val LOCAL_ONCE_PER_MONTH: DuplicateDetection = DuplicateDetection("LOCAL_ONCE_PER_MONTH", 13)
        /** Only episodes marked unique in the guide. */
        @JvmField public val UNIQUE: DuplicateDetection = DuplicateDetection("UNIQUE", 14)
        /** Use the recording profile's policy. */
        @JvmField public val DVR_PROFILE: DuplicateDetection = DuplicateDetection("DVR_PROFILE", 15)
        /**
         * Unrecognized server value. Has no wire value; create and update requests reject it
         * with [IllegalArgumentException].
         */
        @JvmField public val UNKNOWN: DuplicateDetection = DuplicateDetection("UNKNOWN", null)

        internal fun fromWire(value: Long): DuplicateDetection = when (value) {
            0L -> ALL
            1L -> DIFFERENT_EPISODE_NUMBER
            2L -> DIFFERENT_SUBTITLE
            3L -> DIFFERENT_DESCRIPTION
            4L -> ONCE_PER_WEEK
            5L -> ONCE_PER_DAY
            6L -> LOCAL_DIFFERENT_EPISODE_NUMBER
            7L -> LOCAL_DIFFERENT_TITLE
            8L -> LOCAL_DIFFERENT_SUBTITLE
            9L -> LOCAL_DIFFERENT_DESCRIPTION
            10L -> LOCAL_ONCE_PER_WEEK
            11L -> LOCAL_ONCE_PER_DAY
            12L -> ONCE_PER_MONTH
            13L -> LOCAL_ONCE_PER_MONTH
            14L -> UNIQUE
            15L -> DVR_PROFILE
            else -> UNKNOWN
        }
    }
}

/**
 * Broadcast selection. Unknown server values map to [UNKNOWN]. Consumers must retain a
 * fallback when comparing this open set. Updates omit it unless explicitly supplied.
 */
public class BroadcastType private constructor(private val label: String, internal val wireValue: Long?) {
    override fun toString(): String = label

    public companion object {
        /** Match all broadcasts. */
        @JvmField public val ALL: BroadcastType = BroadcastType("ALL", 0)
        /** Match new broadcasts and broadcasts without repeat information. */
        @JvmField public val NEW_OR_UNKNOWN: BroadcastType = BroadcastType("NEW_OR_UNKNOWN", 1)
        /** Match repeats only. */
        @JvmField public val REPEAT: BroadcastType = BroadcastType("REPEAT", 2)
        /** Match broadcasts explicitly marked new. */
        @JvmField public val NEW: BroadcastType = BroadcastType("NEW", 3)
        /**
         * Unrecognized server value. Has no wire value; create and update requests reject it
         * with [IllegalArgumentException].
         */
        @JvmField public val UNKNOWN: BroadcastType = BroadcastType("UNKNOWN", null)

        internal fun fromWire(value: Long): BroadcastType = when (value) {
            0L -> ALL
            1L -> NEW_OR_UNKNOWN
            2L -> REPEAT
            3L -> NEW
            else -> UNKNOWN
        }
    }
}

internal fun LocalTime.requireDvrWholeMinute() {
    require(second == 0 && nano == 0) { "DVR time must use whole minutes" }
}

internal fun Duration.requireDvrExtraMinutes() {
    require(isFinite() && this == inWholeMinutes.minutes) { "DVR padding must use finite whole minutes" }
}

internal fun LocalTime.toDvrMinute(): Int = hour * 60 + minute

internal fun Int.toDvrLocalTime(): LocalTime? =
    takeIf { it in 0..1439 }?.let { LocalTime.of(it / 60, it % 60) }

internal fun AutorecTime.toDvrMinute(): Int = when (this) {
    AutorecTime.AnyTime -> -1
    is AutorecTime.At -> time.toDvrMinute()
}

internal fun Int.toAutorecTime(): AutorecTime =
    toDvrLocalTime()?.let(AutorecTime::At) ?: AutorecTime.AnyTime

// TVHeadend numbers Monday as 1 and Sunday as 7; bit zero therefore means Monday.
internal fun Long.toDvrDays(): Set<DayOfWeek> =
    DayOfWeek.values().filterTo(linkedSetOf()) { this and (1L shl (it.value - 1)) != 0L }

internal fun Set<DayOfWeek>.toDvrDayMask(): Long = fold(0L) { mask, day -> mask or (1L shl (day.value - 1)) }

internal fun Set<DayOfWeek>.toDvrImmutableDays(): Set<DayOfWeek> = Collections.unmodifiableSet(toSet())

internal fun Long.toDvrExtraDuration(): Duration? = minutes.takeIf { it.isFinite() && it.inWholeMinutes == this }
