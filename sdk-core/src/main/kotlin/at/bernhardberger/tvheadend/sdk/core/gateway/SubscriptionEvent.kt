package at.bernhardberger.tvheadend.sdk.core.gateway

@JvmInline
internal value class SubscriptionId(internal val value: Long) {
    init {
        require(value in 0L..0xffff_ffffL) { "SubscriptionId must be an unsigned 32-bit value" }
    }

    override fun toString(): String = "SubscriptionId(<redacted>)"
}

@JvmInline
internal value class StreamIndex(internal val value: Long) {
    init {
        require(value in 0L..0xffff_ffffL) { "StreamIndex must be an unsigned 32-bit value" }
    }

    override fun toString(): String = "StreamIndex(<redacted>)"
}

internal interface GatewayBinary {
    public val size: Int

    public fun copyInto(
        destination: ByteArray,
        destinationOffset: Int = 0,
    ): Int
}

internal sealed interface SubscriptionEvent {
    public class Started(
        streams: List<SubscriptionStream>?,
        internal val codecMetadata: GatewayBinary?,
        internal val condition: SubscriptionCondition,
    ) : SubscriptionEvent {
        internal val streams: List<SubscriptionStream>? = streams?.toList()

        override fun toString(): String = "SubscriptionEvent.Started(<redacted>)"
    }

    public class Packet(
        internal val frameType: MuxFrameType,
        internal val streamIndex: StreamIndex,
        internal val decodingTimeUs: Long?,
        internal val presentationTimeUs: Long?,
        internal val durationUs: Long,
        internal val payload: GatewayBinary,
    ) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Packet(<redacted>)"
    }

    public class Skipped(
        internal val absolute: Boolean?,
        internal val outcome: SkipOutcome,
        internal val time: Long?,
        internal val sizeBytes: Long?,
    ) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Skipped(<redacted>)"
    }

    public class Stopped(
        internal val condition: SubscriptionCondition,
    ) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Stopped(<redacted>)"
    }

    public class Status(
        internal val condition: SubscriptionCondition,
    ) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Status(<redacted>)"
    }

    public class Grace(
        internal val timeoutSeconds: Long,
    ) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Grace(<redacted>)"
    }

    public class Speed(
        internal val speed: Int,
    ) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Speed(<redacted>)"
    }

    public class Timeshift(
        internal val full: Long,
        internal val shift: Long,
        internal val start: Long?,
        internal val end: Long?,
        internal val speed: Int?,
    ) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Timeshift(<redacted>)"
    }

    public class Queue(
        internal val packetCount: Long,
        internal val byteCount: Long,
        internal val delay: Long?,
        internal val bFrameDropCount: Long,
        internal val pFrameDropCount: Long,
        internal val iFrameDropCount: Long,
    ) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Queue(<redacted>)"
    }

    public class Signal(
        internal val relativeSnr: Long?,
        internal val absoluteSnr: Long?,
        internal val relativeSignal: Long?,
        internal val absoluteSignal: Long?,
        internal val bitErrorRate: Long?,
        internal val uncorrectedBlockCount: Long?,
        internal val frontendStatusReported: Boolean,
    ) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Signal(<redacted>)"
    }

    public class Descramble(
        internal val pid: Long,
        internal val conditionalAccessId: Long,
        internal val providerId: Long,
        internal val ecmTime: Long,
        internal val hopCount: Long,
    ) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Descramble(<redacted>)"
    }

    public class Dropped(
        internal val count: Long,
    ) : SubscriptionEvent {
        init {
            require(count > 0L) { "Dropped packet count must be positive" }
        }

        override fun toString(): String = "SubscriptionEvent.Dropped(<redacted>)"
    }

    public class Terminated(
        internal val reason: SubscriptionTermination,
    ) : SubscriptionEvent {
        override fun toString(): String = "SubscriptionEvent.Terminated(<redacted>)"
    }
}

internal class SubscriptionStream(
    internal val index: StreamIndex,
    internal val type: SubscriptionStreamType,
    internal val language: String?,
    internal val compositionId: Long?,
    internal val ancillaryId: Long?,
    internal val width: Long?,
    internal val height: Long?,
    internal val frameDuration: Long?,
    internal val aspectNumerator: Long?,
    internal val aspectDenominator: Long?,
    internal val audioType: Long?,
    internal val audioVersion: Long?,
    internal val channelCount: Long?,
    internal val rate: Long?,
    internal val rdsUecp: Long?,
    internal val codecMetadata: GatewayBinary?,
) {
    override fun toString(): String = "SubscriptionStream(<redacted>)"
}

internal enum class SubscriptionStreamType {
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

internal enum class SubscriptionCondition {
    NO_DETAIL,
    STATUS_REPORTED,
    ERROR_REPORTED,
    STATUS_AND_ERROR_REPORTED,
}

internal enum class MuxFrameType {
    UNKNOWN,
    I,
    P,
    B,
}

internal enum class SkipOutcome {
    ACCEPTED,
    REJECTED,
    UNKNOWN,
}

internal enum class SubscriptionTermination {
    GENERATION_LOST,
    TRANSPORT_CLOSED,
}
