package at.bernhardberger.tvheadend.sdk.playback

import java.util.Collections
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** Marks the transport-neutral infrastructure API used to compose SDK playback internals. */
@RequiresOptIn(
    message = "This API is for SDK infrastructure composition, not application playback control.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
public annotation class SubscriptionInfrastructureApi

/** Unsigned 32-bit subscription identifier owned by one connection generation. */
@SubscriptionInfrastructureApi
@JvmInline
public value class SubscriptionId(public val value: Long) {
    init {
        require(value in 0L..0xffff_ffffL) { "Subscription ID must be an unsigned 32-bit value" }
    }

    override fun toString(): String = "SubscriptionId(<redacted>)"
}

/** Unsigned 32-bit channel identifier used by the subscription transport. */
@SubscriptionInfrastructureApi
@JvmInline
public value class SubscriptionChannelId(public val value: Long) {
    init {
        require(value in 0L..0xffff_ffffL) { "Channel ID must be an unsigned 32-bit value" }
    }

    override fun toString(): String = "SubscriptionChannelId(<redacted>)"
}

/** Unsigned 32-bit stream index within a subscription. */
@SubscriptionInfrastructureApi
@JvmInline
public value class StreamIndex(public val value: Long) {
    init {
        require(value in 0L..0xffff_ffffL) { "Stream index must be an unsigned 32-bit value" }
    }

    override fun toString(): String = "StreamIndex(<redacted>)"
}

/**
 * Bounded-copy binary payload that does not expose its backing storage.
 *
 * [size] and the bytes returned by [copyInto] remain stable for this object's lifetime.
 */
@SubscriptionInfrastructureApi
public interface SubscriptionBinary {
    /** Payload size in bytes. */
    public val size: Int

    /** Copies the prefix that fits into [destination] and returns the copied byte count. */
    public fun copyInto(destination: ByteArray, destinationOffset: Int = 0): Int
}

/** Complete ordered event family emitted for one subscription. */
@SubscriptionInfrastructureApi
public sealed interface SubscriptionEvent {
    /** Stream description supplied by TVHeadend. */
    public class Started(
        streams: List<SubscriptionStream>?,
        public val codecMetadata: SubscriptionBinary?,
        public val condition: SubscriptionCondition,
    ) : SubscriptionEvent {
        /** Ordered stream descriptions, or null when omitted. */
        public val streams: List<SubscriptionStream>? = streams?.toImmutableList()

        override fun toString(): String = "SubscriptionEvent.Started(<redacted>)"
    }

    /**
     * One mux packet in committed protocol order.
     *
     * [decodingTimeUs] and [presentationTimeUs] are the transport's own media coordinates on the
     * [SubscriptionConnection] boundary. An [ActiveSubscription] may move both by one shared
     * offset after an accepted [Skipped], so the timestamps its consumer observes are output
     * coordinates rather than server positions.
     */
    public class Packet(
        public val frameType: MuxFrameType,
        public val streamIndex: StreamIndex,
        public val decodingTimeUs: Long?,
        public val presentationTimeUs: Long?,
        public val durationUs: Long,
        public val payload: SubscriptionBinary,
    ) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Packet(<redacted>)"
    }

    /**
     * Result of a timeshift skip request.
     *
     * [time] is the server position the reader reached, not the output coordinate an
     * [ActiveSubscription] presents for the packets that follow it.
     */
    public class Skipped(
        public val absolute: Boolean?,
        public val outcome: SkipOutcome,
        public val time: Long?,
        public val sizeBytes: Long?,
    ) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Skipped(<redacted>)"
    }

    /** Server-reported graceful stop. */
    public class Stopped(public val condition: SubscriptionCondition) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Stopped(<redacted>)"
    }

    /** Nonterminal server status. */
    public class Status(public val condition: SubscriptionCondition) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Status(<redacted>)"
    }

    /** Nonterminal grace period. */
    public class Grace(public val timeoutSeconds: Long) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Grace(<redacted>)"
    }

    /** Subscription speed update. */
    public class Speed(public val speed: Int) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Speed(<redacted>)"
    }

    /**
     * Timeshift status update in the negotiated wire-clock units.
     *
     * [start], [end], and [shift] describe server positions inside the buffer, so they remain the
     * base a [SubscriptionSeekTarget] uses. They are not output coordinates.
     */
    public class Timeshift(
        public val full: Long,
        public val shift: Long,
        public val start: Long?,
        public val end: Long?,
        public val speed: Int?,
    ) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Timeshift(<redacted>)"
    }

    /** Server queue diagnostics. */
    public class Queue(
        public val packetCount: Long,
        public val byteCount: Long,
        public val delay: Long?,
        public val bFrameDropCount: Long,
        public val pFrameDropCount: Long,
        public val iFrameDropCount: Long,
    ) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Queue(<redacted>)"
    }

    /** Tuner signal diagnostics without raw frontend text. */
    public class Signal(
        public val relativeSnr: Long?,
        public val absoluteSnr: Long?,
        public val relativeSignal: Long?,
        public val absoluteSignal: Long?,
        public val bitErrorRate: Long?,
        public val uncorrectedBlockCount: Long?,
        public val frontendStatusReported: Boolean,
    ) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Signal(<redacted>)"
    }

    /** Descrambling diagnostics with textual labels omitted. */
    public class Descramble(
        public val pid: Long,
        public val conditionalAccessId: Long,
        public val providerId: Long,
        public val ecmTime: Long,
        public val hopCount: Long,
    ) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Descramble(<redacted>)"
    }

    /** Ordered count of packets evicted by the protocol queue. */
    public class Dropped(public val count: Long) : SubscriptionEvent {
        init {
            require(count > 0L) { "Dropped packet count must be positive" }
        }

        override fun toString(): String = "SubscriptionEvent.Dropped(<redacted>)"
    }

    /** Terminal transport loss. */
    public class Terminated(public val reason: SubscriptionTermination) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Terminated(<redacted>)"
    }
}

