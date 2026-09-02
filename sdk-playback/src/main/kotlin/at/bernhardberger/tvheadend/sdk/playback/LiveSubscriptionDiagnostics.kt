@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.playback

import java.net.IDN
import java.text.Normalizer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/** Safe display labels reported for the source serving the active live subscription. */
public class LiveSubscriptionSource internal constructor(
    /** Active adapter display name, or `null` when absent or unsafe to expose. */
    public val adapterName: String?,
    /** Active mux display name, or `null` when absent or unsafe to expose. */
    public val muxName: String?,
    /** Active network display name, or `null` when absent or unsafe to expose. */
    public val networkName: String?,
    /** Active provider display name, or `null` when absent or unsafe to expose. */
    public val providerName: String?,
    /** Active service display name, or `null` when absent or unsafe to expose. */
    public val serviceName: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is LiveSubscriptionSource &&
            adapterName == other.adapterName &&
            muxName == other.muxName &&
            networkName == other.networkName &&
            providerName == other.providerName &&
            serviceName == other.serviceName

    override fun hashCode(): Int {
        var result = adapterName?.hashCode() ?: 0
        result = 31 * result + (muxName?.hashCode() ?: 0)
        result = 31 * result + (networkName?.hashCode() ?: 0)
        result = 31 * result + (providerName?.hashCode() ?: 0)
        result = 31 * result + (serviceName?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "LiveSubscriptionSource(<redacted>)"

    public companion object {
        /** Creates a bounded display observation, omitting recognizable identifiers, locators, and credentials. */
        @SubscriptionInfrastructureApi
        public fun create(
            adapterName: String?,
            muxName: String?,
            networkName: String?,
            providerName: String?,
            serviceName: String?,
        ): LiveSubscriptionSource? {
            val adapter = adapterName.safeDisplayValue()
            val mux = muxName.safeDisplayValue()
            val network = networkName.safeDisplayValue()
            val provider = providerName.safeDisplayValue()
            val service = serviceName.safeDisplayValue()
            return if (adapter == null && mux == null && network == null && provider == null && service == null) {
                null
            } else {
                LiveSubscriptionSource(adapter, mux, network, provider, service)
            }
        }
    }
}

/** Non-exhaustive frontend lock observations for the active live subscription. */
public class LiveFrontendState internal constructor(
    /** Whether the frontend reports a detectable signal. */
    public val signalDetected: Boolean,
    /** Whether the frontend reports synchronization beyond signal detection. */
    public val partiallySynchronized: Boolean,
    /** Whether the frontend reports a full lock. */
    public val locked: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is LiveFrontendState &&
            signalDetected == other.signalDetected &&
            partiallySynchronized == other.partiallySynchronized &&
            locked == other.locked

    override fun hashCode(): Int {
        var result = signalDetected.hashCode()
        result = 31 * result + partiallySynchronized.hashCode()
        result = 31 * result + locked.hashCode()
        return result
    }

    override fun toString(): String = "LiveFrontendState(<redacted>)"

    public companion object {
        /** Creates a validated state from normalized transport observations. */
        @SubscriptionInfrastructureApi
        public fun create(
            signalDetected: Boolean,
            partiallySynchronized: Boolean,
            locked: Boolean,
        ): LiveFrontendState {
            require(!partiallySynchronized || signalDetected) {
                "Frontend synchronization requires signal detection"
            }
            require(!locked || partiallySynchronized) {
                "Frontend lock requires partial synchronization"
            }
            return LiveFrontendState(signalDetected, partiallySynchronized, locked)
        }
    }
}

/** Frontend measurements reported for the active live subscription. */
public class LiveFrontendDiagnostics internal constructor(
    /** Known frontend state, or `null` when the server omitted or did not recognize it. */
    public val state: LiveFrontendState?,
    /** Relative signal-to-noise ratio in percent, from 0.0 through 100.0. */
    public val relativeSnrPercent: Double?,
    /** Absolute signal-to-noise ratio in decibels. */
    public val absoluteSnrDecibels: Double?,
    /** Relative signal strength in percent, from 0.0 through 100.0. */
    public val relativeSignalPercent: Double?,
    /** Absolute signal strength in dBm. */
    public val absoluteSignalDbm: Double?,
    /** Raw adapter-reported bit error rate; no portable denominator is defined. */
    public val bitErrorRateRaw: Long?,
    /** Adapter-reported uncorrected-block count. */
    public val uncorrectedBlockCount: Long?,
) {
    override fun equals(other: Any?): Boolean =
        other is LiveFrontendDiagnostics &&
            state == other.state &&
            relativeSnrPercent == other.relativeSnrPercent &&
            absoluteSnrDecibels == other.absoluteSnrDecibels &&
            relativeSignalPercent == other.relativeSignalPercent &&
            absoluteSignalDbm == other.absoluteSignalDbm &&
            bitErrorRateRaw == other.bitErrorRateRaw &&
            uncorrectedBlockCount == other.uncorrectedBlockCount

    override fun hashCode(): Int {
        var result = state?.hashCode() ?: 0
        result = 31 * result + (relativeSnrPercent?.hashCode() ?: 0)
        result = 31 * result + (absoluteSnrDecibels?.hashCode() ?: 0)
        result = 31 * result + (relativeSignalPercent?.hashCode() ?: 0)
        result = 31 * result + (absoluteSignalDbm?.hashCode() ?: 0)
        result = 31 * result + (bitErrorRateRaw?.hashCode() ?: 0)
        result = 31 * result + (uncorrectedBlockCount?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "LiveFrontendDiagnostics(<redacted>)"
}

/** Server-side queue observations for the active live subscription. */
public class LiveQueueDiagnostics internal constructor(
    /** Number of packets currently queued by the server. */
    public val packetCount: Long,
    /** Number of payload bytes currently queued by the server. */
    public val byteCount: Long,
    /** Queued media timestamp span, or `null` when unavailable. */
    public val mediaSpan: Duration?,
    /** Number of dropped B frames reported by the server. */
    public val droppedBFrameCount: Long,
    /** Number of dropped P frames reported by the server. */
    public val droppedPFrameCount: Long,
    /** Number of dropped I frames reported by the server. */
    public val droppedIFrameCount: Long,
) {
    /** Queued media timestamp span in microseconds for Java callers, or `null` when unavailable. */
    public val mediaSpanMicroseconds: Long?
        get() = mediaSpan?.inWholeMicroseconds

    override fun equals(other: Any?): Boolean =
        other is LiveQueueDiagnostics &&
            packetCount == other.packetCount &&
            byteCount == other.byteCount &&
            mediaSpan == other.mediaSpan &&
            droppedBFrameCount == other.droppedBFrameCount &&
            droppedPFrameCount == other.droppedPFrameCount &&
            droppedIFrameCount == other.droppedIFrameCount

    override fun hashCode(): Int {
        var result = packetCount.hashCode()
        result = 31 * result + byteCount.hashCode()
        result = 31 * result + (mediaSpan?.hashCode() ?: 0)
        result = 31 * result + droppedBFrameCount.hashCode()
        result = 31 * result + droppedPFrameCount.hashCode()
        result = 31 * result + droppedIFrameCount.hashCode()
        return result
    }

    override fun toString(): String = "LiveQueueDiagnostics(<redacted>)"
}

/** Immutable, app-safe diagnostics for the currently active live subscription. */
public class LiveSubscriptionDiagnostics internal constructor(
    /** Display labels for the active subscription source. */
    public val source: LiveSubscriptionSource?,
    /** Most recent frontend observation. */
    public val frontend: LiveFrontendDiagnostics?,
    /** Most recent server queue observation. */
    public val queue: LiveQueueDiagnostics?,
) {
    override fun equals(other: Any?): Boolean =
        other is LiveSubscriptionDiagnostics &&
            source == other.source &&
            frontend == other.frontend &&
            queue == other.queue

    override fun hashCode(): Int {
        var result = source?.hashCode() ?: 0
        result = 31 * result + (frontend?.hashCode() ?: 0)
        result = 31 * result + (queue?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "LiveSubscriptionDiagnostics(<redacted>)"

    public companion object {
        /** Applies one ordered subscription event to an immutable diagnostics snapshot. */
        @SubscriptionInfrastructureApi
        public fun update(
            previous: LiveSubscriptionDiagnostics?,
            event: SubscriptionEvent,
        ): LiveSubscriptionDiagnostics? = when (event) {
            is SubscriptionEvent.Started -> createOrNull(event.source, null, null)
            is SubscriptionEvent.Signal -> createOrNull(
                previous?.source,
                event.toFrontendDiagnostics(),
                previous?.queue,
            )
            is SubscriptionEvent.Queue -> createOrNull(
                previous?.source,
                previous?.frontend,
                LiveQueueDiagnostics(
                    packetCount = event.packetCount,
                    byteCount = event.byteCount,
                    mediaSpan = event.delay
                        ?.takeIf { it in MIN_QUEUE_DELAY_MICROSECONDS..MAX_QUEUE_DELAY_MICROSECONDS }
                        ?.microseconds
                        ?.takeIf(Duration::isFinite),
                    droppedBFrameCount = event.bFrameDropCount,
                    droppedPFrameCount = event.pFrameDropCount,
                    droppedIFrameCount = event.iFrameDropCount,
                ),
            )
            is SubscriptionEvent.Stopped,
            is SubscriptionEvent.Terminated,
            -> null
            is SubscriptionEvent.Descramble,
            is SubscriptionEvent.Dropped,
            is SubscriptionEvent.Grace,
            is SubscriptionEvent.Packet,
            is SubscriptionEvent.Skipped,
            is SubscriptionEvent.Speed,
            is SubscriptionEvent.Status,
            is SubscriptionEvent.Timeshift,
            -> previous
        }

        private fun createOrNull(
            source: LiveSubscriptionSource?,
            frontend: LiveFrontendDiagnostics?,
            queue: LiveQueueDiagnostics?,
        ): LiveSubscriptionDiagnostics? =
            if (source == null && frontend == null && queue == null) {
                null
            } else {
                LiveSubscriptionDiagnostics(source, frontend, queue)
            }
    }
}

private fun SubscriptionEvent.Signal.toFrontendDiagnostics(): LiveFrontendDiagnostics? {
    val relativeSnrPercent = relativeSnr.toRelativePercentOrNull()
    val absoluteSnrDecibels = absoluteSnr?.toDouble()?.div(MILLI_UNITS_PER_UNIT)
    val relativeSignalPercent = relativeSignal.toRelativePercentOrNull()
    val absoluteSignalDbm = absoluteSignal?.toDouble()?.div(MILLI_UNITS_PER_UNIT)
    val bitErrorRateRaw = bitErrorRate.toUnsigned32OrNull()
    val uncorrectedBlockCount = uncorrectedBlockCount.toUnsigned32OrNull()
    return if (
        frontendState == null &&
        relativeSnrPercent == null &&
        absoluteSnrDecibels == null &&
        relativeSignalPercent == null &&
        absoluteSignalDbm == null &&
        bitErrorRateRaw == null &&
        uncorrectedBlockCount == null
    ) {
        null
    } else {
        LiveFrontendDiagnostics(
            state = frontendState,
            relativeSnrPercent = relativeSnrPercent,
            absoluteSnrDecibels = absoluteSnrDecibels,
            relativeSignalPercent = relativeSignalPercent,
            absoluteSignalDbm = absoluteSignalDbm,
            bitErrorRateRaw = bitErrorRateRaw,
            uncorrectedBlockCount = uncorrectedBlockCount,
        )
    }
}

private fun Long?.toRelativePercentOrNull(): Double? =
    this?.takeIf { it in MIN_RELATIVE_READING..MAX_RELATIVE_READING }
        ?.toDouble()
        ?.times(PERCENT_MAX)
        ?.div(MAX_RELATIVE_READING.toDouble())

private fun Long?.toUnsigned32OrNull(): Long? =
    this?.takeIf { it in MIN_UNSIGNED_32_READING..MAX_UNSIGNED_32_READING }

private fun String?.safeDisplayValue(): String? {
    val rawValue = this ?: return null
    if (rawValue.length > MAX_SOURCE_LABEL_LENGTH || rawValue.hasDisplayControl()) return null
    val value = Normalizer.normalize(rawValue, Normalizer.Form.NFC)
        .trim()
        .takeIf(String::isNotEmpty) ?: return null
    if (value.length > MAX_SOURCE_LABEL_LENGTH || value.hasDisplayControl()) return null
    val inspected = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .normalizeIdnSeparators()
    if (inspected.length > MAX_SOURCE_LABEL_LENGTH || inspected.hasDisplayControl()) return null
    return value.takeUnless {
        inspected.isSingleComponentIpv4Address() ||
            LOCALHOST.containsMatchIn(inspected) ||
            IPV4_ADDRESS.containsMatchIn(inspected) ||
            IPV6_ADDRESS.containsMatchIn(inspected) ||
            inspected.containsHostname() ||
            UUID_VALUE.containsMatchIn(inspected) ||
            COMPACT_UUID_VALUE.containsMatchIn(inspected) ||
            HARDWARE_ADDRESS.containsMatchIn(inspected) ||
            URI_USERINFO.containsMatchIn(inspected) ||
            URI_OR_PATH.containsMatchIn(inspected) ||
            EQUALS_ASSIGNMENT.containsMatchIn(inspected) ||
            CREDENTIAL_COLON_ASSIGNMENT.containsMatchIn(inspected) ||
            AUTHORIZATION_VALUE.containsMatchIn(inspected)
    }
}

private fun String.containsHostname(): Boolean =
    HOSTNAME_CANDIDATE.findAll(this).any { candidate ->
        val ascii = try {
            IDN.toASCII(candidate.value, IDN.USE_STD3_ASCII_RULES)
        } catch (_: RuntimeException) {
            return true
        }
        HOSTNAME.matches(ascii)
    }

private fun String.normalizeIdnSeparators(): String =
    replace('\u3002', '.')
        .replace('\uff0e', '.')
        .replace('\uff61', '.')

private fun String.hasDisplayControl(): Boolean =
    codePoints().anyMatch { codePoint ->
        Character.isISOControl(codePoint) ||
            when (Character.getType(codePoint)) {
                Character.FORMAT.toInt(),
                Character.LINE_SEPARATOR.toInt(),
                Character.PARAGRAPH_SEPARATOR.toInt(),
                Character.SURROGATE.toInt(),
                Character.UNASSIGNED.toInt(),
                -> true
                else -> false
            }
    }

private fun String.isSingleComponentIpv4Address(): Boolean {
    val (digits, radix) = when {
        startsWith("0x", ignoreCase = true) -> drop(2) to 16
        length > 1 && startsWith('0') -> drop(1) to 8
        else -> this to 10
    }
    val value = digits.toLongOrNull(radix) ?: return false
    return value in 0L..MAX_IPV4_VALUE
}

private const val MIN_RELATIVE_READING: Long = 0L
private const val MAX_RELATIVE_READING: Long = 65_535L
private const val MIN_UNSIGNED_32_READING: Long = 0L
private const val MAX_UNSIGNED_32_READING: Long = 0xffff_ffffL
private const val MIN_QUEUE_DELAY_MICROSECONDS: Long = 0L
private const val MAX_QUEUE_DELAY_MICROSECONDS: Long = 0xffff_ffffL
private const val PERCENT_MAX: Double = 100.0
private const val MILLI_UNITS_PER_UNIT: Double = 1_000.0
private const val MAX_SOURCE_LABEL_LENGTH: Int = 256
private const val MAX_IPV4_VALUE: Long = 0xffff_ffffL

private val LOCALHOST = Regex("""(?i)(?<![\p{L}\p{N}_-])localhost\.?(?![\p{L}\p{N}_-])""")
private val IPV4_ADDRESS = Regex("""(?<![0-9])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9])""")
private val IPV6_ADDRESS = Regex(
    """(?i)(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-f:])""",
)
private val HOSTNAME_CANDIDATE = Regex("""[\p{L}\p{M}\p{N}-]+(?:\.[\p{L}\p{M}\p{N}-]+)+""")
private val HOSTNAME = Regex(
    """(?i)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?""",
)
private val UUID_VALUE = Regex(
    """(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""",
)
private val COMPACT_UUID_VALUE = Regex("""(?i)[0-9a-f]{32}""")
private val HARDWARE_ADDRESS = Regex(
    """(?i)(?:(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}|(?:[0-9a-f]{2}\s+){5}[0-9a-f]{2}|(?:[0-9a-f]{4}\.){2}[0-9a-f]{4}|[0-9a-f]{12})""",
)
private val URI_USERINFO = Regex(
    """(?i)(?:[a-z][a-z0-9+.-]*://)?[^\s/:@]+(?::[^\s/@]*)?@[^\s]+""",
)
private val URI_OR_PATH = Regex(
    """(?i)(?:^|\s)(?:[a-z][a-z0-9+.-]*:\S|(?:\.{1,2})?[/\\]\S*)""",
)
private val EQUALS_ASSIGNMENT = Regex(
    """(?:^|[^\p{L}\p{N}])[\p{L}\p{N}_-]+(?:\s+[\p{L}\p{N}_-]+){0,2}\s*=\s*\S+""",
)
private val CREDENTIAL_COLON_ASSIGNMENT = Regex(
    """(?i)(?:^|[^\p{L}\p{N}])(?:[\p{L}\p{N}]+[_-])*(?:auth(?:orization)?|credential|key|passphrase|password|passwd|pwd|secret|token|user(?:name)?)(?:[_-][\p{L}\p{N}]+)*\s*:\s*\S+""",
)
private val AUTHORIZATION_VALUE = Regex(
    """(?i)\b(?:authorization\s+\S|(?:bearer|basic|digest|negotiate|ntlm|oauth|scram-sha-(?:1|256))\s+\S)""",
)
