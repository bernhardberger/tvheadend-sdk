package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.core.gateway.htsp.HtspProtocolGateway
import at.bernhardberger.tvheadend.sdk.core.session.PlaybackSessionChildren
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days

internal class TvheadendSessionFactoryTest {
    @Test
    fun `factory enforces one production owner and releases it after shutdown`() = runTest {
        val first = createTvheadendSession(EpgCoveragePolicy.create(7.days))
        val same = createTvheadendSession()
        assertSame(first, same)

        first.shutdown()
        val replacement = createTvheadendSession()
        try {
            assertNotSame(first, replacement)
        } finally {
            replacement.shutdown()
        }
    }

    @Test
    fun `owner factory fans the same policy out to gateway and worker`() = runTest {
        val policy = EpgCoveragePolicy.create(
            futureHorizon = 7.days,
            maximumRetainedEvents = 123,
        )
        var gatewayPolicy: EpgCoveragePolicy? = null
        var workerPolicy: EpgCoveragePolicy? = null
        val owner = SessionRegistry.createOwner(
            epgCoveragePolicy = policy,
            gatewayFactory = { suppliedPolicy ->
                gatewayPolicy = suppliedPolicy
                HtspProtocolGateway(Dispatchers.Unconfined, suppliedPolicy)
            },
            childrenFactory = { gateway, metadata, settings ->
                workerPolicy = settings.coveragePolicy
                PlaybackSessionChildren(
                    gateway = gateway,
                    metadata = metadata,
                    dispatcher = Dispatchers.Unconfined,
                    epgSettings = settings,
                )
            },
        )

        try {
            assertSame(policy, gatewayPolicy)
            assertSame(policy, workerPolicy)
        } finally {
            owner.shutdown()
        }
    }
}
