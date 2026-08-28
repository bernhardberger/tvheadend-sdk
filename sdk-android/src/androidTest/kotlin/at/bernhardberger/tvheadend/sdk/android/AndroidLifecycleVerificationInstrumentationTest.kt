@file:Suppress("DEPRECATION")

package at.bernhardberger.tvheadend.sdk.android

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import at.bernhardberger.tvheadend.sdk.core.ServerAuthentication
import at.bernhardberger.tvheadend.sdk.core.ServerProfile
import at.bernhardberger.tvheadend.sdk.core.SessionCommandResult
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.createTvheadendSession
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class AndroidLifecycleVerificationInstrumentationTest {
    @Test(timeout = STORE_TIMEOUT_MS)
    fun stores_credentials_for_process_restart() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val profileFile = File(context.filesDir, PRIVATE_PROFILE_FILE_NAME)
        val markerFile = File(context.filesDir, PROCESS_MARKER_FILE_NAME)
        assumeTrue("Private lifecycle verification is not provisioned", profileFile.isFile)

        val profileText = try {
            profileFile.readText()
        } catch (_: Exception) {
            if (profileFile.exists() && !profileFile.delete()) {
                throw AssertionError("Private lifecycle verification could not be consumed")
            }
            throw AssertionError("Private lifecycle verification could not be read")
        }
        check(profileFile.delete()) { "Private lifecycle verification could not be consumed" }
        val profile = try {
            JSONObject(profileText).let { root ->
                PrivateProfile(
                    host = root.boundedString("host", 255),
                    port = root.getInt("htsp_port").also { port ->
                        require(port in 1..65_535) { "Private verification value is invalid" }
                    },
                    username = root.boundedString("username", 255),
                    password = root.boundedString("password", 1_024),
                )
            }
        } catch (_: Exception) {
            throw AssertionError("Private lifecycle verification is invalid")
        }

        if (markerFile.exists()) {
            check(markerFile.delete()) { "Stale lifecycle marker could not be removed" }
        }
        val store = TvheadendCredentialStore(context)
        assertEquals(CredentialOperationResult.SUCCESS, store.clearPassword())
        assertEquals(
            CredentialOperationResult.SUCCESS,
            store.storePassword(profile.username, profile.password),
        )
        try {
            context.openFileOutput(PROCESS_MARKER_FILE_NAME, Context.MODE_PRIVATE).bufferedWriter().use { writer ->
                writer.write(
                    JSONObject()
                        .put("process", Process.myPid())
                        .put("host", profile.host)
                        .put("port", profile.port)
                        .toString(),
                )
            }
        } catch (_: Exception) {
            val markerRemoved = !markerFile.exists() || markerFile.delete()
            val credentialsCleared = store.clearPassword() == CredentialOperationResult.SUCCESS
            if (!markerRemoved || !credentialsCleared) {
                throw AssertionError("Process-restart marker failure cleanup failed")
            }
            throw AssertionError("Process-restart marker could not be written")
        }

        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("p5_4_stage", "credentials-stored")
                putBoolean("p5_4_profile_consumed", true)
            },
        )
    }

    @Test(timeout = VERIFY_TIMEOUT_MS)
    fun discovers_server_and_loads_credentials_after_process_restart() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = TvheadendCredentialStore(context)
        var connectivityResult: TvheadendConnectivityStatus? = null
        var apiVersion: Int? = null
        var primaryFailure: Throwable? = null

        try {
            assertFalse(
                "One-use lifecycle profile must already be consumed",
                File(context.filesDir, PRIVATE_PROFILE_FILE_NAME).exists(),
            )
            val lifecycle = consumeLifecycleMarker(context.filesDir)
            assertNotEquals("Verification must resume in a new process", lifecycle.process, Process.myPid())

            val loaded = store.loadPassword()
            assertTrue(
                "Encrypted credentials must survive the process restart",
                loaded is CredentialReadResult.Available,
            )
            val authentication = (loaded as CredentialReadResult.Available).authentication

            instrumentation.checkpoint("connectivity-first-started")
            val firstConnectivity = observeConnectivity(context)
            instrumentation.checkpoint("connectivity-first-complete")
            val secondConnectivity = observeConnectivity(context)
            connectivityResult = secondConnectivity
            instrumentation.checkpoint("connectivity-second-complete")
            assertEquals(TvheadendConnectivityStatus.AVAILABLE, firstConnectivity)
            assertEquals(TvheadendConnectivityStatus.AVAILABLE, secondConnectivity)

            verifyDiscoveryLifecycles(context, instrumentation)
            apiVersion = connectToServer(lifecycle.host, lifecycle.port, authentication)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure = try {
                if (store.clearPassword() == CredentialOperationResult.SUCCESS) {
                    null
                } else {
                    AssertionError("Credential cleanup failed")
                }
            } catch (failure: Throwable) {
                failure
            }
            val markerFile = File(context.filesDir, PROCESS_MARKER_FILE_NAME)
            if (markerFile.exists() && !markerFile.delete()) {
                val markerFailure = AssertionError("Process-restart marker cleanup failed")
                val failure = cleanupFailure
                if (failure == null) {
                    cleanupFailure = markerFailure
                } else {
                    failure.addSuppressed(markerFailure)
                }
            }
            if (cleanupFailure != null) {
                val failure = primaryFailure
                if (failure == null) {
                    throw cleanupFailure
                }
                failure.addSuppressed(cleanupFailure)
            }
        }

        assertTrue(
            "Credential cleanup did not produce a missing state",
            store.loadPassword() === CredentialReadResult.Missing,
        )
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("p5_4_stage", "android-lifecycle-passed")
                putBoolean("p5_4_process_restarted", true)
                putString("p5_4_connectivity", checkNotNull(connectivityResult).name)
                putInt("p5_4_discovery_passes", 2)
                putInt("p5_4_server_api_version", apiVersion ?: -1)
                putBoolean("p5_4_credentials_cleared", true)
            },
        )
    }
}

