package at.bernhardberger.tvheadend.sdk.media3

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class AndroidNsdAdvertisementFixtureInstrumentationTest {
    @Test(timeout = TEST_TIMEOUT_MS)
    fun advertises_htsp_fixture_until_stopped() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = checkNotNull(context.getSystemService(NsdManager::class.java))
        val startFile = File(context.filesDir, START_FILE_NAME)
        val stopFile = File(context.filesDir, STOP_FILE_NAME)
        assumeTrue("P5-4 NSD fixture is not provisioned", startFile.isFile)
        check(startFile.delete()) { "NSD fixture start marker could not be consumed" }
        if (stopFile.exists()) {
            check(stopFile.delete()) { "Stale NSD fixture stop marker could not be removed" }
        }
        val registered = CompletableDeferred<NsdServiceInfo>()
        val unregistered = CompletableDeferred<Unit>()
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registered.complete(serviceInfo)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registered.completeExceptionally(AssertionError("NSD fixture registration failed; code=$errorCode"))
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                unregistered.complete(Unit)
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                unregistered.completeExceptionally(AssertionError("NSD fixture cleanup failed; code=$errorCode"))
            }
        }
        ServerSocket(FIXTURE_PORT).use { socket ->
            var registrationSucceeded = false
            var primaryFailure: Throwable? = null
            try {
                manager.registerService(
                    NsdServiceInfo().apply {
                        serviceName = FIXTURE_NAME
                        serviceType = HTSP_SERVICE_TYPE
                        port = socket.localPort
                    },
                    NsdManager.PROTOCOL_DNS_SD,
                    listener,
                )
                val service = withTimeout(REGISTRATION_TIMEOUT_MS) { registered.await() }
                registrationSucceeded = true
                assertEquals(FIXTURE_NAME, service.serviceName)
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString("p5_4_fixture", "registered")
                    },
                )

                withTimeout(ADVERTISE_TIMEOUT_MS) {
                    while (!stopFile.isFile) {
                        delay(STOP_POLL_MS)
                    }
                }
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                stopFile.delete()
                val cleanupFailure = try {
                    if (registrationSucceeded) {
                        manager.unregisterService(listener)
                        withTimeout(REGISTRATION_TIMEOUT_MS) { unregistered.await() }
                    }
                    null
                } catch (failure: Throwable) {
                    failure
                }
                if (cleanupFailure != null) {
                    val failure = primaryFailure
                    if (failure == null) {
                        throw cleanupFailure
                    }
                    failure.addSuppressed(cleanupFailure)
                }
            }
        }
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("p5_4_fixture", "unregistered")
            },
        )
    }
}

private const val FIXTURE_NAME = "tvheadend-sdk-p5-4"
private const val HTSP_SERVICE_TYPE = "_htsp._tcp"
private const val FIXTURE_PORT = 49_854
private const val START_FILE_NAME = "p5-4-start"
private const val STOP_FILE_NAME = "p5-4-stop"
private const val TEST_TIMEOUT_MS = 7 * 60 * 1_000L
private const val ADVERTISE_TIMEOUT_MS = 6 * 60 * 1_000L
private const val REGISTRATION_TIMEOUT_MS = 15_000L
private const val STOP_POLL_MS = 100L
