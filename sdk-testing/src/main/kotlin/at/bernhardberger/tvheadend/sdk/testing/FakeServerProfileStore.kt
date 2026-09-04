package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.ServerProfileReadResult
import at.bernhardberger.tvheadend.sdk.core.ServerProfileStore

/** Value-free profile-store invocation labels safe for assertions and diagnostics. */
public enum class FakeServerProfileStoreCall {
    LOAD_PROFILE,
    STORE_ANONYMOUS,
    STORE_PASSWORD,
    CLEAR_PROFILE,
}

/** JVM-only profile store with controllable local persistence availability. */
public class FakeServerProfileStore(
    initialResult: ServerProfileReadResult = ServerProfileReadResult.Missing,
) : ServerProfileStore {
    private val lock = Any()
    private val mutableCalls = ArrayList<FakeServerProfileStoreCall>()
    private var storedResult = initialResult
    private var mutationAvailable = true

    /** Snapshot of value-free invocation order. */
    public val calls: List<FakeServerProfileStoreCall>
        get() = synchronized(lock) { mutableCalls.toList() }

    /** Replaces the result returned by subsequent reads. */
    public fun scriptProfile(result: ServerProfileReadResult) {
        synchronized(lock) { storedResult = result }
    }

    /** Makes subsequent mutations return typed unavailable state without changing stored state. */
    public fun scriptMutationUnavailable() {
        synchronized(lock) { mutationAvailable = false }
    }

    /** Makes subsequent mutations normalize and persist their requested state. */
    public fun scriptMutationSuccess() {
        synchronized(lock) { mutationAvailable = true }
    }

    override suspend fun loadProfile(): ServerProfileReadResult = synchronized(lock) {
        mutableCalls += FakeServerProfileStoreCall.LOAD_PROFILE
        storedResult
    }

    override suspend fun storeAnonymous(host: String, port: Int): ServerProfileReadResult {
        val result = ServerProfileReadResult.anonymous(host, port)
        return persist(FakeServerProfileStoreCall.STORE_ANONYMOUS, result)
    }

    override suspend fun storePassword(
        host: String,
        port: Int,
        username: String,
        password: String,
    ): ServerProfileReadResult {
        val result = ServerProfileReadResult.password(host, port, username, password)
        return persist(FakeServerProfileStoreCall.STORE_PASSWORD, result)
    }

    override suspend fun clearProfile(): ServerProfileReadResult =
        persist(FakeServerProfileStoreCall.CLEAR_PROFILE, ServerProfileReadResult.Missing)

    private fun persist(
        call: FakeServerProfileStoreCall,
        result: ServerProfileReadResult,
    ): ServerProfileReadResult = synchronized(lock) {
        mutableCalls += call
        if (!mutationAvailable) return@synchronized ServerProfileReadResult.Unavailable
        storedResult = result
        result
    }
}