private suspend fun observeConnectivity(context: Context): TvheadendConnectivityStatus =
    withTimeout(CONNECTIVITY_TIMEOUT_MS) {
        TvheadendConnectivity(context).status.first { status ->
            status != TvheadendConnectivityStatus.UNKNOWN
        }
    }

private fun android.app.Instrumentation.checkpoint(value: String) {
    sendStatus(
        0,
        Bundle().apply {
            putString("p5_4_checkpoint", value)
        },
    )
}

private suspend fun verifyDiscoveryLifecycles(
    context: Context,
    instrumentation: android.app.Instrumentation,
) {
    instrumentation.checkpoint("discovery-first-started")
    discoverFixture(context, DISCOVERY_FIXTURE_NAME, DISCOVERY_FIXTURE_PORT)
    instrumentation.checkpoint("discovery-first-complete")
    discoverFixture(context, DISCOVERY_FIXTURE_NAME, DISCOVERY_FIXTURE_PORT)
    instrumentation.checkpoint("discovery-second-complete")
}

private suspend fun discoverFixture(
    context: Context,
    fixtureName: String,
    fixturePort: Int,
): DiscoveredTvheadendServer =
    withTimeout(DISCOVERY_TIMEOUT_MS) {
        val state = TvheadendDiscovery(context).state.first { current ->
            when (current) {
                is TvheadendDiscoveryState.Discovering -> current.servers.any { server ->
                    server.name == fixtureName && server.port == fixturePort
                }
                is TvheadendDiscoveryState.Unavailable ->
                    throw AssertionError("Android NSD discovery failed; failure=${current.failure.name}")
            }
        } as TvheadendDiscoveryState.Discovering
        state.servers.single { server -> server.name == fixtureName && server.port == fixturePort }
    }

private suspend fun connectToServer(
    host: String,
    port: Int,
    authentication: ServerAuthentication.Password,
): Int? {
    val session = createTvheadendSession()
    return try {
        assertEquals(
            SessionCommandResult.STARTED,
            session.connect(
                ServerProfile(
                    host = host,
                    port = port,
                    authentication = authentication,
                ),
            ),
        )
        val state = withTimeout(CONNECTION_TIMEOUT_MS) {
            session.observation.first { current ->
                current.sessionState is SessionState.Ready ||
                    current.sessionState is SessionState.Unavailable
            }
        }.sessionState
        assertTrue(
            "The real server must become ready; state=${state.safeCategory()}",
            state is SessionState.Ready,
        )
        (state as SessionState.Ready).capabilities.apiVersion
    } finally {
        session.shutdown()
    }
}

private fun consumeLifecycleMarker(filesDirectory: File): PrivateLifecycleState {
    val markerFile = File(filesDirectory, PROCESS_MARKER_FILE_NAME)
    assumeTrue("Process-restart lifecycle stage is not provisioned", markerFile.isFile)
    val lifecycle = try {
        JSONObject(markerFile.readText()).let { root ->
            PrivateLifecycleState(
                process = root.getInt("process"),
                host = root.boundedString("host", 255),
                port = root.getInt("port").also { port ->
                    require(port in 1..65_535) { "Private verification value is invalid" }
                },
            )
        }
    } catch (_: Exception) {
        throw AssertionError("Process-restart marker is invalid")
    }
    check(markerFile.delete()) { "Process-restart marker could not be consumed" }
    return lifecycle
}

private fun SessionState.safeCategory(): String = when (this) {
    SessionState.Disconnected -> "Disconnected"
    SessionState.Connecting -> "Connecting"
    SessionState.Synchronizing -> "Synchronizing"
    is SessionState.Ready -> "Ready"
    is SessionState.Unavailable -> "Unavailable:${reason.javaClass.simpleName}"
}

private fun JSONObject.boundedString(name: String, maximumLength: Int): String = getString(name).also { value ->
    require(value.isNotBlank() && value.length <= maximumLength) { "Private verification value is invalid" }
}

private class PrivateProfile(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
) {
    override fun toString(): String = "PrivateProfile(<redacted>)"
}

private class PrivateLifecycleState(
    val process: Int,
    val host: String,
    val port: Int,
) {
    override fun toString(): String = "PrivateLifecycleState(<redacted>)"
}

private const val PRIVATE_PROFILE_FILE_NAME = "p5-4-real-server.json"
private const val PROCESS_MARKER_FILE_NAME = "p5-4-process.marker"
private const val STORE_TIMEOUT_MS = 60_000L
private const val VERIFY_TIMEOUT_MS = 5 * 60 * 1_000L
private const val CONNECTIVITY_TIMEOUT_MS = 15_000L
private const val DISCOVERY_TIMEOUT_MS = 60_000L
private const val CONNECTION_TIMEOUT_MS = 120_000L
private const val DISCOVERY_FIXTURE_NAME = "tvheadend-sdk-p5-4"
private const val DISCOVERY_FIXTURE_PORT = 49_854
