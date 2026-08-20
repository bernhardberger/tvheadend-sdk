package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayServerFacts
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import kotlinx.coroutines.CompletableDeferred

internal interface SessionMetadata {
    public fun resetWorkingStateRetainingPublishedSnapshot()

    public fun bindGeneration(generation: GatewayGeneration)

    public fun applyDvrAccess(generation: GatewayGeneration, access: Boolean?)

    public fun publishServerFacts(generation: GatewayGeneration, facts: GatewayServerFacts)

    public fun acceptMetadata(event: MetadataEvent)

    public suspend fun awaitChannelsAndTagsCurrent(generation: GatewayGeneration)

    public fun capabilities(generation: GatewayGeneration): ServerCapabilities
}

internal class PhaseOneSessionMetadata : SessionMetadata {
    private val lock = Any()
    private var generation: GatewayGeneration? = null
    private var initialSync = CompletableDeferred<Unit>()
    private var streaming: Boolean? = null
    private var dvrAccess: Boolean? = null

    override fun resetWorkingStateRetainingPublishedSnapshot() {
        synchronized(lock) {
            generation = null
            initialSync = CompletableDeferred()
            streaming = null
            dvrAccess = null
        }
    }

    override fun bindGeneration(generation: GatewayGeneration) {
        synchronized(lock) {
            this.generation = generation
            initialSync = CompletableDeferred()
        }
    }

    override fun applyDvrAccess(generation: GatewayGeneration, access: Boolean?) {
        synchronized(lock) {
            if (this.generation === generation) {
                dvrAccess = access
            }
        }
    }

    override fun publishServerFacts(generation: GatewayGeneration, facts: GatewayServerFacts) {
        synchronized(lock) {
            if (this.generation === generation) {
                streaming = facts.streaming
            }
        }
    }

    override fun acceptMetadata(event: MetadataEvent) {
        synchronized(lock) {
            if (event.generation === generation && event is MetadataEvent.InitialSyncCompleted) {
                initialSync.complete(Unit)
            }
        }
    }

    override suspend fun awaitChannelsAndTagsCurrent(generation: GatewayGeneration) {
        val fence = synchronized(lock) {
            check(this.generation === generation) { "Session generation is not current" }
            initialSync
        }
        fence.await()
    }

    override fun capabilities(generation: GatewayGeneration): ServerCapabilities = synchronized(lock) {
        check(this.generation === generation) { "Session generation is not current" }
        ServerCapabilities(
            streaming = streaming.toCapabilityAccess(),
            dvrWrite = dvrAccess.toCapabilityAccess(),
        )
    }
}

private fun Boolean?.toCapabilityAccess(): CapabilityAccess = when (this) {
    null -> CapabilityAccess.UNKNOWN
    true -> CapabilityAccess.ALLOWED
    false -> CapabilityAccess.DENIED
}

internal interface SessionChildren {
    public fun stopAdmission()

    public suspend fun cancelAndJoinEpgWorker()

    public suspend fun closeAndJoinSubscriptions()

    public data object None : SessionChildren {
        override fun stopAdmission() = Unit

        override suspend fun cancelAndJoinEpgWorker() = Unit

        override suspend fun closeAndJoinSubscriptions() = Unit
    }
}
