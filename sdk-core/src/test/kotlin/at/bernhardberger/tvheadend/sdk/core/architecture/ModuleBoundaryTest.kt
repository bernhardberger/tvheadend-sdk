package at.bernhardberger.tvheadend.sdk.core.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class ModuleBoundaryTest {
    private val modulePackages = mapOf(
        "sdk-android" to "at.bernhardberger.tvheadend.sdk.android",
        "sdk-core" to "at.bernhardberger.tvheadend.sdk.core",
        "sdk-media3" to "at.bernhardberger.tvheadend.sdk.media3",
        "sdk-playback" to "at.bernhardberger.tvheadend.sdk.playback",
        "sdk-testing" to "at.bernhardberger.tvheadend.sdk.testing",
    )

    @Test
    fun `production sources stay inside their module package`() {
        modulePackages.forEach { (module, packagePrefix) ->
            assertFalse(File("$module/src/main/java").exists(), "$module must use Kotlin production sources")
            val files = productionScope(module).files
            assertFalse(files.isEmpty(), "$module must have production source")
            files.assertTrue { file ->
                val packageName = file.packagee?.name.orEmpty()
                packageName == packagePrefix || packageName.startsWith("$packagePrefix.")
            }
        }
    }

    @Test
    fun `pure JVM modules import no Android or Media3 types`() {
        val forbiddenPrefixes = listOf("android.", "androidx.", "com.android.")
        listOf("sdk-core", "sdk-playback", "sdk-testing").forEach { module ->
            productionScope(module).files.assertTrue { file ->
                forbiddenPrefixes.none(file.text::contains) &&
                    file.imports.none { declaration ->
                        forbiddenPrefixes.any(declaration.name::startsWith)
                    }
            }
        }
    }

    @Test
    fun `only the core gateway implementation may import HTSP`() {
        modulePackages.keys.forEach { module ->
            productionScope(module).files.assertTrue { file ->
                val htspPrefix = "at.bernhardberger.tvheadend.htsp."
                val referencesHtsp = file.text.contains(htspPrefix) || file.imports.any { declaration ->
                    declaration.name.startsWith(htspPrefix)
                }
                val packageName = file.packagee?.name.orEmpty()
                val gatewayPackage = "at.bernhardberger.tvheadend.sdk.core.gateway.htsp"
                !referencesHtsp || packageName == gatewayPackage || packageName.startsWith("$gatewayPackage.")
            }
        }
    }

    @Test
    fun `HTSP implementation contains no public top level SDK declarations`() {
        val publicDeclaration = Regex(
            pattern = "^public\\s+(?:(?:data|sealed)\\s+)*(?:class|interface|object|fun|val|var|typealias)\\b",
            option = RegexOption.MULTILINE,
        )
        productionScope("sdk-core").files
            .filter { file ->
                val packageName = file.packagee?.name.orEmpty()
                packageName == "at.bernhardberger.tvheadend.sdk.core.gateway.htsp" ||
                    packageName.startsWith("at.bernhardberger.tvheadend.sdk.core.gateway.htsp.")
            }
            .assertTrue { file -> !publicDeclaration.containsMatchIn(file.text) }
    }

    @Test
    fun `public SDK type set remains deliberate and reachable from the session API`() {
        val publicType = Regex(
            "public\\s+(?:(?:data|sealed)\\s+)*(?:class|interface|enum\\s+class|object)\\s+(\\w+)",
        )
        val sessionApi = File(
            "src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/TvheadendSession.kt",
        ).readText()
        val actual = publicType.findAll(sessionApi).map { match -> match.groupValues[1] }.toSet()
        val expected = setOf(
            "TvheadendSession",
            "ServerProfile",
            "ServerAuthentication",
            "Anonymous",
            "Password",
            "SessionCommandResult",
            "SessionState",
            "Disconnected",
            "Connecting",
            "Synchronizing",
            "Ready",
            "Unavailable",
            "SessionFailure",
            "AuthenticationRejected",
            "PermissionDenied",
            "ServerUnreachable",
            "NetworkUnavailable",
            "IncompatibleServer",
            "NoChannels",
            "TransportUnavailable",
            "SynchronizationFailed",
            "UnexpectedFailure",
            "SessionOperationFailure",
            "ServerCapabilities",
            "CapabilityAccess",
        )

        assertEquals(expected, actual)
    }

    @Test
    fun `public suspending SDK calls use typed outcomes or lifecycle Unit`() {
        val sessionApi = File(
            "src/main/kotlin/at/bernhardberger/tvheadend/sdk/core/TvheadendSession.kt",
        ).readText().replace(Regex("\\s+"), " ")
        val expectedSignatures = setOf(
            "public suspend fun connect(profile: ServerProfile): SessionCommandResult",
            "public suspend fun retry(): SessionCommandResult",
            "public suspend fun disconnect()",
            "public suspend fun shutdown()",
        )

        assertEquals(4, Regex("public suspend fun ").findAll(sessionApi).count())
        expectedSignatures.forEach { signature ->
            org.junit.jupiter.api.Assertions.assertTrue(
                sessionApi.contains(signature),
                "Missing typed public lifecycle signature",
            )
        }
    }

    private fun productionScope(module: String) =
        Konsist.scopeFromDirectory("$module/src/main/kotlin")
}
