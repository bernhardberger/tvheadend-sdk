package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.ServerProfileId
import at.bernhardberger.tvheadend.sdk.core.ServerProfileAuthenticationMode
import at.bernhardberger.tvheadend.sdk.core.ServerProfileListResult
import at.bernhardberger.tvheadend.sdk.core.ServerProfileReadResult
import at.bernhardberger.tvheadend.sdk.core.ServerProfileStore
import at.bernhardberger.tvheadend.sdk.core.ServerProfileSummary
import at.bernhardberger.tvheadend.sdk.core.ServerProfileWriteResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Names profile-store calls without including profile fields. */
public enum class FakeServerProfileStoreCall {
    /** Lists stored summaries. */
    PROFILES,
    /** Loads the active profile. */
    LOAD_ACTIVE,
    /** Loads a profile by identifier. */
    LOAD,
    /** Adds anonymous authentication. */
    ADD_ANONYMOUS,
    /** Adds password authentication. */
    ADD_PASSWORD,
    /** Replaces fields with anonymous authentication. */
    UPDATE_ANONYMOUS,
    /** Replaces fields with password authentication. */
    UPDATE_PASSWORD,
    /** Replaces only the display label. */
    UPDATE_LABEL,
    /** Changes the active selection. */
    SET_ACTIVE,
    /** Removes one profile. */
    REMOVE,
    /** Discards the collection and selection. */
    CLEAR_ALL,
}

