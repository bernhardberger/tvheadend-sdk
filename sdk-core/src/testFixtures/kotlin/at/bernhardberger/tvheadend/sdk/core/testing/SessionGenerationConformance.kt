package at.bernhardberger.tvheadend.sdk.core.testing

import at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionObservationStore
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

public interface SessionGenerationConformanceSubject {
    public val session: TvheadendSession
    public fun publish(observation: SessionObservation)
    public fun replaceGeneration(observation: SessionObservation)
}

public fun productionSessionGenerationSubject(): SessionGenerationConformanceSubject =
    ProductionSessionGenerationSubject()

public suspend fun verifySessionGenerationConformance(
    subjectFactory: () -> SessionGenerationConformanceSubject,
    readyObservation: () -> SessionObservation,
): Unit = coroutineScope {
    val subject = subjectFactory()
    subject.publish(readyObservation())
    val first = requireNotNull(subject.session.observation.value.currentSession)
    subject.publish(readyObservation())
    check(subject.session.observation.value.currentSession === first)
    check(subject.session.isCurrent(first))
    subject.replaceGeneration(readyObservation())
    val replacement = requireNotNull(subject.session.observation.value.currentSession)
    check(!subject.session.isCurrent(first))
    check(first !== replacement)
    check(first.generationIdentity !== replacement.generationIdentity)
    val other = subjectFactory()
    other.publish(readyObservation())
    check(!other.session.isCurrent(replacement))
    val wait = async(start = CoroutineStart.UNDISPATCHED) {
        subject.session.awaitCurrentSession(replaced = replacement)
    }
    val cancellation = CancellationException("fixed conformance cancellation")
    wait.cancel(cancellation)
    val caught = runCatching { wait.await() }.exceptionOrNull()
    check(caught === cancellation || caught?.cause === cancellation)
}

private class ProductionSessionGenerationSubject : SessionGenerationConformanceSubject {
    private val store = SessionObservationStore()
    private var generation: Any = Any()
    override val session: TvheadendSession = sessionFor(store)

    override fun publish(observation: SessionObservation) {
        publish(observation, generation)
    }

    override fun replaceGeneration(observation: SessionObservation) {
        generation = Any()
        publish(observation, generation)
    }

    private fun publish(observation: SessionObservation, generation: Any) {
        require(observation.sessionState is SessionState.Ready)
        store.publishSessionState(
            observation.sessionState,
            observation.recordingProgressCapability,
            generation,
        )
        store.publishMetadata(
            observation.channelState,
            observation.epgState,
            observation.dvrState,
            observation.dvrConfigurationsState,
            observation.dvrDiskSpaceState,
        )
    }
}

private fun sessionFor(store: SessionObservationStore): TvheadendSession =
    Proxy.newProxyInstance(
        TvheadendSession::class.java.classLoader,
        arrayOf(TvheadendSession::class.java),
    ) { proxy, method, arguments ->
        when {
            method.name == "getObservation" -> store.observation
            method.isDefault -> InvocationHandler.invokeDefault(proxy, method, *(arguments ?: emptyArray()))
            else -> error("Unexpected production boundary call")
        }
    } as TvheadendSession
