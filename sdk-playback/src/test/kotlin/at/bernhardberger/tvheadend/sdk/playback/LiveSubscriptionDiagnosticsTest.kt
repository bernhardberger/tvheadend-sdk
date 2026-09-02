@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.playback

import kotlin.time.Duration.Companion.microseconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class LiveSubscriptionDiagnosticsTest {
    @Test
    fun `ordered observations preserve safe source frontend units and queue depth`() {
        val source = requireNotNull(
            LiveSubscriptionSource.create(
                adapterName = "Adapter A",
                muxName = "Mux B",
                networkName = "Network C",
                providerName = "Provider D",
                serviceName = "Service E",
            ),
        )
        var diagnostics = LiveSubscriptionDiagnostics.update(
            previous = null,
            event = SubscriptionEvent.Started(
                streams = emptyList(),
                codecMetadata = null,
                condition = SubscriptionCondition.NO_DETAIL,
                issue = null,
                source = source,
            ),
        )
        diagnostics = LiveSubscriptionDiagnostics.update(
            diagnostics,
            SubscriptionEvent.Signal(
                relativeSnr = 32_768L,
                absoluteSnr = 12_345L,
                relativeSignal = 65_535L,
                absoluteSignal = -67_890L,
                bitErrorRate = 9L,
                uncorrectedBlockCount = 10L,
                frontendStatusReported = true,
                frontendState = LiveFrontendState.create(
                    signalDetected = true,
                    partiallySynchronized = true,
                    locked = true,
                ),
            ),
        )
        diagnostics = LiveSubscriptionDiagnostics.update(
            diagnostics,
            SubscriptionEvent.Queue(
                packetCount = 11L,
                byteCount = 12L,
                delay = 1_300L,
                bFrameDropCount = 13L,
                pFrameDropCount = 14L,
                iFrameDropCount = 15L,
            ),
        )

        val current = requireNotNull(diagnostics)
        assertSame(source, current.source)
        with(requireNotNull(current.frontend)) {
            assertTrue(requireNotNull(state).locked)
            assertEquals(32_768.0 * 100.0 / 65_535.0, relativeSnrPercent)
            assertEquals(12.345, absoluteSnrDecibels)
            assertEquals(100.0, relativeSignalPercent)
            assertEquals(-67.89, absoluteSignalDbm)
            assertEquals(9L, bitErrorRateRaw)
            assertEquals(10L, uncorrectedBlockCount)
        }
        with(requireNotNull(current.queue)) {
            assertEquals(11L, packetCount)
            assertEquals(12L, byteCount)
            assertEquals(1_300.microseconds, mediaSpan)
            assertEquals(1_300L, mediaSpanMicroseconds)
            assertEquals(13L, droppedBFrameCount)
            assertEquals(14L, droppedPFrameCount)
            assertEquals(15L, droppedIFrameCount)
        }
        assertFalse(current.toString().contains("Adapter A"))
        assertFalse(source.toString().contains("Service E"))
        assertEquals("LiveSubscriptionDiagnostics(<redacted>)", current.toString())
    }

    @Test
    fun `source labels enforce the structural display boundary`() {
        val source = requireNotNull(
            LiveSubscriptionSource.create(
                adapterName = "/dev/dvb/adapter0",
                muxName = "http://user:secret@10.0.0.5:8001/live.ts",
                networkName = "backend.example.com",
                providerName = "password=private",
                serviceName = " Service E ",
            ),
        )

        assertTrue(source.adapterName == null, "Unsafe adapter label was exposed")
        assertTrue(source.muxName == null, "Unsafe mux label was exposed")
        assertTrue(source.networkName == null, "Unsafe network label was exposed")
        assertTrue(source.providerName == null, "Unsafe provider label was exposed")
        assertEquals("Service E", source.serviceName)
        assertNull(
            LiveSubscriptionSource.create(
                adapterName = "123e4567-e89b-42d3-a456-426614174000",
                muxName = "[2001:db8::1]:9982",
                networkName = "localhost:9982",
                providerName = "pipe:/usr/bin/source",
                serviceName = "x".repeat(257),
            ),
        )
        assertNull(
            LiveSubscriptionSource.create(
                adapterName = "::1",
                muxName = "localhost",
                networkName = "rtsp:secret",
                providerName = "../secrets/token",
                serviceName = "api_key=secret",
            ),
        )
        assertNull(
            LiveSubscriptionSource.create(
                adapterName = "0123456789abcdef0123456789abcdef",
                muxName = "AA-BB-CC-DD-EE-FF",
                networkName = "aabb.ccdd.eeff",
                providerName = null,
                serviceName = null,
            ),
        )
        assertNull(
            LiveSubscriptionSource.create(
                adapterName = "a123e4567-e89b-42d3-a456-426614174000",
                muxName = "\u4f8b\u5b50.cn",
                networkName = "Bearer abc123",
                providerName = "AABBCCDDEEFF",
                serviceName = "Authorization opaque",
            ),
        )
        assertNull(
            LiveSubscriptionSource.create(
                adapterName = "client_secret=hunter2",
                muxName = "refresh_token:private",
                networkName = "AWS_SECRET_ACCESS_KEY=hidden",
                providerName = "AA BB CC DD EE FF",
                serviceName = "a.b",
            ),
        )
        assertNull(
            LiveSubscriptionSource.create(
                adapterName = "\u4f8b\u5b50.x",
                muxName = "2130706433",
                networkName = "0x7f000001",
                providerName = "017700000001",
                serviceName = "user:private@backend.example",
            ),
        )
        assertNull(
            LiveSubscriptionSource.create(
                adapterName = "\u4f8b\u5b50\u3002cn",
                muxName = "127\uff610\uff610\uff611",
                networkName = "backend\uff0eexample",
                providerName = null,
                serviceName = null,
            ),
        )
        assertNull(
            LiveSubscriptionSource.create(
                adapterName = "passwordHash=YWJjZGVmZ2hpamtsbW5vcA",
                muxName = "accessKeyId=AKIAIOSFODNN7EXAMPLE",
                networkName = "client_id=YWJjZGVmZ2hpamtsbW5vcA",
                providerName = "Negotiate YWJjZGVmZ2hpamtsbW5vcA",
                serviceName = "localhost.",
            ),
        )
        assertNull(
            LiveSubscriptionSource.create(
                adapterName = "passphrase=hunter2",
                muxName = "encryptionKey=YWJjZGVmZ2hpamtsbW5vcA",
                networkName = "auth_token:YWJjZGVmZ2hpamtsbW5vcA",
                providerName = "SCRAM-SHA-256 cD10bHMtc2VydmVy",
                serviceName = "${"a".repeat(64)}.com",
            ),
        )
        val humanLabels = requireNotNull(
            LiveSubscriptionSource.create(
                adapterName = "Silicon Labs Si2168 : DVB-T #0",
                muxName = "Mux 11778V",
                networkName = "\u092d\u093e\u0930\u0924 \u0928\u0947\u091f\u0935\u0930\u094d\u0915",
                providerName = "Trusted Provider",
                serviceName = "News/Sports",
            ),
        )
        assertEquals("Silicon Labs Si2168 : DVB-T #0", humanLabels.adapterName)
        assertEquals("\u092d\u093e\u0930\u0924 \u0928\u0947\u091f\u0935\u0930\u094d\u0915", humanLabels.networkName)
        assertEquals("News/Sports", humanLabels.serviceName)
        val boundary = requireNotNull(
            LiveSubscriptionSource.create(
                adapterName = "x".repeat(256),
                muxName = "\u0000hidden",
                networkName = "api key=secret",
                providerName = "Cafe\u0301",
                serviceName = null,
            ),
        )
        assertEquals(256, boundary.adapterName?.length)
        assertTrue(boundary.muxName == null, "Control-bearing mux label was exposed")
        assertTrue(boundary.networkName == null, "Credential-shaped network label was exposed")
        assertEquals("Caf\u00e9", boundary.providerName)
    }

    @Test
    fun `new generation and terminal events clear stale observations`() {
        val queued = LiveSubscriptionDiagnostics.update(
            null,
            SubscriptionEvent.Queue(1L, 2L, null, 3L, 4L, 5L),
        )
        assertEquals(1L, queued?.queue?.packetCount)

        val restarted = LiveSubscriptionDiagnostics.update(
            queued,
            SubscriptionEvent.Started(
                streams = null,
                codecMetadata = null,
                condition = SubscriptionCondition.NO_DETAIL,
            ),
        )
        assertNull(restarted)
        assertNull(
            LiveSubscriptionDiagnostics.update(
                queued,
                SubscriptionEvent.Stopped(SubscriptionCondition.ERROR_REPORTED),
            ),
        )
        assertNull(
            LiveSubscriptionDiagnostics.update(
                queued,
                SubscriptionEvent.Terminated(SubscriptionTermination.GENERATION_LOST),
            ),
        )
    }

    @Test
    fun `invalid or absent observations remain absent`() {
        assertNull(LiveSubscriptionSource.create(" ", null, null, null, null))
        assertNull(
            LiveSubscriptionDiagnostics.update(
                null,
                SubscriptionEvent.Signal(
                    relativeSnr = 65_536L,
                    absoluteSnr = null,
                    relativeSignal = -1L,
                    absoluteSignal = null,
                    bitErrorRate = -1L,
                    uncorrectedBlockCount = 0x1_0000_0000L,
                    frontendStatusReported = true,
                ),
            ),
        )

        val queue = requireNotNull(
            LiveSubscriptionDiagnostics.update(
                null,
                SubscriptionEvent.Queue(0L, 0L, -1L, 0L, 0L, 0L),
            ),
        ).queue
        assertNull(queue?.mediaSpan)
        assertNull(queue?.mediaSpanMicroseconds)
        val oversizedQueueDelay = requireNotNull(
            LiveSubscriptionDiagnostics.update(
                null,
                SubscriptionEvent.Queue(0L, 0L, Long.MAX_VALUE, 0L, 0L, 0L),
            ),
        ).queue
        assertNull(oversizedQueueDelay?.mediaSpan)
        assertNull(oversizedQueueDelay?.mediaSpanMicroseconds)

        val populated = requireNotNull(
            LiveSubscriptionDiagnostics.update(
                LiveSubscriptionDiagnostics.update(
                    null,
                    SubscriptionEvent.Queue(1L, 2L, 3L, 4L, 5L, 6L),
                ),
                SubscriptionEvent.Signal(
                    relativeSnr = 32_768L,
                    absoluteSnr = null,
                    relativeSignal = null,
                    absoluteSignal = null,
                    bitErrorRate = null,
                    uncorrectedBlockCount = null,
                    frontendStatusReported = true,
                    frontendState = LiveFrontendState.create(true, true, true),
                ),
            ),
        )
        val cleared = requireNotNull(
            LiveSubscriptionDiagnostics.update(
                populated,
                SubscriptionEvent.Signal(
                    relativeSnr = null,
                    absoluteSnr = null,
                    relativeSignal = null,
                    absoluteSignal = null,
                    bitErrorRate = null,
                    uncorrectedBlockCount = null,
                    frontendStatusReported = true,
                ),
            ),
        )
        assertNull(cleared.frontend)
        assertEquals(populated.queue, cleared.queue)
        assertThrows(IllegalArgumentException::class.java) {
            LiveFrontendState.create(
                signalDetected = false,
                partiallySynchronized = true,
                locked = false,
            )
        }
    }
}
