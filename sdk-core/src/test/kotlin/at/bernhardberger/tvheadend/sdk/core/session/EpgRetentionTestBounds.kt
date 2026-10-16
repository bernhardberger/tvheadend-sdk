package at.bernhardberger.tvheadend.sdk.core.session

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal fun retentionBounds(from: Instant, to: Instant): EpgRetentionBounds = EpgRetentionBounds(
    clock = object : Clock { override fun now(): Instant = from },
    retainPast = Duration.ZERO,
    // Callers specify the final prune bound, including its slide allowance.
    futureHorizon = to - from - 60.seconds,
)