/** One stream description supplied by a Started event. */
@SubscriptionInfrastructureApi
public data class SubscriptionStream(
    public val index: StreamIndex,
    public val type: SubscriptionStreamType,
    public val language: String?,
    public val compositionId: Long?,
    public val ancillaryId: Long?,
    public val width: Long?,
    public val height: Long?,
    public val frameDuration: Long?,
    public val aspectNumerator: Long?,
    public val aspectDenominator: Long?,
    public val audioType: Long?,
    public val audioVersion: Long?,
    public val channelCount: Long?,
    public val rate: Long?,
    public val rdsUecp: Long?,
    public val codecMetadata: SubscriptionBinary?,
) {
    override fun toString(): String = "SubscriptionStream(<redacted>)"
}

/** SDK-authored stream codec classification. */
public enum class SubscriptionStreamType {
    MPEG2_VIDEO,
    H264,
    H265,
    AAC,
    AC3,
    EAC3,
    MPEG2_AUDIO,
    DVB_SUBTITLE,
    TEXT_SUBTITLE,
    TELETEXT,
    UNKNOWN,
}

/** Safe presence-only status and error classification. */
@SubscriptionInfrastructureApi
public enum class SubscriptionCondition {
    NO_DETAIL,
    STATUS_REPORTED,
    ERROR_REPORTED,
    STATUS_AND_ERROR_REPORTED,
}

/** Frame classification supplied by TVHeadend. */
@SubscriptionInfrastructureApi
public enum class MuxFrameType { UNKNOWN, I, P, B }

/** Safe skip acknowledgement classification. */
@SubscriptionInfrastructureApi
public enum class SkipOutcome { ACCEPTED, REJECTED, UNKNOWN }

/**
 * Closed set of timeshift positioning requests.
 *
 * Media coordinates are server positions in the microsecond base reported by
 * [SubscriptionEvent.Timeshift] and [SubscriptionEvent.Skipped]. They are not the output
 * coordinates an [ActiveSubscription] hands to its consumer, because a resumed segment is rebased
 * onto the already delivered timeline.
 */
@SubscriptionInfrastructureApi
public sealed interface SubscriptionSeekTarget {
    /** Absolute media position inside the granted timeshift buffer. */
    public class Absolute(public val position: Duration) : SubscriptionSeekTarget {
        init {
            require(position.isFinite()) { "Absolute seek position must be finite" }
            require(!position.isNegative()) { "Absolute seek position must not be negative" }
        }

        override fun toString(): String = "SubscriptionSeekTarget.Absolute(<redacted>)"
    }

    /** Signed offset from the current media position. */
    public class Relative(public val offset: Duration) : SubscriptionSeekTarget {
        init {
            require(offset.isFinite()) { "Relative seek offset must be finite" }
        }

        override fun toString(): String = "SubscriptionSeekTarget.Relative(<redacted>)"
    }

    /** Requests a bounded position near the latest observed live edge, not exact live mode. */
    public data object Live : SubscriptionSeekTarget
}

/** Payload-free reason the ordered stream was terminated by subscription infrastructure. */
@SubscriptionInfrastructureApi
public enum class SubscriptionTermination {
    GENERATION_LOST,
    REMOTE_EOF,
    IO_FAILURE,
    FRAMING_FAILURE,
    MALFORMED_MESSAGE,
    TIMEOUT,
    LOCAL_RETIREMENT,
    PUBLICATION_FAILURE,
    INTERNAL_FAILURE,
    /** Legacy unattributed closure retained for SDK compatibility. */
    TRANSPORT_CLOSED,
}

/** Typed result of a subscription protocol operation. */
@SubscriptionInfrastructureApi
public sealed interface SubscriptionOperationResult<out T> {
    /** Successful operation value. */
    public class Ok<out T>(public val value: T) : SubscriptionOperationResult<T> {
        override fun toString(): String = "SubscriptionOperationResult.Ok(<redacted>)"
    }

    /** The server rejected the operation without a safe detailed reason. */
    public data object ServerRejected : SubscriptionOperationResult<Nothing>

    /** The authenticated session lacks permission. */
    public data object AccessDenied : SubscriptionOperationResult<Nothing>