/** Stores ordered profiles in memory with scripted unavailability and cancellation-safe calls. */
public class FakeServerProfileStore(
    initialResult: ServerProfileReadResult = ServerProfileReadResult.Missing,
    private val idSource: () -> ServerProfileId = ServerProfileId::generate,
) : ServerProfileStore {
    private val lock = Any()
    private val mutableCalls = ArrayList<FakeServerProfileStoreCall>()
    private val records = LinkedHashMap<ServerProfileId, ServerProfileReadResult.Available>()
    private val unavailableCredentials = HashSet<ServerProfileId>()
    private val unknownUsernames = HashSet<ServerProfileId>()
    private var activeId: ServerProfileId? = null
    private var readUnavailable = false
    private var mutationAvailable = true

    init { scriptProfile(initialResult) }

    /** Returns a snapshot of value-free invocation order. */
    public val calls: List<FakeServerProfileStoreCall>
        get() = synchronized(lock) { mutableCalls.toList() }

    /** Replaces the collection with one active result, missing state, or unreadable state. */
    public fun scriptProfile(result: ServerProfileReadResult) {
        synchronized(lock) {
            records.clear()
            unavailableCredentials.clear()
            unknownUsernames.clear()
            activeId = null
            readUnavailable = result == ServerProfileReadResult.Unavailable
            if (result is ServerProfileReadResult.Available) {
                records[result.id] = result
                activeId = result.id
            }
        }
    }

    /** Makes mutations unavailable without changing stored state; clear throws a value-free error. */
    public fun scriptMutationUnavailable() { synchronized(lock) { mutationAvailable = false } }

    /** Restores successful mutations. */
    public fun scriptMutationSuccess() { synchronized(lock) { mutationAvailable = true } }

    /**
     * Makes [id] unreadable while retaining its summary; updating the profile repairs it.
     * [forgetUsername] simulates an unreadable legacy migration with an unknown username.
     * @throws IllegalArgumentException if [id] is missing or uses anonymous authentication
     */
    public fun scriptCredentialsUnavailable(id: ServerProfileId, forgetUsername: Boolean = false) {
        synchronized(lock) {
            require(id in records) { "Profile not found" }
            require(records.getValue(id).authenticationMode == ServerProfileAuthenticationMode.PASSWORD) {
                "Profile has no credentials"
            }
            unavailableCredentials += id
            if (forgetUsername) unknownUsernames += id else unknownUsernames -= id
        }
    }

    override suspend fun profiles(): ServerProfileListResult = call(FakeServerProfileStoreCall.PROFILES) {
        if (readUnavailable) ServerProfileListResult.Unavailable
        else ServerProfileListResult.Available(records.values.map {
            ServerProfileSummary.create(it.id, it.label, it.host, it.port, it.authenticationMode,
                username = it.username.takeUnless { _ -> it.id in unknownUsernames },
                credentialsAvailable = it.id !in unavailableCredentials)
        }, activeId)
    }

    override suspend fun loadActive(): ServerProfileReadResult = call(FakeServerProfileStoreCall.LOAD_ACTIVE) {
        read(activeId)
    }

    override suspend fun load(id: ServerProfileId): ServerProfileReadResult = call(FakeServerProfileStoreCall.LOAD) {
        read(id)
    }

    private fun read(id: ServerProfileId?): ServerProfileReadResult =
        if (readUnavailable || id in unavailableCredentials) ServerProfileReadResult.Unavailable
        else records[id] ?: ServerProfileReadResult.Missing

    override suspend fun addAnonymous(
        host: String, port: Int, label: String?, activate: Boolean,
    ): ServerProfileWriteResult = call(FakeServerProfileStoreCall.ADD_ANONYMOUS) {
        save(ServerProfileReadResult.anonymous(idSource(), host, port, label), true, activate)
    }

    override suspend fun addPassword(
        host: String, port: Int, username: String, password: String, label: String?, activate: Boolean,
    ): ServerProfileWriteResult = call(FakeServerProfileStoreCall.ADD_PASSWORD) {
        save(ServerProfileReadResult.password(idSource(), host, port, username, password, label), true, activate)
    }

    override suspend fun updateAnonymous(
        id: ServerProfileId, host: String, port: Int, label: String?,
    ): ServerProfileWriteResult = call(FakeServerProfileStoreCall.UPDATE_ANONYMOUS) {
        save(ServerProfileReadResult.anonymous(id, host, port, label), false, false)
    }

    override suspend fun updatePassword(
        id: ServerProfileId, host: String, port: Int, username: String, password: String, label: String?,
    ): ServerProfileWriteResult = call(FakeServerProfileStoreCall.UPDATE_PASSWORD) {
        save(ServerProfileReadResult.password(id, host, port, username, password, label), false, false)
    }

    override suspend fun updateLabel(id: ServerProfileId, label: String?): ServerProfileWriteResult =
        call(FakeServerProfileStoreCall.UPDATE_LABEL) {
            val replacement = records[id]?.withLabel(label)
            mutate(id) { records[id] = checkNotNull(replacement) }
        }

    override suspend fun setActive(id: ServerProfileId): ServerProfileWriteResult = call(FakeServerProfileStoreCall.SET_ACTIVE) {
        mutate(id) { activeId = id }
    }

    override suspend fun remove(id: ServerProfileId): ServerProfileWriteResult = call(FakeServerProfileStoreCall.REMOVE) {
        mutate(id) {
            records.remove(id)
            unavailableCredentials.remove(id)
            unknownUsernames.remove(id)
            if (activeId == id) activeId = null
        }
    }

    override suspend fun clearAll(): Unit = call(FakeServerProfileStoreCall.CLEAR_ALL) {
        check(mutationAvailable) { "Profile storage unavailable" }
        records.clear()
        unavailableCredentials.clear()
        unknownUsernames.clear()
        activeId = null
        readUnavailable = false
    }

    private fun save(
        result: ServerProfileReadResult.Available, adding: Boolean, activate: Boolean,
    ): ServerProfileWriteResult {
        if (!mutationAvailable || readUnavailable) return ServerProfileWriteResult.Unavailable
        if (!adding && result.id !in records) return ServerProfileWriteResult.NotFound
        check(!adding || result.id !in records) { "Profile id already exists" }
        records[result.id] = result
        unavailableCredentials.remove(result.id)
        unknownUsernames.remove(result.id)
        if (activate) activeId = result.id
        return ServerProfileWriteResult.Applied(result.id)
    }

    private fun mutate(id: ServerProfileId, operation: () -> Unit): ServerProfileWriteResult {
        if (!mutationAvailable || readUnavailable) return ServerProfileWriteResult.Unavailable
        if (id !in records) return ServerProfileWriteResult.NotFound
        operation()
        return ServerProfileWriteResult.Applied(id)
    }

    private suspend fun <T> call(call: FakeServerProfileStoreCall, operation: () -> T): T {
        currentCoroutineContext().ensureActive()
        return synchronized(lock) {
            mutableCalls += call
            operation()
        }
    }
}
