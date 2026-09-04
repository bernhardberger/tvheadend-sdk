@file:OptIn(at.bernhardberger.tvheadend.sdk.core.TvheadendTestingApi::class)

package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSnapshot
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.testing.SessionGenerationConformanceSubject
import at.bernhardberger.tvheadend.sdk.core.testing.productionSessionGenerationSubject
import at.bernhardberger.tvheadend.sdk.core.testing.verifySessionGenerationConformance
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

internal class SessionGenerationConformanceTest {
    @Test
    fun `production boundary and fake share generation and cancellation invariants`() = runTest {
        listOf(::productionSessionGenerationSubject, ::fakeSubject).forEach { subject ->
            verifySessionGenerationConformance(subject, ::currentObservation)
        }
    }
}
private fun fakeSubject(): SessionGenerationConformanceSubject {
    val fake = FakeTvheadendSession()
    return generationSubject(fake, fake::publish, fake::replaceGeneration)
}

private fun generationSubject(
    boundary: FakeTvheadendSession,
    publish: (SessionObservation) -> Unit,
    replace: (SessionObservation) -> Unit,
): SessionGenerationConformanceSubject = object : SessionGenerationConformanceSubject {
    override val session: FakeTvheadendSession = boundary
    override fun publish(observation: SessionObservation): Unit = publish.invoke(observation)
    override fun replaceGeneration(observation: SessionObservation): Unit = replace.invoke(observation)
}

private fun currentObservation(): SessionObservation = SessionObservation.create(
    sessionState = SessionState.Ready(
        ServerCapabilities.create(CapabilityAccess.ALLOWED, CapabilityAccess.ALLOWED),
    ),
    channelState = ChannelRepositoryState.Current(ChannelCatalog.create()),
    epgState = EpgRepositoryState.Current(EpgSnapshot.create()),
    dvrState = DvrRepositoryState.Current(DvrSnapshot.create()),
)