    /** The server refused another concurrent operation. */
    public data object ConnectionLimit : SubscriptionOperationResult<Nothing>

    /** The operation did not complete before its deadline. */
    public data object Timeout : SubscriptionOperationResult<Nothing>

    /** The bound transport generation is unavailable. */
    public data object TransportUnavailable : SubscriptionOperationResult<Nothing>

    /** The bound transport cannot support the operation in its current state. */
    public data object NotSupported : SubscriptionOperationResult<Nothing>
}

/**
 * Safe successful subscribe acknowledgement.
 *
 * [normalizedTimestamps] describes the server's ingest-time normalization of this subscription.
 * It is not a promise that a resumed timeshift segment continues the timestamps already
 * delivered, which is why an [ActiveSubscription] rebases after an accepted
 * [SubscriptionEvent.Skipped].
 */
@SubscriptionInfrastructureApi
public class SubscriptionConfirmation(
    public val ninetyKhz: Boolean?,
    public val normalizedTimestamps: Boolean?,
    public val weight: Long?,
    public val timeshiftPeriodSeconds: Long?,
) {
    override fun toString(): String = "SubscriptionConfirmation(<redacted>)"
}

/** Optional server profile and requested timeshift buffer for one live subscription. */
@SubscriptionInfrastructureApi
public class SubscriptionOptions(
    public val streamProfileUuid: String? = null,
    public val timeshiftPeriod: Duration = Duration.ZERO,
) {
    init {
        require(
            streamProfileUuid == null ||
                streamProfileUuid.length == STREAM_PROFILE_UUID_LENGTH &&
                streamProfileUuid.all(::isLowercaseHexDigit),
        ) {
            "Stream profile UUID must be a canonical lowercase 128-bit UUID"
        }
        require(timeshiftPeriod.isFinite() && !timeshiftPeriod.isNegative()) {
            "Requested timeshift period must be finite and not negative"
        }
        require(timeshiftPeriod.inWholeSeconds <= MAXIMUM_TIMESHIFT_PERIOD_SECONDS) {
            "Requested timeshift period must be an unsigned 32-bit second count"
        }
    }

    override fun toString(): String = "SubscriptionOptions(<redacted>)"
}

/**
 * Generation-bound transport used by one subscription manager.
 *
 * Each [events] flow is cold and may be collected once for its identifier. Collection atomically
 * registers before [subscribe]. A successful [unsubscribe] must complete [events] only after every
 * event committed before its acknowledgement has been delivered in order.
 */
@SubscriptionInfrastructureApi
public interface SubscriptionConnection {
    /** Returns the cold, single-collection, generation-fenced ordered stream for [id]. */
    public fun events(id: SubscriptionId): Flow<SubscriptionEvent>

    /**
     * Issues subscribe after event collection has registered.
     *
     * A positive [timeshiftPeriod] requests a server-side timeshift buffer of that length. The
     * granted period is reported by [SubscriptionConfirmation.timeshiftPeriodSeconds].
     */
    public suspend fun subscribe(
        id: SubscriptionId,
        channelId: SubscriptionChannelId,
        timeshiftPeriod: Duration,
    ): SubscriptionOperationResult<SubscriptionConfirmation>

    /** Issues subscribe with optional profile selection while preserving legacy adapters. */
    public suspend fun subscribe(
        id: SubscriptionId,
        channelId: SubscriptionChannelId,
        options: SubscriptionOptions,
    ): SubscriptionOperationResult<SubscriptionConfirmation> =
        if (options.streamProfileUuid == null) {
            subscribe(id, channelId, options.timeshiftPeriod)
        } else {
            SubscriptionOperationResult.NotSupported
        }

    /**
     * Issues one generation-bound timeshift positioning request for [id].
     *
     * A successful result reports only that the server accepted the command. The ordered
     * [SubscriptionEvent.Skipped] acknowledgement remains authoritative for the resulting position.
     */
    public suspend fun skip(
        id: SubscriptionId,
        target: SubscriptionSeekTarget,
    ): SubscriptionOperationResult<Unit>

    /** Issues a generation-bound server playback-speed request for [id]. */
    public suspend fun speed(
        id: SubscriptionId,
        speed: Int,
    ): SubscriptionOperationResult<Unit> = SubscriptionOperationResult.NotSupported

    /** Issues generation-bound unsubscribe whose success drains and then completes [events]. */
    public suspend fun unsubscribe(id: SubscriptionId): SubscriptionOperationResult<Unit>

    /** Atomically runs [block] only while this bound connection generation is live. */
    public fun <T> commitIfLive(block: () -> T): T?
}

internal fun <T> List<T>.toImmutableList(): List<T> =
    Collections.unmodifiableList(ArrayList(this))

private const val STREAM_PROFILE_UUID_LENGTH = 32
private const val MAXIMUM_TIMESHIFT_PERIOD_SECONDS = 0xffff_ffffL

private fun isLowercaseHexDigit(character: Char): Boolean =
    character in '0'..'9' || character in 'a'..'f'
